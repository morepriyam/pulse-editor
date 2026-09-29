package com.margelo.nitro.pulseeditor

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.inspector.MetadataRetriever
import java.io.File
import kotlinx.coroutines.guava.await

/**
 * Container and track metadata from Media3's extractors (the same ones the player and Transformer
 * use): reads the header, never decodes, so a probe costs a few milliseconds. One retriever per
 * item, closed with `use`, futures awaited — the usage the Media3 Inspector guide recommends.
 */
object Probe {
  suspend fun read(context: Context, uri: String): ProbeResult {
    MetadataRetriever.Builder(context, MediaItem.fromUri(toUri(uri))).build().use { retriever ->
      val groups = retriever.retrieveTrackGroups().await()
      val durationUs = retriever.retrieveDurationUs().await()
      val formats = (0 until groups.length).map { groups[it].getFormat(0) }
      if (formats.isEmpty()) throw IllegalArgumentException("No media tracks in $uri")

      val durationMs = if (durationUs == C.TIME_UNSET) -1.0 else durationUs / 1000.0
      val video = formats.firstOrNull { MimeTypes.isVideo(it.sampleMimeType) }?.let { readVideo(it, durationMs) }
      val audio = formats.firstOrNull { MimeTypes.isAudio(it.sampleMimeType) }?.let(::readAudio)
      return ProbeResult(durationMs = durationMs, video = video, audio = audio)
    }
  }

  private fun readVideo(f: Format, durationMs: Double): ProbeVideo {
    val transfer = when (f.colorInfo?.colorTransfer) {
      C.COLOR_TRANSFER_HLG -> Transfer.HLG
      C.COLOR_TRANSFER_ST2084 -> Transfer.PQ
      else -> Transfer.SDR
    }
    val luma = f.colorInfo?.lumaBitdepth ?: Format.NO_VALUE
    return ProbeVideo(
      codec = videoCodec(f.sampleMimeType),
      width = f.width.toDouble(),
      height = f.height.toDouble(),
      rotation = (((f.rotationDegrees % 360) + 360) % 360).toDouble(),
      mirrored = false,
      fps = if (f.frameRate > 0) f.frameRate.toDouble() else -1.0,
      bitrate = if (f.averageBitrate > 0) f.averageBitrate.toDouble() else f.bitrate.toDouble(),
      bitDepth = (if (luma > 0) luma else if (transfer == Transfer.SDR) 8 else 10).toDouble(),
      transfer = transfer,
      durationMs = durationMs,
    )
  }

  private fun readAudio(f: Format) = ProbeAudio(
    codec = audioCodec(f.sampleMimeType),
    sampleRate = f.sampleRate.toDouble(),
    channels = f.channelCount.toDouble(),
  )

  private fun videoCodec(mime: String?) = when (mime) {
    MimeTypes.VIDEO_H264 -> "h264"
    MimeTypes.VIDEO_H265 -> "hevc"
    else -> mime?.substringAfter('/') ?: ""
  }

  private fun audioCodec(mime: String?) = when (mime) {
    MimeTypes.AUDIO_AAC -> "aac"
    MimeTypes.AUDIO_OPUS -> "opus"
    else -> mime?.substringAfter('/') ?: ""
  }

  /** `file://` / `content://` URI or bare path → Uri. */
  private fun toUri(uri: String): Uri =
    if (uri.startsWith("/")) Uri.fromFile(File(uri)) else Uri.parse(uri)
}
