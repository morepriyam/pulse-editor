package com.margelo.nitro.pulseeditor.preview

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.margelo.nitro.pulseeditor.MergeAudio
import com.margelo.nitro.pulseeditor.MergeClip
import com.margelo.nitro.pulseeditor.MergeOptions
import com.margelo.nitro.pulseeditor.ProbeResult
import com.margelo.nitro.pulseeditor.Transfer
import com.margelo.nitro.pulseeditor.mediaUri
import com.margelo.nitro.pulseeditor.merge.MergeExport
import com.margelo.nitro.pulseeditor.merge.MergePlan
import com.margelo.nitro.pulseeditor.transcode.audioProcessors
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The composition the preview plays: merge's own video items (trim, natural-pitch speed,
 * rotate / flip / crop, letterbox) at the preview's size, and a parallel audio sequence with
 * each clip's window clipped by Media3, silence for muted or silent clips. CompositionPlayer
 * lays items end to end by their clipped, retimed length, so every item states its source
 * duration.
 */
@OptIn(UnstableApi::class)
internal object PreviewComposition {
  fun build(clips: List<MergeClip>, media: List<ProbeResult>, width: Int, height: Int, fps: Double): Composition {
    val options = MergeOptions(width.toDouble(), height.toDouble(), fps, 5_000_000.0, MergeAudio(48_000.0, 2.0))
    val pairs = clips.zip(media)
    val video = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO))
      .addItems(pairs.map { (clip, m) -> MergeExport.videoItem(clip, m, options).withSourceDuration(m) })
      .build()
    val sequences = mutableListOf(video)
    if (pairs.any { (clip, m) -> !clip.muted && m.audio != null }) {
      val audio = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
      for ((clip, m) in pairs) {
        if (clip.muted || m.audio == null) audio.addGap((MergePlan.timelineMs(clip, m) * 1000).roundToLong())
        else audio.addItem(audioItem(clip, m, options))
      }
      sequences += audio.build()
    }
    return Composition.Builder(sequences).apply {
      if (media.any { it.video?.transfer != null && it.video?.transfer != Transfer.SDR }) setHdrMode(MergeExport.hdrMode)
    }.build()
  }

  /** A clip's sound in its window, retimed, in the preview's audio layout. */
  private fun audioItem(clip: MergeClip, media: ProbeResult, options: MergeOptions): EditedMediaItem {
    val mediaItem = MediaItem.Builder().setUri(mediaUri(clip.uri))
    if (clip.endMs > clip.startMs) {
      mediaItem.setClippingConfiguration(
        MediaItem.ClippingConfiguration.Builder()
          .setStartPositionMs(clip.startMs.toLong())
          .setEndPositionMs(minOf(clip.endMs, media.durationMs).toLong())
          .build(),
      )
    }
    val item = EditedMediaItem.Builder(mediaItem.build()).setRemoveVideo(true)
    MergeExport.speed(clip)?.let { item.setSpeed(it) }
    val processors = audioProcessors(options.audio.sampleRate.roundToInt(), options.audio.channels.roundToInt())
    return item.setEffects(Effects(processors, emptyList())).setDurationUs((media.durationMs * 1000).roundToLong()).build()
  }

  private fun EditedMediaItem.withSourceDuration(media: ProbeResult): EditedMediaItem =
    buildUpon().setDurationUs((media.durationMs * 1000).roundToLong()).build()
}
