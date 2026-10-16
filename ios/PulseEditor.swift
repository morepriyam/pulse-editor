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

  public func thumbnail(uri: String, options: ThumbnailOptions) throws -> Promise<Thumbnail> {
    return Promise.async(.userInitiated) {
      try await withJSError {
        Merge.removeStaleOutputs()
        return try await FrameGrab.run(uri, options)
      }
    }
  }

  public func createMerge(clips: [MergeClip], options: MergeOptions) throws -> (any HybridMergeJobSpec) {
    return MergeJob(clips: clips, options: options)
  }

  public func createConform(uri: String, options: ConformOptions) throws -> (any HybridConformJobSpec) {
    return ConformJob(uri: uri, options: options)
  }

  /// Bench only: the plain-player seek bench is Android's (see `SeekBench`).
  public func createSeekBench() throws -> (any HybridSeekBenchSpec) {
    return SeekBench()
  }

  /// See `FilmstripGrab`.
  public func filmstrip(uri: String, options: FilmstripOptions) throws -> Promise<Filmstrip> {
    return Promise.async(.utility) {
      try await withJSError { try await FilmstripGrab.run(uri, options) }
    }
  }

  /// Peaks and RMS per `peakMs`, worked out here from an 8 kHz decode (no samples cross to JS).
  public func waveform(uri: String, peakMs: Double) throws -> Promise<ArrayBuffer> {
    return Promise.async(.utility) {
      let pcm = try await withJSError { try await ExtractAudio.read(fileURL(uri), sampleRate: 8000) }
      guard let raw = pcm.data, pcm.byteCount >= 2 else {
        if let raw = pcm.data { free(raw) }
        return ArrayBuffer.allocate(size: 0)
      }
      defer { free(raw) }
      let count = pcm.byteCount / 2
      let samples = raw.assumingMemoryBound(to: Int16.self)
      let per = max(1, Int((8000 * peakMs / 1000).rounded()))
      let buckets = (count + per - 1) / per
      let out = UnsafeMutablePointer<UInt8>.allocate(capacity: buckets * 2)
      func level(_ amplitude: Double) -> UInt8 {
        guard amplitude > 0 else { return 0 }
        let db = 20 * log10(amplitude / 32768)
        return UInt8((min(1, max(0, (db + 48) / 48)) * 255).rounded())
      }
      for b in 0..<buckets {
        let start = b * per
        let end = min(count, start + per)
        var peak: Int32 = 0
        var sum: Double = 0
        for i in start..<end {
          let v = Int32(samples[i])
          let a = v < 0 ? -v : v
          if a > peak { peak = a }
          sum += Double(v * v)
        }
        out[b * 2] = level(Double(peak))
        out[b * 2 + 1] = level((sum / Double(max(1, end - start))).squareRoot())
      }
      return ArrayBuffer.wrap(dataWithoutCopy: out, size: buckets * 2, onDelete: { out.deallocate() })
    }
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
