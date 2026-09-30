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
 * `input` channels → `output` channels, one row of coefficients per input channel. To stereo (and
 * mono, half of each side) it uses the coefficients iOS's merge applies (measured with a click on
 * each 5.1 channel): front left/right at full level, centre into both sides at −3 dB, surrounds
 * into their own side at −3 dB, LFE left out. Other outputs use Media3's matrices where it has
 * them, else each input keeps its channel (mono feeding both sides).
 */
@OptIn(UnstableApi::class)
private fun mixingMatrix(input: Int, output: Int): ChannelMixingMatrix {
  val m = Array(input) { FloatArray(output) }
  if (output <= 2 && input > 2) {
    for ((i, role) in roles(input).withIndex()) {
      val (left, right) = when (role) {
        Role.LEFT -> 1f to 0f
        Role.RIGHT -> 0f to 1f
        Role.CENTER -> MINUS_3_DB to MINUS_3_DB
        Role.SURROUND_LEFT -> MINUS_3_DB to 0f
        Role.SURROUND_RIGHT -> 0f to MINUS_3_DB
        Role.LFE -> 0f to 0f
      }
      if (output == 2) {
        m[i][0] = left
        m[i][1] = right
      } else {
        m[i][0] = (left + right) / 2
      }
    }
    return ChannelMixingMatrix(input, output, m.flatMap { it.asList() }.toFloatArray())
  }
  runCatching { return ChannelMixingMatrix.createForConstantGain(input, output) }
  for (i in 0 until minOf(input, output)) m[i][i] = 1f
  if (input == 1 && output >= 2) m[0][1] = 1f
  return ChannelMixingMatrix(input, output, m.flatMap { it.asList() }.toFloatArray())
}

private enum class Role { LEFT, RIGHT, CENTER, SURROUND_LEFT, SURROUND_RIGHT, LFE }

private const val MINUS_3_DB = 0.70710677f

/** What each decoded channel is, in Android's channel order for that count. */
private fun roles(channels: Int): List<Role> = when (channels) {
  3 -> listOf(Role.LEFT, Role.RIGHT, Role.CENTER)
  4 -> listOf(Role.LEFT, Role.RIGHT, Role.SURROUND_LEFT, Role.SURROUND_RIGHT)
  5 -> listOf(Role.LEFT, Role.RIGHT, Role.CENTER, Role.SURROUND_LEFT, Role.SURROUND_RIGHT)
  6 -> listOf(Role.LEFT, Role.RIGHT, Role.CENTER, Role.LFE, Role.SURROUND_LEFT, Role.SURROUND_RIGHT)
  7 -> listOf(Role.LEFT, Role.RIGHT, Role.CENTER, Role.LFE, Role.SURROUND_LEFT, Role.SURROUND_RIGHT, Role.CENTER)
  else -> listOf(Role.LEFT, Role.RIGHT, Role.CENTER, Role.LFE, Role.SURROUND_LEFT, Role.SURROUND_RIGHT) +
    List(channels - 6) { if (it % 2 == 0) Role.SURROUND_LEFT else Role.SURROUND_RIGHT }
}
