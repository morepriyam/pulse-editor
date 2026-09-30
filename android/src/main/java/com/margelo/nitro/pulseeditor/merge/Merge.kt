package com.margelo.nitro.pulseeditor.merge

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.ExportResult
import com.margelo.nitro.pulseeditor.MergeClip
import com.margelo.nitro.pulseeditor.MergeOptions
import com.margelo.nitro.pulseeditor.MergeResult
import com.margelo.nitro.pulseeditor.Probe
import com.margelo.nitro.pulseeditor.ProbeResult
import com.margelo.nitro.pulseeditor.transcode.Transcode
import com.margelo.nitro.pulseeditor.transcode.isFaststart
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/** Merge entry point: read every clip, join or encode, verify the output. */
@OptIn(UnstableApi::class)
object Merge {
  suspend fun run(context: Context, clips: List<MergeClip>, options: MergeOptions, progress: MergeProgress): MergeResult {
    if (clips.isEmpty()) throw MergeException("No clips to merge.")
    progress(0.0)
    val media = coroutineScope {
      clips.map { clip -> async(Dispatchers.IO) { Probe.read(context, clip.uri) } }.awaitAll()
    }
    val expectedMs = clips.zip(media).sumOf { (clip, m) -> MergePlan.timelineMs(clip, m) }

    if (MergePlan.canJoin(clips, media, options)) {
      try {
        return export(context, clips, media, options, join = true, expectedMs, progress)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // Safety net: a join that failed or didn't check out gets one full encode instead.
      }
    }
    return export(context, clips, media, options, join = false, expectedMs, progress)
  }

  private suspend fun export(
    context: Context, clips: List<MergeClip>, media: List<ProbeResult>, options: MergeOptions,
    join: Boolean, expectedMs: Double, progress: MergeProgress,
  ): MergeResult {
    val output = outputFile(context)
    try {
      val outcome = MergeExport.run(context, clips, media, options, join, output) { progress(it * 0.98) }
      return withContext(Dispatchers.IO) { verify(context, output, outcome, expectedMs, options) }.also { progress(1.0) }
    } catch (e: Throwable) {
      output.delete()
      throw e
    }
  }

  /**
   * Reject silent assembly bugs: the right length, the canvas, H.264, and moov before mdat. A
   * rejection names any encoder fallback Media3 applied, the likely cause.
   */
  private suspend fun verify(
    context: Context, output: File, outcome: Transcode.Outcome, expectedMs: Double, options: MergeOptions,
  ): MergeResult {
    fun fail(reason: String) = MergeException((listOf(reason) + outcome.fallbacks).joinToString("; "))
    val result = outcome.result
    val probed = Probe.read(context, Uri.fromFile(output).toString())
    val v = probed.video ?: throw fail("The merged video has no video track.")
    val swapped = v.rotation.toInt() % 180 != 0
    val (w, h) = if (swapped) v.height to v.width else v.width to v.height
    if (v.codec != "h264" || w != options.width || h != options.height) {
      throw fail("The merged video came out ${v.codec} ${w.toInt()}x${h.toInt()}.")
    }
    if (abs(probed.durationMs - expectedMs) > max(500.0, expectedMs * 0.01)) {
      throw fail("The merged video is ${probed.durationMs.toInt()} ms, expected ${expectedMs.toInt()} ms.")
    }
    if (!isFaststart(output)) throw fail("The merged video isn't faststart.")
    return MergeResult(
      uri = Uri.fromFile(output).toString(),
      durationMs = probed.durationMs,
      encoded = result.videoConversionProcess != ExportResult.CONVERSION_PROCESS_TRANSMUXED,
      bitrate = if (result.averageVideoBitrate > 0) result.averageVideoBitrate.toDouble() else v.bitrate,
    )
  }

  private fun outputFile(context: Context): File =
    File(File(context.cacheDir, "pulse-editor"), "merge-${UUID.randomUUID()}.mp4")
}
