import Foundation

@main
struct VoiceoverFileOwnershipCheck {
  static func main() throws {
    let files = FileManager.default
    let directory = files.temporaryDirectory.appendingPathComponent("pulse-voiceover-ownership-\(UUID().uuidString)")
    try files.createDirectory(at: directory, withIntermediateDirectories: true)
    defer { try? files.removeItem(at: directory) }
    let bytes = Data("completed narration awaiting a durable copy".utf8)
    func capture(_ name: String) throws -> (URL, VoiceoverFileOwnership) {
      let url = directory.appendingPathComponent(name + ".caf")
      try bytes.write(to: url)
      return (url, VoiceoverFileOwnership(url: url))
    }

    // A is delivered, then interrupted/disposed while its host save has not opened the file.
    // A new workflow starts B on the same preview and clears A's cached result. That native
    // cleanup must leave A available for the original host's delayed copy.
    let (aURL, a) = try capture("delivered-A")
    a.transferToHost()
    a.discardIfUnclaimed() // cancellation/view destruction
    let (bURL, b) = try capture("new-B")
    a.discardIfUnclaimed() // clearing the preceding result on the next start
    a.transferToHost() // a repeated stop result does not reclaim host ownership
    let saved = directory.appendingPathComponent("durable-A.caf")
    try files.copyItem(at: aURL, to: saved)
    let savedBytes = try Data(contentsOf: saved)
    let capturedBytes = try Data(contentsOf: aURL)
    precondition(savedBytes == bytes)
    precondition(capturedBytes == bytes)
    b.discardIfUnclaimed()
    precondition(!files.fileExists(atPath: bURL.path))
    precondition(files.fileExists(atPath: aURL.path))

    // Results never delivered to a stop promise remain native-owned, including an auto-ended
    // result replaced by another take, a dropped preview, and a canceled generation's reply.
    for name in ["unclaimed-replacement", "unclaimed-drop", "canceled-completion"] {
      let (url, ownership) = try capture(name)
      ownership.discardIfUnclaimed()
      ownership.discardIfUnclaimed() // cleanup is idempotent
      precondition(!files.fileExists(atPath: url.path))
    }
    print("PASS: delivered capture survives cancellation, replacement and delayed copy; unclaimed replacement/drop/canceled completion files are reclaimed.")
  }
}
