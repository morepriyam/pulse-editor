import AVFoundation

/// A timeline of clips with their edits as an AVFoundation composition: each clip trimmed into
/// its slot on one video track (and its sound on the audio tracks), retimed in place for speed, with a video
/// composition carrying each clip's geometry. `Render` encodes it for merge and conform; the
/// preview plays the very same composition, so what plays is what exports.
enum Timeline {
  struct Composed {
    let composition: AVMutableComposition
    let videoTrack: AVMutableCompositionTrack
    /// Empty when no clip has sound (or every clip with sound is muted); more than one when a clip's
    /// sound is mixed from more than one source track.
    let audioTracks: [AVMutableCompositionTrack]
    let videoComposition: AVMutableVideoComposition
    let duration: CMTime
    /// Where each clip's slot starts, in the items' order.
    let starts: [CMTime]
  }

  /// A clip is whole source frames: both trim ends snap to the nearest frame, as the join's do,
  /// so a rendered clip, its copied neighbours and the preview all cut on the same frames.
  static func compose(_ items: [(clip: MergeClip, media: Probe.Media)], target: RenderTarget) async throws -> Composed {
    let composition = AVMutableComposition()
    guard let video = composition.addMutableTrack(withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid) else {
      throw MergeError.failed("Couldn't create the video track.")
    }
    let hasAudio = items.contains { !$0.clip.muted && $0.media.audioTrack != nil }
    var audio = hasAudio
      ? [composition.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid)].compactMap { $0 }
      : []

    var instructions: [AVVideoCompositionInstruction] = []
    var cursor = CMTime.zero
    var starts: [CMTime] = []
    for (clip, media) in items {
      starts.append(cursor)
      guard let sourceVideo = media.videoTrack else { throw MergeError.failed("A clip has no video.") }
      let (sourceSize, sourceTransform, sourceRange, frame) = try await sourceVideo.load(
        .naturalSize, .preferredTransform, .timeRange, .minFrameDuration)
      let range = try Join.trimRange(
        startMs: clip.startMs, endMs: clip.endMs, in: sourceRange, frame: frame, speed: clip.speed)

      // The clip's slot: trimmed in at the cursor, then retimed in place.
      try video.insertTimeRange(range, of: sourceVideo, at: cursor)
      if !audio.isEmpty, !clip.muted {
        try await insertAudio(media.audioTracks, range: range, at: cursor, into: &audio, of: composition)
      }
      var slot = range.duration
      if abs(clip.speed - 1) > 0.0001 {
        // Whole ms (trimRange made it so; this drops the µs rounding of the window).
        let scaled = CMTime(value: CMTimeValue((slot.seconds * 1000 / clip.speed).rounded()), timescale: 1000)
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
      composition: composition, videoTrack: video, audioTracks: audio,
      videoComposition: videoComposition, duration: duration, starts: starts)
  }

  /// Insert a clip's sound, the part of each source track inside `range`, at `cursor`: the k-th
  /// source track with sound there goes on the k-th composition audio track (added when missing),
  /// so tracks that play together are mixed by the reader or player. A track with no sound in
  /// `range` (an empty microphone track) is skipped. Returns the longest stretch any track covers
  /// and how many pieces went in.
  @discardableResult
  static func insertAudio(
    _ sources: [AVAssetTrack], range: CMTimeRange, at cursor: CMTime,
    into tracks: inout [AVMutableCompositionTrack], of composition: AVMutableComposition
  ) async throws -> (covered: CMTime, pieces: Int) {
    var covered = CMTime.zero
    var pieces = 0
    for source in sources {
      let overlap = range.intersection(try await source.load(.timeRange))
      guard overlap.duration > .zero else { continue }
      if pieces == tracks.count {
        guard let track = composition.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid) else {
          throw MergeError.failed("Couldn't create the audio track.")
        }
        tracks.append(track)
      }
      try tracks[pieces].insertTimeRange(overlap, of: source, at: cursor + (overlap.start - range.start))
      covered = CMTimeMaximum(covered, overlap.duration)
      pieces += 1
    }
    return (covered, pieces)
  }
}
