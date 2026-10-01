package com.margelo.nitro.pulseeditor.merge

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.SpeedParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.GlUtil
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
import com.margelo.nitro.pulseeditor.transcode.TrimAudioProcessor
import com.margelo.nitro.pulseeditor.transcode.Transcode
import com.margelo.nitro.pulseeditor.transcode.audioProcessors
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * One Media3 Transformer export of the whole timeline: an EditedMediaItemSequence of the clips.
 * - join: `setTransmuxVideo` copies every clip's video samples.
 * - encode: each clip is trimmed (ClippingConfiguration), retimed with natural pitch
 *   (SpeedParameters), rotated / flipped / cropped and letterboxed onto the canvas (effects run
 *   on the upright frame) and capped at the frame rate.
 * Audio is encoded either way: each clip's audio is cut to the clip's length ([TrimAudioProcessor])
 * and brought to the recorder's layout (Media3 needs one layout across a sequence), silence for
 * muted or silent clips. [Transcode] runs the export: H.264 at the chosen bitrate, AAC.
 */
@OptIn(UnstableApi::class, ExperimentalApi::class)
internal object MergeExport {
  /**
   * How HDR clips become SDR. Media3's OpenGL tone mapping needs the GPU's GL_EXT_YUV_target
   * (most Android 10+ phones; not the emulator), and without it every HDR clip fails with a bare
   * "Video frame processing error". There the HDR picture is read as SDR instead: it finishes, with
   * flatter colours. Checked once per process.
   */
  private val hdrMode: Int by lazy {
    if (GlUtil.isYuvTargetExtensionSupported()) {
      Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL
    } else {
      Log.i("PulseEditor", "HDR tone mapping unavailable (no GL_EXT_YUV_target): HDR clips are read as SDR")
      Composition.HDR_MODE_EXPERIMENTAL_FORCE_INTERPRET_HDR_AS_SDR
    }
  }

  suspend fun run(
    context: Context, clips: List<MergeClip>, media: List<ProbeResult>, options: MergeOptions,
    join: Boolean, expectedMs: Double, output: File, progress: (Double) -> Unit,
  ): Transcode.Outcome =
    Transcode.run(
      context, composition(clips, media, options, join), output,
      Transcode.Settings(bitrate = options.bitrate.toInt(), durationMs = expectedMs, copyVideo = join), progress,
    )

  private fun composition(clips: List<MergeClip>, media: List<ProbeResult>, options: MergeOptions, join: Boolean): Composition {
    val pairs = clips.zip(media)
    // Declare audio only when some clip has sound: muted or silent clips then get silence.
    val hasAudio = pairs.any { (clip, m) -> !clip.muted && m.audio != null }
    val builder = if (join) {
      // One sequence, video copied, audio encoded: copied AAC keeps each source's end padding,
      // which can't be cut mid-frame, so every clip would push the next clip's audio later.
      val trackTypes = if (hasAudio) setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO) else setOf(C.TRACK_TYPE_VIDEO)
      val items = pairs.map { (clip, m) ->
        EditedMediaItem.Builder(MediaItem.fromUri(mediaUri(clip.uri)))
          .setRemoveAudio(clip.muted)
          .setEffects(Effects(audioChain(clip, m, options), emptyList()))
          .build()
      }
      Composition.Builder(EditedMediaItemSequence.Builder(trackTypes).addItems(items).build()).setTransmuxVideo(true)
    } else {
      // Video and audio in parallel sequences: the video clipped frame-accurately by Media3, the
      // audio read from each file's start and cut by TrimAudioProcessor (Media3's clipping lands
      // audio late, see there). Muted and silent clips are gaps of silence.
      val video = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO))
        .addItems(pairs.map { (clip, m) -> videoItem(clip, m, options) })
        .build()
      val sequences = mutableListOf(video)
      if (hasAudio) {
        val audio = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
        for ((clip, m) in pairs) {
          if (clip.muted || m.audio == null) audio.addGap((MergePlan.timelineMs(clip, m) * 1000).roundToLong())
          else audio.addItem(audioItem(clip, m, options))
        }
        sequences += audio.build()
      }
      Composition.Builder(sequences).apply {
        // HDR sources are tone-mapped to SDR (inputs are normally conformed to SDR already).
        if (media.any { it.video?.transfer != null && it.video?.transfer != Transfer.SDR }) setHdrMode(hdrMode)
      }
    }
    return builder.build()
  }

  /** A clip's picture: trimmed, retimed, with its geometry, capped at the frame rate. */
  private fun videoItem(clip: MergeClip, media: ProbeResult, options: MergeOptions): EditedMediaItem {
    val mediaItem = MediaItem.Builder().setUri(mediaUri(clip.uri))
    if (clip.endMs > clip.startMs) {
      mediaItem.setClippingConfiguration(
        MediaItem.ClippingConfiguration.Builder()
          .setStartPositionMs(clip.startMs.toLong())
          .setEndPositionMs(minOf(clip.endMs, media.durationMs).toLong())
          .build(),
      )
    }
    val item = EditedMediaItem.Builder(mediaItem.build()).setRemoveAudio(true)
    speed(clip)?.let { item.setSpeed(it) }
    return item.setFrameRate(options.fps.roundToInt()).setEffects(Effects(emptyList(), videoEffects(clip, options))).build()
  }

  /** A clip's sound, from the file's start, retimed; the window is cut by [audioChain]. */
  private fun audioItem(clip: MergeClip, media: ProbeResult, options: MergeOptions): EditedMediaItem {
    val item = EditedMediaItem.Builder(MediaItem.fromUri(mediaUri(clip.uri))).setRemoveVideo(true)
    speed(clip)?.let { item.setSpeed(it) }
    return item.setEffects(Effects(audioChain(clip, media, options), emptyList())).build()
  }

  /** The clip's window cut exactly (in timeline time, after the speed change), then the recorder's layout. */
  private fun audioChain(clip: MergeClip, media: ProbeResult, options: MergeOptions): List<AudioProcessor> {
    val startMs = if (clip.endMs > clip.startMs) clip.startMs else 0.0
    val trim = TrimAudioProcessor(
      skipUs = (startMs / clip.speed * 1000).roundToLong(),
      durationUs = (MergePlan.timelineMs(clip, media) * 1000).roundToLong(),
    )
    return listOf(trim) + audioProcessors(options.audio.sampleRate.roundToInt(), options.audio.channels.roundToInt())
  }

  /** Natural-pitch speed change, or null at 1×. */
  private fun speed(clip: MergeClip): SpeedParameters? =
    if (abs(clip.speed - 1) > 0.0001) SpeedParameters(ConstantSpeed(clip.speed.toFloat()), /* shouldMaintainPitch= */ true) else null

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
