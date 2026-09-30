import AVFoundation

/// Merge entry point: read every clip, pick the path, run it.
enum Merge {
  static func run(_ clips: [MergeClip], _ options: MergeOptions, progress: MergeProgress) async throws -> MergeResult {
    guard !clips.isEmpty else { throw MergeError.invalid("No clips to merge.") }
    removeStaleOutputs()
    progress(0)

    // Read all clips concurrently, keeping their order.
    let media: [Probe.Media] = try await withThrowingTaskGroup(of: (Int, Probe.Media).self) { group in
      for (i, clip) in clips.enumerated() {
        group.addTask {
          do {
            return (i, try await Probe.load(fileURL(clip.uri)))
          } catch {
            throw MergeError.clip(i, clip.uri, error)
          }
        }
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
      // Our renders don't reorder frames (no B-frames). A clip that does can't be copied next to
      // them: FFmpeg-based players mis-decode the mix. Camera recordings don't; such a draft
      // (e.g. an H.264 import from another encoder) is encoded instead.
      if try await reordersFrames(media, except: render) {
        return try await encode(clips, media, options: options, progress: progress)
      }
      do {
        return try await selective(clips, media, render: render, options: options, progress: progress)
      } catch where !(error is CancellationError) && !Task.isCancelled {
        // Safety net: anything the selective path can't do (a render that didn't come out in the
        // draft's format, a join that failed its checks) gets a full encode instead.
        return try await encode(clips, media, options: options, progress: progress)
      }
    case .encode:
      return try await encode(clips, media, options: options, progress: progress)
    }
  }

  /// Render only the edited clips, into the draft's own format, then join everything. Rendered
  /// clips carry our encoder's H.264 settings next to the camera's, which Apple's passthrough join
  /// supports.
  private static func selective(
    _ clips: [MergeClip], _ media: [Probe.Media], render: [Int], options: MergeOptions, progress: MergeProgress
  ) async throws -> MergeResult {
    let reference = media[0].videoTrack!
    let (size, transform) = try await reference.load(.naturalSize, .preferredTransform)
    let target = RenderTarget(
      size: size, transform: transform, canvas: CGSize(width: options.width, height: options.height),
      fps: options.fps, bitrate: options.bitrate, audio: options.audio)
    return try await renderAndJoin(clips, media, render: render, target: target, options: options, progress: progress)
  }

  /// Whether any clip that would be copied (not in `render`) has out-of-order frames.
  private static func reordersFrames(_ media: [Probe.Media], except render: [Int]) async throws -> Bool {
    for (i, m) in media.enumerated() where !render.contains(i) {
      if try await m.videoTrack?.load(.requiresFrameReordering) == true { return true }
    }
    return false
  }

  /// Every clip rendered onto the upright canvas, then joined: for clips that don't share a format
  /// that fits, and as the selective path's fallback. Each clip is rendered on its own: rendering
  /// a whole timeline in one composition stalls AVFoundation's audio mix reader for good when a
  /// sped-up clip meets a clip in another audio format, and plays that clip's audio at 1×.
  private static func encode(
    _ clips: [MergeClip], _ media: [Probe.Media], options: MergeOptions, progress: MergeProgress
  ) async throws -> MergeResult {
    let canvas = CGSize(width: options.width, height: options.height)
    let target = RenderTarget(
      size: canvas, transform: .identity, canvas: canvas,
      fps: options.fps, bitrate: options.bitrate, audio: options.audio)
    return try await renderAndJoin(clips, media, render: Array(clips.indices), target: target, options: options, progress: progress)
  }

  /// Render the clips in `render` into `target`'s format, then join them with the others. The
  /// audio is re-encoded once over the whole timeline, so the output has a single audio setup.
  private static func renderAndJoin(
    _ clips: [MergeClip], _ media: [Probe.Media], render: [Int], target: RenderTarget,
    options: MergeOptions, progress: MergeProgress
  ) async throws -> MergeResult {
    // Rendering is most of the work: 0–0.8 of the bar, weighted by each clip's length.
    let weights = render.map { i in max(1, clips[i].endMs > clips[i].startMs ? clips[i].endMs - clips[i].startMs : media[i].result.durationMs) }
    let renderProgress = WeightedProgress(weights: weights) { progress($0 * 0.8) }

    var rendered: [Int: URL] = [:]
    defer { for url in rendered.values { try? FileManager.default.removeItem(at: url) } }
    try await withThrowingTaskGroup(of: (Int, URL).self) { group in
      // Two at a time: enough to overlap the work without overloading the hardware encoder.
      var pending = render.enumerated().makeIterator()
      func next() throws {
        guard let (slot, i) = pending.next() else { return }
        let output = try outputURL()
        group.addTask {
          do {
            try await Render.timeline([(clips[i], media[i])], target: target, to: output, faststart: false) {
              renderProgress.update(slot, $0)
            }
          } catch {
            try? FileManager.default.removeItem(at: output)
            if error is CancellationError { throw error }
            throw MergeError.clip(i, clips[i].uri, error)
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

    // Every rendered clip must come out in the target's exact layout, or the join can't use it.
    let rotation = Probe.rotation(of: target.transform)
    var segments: [Join.Segment] = []
    for (i, clip) in clips.enumerated() {
      guard let url = rendered[i] else {
        segments.append(Join.Segment(media: media[i], startMs: clip.startMs, endMs: clip.endMs, muted: clip.muted))
        continue
      }
      let out = try await Probe.load(url)
      guard let v = out.result.video, v.codec == "h264", v.width == target.size.width, v.height == target.size.height,
            v.rotation == rotation else {
        throw MergeError.failed("Clip \(i + 1) rendered in the wrong format.")
      }
      segments.append(Join.Segment(media: out))
    }
    return try await Join.run(segments, options: options, reencodeAudio: true, encoded: true) { progress(0.8 + $0 * 0.2) }
  }

  /// Where merges write: a folder of their own in the caches directory.
  private static var outputDirectory: URL {
    FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
      .appendingPathComponent("pulse-editor", isDirectory: true)
  }

  /// A fresh output file in the caches directory.
  static func outputURL() throws -> URL {
    try FileManager.default.createDirectory(at: outputDirectory, withIntermediateDirectories: true)
    return outputDirectory.appendingPathComponent("merge-\(UUID().uuidString).mp4")
  }

  /// Deletes merge files a killed app left behind (a finished merge is moved out by its caller).
  private static func removeStaleOutputs() {
    let cutoff = Date().addingTimeInterval(-24 * 60 * 60)
    let files = (try? FileManager.default.contentsOfDirectory(
      at: outputDirectory, includingPropertiesForKeys: [.contentModificationDateKey])) ?? []
    for file in files where file.lastPathComponent.hasPrefix("merge-") {
      let modified = (try? file.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate
      if let modified, modified < cutoff { try? FileManager.default.removeItem(at: file) }
    }
  }
}

/// Progress that only ever moves forward, so a later phase can never make the bar jump back, and
/// is reported in steps of at least 0.5% (a frame-by-frame render would otherwise send JS
/// hundreds of updates a second).
final class MergeProgress: @unchecked Sendable {
  private static let step = 0.005
  private let report: (Double) -> Void
  private let lock = NSLock()
  private var last = -1.0

  init(_ report: @escaping (Double) -> Void) { self.report = report }

  func callAsFunction(_ value: Double) {
    let clamped = min(max(value, 0), 1)
    let send: Bool = lock.withLock {
      guard clamped > last, clamped - last >= Self.step || clamped == 1 || last < 0 else { return false }
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
  case failed(String)

  var errorDescription: String? {
    switch self {
    case .cancelled: return "Merge cancelled"
    case .invalid(let why), .failed(let why): return why
    }
  }

  /// A clip's failure, naming the clip (its position and file name).
  static func clip(_ index: Int, _ uri: String, _ error: Error) -> MergeError {
    .failed("Clip \(index + 1) (\(fileURL(uri).lastPathComponent)): \(error.localizedDescription)")
  }
}
