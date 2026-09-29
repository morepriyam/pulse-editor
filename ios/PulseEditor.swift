import AVFoundation
import CoreMedia
import NitroModules

class PulseEditor: HybridPulseEditorSpec {
  public func probe(uri: String) throws -> Promise<ProbeResult> {
    return Promise.async(.userInitiated) {
      try await Probe.read(fileURL(uri))
    }
  }

  public func createMerge(clips: [MergeClip], options: MergeOptions) throws -> (any HybridMergeJobSpec) {
    return MergeJob(clips: clips, options: options)
  }
}

/// `file://` URI or bare path → file URL.
func fileURL(_ uri: String) -> URL {
  if let url = URL(string: uri), url.scheme != nil { return url }
  return URL(fileURLWithPath: uri)
}
