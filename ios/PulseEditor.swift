import AVFoundation
import CoreMedia
import NitroModules

class PulseEditor: HybridPulseEditorSpec {
  public func probe(uri: String) throws -> Promise<ProbeResult> {
    return Promise.async(.userInitiated) {
      try await Probe.read(fileURL(uri))
    }
  }

  public func extractAudio(uri: String, sampleRate: Double) throws -> Promise<AudioPCM> {
    return Promise.async(.userInitiated) {
      let pcm = try await ExtractAudio.read(fileURL(uri), sampleRate: sampleRate)
      let data = pcm.data.map { data in
        ArrayBuffer.wrap(dataWithoutCopy: data, size: pcm.byteCount, onDelete: { free(data) })
      } ?? ArrayBuffer.allocate(size: 0)
      return AudioPCM(data: data, sampleRate: sampleRate, durationMs: pcm.durationMs)
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
