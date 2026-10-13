package com.margelo.nitro.pulseeditor.transcode

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaFormat
import android.media.metrics.LogSessionId
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.MediaFormatUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.transformer.Codec
import androidx.media3.transformer.DefaultCodec
import androidx.media3.transformer.ExportException
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Media3's decoder factory, with audio decoded in large batches on Android 15+.
 *
 * Transformer's own audio decoder ([DefaultCodec]) queues one access unit per codec buffer and
 * polls for one decoded frame at a time, and each of those is a round trip to the codec's process
 * (S24: ~2 ms per AAC frame, so ~92 ms per second of audio; an 8-minute join took 44 s against
 * 4 s on the iPhone). With the platform's large audio frames (FEATURE_MultipleFrames, async mode
 * only) one input buffer carries ~64 KB of access units and one output buffer up to 256 KB of PCM,
 * as [com.margelo.nitro.pulseeditor.ExtractAudio] already does. Video, and audio on a decoder that
 * can't batch or fails to start this way, go to Media3's factory unchanged.
 */
@OptIn(UnstableApi::class)
internal class BatchedAudioDecoderFactory(
  private val context: Context,
  private val decoders: Codec.DecoderFactory,
) : Codec.DecoderFactory {
  override fun createForAudioDecoding(format: Format, logSessionId: LogSessionId?): Codec {
    if (Build.VERSION.SDK_INT < 35) return decoders.createForAudioDecoding(format, logSessionId)
    // The decoder Media3's factory would try first (same selector, same ordering).
    val decoder = runCatching {
      MediaCodecUtil.getDecoderInfosSortedByFullFormatSupport(
        context,
        MediaCodecUtil.getDecoderInfosSoftMatch(MediaCodecSelector.DEFAULT, format, false, false),
        format,
      ).firstOrNull()
    }.getOrNull()
    if (decoder == null || decoder.capabilities?.isFeatureSupported(CodecCapabilities.FEATURE_MultipleFrames) != true) {
      logOnce("audio decoder: default (${decoder?.name ?: "no decoder found"} doesn't batch)")
      return decoders.createForAudioDecoding(format, logSessionId)
    }
    return try {
      BatchedAudioDecoder(format, decoder.name, decoder.codecMimeType, logSessionId).also {
        logOnce("audio decoder: batched (${it.name}, input ${it.inputBytes / 1024} KB, output ${BATCH_OUTPUT_BYTES / 1024} KB)")
      }
    } catch (e: Exception) {
      Log.w("PulseEditor", "The batched audio decoder ${decoder.name} didn't start; using Media3's", e)
      decoders.createForAudioDecoding(format, logSessionId)
    }
  }

  override fun createForVideoDecoding(
    format: Format, outputSurface: Surface, requestSdrToneMapping: Boolean, logSessionId: LogSessionId?,
  ): Codec = decoders.createForVideoDecoding(format, outputSurface, requestSdrToneMapping, logSessionId)

  private companion object {
    val logged = AtomicBoolean()

    fun logOnce(message: String) {
      if (logged.compareAndSet(false, true)) Log.i("PulseEditor", message)
    }
  }
}

private const val BATCH_INPUT_BYTES = 64 * 1024
private const val BATCH_OUTPUT_BYTES = 256 * 1024
/** Room kept for one access unit when the container gives no maximum (AAC needs < 1 KB a channel). */
private const val MIN_SAMPLE_BYTES = 16 * 1024
private const val UNKNOWN_SAMPLE_BYTES = 64 * 1024
private const val NONE = -1

/**
 * A [Codec] over an async MediaCodec configured for large audio frames.
 *
 * Input: Transformer fills one access unit at a time into a staging buffer handed out by
 * [maybeDequeueInputBuffer]; [queueInputBuffer] copies it into the codec input buffer being filled,
 * which is queued whole ([MediaCodec.queueInputBuffers]) once the next unit might not fit, at end of
 * stream, or when Transformer comes back for input without having queued any (its source has
 * nothing more for now, so the codec mustn't wait for a full batch). The codec returns the output of
 * each queued batch without waiting for the next one (C2's MultiAccessUnitHelper finalizes a large
 * output frame once all of its input frame's units are decoded).
 *
 * Output: one buffer of many PCM frames; Transformer copies whatever size it gets into its audio
 * graph and reads only the first buffer's timestamp (the stream's start).
 *
 * Priming: the same MediaFormat keys as Media3's decoder (encoder-delay / encoder-padding from the
 * container), so the platform cuts the priming and end padding exactly as it does for the default
 * decoder; Transformer itself never trims them. With large frames the platform also shifts the
 * timestamps (by a rounded per-byte duration: the first output can land before the first input),
 * so the timestamps reported here are the first queued input's plus the PCM handed out so far.
 *
 * Codec callbacks run on a thread of their own; every other call comes from Transformer's asset
 * loader thread. State the two share is guarded by [lock].
 */
@RequiresApi(35)
@OptIn(UnstableApi::class)
private class BatchedAudioDecoder(
  private val format: Format, name: String, codecMimeType: String, logSessionId: LogSessionId?,
) : Codec {
  private class Output(val index: Int, val offset: Int, val size: Int, val flags: Int, val codecTimeUs: Long)

  private val maxSampleBytes = if (format.maxInputSize > 0) maxOf(format.maxInputSize, MIN_SAMPLE_BYTES) else UNKNOWN_SAMPLE_BYTES
  val inputBytes = maxOf(BATCH_INPUT_BYTES, 4 * maxSampleBytes)
  private val mediaFormat = MediaFormatUtil.createMediaFormatFromFormat(format).apply {
    setString(MediaFormat.KEY_MIME, codecMimeType)
    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, inputBytes)
    setInteger(MediaFormat.KEY_BUFFER_BATCH_MAX_OUTPUT_SIZE, BATCH_OUTPUT_BYTES)
    setInteger(MediaFormat.KEY_BUFFER_BATCH_THRESHOLD_OUTPUT_SIZE, BATCH_OUTPUT_BYTES / 2)
    // As Media3's factory does (TransformerUtil.Api35).
    if (logSessionId != null && logSessionId != LogSessionId.LOG_SESSION_ID_NONE) setString("log-session-id", logSessionId.stringId)
  }
  private val thread = HandlerThread("pulse-editor-audio-decoder").apply { start() }
  private val codec: MediaCodec

  private val lock = Any()
  // Shared with the callbacks (under lock).
  private val freeInputs = ArrayDeque<Int>()
  /** Output buffers ([Output]) and output format changes ([Format]), in the codec's order. */
  private val outputs = ArrayDeque<Any>()
  private var error: Exception? = null

  // Asset loader thread only.
  private val staging = ByteBuffer.allocateDirect(maxSampleBytes)
  private var handedOut = false
  private var staged = false
  private var stagedTimeUs = 0L
  private var fill = NONE
  private var fillBuffer: ByteBuffer? = null
  private var fillInfos = ArrayDeque<MediaCodec.BufferInfo>()
  private var fillBytes = 0
  private var inputEnded = false
  private var endPending = false
  private var firstInputUs = C.TIME_UNSET
  private var outputFormat: Format? = null
  private var current: Output? = null
  private var currentBuffer: ByteBuffer? = null
  private val currentInfo = MediaCodec.BufferInfo()
  private var nextOutputUs = C.TIME_UNSET
  private var outputEnded = false

  init {
    var created: MediaCodec? = null
    try {
      created = MediaCodec.createByCodecName(name)
      created.setCallback(Callbacks(), Handler(thread.looper))
      created.configure(mediaFormat, null, null, 0)
      created.start()
      codec = created
    } catch (e: Exception) {
      created?.release()
      thread.quitSafely()
      throw e
    }
  }

  private inner class Callbacks : MediaCodec.Callback() {
    override fun onInputBufferAvailable(c: MediaCodec, index: Int) {
      synchronized(lock) { freeInputs.add(index) }
    }

    override fun onOutputBufferAvailable(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
      val output = Output(index, info.offset, info.size, info.flags, info.presentationTimeUs)
      synchronized(lock) { outputs.add(output) }
    }

    // The access units of one buffer are contiguous from the first one's offset.
    override fun onOutputBuffersAvailable(c: MediaCodec, index: Int, infos: ArrayDeque<MediaCodec.BufferInfo>) {
      val first = infos.first
      val output = Output(index, first.offset, infos.sumOf { it.size }, infos.fold(0) { f, i -> f or i.flags }, first.presentationTimeUs)
      synchronized(lock) { outputs.add(output) }
    }

    override fun onOutputFormatChanged(c: MediaCodec, f: MediaFormat) {
      val converted = toFormat(f)
      synchronized(lock) { outputs.add(converted) }
    }

    override fun onError(c: MediaCodec, e: MediaCodec.CodecException) {
      synchronized(lock) { if (error == null) error = e }
    }
  }

  override fun getConfigurationFormat(): Format = format

  override fun getName(): String = codec.canonicalName

  override fun getInputSurface(): Surface = throw UnsupportedOperationException("An audio decoder has no input surface.")

  override fun maybeDequeueInputBuffer(inputBuffer: DecoderInputBuffer): Boolean {
    checkError()
    // Handed out last time and never queued: the source had nothing more for now.
    if (handedOut) submitFill()
    pumpInput()
    if (inputEnded || staged) return false
    inputBuffer.data = staging
    inputBuffer.clear()
    handedOut = true
    return true
  }

  override fun queueInputBuffer(inputBuffer: DecoderInputBuffer) {
    checkError()
    check(!inputEnded) { "Input buffer can not be queued after the input stream has ended." }
    handedOut = false
    if (inputBuffer.isEndOfStream) {
      inputEnded = true
      endPending = true
    } else {
      staged = true
      stagedTimeUs = inputBuffer.timeUs
      if (firstInputUs == C.TIME_UNSET) firstInputUs = inputBuffer.timeUs
    }
    inputBuffer.data = null
    pumpInput()
  }

  override fun signalEndOfInputStream() {
    throw UnsupportedOperationException("An audio decoder has no input surface.")
  }

  override fun getInputFormat(): Format = guarded { toFormat(codec.inputFormat) }

  override fun getOutputFormat(): Format? {
    checkError()
    pumpInput()
    if (current == null) synchronized(lock) { applyFormatChanges() }
    return outputFormat
  }

  override fun getOutputBuffer(): ByteBuffer? = if (dequeueOutput()) currentBuffer else null

  override fun getOutputBufferInfo(): MediaCodec.BufferInfo? = if (dequeueOutput()) currentInfo else null

  override fun releaseOutputBuffer(render: Boolean) {
    val output = current ?: return
    guarded { codec.releaseOutputBuffer(output.index, false) }
    nextOutputUs += durationUs(output.size)
    if (output.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputEnded = true
    current = null
    currentBuffer = null
  }

  override fun releaseOutputBuffer(renderPresentationTimeUs: Long) = releaseOutputBuffer(false)

  override fun isEnded(): Boolean = outputEnded && current == null

  override fun release() {
    current = null
    currentBuffer = null
    codec.release()
    thread.quitSafely()
  }

  /** Makes the next decoded buffer current, if there is one. */
  private fun dequeueOutput(): Boolean {
    checkError()
    pumpInput()
    while (current == null && !outputEnded) {
      val output = synchronized(lock) {
        applyFormatChanges()
        outputs.pollFirst() as Output?
      } ?: return false
      if (output.size == 0) {
        // Only a flag (end of stream), or units the priming cut emptied.
        guarded { codec.releaseOutputBuffer(output.index, false) }
        if (output.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputEnded = true
        continue
      }
      if (nextOutputUs == C.TIME_UNSET) {
        nextOutputUs = if (firstInputUs != C.TIME_UNSET) firstInputUs else output.codecTimeUs
        logFirstOutput(output)
      }
      val buffer = guarded { codec.getOutputBuffer(output.index) } ?: throw failure(IllegalStateException("No output buffer ${output.index}."))
      buffer.position(output.offset).limit(output.offset + output.size)
      // End of stream is reported by isEnded once this buffer is released, as DefaultCodec does.
      currentInfo.set(output.offset, output.size, nextOutputUs, 0)
      current = output
      currentBuffer = buffer
    }
    return current != null
  }

  /** Applies format changes queued ahead of the next output buffer. Call under [lock]. */
  private fun applyFormatChanges() {
    while (true) {
      val next = outputs.peekFirst() as? Format ?: return
      outputs.pollFirst()
      outputFormat = next
    }
  }

  /** Moves the staged unit into a codec buffer and queues a pending end of stream, as buffers allow. */
  private fun pumpInput() {
    if (staged) {
      val size = staging.remaining()
      var target = fillBuffer
      if (target != null && fillBytes + size > target.capacity()) {
        submitFill()
        target = null
      }
      if (target == null) {
        val index = takeFreeInput() ?: return
        target = guarded { codec.getInputBuffer(index) } ?: throw failure(IllegalStateException("No input buffer $index."))
        if (size > target.capacity()) throw failure(IllegalStateException("An audio frame ($size B) is larger than the decoder's input buffer (${target.capacity()} B)."))
        fill = index
        fillBuffer = target
      }
      target.position(fillBytes)
      target.put(staging)
      fillInfos.add(MediaCodec.BufferInfo().apply { set(fillBytes, size, stagedTimeUs, 0) })
      fillBytes += size
      staged = false
      if (target.capacity() - fillBytes < maxSampleBytes) submitFill()
    }
    if (endPending) {
      if (fill != NONE) {
        fillInfos.last.flags = MediaCodec.BUFFER_FLAG_END_OF_STREAM
        submitFill()
      } else {
        val index = takeFreeInput() ?: return
        guarded { codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM) }
      }
      endPending = false
    }
  }

  /** Queues the codec buffer being filled, if it holds anything. */
  private fun submitFill() {
    if (fill == NONE || fillInfos.isEmpty()) return
    val infos = fillInfos
    guarded { codec.queueInputBuffers(fill, infos) }
    fill = NONE
    fillBuffer = null
    fillInfos = ArrayDeque()
    fillBytes = 0
  }

  private fun takeFreeInput(): Int? = synchronized(lock) { freeInputs.pollFirst() }

  private fun durationUs(bytes: Int): Long {
    val f = outputFormat ?: return 0
    if (f.sampleRate <= 0 || f.channelCount <= 0) return 0
    return Util.sampleCountToDurationUs((bytes / Util.getPcmFrameSize(f.pcmEncoding, f.channelCount)).toLong(), f.sampleRate)
  }

  /** Mirrors DefaultCodec's conversion: decoded audio without an encoding is 16-bit PCM. */
  private fun toFormat(f: MediaFormat): Format {
    val converted = MediaFormatUtil.createFormatFromMediaFormat(f)
    val builder = converted.buildUpon().setMetadata(format.metadata)
    if (converted.pcmEncoding == Format.NO_VALUE && converted.sampleMimeType == MimeTypes.AUDIO_RAW) {
      builder.setPcmEncoding(DefaultCodec.DEFAULT_PCM_ENCODING)
    }
    return builder.build()
  }

  private fun logFirstOutput(output: Output) {
    if (!firstOutputLogged.compareAndSet(false, true)) return
    val f = runCatching { codec.outputFormat }.getOrNull()
    Log.d(
      "PulseEditor",
      "batched audio decoder: first output at $nextOutputUs us (codec said ${output.codecTimeUs} us), " +
        "${output.size} B; codec batch ${f?.getIntegerOrNull(MediaFormat.KEY_BUFFER_BATCH_MAX_OUTPUT_SIZE)} B, " +
        "threshold ${f?.getIntegerOrNull(MediaFormat.KEY_BUFFER_BATCH_THRESHOLD_OUTPUT_SIZE)} B",
    )
  }

  private fun MediaFormat.getIntegerOrNull(key: String): Int? = if (containsKey(key)) getInteger(key) else null

  private fun checkError() {
    synchronized(lock) { error }?.let { throw failure(it) }
  }

  private inline fun <T> guarded(block: () -> T): T =
    try {
      block()
    } catch (e: RuntimeException) {
      throw failure(e)
    }

  private fun failure(cause: Exception): ExportException = ExportException.createForCodec(
    cause,
    ExportException.ERROR_CODE_DECODING_FAILED,
    ExportException.CodecInfo(mediaFormat.toString(), /* isVideo= */ false, /* isDecoder= */ true, codec.name),
  )

  private companion object {
    val firstOutputLogged = AtomicBoolean()
  }
}
