package com.margelo.nitro.pulseeditor

import android.content.Context
import android.media.MediaFormat
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.inspector.MediaExtractorCompat
import androidx.media3.inspector.MetadataRetriever
import kotlin.math.roundToLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withContext

/**
 * Container and track metadata from Media3's extractors (the same ones the player and Transformer
 * use): reads the header, never decodes, so a probe costs a few milliseconds. One retriever per
 * item, closed with `use`, futures awaited — the usage the Media3 Inspector guide recommends.
 */
@OptIn(UnstableApi::class)
object Probe {
  /** A probed file, plus the video track's Media3 format for callers that need more (merge). */
  class Probed(val result: ProbeResult, val videoFormat: Format?)

  suspend fun read(context: Context, uri: String): ProbeResult = load(context, uri).result

  suspend fun load(context: Context, uri: String): Probed {
    MetadataRetriever.Builder(context, MediaItem.fromUri(mediaUri(uri))).build().use { retriever ->
      val groups = retriever.retrieveTrackGroups().await()
      val durationUs = retriever.retrieveDurationUs().await()
      val formats = (0 until groups.length).map { groups[it].getFormat(0) }
      if (formats.isEmpty()) throw IllegalArgumentException("No media tracks in $uri")

      val durationMs = if (durationUs == C.TIME_UNSET) -1.0 else durationUs / 1000.0
      val videoFormat = formats.firstOrNull { MimeTypes.isVideo(it.sampleMimeType) }
      val video = videoFormat?.let {
        readVideo(it, withContext(Dispatchers.IO) { videoTrackDurationMs(context, uri) } ?: durationMs)
      }
      val audio = formats.firstOrNull { MimeTypes.isAudio(it.sampleMimeType) }?.let(::readAudio)
      return Probed(ProbeResult(durationMs = durationMs, video = video, audio = audio), videoFormat)
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
      codec = videoCodec(f),
      width = f.width.toDouble(),
      height = f.height.toDouble(),
      rotation = (((f.rotationDegrees % 360) + 360) % 360).toDouble(),
      mirrored = f.mirrorHorizontal,
      // Rounded to 0.001: the container's rate is a float (30 fps reads 30.000001907).
      fps = if (f.frameRate > 0) (f.frameRate * 1000.0).roundToLong() / 1000.0 else -1.0,
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

  /**
   * The first video track's own duration, from its track header: audio can outlast the picture,
   * and the Inspector only exposes the container's. Media3's extractor reports it per track.
   */
  private fun videoTrackDurationMs(context: Context, uri: String): Double? {
    val extractor = MediaExtractorCompat(context)
    try {
      extractor.setDataSource(mediaUri(uri), 0)
      val format = (0 until extractor.trackCount).map(extractor::getTrackFormat)
        .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true } ?: return null
      return if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1000.0 else null
    } finally {
      extractor.release()
    }
  }

  /**
   * Dolby Vision profiles with a backward-compatible base layer (iPhone HDR is 8.4, HEVC) and
   * MV-HEVC report the codec that base layer is in, the one a plain decoder plays.
   */
  private fun videoCodec(f: Format) = when (val mime = MediaCodecUtil.getAlternativeCodecMimeType(f) ?: f.sampleMimeType) {
    MimeTypes.VIDEO_H264 -> "h264"
    MimeTypes.VIDEO_H265 -> "hevc"
    else -> mime?.substringAfter('/') ?: ""
  }

  private fun audioCodec(mime: String?) = when (mime) {
    MimeTypes.AUDIO_AAC -> "aac"
    MimeTypes.AUDIO_OPUS -> "opus"
    else -> mime?.substringAfter('/') ?: ""
  }
}
