import AVFoundation

/// The fast path: clips that already share one format are joined by copying their samples
/// (AVMutableComposition + a passthrough export), with trims as frame-accurate edit lists.
/// Video is never decoded or encoded. Audio is copied too, unless the timeline has a gap (a muted
/// clip, or one without sound): then the audio alone is re-encoded with real silence in the gap
/// (see AudioEncode) and joined with the copied video.
enum Join {
  static func run(_ clips: [MergeClip], _ media: [Probe.Media], options: MergeOptions, progress: MergeProgress) async throws -> MergeResult {
    let composition = AVMutableComposition()
    guard let videoOut = composition.addMutableTrack(withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid) else {
      throw MergeError.failed("Couldn't create the video track.")
    }
    let hasAudio = zip(clips, media).contains { !$0.muted && $1.audioTrack != nil }
    let audioOut = hasAudio
      ? composition.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid)
      : nil
    var audioGap = false

    // Every clip shares the first clip's orientation (MergePlan checked), so one track transform
    // shows them all upright.
    videoOut.preferredTransform = try await media[0].videoTrack!.load(.preferredTransform)

    var cursor = CMTime.zero
    for (clip, m) in zip(clips, media) {
      let video = m.videoTrack!
      let range = trimRange(clip, in: try await video.load(.timeRange))
      try videoOut.insertTimeRange(range, of: video, at: cursor)

      // A muted clip (or one without sound, or sound shorter than its picture) leaves a gap.
      if let audioOut {
        var covered = CMTime.zero
        if !clip.muted, let audio = m.audioTrack {
          let overlap = range.intersection(try await audio.load(.timeRange))
          if overlap.duration > .zero {
            try audioOut.insertTimeRange(overlap, of: audio, at: cursor + (overlap.start - range.start))
            covered = overlap.duration
          }
        }
        if range.duration - covered > CMTime(value: 1, timescale: 10) { audioGap = true }
      }
      cursor = cursor + range.duration
    }
    try Task.checkCancellation()

    let output = try Merge.outputURL()
    var encodedAudio: URL?
    defer { if let encodedAudio { try? FileManager.default.removeItem(at: encodedAudio) } }
    do {
      var exportProgress = progress.callAsFunction
      // The composition reads the encoded audio through this asset; a track doesn't keep its
      // asset alive, so hold it until the export is done.
      var encodedAsset: AVAsset?
      if let audioOut, audioGap {
        let url = output.deletingPathExtension().appendingPathExtension("audio.m4a")
        encodedAudio = url
        try await AudioEncode.run(composition, audio: options.audio, to: url) { progress($0 * 0.3) }
        encodedAsset = try await replaceAudio(of: composition, track: audioOut, with: url)
        exportProgress = { progress(0.3 + $0 * 0.7) }
      }
      try Task.checkCancellation()
      try await export(composition, to: output, progress: exportProgress)
      withExtendedLifetime(encodedAsset) {}
      let result = try await verify(output, expectedMs: Probe.ms(cursor))
      progress(1)
      return result
    } catch {
      try? FileManager.default.removeItem(at: output)
      throw error
    }
  }

  /// The clip's trim window inside its source video track (the whole track when unset or invalid).
  static func trimRange(_ clip: MergeClip, in track: CMTimeRange) -> CMTimeRange {
    guard clip.endMs > clip.startMs else { return track }
    let start = track.start + CMTime(value: CMTimeValue(clip.startMs.rounded()), timescale: 1000)
    let end = CMTimeMinimum(track.start + CMTime(value: CMTimeValue(clip.endMs.rounded()), timescale: 1000), track.end)
    guard start < end else { return track }
    return CMTimeRange(start: start, end: end)
  }

  /// Replace the composition's audio track with one encoded audio file; returns that file's asset.
  private static func replaceAudio(of composition: AVMutableComposition, track: AVMutableCompositionTrack, with url: URL) async throws -> AVAsset {
    let asset = AVURLAsset(url: url)
    guard let encoded = try await asset.loadTracks(withMediaType: .audio).first else {
      throw MergeError.failed("The encoded audio has no track.")
    }
    let range = try await encoded.load(.timeRange)
    composition.removeTrack(track)
    guard let audioOut = composition.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid) else {
      throw MergeError.failed("Couldn't create the audio track.")
    }
    let duration = CMTimeMinimum(range.duration, composition.duration)
    try audioOut.insertTimeRange(CMTimeRange(start: range.start, duration: duration), of: encoded, at: .zero)
    return asset
  }

  /// Passthrough export with faststart (moov before mdat). Cancelling the calling task cancels it.
  private static func export(_ composition: AVComposition, to output: URL, progress: @escaping (Double) -> Void) async throws {
    guard let session = AVAssetExportSession(asset: composition, presetName: AVAssetExportPresetPassthrough) else {
      throw MergeError.failed("Couldn't create the export session.")
    }
    session.shouldOptimizeForNetworkUse = true

    let watcher = Task {
      if #available(iOS 18, *) {
        for await state in session.states(updateInterval: 0.1) {
          if case .exporting(let p) = state { progress(p.fractionCompleted) }
        }
      } else {
        while !Task.isCancelled {
          progress(Double(session.progress))
          try? await Task.sleep(nanoseconds: 100_000_000)
        }
      }
    }
    defer { watcher.cancel() }
    try await session.export(to: output, as: .mp4)
  }

  /// Reject silent assembly bugs: the output must be as long as the clips it was built from.
  private static func verify(_ output: URL, expectedMs: Double) async throws -> MergeResult {
    let media = try await Probe.load(output)
    let gotMs = media.result.durationMs
    let tolerance = max(500, expectedMs * 0.01)
    guard abs(gotMs - expectedMs) <= tolerance else {
      throw MergeError.failed("Joined video is \(Int(gotMs)) ms, expected \(Int(expectedMs)) ms.")
    }
    return MergeResult(
      uri: output.absoluteString,
      durationMs: gotMs,
      encoded: false,
      bitrate: media.result.video?.bitrate ?? -1)
  }
}
