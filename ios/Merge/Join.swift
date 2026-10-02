import AVFoundation

/// Joins clips that share one format by copying their video samples (AVMutableComposition + a
/// passthrough export), with trims as edit lists on frame boundaries; video is never decoded or
/// encoded. Audio from more than one piece (or with a gap) is re-encoded once over the whole
/// timeline (see AudioEncode), gaps as real silence: copied AAC would need one edit-list entry per
/// clip to drop each clip's priming and end padding, which Apple players honour but FFmpeg-based
/// ones (Chrome, servers) don't — they play every clip's padding and fall ~10 ms further behind per
/// clip. A single piece has no seams, so its audio is copied.
enum Join {
  /// One piece of the timeline: a source file, cut to a window (the whole file when unset).
  struct Segment {
    let media: Probe.Media
    var startMs: Double = 0
    var endMs: Double = 0
    var muted = false
  }

  /// `encodeAudio` re-encodes the audio into `options.audio` even when it's one piece (conform).
  static func run(
    _ segments: [Segment], options: MergeOptions, encoded: Bool, encodeAudio: Bool = false,
    output: URL? = nil, progress: @escaping @Sendable (Double) -> Void
  ) async throws -> MergeResult {
    let composition = AVMutableComposition()
    guard let videoOut = composition.addMutableTrack(withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid) else {
      throw MergeError.failed("Couldn't create the video track.")
    }
    let hasAudio = segments.contains { !$0.muted && $0.media.audioTrack != nil }
    let audioOut = hasAudio
      ? composition.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid)
      : nil

    // Every segment is in the draft's format (MergePlan checked; renders are verified), so one
    // track transform shows them all upright.
    videoOut.preferredTransform = try await segments[0].media.videoTrack!.load(.preferredTransform)

    var cursor = CMTime.zero
    var audioPieces = 0
    var audioGap = false
    for segment in segments {
      let video = segment.media.videoTrack!
      let (timeRange, frame) = try await video.load(.timeRange, .minFrameDuration)
      let range = try trimRange(startMs: segment.startMs, endMs: segment.endMs, in: timeRange, frame: frame)
      try videoOut.insertTimeRange(range, of: video, at: cursor)

      // A muted segment (or one without sound, or sound shorter than its picture) leaves a gap,
      // which the audio encode fills with silence.
      if let audioOut {
        var covered = CMTime.zero
        if !segment.muted, let audio = segment.media.audioTrack {
          let overlap = range.intersection(try await audio.load(.timeRange))
          if overlap.duration > .zero {
            try audioOut.insertTimeRange(overlap, of: audio, at: cursor + (overlap.start - range.start))
            covered = overlap.duration
            audioPieces += 1
          }
        }
        if range.duration - covered > CMTime(value: 1, timescale: 10) { audioGap = true }
      }
      cursor = cursor + range.duration
    }
    try Task.checkCancellation()

    let output = try output ?? Merge.outputURL()
    var encodedAudio: URL?
    defer { if let encodedAudio { try? FileManager.default.removeItem(at: encodedAudio) } }
    do {
      var exportProgress = progress
      // The composition reads the encoded audio through this asset; a track doesn't keep its
      // asset alive, so hold it until the export is done.
      var encodedAsset: AVAsset?
      if let audioOut, encodeAudio || audioPieces > 1 || audioGap {
        let url = output.deletingPathExtension().appendingPathExtension("audio.mp4")
        encodedAudio = url
        try await AudioEncode.run(composition, audio: options.audio, to: url) { progress($0 * 0.3) }
        encodedAsset = try await replaceAudio(of: composition, track: audioOut, with: url)
        exportProgress = { progress(0.3 + $0 * 0.7) }
      }
      try Task.checkCancellation()
      try await export(composition, to: output, progress: exportProgress)
      withExtendedLifetime(encodedAsset) {}
      let result = try await verify(output, expectedMs: Probe.ms(cursor), encoded: encoded)
      progress(1)
      return result
    } catch {
      try? FileManager.default.removeItem(at: output)
      throw error
    }
  }

  /// The window inside a source video track (the whole track when unset). A window that starts at
  /// or past the track's end is an error, not the whole clip. With `frame`, both ends move to the
  /// nearest frame boundary: a copied cut inside a frame is shown by Apple players but dropped by
  /// FFmpeg-based ones, which then start the picture up to a frame after the sound.
  static func trimRange(startMs: Double, endMs: Double, in track: CMTimeRange, frame: CMTime? = nil) throws -> CMTimeRange {
    guard endMs > startMs else { return track }
    func at(_ ms: Double) -> CMTime {
      let t = CMTime(value: CMTimeValue(ms.rounded()), timescale: 1000)
      guard let frame, frame.isNumeric, frame > .zero else { return track.start + t }
      return track.start + CMTimeMultiply(frame, multiplier: Int32((t.seconds / frame.seconds).rounded()))
    }
    let start = at(startMs)
    let end = CMTimeMinimum(at(endMs), track.end)
    guard start < end else {
      throw MergeError.invalid("The trim starts at \(Int(startMs)) ms, past the end of the video (\(Probe.ms(track.duration)) ms).")
    }
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
  private static func export(_ composition: AVComposition, to output: URL, progress: @escaping @Sendable (Double) -> Void) async throws {
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

  /// Reject silent assembly bugs: the output must be as long as the segments it was built from.
  private static func verify(_ output: URL, expectedMs: Double, encoded: Bool) async throws -> MergeResult {
    let media = try await Probe.load(output)
    let gotMs = media.result.durationMs
    let tolerance = max(500, expectedMs * 0.01)
    guard abs(gotMs - expectedMs) <= tolerance else {
      throw MergeError.failed("Joined video is \(Int(gotMs)) ms, expected \(Int(expectedMs)) ms.")
    }
    return MergeResult(
      uri: output.absoluteString,
      durationMs: gotMs,
      encoded: encoded,
      bitrate: media.result.video?.bitrate ?? -1)
  }
}
