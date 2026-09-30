package com.margelo.nitro.pulseeditor.merge

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.SpeedParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Crop
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.margelo.nitro.pulseeditor.MergeClip
import com.margelo.nitro.pulseeditor.MergeOptions
import com.margelo.nitro.pulseeditor.ProbeResult
import com.margelo.nitro.pulseeditor.Transfer
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * One Media3 Transformer export of the whole timeline: an EditedMediaItemSequence of the clips.
 * - join: `setTransmuxVideo` copies every clip's samples (audio too, unless a clip is muted or
 *   silent: then audio alone is encoded, with generated silence).
 * - encode: each clip is trimmed (ClippingConfiguration), retimed with natural pitch
 *   (SpeedParameters), rotated / flipped / cropped and letterboxed onto the canvas (effects run
 *   on the upright frame), capped at the frame rate, then H.264 at the chosen bitrate.
 * Transformer is driven from the main looper, as Media3 requires one application thread.
 */
@OptIn(UnstableApi::class, ExperimentalApi::class)
object MergeExport {
  suspend fun run(
    context: Context, clips: List<MergeClip>, media: List<ProbeResult>, options: MergeOptions,
    join: Boolean, output: File, progress: (Double) -> Unit,
  ): ExportResult {
    val composition = composition(clips, media, options, join)
    val main = Handler(Looper.getMainLooper())
    return suspendCancellableCoroutine { continuation ->
      var transformer: Transformer? = null
      val poll = object : Runnable {
        override fun run() {
          val t = transformer ?: return
          val holder = ProgressHolder()
          if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) progress(holder.progress / 100.0)
          main.postDelayed(this, 100)
        }
      }
      continuation.invokeOnCancellation {
        main.post {
          main.removeCallbacks(poll)
          transformer?.cancel()
          output.delete()
        }
      }
      main.post {
        if (!continuation.isActive) return@post
        val t = Transformer.Builder(context)
          .setLooper(Looper.getMainLooper())
          .setVideoMimeType(MimeTypes.VIDEO_H264)
          .setAudioMimeType(MimeTypes.AUDIO_AAC)
          .setEncoderFactory(
            DefaultEncoderFactory.Builder(context)
              .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder()
                  .setBitrate(options.bitrate.toInt())
                  .setiFrameIntervalSeconds(2f)
                  .build(),
              )
              .build(),
          )
          .addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, result: ExportResult) {
              main.removeCallbacks(poll)
              if (continuation.isActive) continuation.resume(result)
            }

            override fun onError(composition: Composition, result: ExportResult, exception: ExportException) {
              main.removeCallbacks(poll)
              output.delete()
              if (continuation.isActive) continuation.resumeWithException(exception)
            }
          })
          .build()
        transformer = t
        output.parentFile?.mkdirs()
        output.delete()
        t.start(composition, output.absolutePath)
        main.postDelayed(poll, 100)
      }
    }
  }

  private fun composition(clips: List<MergeClip>, media: List<ProbeResult>, options: MergeOptions, join: Boolean): Composition {
    val items = clips.zip(media).map { (clip, m) -> item(clip, m, options, join) }
    // Declare audio only when some clip has sound: muted or silent clips then get generated silence.
    val hasAudio = clips.zip(media).any { (clip, m) -> !clip.muted && m.audio != null }
    val trackTypes = if (hasAudio) setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO) else setOf(C.TRACK_TYPE_VIDEO)
    val sequence = EditedMediaItemSequence.Builder(trackTypes).addItems(items).build()
    val everyClipSounds = clips.zip(media).all { (clip, m) -> !clip.muted && m.audio != null }
    val builder = Composition.Builder(sequence)
      .setTransmuxVideo(join)
      .setTransmuxAudio(join && everyClipSounds)
    // HDR sources are tone-mapped to SDR (inputs are normally conformed to SDR already).
    if (!join && media.any { it.video?.transfer != null && it.video?.transfer != Transfer.SDR }) {
      builder.setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
    }
    return builder.build()
  }

  private fun item(clip: MergeClip, media: ProbeResult, options: MergeOptions, join: Boolean): EditedMediaItem {
    val mediaItem = MediaItem.Builder().setUri(uri(clip.uri))
    if (!join && clip.endMs > clip.startMs) {
      mediaItem.setClippingConfiguration(
        MediaItem.ClippingConfiguration.Builder()
          .setStartPositionMs(clip.startMs.toLong())
          .setEndPositionMs(clip.endMs.toLong())
          .build(),
      )
    }
    val item = EditedMediaItem.Builder(mediaItem.build()).setRemoveAudio(clip.muted)
    if (join) return item.build()

    if (abs(clip.speed - 1) > 0.0001) {
      item.setSpeed(SpeedParameters(ConstantSpeed(clip.speed.toFloat()), /* shouldMaintainPitch= */ true))
    }
    item.setFrameRate(options.fps.roundToInt())
    item.setEffects(Effects(audioProcessors(options), videoEffects(clip, options)))
    return item.build()
  }

  /** rotate (clockwise; Media3 turns counter-clockwise) → flip → crop → letterboxed fit. */
  private fun videoEffects(clip: MergeClip, options: MergeOptions): List<Effect> {
    val effects = mutableListOf<Effect>()
    val degrees = ((clip.rotation.roundToInt() % 360) + 360) % 360
    if (degrees != 0) {
      effects += ScaleAndRotateTransformation.Builder().setRotationDegrees(-degrees.toFloat()).build()
    }
    if (clip.flipped) {
      effects += ScaleAndRotateTransformation.Builder().setScale(-1f, 1f).build()
    }
    clip.crop?.let { c ->
      // Normalized, y down → Media3's normalized device coordinates, y up.
      val left = (-1 + 2 * c.x).toFloat()
      val right = (-1 + 2 * (c.x + c.w)).toFloat()
      val top = (1 - 2 * c.y).toFloat()
      val bottom = (1 - 2 * (c.y + c.h)).toFloat()
      effects += Crop(left, right, bottom, top)
    }
    effects += Presentation.createForWidthAndHeight(
      options.width.roundToInt(), options.height.roundToInt(), Presentation.LAYOUT_SCALE_TO_FIT)
    return effects
  }

  /** Every clip's audio in one layout (the recorder's), as Media3 requires across a sequence. */
  private fun audioProcessors(options: MergeOptions): List<AudioProcessor> {
    val channels = options.audio.channels.roundToInt().coerceIn(1, 8)
    val mixing = ChannelMixingAudioProcessor()
    for (input in 1..8) mixing.putChannelMixingMatrix(mixingMatrix(input, channels))
    val resample = SonicAudioProcessor().apply { setOutputSampleRateHz(options.audio.sampleRate.roundToInt()) }
    return listOf(mixing, resample)
  }

  /**
   * `input` channels → `output` channels. Media3's built-in coefficients cover only some pairs
   * (not 5.1 → stereo, the iPhone 17 Pro's recording layout), so the rest are given here.
   * Coefficients are one row per input channel, one column per output channel. Decoded channel
   * order is Android's: FL FR FC LFE BL BR.
   */
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

  private fun uri(uri: String): Uri = if (uri.startsWith("/")) Uri.fromFile(File(uri)) else Uri.parse(uri)

  private class ConstantSpeed(private val speed: Float) : SpeedProvider {
    override fun getSpeed(timeUs: Long): Float = speed
    override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
  }
}
