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
      return try await Join.run(clips, media, options: options, progress: progress)
    case .encode(let reasons):
      throw MergeError.unsupported("This merge needs encoding, which isn't built yet: \(reasons.joined(separator: "; "))")
    }
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
