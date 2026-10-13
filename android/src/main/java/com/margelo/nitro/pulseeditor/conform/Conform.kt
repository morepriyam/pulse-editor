package com.margelo.nitro.pulseeditor.conform

import android.content.Context
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.inspector.MediaExtractorCompat
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.margelo.nitro.pulseeditor.ConformOptions
import com.margelo.nitro.pulseeditor.ConformResult
import com.margelo.nitro.pulseeditor.Probe
import com.margelo.nitro.pulseeditor.ProbeResult
import com.margelo.nitro.pulseeditor.ProbeVideo
import com.margelo.nitro.pulseeditor.Transfer
import com.margelo.nitro.pulseeditor.mediaUri
import com.margelo.nitro.pulseeditor.merge.Merge
import com.margelo.nitro.pulseeditor.merge.MergeException
import com.margelo.nitro.pulseeditor.merge.MergeExport
import com.margelo.nitro.pulseeditor.merge.MergeProgress
import com.margelo.nitro.pulseeditor.transcode.Transcode
import com.margelo.nitro.pulseeditor.transcode.addAacRollGroup
import com.margelo.nitro.pulseeditor.transcode.audioProcessors
import com.margelo.nitro.pulseeditor.transcode.isFaststart
import com.margelo.nitro.pulseeditor.transcode.moveMoovToFront
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Conform one file into the recorder's format (see `ConformOptions`), as one Media3 Transformer
 * export through merge's [Transcode]:
 * - copyVideo: the video samples are copied (transmuxed) and the audio is encoded into the layout.
 * - otherwise the clip is re-encoded: HDR tone-mapped to SDR (on the GPU where it can, as merge),
 *   a mirrored source flipped back (Media3 reads the flag but doesn't apply it), frames above the
 *   rate dropped, letterboxed onto the canvas, then turned into the coded orientation the rotation
 *   tag turns upright, and written with exactly that tag.
 * A file whose sound this phone can't decode is rejected rather than conformed silent.
 */
@OptIn(UnstableApi::class)
object Conform {
  suspend fun run(context: Context, uri: String, options: ConformOptions, progress: MergeProgress): ConformResult {
    Merge.removeStaleOutputs(context)
    progress(0.0)
    val source = Probe.read(context, uri)
    val video = source.video ?: throw MergeException("The file has no video.")
    val degrees = ((options.rotation.roundToInt() % 360) + 360) % 360
    if (degrees % 90 != 0) throw MergeException("The rotation must be 0, 90, 180 or 270.")
    val durationMs = if (video.durationMs > 0) video.durationMs else source.durationMs
    val output = Merge.outputFile(context, "conform")
    try {
      val settings = Transcode.Settings(
        bitrate = options.bitrate.toInt(), durationMs = durationMs,
        portrait = true, copyVideo = options.copyVideo, rotationTag = if (options.copyVideo) null else degrees,
        oneFile = true,
      )
      val outcome = Transcode.run(context, composition(uri, source, options, degrees), output, settings) { progress(it * 0.98) }
      return withContext(Dispatchers.IO) {
        moveMoovToFront(output)
        if (source.audio != null && !addAacRollGroup(output)) {
          Log.w("PulseEditor", "Couldn't add the AAC roll group: Apple players may play the audio 44 ms early")
        }
        if (!options.copyVideo) checkFrameCount(context, uri, video, options, outcome.result.videoFrameCount, outcome.fallbacks)
        val out = verify(context, output, source, options, degrees, durationMs, outcome.fallbacks)
        ConformResult(
          uri = Uri.fromFile(output).toString(),
          durationMs = out.durationMs,
          encoded = !options.copyVideo,
          bitrate = if (outcome.result.averageVideoBitrate > 0) outcome.result.averageVideoBitrate.toDouble() else out.video?.bitrate ?: -1.0,
        )
      }.also { progress(1.0) }
    } catch (e: Throwable) {
      output.delete()
      throw e
    }
  }

  private fun composition(uri: String, source: ProbeResult, options: ConformOptions, degrees: Int): Composition {
    val video = source.video!!
    val audio = audioProcessors(options.audio.sampleRate.roundToInt(), options.audio.channels.roundToInt())
    val trackTypes = if (source.audio != null) setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO) else setOf(C.TRACK_TYPE_VIDEO)
    val item = EditedMediaItem.Builder(MediaItem.fromUri(mediaUri(uri)))
    if (options.copyVideo) {
      item.setEffects(Effects(audio, emptyList()))
      return Composition.Builder(EditedMediaItemSequence.Builder(trackTypes).addItem(item.build()).build())
        .setTransmuxVideo(true)
        .build()
    }

    val effects = mutableListOf<Effect>()
    if (dropsFrames(video, options)) effects += FrameDropEffect.createDefaultFrameDropEffect(options.fps.toFloat())
    if (video.mirrored) effects += ScaleAndRotateTransformation.Builder().setScale(-1f, 1f).build()
    effects += Presentation.createForWidthAndHeight(
      options.width.roundToInt(), options.height.roundToInt(), Presentation.LAYOUT_SCALE_TO_FIT)
    // Upright canvas → coded orientation: Media3 turns counter-clockwise, the tag clockwise.
    if (degrees != 0) effects += ScaleAndRotateTransformation.Builder().setRotationDegrees(degrees.toFloat()).build()
    item.setEffects(Effects(audio, effects))
    return Composition.Builder(EditedMediaItemSequence.Builder(trackTypes).addItem(item.build()).build()).apply {
      if (video.transfer != Transfer.SDR) setHdrMode(MergeExport.hdrMode)
    }.build()
  }

  /** Whether the composition adds the frame drop: above the target rate, or when the source's rate is unknown. */
  private fun dropsFrames(video: ProbeVideo, options: ConformOptions) =
    video.fps <= 0 || video.fps.roundToInt() > options.fps.roundToInt()

  /**
   * A damaged source must not come out with holes: the platform decoder drops the frames it can't
   * decode and Transformer carries on, so the output keeps the source's length with the picture
   * frozen over the gaps (a corrupt 114 s clip came out with 746 of its 3424 frames on a Galaxy
   * S24; AVFoundation conceals the same errors). The output's frame count is compared with the
   * frames a clean decode of the source would give: its samples from time 0 on, put through the
   * frame drop's own rule when the composition adds it.
   */
  private suspend fun checkFrameCount(
    context: Context, uri: String, video: ProbeVideo, options: ConformOptions, outputFrames: Int, fallbacks: List<String>,
  ) {
    if (outputFrames <= 0) return  // not reported (Media3 counts only encoded video)
    val times = videoSampleTimes(context, uri) ?: return
    val expected = if (dropsFrames(video, options)) framesKept(times, options.fps.toFloat()) else times.size
    Log.i("PulseEditor", "conform frames: $outputFrames written, ${times.size} in the source, $expected expected")
    if (outputFrames < expected * MIN_FRAME_FRACTION) {
      throw MergeException(
        (listOf("This phone couldn't decode all of the video ($outputFrames of $expected frames).") + fallbacks).joinToString("; "))
    }
  }

  /**
   * The presentation times of the first video track's samples, in presentation order, without
   * the edit list's preroll (samples before time 0, which Transformer decodes and drops). Nothing
   * is decoded, but the extractor reads every sample, so the whole file is read.
   */
  private suspend fun videoSampleTimes(context: Context, uri: String): LongArray? {
    val extractor = MediaExtractorCompat(context)
    try {
      extractor.setDataSource(mediaUri(uri), 0)
      val track = (0 until extractor.trackCount).firstOrNull {
        extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
      } ?: return null
      extractor.selectTrack(track)
      val times = ArrayList<Long>()
      while (extractor.sampleTrackIndex >= 0) {
        currentCoroutineContext().ensureActive()
        val time = extractor.sampleTime
        if (time >= 0) times += time
        if (!extractor.advance()) break
      }
      return times.toLongArray().apply { sort() }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w("PulseEditor", "Couldn't read the video samples of $uri", e)
      return null
    } finally {
      extractor.release()
    }
  }

  /**
   * The frames `FrameDropEffect.createDefaultFrameDropEffect(fps)` passes on for these times
   * (Media3 1.11.1's DefaultFrameDroppingShaderProgram): the first frame; then a held frame is
   * passed on when it's closer to the last one passed on + 1/fps than the next frame is; the
   * frame held at the end of the stream is dropped. It keeps whole frames, so the count isn't the
   * ratio of the rates (a constant 47 fps source keeps half its frames at 30).
   */
  private fun framesKept(times: LongArray, fps: Float): Int {
    if (times.isEmpty()) return 0
    val targetDeltaUs = (C.MICROS_PER_SECOND / fps).toLong()
    var kept = 1
    var last = times[0]
    var previous = times[0]
    for (i in 1 until times.size) {
      if (i > 1 && abs(previous - last - targetDeltaUs) < abs(times[i] - last - targetDeltaUs)) {
        kept++
        last = previous
      }
      previous = times[i]
    }
    return kept
  }

  /** An output below this share of the expected frames lost them to the decoder (a clean one has them all, ± a frame). */
  private const val MIN_FRAME_FRACTION = 0.8

  /**
   * The output must be what was asked for: H.264 8-bit SDR in the requested layout, sound kept,
   * as long as the source's picture, faststart. A rejection names any encoder fallback Media3
   * applied, the likely cause.
   */
  private suspend fun verify(
    context: Context, output: File, source: ProbeResult, options: ConformOptions, degrees: Int,
    durationMs: Double, fallbacks: List<String>,
  ): ProbeResult {
    fun fail(reason: String) = MergeException((listOf(reason) + fallbacks).joinToString("; "))
    val out = Probe.read(context, Uri.fromFile(output).toString())
    val v = out.video ?: throw fail("The conformed video has no picture.")
    if (!options.copyVideo) {
      val swapped = v.rotation.roundToInt() % 180 != 0
      val (w, h) = if (swapped) v.height to v.width else v.width to v.height
      if (v.codec != "h264" || v.transfer != Transfer.SDR || v.bitDepth > 8 || v.mirrored ||
        v.rotation.roundToInt() != degrees || w != options.width || h != options.height
      ) {
        throw fail("The conformed video came out ${v.codec} ${v.width.toInt()}x${v.height.toInt()} rotated ${v.rotation.toInt()}.")
      }
    }
    if (source.audio != null) {
      val a = out.audio
      if (a == null) throw fail("This video's sound can't be read on this phone.")
      if (a.codec != "aac" || a.sampleRate != options.audio.sampleRate || a.channels != options.audio.channels) {
        throw fail("The conformed video's sound came out ${a.codec} ${a.sampleRate.toInt()} Hz ${a.channels.toInt()} ch.")
      }
    }
    val got = if (v.durationMs > 0) v.durationMs else out.durationMs
    if (abs(got - durationMs) > max(500.0, durationMs * 0.01)) {
      throw fail("The conformed video is ${got.toInt()} ms, expected ${durationMs.toInt()} ms.")
    }
    if (!isFaststart(output)) throw fail("The conformed video isn't faststart.")
    return out
  }
}
