import Foundation
import NitroModules

/// One conform run, like `MergeJob`: `start` runs it once in a Swift `Task`; `cancel` cancels
/// that task, which stops the work in flight and deletes its partial output. Image clips use it
/// too (`name` "Image clip"), so callers get the same progress, cancel and result.
final class ConformJob: HybridConformJobSpec {
  private let name: String
  private let work: @Sendable (MergeProgress) async throws -> ConformResult
  private let lock = NSLock()
  private var task: Task<ConformResult, Error>?
  private var cancelled = false

  init(name: String, work: @escaping @Sendable (MergeProgress) async throws -> ConformResult) {
    self.name = name
    self.work = work
    super.init()
  }

  convenience init(uri: String, options: ConformOptions) {
    self.init(name: "Conform") { try await Conform.run(uri, options, progress: $0) }
  }

  func start(onProgress: @escaping (_ progress: Double) -> Void) throws -> Promise<ConformResult> {
    let (name, work) = (self.name, self.work)
    let task: Task<ConformResult, Error> = try lock.withLock {
      guard self.task == nil else { throw MergeError.invalid("This \(name.lowercased()) was already started.") }
      let task = Task(priority: .userInitiated) {
        try await work(MergeProgress(onProgress))
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
          if task.isCancelled { throw MergeError.failed("\(name) cancelled") }
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
