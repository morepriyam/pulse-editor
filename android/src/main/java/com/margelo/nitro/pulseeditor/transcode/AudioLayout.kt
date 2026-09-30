package com.margelo.nitro.pulseeditor.transcode

import androidx.annotation.OptIn
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.UnstableApi

/** Audio processors that bring any input to one layout: channels mixed first, then resampled. */
@OptIn(UnstableApi::class)
internal fun audioProcessors(sampleRate: Int, channels: Int): List<AudioProcessor> {
  val output = channels.coerceIn(1, 8)
  val mixing = ChannelMixingAudioProcessor()
  for (input in 1..8) mixing.putChannelMixingMatrix(mixingMatrix(input, output))
  return listOf(mixing, ResamplingAudioProcessor(sampleRate))
}

/**
 * `input` channels → `output` channels. Media3's built-in coefficients cover only some pairs
 * (not 5.1 → stereo, the iPhone 17 Pro's recording layout), so the rest are given here.
 * Coefficients are one row per input channel, one column per output channel. Decoded channel
 * order is Android's: FL FR FC LFE BL BR.
 */
@OptIn(UnstableApi::class)
private fun mixingMatrix(input: Int, output: Int): ChannelMixingMatrix {
  runCatching { return ChannelMixingMatrix.createForConstantGain(input, output) }
  val m = Array(input) { FloatArray(output) }
  if (input == 6 && output == 2) {
    // ITU-R BS.775 downmix without LFE, scaled so a full-scale signal can't clip.
    val c = 0.7071f
    val scale = 1f / (1f + 2 * c)
    m[0][0] = scale; m[1][1] = scale              // front left / right
    m[2][0] = c * scale; m[2][1] = c * scale      // centre into both
    m[4][0] = c * scale; m[5][1] = c * scale      // surrounds into their side
  } else if (input < output) {
    // Upmix: each input keeps its channel; mono also feeds the right channel.
    for (i in 0 until input) m[i][i] = 1f
    if (input == 1 && output >= 2) m[0][1] = 1f
  } else {
    // Downmix: fold input i into output i % output, averaged.
    for (i in 0 until input) m[i][i % output] = 1f / ((input + output - 1 - i % output) / output)
  }
  return ChannelMixingMatrix(input, output, m.flatMap { it.asList() }.toFloatArray())
}
