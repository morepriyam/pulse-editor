package com.margelo.nitro.pulseeditor.transcode

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.muxer.Muxer
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.InAppMp4Muxer
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
 *   by default, because more encoders support landscape;
 * - the AAC encoder's measured priming as its encoder delay ([AacPriming]), so the audio starts in
 *   sync;
 * - on Android 15+, audio decoded in large batches ([BatchedAudioDecoderFactory]).
 * Progress is polled every 100 ms. Cancelling the coroutine cancels the export and deletes the
 * partial output. Any fallback Media3's encoder factory applied is returned with the result.
 */
@OptIn(UnstableApi::class)
internal object Transcode {
  /**
   * `durationMs` is the output's expected length: it sizes the space kept at the front of the file
   * for the index (moov), see [moovReserveBytes].
   */
  /**
   * `copyVideo`: the composition copies its video (a join), so nothing should re-encode it.
   * `rotationTag`: write the video with this orientation tag (clockwise degrees) instead of the
   * one Transformer picks; the frames must already be in the matching coded orientation.
   * `oneFile`: the export reads a single file (conform), which errors then name instead of a clip.
   */
  class Settings(
    val bitrate: Int, val durationMs: Double, val portrait: Boolean = false, val copyVideo: Boolean = false,
    val rotationTag: Int? = null, val oneFile: Boolean = false,
  )

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
            // The message is generic ("Video frame processing error"); the cause chain says why.
            Log.w("PulseEditor", "Export failed: ${exception.errorCodeName}", exception)
            main.removeCallbacks(poll)
            output.delete()
            if (continuation.isActive) continuation.resumeWithException(IllegalStateException(plainMessage(exception, settings.oneFile), exception))
          }

          override fun onFallbackApplied(
            composition: Composition, original: TransformationRequest, fallback: TransformationRequest,
          ) {
            fallbacks += describe(original, fallback)
            Log.i("PulseEditor", fallbacks.last())
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

  /**
   * What went wrong, in words a person can act on. Media3's own message can be a whole codec
   * configuration dump; the full exception is logged (above) for diagnosis.
   */
  private fun plainMessage(e: ExportException, oneFile: Boolean): String = when (e.errorCode) {
    ExportException.ERROR_CODE_DECODER_INIT_FAILED,
    ExportException.ERROR_CODE_DECODING_FAILED,
    ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
      if (oneFile) "This phone couldn't decode the video." else "This phone couldn't decode one of the clips."
    ExportException.ERROR_CODE_ENCODER_INIT_FAILED,
    ExportException.ERROR_CODE_ENCODING_FAILED,
    ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED -> "This phone's encoder couldn't encode the video."
    ExportException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED -> "Couldn't process the video's frames."
    ExportException.ERROR_CODE_AUDIO_PROCESSING_FAILED -> "Couldn't process the audio."
    ExportException.ERROR_CODE_MUXING_FAILED,
    ExportException.ERROR_CODE_MUXING_TIMEOUT,
    ExportException.ERROR_CODE_MUXING_APPEND -> "Couldn't write the video file."
    ExportException.ERROR_CODE_IO_FILE_NOT_FOUND -> if (oneFile) "The video's file is missing." else "A clip's file is missing."
    ExportException.ERROR_CODE_IO_NO_PERMISSION -> if (oneFile) "The video's file can't be read." else "A clip's file can't be read."
    else -> when {
      e.errorCode in 2000..2999 -> if (oneFile) "Couldn't read the video's file." else "Couldn't read a clip's file."
      oneFile -> "The video couldn't be converted."
      else -> "The video couldn't be exported."
    }
  }

  private fun transformer(context: Context, settings: Settings, listener: Transformer.Listener): Transformer {
    val defaultDecoders = DefaultDecoderFactory.Builder(context).setEnableDecoderFallback(true).build()
    val decoders = if (Build.VERSION.SDK_INT >= 35) BatchedAudioDecoderFactory(context, defaultDecoders) else defaultDecoders
    val encoders = PrimingCorrectedEncoderFactory(
      DefaultEncoderFactory.Builder(context)
        .setRequestedVideoEncoderSettings(
          VideoEncoderSettings.Builder().setBitrate(settings.bitrate).setiFrameIntervalSeconds(2f).build(),
        )
        .build(),
      copyVideo = settings.copyVideo,
    )
    return Transformer.Builder(context)
      .setLooper(Looper.getMainLooper())
      .setVideoMimeType(MimeTypes.VIDEO_H264)
      .setAudioMimeType(MimeTypes.AUDIO_AAC)
      .setAssetLoaderFactory(DefaultAssetLoaderFactory(context, decoders, Clock.DEFAULT, /* logSessionId= */ null))
      .setEncoderFactory(encoders)
      .setMuxerFactory(muxers(settings))
      .setPortraitEncodingEnabled(settings.portrait)
      .addListener(listener)
      .build()
  }

  private fun muxers(settings: Settings): Muxer.Factory {
    val muxers = InAppMp4Muxer.Factory().setFreeSpaceAfterFileTypeBoxBytes(moovReserveBytes(settings.durationMs))
    return settings.rotationTag?.let { RotationTagMuxerFactory(muxers, it) } ?: muxers
  }

  /**
   * Extra space for the index at the front of the file, beyond the 400 KB Media3 always keeps. Its
   * muxer writes one chunk per sample, so the index grows by about 1.2 KB a second (measured:
   * 349 KB for a 5-minute join); past ~5.5 minutes it no longer fits and goes to the end.
   * [moveMoovToFront] then moves it into this space.
   */
  private fun moovReserveBytes(durationMs: Double): Int {
    val needed = durationMs / 1000 * MOOV_BYTES_PER_SECOND
    return maxOf(0.0, needed - MEDIA3_MOOV_RESERVE_BYTES + MOOV_MARGIN_BYTES).toInt()
  }

  private const val MOOV_BYTES_PER_SECOND = 1_500.0
  private const val MEDIA3_MOOV_RESERVE_BYTES = 400_000.0
  private const val MOOV_MARGIN_BYTES = 64_000.0

  private fun describe(original: TransformationRequest, fallback: TransformationRequest): String {
    val changes = mutableListOf<String>()
    if (original.videoMimeType != fallback.videoMimeType) changes += "video ${original.videoMimeType} → ${fallback.videoMimeType}"
    if (original.audioMimeType != fallback.audioMimeType) changes += "audio ${original.audioMimeType} → ${fallback.audioMimeType}"
    if (original.outputHeight != fallback.outputHeight) changes += "height ${original.outputHeight} → ${fallback.outputHeight}"
    if (original.hdrMode != fallback.hdrMode) changes += "HDR mode ${original.hdrMode} → ${fallback.hdrMode}"
    return "encoder fallback: " + changes.ifEmpty { listOf("settings changed") }.joinToString(", ")
  }
}
