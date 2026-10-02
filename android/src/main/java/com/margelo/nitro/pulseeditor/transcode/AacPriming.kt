package com.margelo.nitro.pulseeditor.transcode

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.metrics.LogSessionId
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Codec
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * An AAC encoder's real priming: how many samples come out before the first input sample. The
 * output's edit list has to say exactly this for players to start the audio in sync. Media3 only
 * knows one encoder (c2.android.aac.encoder, where it assumes FDK's 1600-sample algorithmic
 * delay, yet the stream carries 2048 on the emulator: audio 9.4 ms late) and assumes 0 for every
 * other encoder. So it's measured, once per encoder and layout: a short chirp through the
 * encoder, decoded back with no delay information, located by cross-correlation (a chirp has one
 * sharp correlation peak; a pure tone's runner-up, one period away, scored 0.9 of the peak).
 *
 * It runs inside Media3's encoder factory, on its thread, so it's bounded: 2 s at most, and a
 * failed measurement is remembered (Media3's own value is used) instead of retried every export.
 */
internal object AacPriming {
  private const val TIMEOUT_US = 10_000L
  private const val DEADLINE_NS = 2_000_000_000L
  private const val FAILED = -1
  private val measured = ConcurrentHashMap<String, Int>()

  /** The encoder's priming in samples, or null when it couldn't be measured. */
  fun samples(encoderName: String, sampleRate: Int, channels: Int): Int? {
    val key = "$encoderName/$sampleRate/$channels"
    val priming = measured.getOrPut(key) {
      // Any AAC encoder primes at least one 1024-sample frame; a smaller result means the decoder
      // dropped the priming itself, so it can't be trusted.
      val result = runCatching { measure(encoderName, sampleRate, channels, System.nanoTime() + DEADLINE_NS) }
      val samples = result.getOrNull()?.takeIf { it in 1024..8192 }
      Log.i(
        "PulseEditor",
        if (samples != null) "AAC priming $key: $samples samples"
        else "AAC priming $key: not measured (${result.exceptionOrNull()?.message ?: "got ${result.getOrNull()}"}), Media3's value is used",
      )
      samples ?: FAILED
    }
    return priming.takeIf { it != FAILED }
  }

  private fun measure(encoderName: String, sampleRate: Int, channels: Int, deadline: Long): Int {
    val burstAt = sampleRate / 4
    // 10 ms linear chirp, 500 Hz → 5 kHz, Hann-windowed.
    val length = sampleRate / 100
    val burst = FloatArray(length) { i ->
      val t = i.toDouble() / sampleRate
      val sweep = 4500.0 / (length.toDouble() / sampleRate)
      val window = 0.5 - 0.5 * cos(2 * PI * i / (length - 1))
      (0.5 * window * sin(2 * PI * (500 * t + sweep * t * t / 2))).toFloat()
    }
    val input = ShortArray(sampleRate / 2 * channels)
    for (i in burst.indices) for (c in 0 until channels) input[(burstAt + i) * channels + c] = (burst[i] * 32767).toInt().toShort()
    val (csd, frames) = encode(encoderName, sampleRate, channels, input, deadline)
    val decoded = decode(sampleRate, channels, csd, frames, deadline)
    // The lag at which the decoded audio best matches the burst.
    var best = 0
    var bestScore = Double.NEGATIVE_INFINITY
    for (lag in 0..minOf(8192, decoded.size - burstAt - burst.size)) {
      var score = 0.0
      for (i in burst.indices) score += burst[i] * decoded[burstAt + lag + i]
      if (score > bestScore) { bestScore = score; best = lag }
    }
    return best
  }

  private fun encode(name: String, sampleRate: Int, channels: Int, pcm: ShortArray, deadline: Long): Pair<ByteBuffer, List<ByteArray>> {
    val codec = MediaCodec.createByCodecName(name)
    try {
      val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
      }
      codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
      codec.start()
      val bytes = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.nativeOrder())
      bytes.asShortBuffer().put(pcm)
      var csd: ByteBuffer? = null
      val frames = mutableListOf<ByteArray>()
      val info = MediaCodec.BufferInfo()
      var inputDone = false
      var framesIn = 0L
      while (true) {
        check(System.nanoTime() < deadline) { "AAC priming measurement timed out." }
        if (!inputDone) {
          val index = codec.dequeueInputBuffer(TIMEOUT_US)
          if (index >= 0) {
            val buffer = codec.getInputBuffer(index)!!
            val size = minOf(buffer.remaining(), bytes.remaining())
            val ptsUs = framesIn * 1_000_000 / sampleRate
            if (size == 0) {
              codec.queueInputBuffer(index, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
              inputDone = true
            } else {
              val slice = bytes.duplicate().apply { limit(position() + size) }
              buffer.put(slice)
              bytes.position(bytes.position() + size)
              codec.queueInputBuffer(index, 0, size, ptsUs, 0)
              framesIn += size / (2 * channels)
            }
          }
        }
        val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
          csd = codec.outputFormat.getByteBuffer("csd-0")
        } else if (index >= 0) {
          val out = codec.getOutputBuffer(index)!!
          val data = ByteArray(info.size).also { out.position(info.offset); out.get(it) }
          if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) csd = ByteBuffer.wrap(data) else if (info.size > 0) frames += data
          codec.releaseOutputBuffer(index, false)
          if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
        }
      }
      return (csd ?: error("The AAC encoder gave no codec config.")) to frames
    } finally {
      codec.release()
    }
  }

  /** Decodes every frame, priming included (the format carries no encoder delay), to channel 0. */
  private fun decode(sampleRate: Int, channels: Int, csd: ByteBuffer, frames: List<ByteArray>, deadline: Long): FloatArray {
    val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    try {
      val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
        setByteBuffer("csd-0", csd)
      }
      codec.configure(format, null, null, 0)
      codec.start()
      val out = ArrayList<Float>(sampleRate)
      val info = MediaCodec.BufferInfo()
      var next = 0
      var outChannels = channels
      while (true) {
        check(System.nanoTime() < deadline) { "AAC priming measurement timed out." }
        if (next <= frames.size) {
          val index = codec.dequeueInputBuffer(TIMEOUT_US)
          if (index >= 0) {
            if (next == frames.size) {
              codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            } else {
              codec.getInputBuffer(index)!!.put(frames[next])
              codec.queueInputBuffer(index, 0, frames[next].size, next * 1024L * 1_000_000 / sampleRate, 0)
            }
            next++
          }
        }
        val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
          outChannels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        } else if (index >= 0) {
          val samples = codec.getOutputBuffer(index)!!.order(ByteOrder.nativeOrder()).apply {
            position(info.offset); limit(info.offset + info.size)
          }.asShortBuffer()
          for (i in 0 until samples.remaining() / outChannels) out += samples.get(i * outChannels) / 32768f
          codec.releaseOutputBuffer(index, false)
          if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
        }
      }
      return out.toFloatArray()
    } finally {
      codec.release()
    }
  }
}

/**
 * Media3's encoder factory, with each AAC encoder's output format carrying its measured priming
 * ([AacPriming]) as the encoder delay, so the muxer writes the edit list players need.
 */
@OptIn(UnstableApi::class, ExperimentalApi::class)
internal class PrimingCorrectedEncoderFactory(
  private val encoders: Codec.EncoderFactory,
  private val copyVideo: Boolean = false,
) : Codec.EncoderFactory {
  override fun createForAudioEncoding(format: Format, logSessionId: LogSessionId?): Codec {
    // Media3 fills each encoder input buffer before queueing it, and every queued buffer is a
    // round trip to the codec's process (the default holds ~40 ms of PCM). In a join, where the
    // audio is the only encoding, a larger buffer made exports 21-34% faster on the S24; with
    // video also encoding, the bursts slowed exports by 5-15%, so encodes keep the default.
    val requested = if (copyVideo) format.buildUpon().setMaxInputSize(JOIN_AUDIO_INPUT_BYTES).build() else format
    val codec = encoders.createForAudioEncoding(requested, logSessionId)
    if (codec.configurationFormat.sampleMimeType != MimeTypes.AUDIO_AAC) return codec
    val priming = AacPriming.samples(codec.name, format.sampleRate, format.channelCount) ?: return codec
    return PrimedCodec(codec, priming)
  }

  // Media3 encodes an SDR source in its own colour description, so a full-range one (screen
  // recordings, some messaging apps) came out full range, and a BT.601 one BT.601. Pulse's
  // recordings and iOS exports are BT.709 limited range, which every output is now encoded as.
  override fun createForVideoEncoding(format: Format, logSessionId: LogSessionId?): Codec {
    val sdr = format.colorInfo?.let { !ColorInfo.isTransferHdr(it) } ?: true
    val requested = if (sdr) format.buildUpon().setColorInfo(ColorInfo.SDR_BT709_LIMITED).build() else format
    return encoders.createForVideoEncoding(requested, logSessionId)
  }

  override fun isVideoFormatSupported(format: Format) = encoders.isVideoFormatSupported(format)

  override fun audioNeedsEncoding() = encoders.audioNeedsEncoding()

  private companion object {
    const val JOIN_AUDIO_INPUT_BYTES = 256 * 1024
  }

  // A one-clip composition ignores setTransmuxVideo and asks this instead, and the default factory
  // says yes whenever encoder settings are given: a one-clip "join" was re-encoded (S24: 5.2 Mbps
  // out of a 0.7 Mbps clip). A join says no; an encode keeps the default.
  override fun videoNeedsEncoding() = !copyVideo && encoders.videoNeedsEncoding()

  private class PrimedCodec(private val codec: Codec, private val priming: Int) : Codec by codec {
    override fun getOutputFormat(): Format? = codec.outputFormat?.buildUpon()?.setEncoderDelay(priming)?.build()

    override fun getMaxPendingFrameCount() = codec.maxPendingFrameCount
  }
}
