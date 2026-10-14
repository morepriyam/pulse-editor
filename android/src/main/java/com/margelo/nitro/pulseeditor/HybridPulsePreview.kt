package com.margelo.nitro.pulseeditor

import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.PixelCopy
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import androidx.media3.transformer.CompositionPlayer
import com.facebook.proguard.annotations.DoNotStrip
import com.facebook.react.uimanager.ThemedReactContext
import com.margelo.nitro.core.Promise
import com.margelo.nitro.pulseeditor.merge.Merge
import com.margelo.nitro.pulseeditor.preview.DragLayer
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
 *
 * Drags show through a plain player on top (DragLayer) until the composition shows the frame the
 * drag ended on.
 */
@OptIn(UnstableApi::class)
@DoNotStrip
class HybridPulsePreview(private val context: ThemedReactContext) : HybridPulsePreviewSpec() {
  private val texture = TextureView(context)
  /** Drags show the clip under the finger through a plain player on top (DragLayer). */
  private val drag = DragLayer(context, { clip, ptsUs, releaseNs -> onDragFrame(clip, ptsUs, releaseNs) }, { onLayerFailed() })
  private val root = Container(context).apply {
    val full = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    addView(texture, full)
    addView(drag.box, FrameLayout.LayoutParams(full))
  }
  override val view: View = root
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
  /** Each clip's place on the timeline and its frame grid, for frame-exact seeks; for the drag
   * layer, the clip itself, its upright size, and whether the layer can draw it as the composition does. */
  private class Slot(
    val startMs: Double, val durationMs: Double, val trimStartMs: Double, val speed: Double, val fps: Double,
    val clip: MergeClip, val size: VideoSize?, val layerOk: Boolean,
  )
  private var slots: List<Slot> = emptyList()
  private fun clipStartsMs() = DoubleArray(slots.size) { slots[it].startMs }
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
  /** The release time of the frame on screen, and when the composition was last sent elsewhere
   * (a seek, a new timeline): a frame released before that isn't where the composition is going. */
  private var lastFrameReleaseNs = C.TIME_UNSET
  private var compositionMovedNs = 0L
  private var seekStartNs = 0L
  private val pendingSeeks = mutableListOf<Promise<Double>>()
  /** The millisecond last passed to the player's seekTo (or setComposition), to keep seeks real. */
  private var lastSentMs = C.TIME_UNSET
  /** A drag is on (setScrubbing): seeks go to the drag layer. */
  private var scrubbing = false
  /** This drag runs on the composition in Media3's scrubbing mode: the layer failed, or can't draw a clip it reached. */
  private var compositionScrub = false
  /** The drag layer is up (from a drag's start until the composition shows where it ended). */
  private var dragging = false
  private var dragEndNs = 0L
  /** The drag layer's latest target: its clip, the source frame asked for, and that frame's timeline time. */
  private var dragClip = -1
  private var dragSourceUs = C.TIME_UNSET
  private var dragTimelineMs = 0.0
  /** The frame the drag layer last drew (its clip and source time). */
  private var layerClip = -1
  private var layerPtsUs = C.TIME_UNSET
  // A seek that never showed: resolved with where the picture is (the layer's target while it's up).
  private val seekTimeout = Runnable { finishSeeks(if (dragging) dragTimelineMs else player?.currentPosition?.toDouble() ?: 0.0) }

  private val ticker = object : Runnable {
    override fun run() {
      val p = player ?: return
      if (p.isPlaying) {
        onTime(shownTimeMs(p), true)
        main.postDelayed(this, TICK_MS)
      }
    }
  }

  private val listener = object : Player.Listener {
    override fun onIsPlayingChanged(isPlaying: Boolean) {
      val p = player ?: return
      onTime(shownTimeMs(p), isPlaying)
      main.removeCallbacks(ticker)
      if (isPlaying) main.postDelayed(ticker, TICK_MS)
    }

    override fun onPlayerError(error: PlaybackException) {
      Log.w("PulseEditor", "Preview failed: ${error.errorCodeName}", error)
      onStatus(PreviewStatus(durationMs, clipStartsMs(), ready, error.message ?: error.errorCodeName))
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

      // A new frame reached the view: it's drawn in this frame. The view takes it after this
      // callback (the texture's timestamp here is still the previous frame's on the S24), so the
      // frame is looked up once this draw is done.
      override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
        main.post { onShown(st.timestamp) }
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
          list.map { clip -> probes.getOrPut(clip.uri) { Probe.read(context, clip.uri) } }.also(::logDecoders)
        }
        val composition = PreviewComposition.build(list, media, size.first, size.second, options.fps)
        var at = 0.0
        slots = list.zip(media).map { (c, m) ->
          val length = com.margelo.nitro.pulseeditor.merge.MergePlan.timelineMs(c, m)
          val trimStartMs = com.margelo.nitro.pulseeditor.merge.MergePlan.windowUs(c, m)?.let { it.first / 1000.0 } ?: 0.0
          val v = m.video
          // The layer is a plain player on a TextureView: no tone mapping (HDR, 10-bit), quarter turns only.
          val layerOk = v != null && v.transfer == Transfer.SDR && v.bitDepth <= 8 && c.rotation.roundToInt() % 90 == 0
          val upright = v?.let {
            if (it.rotation.roundToInt() % 180 == 90) VideoSize(it.height.toInt(), it.width.toInt()) else VideoSize(it.width.toInt(), it.height.toInt())
          }
          Slot(at, length, trimStartMs, c.speed, v?.fps?.takeIf { it > 0 } ?: options.fps, c, upright, layerOk)
            .also { at += length }
        }
        durationMs = at
        drag.setCanvas(options.width, options.height)
        // The layer loads its clip again on its next seek. Over a hand-off it stays up until the
        // composition shows that frame (taking it down now would flash the frame before the drag).
        if (!scrubbing) {
          if (dragging) drag.forget() else drag.invalidate()
          layerClip = -1
        }
        val p = ensurePlayer()
        val first = p.playbackState == Player.STATE_IDLE
        inFlight.clear()
        compositionSetNs = System.nanoTime()
        compositionMovedNs = compositionSetNs
        if (first) {
          awaitingFirstFrame = true
          p.setComposition(composition)
          lastSentMs = 0
          p.prepare()
        } else {
          awaitingSwap = true
          val position = min(p.currentPosition, durationMs.toLong())
          p.setComposition(composition, position)
          lastSentMs = position
          if (p.playbackState == Player.STATE_IDLE) p.prepare()
        }
        framesShown = 0.0
        framesDropped = 0.0
        onStatus(PreviewStatus(durationMs, clipStartsMs(), ready, null))
      } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        Log.w("PulseEditor", "Preview couldn't load the clips", e)
        onStatus(PreviewStatus(0.0, DoubleArray(0), false, e.message ?: e.javaClass.simpleName))
      }
    }
  }

  /**
   * Bench: the decoder ExoPlayer's default selector puts first for each clip codec, once per
   * rebuild. CompositionPlayer exposes no AnalyticsListener, so this is the first candidate its
   * video renderer tries (MediaCodecSelector.DEFAULT), not a report of the one that initialised.
   */
  private fun logDecoders(media: List<ProbeResult>) {
    for (codec in media.mapNotNull { it.video?.codec }.distinct()) {
      val mime = when (codec) {
        "h264" -> MimeTypes.VIDEO_H264
        "hevc" -> MimeTypes.VIDEO_H265
        else -> "video/$codec"
      }
      val names = try {
        MediaCodecSelector.DEFAULT.getDecoderInfos(mime, false, false).map { it.name }
      } catch (e: Exception) {
        emptyList()
      }
      Log.i("PulseEditor", "preview decoder $mime: ${names.firstOrNull() ?: "none"} (ExoPlayer default selector, ${names.size} candidates)")
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
    if (match < 0) {
      // Matched by order (no texture timestamp): frames a scrub released but never drew would be
      // taken for this one. A frame reaches the view within a refresh or two of its release time,
      // and one released before the last seek (or timeline) that hasn't by now was flushed by it
      // (frames released just before a pause, then a seek: else the seek's frame is taken for one
      // of those and its seek waits). Not the latest one: frames are released into the effects
      // pipeline, so later ones can be on their way behind the one drawn.
      val now = System.nanoTime()
      val stale = now - STALE_FRAME_MS * 1_000_000
      val flushed = minOf(compositionMovedNs, now - FLUSHED_FRAME_MS * 1_000_000)
      while (inFlight.size > 1 && (inFlight.first().second < stale || inFlight.first().second < flushed)) inFlight.removeFirst()
    }
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
    lastFrameReleaseNs = releaseTimeNs
    val shownNs = System.nanoTime()
    val afterShown = REFRESH_MARGIN_MS
    if (awaitingFirstFrame) {
      awaitingFirstFrame = false
      firstFrameMs = (shownNs - compositionSetNs) / 1e6
      main.postDelayed({
        ready = true
        onStatus(PreviewStatus(durationMs, clipStartsMs(), true, null))
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
      // Bench: the split of this seek (released = Media3's release time of the matched frame, in
      // the System.nanoTime domain; shown = when it reached the view).
      val releasedMs = if (releaseTimeNs == C.TIME_UNSET) "?" else "${((releaseTimeNs - seekStartNs) / 1e6).roundToInt()}"
      Log.i("PulseEditor", "preview seek target=${target / 1000} released=+$releasedMs shown=+${lastSeekMs.roundToInt()}")
      // Told once it's up, unless a newer seek replaced the target meanwhile.
      main.postDelayed({ if (seekTargetUs == target) finishSeeks(presentationTimeUs / 1000.0) }, afterShown)
    }
    // The composition is back on the drag's end frame (or playing on from it): take the layer down
    // once that frame is up.
    if (dragging && !scrubbing && releaseTimeNs != C.TIME_UNSET && releaseTimeNs > dragEndNs &&
      (seekTargetUs == C.TIME_UNSET || (presentationTimeUs >= seekTargetUs - 1_000 && presentationTimeUs < seekTargetUs + frameUs()))) {
      main.postDelayed({
        if (dragging && !scrubbing) {
          dragging = false
          drag.hide()
        }
      }, afterShown)
    }
    // A frame that reaches the view while paused with no seek waiting (released before a pause and
    // drawn after it, or an edit swapped in): the reported time follows the picture, so the playhead
    // doesn't sit a frame or two behind it until the next seek or play. Not under the drag layer:
    // the time is the layer's then.
    if (seekTargetUs == C.TIME_UNSET && player?.isPlaying != true && !dragging) {
      main.postDelayed({
        if (seekTargetUs == C.TIME_UNSET && player?.isPlaying != true && !dragging) onTime(presentationTimeUs / 1000.0, false)
      }, afterShown)
    }
  }

  private fun frameUs(): Long = (1_000_000 / options.fps.coerceAtLeast(1.0)).toLong()

  /**
   * Where the frame showing at `timeMs` starts on the timeline. What plays (and what merge
   * exports) at a time is the frame that started at or before it; Media3's exact seek shows the
   * first frame starting at or after the seek position, so seeks go to that start. Source frames
   * are taken to be on the clip's average frame rate. A clip's window starts on a frame and its
   * timeline length is whole ms (MergePlan.windowUs), so every clip starts on a whole ms with its
   * first frame there: the seek to that start resolves to the clip itself, at its first sample,
   * and shows it. (A start inside a ms can't be hit: a seek below it shows the previous clip's
   * last frame, one above it skips the first frame as decode-only.)
   */
  private fun frameStartMs(timeMs: Double): Double {
    val slot = slots.lastOrNull { it.startMs <= timeMs + 0.001 } ?: return timeMs
    // Sped up past the canvas rate, the clip plays on the canvas's frame grid (merge keeps a
    // frame per canvas frame), counted from the clip's start.
    if (spedPastCanvas(slot)) {
      val outputMs = 1000 / options.fps.coerceAtLeast(1.0)
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

  /** The time of the frame on screen, else the player's position (before the first frame). */
  private fun shownTimeMs(p: CompositionPlayer): Double =
    if (lastFrameUs != C.TIME_UNSET) lastFrameUs / 1000.0 else p.currentPosition.toDouble()

  /**
   * The composition shows the frame a seek to `target` ms lands on (the first frame starting at or
   * after it, matched as onShown matches a seek's frame), released after it was last sent anywhere,
   * and no seek is on its way: a seek there would draw nothing new.
   */
  private fun compositionShows(target: Long): Boolean {
    val shown = lastFrameUs
    return seekTargetUs == C.TIME_UNSET && shown != C.TIME_UNSET && lastFrameReleaseNs != C.TIME_UNSET &&
      lastFrameReleaseNs > compositionMovedNs && shown >= target * 1000 - 1_000 && shown < target * 1000 + frameUs()
  }

  private fun startSeek(p: CompositionPlayer, target: Long) {
    // Already showing that frame, with no seek on its way: nothing new will be drawn (and the drag
    // layer over it, showing the same frame, can come down).
    if (!p.isPlaying && compositionShows(target)) {
      lastSeekMs = 0.0
      if (dragging && !scrubbing) {
        dragging = false
        drag.hide()
      }
      finishSeeks(lastFrameUs / 1000.0)
      return
    }
    seekTargetUs = target * 1000
    seekStartNs = System.nanoTime()
    compositionMovedNs = seekStartNs
    main.removeCallbacks(seekTimeout)
    main.postDelayed(seekTimeout, SEEK_TIMEOUT_MS)
    // The millisecond last sent again: ExoPlayer skips a seek to the millisecond it's at, so the
    // earlier seek's frame would be the only one coming, and after a drag it can be lost (the settle
    // seek after a drag timed out on the trims draft). A throwaway seek to another millisecond right
    // before makes the one to the target real. The throwaway stays inside the target's clip: 1 ms
    // earlier at a clip's start is in the previous clip (there it goes a canvas frame later, past the
    // resolve window), where a paused player doesn't draw the
    // next clip's first frame, and in scrubbing mode the target's seek would queue behind it.
    // While dragging too, so letting go works whichever of setScrubbing(false) and the settle seek
    // comes first.
    if (lastSentMs == target) p.seekTo(if (target > 0 && slotAt(target - 1.0) === slotAt(target.toDouble())) target - 1 else target + kotlin.math.ceil(1000 / options.fps.coerceAtLeast(1.0)).toLong())
    lastSentMs = target
    p.seekTo(target)
  }

  private fun slotAt(timeMs: Double): Slot? = slots.lastOrNull { it.startMs <= timeMs + 0.001 }

  override fun play() {
    // A drag holds the picture.
    main.post { if (!scrubbing) player?.play() }
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
      val t = timeMs.coerceIn(0.0, maxOf(0.0, durationMs - 1))
      if (scrubbing && slots.isNotEmpty()) {
        pendingSeeks += promise
        if (compositionScrub || !layerCan(t)) {
          scrubOnComposition(p)
          startSeek(p, kotlin.math.floor(frameStartMs(t)).toLong())
        } else dragSeek(t)
        return@post
      }
      // Whole milliseconds, rounded down: the first frame at or after it is the one starting there.
      val target = kotlin.math.floor(frameStartMs(t)).toLong()
      // Every seek goes to the player at once (in scrubbing mode it keeps only the latest);
      // callers all resolve when the latest target is on screen.
      pendingSeeks += promise
      startSeek(p, target)
    }
    return promise
  }

  override fun setScrubbing(scrubbing: Boolean) {
    main.post {
      val p = player
      if (scrubbing == this.scrubbing || p == null || slots.isEmpty()) {
        this.scrubbing = scrubbing && p != null && slots.isNotEmpty()
        return@post
      }
      this.scrubbing = scrubbing
      if (scrubbing) {
        // The drag starts on the frame on screen (the layer's, if it's still up after the last
        // drag), so the layer comes up without a jump.
        if (p.isPlaying) p.pause()
        val from = if (dragging) dragTimelineMs else shownTimeMs(p)
        if (layerCan(from)) {
          // A composition seek on its way (the last drag's hand-off) would land mid-drag: the drag replaces it.
          seekTargetUs = C.TIME_UNSET
          dragging = true
          drag.arm()
          dragSeek(from)
        } else {
          val layerUp = dragging
          scrubOnComposition(p)
          if (layerUp) startSeek(p, kotlin.math.floor(frameStartMs(from)).toLong())
        }
      } else {
        // Let go: the composition goes to where the drag ended (a seek() that follows replaces
        // this target), also when the layer's first frame never came; the layer stays up until
        // that frame is on screen.
        dragEndNs = System.nanoTime()
        if (compositionScrub) {
          compositionScrub = false
          compositionMovedNs = dragEndNs
          p.setScrubbingModeEnabled(false)
        } else startSeek(p, kotlin.math.floor(frameStartMs(dragTimelineMs)).toLong())
      }
    }
  }

  /** The rest of this drag runs on the composition, in Media3's scrubbing mode (as before the drag layer). */
  private fun scrubOnComposition(p: CompositionPlayer) {
    if (compositionScrub) return
    compositionScrub = true
    dragging = false
    drag.hide()
    p.setScrubbingModeEnabled(true)
  }

  /** The drag layer's player failed: the rest of the drag goes to the composition. */
  private fun onLayerFailed() {
    val p = player ?: return
    if (!scrubbing) return
    scrubOnComposition(p)
    startSeek(p, kotlin.math.floor(frameStartMs(dragTimelineMs)).toLong())
  }

  private fun slotIndexAt(timeMs: Double): Int = slots.indexOfLast { it.startMs <= timeMs + 0.001 }.coerceAtLeast(0)

  /** The clip's frames come faster than the canvas's (sped up, or a higher frame rate): it plays on the canvas's grid. */
  private fun spedPastCanvas(s: Slot) = 1000 / s.fps / s.speed < 1000 / options.fps.coerceAtLeast(1.0) - 0.01

  /** The drag layer can draw the clip under `timeMs` as the composition does. */
  private fun layerCan(timeMs: Double): Boolean = drag.usable && slots.getOrNull(slotIndexAt(timeMs))?.layerOk == true

  /** Where the source frame the timeline shows at `timeMs` starts in slot `s`'s file, in ms. */
  private fun sourceFrameMs(s: Slot, timeMs: Double): Double {
    val frameMs = 1000 / s.fps
    val first = kotlin.math.ceil(s.trimStartMs / frameMs - 1e-6)
    val frame = if (spedPastCanvas(s)) {
      // On the canvas's grid: after a seek to a canvas frame the composition shows the first
      // source frame at or after it (its frame dropper starts over there), not the one before.
      val sourceMs = s.trimStartMs + (frameStartMs(timeMs) - s.startMs) * s.speed
      maxOf(first, kotlin.math.ceil(sourceMs / frameMs - 1e-6))
    } else {
      val sourceMs = s.trimStartMs + (timeMs - s.startMs) * s.speed
      maxOf(first, kotlin.math.floor(sourceMs / frameMs + 1e-6))
    }
    return frame * frameMs
  }

  /**
   * A drag seek: the source frame of the clip under `timeMs` (the frame the timeline shows
   * there), through the drag layer's player. Callers resolve when it's on the layer.
   */
  private fun dragSeek(timeMs: Double) {
    val index = slotIndexAt(timeMs)
    val slot = slots[index]
    val frameStart = sourceFrameMs(slot, timeMs)
    val sourceUs = (frameStart * 1000).toLong()
    val frameUs = (1_000_000 / slot.fps).toLong()
    // Already the layer's target and on it: ExoPlayer would drop the seek (it's at that position),
    // so no frame would come; the callers are told now.
    val onLayer = drag.shown && index == dragClip && sourceUs == dragSourceUs && layerClip == index &&
      layerPtsUs >= sourceUs - 1_000 && layerPtsUs < sourceUs + frameUs
    dragClip = index
    dragSourceUs = sourceUs
    dragTimelineMs = slot.startMs + (frameStart - slot.trimStartMs) / slot.speed
    if (onLayer) {
      lastSeekMs = 0.0
      finishSeeks(dragTimelineMs)
      return
    }
    seekStartNs = System.nanoTime()
    main.removeCallbacks(seekTimeout)
    main.postDelayed(seekTimeout, SEEK_TIMEOUT_MS)
    drag.seek(index, slot.clip, slot.size, kotlin.math.floor(frameStart).toLong(), frameUs)
  }

  /** A frame reached the drag layer: show the layer, report its time, resolve the drag's seeks. */
  private fun onDragFrame(clip: Int, ptsUs: Long, releaseNs: Long) {
    layerClip = clip
    layerPtsUs = ptsUs
    // Only while a drag is on the layer: a late frame after letting go isn't the picture (and must
    // not resolve a seek that came after).
    if (!dragging || clip != dragClip || !scrubbing) return
    framesShown++
    val slot = slots.getOrNull(clip) ?: return
    val timeMs = slot.startMs + (ptsUs / 1000.0 - slot.trimStartMs) / slot.speed
    if (!drag.shown) drag.show()
    onTime(timeMs, false)
    val frameUs = (1_000_000 / slot.fps).toLong()
    if (ptsUs >= dragSourceUs - 1_000 && ptsUs < dragSourceUs + frameUs) {
      lastSeekMs = (System.nanoTime() - seekStartNs) / 1e6
      val releasedMs = if (releaseNs == C.TIME_UNSET) "?" else "${((releaseNs - seekStartNs) / 1e6).roundToInt()}"
      Log.i("PulseEditor", "preview drag seek target=${dragTimelineMs.roundToInt()} released=+$releasedMs shown=+${lastSeekMs.roundToInt()}")
      val target = dragSourceUs
      main.postDelayed({
        if (dragSourceUs == target && scrubbing && pendingSeeks.isNotEmpty()) finishSeeks(timeMs)
      }, REFRESH_MARGIN_MS)
    }
  }

  override fun snapshot(): Promise<String> {
    val promise = Promise<String>()
    main.post {
      if (drag.shown) {
        snapshotScreen(promise)
        return@post
      }
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

  /** What the preview shows on screen (the drag layer is a view on top: TextureView.getBitmap
   * of the composition wouldn't have it). */
  private fun snapshotScreen(promise: Promise<String>) {
    val window = context.currentActivity?.window
    if (window == null || root.width <= 0 || root.height <= 0) {
      promise.reject(IllegalStateException("No window to copy the frame from."))
      return
    }
    val at = IntArray(2).also { root.getLocationInWindow(it) }
    val bitmap = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
    PixelCopy.request(window, android.graphics.Rect(at[0], at[1], at[0] + root.width, at[1] + root.height), bitmap, { result ->
      if (result != PixelCopy.SUCCESS) {
        promise.reject(IllegalStateException("Couldn't copy the frame ($result)."))
        return@request
      }
      scope.launch(Dispatchers.IO) {
        val file = Merge.outputFile(context, "frame", "jpg")
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it) }
        promise.resolve(Uri.fromFile(file).toString())
      }
    }, main)
  }

  override fun stats(): PreviewStats = PreviewStats(firstFrameMs, lastSeekMs, lastSwapMs, framesShown, framesDropped)

  override fun onDropView() {
    main.post {
      main.removeCallbacks(ticker)
      main.removeCallbacks(seekTimeout)
      scope.cancel()
      // Nothing more will be drawn: whoever still waits gets the time on screen.
      val waiting = pendingSeeks.toList()
      pendingSeeks.clear()
      val at = if (dragging) dragTimelineMs else if (lastFrameUs != C.TIME_UNSET) lastFrameUs / 1000.0 else 0.0
      waiting.forEach { it.resolve(at) }
      drag.release()
      player?.release()
      player = null
    }
  }

  private companion object {
    const val TICK_MS = 80L
    const val SEEK_TIMEOUT_MS = 3_000L
    /** A screen refresh and a bit: from a frame reaching the view to it being on screen. */
    const val REFRESH_MARGIN_MS = 20L
    /** Older than this since its release time, a frame matched by order is one that never drew. */
    const val STALE_FRAME_MS = 300L
    /** Released before the last seek and this long ago without being drawn: that seek flushed it. */
    const val FLUSHED_FRAME_MS = 50L
  }
}

/** React Native lays out only the views it manages: a child shown later (the drag layer) would
 * stay unmeasured, so the container measures and lays itself out again when a child asks. */
private class Container(context: android.content.Context) : FrameLayout(context) {
  // Nullable: FrameLayout's constructor can ask for a layout before this is set.
  private val relayout: Runnable? = Runnable {
    measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
    layout(left, top, right, bottom)
  }

  override fun requestLayout() {
    super.requestLayout()
    relayout?.let {
      removeCallbacks(it)
      post(it)
    }
  }
}
