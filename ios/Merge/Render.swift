import AVFoundation

/// The format a rendered clip is written in: the draft's own (the recorder's), so it joins the
/// copied clips like a recording.
struct RenderTarget {
  /// Coded size and orientation tag, e.g. 1920×1080 tagged 270° for a portrait iPhone recording.
  let size: CGSize
  let transform: CGAffineTransform
  /// The display canvas, e.g. 1080×1920.
  let canvas: CGSize
  let fps: Double
  let bitrate: Double
  let audio: MergeAudio
}

/// Render one clip with its edit (trim, speed, rotate, flip, crop, mute) into `target`'s format:
/// AVMutableComposition (trim, scaleTimeRange for speed) → AVVideoComposition (the geometry) →
/// AVAssetReader → AVAssetWriter (H.264 at the chosen bitrate, AAC with natural pitch).
enum Render {
  static func clip(
    _ clip: MergeClip, _ media: Probe.Media, target: RenderTarget, to output: URL,
    progress: @escaping @Sendable (Double) -> Void
  ) async throws {
    try? FileManager.default.removeItem(at: output)
    guard let sourceVideo = media.videoTrack else { throw MergeError.failed("Clip has no video.") }
    let (sourceSize, sourceTransform, sourceRange) = try await sourceVideo.load(.naturalSize, .preferredTransform, .timeRange)
    let range = Join.trimRange(startMs: clip.startMs, endMs: clip.endMs, in: sourceRange)

    // The clip on its own timeline: trimmed, then retimed.
    let composition = AVMutableComposition()
    guard let video = composition.addMutableTrack(withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid) else {
      throw MergeError.failed("Couldn't create the video track.")
    }
    try video.insertTimeRange(range, of: sourceVideo, at: .zero)
    var audio: AVMutableCompositionTrack?
    if !clip.muted, let sourceAudio = media.audioTrack {
      let overlap = range.intersection(try await sourceAudio.load(.timeRange))
      if overlap.duration > .zero,
         let track = composition.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid) {
        try track.insertTimeRange(overlap, of: sourceAudio, at: overlap.start - range.start)
        audio = track
      }
    }
    if abs(clip.speed - 1) > 0.0001 {
      composition.scaleTimeRange(
        CMTimeRange(start: .zero, duration: range.duration),
        toDuration: CMTimeMultiplyByFloat64(range.duration, multiplier: 1 / clip.speed))
    }
    let duration = composition.duration

    // The picture: one layer instruction carrying the whole geometry.
    let geometry = MergeGeometry(
      clip: clip, sourceSize: sourceSize, sourceTransform: sourceTransform,
      targetSize: target.size, targetTransform: target.transform, canvas: target.canvas)
    let layer = AVMutableVideoCompositionLayerInstruction(assetTrack: video)
    layer.setTransform(geometry.transform, at: .zero)
    if let crop = geometry.sourceCrop { layer.setCropRectangle(crop, at: .zero) }
    let instruction = AVMutableVideoCompositionInstruction()
    instruction.timeRange = CMTimeRange(start: .zero, duration: duration)
    instruction.layerInstructions = [layer]
    let videoComposition = AVMutableVideoComposition()
    videoComposition.instructions = [instruction]
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
  }
}
