import AVFoundation

/// A timeline of clips with their edits as an AVFoundation composition: each clip trimmed into
/// its slot on one video track (and one audio track), retimed in place for speed, with a video
/// composition carrying each clip's geometry. `Render` encodes it for merge and conform; the
/// preview plays the very same composition, so what plays is what exports.
enum Timeline {
  struct Composed {
    let composition: AVMutableComposition
    let videoTrack: AVMutableCompositionTrack
    /// Absent when no clip has sound (or every clip with sound is muted).
    let audioTrack: AVMutableCompositionTrack?
    let videoComposition: AVMutableVideoComposition
    let duration: CMTime
  }

  /// `snapped`: indexes of clips whose trims snap to the nearest frame, as the join path copies
  /// them (the preview passes the clips the export will copy); the others are trimmed exactly.
  static func compose(
    _ items: [(clip: MergeClip, media: Probe.Media)], target: RenderTarget, snapped: Set<Int> = []
  ) async throws -> Composed {
    let composition = AVMutableComposition()
    guard let video = composition.addMutableTrack(withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid) else {
      throw MergeError.failed("Couldn't create the video track.")
    }
    let hasAudio = items.contains { !$0.clip.muted && $0.media.audioTrack != nil }
    let audio = hasAudio
      ? composition.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid)
      : nil

    var instructions: [AVVideoCompositionInstruction] = []
    var cursor = CMTime.zero
    for (index, (clip, media)) in items.enumerated() {
      guard let sourceVideo = media.videoTrack else { throw MergeError.failed("A clip has no video.") }
      let (sourceSize, sourceTransform, sourceRange, frame) = try await sourceVideo.load(
        .naturalSize, .preferredTransform, .timeRange, .minFrameDuration)
      let range = try Join.trimRange(
        startMs: clip.startMs, endMs: clip.endMs, in: sourceRange, frame: snapped.contains(index) ? frame : nil)

      // The clip's slot: trimmed in at the cursor, then retimed in place.
      try video.insertTimeRange(range, of: sourceVideo, at: cursor)
      if let audio, !clip.muted, let sourceAudio = media.audioTrack {
        let overlap = range.intersection(try await sourceAudio.load(.timeRange))
        if overlap.duration > .zero {
          try audio.insertTimeRange(overlap, of: sourceAudio, at: cursor + (overlap.start - range.start))
        }
      }
      var slot = range.duration
      if abs(clip.speed - 1) > 0.0001 {
        let scaled = CMTimeMultiplyByFloat64(slot, multiplier: 1 / clip.speed)
        composition.scaleTimeRange(CMTimeRange(start: cursor, duration: slot), toDuration: scaled)
        slot = scaled
      }

      // The clip's picture: its own geometry for its slot.
      let geometry = MergeGeometry(
        clip: clip, sourceSize: sourceSize, sourceTransform: sourceTransform,
        targetSize: target.size, targetTransform: target.transform, canvas: target.canvas)
      let layer = AVMutableVideoCompositionLayerInstruction(assetTrack: video)
      layer.setTransform(geometry.transform, at: cursor)
      if let crop = geometry.sourceCrop { layer.setCropRectangle(crop, at: cursor) }
      let instruction = AVMutableVideoCompositionInstruction()
      instruction.timeRange = CMTimeRange(start: cursor, duration: slot)
      instruction.layerInstructions = [layer]
      instructions.append(instruction)

      cursor = cursor + slot
    }
    let duration = cursor

    let videoComposition = AVMutableVideoComposition()
    videoComposition.instructions = instructions
    videoComposition.renderSize = target.size
    // At most the target rate, whatever the source timing (a 2× clip doesn't become 60 fps).
    videoComposition.frameDuration = CMTime(value: 1, timescale: CMTimeScale(target.fps.rounded()))
    videoComposition.sourceTrackIDForFrameTiming = kCMPersistentTrackID_Invalid
    videoComposition.colorPrimaries = AVVideoColorPrimaries_ITU_R_709_2
    videoComposition.colorTransferFunction = AVVideoTransferFunction_ITU_R_709_2
    videoComposition.colorYCbCrMatrix = AVVideoYCbCrMatrix_ITU_R_709_2

    return Composed(
      composition: composition, videoTrack: video, audioTrack: audio,
      videoComposition: videoComposition, duration: duration)
  }
}
