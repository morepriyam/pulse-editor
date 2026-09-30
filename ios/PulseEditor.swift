import AVFoundation
import CoreMedia
import NitroModules

class PulseEditor: HybridPulseEditorSpec {
  public func probe(uri: String) throws -> Promise<ProbeResult> {
    return Promise.async(.userInitiated) {
      try await withJSError { try await Probe.read(fileURL(uri)) }
    }
  }

  public func extractAudio(uri: String, sampleRate: Double) throws -> Promise<AudioPCM> {
    return Promise.async(.userInitiated) {
      let pcm = try await withJSError { try await ExtractAudio.read(fileURL(uri), sampleRate: sampleRate) }
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

/// Runs `work`, turning any error into the message JS sees: Nitro describes other errors with
/// `String(describing:)` (`failed("…")`, `Error Domain=… Code=…`), a `RuntimeError` as its message.
func withJSError<T>(_ work: () async throws -> T) async throws -> T {
  do {
    return try await work()
  } catch let error as RuntimeError {
    throw error
  } catch {
    throw RuntimeError(error.localizedDescription)
  }
}

/// `file://` URI (percent-encoded or not) or bare path → file URL. A `file://` URI is read as a
/// path, so a `#` or `?` in a file name isn't taken for a fragment or query.
func fileURL(_ uri: String) -> URL {
  if uri.hasPrefix("file://") {
    let path = String(uri.dropFirst("file://".count))
    return URL(fileURLWithPath: path.removingPercentEncoding ?? path)
  }
  if let url = URL(string: uri), url.scheme != nil { return url }
  return URL(fileURLWithPath: uri)
}
