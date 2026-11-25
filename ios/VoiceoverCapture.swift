import AVFoundation

/// The microphone stays running while the voiceover tool is open. A press selects samples from
/// this already warm input stream by host timestamp, avoiding a cold recorder's missing opening.
/// File writes and capture state live on one queue; neither JS nor the player clock supplies
/// guessed recording timestamps.
final class VoiceoverCapture {
  struct Finished {
    let uri: String
    let firstHostSeconds: Double
    let durationMs: Double
  }

  private let engine = AVAudioEngine()
  private let io = DispatchQueue(label: "com.mieweb.pulse.voiceover-input", qos: .userInitiated)
  private var preparedURL: URL?
  private var preparedFile: AVAudioFile?
  private var active: VoiceoverSamples?
  private var installed = false
  private var generation = 0
  private var latestInputEnd = -Double.infinity
  private var previousSession: (AVAudioSession.Category, AVAudioSession.Mode, AVAudioSession.CategoryOptions, Double, TimeInterval)?
  private(set) var prepared = false

  /// Called on main after microphone permission was granted. Keep output enabled for preview.
  func prepare() throws {
    if prepared, engine.isRunning { return }
    // A failed re-arm or stopped audio engine must remove its tap before installing another.
    if installed { cancel() }
    let session = AVAudioSession.sharedInstance()
    previousSession = (session.category, session.mode, session.categoryOptions, session.preferredSampleRate, session.preferredIOBufferDuration)
    try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .allowBluetoothHFP])
    try session.setPreferredSampleRate(48_000)
    try session.setPreferredIOBufferDuration(0.005)
    try session.setActive(true)
    let input = engine.inputNode
    let format = input.outputFormat(forBus: 0)
    guard format.sampleRate > 0, format.channelCount > 0 else {
      throw NSError(domain: "PulseVoiceover", code: 1, userInfo: [NSLocalizedDescriptionKey: "The microphone has no usable input."])
    }
    try io.sync { try prepareFile(format) }
    input.installTap(onBus: 0, bufferSize: 256, format: format) { [weak self] buffer, time in
      guard let self, time.isHostTimeValid, let copy = VoiceoverSamples.slice(buffer, offset: 0, frames: Int(buffer.frameLength)) else { return }
      let host = AVAudioTime.seconds(forHostTime: time.hostTime)
      self.io.async {
        self.active?.append(copy, host: host)
        self.latestInputEnd = host + Double(copy.frameLength) / copy.format.sampleRate
      }
    }
    installed = true
    engine.prepare()
    do {
      try engine.start()
      prepared = true
    } catch {
      cancel()
      throw error
    }
  }

  private func prepareFile(_ format: AVAudioFormat) throws {
    let url = try Merge.outputURL("voiceover", extension: "caf")
    let file = try AVAudioFile(forWriting: url, settings: format.settings, commonFormat: format.commonFormat, interleaved: format.isInterleaved)
    preparedURL = url
    preparedFile = file
  }

  /// Starting the engine does not establish that input is flowing. Enable push-to-talk only
  /// after a real timestamped input buffer has arrived, so the first press cannot race startup.
  func waitForInput(completion: @escaping (Result<Void, Error>) -> Void) {
    let generation = io.sync { self.generation }
    io.async {
      self.pollForInput(generation: generation, deadline: .now() + 2, completion: completion)
    }
  }

  private func pollForInput(generation: Int, deadline: DispatchTime,
    completion: @escaping (Result<Void, Error>) -> Void) {
    guard self.generation == generation else {
      DispatchQueue.main.async { completion(.failure(Self.noCapture())) }
      return
    }
    if latestInputEnd.isFinite {
      DispatchQueue.main.async { completion(.success(())) }
    } else if DispatchTime.now() >= deadline {
      DispatchQueue.main.async {
        // A newer preparation owns its own engine/tap. Never tear that session down for an
        // earlier waiter's timeout; cancellation still resolves this waiter with a failure.
        if self.io.sync(execute: { self.generation == generation }) { self.cancel() }
        completion(.failure(NSError(domain: "PulseVoiceover", code: 4,
          userInfo: [NSLocalizedDescriptionKey: "The microphone did not become ready. Reopen Voiceover and try again."])))
      }
    } else {
      io.asyncAfter(deadline: .now() + 0.005) {
        self.pollForInput(generation: generation, deadline: deadline, completion: completion)
      }
    }
  }

  func start(hostSeconds: Double) throws {
    try io.sync {
      guard prepared, engine.isRunning, latestInputEnd.isFinite, active == nil, let file = preparedFile, let url = preparedURL else {
        throw NSError(domain: "PulseVoiceover", code: 2, userInfo: [NSLocalizedDescriptionKey: "The microphone is not ready. Reopen Voiceover and try again."])
      }
      generation += 1
      active = VoiceoverSamples(url: url, file: file, start: hostSeconds)
      preparedURL = nil
      preparedFile = nil
    }
  }

  /// Finish after the input stream has delivered the buffer covering the cutoff. A tap's
  /// requested size is only a hint, so a fixed delay could lose the final syllable.
  func stop(hostSeconds: Double, completion: @escaping (Result<Finished, Error>) -> Void) {
    io.async {
      guard let capture = self.active else {
        DispatchQueue.main.async { completion(.failure(Self.noCapture())) }
        return
      }
      capture.stop = hostSeconds
      let generation = self.generation
      self.finishWhenDelivered(capture, generation: generation, deadline: .now() + 1, completion: completion)
    }
  }

  private func finishWhenDelivered(_ capture: VoiceoverSamples, generation: Int,
    deadline: DispatchTime, completion: @escaping (Result<Finished, Error>) -> Void) {
    io.asyncAfter(deadline: .now() + 0.005) {
        guard self.generation == generation, self.active === capture else {
          DispatchQueue.main.async { completion(.failure(Self.noCapture())) }
          return
        }
        if self.latestInputEnd < capture.stop, DispatchTime.now() < deadline {
          self.finishWhenDelivered(capture, generation: generation, deadline: deadline, completion: completion)
          return
        }
        self.active = nil
        let result: Result<Finished, Error>
        do {
          guard self.latestInputEnd >= capture.stop else { throw Self.noCapture() }
          let finished = try capture.finish()
          result = .success(Finished(uri: capture.url.absoluteString,
            firstHostSeconds: finished.firstHostSeconds, durationMs: finished.durationMs))
        } catch {
          try? FileManager.default.removeItem(at: capture.url)
          result = .failure(error)
        }
        var rearmed = true
        do { try self.prepareFile(self.engine.inputNode.outputFormat(forBus: 0)) }
        catch { rearmed = false }
        DispatchQueue.main.async {
          if !rearmed { self.cancel() }
          completion(result)
        }
    }
  }

  /// Called on main on tool exit, interruption, background and view destruction.
  func cancel() {
    engine.stop()
    if installed { engine.inputNode.removeTap(onBus: 0); installed = false }
    io.sync {
      generation += 1
      if let url = active?.url { try? FileManager.default.removeItem(at: url) }
      if let url = preparedURL { try? FileManager.default.removeItem(at: url) }
      active = nil
      preparedFile = nil
      preparedURL = nil
      prepared = false
      latestInputEnd = -Double.infinity
    }
    if let previousSession {
      let session = AVAudioSession.sharedInstance()
      try? session.setCategory(previousSession.0, mode: previousSession.1, options: previousSession.2)
      if previousSession.3 > 0 { try? session.setPreferredSampleRate(previousSession.3) }
      if previousSession.4 > 0 { try? session.setPreferredIOBufferDuration(previousSession.4) }
      self.previousSession = nil
    }
  }

  private static func noCapture() -> Error {
    NSError(domain: "PulseVoiceover", code: 3, userInfo: [NSLocalizedDescriptionKey: "No microphone audio was captured."])
  }

}
