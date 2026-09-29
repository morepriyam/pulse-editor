import Foundation
import NitroModules

/// One merge run. `start` runs it once in a Swift `Task`; `cancel` cancels that task, which
/// stops the export in flight and deletes its partial output.
final class MergeJob: HybridMergeJobSpec {
  private let clips: [MergeClip]
  private let options: MergeOptions
  private let lock = NSLock()
  private var task: Task<MergeResult, Error>?
  private var cancelled = false

  init(clips: [MergeClip], options: MergeOptions) {
    self.clips = clips
    self.options = options
    super.init()
  }

  func start(onProgress: @escaping (_ progress: Double) -> Void) throws -> Promise<MergeResult> {
    let (clips, options) = (self.clips, self.options)
    let task: Task<MergeResult, Error> = try lock.withLock {
      guard self.task == nil else { throw MergeError.invalid("This merge was already started.") }
      let task = Task(priority: .userInitiated) {
        try await Merge.run(clips, options, progress: MergeProgress(onProgress))
      }
      self.task = task
      if cancelled { task.cancel() }
      return task
    }
    return Promise.async {
      do {
        return try await task.value
      } catch {
        if task.isCancelled { throw MergeError.cancelled }
        throw error
      }
    }
  }

  func cancel() throws {
    lock.withLock {
      cancelled = true
      task?.cancel()
    }
  }
}
