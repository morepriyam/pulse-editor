import Foundation

/// A completed native capture is ours only until a stop promise hands it to JavaScript.
/// The host may still be copying that file when another voiceover workflow starts.
final class VoiceoverFileOwnership {
  private let url: URL
  private var delivered = false

  init(url: URL) { self.url = url }

  /// Call before resolving the promise: its recipient can immediately start another take.
  func transferToHost() { delivered = true }

  /// Replacement, view destruction and canceled completion clean up unclaimed files only.
  func discardIfUnclaimed() {
    guard !delivered else { return }
    try? FileManager.default.removeItem(at: url)
  }
}
