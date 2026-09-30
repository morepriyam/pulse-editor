package com.margelo.nitro.pulseeditor.transcode

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.TransformationRequest
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * One Media3 Transformer export to H.264 + AAC, shared by merge and conform. Transformer runs on
 * the main looper (Media3 wants one application thread) with:
 * - the video bitrate asked for and a 2 s keyframe interval;
 * - decoder fallback on, so a decoder that fails to start hands over to the next one (a Dolby
 *   Vision decoder to the plain HEVC decoder for the base layer, say);
 * - portrait encoding only when asked: Media3 encodes portrait as landscape plus a rotation tag
 *   by default, because more encoders support landscape.
 * Progress is polled every 100 ms. Cancelling the coroutine cancels the export and deletes the
 * partial output. Any fallback Media3's encoder factory applied is returned with the result.
 */
@OptIn(UnstableApi::class)
internal object Transcode {
  class Settings(val bitrate: Int, val portrait: Boolean = false)

  class Outcome(val result: ExportResult, val fallbacks: List<String>)

  suspend fun run(
    context: Context, composition: Composition, output: File, settings: Settings, progress: (Double) -> Unit,
  ): Outcome {
    val main = Handler(Looper.getMainLooper())
    val fallbacks = mutableListOf<String>()
    return suspendCancellableCoroutine { continuation ->
      var transformer: Transformer? = null
      val poll = object : Runnable {
        override fun run() {
          val t = transformer ?: return
          val holder = ProgressHolder()
          if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) progress(holder.progress / 100.0)
          main.postDelayed(this, 100)
        }
      }
      continuation.invokeOnCancellation {
        main.post {
          main.removeCallbacks(poll)
          transformer?.cancel()
          output.delete()
        }
      }
      main.post {
        if (!continuation.isActive) return@post
        val t = transformer(context, settings, object : Transformer.Listener {
          override fun onCompleted(composition: Composition, result: ExportResult) {
            main.removeCallbacks(poll)
            if (continuation.isActive) continuation.resume(Outcome(result, fallbacks.toList()))
          }

          override fun onError(composition: Composition, result: ExportResult, exception: ExportException) {
            main.removeCallbacks(poll)
            output.delete()
            if (continuation.isActive) continuation.resumeWithException(exception)
          }

          override fun onFallbackApplied(
            composition: Composition, original: TransformationRequest, fallback: TransformationRequest,
          ) {
            fallbacks += describe(original, fallback)
          }
        })
        transformer = t
        output.parentFile?.mkdirs()
        output.delete()
        t.start(composition, output.absolutePath)
        main.postDelayed(poll, 100)
      }
    }
  }

  private fun transformer(context: Context, settings: Settings, listener: Transformer.Listener): Transformer {
    val decoders = DefaultDecoderFactory.Builder(context).setEnableDecoderFallback(true).build()
    val encoders = DefaultEncoderFactory.Builder(context)
      .setRequestedVideoEncoderSettings(
        VideoEncoderSettings.Builder().setBitrate(settings.bitrate).setiFrameIntervalSeconds(2f).build(),
      )
      .build()
    return Transformer.Builder(context)
      .setLooper(Looper.getMainLooper())
      .setVideoMimeType(MimeTypes.VIDEO_H264)
      .setAudioMimeType(MimeTypes.AUDIO_AAC)
      .setAssetLoaderFactory(DefaultAssetLoaderFactory(context, decoders, Clock.DEFAULT, /* logSessionId= */ null))
      .setEncoderFactory(encoders)
      .setPortraitEncodingEnabled(settings.portrait)
      .addListener(listener)
      .build()
  }

  private fun describe(original: TransformationRequest, fallback: TransformationRequest): String {
    val changes = mutableListOf<String>()
    if (original.videoMimeType != fallback.videoMimeType) changes += "video ${original.videoMimeType} → ${fallback.videoMimeType}"
    if (original.audioMimeType != fallback.audioMimeType) changes += "audio ${original.audioMimeType} → ${fallback.audioMimeType}"
    if (original.outputHeight != fallback.outputHeight) changes += "height ${original.outputHeight} → ${fallback.outputHeight}"
    if (original.hdrMode != fallback.hdrMode) changes += "HDR mode ${original.hdrMode} → ${fallback.hdrMode}"
    return "encoder fallback: " + changes.ifEmpty { listOf("settings changed") }.joinToString(", ")
  }
}
