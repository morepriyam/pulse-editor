import AVFoundation

/// The format rendered video is written in.
struct RenderTarget {
  /// Coded size and orientation tag: the draft's own (e.g. 1920×1080 tagged 270° for a portrait
  /// iPhone recording) when the result joins other clips, or the upright canvas for a full encode.
  let size: CGSize
  let transform: CGAffineTransform
  /// The display canvas, e.g. 1080×1920.
  let canvas: CGSize
  let fps: Double
  let bitrate: Double
  let audio: MergeAudio
}

/// Render clips, in order, with their edits (trim, speed, rotate, flip, crop, mute) into one file
/// in `target`'s format:
/// AVMutableComposition (each clip trimmed into its slot, scaleTimeRange for speed) →
/// AVVideoComposition (one instruction per clip carrying its geometry) → AVAssetReader →
/// AVAssetWriter (H.264 at the chosen bitrate, AAC with natural pitch).
enum Render {
  /// Returns the rendered duration.
  @discardableResult
  static func timeline(
    _ items: [(clip: MergeClip, media: Probe.Media)], target: RenderTarget, to output: URL,
    faststart: Bool, progress: @escaping @Sendable (Double) -> Void
  ) async throws -> CMTime {
    try? FileManager.default.removeItem(at: output)
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
    for (clip, media) in items {
      guard let sourceVideo = media.videoTrack else { throw MergeError.failed("A clip has no video.") }
      let (sourceSize, sourceTransform, sourceRange) = try await sourceVideo.load(.naturalSize, .preferredTransform, .timeRange)
      let range = Join.trimRange(startMs: clip.startMs, endMs: clip.endMs, in: sourceRange)

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

    let reader = try AVAssetReader(asset: composition)
    reader.timeRange = CMTimeRange(start: .zero, duration: duration)
    let videoOutput = AVAssetReaderVideoCompositionOutput(
      videoTracks: [video],
      videoSettings: [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange])
    videoOutput.videoComposition = videoComposition
    videoOutput.alwaysCopiesSampleData = false
    reader.add(videoOutput)

    let writer = try AVAssetWriter(outputURL: output, fileType: .mp4)
    writer.shouldOptimizeForNetworkUse = faststart
    let videoInput = AVAssetWriterInput(
      mediaType: .video, outputSettings: EncodeSettings.h264(size: target.size, fps: target.fps, bitrate: target.bitrate))
    videoInput.expectsMediaDataInRealTime = false
    videoInput.transform = target.transform
    writer.add(videoInput)
    var pairs: [(output: AVAssetReaderOutput, input: AVAssetWriterInput)] = [(videoOutput, videoInput)]

    if let audio {
      let audioOutput = AVAssetReaderAudioMixOutput(audioTracks: [audio], audioSettings: EncodeSettings.pcm(target.audio))
      audioOutput.audioTimePitchAlgorithm = .spectral  // sped-up / slowed audio keeps its natural pitch
      audioOutput.alwaysCopiesSampleData = false
      reader.add(audioOutput)
      let audioInput = AVAssetWriterInput(mediaType: .audio, outputSettings: EncodeSettings.aac(target.audio))
      audioInput.expectsMediaDataInRealTime = false
      writer.add(audioInput)
      pairs.append((audioOutput, audioInput))
    }

    try await SampleTransfer.run(
      reader: reader, writer: writer, pairs: pairs, duration: duration,
      frameDuration: videoComposition.frameDuration, progress: progress)
    return duration
  }
}
