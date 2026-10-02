package com.margelo.nitro.pulseeditor.transcode

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.muxer.Muxer
import com.google.common.collect.ImmutableList

/**
 * Writes the video track with a chosen orientation tag (clockwise degrees). Transformer only tags
 * what it rotated itself (portrait encoded as landscape, its own pick of 90 or 270); conform
 * renders the frames in the coded orientation it wants and tags them here, so the output carries
 * exactly the camera's layout (1920×1080 tagged 90° for a portrait recording).
 */
@OptIn(UnstableApi::class)
internal class RotationTagMuxerFactory(private val inner: Muxer.Factory, private val degrees: Int) : Muxer.Factory {
  override fun create(path: String): Muxer {
    val muxer = inner.create(path)
    return object : Muxer by muxer {
      override fun addTrack(format: Format): Int =
        muxer.addTrack(
          if (MimeTypes.isVideo(format.sampleMimeType)) format.buildUpon().setRotationDegrees(degrees).build() else format,
        )
    }
  }

  override fun getSupportedSampleMimeTypes(trackType: Int): ImmutableList<String> =
    inner.getSupportedSampleMimeTypes(trackType)

  override fun supportsWritingNegativeTimestampsInEditList(): Boolean = inner.supportsWritingNegativeTimestampsInEditList()
}
