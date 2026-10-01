package com.margelo.nitro.pulseeditor

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.media.MediaFormat
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.inspector.MediaExtractorCompat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
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

  private const val BATCH_INPUT_BYTES = 64 * 1024
  private const val BATCH_OUTPUT_BYTES = 256 * 1024

  /**
   * Fills one input buffer with as many access units as fit, queued together. Returns true when
   * the stream ended (end of stream is queued, alone or after the last units).
   */
  @RequiresApi(35)
  private fun queueBatch(codec: MediaCodec, index: Int, extractor: MediaExtractorCompat, firstUs: (Long) -> Unit): Boolean {
    val buffer = codec.getInputBuffer(index)!!
    val infos = ArrayDeque<MediaCodec.BufferInfo>()
    var offset = 0
    var ended = false
    while (true) {
      val next = extractor.sampleSize
      if (next < 0) { ended = true; break }
      if (offset + next > buffer.capacity()) {
        check(offset > 0) { "An audio frame is larger than the decoder's input buffer." }
        break
      }
      val size = extractor.readSampleData(buffer, offset)
      if (size < 0) { ended = true; break }
      firstUs(extractor.sampleTime)
      infos.add(MediaCodec.BufferInfo().apply { set(offset, size, extractor.sampleTime, 0) })
      offset += size
      extractor.advance()
    }
    if (ended) {
      if (infos.isEmpty()) {
        codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        return true
      }
      infos.last.flags = MediaCodec.BUFFER_FLAG_END_OF_STREAM
    }
    codec.queueInputBuffers(index, infos)
    return ended
  }

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
    val mime = format.getString(MediaFormat.KEY_MIME)!!
    val codec = MediaCodec.createDecoderByType(mime)
    try {
      // The encoder's priming samples, which the container says to drop (Media3 sets the key on
      // every API level; the MediaFormat constant is API 30+). Some decoders drop them already,
      // so what's left to skip is read off the first decoded buffer's timestamp. Opus decoders
      // always drop the stream's pre-skip themselves (from its header), so nothing is left.
      val opus = mime == MediaFormat.MIMETYPE_AUDIO_OPUS
      val delayFrames = if (!opus && format.containsKey(ENCODER_DELAY)) format.getInteger(ENCODER_DELAY) else 0
      val batched = Build.VERSION.SDK_INT >= 35 &&
        codec.codecInfo.getCapabilitiesForType(mime).isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_MultipleFrames)
      // A batching decoder drops the priming itself (its first output is timestamped before the
      // first input), so skipping it again put the audio a frame early (S24: 21.3 ms).
      val sink = PcmSink(format, sampleRate, if (batched) 0 else delayFrames, out)
      // Large audio frames (Android 15+, async only): many access units per input buffer and many
      // decoded frames per output buffer, so far fewer round trips to the codec's process, which
      // is where the time goes (S24: ~1.5-2.5 ms per AAC frame one at a time).
      if (batched && Build.VERSION.SDK_INT >= 35) {
        decodeBatched(codec, extractor, format, sink)
      } else {
        decodeOneByOne(codec, extractor, format, sink)
      }
      sink.finish()
    } finally {
      codec.release()
    }
  }

  /** One access unit per buffer, synchronously, keeping the codec as full as it'll take. */
  private fun decodeOneByOne(codec: MediaCodec, extractor: MediaExtractorCompat, format: MediaFormat, sink: PcmSink) {
    codec.configure(format, null, null, 0)
    codec.start()
    val info = MediaCodec.BufferInfo()
    var inputDone = false
    var outputDone = false
    while (!outputDone) {
      var progressed = false
      while (!inputDone) {
        val index = codec.dequeueInputBuffer(0)
        if (index < 0) break
        progressed = true
        val size = extractor.readSampleData(codec.getInputBuffer(index)!!, 0)
        if (size < 0) {
          codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
          inputDone = true
        } else {
          sink.inputStarted(extractor.sampleTime)
          codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
          extractor.advance()
        }
      }
      while (!outputDone) {
        val index = codec.dequeueOutputBuffer(info, if (progressed) 0 else TIMEOUT_US)
        if (index == MediaCodec.INFO_TRY_AGAIN_LATER) break
        progressed = true
        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
          sink.formatChanged(codec.outputFormat)
          continue
        }
        if (index < 0) continue
        sink.consume(codec.getOutputBuffer(index)!!, info.offset, info.size, info.presentationTimeUs)
        codec.releaseOutputBuffer(index, false)
        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
      }
    }
  }

  /** Many access units per buffer, through the codec's callbacks (the only mode that batches). */
  @RequiresApi(35)
  private fun decodeBatched(codec: MediaCodec, extractor: MediaExtractorCompat, format: MediaFormat, sink: PcmSink) {
    format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, BATCH_INPUT_BYTES)
    format.setInteger(MediaFormat.KEY_BUFFER_BATCH_MAX_OUTPUT_SIZE, BATCH_OUTPUT_BYTES)
    format.setInteger(MediaFormat.KEY_BUFFER_BATCH_THRESHOLD_OUTPUT_SIZE, BATCH_OUTPUT_BYTES / 2)
    val thread = HandlerThread("pulse-editor-extract-audio").apply { start() }
    val done = CountDownLatch(1)
    var failure: Throwable? = null
    var inputDone = false
    fun fail(e: Throwable) {
      if (failure == null) failure = e
      done.countDown()
    }
    fun output(c: MediaCodec, index: Int, infos: List<MediaCodec.BufferInfo>) {
      try {
        val first = infos.first()
        sink.consume(c.getOutputBuffer(index)!!, first.offset, infos.sumOf { it.size }, first.presentationTimeUs)
        c.releaseOutputBuffer(index, false)
        if (infos.any { it.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 }) done.countDown()
      } catch (e: Exception) {
        fail(e)
      }
    }
    try {
      // Every callback runs on `thread`, one at a time, so the extractor and sink see one caller.
      codec.setCallback(object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(c: MediaCodec, index: Int) {
          if (inputDone) return
          try {
            inputDone = queueBatch(c, index, extractor) { sink.inputStarted(it) }
          } catch (e: Exception) {
            fail(e)
          }
        }

        override fun onOutputBufferAvailable(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) =
          output(c, index, listOf(info))

        override fun onOutputBuffersAvailable(c: MediaCodec, index: Int, infos: ArrayDeque<MediaCodec.BufferInfo>) =
          output(c, index, infos.toList())

        override fun onOutputFormatChanged(c: MediaCodec, f: MediaFormat) = sink.formatChanged(f)

        override fun onError(c: MediaCodec, e: MediaCodec.CodecException) = fail(e)
      }, Handler(thread.looper))
      codec.configure(format, null, null, 0)
      codec.start()
      done.await()
      failure?.let { throw it }
    } finally {
      runCatching { codec.stop() } // never configured or started when setup failed
      thread.quitSafely()
    }
  }

  /** Decoded PCM of any layout → mono, priming skipped, resampled into [FloatSink]. */
  private class PcmSink(format: MediaFormat, private val sampleRate: Int, private val delayFrames: Int, private val out: FloatSink) {
    private var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
    private var inputRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
    private var encoding = AudioFormat.ENCODING_PCM_16BIT
    private var resampler = Resampler(inputRate, sampleRate)
    private var mono = FloatArray(0)
    private val scratch = Scratch()
    private var firstInputUs = -1L
    private var skipFrames = -1

    fun inputStarted(timeUs: Long) {
      if (firstInputUs < 0) firstInputUs = timeUs
    }

    fun formatChanged(f: MediaFormat) {
      channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
      encoding = if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) f.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
      val rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
      if (rate != inputRate) {
        inputRate = rate
        resampler = Resampler(inputRate, sampleRate)
      }
    }

    fun consume(buffer: ByteBuffer, offset: Int, size: Int, presentationTimeUs: Long) {
      buffer.order(ByteOrder.nativeOrder()).position(offset).limit(offset + size)
      val bytesPerSample = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
      val frames = size / (bytesPerSample * channels)
      if (skipFrames < 0 && frames > 0) {
        val decodedFrom = ((presentationTimeUs - max(firstInputUs, 0L)) * inputRate / 1_000_000.0).roundToInt()
        skipFrames = (delayFrames - decodedFrom).coerceIn(0, delayFrames)
      }
      val skip = minOf(max(skipFrames, 0), frames)
      skipFrames -= skip
      if (frames - skip > 0) {
        if (mono.size < frames) mono = FloatArray(frames)
        mixToMono(buffer, encoding, channels, frames, mono, scratch)
        resampler.process(mono, skip, frames - skip, out)
      }
    }

    fun finish() = resampler.finish(out)
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
