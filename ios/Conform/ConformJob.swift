import Foundation
import NitroModules

/// One conform run, like `MergeJob`: `start` runs it once in a Swift `Task`; `cancel` cancels
/// that task, which stops the work in flight and deletes its partial output.
final class ConformJob: HybridConformJobSpec {
  private let uri: String
  private let options: ConformOptions
  private let lock = NSLock()
  private var task: Task<ConformResult, Error>?
  private var cancelled = false

  init(uri: String, options: ConformOptions) {
    self.uri = uri
    self.options = options
    super.init()
  }

  func start(onProgress: @escaping (_ progress: Double) -> Void) throws -> Promise<ConformResult> {
    let (uri, options) = (self.uri, self.options)
    let task: Task<ConformResult, Error> = try lock.withLock {
      guard self.task == nil else { throw MergeError.invalid("This conform was already started.") }
      let task = Task(priority: .userInitiated) {
        try await Conform.run(uri, options, progress: MergeProgress(onProgress))
      }
      self.task = task
      if cancelled { task.cancel() }
      return task
    }
    return Promise.async {
      try await withJSError {
        do {
          return try await task.value
        } catch {
          if task.isCancelled { throw MergeError.failed("Conform cancelled") }
          throw error
        }
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
