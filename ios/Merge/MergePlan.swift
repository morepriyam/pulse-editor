import Foundation

/// Which path a merge takes. The fast join copies samples untouched, so it needs every clip to
/// have no rendered edit and to already share one format that fits the output.
struct MergePlan {
  enum Path {
    case join
    case encode(reasons: [String])
  }

  /// A VBR encode overshoots its target: recordings aimed at 5 Mbps average 6–7 Mbps. Clips up to
  /// this multiple of the chosen bitrate still count as at it (1.6× of 5 Mbps is the 8 Mbps an
  /// import may carry and still pass through untouched in Pulse's import rules).
  static let bitrateTolerance = 1.6

  let path: Path

  init(clips: [MergeClip], media: [Probe.Media], options: MergeOptions) {
    var reasons: [String] = []
    for (i, clip) in clips.enumerated() where Self.needsRender(clip) {
      reasons.append("clip \(i + 1) has a rotate, flip, crop or speed edit")
    }
    reasons += Self.joinBlockers(media: media, options: options)
    path = reasons.isEmpty ? .join : .encode(reasons: reasons)
  }

  /// Edits that change pixels or timing, so the clip can't be copied.
  static func needsRender(_ clip: MergeClip) -> Bool {
    clip.rotation.truncatingRemainder(dividingBy: 360) != 0 || clip.flipped || clip.crop != nil
      || abs(clip.speed - 1) > 0.0001
  }

  /// Why these clips can't be joined without re-encoding (empty when they can).
  static func joinBlockers(media: [Probe.Media], options: MergeOptions) -> [String] {
    var reasons: [String] = []
    guard let first = media.first?.result.video else { return ["clip 1 has no video"] }
    let firstAudio = media.compactMap { $0.result.audio }.first

    for (i, m) in media.enumerated() {
      let n = i + 1
      guard let v = m.result.video, m.videoTrack != nil else {
        reasons.append("clip \(n) has no video")
        continue
      }
      if v.codec != "h264" { reasons.append("clip \(n) is \(v.codec), not h264") }
      if v.mirrored { reasons.append("clip \(n) is mirrored") }
      let swapped = Int(v.rotation) % 180 != 0
      let (w, h) = swapped ? (v.height, v.width) : (v.width, v.height)
      if w != options.width || h != options.height {
        reasons.append("clip \(n) displays \(Int(w))x\(Int(h)), not \(Int(options.width))x\(Int(options.height))")
      }
      if v.width != first.width || v.height != first.height || v.rotation != first.rotation {
        reasons.append("clip \(n) is coded differently from clip 1")
      }
      if v.fps > 0 && v.fps.rounded() != options.fps.rounded() {
        reasons.append("clip \(n) is \(v.fps.rounded()) fps, not \(options.fps.rounded())")
      }
      if v.bitrate > options.bitrate * bitrateTolerance {
        reasons.append("clip \(n) is \(Int(v.bitrate / 1000)) kbps, above the chosen \(Int(options.bitrate / 1000))")
      }
      if let a = m.result.audio {
        if a.codec != "aac" { reasons.append("clip \(n) audio is \(a.codec), not aac") }
        if let f = firstAudio, a.sampleRate != f.sampleRate || a.channels != f.channels {
          reasons.append("clip \(n) audio layout differs")
        }
      }
    }
    return reasons
  }
}
