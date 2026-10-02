package com.margelo.nitro.pulseeditor

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.inspector.frame.FrameExtractor
import com.margelo.nitro.pulseeditor.merge.Merge
import com.margelo.nitro.pulseeditor.merge.MergeException
import com.margelo.nitro.pulseeditor.merge.MergeExport
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withContext

/**
 * One frame as a JPEG: Media3's `FrameExtractor` (an exact seek, decoded on the platform decoder,
 * HDR tone-mapped to SDR) with merge's own edit effects, so an edited clip's cover is drawn as the
 * export renders it. Media3 turns the frame upright itself but leaves a mirrored source's flip to
 * us (as in conform). The picture is scaled to fit the maximum size, never up.
 */
@OptIn(UnstableApi::class)
internal object FrameGrab {
  suspend fun run(context: Context, uri: String, options: ThumbnailOptions): Thumbnail {
    Merge.removeStaleOutputs(context)
    val source = Probe.read(context, uri)
    val video = source.video ?: throw MergeException("The file has no video.")

    // The edited picture's size, then the output: that size fitted into the maximum.
    val (contentW, contentH) = editedSize(video, options)
    val (outW, outH) = fitted(contentW, contentH, options.maxWidth, options.maxHeight)
    val effects = mutableListOf<Effect>()
    if (video.mirrored) effects += ScaleAndRotateTransformation.Builder().setScale(-1f, 1f).build()
    effects += MergeExport.editEffects(options.rotation, options.flipped, options.crop)
    effects += Presentation.createForWidthAndHeight(outW, outH, Presentation.LAYOUT_SCALE_TO_FIT)

    // Inside the video: a time past its last frame takes the last frame.
    val frameMs = if (video.fps > 0) 1000 / video.fps else 1000 / 30.0
    val lengthMs = if (video.durationMs > 0) video.durationMs else source.durationMs
    val timeMs = min(max(0.0, options.timeMs), max(0.0, lengthMs - frameMs)).roundToLong()

    // FrameExtractor is used from one thread (the main one, where it runs its player).
    val frame = withContext(Dispatchers.Main) {
      val extractor = FrameExtractor.Builder(context, MediaItem.fromUri(mediaUri(uri))).setEffects(effects).build()
      try {
        extractor.getFrame(timeMs).await()
      } finally {
        extractor.close()
      }
    }

    val file = Merge.outputFile(context, "frame", "jpg")
    withContext(Dispatchers.IO) {
      file.parentFile?.mkdirs()
      val quality = (options.quality.coerceIn(0.0, 1.0) * 100).roundToInt()
      file.outputStream().use { out ->
        if (!frame.bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) {
          throw MergeException("Couldn't write the image file.")
        }
      }
    }
    return Thumbnail(
      uri = Uri.fromFile(file).toString(),
      width = frame.bitmap.width.toDouble(),
      height = frame.bitmap.height.toDouble(),
      timeMs = frame.presentationTimeMs.toDouble(),
    )
  }

  /** The source's upright size after the edit's rotation and crop. */
  private fun editedSize(video: ProbeVideo, options: ThumbnailOptions): Pair<Double, Double> {
    val upright = video.rotation.roundToInt() % 180 != 0
    var w = if (upright) video.height else video.width
    var h = if (upright) video.width else video.height
    if (options.rotation.roundToInt() % 180 != 0) w = h.also { h = w }
    options.crop?.let { c ->
      w *= c.w
      h *= c.h
    }
    return w to h
  }

  /** `w`×`h` scaled down (never up) to fit inside maxWidth × maxHeight; 0 means no limit. */
  private fun fitted(w: Double, h: Double, maxWidth: Double, maxHeight: Double): Pair<Int, Int> {
    var scale = 1.0
    if (maxWidth > 0) scale = min(scale, maxWidth / w)
    if (maxHeight > 0) scale = min(scale, maxHeight / h)
    return max(1, (w * scale).roundToInt()) to max(1, (h * scale).roundToInt())
  }
}
