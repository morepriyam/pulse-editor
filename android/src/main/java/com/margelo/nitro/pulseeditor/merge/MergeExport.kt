package com.margelo.nitro.pulseeditor.merge

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.SpeedParameters
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Crop
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.margelo.nitro.pulseeditor.MergeClip
import com.margelo.nitro.pulseeditor.MergeOptions
import com.margelo.nitro.pulseeditor.ProbeResult
import com.margelo.nitro.pulseeditor.Transfer
import com.margelo.nitro.pulseeditor.mediaUri
import com.margelo.nitro.pulseeditor.transcode.Transcode
import com.margelo.nitro.pulseeditor.transcode.audioProcessors
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * One Media3 Transformer export of the whole timeline: an EditedMediaItemSequence of the clips.
 * - join: `setTransmuxVideo` copies every clip's samples (audio too, unless a clip is muted or
 *   silent: then audio alone is encoded, with generated silence).
 * - encode: each clip is trimmed (ClippingConfiguration), retimed with natural pitch
 *   (SpeedParameters), rotated / flipped / cropped and letterboxed onto the canvas (effects run
 *   on the upright frame), capped at the frame rate, and every clip's audio brought to the
 *   recorder's layout (Media3 needs one layout across a sequence).
 * [Transcode] runs the export: H.264 at the chosen bitrate, AAC.
 */
@OptIn(UnstableApi::class, ExperimentalApi::class)
internal object MergeExport {
  suspend fun run(
    context: Context, clips: List<MergeClip>, media: List<ProbeResult>, options: MergeOptions,
    join: Boolean, output: File, progress: (Double) -> Unit,
  ): Transcode.Outcome =
    Transcode.run(context, composition(clips, media, options, join), output, Transcode.Settings(options.bitrate.toInt()), progress)

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
    val mediaItem = MediaItem.Builder().setUri(mediaUri(clip.uri))
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
    item.setEffects(Effects(audioProcessors(options.audio.sampleRate.roundToInt(), options.audio.channels.roundToInt()), videoEffects(clip, options)))
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

  private class ConstantSpeed(private val speed: Float) : SpeedProvider {
    override fun getSpeed(timeUs: Long): Float = speed
    override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
  }
}
