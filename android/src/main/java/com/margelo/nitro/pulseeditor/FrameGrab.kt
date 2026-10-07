package com.margelo.nitro.pulseeditor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.inspector.frame.FrameExtractor
import com.margelo.nitro.pulseeditor.merge.Merge
import com.margelo.nitro.pulseeditor.merge.MergeException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One frame as a JPEG: Media3's `FrameExtractor` (an exact seek, decoded on the platform decoder,
 * HDR tone-mapped to SDR), then the clip's edit drawn on the bitmap with merge's own geometry
 * (rotate → flip → crop, as `MergeExport.editEffects`), so an edited clip's cover is drawn as the
 * export renders it. Media3 turns the frame upright itself but leaves a mirrored source's flip to
 * us (as in conform). The picture is scaled to fit the maximum size, never up.
 *
 * The extractor is kept warm for the last source: `FrameExtractor` builds and prepares a player
 * per instance (160–600 ms per cover on a Galaxy S24 when every call built one), so covers of the
 * same clip — a cover picker, a strip — pay a seek instead. Its effects don't depend on the edit
 * (only the mirror flip and a size bound), which is what lets the same instance serve every edit;
 * a warm extractor whose effects changed per call returned the previous request's frame.
 */
@OptIn(UnstableApi::class)
internal object FrameGrab {
  /** Frames are extracted no larger than this on the longer side: enough for any cover, bounded memory. */
  private const val MAX_FRAME_SIDE = 1920
  /** How long the last source's extractor is kept after a cover. */
  private const val WARM_MS = 4000L

  private class Warm(val key: String, val extractor: FrameExtractor)

  // Main thread only (FrameExtractor runs its player there).
  private var warm: Warm? = null
  private val main = Handler(Looper.getMainLooper())
  private val release = Runnable { closeWarm() }

  /** Covers run one at a time: a call for another source would close the extractor a call in flight waits on. */
  private val oneAtATime = Mutex()

  suspend fun run(context: Context, uri: String, options: ThumbnailOptions): Thumbnail = oneAtATime.withLock {
    Merge.removeStaleOutputs(context)
    val source = Probe.read(context, uri)
    val video = source.video ?: throw MergeException("The file has no video.")

    // Inside the video: a time past its last frame takes the last frame.
    val frameMs = if (video.fps > 0) 1000 / video.fps else 1000 / 30.0
    val lengthMs = if (video.durationMs > 0) video.durationMs else source.durationMs
    val timeMs = min(max(0.0, options.timeMs), max(0.0, lengthMs - frameMs)).roundToLong()

    val (boundW, boundH) = boundedSize(video)
    val key = "$uri|${video.mirrored}|${boundW}x$boundH"
    val frame = withContext(Dispatchers.Main) {
      main.removeCallbacks(release)
      val w = warm?.takeIf { it.key == key } ?: run {
        closeWarm()
        val effects = mutableListOf<Effect>()
        if (video.mirrored) effects += ScaleAndRotateTransformation.Builder().setScale(-1f, 1f).build()
        effects += Presentation.createForWidthAndHeight(boundW, boundH, Presentation.LAYOUT_SCALE_TO_FIT)
        Warm(key, FrameExtractor.Builder(context, MediaItem.fromUri(mediaUri(uri))).setEffects(effects).build())
          .also { warm = it }
      }
      try {
        w.extractor.getFrame(timeMs).await()
      } catch (e: Throwable) {
        closeWarm()
        throw e
      } finally {
        if (warm != null) main.postDelayed(release, WARM_MS)
      }
    }

    val bitmap = withContext(Dispatchers.Default) { edited(frame.bitmap, options) }
    val file = Merge.outputFile(context, "frame", "jpg")
    withContext(Dispatchers.IO) {
      file.parentFile?.mkdirs()
      val quality = (options.quality.coerceIn(0.0, 1.0) * 100).roundToInt()
      file.outputStream().use { out ->
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) {
          throw MergeException("Couldn't write the image file.")
        }
      }
    }
    Thumbnail(
      uri = Uri.fromFile(file).toString(),
      width = bitmap.width.toDouble(),
      height = bitmap.height.toDouble(),
      timeMs = frame.presentationTimeMs.toDouble(),
    )
  }

  /** Drops the warm extractor (main thread). */
  fun closeWarm() {
    warm?.extractor?.close()
    warm = null
  }

  /** The upright frame (rotate → flip → crop), fitted into the maximum, never scaled up. */
  private fun edited(upright: Bitmap, options: ThumbnailOptions): Bitmap {
    val degrees = ((options.rotation.roundToInt() % 360) + 360) % 360
    val turned = if (degrees == 0 && !options.flipped) upright else {
      val m = Matrix()
      if (degrees != 0) m.postRotate(degrees.toFloat())
      if (options.flipped) m.postScale(-1f, 1f)
      Bitmap.createBitmap(upright, 0, 0, upright.width, upright.height, m, true)
    }
    val c = options.crop
    val x = ((c?.x ?: 0.0) * turned.width).roundToInt().coerceIn(0, turned.width - 1)
    val y = ((c?.y ?: 0.0) * turned.height).roundToInt().coerceIn(0, turned.height - 1)
    val w = ((c?.w ?: 1.0) * turned.width).roundToInt().coerceIn(1, turned.width - x)
    val h = ((c?.h ?: 1.0) * turned.height).roundToInt().coerceIn(1, turned.height - y)
    val (outW, outH) = fitted(w.toDouble(), h.toDouble(), options.maxWidth, options.maxHeight)
    if (x == 0 && y == 0 && w == turned.width && h == turned.height && outW == w && outH == h) return turned
    val scale = Matrix().apply { postScale(outW / w.toFloat(), outH / h.toFloat()) }
    return Bitmap.createBitmap(turned, x, y, w, h, scale, true)
  }

  /** The source's upright size, scaled down (never up) so its longer side is at most [MAX_FRAME_SIDE]. */
  private fun boundedSize(video: ProbeVideo): Pair<Int, Int> {
    val upright = video.rotation.roundToInt() % 180 != 0
    val w = if (upright) video.height else video.width
    val h = if (upright) video.width else video.height
    val scale = min(1.0, MAX_FRAME_SIDE / max(w, h))
    return max(1, (w * scale).roundToInt()) to max(1, (h * scale).roundToInt())
  }

  /** `w`×`h` scaled down (never up) to fit inside maxWidth × maxHeight; 0 means no limit. */
  private fun fitted(w: Double, h: Double, maxWidth: Double, maxHeight: Double): Pair<Int, Int> {
    var scale = 1.0
    if (maxWidth > 0) scale = min(scale, maxWidth / w)
    if (maxHeight > 0) scale = min(scale, maxHeight / h)
    return max(1, (w * scale).roundToInt()) to max(1, (h * scale).roundToInt())
  }
}
