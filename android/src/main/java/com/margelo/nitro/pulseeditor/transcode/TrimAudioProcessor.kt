package com.margelo.nitro.pulseeditor.transcode

import androidx.annotation.OptIn
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import java.nio.ByteBuffer
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Ends a clip's audio at the clip's length. Media3 pads a clip's audio with silence when it runs
 * short but never cuts it when it runs long, and decoded AAC keeps the source encoder's end
 * padding (up to 1024 samples), so without this every clip pushes the next clip's audio later.
 * Placed first in a clip's audio effects, after Media3's own trimming and speed change.
 */
@OptIn(UnstableApi::class)
internal class TrimAudioProcessor(private val durationUs: Long) : BaseAudioProcessor() {
  private var framesLeft = 0L

  override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
    if (!Util.isEncodingLinearPcm(inputAudioFormat.encoding)) throw UnhandledAudioFormatException(inputAudioFormat)
    return inputAudioFormat
  }

  override fun queueInput(inputBuffer: ByteBuffer) {
    val bytesPerFrame = inputAudioFormat.bytesPerFrame
    val keep = min(inputBuffer.remaining() / bytesPerFrame.toLong(), framesLeft).toInt()
    if (keep > 0) {
      val limit = inputBuffer.limit()
      inputBuffer.limit(inputBuffer.position() + keep * bytesPerFrame)
      replaceOutputBuffer(keep * bytesPerFrame).put(inputBuffer).flip()
      inputBuffer.limit(limit)
      framesLeft -= keep
    }
    inputBuffer.position(inputBuffer.limit())
  }

  override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
    framesLeft = (durationUs * inputAudioFormat.sampleRate / 1_000_000.0).roundToLong()
  }
}
