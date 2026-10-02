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
  struct Rendered {
    let duration: CMTime
    /// When the last decoded video frame starts (before any padding to `duration`).
    let lastVideoFrame: CMTime
    /// When the last decoded audio starts; `.invalid` when the timeline has sound that decoded to
    /// nothing, nil when it has no sound.
    let lastAudio: CMTime?
  }

  @discardableResult
  static func timeline(
    _ items: [(clip: MergeClip, media: Probe.Media)], target: RenderTarget, to output: URL,
    faststart: Bool, progress: @escaping @Sendable (Double) -> Void
  ) async throws -> Rendered {
    try? FileManager.default.removeItem(at: output)
    let composed = try await Timeline.compose(items, target: target)
    let (composition, video, audio, videoComposition, duration) = (
      composed.composition, composed.videoTrack, composed.audioTrack, composed.videoComposition, composed.duration)

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

    let last = try await SampleTransfer.run(
      reader: reader, writer: writer, pairs: pairs, duration: duration,
      frameDuration: videoComposition.frameDuration, progress: progress)
    return Rendered(duration: duration, lastVideoFrame: last[0], lastAudio: last.count > 1 ? last[1] : nil)
  }
}
