package com.margelo.nitro.pulseeditor.conform

import android.content.Context
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
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.margelo.nitro.pulseeditor.ConformOptions
import com.margelo.nitro.pulseeditor.ConformResult
import com.margelo.nitro.pulseeditor.Probe
import com.margelo.nitro.pulseeditor.ProbeResult
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
import kotlinx.coroutines.Dispatchers
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
        if (!addAacRollGroup(output)) Log.w("PulseEditor", "Couldn't add the AAC roll group: Apple players may play the audio 44 ms early")
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
    if (video.fps <= 0 || video.fps.roundToInt() > options.fps.roundToInt()) {
      effects += FrameDropEffect.createDefaultFrameDropEffect(options.fps.toFloat())
    }
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
