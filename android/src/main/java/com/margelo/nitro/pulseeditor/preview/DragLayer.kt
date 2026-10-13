package com.margelo.nitro.pulseeditor.preview

import android.content.Context
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import com.margelo.nitro.pulseeditor.MergeClip
import com.margelo.nitro.pulseeditor.mediaUri
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The preview's drag layer: a plain ExoPlayer on the source file of the clip under the finger,
 * drawn into its own TextureView on top of the composition while a drag runs. No effects
 * pipeline: the clip's edit (rotate / flip / crop / letterbox) is the TextureView's transform and
 * clip bounds, so a scrubbing seek is decode + one draw. On the S24 that lands ~27 pictures/s
 * against the CompositionPlayer's 9-12 (seek bench, 2026-10-13).
 *
 * The box sits over the whole preview, black once shown; it's shown once its first frame for the
 * drag is up (that frame is the one already on screen, so nothing jumps) and hidden by the
 * preview once the composition shows the frame the drag ended on.
 */
@OptIn(UnstableApi::class)
internal class DragLayer(
  context: Context,
  private val onFrame: (clip: Int, ptsUs: Long, releaseNs: Long) -> Unit,
  private val onFailed: () -> Unit,
) {
  private val texture = TextureView(context).apply { isOpaque = false }
  val box = FrameLayout(context).apply {
    // Gone between drags; during one, before its first frame is up, all but transparent (not 0:
    // a view with no alpha isn't drawn, and a TextureView reports frames as it draws), and with no
    // background, so it doesn't darken the composition under it.
    visibility = android.view.View.GONE
    forceHasOverlappingRendering(false)
    addView(texture, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
  }
  private val main = Handler(Looper.getMainLooper())
  private var player: ExoPlayer? = null
  private var surface: Surface? = null
  /** The clip loaded (its index on the timeline and the clip itself, for the geometry). */
  private var clipIndex = -1
  private var clip: MergeClip? = null
  private var videoSize: VideoSize? = null
  /** Since a clip switch, the new clip and its size: applied once its first frame is up, so the old
   * clip's last frame isn't redrawn with the new clip's geometry while the file loads. */
  private var pendingClip: MergeClip? = null
  private var pendingSize: VideoSize? = null
  private var lastSeekMs = -1L
  /** Since a clip switch, until a frame of the new file is up: the source frames asked for (a
   * frame the old file released late is dropped, it isn't one of them) and a frame's length. */
  private var switching = false
  private val sinceSwitchUs = mutableListOf<Long>()
  private var frameUs = 33_333L
  /** The canvas's place in the view (letterboxed like the composition's output). */
  private var canvasW = 1080.0
  private var canvasH = 1920.0
  private val inFlight = ArrayDeque<Pair<Long, Long>>()
  /** How frames were matched so far (logged once each). */
  private val matchedBy = mutableSetOf<String>()
  var shown = false
    private set
  /** False once the player failed (decoder init, the file): the preview stays on the composition. */
  var usable = true
    private set

  private val frames = VideoFrameMetadataListener { presentationTimeUs, releaseTimeNs, _, _ ->
    if (presentationTimeUs == C.TIME_UNSET) return@VideoFrameMetadataListener
    main.post {
      inFlight.addLast(presentationTimeUs to releaseTimeNs)
      while (inFlight.size > 16) inFlight.removeFirst()
    }
  }

  private val listener = object : Player.Listener {
    override fun onVideoSizeChanged(size: VideoSize) {
      if (size.width <= 0 || size.height <= 0) return
      if (switching) {
        pendingSize = size
        return
      }
      videoSize = size
      applyTransform()
    }

    override fun onPlayerError(error: PlaybackException) {
      // Once: the player is let go (its decoder too) and every later seek goes to the composition.
      Log.w("PulseEditor", "drag layer failed: ${error.errorCodeName}", error)
      usable = false
      hide()
      main.post { release() }
      onFailed()
    }
  }

  init {
    texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
      override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        surface = Surface(st)
        player?.setVideoSurface(surface)
        applyTransform()
        // A new surface is empty and a paused player doesn't draw into it again: the same frame
        // through a real seek (1 ms earlier, ExoPlayer drops a seek to where it is).
        if (shown && lastSeekMs > 0) {
          lastSeekMs -= 1
          player?.seekTo(lastSeekMs)
        }
      }

      override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = applyTransform()

      override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        player?.clearVideoSurface()
        surface?.release()
        surface = null
        return true
      }

      // Called before the view takes the new frame: the texture's timestamp here is still the
      // previous frame's (the S24: each one equals the release time of the frame before), so
      // matching on it pairs every frame with the one before. Once this draw is done it's the
      // frame's own release time.
      override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
        main.post { onLatched(st.timestamp) }
      }
    }
  }

  private fun onLatched(ts: Long) {
    var match = inFlight.indexOfFirst { it.second == ts }
    // No timestamp to go by: the latest frame released (the player is paused, one frame per seek).
    if (match < 0) match = inFlight.lastIndex
    if (match < 0) return
    val frame = inFlight[match].also { repeat(match + 1) { inFlight.removeFirst() } }
    val by = if (frame.second == ts) "release time" else "order"
    if (matchedBy.add(by)) {
      Log.i("PulseEditor", "drag layer frames matched by $by (texture timestamp $ts, release ${frame.second}, pts ${frame.first})")
    }
    if (switching) {
      if (sinceSwitchUs.none { frame.first >= it - 1_000 && frame.first < it + frameUs }) return
      switching = false
      sinceSwitchUs.clear()
      clip = pendingClip
      videoSize = pendingSize
      pendingClip = null
      pendingSize = null
      applyTransform()
    }
    onFrame(clipIndex, frame.first, frame.second)
  }

  fun setCanvas(width: Double, height: Double) {
    canvasW = width
    canvasH = height
    applyTransform()
  }

  /**
   * Seek the drag player to `sourceMs` of clip `index` (loading it first if it's another clip).
   * `size` is the clip's upright size from the probe, so its first frame gets its own geometry
   * (the player's size report can come a draw later); `frameUs` its frame's length.
   */
  fun seek(index: Int, c: MergeClip, size: VideoSize?, sourceMs: Long, frameUs: Long) {
    if (!usable) return
    val p = player ?: build()
    if (index != clipIndex || c.uri != (if (switching) pendingClip else clip)?.uri) {
      clipIndex = index
      pendingSize = size
      inFlight.clear()
      lastSeekMs = -1L
      switching = true
      sinceSwitchUs.clear()
      p.setMediaItem(MediaItem.fromUri(mediaUri(c.uri)))
      p.prepare()
    }
    if (switching) {
      sinceSwitchUs += sourceMs * 1000
      if (sinceSwitchUs.size > 16) sinceSwitchUs.removeAt(0)
    }
    this.frameUs = frameUs
    if (switching) pendingClip = c
    else {
      clip = c
      applyTransform()
    }
    // ExoPlayer drops a seek to the position it's at (a new drag that starts where the last one
    // ended), and in scrubbing mode a later seek waits for that one's frame: 1 ms earlier is the
    // same frame (the first starting at or after it) through a real seek.
    val ms = if (sourceMs == lastSeekMs && sourceMs > 0) sourceMs - 1 else sourceMs
    lastSeekMs = ms
    p.seekTo(ms)
  }

  /** A drag starts: drawn (so its TextureView gets frames) but not seen yet. */
  fun arm() {
    if (box.visibility == android.view.View.VISIBLE) return
    box.alpha = HIDDEN_ALPHA
    box.setBackgroundColor(Color.TRANSPARENT)
    box.visibility = android.view.View.VISIBLE
  }

  fun show() {
    if (shown) return
    shown = true
    box.alpha = 1f
    box.setBackgroundColor(Color.BLACK)
  }

  fun hide() {
    shown = false
    box.visibility = android.view.View.GONE
  }

  /** A new timeline: the loaded clip may have changed, the next seek loads it again. */
  fun forget() {
    clipIndex = -1
    clip = null
    pendingClip = null
  }

  fun invalidate() {
    forget()
    hide()
  }

  fun release() {
    player?.release()
    player = null
    forget()
    videoSize = null
    pendingSize = null
    inFlight.clear()
    switching = false
    sinceSwitchUs.clear()
    lastSeekMs = -1L
  }

  // A 10 s back buffer: seeks re-read a local file quickly, and the composition keeps its own.
  private fun build(): ExoPlayer = ExoPlayer.Builder(box.context.applicationContext)
    .setLooper(Looper.getMainLooper())
    .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(500, 3_000, 100, 200).setBackBuffer(10_000, true).build())
    .build()
    .also { p ->
      // Pictures only: the drag is silent and the audio renderer would be one more thing to seek.
      p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
      p.addListener(listener)
      p.setVideoFrameMetadataListener(frames)
      p.setScrubbingModeEnabled(true)
      surface?.let { p.setVideoSurface(it) }
      player = p
    }

  /**
   * The composition's picture of this clip as the TextureView's transform: the decoded buffer
   * (stretched to the view by TextureView; upright already, Media3 applies the rotation tag) →
   * the edit's clockwise rotation → flip → crop → fitted onto the canvas, which is fitted
   * into the view. Crop and letterbox are the clip bounds; the box is black around them.
   */
  private fun applyTransform() {
    val c = clip ?: return
    val size = videoSize ?: return
    val vw = texture.width.toFloat()
    val vh = texture.height.toFloat()
    if (vw <= 0 || vh <= 0) return
    val m = Matrix()
    var w = size.width * size.pixelWidthHeightRatio
    var h = size.height.toFloat()
    m.setScale(w / vw, h / vh)
    fun turn(degrees: Int) {
      when (((degrees % 360) + 360) % 360) {
        90 -> { m.postRotate(90f); m.postTranslate(h, 0f); w = h.also { h = w } }
        180 -> { m.postRotate(180f); m.postTranslate(w, h) }
        270 -> { m.postRotate(270f); m.postTranslate(0f, w); w = h.also { h = w } }
      }
    }
    turn(c.rotation.roundToInt())
    if (c.flipped) {
      m.postScale(-1f, 1f)
      m.postTranslate(w, 0f)
    }
    c.crop?.let { k ->
      m.postTranslate((-k.x * w).toFloat(), (-k.y * h).toFloat())
      w *= k.w.toFloat()
      h *= k.h.toFloat()
    }
    val canvasScale = min(vw / canvasW, vh / canvasH).toFloat()
    val canvas = RectF(0f, 0f, (canvasW * canvasScale).toFloat(), (canvasH * canvasScale).toFloat())
    canvas.offset((vw - canvas.width()) / 2, (vh - canvas.height()) / 2)
    val s = min(canvas.width() / w, canvas.height() / h)
    val left = canvas.left + (canvas.width() - w * s) / 2
    val top = canvas.top + (canvas.height() - h * s) / 2
    m.postScale(s, s)
    m.postTranslate(left, top)
    texture.setTransform(m)
    texture.clipBounds = Rect(left.roundToInt(), top.roundToInt(), (left + w * s).roundToInt(), (top + h * s).roundToInt())
    texture.invalidate()
  }

  private companion object {
    const val HIDDEN_ALPHA = 0.01f
  }
}
