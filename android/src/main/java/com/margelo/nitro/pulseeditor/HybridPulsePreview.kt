package com.margelo.nitro.pulseeditor

import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.View
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import androidx.media3.transformer.CompositionPlayer
import com.facebook.proguard.annotations.DoNotStrip
import com.facebook.react.uimanager.ThemedReactContext
import com.margelo.nitro.core.Promise
import com.margelo.nitro.pulseeditor.merge.Merge
import com.margelo.nitro.pulseeditor.preview.PreviewComposition
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The preview (prototype): one long-lived Media3 CompositionPlayer drawing into a TextureView,
 * playing the composition merge would export (PreviewComposition), at the view's size. A new
 * clip list rebuilds the composition and swaps it in at the current position. Everything that
 * touches the player runs on the main looper, where the player lives.
 *
 * A TextureView rather than a SurfaceView: CompositionPlayer's frame callback fires before its
 * effects run (rotate, crop...), so it can't tell when an edited frame is on screen; the
 * TextureView reports every frame that reaches it. Drafts are SDR (conform), which a TextureView
 * shows as well as a SurfaceView.
 */
@OptIn(UnstableApi::class)
@DoNotStrip
class HybridPulsePreview(private val context: ThemedReactContext) : HybridPulsePreviewSpec() {
  private val texture = TextureView(context)
  override val view: View = texture
  private var videoSurface: Surface? = null
  /** The surface the player draws into (CompositionPlayer only clears the one it was given). */
  private var attachedSurface: Surface? = null

  private val main = Handler(Looper.getMainLooper())
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private var player: CompositionPlayer? = null
  private val probes = HashMap<String, ProbeResult>()
  private var build: Job? = null
  private var changed = false
  private var durationMs = 0.0
  /** Each clip's place on the timeline and its frame grid, for frame-exact seeks. */
  private class Slot(val startMs: Double, val durationMs: Double, val trimStartMs: Double, val speed: Double, val fps: Double)
  private var slots: List<Slot> = emptyList()
  private var ready = false

  override var clips: Array<MergeClip> = emptyArray()
    set(value) { field = value; changed = true }
  override var options: MergeOptions = MergeOptions(1080.0, 1920.0, 30.0, 5_000_000.0, MergeAudio(48_000.0, 2.0))
    set(value) { field = value; changed = true }
  override var onTime: (timeMs: Double, playing: Boolean) -> Unit = { _, _ -> }
  override var onStatus: (status: PreviewStatus) -> Unit = {}

  // Measurements (stats()).
  private var firstFrameMs = -1.0
  private var lastSeekMs = -1.0
  private var lastSwapMs = -1.0
  private var framesShown = 0.0
  private var framesDropped = 0.0
  private var compositionSetNs = 0L
  private var awaitingFirstFrame = false
  private var awaitingSwap = false
  private var warnedUnmatched = false

  /** Frames on their way to the view (timeline time and release time), oldest first. */
  private val inFlight = ArrayDeque<Pair<Long, Long>>()

  // Seeks: the latest target, its start, and every caller still waiting for a frame.
  private var seekTargetUs = C.TIME_UNSET
  private var lastFrameUs = C.TIME_UNSET
  private var seekStartNs = 0L
  private val pendingSeeks = mutableListOf<Promise<Double>>()
  private val seekTimeout = Runnable { finishSeeks(player?.currentPosition?.toDouble() ?: 0.0) }

  private val ticker = object : Runnable {
    override fun run() {
      val p = player ?: return
      if (p.isPlaying) {
        onTime(p.currentPosition.toDouble(), true)
        main.postDelayed(this, TICK_MS)
      }
    }
  }

  private val listener = object : Player.Listener {
    override fun onIsPlayingChanged(isPlaying: Boolean) {
      val p = player ?: return
      onTime(p.currentPosition.toDouble(), isPlaying)
      main.removeCallbacks(ticker)
      if (isPlaying) main.postDelayed(ticker, TICK_MS)
    }

    override fun onPlayerError(error: PlaybackException) {
      Log.w("PulseEditor", "Preview failed: ${error.errorCodeName}", error)
      onStatus(PreviewStatus(durationMs, ready, error.message ?: error.errorCodeName))
    }
  }

  // Called on the playback thread as each frame is released into the effects pipeline, on its
  // way to the view.
  private val frames = VideoFrameMetadataListener { presentationTimeUs, releaseTimeNs, _, _ ->
    if (presentationTimeUs == C.TIME_UNSET) return@VideoFrameMetadataListener
    main.post {
      inFlight.addLast(presentationTimeUs to releaseTimeNs)
      while (inFlight.size > 16) inFlight.removeFirst()
    }
  }

  init {
    texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
      override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        videoSurface = Surface(st)
        attachSurface()
      }

      override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
        attachSurface()
      }

      override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        attachedSurface?.let { player?.clearVideoSurface(it) }
        attachedSurface = null
        videoSurface?.release()
        videoSurface = null
        return true
      }

      // A new frame reached the view: it's drawn in this frame.
      override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
        onShown(st.timestamp)
      }
    }
  }

  private fun attachSurface() {
    val s = videoSurface ?: return
    val p = player ?: return
    if (texture.width <= 0 || texture.height <= 0) return
    p.setVideoSurface(s, Size(texture.width, texture.height))
    attachedSurface = s
  }

  override fun afterUpdate() {
    if (!changed) return
    changed = false
    rebuild()
  }

  /** Probe new clips (once each), build the composition and swap it in at the current position. */
  private fun rebuild() {
    val list = clips.toList()
    val size = renderSize()
    build?.cancel()
    build = scope.launch {
      try {
        val media = withContext(Dispatchers.IO) {
          list.map { clip -> probes.getOrPut(clip.uri) { Probe.read(context, clip.uri) } }
        }
        val composition = PreviewComposition.build(list, media, size.first, size.second, options.fps)
        var at = 0.0
        slots = list.zip(media).map { (c, m) ->
          val length = com.margelo.nitro.pulseeditor.merge.MergePlan.timelineMs(c, m)
          val trimmed = c.endMs > c.startMs
          Slot(at, length, if (trimmed) c.startMs else 0.0, c.speed, m.video?.fps?.takeIf { it > 0 } ?: options.fps).also { at += length }
        }
        durationMs = at
        val p = ensurePlayer()
        val first = p.playbackState == Player.STATE_IDLE
        compositionSetNs = System.nanoTime()
        if (first) {
          awaitingFirstFrame = true
          p.setComposition(composition)
          p.prepare()
        } else {
          awaitingSwap = true
          p.setComposition(composition, min(p.currentPosition, durationMs.toLong()))
          if (p.playbackState == Player.STATE_IDLE) p.prepare()
        }
        framesShown = 0.0
        framesDropped = 0.0
        onStatus(PreviewStatus(durationMs, ready, null))
      } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        Log.w("PulseEditor", "Preview couldn't load the clips", e)
        onStatus(PreviewStatus(0.0, false, e.message ?: e.javaClass.simpleName))
      }
    }
  }

  /** The preview draws at the view's size (the canvas's shape, fitted inside it), never larger
   * than the canvas; half the canvas before the view is laid out. */
  private fun renderSize(): Pair<Int, Int> {
    val w = options.width
    val h = options.height
    val vw = texture.width.toDouble()
    val vh = texture.height.toDouble()
    val scale = if (vw > 0 && vh > 0) min(1.0, min(vw / w, vh / h)) else 0.5
    return ((w * scale / 2).roundToInt() * 2).coerceAtLeast(2) to ((h * scale / 2).roundToInt() * 2).coerceAtLeast(2)
  }

  private fun ensurePlayer(): CompositionPlayer = player ?: CompositionPlayer.Builder(context.applicationContext)
    .setLooper(Looper.getMainLooper())
    // Local files: start after 100 ms of media instead of the default second.
    .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(500, 3_000, 100, 200).build())
    .build()
    .also { p ->
      p.addListener(listener)
      p.setVideoFrameMetadataListener(frames)
      player = p
      attachSurface()
    }

  /**
   * A frame reached the view (its SurfaceTexture timestamp is the release time it was rendered
   * with). Which frame: the one released at that time, else the oldest still on its way. Whatever
   * waits for it (first frame, an edit, a seek) is told one screen refresh later, when it's up.
   */
  private fun onShown(timestampNs: Long) {
    // Every frame that reaches the view counts, known or not (scrubbing may skip the metadata).
    framesShown++
    val match = inFlight.indexOfFirst { it.second == timestampNs }
    val (presentationTimeUs, releaseTimeNs) = when {
      match >= 0 -> inFlight[match].also { repeat(match + 1) { inFlight.removeFirst() } }
      inFlight.isNotEmpty() -> inFlight.removeFirst()
      else -> return
    }
    if (match < 0 && !warnedUnmatched) {
      warnedUnmatched = true
      Log.i("PulseEditor", "Preview frames matched by order (texture timestamp $timestampNs, release $releaseTimeNs)")
    }
    lastFrameUs = presentationTimeUs
    val shownNs = System.nanoTime()
    val afterShown = REFRESH_MARGIN_MS
    if (awaitingFirstFrame) {
      awaitingFirstFrame = false
      firstFrameMs = (shownNs - compositionSetNs) / 1e6
      main.postDelayed({
        ready = true
        onStatus(PreviewStatus(durationMs, true, null))
      }, afterShown)
    }
    // A frame the old timeline released before the swap can still arrive: wait for a newer one,
    // and report it once it's on screen.
    if (awaitingSwap && releaseTimeNs > compositionSetNs) {
      awaitingSwap = false
      val swapMs = (shownNs - compositionSetNs) / 1e6
      main.postDelayed({ lastSwapMs = swapMs }, afterShown)
    }
    if (seekTargetUs != C.TIME_UNSET && presentationTimeUs >= seekTargetUs - 1_000 && presentationTimeUs < seekTargetUs + frameUs()) {
      lastSeekMs = (shownNs - seekStartNs) / 1e6
      val target = seekTargetUs
      seekTargetUs = C.TIME_UNSET
      main.postDelayed({ if (seekTargetUs == C.TIME_UNSET || seekTargetUs == target) finishSeeks(presentationTimeUs / 1000.0) }, afterShown)
    }
  }

  private fun frameUs(): Long = (1_000_000 / options.fps.coerceAtLeast(1.0)).toLong()

  /**
   * Where the frame showing at `timeMs` starts on the timeline. What plays (and what merge
   * exports) at a time is the frame that started at or before it; Media3's exact seek shows the
   * first frame starting at or after the seek position, so seeks go to that start. Source frames
   * are taken to be on the clip's average frame rate; a clip trimmed inside a frame starts on its
   * first whole frame, as Media3 clips it.
   */
  private fun frameStartMs(timeMs: Double): Double {
    val slot = slots.lastOrNull { it.startMs <= timeMs + 0.001 } ?: return timeMs
    // Sped up past the canvas rate, the clip plays on the canvas's frame grid (merge keeps a
    // frame per canvas frame), counted from the clip's start.
    val outputMs = 1000 / options.fps.coerceAtLeast(1.0)
    if (1000 / slot.fps / slot.speed < outputMs - 0.01) {
      return slot.startMs + kotlin.math.floor((timeMs - slot.startMs) / outputMs + 1e-6) * outputMs
    }
    val sourceMs = slot.trimStartMs + (timeMs - slot.startMs) * slot.speed
    val first = kotlin.math.ceil(slot.trimStartMs * slot.fps / 1000 - 1e-6)
    val frame = maxOf(first, kotlin.math.floor(sourceMs * slot.fps / 1000 + 1e-6))
    return slot.startMs + (frame * 1000 / slot.fps - slot.trimStartMs) / slot.speed
  }

  private fun finishSeeks(timeMs: Double) {
    main.removeCallbacks(seekTimeout)
    seekTargetUs = C.TIME_UNSET
    val waiting = pendingSeeks.toList()
    pendingSeeks.clear()
    waiting.forEach { it.resolve(timeMs) }
    onTime(timeMs, player?.isPlaying == true)
  }

  override fun play() {
    main.post { player?.play() }
  }

  override fun pause() {
    main.post { player?.pause() }
  }

  override fun seek(timeMs: Double): Promise<Double> {
    val promise = Promise<Double>()
    main.post {
      val p = player
      if (p == null) {
        promise.resolve(0.0)
        return@post
      }
      // Whole milliseconds, rounded down: the first frame at or after it is the one starting there.
      val target = kotlin.math.floor(frameStartMs(timeMs.coerceIn(0.0, maxOf(0.0, durationMs - 1)))).toLong()
      pendingSeeks += promise
      // Already showing that frame: nothing new will be drawn.
      val shown = lastFrameUs
      if (!p.isPlaying && seekTargetUs == C.TIME_UNSET && shown != C.TIME_UNSET &&
        target * 1000 >= shown && target * 1000 < shown + frameUs()
      ) {
        lastSeekMs = 0.0
        finishSeeks(shown / 1000.0)
        return@post
      }
      seekTargetUs = target * 1000
      seekStartNs = System.nanoTime()
      main.removeCallbacks(seekTimeout)
      main.postDelayed(seekTimeout, SEEK_TIMEOUT_MS)
      p.seekTo(target)
    }
    return promise
  }

  override fun setScrubbing(scrubbing: Boolean) {
    main.post { player?.setScrubbingModeEnabled(scrubbing) }
  }

  override fun snapshot(): Promise<String> {
    val promise = Promise<String>()
    main.post {
      // The frame the view shows now.
      val bitmap = texture.bitmap
      if (bitmap == null) {
        promise.reject(IllegalStateException("No frame on screen yet."))
        return@post
      }
      scope.launch(Dispatchers.IO) {
        val file = Merge.outputFile(context, "frame", "jpg")
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it) }
        promise.resolve(Uri.fromFile(file).toString())
      }
    }
    return promise
  }

  override fun stats(): PreviewStats = PreviewStats(firstFrameMs, lastSeekMs, lastSwapMs, framesShown, framesDropped)

  override fun onDropView() {
    main.post {
      main.removeCallbacks(ticker)
      main.removeCallbacks(seekTimeout)
      scope.cancel()
      player?.release()
      player = null
    }
  }

  private companion object {
    const val TICK_MS = 80L
    const val SEEK_TIMEOUT_MS = 3_000L
    /** A screen refresh and a bit: from a frame reaching the view to it being on screen. */
    const val REFRESH_MARGIN_MS = 20L
  }
}
