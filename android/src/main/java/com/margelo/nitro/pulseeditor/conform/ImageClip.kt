package com.margelo.nitro.pulseeditor.conform

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.margelo.nitro.pulseeditor.ConformResult
import com.margelo.nitro.pulseeditor.ImageClipOptions
import com.margelo.nitro.pulseeditor.Probe
import com.margelo.nitro.pulseeditor.ProbeResult
import com.margelo.nitro.pulseeditor.Transfer
import com.margelo.nitro.pulseeditor.mediaUri
import com.margelo.nitro.pulseeditor.merge.Merge
import com.margelo.nitro.pulseeditor.merge.MergeException
import com.margelo.nitro.pulseeditor.merge.MergeProgress
import com.margelo.nitro.pulseeditor.transcode.Transcode
import com.margelo.nitro.pulseeditor.transcode.addAacRollGroup
import com.margelo.nitro.pulseeditor.transcode.audioProcessors
import com.margelo.nitro.pulseeditor.transcode.isFaststart
import com.margelo.nitro.pulseeditor.transcode.moveMoovToFront
import java.io.File
import java.io.FileNotFoundException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A still image as one clip in the recorder's format (see `ImageClipOptions`), so it can join the
 * camera's recordings like a conformed import:
 * - decoded at most canvas-sized with ImageDecoder, which applies the EXIF orientation, into 8-bit
 *   sRGB (a wide-gamut or HDR photo is converted, an Ultra HDR gain map left out);
 * - drawn once onto a canvas-sized bitmap filled with the background colour, fitted and centered,
 *   transparency composited onto the background;
 * - that bitmap exported as a Media3 image item at a constant frame rate (every frame present: 90
 *   for 3 s at 30) through conform's chain: the canvas (Presentation), turned into the coded
 *   orientation the rotation tag turns upright, written with exactly that tag, H.264 at the
 *   bitrate; the sequence asks for an audio track too, so Media3 adds silence, put through
 *   conform's audio processors into the requested layout and encoded as AAC;
 * - the output checked like a conform's.
 * An image this phone can't decode (HEIC without a HEIF decoder, say) is rejected, and so is any
 * image below Android 9, which has no ImageDecoder.
 */
@OptIn(UnstableApi::class)
object ImageClip {
  suspend fun run(context: Context, uri: String, options: ImageClipOptions, progress: MergeProgress): ConformResult {
    if (Build.VERSION.SDK_INT < 28) throw MergeException("Making a clip from an image needs Android 9 or later.")
    Merge.removeStaleOutputs(context)
    progress(0.0)
    validate(options)
    val degrees = ((options.rotation.roundToInt() % 360) + 360) % 360
    if (degrees % 90 != 0) throw MergeException("The rotation must be 0, 90, 180 or 270.")
    val width = options.width.roundToInt()
    val height = options.height.roundToInt()
    val fps = options.fps.roundToInt()
    val background = color(options.background)
    val frames = (options.durationMs / 1000 * fps).roundToInt()
    // Whole ms that Media3's ConstantRateTimestampIterator turns back into exactly `frames` frames.
    val durationMs = (frames * 1000L + fps / 2) / fps

    val picture = withContext(Dispatchers.IO) {
      val image = decode(context, uri, max(width, height))
      try {
        draw(image, width, height, background)
      } finally {
        image.recycle()
      }
    }
    val output = Merge.outputFile(context, "image")
    try {
      val settings = Transcode.Settings(
        bitrate = options.bitrate.toInt(), durationMs = durationMs.toDouble(), portrait = true,
        rotationTag = degrees, input = Transcode.Input.IMAGE,
        // Media3 owns the picture once loaded: it recycles it after the last frame.
        bitmapLoader = PictureLoader(picture),
        // The last frame lasts 1/fps and the picture ends with the clip, as on iOS.
        videoDurationUs = (frames * 1_000_000L + fps / 2) / fps,
      )
      val outcome = Transcode.run(context, composition(uri, options, degrees, durationMs, fps), output, settings) {
        progress(it * 0.98)
      }
      return withContext(Dispatchers.IO) {
        moveMoovToFront(output)
        if (!addAacRollGroup(output)) {
          Log.w("PulseEditor", "Couldn't add the AAC roll group: Apple players may play the audio 44 ms early")
        }
        // Every frame, as iOS appends them (0 = Media3 didn't count them, which fails too).
        val written = outcome.result.videoFrameCount
        if (written != frames) {
          throw MergeException((listOf("The image clip came out with $written of $frames frames.") + outcome.fallbacks).joinToString("; "))
        }
        val out = verify(context, output, options, degrees, fps, frames, outcome.fallbacks)
        ConformResult(
          uri = Uri.fromFile(output).toString(),
          durationMs = out.durationMs,
          encoded = true,
          bitrate = if (outcome.result.averageVideoBitrate > 0) outcome.result.averageVideoBitrate.toDouble() else out.video?.bitrate ?: -1.0,
        )
      }.also { progress(1.0) }
    } catch (e: Throwable) {
      output.delete()
      throw e
    }
  }

  /**
   * A guard, not the check: `imageClip` in JS checks the options and says what's wrong. This keeps
   * a direct native call from making a clip JS would refuse (or a huge one); the limits are JS's.
   */
  private fun validate(o: ImageClipOptions) {
    fun within(value: Double, min: Double, max: Double) = value.isFinite() && value in min..max
    fun side(value: Double) = within(value, 2.0, 4096.0) && value % 2 == 0.0
    val valid = side(o.width) && side(o.height) && within(o.rotation, -360.0, 360.0) && within(o.fps, 1.0, 240.0) &&
      within(o.bitrate, 1.0, 1_000_000_000.0) &&
      within(o.audio.sampleRate, 8000.0, 192_000.0) && o.audio.sampleRate % 1 == 0.0 &&
      (o.audio.channels == 1.0 || o.audio.channels == 2.0) &&
      within(o.durationMs, 0.0, 60_000.0) && (o.durationMs / 1000 * o.fps.roundToInt()).roundToInt() >= 2
    if (!valid) throw MergeException("The image clip's options are invalid (imageClip() says which).")
  }

  private fun composition(uri: String, options: ImageClipOptions, degrees: Int, durationMs: Long, fps: Int): Composition {
    // The picture comes from PictureLoader; the MIME type only makes Media3 treat the item as an image.
    val media = MediaItem.Builder()
      .setUri(mediaUri(uri))
      .setMimeType(MimeTypes.IMAGE_PNG)
      .setImageDurationMs(durationMs)
      .build()
    val video = buildList<Effect> {
      add(Presentation.createForWidthAndHeight(options.width.roundToInt(), options.height.roundToInt(), Presentation.LAYOUT_SCALE_TO_FIT))
      // Upright canvas → coded orientation: Media3 turns counter-clockwise, the tag clockwise.
      if (degrees != 0) add(ScaleAndRotateTransformation.Builder().setRotationDegrees(degrees.toFloat()).build())
    }
    val audio = audioProcessors(options.audio.sampleRate.roundToInt(), options.audio.channels.roundToInt())
    val item = EditedMediaItem.Builder(media).setFrameRate(fps).setEffects(Effects(audio, video)).build()
    // Audio in the track types: the image has none, so Media3 generates silence for it.
    val sequence = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO)).addItem(item).build()
    return Composition.Builder(sequence).build()
  }

  /** Hands Media3 the picture already drawn, whatever the item's URI. */
  private class PictureLoader(private val picture: Bitmap) : BitmapLoader {
    override fun supportsMimeType(mimeType: String): Boolean = true
    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = Futures.immediateFuture(picture)
    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = Futures.immediateFuture(picture)
  }

  private const val UNREADABLE = "This image can't be read on this device."

  /** The image upright, at most about `maxDimension` on its long side, as a software bitmap. */
  @RequiresApi(28)
  private fun decode(context: Context, uri: String, maxDimension: Int): Bitmap {
    val parsed = mediaUri(uri)
    try {
      val source = if (parsed.scheme == ContentResolver.SCHEME_FILE) ImageDecoder.createSource(File(parsed.path!!))
      else ImageDecoder.createSource(context.contentResolver, parsed)
      return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        val longSide = max(info.size.width, info.size.height)
        if (longSide > maxDimension) {
          val scale = maxDimension.toDouble() / longSide
          decoder.setTargetSize(
            max(1, (info.size.width * scale).roundToInt()), max(1, (info.size.height * scale).roundToInt()))
        }
      }
    } catch (e: FileNotFoundException) {
      throw MergeException("The image's file is missing.")
    } catch (e: CancellationException) {
      throw e
    } catch (e: MergeException) {
      throw e
    } catch (e: Exception) {
      Log.w("PulseEditor", "Couldn't decode the image $uri", e)
      throw MergeException(UNREADABLE)
    } catch (e: OutOfMemoryError) {
      throw MergeException("This image is too large to read on this device.")
    }
  }

  /** `#RRGGBB` (or `#RGB`) → an opaque ARGB colour. */
  internal fun color(hex: String): Int {
    var digits = hex.trim().removePrefix("#")
    if (digits.length == 3) digits = digits.map { "$it$it" }.joinToString("")
    if (digits.length != 6 || !digits.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) {
      throw MergeException("The background must be a colour like #000000.")
    }
    return (0xFF000000 or digits.toLong(16)).toInt()
  }

  /** The one picture of the clip: the canvas filled with the background, the image fitted and centered on it. */
  private fun draw(image: Bitmap, width: Int, height: Int, background: Int): Bitmap {
    val picture = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(picture)
    canvas.drawColor(background)
    val scale = min(width.toFloat() / image.width, height.toFloat() / image.height)
    val w = (image.width * scale).roundToInt()
    val h = (image.height * scale).roundToInt()
    val left = (width - w) / 2
    val top = (height - h) / 2
    canvas.drawBitmap(image, null, Rect(left, top, left + w, top + h), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
    picture.setHasAlpha(false)
    return picture
  }

  /**
   * The output must be what was asked for: H.264 8-bit SDR on the canvas with the tag, at the
   * frame rate, the sound in the requested layout, the picture `frames` / fps long (within half a
   * frame, as on iOS), faststart. A rejection names any encoder fallback Media3 applied, the
   * likely cause.
   */
  private suspend fun verify(
    context: Context, output: File, options: ImageClipOptions, degrees: Int, fps: Int, frames: Int,
    fallbacks: List<String>,
  ): ProbeResult {
    fun fail(reason: String) = MergeException((listOf(reason) + fallbacks).joinToString("; "))
    val out = Probe.read(context, Uri.fromFile(output).toString())
    val v = out.video ?: throw fail("The image clip has no picture.")
    val swapped = v.rotation.roundToInt() % 180 != 0
    val (w, h) = if (swapped) v.height to v.width else v.width to v.height
    if (v.codec != "h264" || v.transfer != Transfer.SDR || v.bitDepth > 8 || v.mirrored ||
      v.rotation.roundToInt() != degrees || w.roundToInt() != options.width.roundToInt() || h.roundToInt() != options.height.roundToInt()
    ) {
      throw fail("The image clip came out ${v.codec} ${v.width.toInt()}x${v.height.toInt()} rotated ${v.rotation.toInt()}.")
    }
    if (v.fps > 0 && v.fps.roundToInt() != fps) throw fail("The image clip came out at ${v.fps.roundToInt()} fps, not $fps.")
    val a = out.audio ?: throw fail("The image clip has no sound track.")
    if (a.codec != "aac" || a.sampleRate.roundToInt() != options.audio.sampleRate.roundToInt() ||
      a.channels.roundToInt() != options.audio.channels.roundToInt()
    ) {
      throw fail("The image clip's sound came out ${a.codec} ${a.sampleRate.toInt()} Hz ${a.channels.toInt()} ch.")
    }
    // The video track's own length, never the file's (the sound's): a zero-length last frame
    // would hide behind it.
    val want = frames * 1000.0 / fps
    if (v.durationMs <= 0 || abs(v.durationMs - want) > 500.0 / fps) {
      throw fail("The image clip's picture is ${v.durationMs.roundToInt()} ms, expected ${want.roundToInt()} ms.")
    }
    if (!isFaststart(output)) throw fail("The image clip isn't faststart.")
    return out
  }
}
