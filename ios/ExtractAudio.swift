import AVFoundation

/// A file's audio as 16-bit mono PCM at a chosen sample rate, decoded in one AVAssetReader pass:
/// the reader's converter decodes, downmixes and resamples (with proper filtering) together, and
/// the samples land straight in the buffer handed to JS. No intermediate file.
enum ExtractAudio {
  struct PCM {
    /// Owned by the caller: release with `free()`. Nil when there is no audio.
    let data: UnsafeMutableRawPointer?
    let byteCount: Int
    let durationMs: Double
  }

  static func read(_ url: URL, sampleRate: Double) async throws -> PCM {
    guard sampleRate >= 8000, sampleRate <= 192_000 else {
      throw failure("Sample rate \(sampleRate) is out of range.")
    }
    // Precise timing, so the buffer covers the track to its last sample.
    let asset = AVURLAsset(url: url, options: [AVURLAssetPreferPreciseDurationAndTimingKey: true])
    guard let track = try await asset.loadTracks(withMediaType: .audio).first else {
      return PCM(data: nil, byteCount: 0, durationMs: 0)
    }
    let timeRange = try await track.load(.timeRange)

    // Float, because the converter's downmix sums channels: stereo speech peaks ~1.4× the
    // source and 5.1 ~2.6×, which would clip as integers.
    let reader = try AVAssetReader(asset: asset)
    let output = AVAssetReaderTrackOutput(track: track, outputSettings: [
      AVFormatIDKey: kAudioFormatLinearPCM,
      AVSampleRateKey: sampleRate,
      AVNumberOfChannelsKey: 1,
      AVLinearPCMBitDepthKey: 32,
      AVLinearPCMIsFloatKey: true,
      AVLinearPCMIsBigEndianKey: false,
      AVLinearPCMIsNonInterleaved: false,
    ])
    output.alwaysCopiesSampleData = false
    reader.add(output)
    guard reader.startReading() else {
      throw reader.error ?? failure("Couldn't read the audio.")
    }

    // Sized from the track's duration up front; grown only if the estimate falls short.
    let floatSize = MemoryLayout<Float>.size
    var capacity = max(4096, Int((timeRange.duration.seconds + 1) * sampleRate)) * floatSize
    guard var data = malloc(capacity) else { throw failure("Out of memory.") }
    var bytes = 0
    do {
      while let sample = output.copyNextSampleBuffer() {
        try Task.checkCancellation()
        guard let block = CMSampleBufferGetDataBuffer(sample) else { continue }
        let length = CMBlockBufferGetDataLength(block)
        if bytes + length > capacity {
          capacity = max(capacity * 2, bytes + length)
          guard let grown = realloc(data, capacity) else { throw failure("Out of memory.") }
          data = grown
        }
        let status = CMBlockBufferCopyDataBytes(block, atOffset: 0, dataLength: length, destination: data + bytes)
        guard status == kCMBlockBufferNoErr else { throw failure("Couldn't copy the audio samples.") }
        bytes += length
      }
      if reader.status == .failed {
        throw reader.error ?? failure("Couldn't read the audio.")
      }
    } catch {
      reader.cancelReading()
      free(data)
      throw error
    }

    let count = bytes / floatSize
    guard count > 0 else {
      free(data)
      return PCM(data: nil, byteCount: 0, durationMs: 0)
    }
    // Float → Int16 in place (each write lands at or before its read), turned down as a whole
    // only when the downmix went past full scale, never up.
    var peak: Float = 0
    for i in 0..<count { peak = max(peak, abs(data.load(fromByteOffset: i * floatSize, as: Float.self))) }
    let gain = Float(Int16.max) / max(peak, 1)
    for i in 0..<count {
      let value = data.load(fromByteOffset: i * floatSize, as: Float.self)
      data.storeBytes(of: Int16((value * gain).rounded()), toByteOffset: i * 2, as: Int16.self)
    }
    let byteCount = count * MemoryLayout<Int16>.size
    let shrunk = realloc(data, byteCount) ?? data
    return PCM(data: shrunk, byteCount: byteCount, durationMs: (Double(count) / sampleRate * 1000).rounded())
  }

  private static func failure(_ message: String) -> NSError {
    NSError(domain: "PulseEditor", code: 1, userInfo: [NSLocalizedDescriptionKey: message])
  }
}
