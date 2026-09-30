package com.margelo.nitro.pulseeditor.merge

import com.margelo.nitro.pulseeditor.MergeClip
import com.margelo.nitro.pulseeditor.MergeOptions
import com.margelo.nitro.pulseeditor.ProbeResult
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Whether a merge can be a transmux join (samples copied) or needs one full encode. Media3 can't
 * mix copied and re-encoded clips in one export, and its copy mode isn't frame-accurate at a
 * trimmed start (it begins at the previous keyframe), so the join is only for untrimmed clips
 * with no rendered edit that already share one format fitting the output. Everything else is one
 * hardware encode.
 */
object MergePlan {
  /** A VBR encode overshoots its target: recordings aimed at 5 Mbps average 6–7 Mbps. Clips up
   * to this multiple of the chosen bitrate still count as at it (see the iOS MergePlan). */
  private const val BITRATE_TOLERANCE = 1.6

  fun canJoin(clips: List<MergeClip>, media: List<ProbeResult>, options: MergeOptions): Boolean =
    clips.indices.none { needsRender(clips[it]) || isTrimmed(clips[it], media[it]) } &&
      joinBlockers(media, options).isEmpty()

  /** Edits that change pixels or timing, so the clip can't be copied. */
  fun needsRender(clip: MergeClip): Boolean =
    (clip.rotation.roundToInt() % 360) != 0 || clip.flipped || clip.crop != null || abs(clip.speed - 1) > 0.0001

  /** What a clip contributes to the timeline, ms: its window at its speed. */
  fun timelineMs(clip: MergeClip, media: ProbeResult): Double {
    val window = if (clip.endMs > clip.startMs) minOf(clip.endMs, media.durationMs) - clip.startMs else media.durationMs
    return window / clip.speed
  }

  fun isTrimmed(clip: MergeClip, media: ProbeResult): Boolean =
    clip.endMs > clip.startMs && (clip.startMs > 1 || clip.endMs < media.durationMs - 50)

  /** Why these clips can't be joined without re-encoding (empty when they can). */
  fun joinBlockers(media: List<ProbeResult>, options: MergeOptions): List<String> {
    val reasons = mutableListOf<String>()
    val first = media.firstOrNull()?.video ?: return listOf("clip 1 has no video")
    val firstAudio = media.firstNotNullOfOrNull { it.audio }
    media.forEachIndexed { i, m ->
      val n = i + 1
      val v = m.video ?: run { reasons += "clip $n has no video"; return@forEachIndexed }
      if (v.codec != "h264") reasons += "clip $n is ${v.codec}, not h264"
      val swapped = v.rotation.roundToInt() % 180 != 0
      val (w, h) = if (swapped) v.height to v.width else v.width to v.height
      if (w != options.width || h != options.height) reasons += "clip $n displays ${w.toInt()}x${h.toInt()}"
      if (v.width != first.width || v.height != first.height || v.rotation != first.rotation) {
        reasons += "clip $n is coded differently from clip 1"
      }
      if (v.fps > 0 && v.fps.roundToInt() != options.fps.roundToInt()) reasons += "clip $n is ${v.fps.roundToInt()} fps"
      if (v.bitrate > options.bitrate * BITRATE_TOLERANCE) reasons += "clip $n bitrate is above the chosen one"
      m.audio?.let { a ->
        if (a.codec != "aac") reasons += "clip $n audio is ${a.codec}"
        if (firstAudio != null && (a.sampleRate != firstAudio.sampleRate || a.channels != firstAudio.channels)) {
          reasons += "clip $n audio layout differs"
        }
      }
    }
    return reasons
  }
}
