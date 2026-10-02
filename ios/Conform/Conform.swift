import AVFoundation

/// Conform one file into the recorder's format (see `ConformOptions`), on merge's own building
/// blocks:
/// - copyVideo: the video samples are copied and only the audio is re-encoded (`Join` with one
///   segment and its audio encoded).
/// - otherwise the whole clip is rendered (`Render`): decoded in hardware, tone-mapped to SDR
///   BT.709 (HLG, PQ, Dolby Vision), mirrored sources baked upright, letterboxed onto the canvas,
///   constant frame rate, coded in the orientation the rotation tag turns upright.
/// The sound is the track the platform plays (never Apple's spatial-audio APAC track). A file whose
/// sound can't be decoded here is rejected rather than conformed silent, and so is a source that
/// decodes short (a decoder that gives up early without an error).
enum Conform {
  static func run(_ uri: String, _ options: ConformOptions, progress: MergeProgress) async throws -> ConformResult {
    Merge.removeStaleOutputs()
    progress(0)
    let media = try await Probe.load(fileURL(uri))
    guard media.result.video != nil, media.videoTrack != nil else {
      throw MergeError.invalid("The file has no video.")
    }
    if media.audioTrack == nil, try await !media.asset.loadTracks(withMediaType: .audio).isEmpty {
      throw MergeError.failed("This video's sound can't be read on this device.")
    }
    try Task.checkCancellation()

    let mergeOptions = MergeOptions(
      width: options.width, height: options.height, fps: options.fps, bitrate: options.bitrate, audio: options.audio)
    let output = try Merge.outputURL("conform")
    do {
      if options.copyVideo {
        let joined = try await Join.run(
          [Join.Segment(media: media)], options: mergeOptions, encoded: false, encodeAudio: true, output: output
        ) { progress($0) }
        try await verify(output, source: media, options: options)
        return ConformResult(uri: joined.uri, durationMs: joined.durationMs, encoded: false, bitrate: joined.bitrate)
      }

      let degrees = (Int(options.rotation.rounded()) % 360 + 360) % 360
      guard degrees % 90 == 0 else { throw MergeError.invalid("The rotation must be 0, 90, 180 or 270.") }
      let canvas = CGSize(width: options.width, height: options.height)
      let coded = degrees % 180 == 0 ? canvas : CGSize(width: canvas.height, height: canvas.width)
      let target = RenderTarget(
        size: coded, transform: tag(degrees, coded: coded), canvas: canvas,
        fps: options.fps, bitrate: options.bitrate, audio: options.audio)
      let whole = MergeClip(
        uri: uri, startMs: 0, endMs: 0, speed: 1, muted: false, rotation: 0, flipped: false, crop: nil)
      let rendered = try await Render.timeline([(whole, media)], target: target, to: output, faststart: true) {
        progress($0 * 0.98)
      }

      // The render pads every track to the clip's length, which would hide a decoder that stopped
      // early behind a held frame or silence.
      let length = rendered.duration.seconds
      let videoEnd = rendered.lastVideoFrame.seconds
      if length > 0.3, !(videoEnd >= length * 0.9 - 0.1) {
        throw MergeError.failed(String(format: "Only %.1f s of the %.1f s video could be decoded.", max(videoEnd, 0), length))
      }
      if let audioEnd = rendered.lastAudio, !audioEnd.isNumeric {
        throw MergeError.failed("This video's sound can't be read on this device.")
      }
      let out = try await verify(output, source: media, options: options)
      progress(1)
      return ConformResult(
        uri: output.absoluteString, durationMs: out.durationMs, encoded: true, bitrate: out.video?.bitrate ?? -1)
    } catch {
      try? FileManager.default.removeItem(at: output)
      throw error
    }
  }

  /// The orientation tag for a clockwise rotation, as the iPhone camera writes it: the quarter turn
  /// plus the translation that puts the turned frame back at the origin.
  static func tag(_ degrees: Int, coded: CGSize) -> CGAffineTransform {
    let turn: CGAffineTransform
    switch degrees {
    case 90: turn = CGAffineTransform(a: 0, b: 1, c: -1, d: 0, tx: 0, ty: 0)
    case 180: turn = CGAffineTransform(a: -1, b: 0, c: 0, d: -1, tx: 0, ty: 0)
    case 270: turn = CGAffineTransform(a: 0, b: -1, c: 1, d: 0, tx: 0, ty: 0)
    default: return .identity
    }
    return MergeGeometry.anchored(turn, size: coded)
  }

  /// The output must be what was asked for: H.264 8-bit SDR in the requested layout, sound kept,
  /// as long as the source's picture.
  @discardableResult
  private static func verify(_ output: URL, source: Probe.Media, options: ConformOptions) async throws -> ProbeResult {
    let out = try await Probe.load(output).result
    guard let v = out.video else { throw MergeError.failed("The conformed video has no picture.") }
    if !options.copyVideo {
      let degrees = Double((Int(options.rotation.rounded()) % 360 + 360) % 360)
      let swapped = Int(degrees) % 180 != 0
      let (w, h) = swapped ? (v.height, v.width) : (v.width, v.height)
      guard v.codec == "h264", v.transfer == .sdr, v.bitDepth <= 8, !v.mirrored, v.rotation == degrees,
            w == options.width, h == options.height else {
        throw MergeError.failed(
          "The conformed video came out \(v.codec) \(Int(v.width))x\(Int(v.height)) rotated \(Int(v.rotation)).")
      }
    }
    if source.audioTrack != nil {
      guard let a = out.audio, a.codec == "aac", a.sampleRate == options.audio.sampleRate,
            a.channels == Double(EncodeSettings.audioChannels(options.audio.channels)) else {
        throw MergeError.failed("The conformed video's sound didn't come out as asked.")
      }
    }
    let want = source.result.video?.durationMs ?? source.result.durationMs
    if want > 0, abs(out.durationMs - want) > max(500, want * 0.01) {
      throw MergeError.failed("The conformed video is \(Int(out.durationMs)) ms, expected \(Int(want)) ms.")
    }
    return out
  }
}
