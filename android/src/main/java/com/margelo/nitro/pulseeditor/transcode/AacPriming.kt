package com.margelo.nitro.pulseeditor.transcode

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.metrics.LogSessionId
import androidx.annotation.OptIn
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

/**
 * An AAC encoder's real priming: how many samples come out before the first input sample. The
 * output's edit list has to say exactly this for players to start the audio in sync. Media3 only
 * knows one encoder (c2.android.aac.encoder, where it assumes FDK's 1600-sample algorithmic
 * delay, yet the stream carries 2048 on the emulator: audio 9.4 ms late) and assumes 0 for every
 * other encoder. So it's measured, once per encoder and layout: a short tone burst through the
 * encoder, decoded back with no delay information, located by cross-correlation.
 */
internal object AacPriming {
  private const val TIMEOUT_US = 10_000L
  private val measured = ConcurrentHashMap<String, Int>()

  /** The encoder's priming in samples, or null when it couldn't be measured. */
  fun samples(encoderName: String, sampleRate: Int, channels: Int): Int? {
    val key = "$encoderName/$sampleRate/$channels"
    measured[key]?.let { return it }
    // Any AAC encoder primes at least one 1024-sample frame; a smaller result means the decoder
    // dropped the priming itself, so it can't be trusted.
    return runCatching { measure(encoderName, sampleRate, channels) }.getOrNull()
      ?.takeIf { it in 1024..8192 }
      ?.also { measured[key] = it }
  }

  private fun measure(encoderName: String, sampleRate: Int, channels: Int): Int {
    val burstAt = sampleRate / 4
    val burst = FloatArray(sampleRate / 100) { i -> (0.5 * cos(2 * PI * 1000 * i / sampleRate)).toFloat() }
    val input = ShortArray(sampleRate / 2 * channels)
    for (i in burst.indices) for (c in 0 until channels) input[(burstAt + i) * channels + c] = (burst[i] * 32767).toInt().toShort()
    val (csd, frames) = encode(encoderName, sampleRate, channels, input)
    val decoded = decode(sampleRate, channels, csd, frames)
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

  private fun encode(name: String, sampleRate: Int, channels: Int, pcm: ShortArray): Pair<ByteBuffer, List<ByteArray>> {
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
  private fun decode(sampleRate: Int, channels: Int, csd: ByteBuffer, frames: List<ByteArray>): FloatArray {
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
internal class PrimingCorrectedEncoderFactory(private val encoders: Codec.EncoderFactory) : Codec.EncoderFactory {
  override fun createForAudioEncoding(format: Format, logSessionId: LogSessionId?): Codec {
    val codec = encoders.createForAudioEncoding(format, logSessionId)
    if (codec.configurationFormat.sampleMimeType != MimeTypes.AUDIO_AAC) return codec
    val priming = AacPriming.samples(codec.name, format.sampleRate, format.channelCount) ?: return codec
    return PrimedCodec(codec, priming)
  }

  override fun createForVideoEncoding(format: Format, logSessionId: LogSessionId?): Codec =
    encoders.createForVideoEncoding(format, logSessionId)

  override fun isVideoFormatSupported(format: Format) = encoders.isVideoFormatSupported(format)

  override fun audioNeedsEncoding() = encoders.audioNeedsEncoding()

  override fun videoNeedsEncoding() = encoders.videoNeedsEncoding()

  private class PrimedCodec(private val codec: Codec, private val priming: Int) : Codec by codec {
    override fun getOutputFormat(): Format? = codec.outputFormat?.buildUpon()?.setEncoderDelay(priming)?.build()

    override fun getMaxPendingFrameCount() = codec.maxPendingFrameCount
  }
}
