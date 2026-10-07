package com.margelo.nitro.pulseeditor

import android.graphics.SurfaceTexture
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.CodecParameters
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ScrubbingModeParameters
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import com.facebook.proguard.annotations.DoNotStrip
import com.facebook.react.bridge.ReactApplicationContext
import com.margelo.nitro.NitroModules
import com.margelo.nitro.core.Promise
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Bench only: the gate measurement for a plain-ExoPlayer drag layer in `<PulsePreview>`
 * (`.devrun/plans/android-preview-speed.md` §6 step 3a). One pass ([run]) builds a plain Media3
 * [ExoPlayer] on ONE source file, with [DefaultRenderersFactory] and no video effects, so the
 * decoder renders straight into a [TextureView]'s SurfaceTexture (no effects pipeline, no GL hop),
 * then measures:
 * - the prewarm cost: build → STATE_READY → first frame on the view;
 * - paused exact seeks (scrubbing mode off, or on with [SeekBenchOptions.pausedScrubbing]);
 * - drags at a fixed rate in scrubbing mode, each with its own tolerance knob, then an exact
 *   settle seek with scrubbing off ("exact on release");
 * and releases the player.
 *
 * **Where the Surface comes from:** a plain [TextureView] added on top of the current activity's
 * decor view (a FrameLayout) for the duration of the pass, sized [SeekBenchOptions.viewWidth] ×
 * [SeekBenchOptions.viewHeight] dp at the bottom centre, and removed at the end. A TextureView only
 * gets its SurfaceTexture, and only reports frames, while it's attached, visible and drawn, so it
 * has to be on screen. The player gets `Surface(surfaceTexture)` through `setVideoSurface`, not
 * `setVideoTextureView`, which would replace this bench's SurfaceTextureListener with ExoPlayer's.
 *
 * **"Shown" is measured as in HybridPulsePreview:** every frame's [VideoFrameMetadataListener]
 * release (presentation time, release time) is queued; each `onSurfaceTextureUpdated` takes the
 * release whose time equals the SurfaceTexture's timestamp (MediaCodec stamps a buffer rendered
 * with a release time with that time), else the oldest one still queued (counted in
 * [SeekBenchResult.matchedByOrder]). Without effects that release is the decoder output's own
 * release (`MediaCodecVideoRenderer`), not the effects pipeline's input stage the composition
 * player reports.
 *
 * Every landed seek is logged as
 * `seekbench seek target=<ms> released=+<ms> shown=+<ms> cb=+<ms> pts=<ms> pass=<label> kind=<kind>`
 * under the `PulseEditor` tag (`.devrun/preview/seeklog.py` reads them).
 */
@DoNotStrip
class SeekBench : HybridSeekBenchSpec() {
  /** A pass already running: passes share the screen and the decoder, so they never overlap. */
  private val running = AtomicBoolean(false)

  override fun run(uri: String, options: SeekBenchOptions): Promise<SeekBenchResult> {
    val context = NitroModules.applicationContext
      ?: return Promise.rejected(IllegalStateException("React context not ready"))
    if (!running.compareAndSet(false, true)) {
      return Promise.rejected(IllegalStateException("A seek bench pass is already running."))
    }
    return Promise.async {
      try {
        // The player, its listeners and the view all live on the main looper.
        withContext(Dispatchers.Main) { SeekBenchPass(context, uri, options).run() }
      } finally {
        running.set(false)
      }
    }
  }
}

/** One pass of [SeekBench]: everything in it runs on the main thread, except where noted. */
@OptIn(UnstableApi::class)
private class SeekBenchPass(
  private val context: ReactApplicationContext,
  private val uri: String,
  private val options: SeekBenchOptions,
) {
  /** A frame Media3 released towards the view: its presentation time, the release time it was
   * rendered with (System.nanoTime domain) and when the release callback ran. */
  private class Released(val ptsUs: Long, val releaseNs: Long, val callbackNs: Long)

  /** A seek waiting for its frame on the view: frames from `targetUs` to one frame after it,
   * released after `startNs`, answer it. */
  private class Waiter(val targetUs: Long, val startNs: Long) {
    val done = CompletableDeferred<Pair<Released, Long>>()
  }

  /** The pictures one drag puts on the view, and the seeks it gave the player. */
  private inner class DragCollector(val startNs: Long) {
    var endNs = Long.MAX_VALUE
    /** Each target given to the player and when it was first asked for (drag targets only move
     * one way, so a target repeats only in a row). */
    val firstIssueNs = LinkedHashMap<Long, Long>()
    var seeksIssued = 0
    var pictures = 0
    val distinct = HashSet<Long>()
    val frames = ArrayList<SeekBenchPicture>()

    fun issue(targetUs: Long, nowNs: Long) {
      seeksIssued++
      firstIssueNs.putIfAbsent(targetUs, nowNs)
    }

    fun onPicture(frame: Released?, shownNs: Long) {
      if (shownNs < startNs || shownNs > endNs) return
      pictures++
      if (frame == null) {
        frames += SeekBenchPicture(-1.0, ms(shownNs - startNs), -1.0)
        return
      }
      distinct += frame.ptsUs
      // The seek that asked for exactly this frame (a tolerant seek can land on another one).
      val asked = firstIssueNs.entries.firstOrNull { (t, issued) -> inFrame(frame.ptsUs, t) && issued <= frame.callbackNs }
      frames += SeekBenchPicture(frame.ptsUs / 1000.0, ms(shownNs - startNs), asked?.let { ms(shownNs - it.value) } ?: -1.0)
    }
  }

  /** Releases on their way to the view, oldest first. Written on the playback thread. */
  private val inFlight = ArrayDeque<Released>()
  private var waiter: Waiter? = null
  private var drag: DragCollector? = null
  private var matchedByOrder = 0
  private var fps = 30.0
  /** The last position given to `seekTo`, ms. */
  private var lastSeekPosMs = 0L
  private var error: PlaybackException? = null

  private val firstFrame = CompletableDeferred<Long>()
  private val ready = CompletableDeferred<Long>()
  private var decoder = ""
  private var decoderInitMs = -1.0

  private lateinit var player: ExoPlayer

  /** Called on the playback thread just before each frame is rendered to the surface. */
  private val frameListener = VideoFrameMetadataListener { ptsUs, releaseNs, _, _ ->
    val now = System.nanoTime()
    if (ptsUs == C.TIME_UNSET) return@VideoFrameMetadataListener
    synchronized(inFlight) {
      inFlight.addLast(Released(ptsUs, releaseNs, now))
      while (inFlight.size > MAX_IN_FLIGHT) inFlight.removeFirst()
    }
  }

  private val playerListener = object : Player.Listener {
    override fun onPlaybackStateChanged(playbackState: Int) {
      if (playbackState == Player.STATE_READY) ready.complete(System.nanoTime())
    }

    override fun onPlayerError(error: PlaybackException) {
      Log.w(TAG, "seekbench player failed: ${error.errorCodeName}", error)
      this@SeekBenchPass.error = error
    }
  }

  private val analytics = object : AnalyticsListener {
    override fun onVideoDecoderInitialized(
      eventTime: AnalyticsListener.EventTime,
      decoderName: String,
      initializedTimestampMs: Long,
      initializationDurationMs: Long,
    ) {
      decoder = decoderName
      decoderInitMs = initializationDurationMs.toDouble()
    }
  }

  suspend fun run(): SeekBenchResult {
    check(Looper.myLooper() == Looper.getMainLooper())
    val probe = withContext(Dispatchers.IO) { Probe.read(context, uri) }
    fps = probe.video?.fps?.takeIf { it > 0 } ?: 30.0
    val activity = context.currentActivity ?: throw IllegalStateException("No activity to show the seek bench's view in.")
    val root = activity.window.decorView as ViewGroup
    val density = activity.resources.displayMetrics.density
    val texture = TextureView(activity)
    var surface: Surface? = null
    val available = CompletableDeferred<SurfaceTexture>()
    texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
      override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        available.complete(st)
      }

      override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = Unit

      override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true

      override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
        onShown(st.timestamp)
      }
    }
    val params = FrameLayout.LayoutParams(
      (options.viewWidth * density).roundToInt(),
      (options.viewHeight * density).roundToInt(),
      Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
    ).apply { bottomMargin = (VIEW_BOTTOM_MARGIN_DP * density).roundToInt() }

    val viewStart = System.nanoTime()
    root.addView(texture, params)
    var releaseMs = -1.0
    try {
      val st = withTimeoutOrNull(VIEW_TIMEOUT_MS) { available.await() }
        ?: throw IllegalStateException("The seek bench's TextureView got no surface (is the app in the foreground?).")
      val viewMs = ms(System.nanoTime() - viewStart)
      surface = Surface(st)

      // Prewarm: create + prepare + first frame on the view.
      val t0 = System.nanoTime()
      player = buildPlayer()
      val builtNs = System.nanoTime()
      player.setVideoSurface(surface)
      player.setMediaItem(MediaItem.fromUri(mediaUri(uri)))
      player.playWhenReady = false
      player.prepare()
      val readyNs = withTimeoutOrNull(PREPARE_TIMEOUT_MS) { ready.await() }
      val firstNs = withTimeoutOrNull(PREPARE_TIMEOUT_MS) { firstFrame.await() }
      failIfError()
      if (readyNs == null || firstNs == null) throw IllegalStateException("The player showed no first frame within $PREPARE_TIMEOUT_MS ms.")
      val durationMs = player.duration.toDouble()
      val (lowLatencySupported, instances) = decoderCapabilities(decoder)
      Log.i(TAG, "seekbench decoder ${decoder.ifEmpty { "?" }} init=${decoderInitMs.roundToInt()} lowLatency=${options.lowLatency} supported=$lowLatencySupported instances=$instances pass=${options.label}")
      Log.i(TAG, "seekbench prewarm pass=${options.label} build=+${ms(builtNs - t0).roundToInt()} ready=+${ms(readyNs - t0).roundToInt()} firstFrame=+${ms(firstNs - t0).roundToInt()}")
      delay(options.restMs.toLong())

      // Paused exact seeks.
      val seeks = ArrayList<SeekBenchSeek>()
      if (options.pausedScrubbing) player.isScrubbingModeEnabled = true
      for (t in options.seekTargetsMs) {
        seeks += seekAndWait("paused", frameStartMs(t), log = true)
        delay(options.restMs.toLong())
      }
      if (options.pausedScrubbing) player.isScrubbingModeEnabled = false
      failIfError()

      // Drags.
      val drags = ArrayList<SeekBenchDragResult>()
      for (d in options.drags) {
        drags += runDrag(d, durationMs, seeks)
        failIfError()
      }

      val r0 = System.nanoTime()
      player.release()
      releaseMs = ms(System.nanoTime() - r0)
      Log.i(TAG, "seekbench release pass=${options.label} release=+${releaseMs.roundToInt()}")
      return SeekBenchResult(
        label = options.label,
        decoder = decoder,
        decoderInitMs = decoderInitMs,
        lowLatency = options.lowLatency,
        lowLatencySupported = lowLatencySupported,
        maxDecoderInstances = instances.toDouble(),
        viewMs = viewMs,
        buildMs = ms(builtNs - t0),
        readyMs = ms(readyNs - t0),
        firstFrameMs = ms(firstNs - t0),
        releaseMs = releaseMs,
        durationMs = durationMs,
        fps = fps,
        seeks = seeks.toTypedArray(),
        drags = drags.toTypedArray(),
        matchedByOrder = matchedByOrder.toDouble(),
      )
    } finally {
      waiter = null
      drag = null
      if (releaseMs < 0 && this::player.isInitialized) player.release()
      root.removeView(texture)
      surface?.release()
    }
  }

  /** The plain player: default renderers (hardware decoder, no effects), audio off, exact seeks,
   * the whole clip kept in memory both ways so seeks inside it never reload the file. */
  private fun buildPlayer(): ExoPlayer {
    val p = ExoPlayer.Builder(context, DefaultRenderersFactory(context))
      .setLooper(Looper.getMainLooper())
      .setLoadControl(
        DefaultLoadControl.Builder()
          .setBufferDurationsMs(BUFFER_MIN_MS, BUFFER_MAX_MS, BUFFER_FOR_PLAYBACK_MS, BUFFER_AFTER_REBUFFER_MS)
          .setBackBuffer(BUFFER_MAX_MS, true)
          .build(),
      )
      .setSeekParameters(SeekParameters.EXACT)
      // As CompositionPlayer's inner players: renderers wake the player when they have work instead
      // of the 10 ms poll while buffering.
      .experimentalSetDynamicSchedulingEnabled(true)
      .build()
    p.addListener(playerListener)
    p.addAnalyticsListener(analytics)
    p.setVideoFrameMetadataListener(frameListener)
    // A drag layer never plays sound: no audio renderer at all, not just while scrubbing.
    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
    if (options.lowLatency && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      // Applied to the decoder's MediaFormat when it's configured (MediaCodecRenderer
      // .applyCodecParametersToMediaFormat, API 29+); a decoder without FEATURE_LowLatency ignores it.
      p.setVideoCodecParameters(CodecParameters.Builder().setInteger(MediaFormat.KEY_LOW_LATENCY, 1).build())
    }
    return p
  }

  /**
   * One drag: start settled on its first frame, turn scrubbing on with the drag's knob, seek at
   * [SeekBenchDrag.hz] for [SeekBenchDrag.durationMs], counting the pictures that reach the view,
   * then let go: scrubbing off, knobs back to exact, and an exact seek to the end target (its time
   * is the drag's settle). The settle seek is added to [seeks].
   */
  private suspend fun runDrag(d: SeekBenchDrag, durationMs: Double, seeks: MutableList<SeekBenchSeek>): SeekBenchDragResult {
    seekAndWait("pre-${d.name}", frameStartMs(d.fromMs), log = false)
    delay(options.restMs.toLong())

    when (d.mode) {
      SeekBenchDragMode.EXACT -> player.scrubbingModeParameters = ScrubbingModeParameters.DEFAULT
      SeekBenchDragMode.FRACTIONAL -> {
        val before = (d.toleranceBeforeMs / durationMs).coerceIn(0.0, 1.0)
        val after = (d.toleranceAfterMs / durationMs).coerceIn(0.0, 1.0)
        player.scrubbingModeParameters = ScrubbingModeParameters.DEFAULT.buildUpon().setFractionalSeekTolerance(before, after).build()
      }
      // With no fractional tolerance, scrubbing mode uses the player's own seek parameters.
      SeekBenchDragMode.CLOSESTSYNC -> {
        player.scrubbingModeParameters = ScrubbingModeParameters.DEFAULT
        player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
      }
    }
    player.isScrubbingModeEnabled = true

    val count = max(1, (d.durationMs * d.hz / 1000).roundToInt())
    val periodNs = (1e9 / d.hz.coerceAtLeast(1.0)).roundToLong()
    val collector = DragCollector(System.nanoTime())
    drag = collector
    for (k in 0 until count) {
      waitUntil(collector.startNs + k * periodNs)
      val target = frameStartMs(d.fromMs + (d.toMs - d.fromMs) * k / count)
      collector.issue(target * 1000, System.nanoTime())
      lastSeekPosMs = target
      player.seekTo(target)
    }
    collector.endNs = collector.startNs + count * periodNs
    waitUntil(collector.endNs)
    drag = null

    player.isScrubbingModeEnabled = false
    player.setSeekParameters(SeekParameters.EXACT)
    player.scrubbingModeParameters = ScrubbingModeParameters.DEFAULT
    val settle = seekAndWait("settle-${d.name}", frameStartMs(d.toMs), log = true)
    seeks += settle
    delay(options.restMs.toLong())

    val windowMs = ms(collector.endNs - collector.startNs)
    val perSecond = (collector.pictures / (windowMs / 1000) * 10).roundToInt() / 10.0
    Log.i(TAG, "seekbench drag pass=${options.label} name=${d.name} mode=${d.mode.name.lowercase()} seeks=${collector.seeksIssued} pictures=${collector.pictures} distinct=${collector.distinct.size} perSecond=$perSecond settle=+${settle.shownMs.roundToInt()}")
    return SeekBenchDragResult(
      name = d.name,
      mode = d.mode,
      seeksIssued = collector.seeksIssued.toDouble(),
      distinctTargets = collector.firstIssueNs.size.toDouble(),
      windowMs = windowMs,
      pictures = collector.pictures.toDouble(),
      distinctFrames = collector.distinct.size.toDouble(),
      picturesPerSecond = perSecond,
      settleMs = settle.shownMs,
      frames = collector.frames.toTypedArray(),
    )
  }

  /**
   * Seek to `targetMs` (a frame start) and wait until that frame reaches the view, or
   * [SEEK_TIMEOUT_MS]. ExoPlayer skips a seek to the position it's already at, so that seek goes
   * 1 ms earlier: the first frame starting at or after it is still the same frame.
   */
  private suspend fun seekAndWait(kind: String, targetMs: Long, log: Boolean): SeekBenchSeek {
    val same = targetMs == lastSeekPosMs || targetMs == player.currentPosition
    val seeked = if (same) max(0L, targetMs - 1) else targetMs
    val w = Waiter(targetMs * 1000, System.nanoTime())
    waiter = w
    lastSeekPosMs = seeked
    player.seekTo(seeked)
    val landed = withTimeoutOrNull(SEEK_TIMEOUT_MS) { w.done.await() }
    if (waiter === w) waiter = null
    val row = if (landed == null) {
      SeekBenchSeek(kind, targetMs.toDouble(), seeked.toDouble(), -1.0, -1.0, -1.0, -1.0)
    } else {
      val (frame, shownNs) = landed
      SeekBenchSeek(
        kind = kind,
        targetMs = targetMs.toDouble(),
        seekedMs = seeked.toDouble(),
        ptsMs = frame.ptsUs / 1000.0,
        releasedMs = if (frame.releaseNs == C.TIME_UNSET) -1.0 else ms(frame.releaseNs - w.startNs),
        callbackMs = ms(frame.callbackNs - w.startNs),
        shownMs = ms(shownNs - w.startNs),
      )
    }
    if (log) {
      if (landed == null) {
        Log.i(TAG, "seekbench seek target=$targetMs released=+? shown=timeout pass=${options.label} kind=$kind")
      } else {
        val released = if (row.releasedMs < 0) "?" else "${row.releasedMs.roundToInt()}"
        Log.i(TAG, "seekbench seek target=$targetMs released=+$released shown=+${row.shownMs.roundToInt()} cb=+${row.callbackMs.roundToInt()} pts=${row.ptsMs.roundToInt()} pass=${options.label} kind=$kind")
      }
    }
    return row
  }

  /** A frame reached the view (main thread): match it to its release, then tell whoever waits. */
  private fun onShown(timestampNs: Long) {
    val shownNs = System.nanoTime()
    val frame = takeReleased(timestampNs, shownNs)
    if (!firstFrame.isCompleted) firstFrame.complete(shownNs)
    drag?.onPicture(frame, shownNs)
    val w = waiter ?: return
    if (frame != null && inFrame(frame.ptsUs, w.targetUs) && frame.callbackNs >= w.startNs) {
      waiter = null
      w.done.complete(frame to shownNs)
    }
  }

  /** The release this frame was drawn with: the one with the texture's timestamp (dropping the
   * older ones, drawn over in the same refresh), else the oldest one not yet stale. */
  private fun takeReleased(timestampNs: Long, nowNs: Long): Released? {
    synchronized(inFlight) {
      val match = inFlight.indexOfFirst { it.releaseNs == timestampNs }
      if (match >= 0) {
        val frame = inFlight[match]
        repeat(match + 1) { inFlight.removeFirst() }
        return frame
      }
      val stale = nowNs - STALE_FRAME_MS * 1_000_000
      while (inFlight.size > 1 && inFlight.first().releaseNs < stale) inFlight.removeFirst()
      if (inFlight.isEmpty()) return null
      matchedByOrder++
      return inFlight.removeFirst()
    }
  }

  /** Whether a frame at `ptsUs` is the one an exact seek to `targetUs` shows (the first frame
   * starting at or after it; a millisecond of rounding allowed). */
  private fun inFrame(ptsUs: Long, targetUs: Long): Boolean =
    ptsUs >= targetUs - 1_000 && ptsUs < targetUs + (1_000_000 / fps).toLong()

  /** Where the frame showing at `timeMs` starts, whole ms rounded down (the clip's frame grid). */
  private fun frameStartMs(timeMs: Double): Long {
    val frame = floor(timeMs.coerceAtLeast(0.0) * fps / 1000 + 1e-6)
    return floor(frame * 1000 / fps).toLong()
  }

  private suspend fun waitUntil(nanoTime: Long) {
    val leftMs = (nanoTime - System.nanoTime()) / 1_000_000
    if (leftMs > 0) delay(leftMs)
  }

  private fun failIfError() {
    error?.let { throw IllegalStateException("The seek bench's player failed: ${it.errorCodeName}", it) }
  }

  /** Whether the decoder supports FEATURE_LowLatency (API 30+), and how many instances it allows. */
  private fun decoderCapabilities(name: String): Pair<Boolean, Int> {
    val info = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { it.name == name } ?: return false to -1
    val type = info.supportedTypes.firstOrNull { it.startsWith("video/") } ?: return false to -1
    val caps = info.getCapabilitiesForType(type)
    val lowLatency = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
      caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
    return lowLatency to caps.maxSupportedInstances
  }

  private companion object {
    const val TAG = "PulseEditor"
    const val MAX_IN_FLIGHT = 16
    const val STALE_FRAME_MS = 300L
    const val SEEK_TIMEOUT_MS = 3_000L
    const val PREPARE_TIMEOUT_MS = 5_000L
    const val VIEW_TIMEOUT_MS = 3_000L
    const val VIEW_BOTTOM_MARGIN_DP = 48
    const val BUFFER_MIN_MS = 500
    const val BUFFER_MAX_MS = 30_000
    const val BUFFER_FOR_PLAYBACK_MS = 100
    const val BUFFER_AFTER_REBUFFER_MS = 200

    fun ms(ns: Long): Double = ns / 1e6
  }
}
