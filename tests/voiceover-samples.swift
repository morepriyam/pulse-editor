import AVFoundation

@main
struct VoiceoverSamplesCheck {
  static func main() throws {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent("pulse-voiceover-samples-\(UUID().uuidString)")
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: directory) }
    var checked = 0
    for rate in [44_100.0, 48_000.0] {
      let format = AVAudioFormat(standardFormatWithSampleRate: rate, channels: 1)!
      let anchor = 12_345.0
      // A one-minute capture, five independent short takes, and a fresh seek/retake. None
      // starts on an input callback boundary. The input buffers arrive in 256-frame chunks.
      let takes: [(Double, Double)] = [(0.013, 60), (0.017, 0.31), (0.327, 0.23),
        (0.557, 0.49), (1.047, 0.37), (1.417, 0.41), (23.853, 0.137)]
      for (index, take) in takes.enumerated() {
        let start = anchor + take.0
        let stop = start + take.1
        let url = directory.appendingPathComponent("\(Int(rate))-\(index).caf")
        let writer = VoiceoverSamples(url: url, file: try AVAudioFile(forWriting: url, settings: format.settings), start: start)
        writer.stop = stop
        let firstFrame = max(0, Int(floor(take.0 * rate / 256)) * 256 - 256)
        let lastFrame = Int(ceil((take.0 + take.1) * rate / 256)) * 256 + 256
        for frame in stride(from: firstFrame, to: lastFrame, by: 256) {
          let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 256)!
          buffer.frameLength = 256
          for sample in 0..<256 {
            buffer.floatChannelData![0][sample] = Float((frame + sample) % 127) / 127
          }
          writer.append(buffer, host: anchor + Double(frame) / rate)
        }
        let finished = try writer.finish()
        let expectedFirst = Int(ceil(take.0 * rate - 1e-6))
        let expectedLast = Int(ceil((take.0 + take.1) * rate - 1e-6))
        let frames = expectedLast - expectedFirst
        let read = try AVAudioFile(forReading: url)
        precondition(read.length == frames, "Capture gained/lost samples: \(read.length), expected \(frames)")
        precondition(abs(finished.firstHostSeconds - (anchor + Double(expectedFirst) / rate)) < 1e-8)
        precondition(abs(finished.durationMs - Double(frames) / rate * 1000) < 1e-6)
        precondition(abs((finished.firstHostSeconds - start) * 1000) <= 1000 / rate + 1e-6)
        var decoded = 0
        while decoded < frames {
          let count = min(4096, frames - decoded)
          let data = AVAudioPCMBuffer(pcmFormat: read.processingFormat, frameCapacity: AVAudioFrameCount(count))!
          try read.read(into: data, frameCount: AVAudioFrameCount(count))
          precondition(data.frameLength > 0, "Decoded only \(decoded) of \(frames) frames")
          for sample in 0..<Int(data.frameLength) {
            let expected = Float((expectedFirst + decoded + sample) % 127) / 127
            precondition(abs(data.floatChannelData![0][sample] - expected) < 1e-6, "Capture skipped, repeated or reordered a sample")
          }
          decoded += Int(data.frameLength)
        }
        checked += 1
      }
      let emptyURL = directory.appendingPathComponent("empty-\(Int(rate)).caf")
      let empty = VoiceoverSamples(url: emptyURL, file: try AVAudioFile(forWriting: emptyURL, settings: format.settings), start: anchor)
      do { _ = try empty.finish(); fatalError("Empty capture must fail") } catch {}
      // A missing or repeated callback must not silently concatenate later speech at an
      // earlier picture position. Exercise the actual writer and its public finish failure.
      for (name, secondFrame) in [("missing", 512), ("reordered", 0)] {
        let url = directory.appendingPathComponent("\(name)-\(Int(rate)).caf")
        let writer = VoiceoverSamples(url: url, file: try AVAudioFile(forWriting: url, settings: format.settings), start: anchor)
        let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 256)!
        buffer.frameLength = 256
        writer.append(buffer, host: anchor)
        writer.append(buffer, host: anchor + Double(secondFrame) / rate)
        do { _ = try writer.finish(); fatalError("Discontinuous input must fail") }
        catch {
          let error = error as NSError
          precondition(error.domain == "PulseVoiceover" && error.code == 5)
        }
      }
    }
    print("PASS: \(checked) timestamped CAF captures, including 60 seconds and five successive takes at 44.1/48 kHz; every decoded sample preserved, <1-sample boundary error, no accumulated drift; empty, missing-buffer and reordered-buffer captures rejected.")
  }
}
