import AVFoundation

/// One take's PCM writer. Input host timestamps, rather than callback arrival times, select
/// samples. Kept separate from AVAudioEngine so the exact capture/trim path can be tested with
/// synthetic, timestamped buffers on a Mac without reserving a phone or microphone.
final class VoiceoverSamples {
  let url: URL
  let start: Double
  var stop = Double.infinity
  private var file: AVAudioFile?
  private var first = Double.nan
  private var frames: AVAudioFramePosition = 0
  private var failure: Error?
  private var nextInputHost: Double?

  init(url: URL, file: AVAudioFile, start: Double) {
    self.url = url; self.file = file; self.start = start
  }

  func append(_ buffer: AVAudioPCMBuffer, host: Double) {
    guard failure == nil, let file else { return }
    let rate = buffer.format.sampleRate
    let count = Int(buffer.frameLength)
    // Both edges use the same sample grid: a buffer spanning the press preserves its opening,
    // and one arriving after release still contributes samples that preceded the pause.
    let lower = max(0, min(count, Int(ceil((start - host) * rate - 1e-6))))
    let upper = stop.isFinite
      ? max(0, min(count, Int(ceil((stop - host) * rate - 1e-6)))) : count
    guard upper > lower, let part = Self.slice(buffer, offset: lower, frames: upper - lower) else { return }
    let includedHost = host + Double(lower) / rate
    // Callback arrival may be delayed, but sample timestamps must remain continuous. A lost
    // or reordered buffer must fail the take instead of compressing later speech ahead of video.
    // Allow at most 1 ms (or two sample frames) for host/sample clock edge quantization.
    let tolerance = max(0.001, 2 / rate)
    if abs(includedHost - (nextInputHost ?? start)) > tolerance {
      failure = NSError(domain: "PulseVoiceover", code: 5,
        userInfo: [NSLocalizedDescriptionKey: "The microphone input was interrupted. Try recording this take again."])
      return
    }
    do {
      try file.write(from: part)
      if first.isNaN { first = includedHost }
      frames += AVAudioFramePosition(upper - lower)
      nextInputHost = includedHost + Double(upper - lower) / rate
    } catch { failure = error }
  }

  /// Closes the CAF before it becomes visible to its caller.
  func finish() throws -> (firstHostSeconds: Double, durationMs: Double) {
    let rate = file?.processingFormat.sampleRate ?? 0
    if #available(iOS 18, macOS 15, *) { file?.close() }
    file = nil
    if let failure { throw failure }
    guard frames > 0, first.isFinite, rate > 0 else {
      throw NSError(domain: "PulseVoiceover", code: 3, userInfo: [NSLocalizedDescriptionKey: "No microphone audio was captured."])
    }
    return (first, Double(frames) / rate * 1000)
  }

  /// Copy a sample-aligned slice of an interleaved or planar PCM buffer.
  static func slice(_ buffer: AVAudioPCMBuffer, offset: Int, frames: Int) -> AVAudioPCMBuffer? {
    guard frames > 0, let output = AVAudioPCMBuffer(pcmFormat: buffer.format, frameCapacity: AVAudioFrameCount(frames)) else { return nil }
    output.frameLength = AVAudioFrameCount(frames)
    let source = UnsafeMutableAudioBufferListPointer(UnsafeMutablePointer(mutating: buffer.audioBufferList))
    let destination = UnsafeMutableAudioBufferListPointer(output.mutableAudioBufferList)
    let stride = Int(buffer.format.streamDescription.pointee.mBytesPerFrame)
    for index in source.indices {
      guard let from = source[index].mData, let to = destination[index].mData else { return nil }
      memcpy(to, from.advanced(by: offset * stride), frames * stride)
    }
    return output
  }
}
