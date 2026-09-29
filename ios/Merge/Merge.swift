import AVFoundation

/// Merge entry point: read every clip, pick the path, run it.
enum Merge {
  static func run(_ clips: [MergeClip], _ options: MergeOptions, progress: MergeProgress) async throws -> MergeResult {
    guard !clips.isEmpty else { throw MergeError.invalid("No clips to merge.") }
    progress(0)

    // Read all clips concurrently, keeping their order.
    let media: [Probe.Media] = try await withThrowingTaskGroup(of: (Int, Probe.Media).self) { group in
      for (i, clip) in clips.enumerated() {
        group.addTask { (i, try await Probe.load(fileURL(clip.uri))) }
      }
      var loaded = [Probe.Media?](repeating: nil, count: clips.count)
      for try await (i, m) in group { loaded[i] = m }
      return loaded.map { $0! }
    }
    try Task.checkCancellation()

    let plan = MergePlan(clips: clips, media: media, options: options)
    switch plan.path {
    case .join:
      let segments = zip(clips, media).map { clip, m in
        Join.Segment(media: m, startMs: clip.startMs, endMs: clip.endMs, muted: clip.muted)
      }
      return try await Join.run(segments, options: options, reencodeAudio: false, encoded: false) { progress($0) }
    case .selective(let render):
      return try await selective(clips, media, render: Set(render), options: options, progress: progress)
    case .encode(let reasons):
      throw MergeError.unsupported("This merge needs a full encode, which isn't built yet: \(reasons.joined(separator: "; "))")
    }
  }

  /// Render only the edited clips into the draft's own format, then join everything. Rendered
  /// clips carry our encoder's H.264 settings next to the camera's, which Apple's passthrough join
  /// supports; the audio is re-encoded once over the whole timeline so it has a single setup.
  private static func selective(
    _ clips: [MergeClip], _ media: [Probe.Media], render: Set<Int>, options: MergeOptions, progress: MergeProgress
  ) async throws -> MergeResult {
    let reference = media[0].videoTrack!
    let (size, transform) = try await reference.load(.naturalSize, .preferredTransform)
    let target = RenderTarget(
      size: size, transform: transform, canvas: CGSize(width: options.width, height: options.height),
      fps: options.fps, bitrate: options.bitrate, audio: options.audio)

    // Rendering is most of the work: 0–0.8 of the bar, weighted by each clip's length.
    let weights = render.sorted().map { i in max(1, clips[i].endMs > clips[i].startMs ? clips[i].endMs - clips[i].startMs : media[i].result.durationMs) }
    let renderProgress = WeightedProgress(weights: weights) { progress($0 * 0.8) }

    var rendered: [Int: URL] = [:]
    defer { for url in rendered.values { try? FileManager.default.removeItem(at: url) } }
    try await withThrowingTaskGroup(of: (Int, URL).self) { group in
      // Two at a time: enough to overlap the work without overloading the hardware encoder.
      var pending = render.sorted().enumerated().makeIterator()
      func next() throws {
        guard let (slot, i) = pending.next() else { return }
        let output = try outputURL()
        group.addTask {
          do {
            try await Render.clip(clips[i], media[i], target: target, to: output) { renderProgress.update(slot, $0) }
          } catch {
            try? FileManager.default.removeItem(at: output)
            throw error
          }
          return (i, output)
        }
      }
      try next()
      try next()
      for try await (i, output) in group {
        rendered[i] = output
        try next()
      }
    }

    // Every rendered clip must come out in the draft's exact layout, or the join can't use it.
    var segments: [Join.Segment] = []
    for (i, clip) in clips.enumerated() {
      guard let url = rendered[i] else {
        segments.append(Join.Segment(media: media[i], startMs: clip.startMs, endMs: clip.endMs, muted: clip.muted))
        continue
      }
      let out = try await Probe.load(url)
      guard let v = out.result.video, let ref = media[0].result.video,
            v.codec == "h264", v.width == ref.width, v.height == ref.height, v.rotation == ref.rotation else {
        throw MergeError.failed("Clip \(i + 1) rendered in the wrong format.")
      }
      segments.append(Join.Segment(media: out))
    }
    return try await Join.run(segments, options: options, reencodeAudio: true, encoded: true) { progress(0.8 + $0 * 0.2) }
  }

  /// A fresh output file in the caches directory.
  static func outputURL() throws -> URL {
    let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
      .appendingPathComponent("pulse-editor", isDirectory: true)
    try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    return dir.appendingPathComponent("merge-\(UUID().uuidString).mp4")
  }
}

/// Progress that only ever moves forward, so a later phase can never make the bar jump back.
final class MergeProgress: @unchecked Sendable {
  private let report: (Double) -> Void
  private let lock = NSLock()
  private var last = -1.0

  init(_ report: @escaping (Double) -> Void) { self.report = report }

  func callAsFunction(_ value: Double) {
    let clamped = min(max(value, 0), 1)
    let send: Bool = lock.withLock {
      guard clamped > last else { return false }
      last = clamped
      return true
    }
    if send { report(clamped) }
  }
}

/// Combines per-item progress (0–1 each) into one fraction, weighted by each item's size.
final class WeightedProgress: @unchecked Sendable {
  private let weights: [Double]
  private let total: Double
  private var done: [Double]
  private let lock = NSLock()
  private let report: (Double) -> Void

  init(weights: [Double], report: @escaping (Double) -> Void) {
    self.weights = weights
    self.total = max(weights.reduce(0, +), 1)
    self.done = Array(repeating: 0, count: weights.count)
    self.report = report
  }

  func update(_ index: Int, _ fraction: Double) {
    let value: Double = lock.withLock {
      done[index] = min(max(fraction, 0), 1)
      return zip(done, weights).reduce(0) { $0 + $1.0 * $1.1 } / total
    }
    report(value)
  }
}

enum MergeError: LocalizedError {
  case cancelled
  case invalid(String)
  case unsupported(String)
  case failed(String)

  var errorDescription: String? {
    switch self {
    case .cancelled: return "Merge cancelled"
    case .invalid(let why), .unsupported(let why), .failed(let why): return why
    }
  }
}
