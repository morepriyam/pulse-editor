package com.margelo.nitro.pulseeditor.transcode

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.margelo.nitro.pulseeditor.Resampler
import com.margelo.nitro.pulseeditor.SampleSink
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * Changes the sample rate with [Resampler] (the band-limited filter extractAudio uses), one per
 * channel. Stands in for SonicAudioProcessor's resampling, which interpolates linearly: taking a
 * 10 kHz tone from 44.1 to 48 kHz, Sonic left an image 21 dB down at 13.9 kHz.
 */
@OptIn(UnstableApi::class)
internal class ResamplingAudioProcessor(private val outputRate: Int) : BaseAudioProcessor() {
  private var resamplers: List<Resampler> = emptyList()
  private var pending: List<Samples> = emptyList()
  private var channel = FloatArray(0)

  override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
    val encoding = inputAudioFormat.encoding
    if (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT) {
      throw UnhandledAudioFormatException(inputAudioFormat)
    }
    if (inputAudioFormat.sampleRate == outputRate) return AudioFormat.NOT_SET
    return AudioFormat(outputRate, inputAudioFormat.channelCount, encoding)
  }

  override fun queueInput(inputBuffer: ByteBuffer) {
    val format = inputAudioFormat
    val float = format.encoding == C.ENCODING_PCM_FLOAT
    val channels = format.channelCount
    val frames = inputBuffer.remaining() / format.bytesPerFrame
    val start = inputBuffer.position()
    val bytesPerSample = format.bytesPerFrame / channels
    if (channel.size < frames) channel = FloatArray(frames)
    for (c in 0 until channels) {
      for (i in 0 until frames) {
        val at = start + (i * channels + c) * bytesPerSample
        channel[i] = if (float) inputBuffer.getFloat(at) else inputBuffer.getShort(at) / 32768f
      }
      resamplers[c].process(channel, 0, frames, pending[c])
    }
    inputBuffer.position(inputBuffer.limit())
    emit()
  }

  /**
   * Only flushes the filters: the pipeline can queue end of stream while this processor's last
   * output is still unread downstream, and writing output now would reuse that buffer. The tail
   * goes out on the next (empty) [queueInput], which the pipeline keeps calling until [isEnded].
   */
  override fun onQueueEndOfStream() {
    resamplers.forEachIndexed { c, resampler -> resampler.finish(pending[c]) }
  }

  override fun isEnded(): Boolean = super.isEnded() && pending.all { it.size == 0 }

  override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
    val format = inputAudioFormat
    resamplers = List(format.channelCount) { Resampler(format.sampleRate, outputRate) }
    pending = List(format.channelCount) { Samples() }
  }

  override fun onReset() {
    resamplers = emptyList()
    pending = emptyList()
  }

  /** Interleaves whatever every channel has ready into the output buffer (only called once the
   * previous output has been read). */
  private fun emit() {
    val frames = pending.minOfOrNull { it.size } ?: 0
    if (frames == 0) return
    val float = outputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
    val out = replaceOutputBuffer(frames * outputAudioFormat.bytesPerFrame)
    for (i in 0 until frames) {
      for (samples in pending) {
        val v = samples[i]
        if (float) out.putFloat(v) else out.putShort((v * 32768f).roundToInt().coerceIn(-32768, 32767).toShort())
      }
    }
    out.flip()
    pending.forEach { it.consume(frames) }
  }

  /** One channel's resampled output, waiting to be interleaved. */
  private class Samples : SampleSink {
    private var data = FloatArray(4096)
    var size = 0
      private set

    override fun add(value: Float) {
      if (size == data.size) data = data.copyOf(data.size * 2)
      data[size++] = value
    }

    operator fun get(i: Int) = data[i]

    fun consume(count: Int) {
      System.arraycopy(data, count, data, 0, size - count)
      size -= count
    }
  }
}
