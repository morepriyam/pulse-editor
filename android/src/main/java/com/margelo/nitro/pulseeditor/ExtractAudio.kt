package com.margelo.nitro.pulseeditor

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.inspector.MediaExtractorCompat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A file's audio as 16-bit mono PCM at a chosen sample rate, in memory: Media3's extractor feeds
 * the platform decoder, each decoded chunk is mixed to mono and band-limited resampled
 * ([Resampler]) as it arrives, and the result lands in the direct buffer handed to JS.
 * Matches the iOS path: channels summed at -3 dB each, the whole turned down only if that clips.
 */
@OptIn(UnstableApi::class)
object ExtractAudio {
  class Pcm(val data: ByteBuffer, val durationMs: Double)

  private const val TIMEOUT_US = 10_000L

  /** Per-call buffers for [mixToMono] (calls can run concurrently). */
  private class Scratch {
    var shorts = ShortArray(0)
    var floats = FloatArray(0)
  }
  private const val ENCODER_DELAY = "encoder-delay"

  fun read(context: Context, uri: String, sampleRate: Int): Pcm {
    require(sampleRate in 8000..192_000) { "Sample rate $sampleRate is out of range." }
    val extractor = MediaExtractorCompat(context)
    try {
      extractor.setDataSource(mediaUri(uri), 0)
      val track = (0 until extractor.trackCount).firstOrNull {
        extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
      } ?: return Pcm(ByteBuffer.allocateDirect(0), 0.0)
      extractor.selectTrack(track)
      val format = extractor.getTrackFormat(track)
      val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
      val out = FloatSink(((durationUs / 1e6 + 1) * sampleRate).toInt())
      decode(extractor, format, sampleRate, out)
      // The track's duration ends before the encoder's padding does.
      if (durationUs > 0) out.truncate((durationUs * sampleRate / 1_000_000.0).roundToInt())
      return out.toPcm16(sampleRate)
    } finally {
      extractor.release()
    }
  }

  private fun decode(extractor: MediaExtractorCompat, format: MediaFormat, sampleRate: Int, out: FloatSink) {
    val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
    try {
      codec.configure(format, null, null, 0)
      codec.start()
      var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
      var inputRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
      var encoding = AudioFormat.ENCODING_PCM_16BIT
      // The encoder's priming samples, which the container says to drop (Media3 sets the key on
      // every API level; the MediaFormat constant is API 30+). Some decoders drop them already,
      // so what's left to skip is read off the first decoded buffer's timestamp. Opus decoders
      // always drop the stream's pre-skip themselves (from its header), so nothing is left.
      val opus = format.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_OPUS
      val delayFrames = if (!opus && format.containsKey(ENCODER_DELAY)) format.getInteger(ENCODER_DELAY) else 0
      var firstInputUs = -1L
      var skipFrames = -1
      var resampler = Resampler(inputRate, sampleRate)
      var mono = FloatArray(0)
      val scratch = Scratch()
      val info = MediaCodec.BufferInfo()
      var inputDone = false
      while (true) {
        // Queue input without waiting, and wait for output only when no input could be queued:
        // waiting on both every turn spent most of the time idle (24 s of AAC took ~2 s on the
        // emulator).
        var queued = false
        if (!inputDone) {
          val index = codec.dequeueInputBuffer(0)
          if (index >= 0) {
            queued = true
            val size = extractor.readSampleData(codec.getInputBuffer(index)!!, 0)
            if (size < 0) {
              codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
              inputDone = true
            } else {
              if (firstInputUs < 0) firstInputUs = extractor.sampleTime
              codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
              extractor.advance()
            }
          }
        }
        val index = codec.dequeueOutputBuffer(info, if (queued) 0 else TIMEOUT_US)
        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
          val f = codec.outputFormat
          channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
          encoding = if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) f.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
          val rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
          if (rate != inputRate) {
            inputRate = rate
            resampler = Resampler(inputRate, sampleRate)
          }
        } else if (index >= 0) {
          val buffer = codec.getOutputBuffer(index)!!.order(ByteOrder.nativeOrder())
          buffer.position(info.offset).limit(info.offset + info.size)
          val bytesPerSample = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
          val frames = info.size / (bytesPerSample * channels)
          if (skipFrames < 0 && frames > 0) {
            val decodedFrom = ((info.presentationTimeUs - max(firstInputUs, 0L)) * inputRate / 1_000_000.0).roundToInt()
            skipFrames = (delayFrames - decodedFrom).coerceIn(0, delayFrames)
          }
          val skip = minOf(max(skipFrames, 0), frames)
          skipFrames -= skip
          if (frames - skip > 0) {
            if (mono.size < frames) mono = FloatArray(frames)
            mixToMono(buffer, encoding, channels, frames, mono, scratch)
            resampler.process(mono, skip, frames - skip, out)
          }
          codec.releaseOutputBuffer(index, false)
          if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
        }
      }
      resampler.finish(out)
    } finally {
      codec.release()
    }
  }

  /** Interleaved PCM → mono: channels summed at -3 dB each (as Apple's converter does). */
  private fun mixToMono(buffer: ByteBuffer, encoding: Int, channels: Int, frames: Int, mono: FloatArray, scratch: Scratch) {
    val gain = if (channels == 1) 1f else 0.70710677f
    // One bulk copy out of the codec buffer, then plain array reads (per-sample get() on the
    // buffer view cost ~6x the resampler).
    val count = frames * channels
    if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
      if (scratch.floats.size < count) scratch.floats = FloatArray(count)
      val floats = scratch.floats
      buffer.asFloatBuffer().get(floats, 0, count)
      for (i in 0 until frames) {
        var sum = 0f
        for (c in 0 until channels) sum += floats[i * channels + c]
        mono[i] = sum * gain
      }
    } else {
      if (scratch.shorts.size < count) scratch.shorts = ShortArray(count)
      val shorts = scratch.shorts
      buffer.asShortBuffer().get(shorts, 0, count)
      val scale = gain / 32768f
      for (i in 0 until frames) {
        var sum = 0
        for (c in 0 until channels) sum += shorts[i * channels + c]
        mono[i] = sum * scale
      }
    }
  }

  /** Growable float buffer for the resampled output. */
  class FloatSink(initialCapacity: Int) : SampleSink {
    private var samples = FloatArray(max(4096, initialCapacity))
    private var count = 0

    fun truncate(length: Int) {
      if (length < count) count = max(0, length)
    }

    override fun add(value: Float) {
      if (count == samples.size) samples = samples.copyOf(samples.size * 2)
      samples[count++] = value
    }

    /** Float → 16-bit little-endian, turned down as a whole only when the mix went past full scale. */
    fun toPcm16(sampleRate: Int): Pcm {
      var peak = 0f
      for (i in 0 until count) peak = max(peak, abs(samples[i]))
      val gain = Short.MAX_VALUE / max(peak, 1f)
      val data = ByteBuffer.allocateDirect(count * 2).order(ByteOrder.LITTLE_ENDIAN)
      for (i in 0 until count) data.putShort((samples[i] * gain).roundToInt().toShort())
      data.flip()
      return Pcm(data, (count * 1000.0 / sampleRate).roundToInt().toDouble())
    }
  }
}
