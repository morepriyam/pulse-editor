package com.margelo.nitro.pulseeditor.merge

import android.content.Context
import android.net.Uri
import android.util.Log
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
import com.margelo.nitro.pulseeditor.transcode.addAacRollGroup
import com.margelo.nitro.pulseeditor.transcode.moveMoovToFront
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
    removeStaleOutputs(context)
    progress(0.0)
    val probed = coroutineScope {
      clips.mapIndexed { i, clip ->
        async(Dispatchers.IO) {
          try {
            Probe.load(context, clip.uri)
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            throw clipFailure(i, clip, e)
          }
        }
      }.awaitAll()
    }
    val media = probed.map { it.result }
    val expectedMs = clips.zip(media).sumOf { (clip, m) -> MergePlan.timelineMs(clip, m) }

    if (MergePlan.canJoin(clips, media, probed.map { it.videoFormat }, options)) {
      try {
        return export(context, clips, media, options, join = true, expectedMs) { progress(it) }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        // Safety net: a join that failed or didn't check out gets one full encode instead. Its
        // progress carries on from where the join got to (the bar never goes back).
        Log.w("PulseEditor", "Join failed, encoding instead: ${e.message}", e)
        val from = progress.last
        return export(context, clips, media, options, join = false, expectedMs) { progress(from + (1 - from) * it) }
      }
    }
    return export(context, clips, media, options, join = false, expectedMs) { progress(it) }
  }

  private suspend fun export(
    context: Context, clips: List<MergeClip>, media: List<ProbeResult>, options: MergeOptions,
    join: Boolean, expectedMs: Double, progress: (Double) -> Unit,
  ): MergeResult {
    val output = outputFile(context)
    try {
      val outcome = MergeExport.run(context, clips, media, options, join, expectedMs, output) { progress(it * 0.98) }
      return withContext(Dispatchers.IO) {
        moveMoovToFront(output)
        if (!addAacRollGroup(output)) Log.w("PulseEditor", "Couldn't add the AAC roll group: Apple players may play the audio 44 ms early")
        verify(context, output, outcome, expectedMs, options)
      }.also { progress(1.0) }
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

  private fun outputDirectory(context: Context) = File(context.cacheDir, "pulse-editor")

  private fun outputFile(context: Context): File = File(outputDirectory(context), "merge-${UUID.randomUUID()}.mp4")

  /** Deletes merge files a killed app left behind (a finished merge is moved out by its caller). */
  private fun removeStaleOutputs(context: Context) {
    val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
    outputDirectory(context).listFiles { file -> file.name.startsWith("merge-") && file.lastModified() < cutoff }
      ?.forEach { it.delete() }
  }

  /** A clip's failure, naming the clip (its position and file name). */
  private fun clipFailure(index: Int, clip: MergeClip, e: Exception): MergeException {
    val name = clip.uri.substringAfterLast('/')
    val reason = generateSequence(e as Throwable) { it.cause }.last().message ?: e.javaClass.simpleName
    return MergeException("Clip ${index + 1} ($name): $reason")
  }
}
