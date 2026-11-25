package com.margelo.nitro.pulseeditor.merge

import android.content.Context
import android.os.Build
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
import com.margelo.nitro.pulseeditor.MergeCrop
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
  /**
   * Whether this phone can read HDR video at all. Media3 decodes HDR (and tone-maps or reinterprets
   * it) only on Android 10 (API 29) and newer, so below that an HDR clip is refused with a plain
   * message instead of a decoder failure. SDR clips are unaffected, and Android 10+ is unchanged.
   */
  internal val hdrSupported: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

  /** Refuse an HDR clip on a phone that can't read HDR (see [hdrSupported]). */
  internal fun requireHdrSupport(isHdr: Boolean) {
    if (isHdr && !hdrSupported) throw MergeException(HDR_UNSUPPORTED)
  }

  internal const val HDR_UNSUPPORTED = "This video is HDR, which needs Android 10 or newer to edit."

  internal val hdrMode: Int by lazy {
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
    val hasAudio = options.sourceMuted != true && pairs.any { (clip, m) -> !clip.muted && m.audio != null }
    val builder = if (join) {
      // Video copied in its own sequence, audio encoded in a parallel one (copied AAC keeps each
      // source's end padding, which can't be cut mid-frame, so every clip would push the next
      // clip's audio later). Not one sequence carrying both: Media3 then reads both tracks
      // through one player, and its copied-video exporter holds at most ~2 MB while the muxer
      // waits for the audio encoder's first output. High-bitrate video (a Galaxy S24's back
      // camera peaks near 12 Mbps) fills that first, the player stops, the audio never arrives,
      // and the export is aborted after 10 s with no sample written.
      val video = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO))
        .addItems(pairs.map { (clip, m) -> EditedMediaItem.Builder(clippedItem(clip, m)).setRemoveAudio(true).build() })
        .build()
      Composition.Builder(listOfNotNull(video, if (hasAudio) audioSequence(pairs, options) else null)).setTransmuxVideo(true)
    } else {
      // Video and audio in parallel sequences: the video clipped frame-accurately by Media3, the
      // audio read from each file's start and cut by TrimAudioProcessor (Media3's clipping lands
      // audio late, see there). Muted and silent clips are gaps of silence.
      val video = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO))
        .addItems(pairs.map { (clip, m) -> videoItem(clip, m, options) })
        .build()
      Composition.Builder(listOfNotNull(video, if (hasAudio) audioSequence(pairs, options) else null)).apply {
        // HDR sources are tone-mapped to SDR (inputs are normally conformed to SDR already).
        if (media.any { it.video?.transfer != null && it.video?.transfer != Transfer.SDR }) {
          requireHdrSupport(true)
          setHdrMode(hdrMode)
        }
      }
    }
    return builder.build()
  }

  /** The timeline's sound, for the join and the encode alike: each clip's audio read from its
   * file's start and cut by TrimAudioProcessor (Media3's clipping lands audio late, see there);
   * muted and silent clips are gaps of silence. */
  private fun audioSequence(pairs: List<Pair<MergeClip, ProbeResult>>, options: MergeOptions): EditedMediaItemSequence {
    val audio = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
    for ((clip, m) in pairs) {
      if (clip.muted || m.audio == null) audio.addGap((MergePlan.timelineMs(clip, m) * 1000).roundToLong())
      else audio.addItem(audioItem(clip, m, options))
    }
    return audio.build()
  }

  /** A clip's picture: trimmed, retimed, with its geometry, capped at the frame rate. Shared
   * with the preview, which draws the same picture (at a smaller size). */
  internal fun videoItem(clip: MergeClip, media: ProbeResult, options: MergeOptions): EditedMediaItem {
    val item = EditedMediaItem.Builder(clippedItem(clip, media)).setRemoveAudio(true)
    speed(clip)?.let { item.setSpeed(it) }
    return item.setFrameRate(options.fps.roundToInt()).setEffects(Effects(emptyList(), videoEffects(clip, options))).build()
  }

  /** The clip's file clipped to its window (MergePlan.windowUs). The join needs it too: an untrimmed
   * clip's window ends under 1 ms early when its length isn't whole ms, and the preview and
   * timelineMs use that length, so an unclipped join would start every later clip late. */
  private fun clippedItem(clip: MergeClip, media: ProbeResult): MediaItem {
    val mediaItem = MediaItem.Builder().setUri(mediaUri(clip.uri))
    MergePlan.windowUs(clip, media)?.let { (startUs, endUs) ->
      mediaItem.setClippingConfiguration(
        MediaItem.ClippingConfiguration.Builder().setStartPositionUs(startUs).setEndPositionUs(endUs).build(),
      )
    }
    return mediaItem.build()
  }

  /** A clip's sound, from the file's start, retimed; the window is cut by [audioChain]. */
  private fun audioItem(clip: MergeClip, media: ProbeResult, options: MergeOptions): EditedMediaItem {
    val item = EditedMediaItem.Builder(MediaItem.fromUri(mediaUri(clip.uri))).setRemoveVideo(true)
    speed(clip)?.let { item.setSpeed(it) }
    return item.setEffects(Effects(audioChain(clip, media, options), emptyList())).build()
  }

  /** The clip's window cut exactly (in timeline time, after the speed change), then the recorder's layout. */
  private fun audioChain(clip: MergeClip, media: ProbeResult, options: MergeOptions): List<AudioProcessor> {
    val startUs = MergePlan.windowUs(clip, media)?.first ?: 0L
    val trim = TrimAudioProcessor(
      skipUs = (startUs / clip.speed).roundToLong(),
      durationUs = (MergePlan.timelineMs(clip, media) * 1000).roundToLong(),
    )
    return listOf(trim) + audioProcessors(options.audio.sampleRate.roundToInt(), options.audio.channels.roundToInt())
  }

  /** Natural-pitch speed change, or null at 1×. */
  internal fun speed(clip: MergeClip): SpeedParameters? =
    if (abs(clip.speed - 1) > 0.0001) SpeedParameters(ConstantSpeed(clip.speed.toFloat()), /* shouldMaintainPitch= */ true) else null

  /** The clip's edit, then a letterboxed fit onto the canvas. */
  private fun videoEffects(clip: MergeClip, options: MergeOptions): List<Effect> =
    editEffects(clip.rotation, clip.flipped, clip.crop) + Presentation.createForWidthAndHeight(
      options.width.roundToInt(), options.height.roundToInt(), Presentation.LAYOUT_SCALE_TO_FIT)

  /** A clip's edit on its upright frame: rotate (clockwise; Media3 turns counter-clockwise) →
   * flip → crop. Shared with thumbnails, so a cover is drawn as the export renders the clip. */
  internal fun editEffects(rotation: Double, flipped: Boolean, crop: MergeCrop?): List<Effect> {
    val effects = mutableListOf<Effect>()
    val degrees = ((rotation.roundToInt() % 360) + 360) % 360
    if (degrees != 0) {
      effects += ScaleAndRotateTransformation.Builder().setRotationDegrees(-degrees.toFloat()).build()
    }
    if (flipped) {
      effects += ScaleAndRotateTransformation.Builder().setScale(-1f, 1f).build()
    }
    crop?.let { c ->
      // Normalized, y down → Media3's normalized device coordinates, y up.
      val left = (-1 + 2 * c.x).toFloat()
      val right = (-1 + 2 * (c.x + c.w)).toFloat()
      val top = (1 - 2 * c.y).toFloat()
      val bottom = (1 - 2 * (c.y + c.h)).toFloat()
      effects += Crop(left, right, bottom, top)
    }
    return effects
  }

  private class ConstantSpeed(private val speed: Float) : SpeedProvider {
    override fun getSpeed(timeUs: Long): Float = speed
    override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
  }
}
