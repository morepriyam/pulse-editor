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
 * Cuts a clip's audio to exactly its window: skips `skipUs` from the start, then keeps `durationUs`,
 * padding with silence if the audio runs out first. Placed first in a clip's audio effects, after
 * Media3's own speed change, so both are in timeline time.
 *
 * Media3 pads short clip audio but never cuts long audio, and decoded AAC keeps the source's end
 * padding (up to 1024 samples), so without the cut every clip pushed the next clip's audio later.
 * The skip replaces Media3's clipping for audio, which drops whole AAC frames before the start
 * and lands the audio 7–48 ms late (measured: the source's priming, less where the next frame
 * falls, more for the first clip, and a different amount again after a speed change). Audio read
 * from the file's start is exact.
 */
@OptIn(UnstableApi::class)
internal class TrimAudioProcessor(private val skipUs: Long, private val durationUs: Long) : BaseAudioProcessor() {
  private var skipFrames = 0L
  private var keepFrames = 0L
  private var inputDone = false

  override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
    if (!Util.isEncodingLinearPcm(inputAudioFormat.encoding)) throw UnhandledAudioFormatException(inputAudioFormat)
    return inputAudioFormat
  }

  override fun queueInput(inputBuffer: ByteBuffer) {
    val bytesPerFrame = inputAudioFormat.bytesPerFrame
    val available = inputBuffer.remaining() / bytesPerFrame.toLong()
    val skip = min(available, skipFrames)
    skipFrames -= skip
    inputBuffer.position(inputBuffer.position() + (skip * bytesPerFrame).toInt())
    val keep = min(available - skip, keepFrames).toInt()
    if (keep > 0) {
      val limit = inputBuffer.limit()
      inputBuffer.limit(inputBuffer.position() + keep * bytesPerFrame)
      replaceOutputBuffer(keep * bytesPerFrame).put(inputBuffer).flip()
      inputBuffer.limit(limit)
      keepFrames -= keep
    } else if (inputDone && keepFrames > 0) {
      // The audio ran out before the window did: silence, a chunk per call (the pipeline keeps
      // calling until isEnded).
      val frames = min(keepFrames, SILENCE_CHUNK_FRAMES).toInt()
      val out = replaceOutputBuffer(frames * bytesPerFrame)
      repeat(frames * bytesPerFrame) { out.put(0) }
      out.flip()
      keepFrames -= frames
    }
    inputBuffer.position(inputBuffer.limit())
  }

  /** Output is written only from [queueInput]: the pipeline can queue end of stream while this
   * processor's last output is still unread, and writing now would reuse that buffer. */
  override fun onQueueEndOfStream() {
    inputDone = true
  }

  override fun isEnded(): Boolean = super.isEnded() && keepFrames == 0L

  override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
    val rate = inputAudioFormat.sampleRate
    skipFrames = (skipUs * rate / 1_000_000.0).roundToLong()
    keepFrames = (durationUs * rate / 1_000_000.0).roundToLong()
    inputDone = false
  }

  private companion object {
    const val SILENCE_CHUNK_FRAMES = 4096L
  }
}
