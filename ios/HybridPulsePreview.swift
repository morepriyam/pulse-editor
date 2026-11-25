import AVFoundation
import ImageIO
import NitroModules
import UIKit
import UniformTypeIdentifiers

/// A view whose layer is an AVPlayerLayer.
final class PlayerLayerView: UIView {
  override class var layerClass: AnyClass { AVPlayerLayer.self }
  var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
}

/// The preview (prototype): an AVPlayer playing the composition merge would export (built by
/// `Timeline.compose`, with the clips merge copies trimmed on their frames as the join does), at
/// the view's size. A new clip list rebuilds the item and swaps it in at
/// the current position. Seeks follow Apple's chase pattern (QA1820): one exact seek at a time,
/// always to the latest requested time. Everything touching the player runs on the main thread.
final class HybridPulsePreview: HybridPulsePreviewSpec {
  private let playerView = PlayerLayerView()
  var view: UIView { playerView }

  private let player = AVPlayer()
  private var media: [String: Probe.Media] = [:]
  private var build: Task<Void, Never>?
  private var timelineGeneration = 0
  private var rebuilding = false
  private var changed = false
  private var durationMs = 0.0
  private var clipStartsMs: [Double] = []
  private var ready = false
  private var hasAudio = false
  /// When audio last stopped in this process (0: nothing played yet). The output goes idle a few
  /// seconds after that, and waking it drops the first ~100-160 ms of a play (iPhone, 2026-10-13).
  private static var audioStoppedAt = 0.0
  private static var audioPlaying = false

  var clips: [MergeClip] = [] { didSet { changed = true } }
  var options = MergeOptions(width: 1080, height: 1920, fps: 30, bitrate: 5_000_000, audio: MergeAudio(sampleRate: 48_000, channels: 2), voiceover: nil, sourceMuted: nil, voiceoverMuted: nil) {
    didSet { changed = true }
  }
  var onTime: (_ timeMs: Double, _ playing: Bool) -> Void = { _, _ in }
  var onStatus: (_ status: PreviewStatus) -> Void = { _ in }

  // Measurements (stats()).
  private var firstFrameMs = -1.0
  private var lastSeekMs = -1.0
  private var lastSwapMs = -1.0
  private var framesShown = 0.0
  private var framesDropped = 0.0

  // Seeks (main thread only).
  private var chaseTime = CMTime.invalid
  /// A chase seek is in flight on the player (until its frame is shown). Cleared as it lands,
  /// before anything else runs, so no path can leave it set with seeks waiting.
  private var seeking = false
  private var scrubbing = false
  private var seekStart = 0.0
  private var waiters: [Promise<Double>] = []

  private var readyObservation: NSKeyValueObservation?
  private var statusObservation: NSKeyValueObservation?
  private var rateObservation: NSKeyValueObservation?
  private var timeObserver: Any?
  private var frameCounter: CADisplayLink?
  /// Watches the layer for a seek's frame (whenShown).
  private var displayWatch: CADisplayLink?
  private var frameOutput: AVPlayerItemVideoOutput?
  /// The frame the counter last took for display (snapshot() while playing).
  private var lastFrame: CVPixelBuffer?
  /// A timeline swap waiting for its first frame on screen: when it started, whether its seek is done.
  private var pendingSwap: (started: Double, seeked: Bool)?
  /// Where a swap is putting the new item, until its seek lands: the time to report and to swap
  /// the next edit in at (the new item reads 0 until then).
  private var swapTarget: CMTime?
  /// Play once the swap lands: it was playing when the edit came in, and nothing paused it since.
  private var resumeAfterSwap = false
  private let voiceoverCapture = VoiceoverCapture()
  private var voiceoverGeneration = 0
  private var voiceoverRecording = false
  private var voiceoverStopping = false
  private var voiceoverStartHost = 0.0
  private var voiceoverStartMs = 0.0
  private var voiceoverFirstFrameMs = -1.0
  private var voiceoverPreviousMute = false
  private var voiceoverResult: Result<VoiceoverTake, Error>?
  private var voiceoverResultFile: VoiceoverFileOwnership?
  private var voiceoverWaiters: [Promise<VoiceoverTake>] = []
  private var lifecycleObservers: [NSObjectProtocol] = []

  override init() {
    super.init()
    player.automaticallyWaitsToMinimizeStalling = false
    playerView.playerLayer.player = player
    playerView.playerLayer.videoGravity = .resizeAspect
    playerView.backgroundColor = .black
    rateObservation = player.observe(\.timeControlStatus) { [weak self] p, _ in
      DispatchQueue.main.async {
        guard let self else { return }
        let playing = p.timeControlStatus == .playing
        if !playing, self.voiceoverRecording {
          if Self.ms(p.currentTime()) >= self.durationMs - 1 { self.finishVoiceover() }
          else { self.interruptVoiceover(reason: "Voiceover was cancelled because video playback stopped. Try recording again.") }
        }
        self.onTime(Self.ms(self.swapTarget ?? p.currentTime()), playing)
        // Follow what plays, not the calls: a swap resumes playback without play().
        if p.timeControlStatus == .paused { Self.audioStopped() }
        if playing && self.hasAudio { Self.audioPlaying = true }
        if playing { self.startCounting() } else if !self.scrubbing { self.stopCounting() }
      }
    }
    timeObserver = player.addPeriodicTimeObserver(forInterval: CMTime(value: 1, timescale: 12), queue: .main) { [weak self] t in
      guard let self, self.player.timeControlStatus == .playing else { return }
      if self.voiceoverRecording {
        let expected = self.voiceoverStartMs + (CMClockGetTime(CMClockGetHostTimeClock()).seconds - self.voiceoverStartHost) * 1000
        if abs(Self.ms(self.player.currentTime()) - expected) > max(50, 1500 / max(1, self.options.fps)) {
          self.interruptVoiceover(reason: "Voiceover was cancelled because video playback stalled. Try recording again.")
          return
        }
      }
      // Mid-swap the new item reads ~0 (and the observer fires on the jump): report its target.
      self.onTime(Self.ms(self.swapTarget ?? t), true)
    }
    lifecycleObservers = [
      NotificationCenter.default.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self] _ in self?.interruptVoiceover() },
      NotificationCenter.default.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] note in
        if (note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt) == AVAudioSession.InterruptionType.began.rawValue { self?.interruptVoiceover() }
      },
      NotificationCenter.default.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main) { [weak self] note in
        let reason = (note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt) ?? 0
        if reason == AVAudioSession.RouteChangeReason.oldDeviceUnavailable.rawValue
          || reason == AVAudioSession.RouteChangeReason.newDeviceAvailable.rawValue
          || reason == AVAudioSession.RouteChangeReason.noSuitableRouteForCategory.rawValue { self?.interruptVoiceover() }
      },
      NotificationCenter.default.addObserver(forName: AVAudioSession.mediaServicesWereResetNotification, object: nil, queue: .main) { [weak self] _ in
        self?.interruptVoiceover()
      }
    ]
  }

  func afterUpdate() {
    guard changed else { return }
    changed = false
    DispatchQueue.main.async { self.rebuild() }
  }

  // MARK: Timeline

  /// Load new clips (once each), build the composition and swap it in at the current position.
  private func rebuild() {
    if voiceoverRecording || voiceoverStopping { interruptVoiceover() }
    let list = clips
    let options = self.options
    let size = renderSize(options)
    build?.cancel()
    timelineGeneration += 1
    let generation = timelineGeneration
    rebuilding = true
    build = Task { @MainActor [weak self] in
      guard let self else { return }
      defer {
        if generation == self.timelineGeneration {
          self.rebuilding = false
          self.build = nil
        }
      }
      let started = CACurrentMediaTime()
      do {
        var items: [(clip: MergeClip, media: Probe.Media)] = []
        for clip in list {
          let m: Probe.Media
          if let cached = self.media[clip.uri] { m = cached } else {
            m = try await Probe.load(fileURL(clip.uri))
            self.media[clip.uri] = m
          }
          items.append((clip, m))
        }
        try Task.checkCancellation()
        let target = RenderTarget(
          size: size, transform: .identity, canvas: size, fps: options.fps, bitrate: 0, audio: options.audio)
        let composed = try await Timeline.compose(items, target: target,
          voiceover: options.voiceover ?? [], sourceMuted: options.sourceMuted ?? false, voiceoverMuted: options.voiceoverMuted ?? false)
        try Task.checkCancellation()
        let item = AVPlayerItem(asset: composed.composition.copy() as! AVComposition)
        item.videoComposition = composed.videoComposition.copy() as? AVVideoComposition
        item.audioTimePitchAlgorithm = .spectral  // natural pitch, as the export
        item.seekingWaitsForVideoCompositionRendering = true
        self.durationMs = Self.ms(composed.duration)
        self.clipStartsMs = composed.starts.map(Self.ms)
        let hadAudio = self.hasAudio
        self.hasAudio = !composed.composition.tracks(withMediaType: .audio).isEmpty
        // Sound gone from a playing draft (every clip muted): the output goes idle from now.
        if hadAudio && !self.hasAudio { Self.audioStopped() }
        self.swap(to: item, started: started)
      } catch is CancellationError {
      } catch {
        self.onStatus(PreviewStatus(durationMs: 0, clipStartsMs: [], ready: false, error: error.localizedDescription))
      }
    }
  }

  private func swap(to item: AVPlayerItem, started: Double) {
    let first = player.currentItem == nil
    // A seek on the old item may still be landing while this composition builds. Preserve
    // that requested position instead of replacing the item at its stale currentTime.
    let now = !waiters.isEmpty && chaseTime.isValid ? chaseTime : (swapTarget ?? player.currentTime())
    let at = first ? CMTime.zero : CMTimeMinimum(now, CMTime(value: CMTimeValue(max(0, durationMs - 1)), timescale: 1000))
    // An edit during a swap: the player isn't playing the new item yet, the swap before resumes.
    let wasPlaying = player.timeControlStatus == .playing || resumeAfterSwap
    resumeAfterSwap = wasPlaying
    statusObservation = item.observe(\.status) { [weak self] item, _ in
      DispatchQueue.main.async {
        guard let self, item.status == .failed else { return }
        self.onStatus(PreviewStatus(durationMs: self.durationMs, clipStartsMs: self.clipStartsMs, ready: self.ready, error: item.error?.localizedDescription ?? "The preview couldn't play."))
      }
    }
    // The counter's output goes on with the item: adding one to a playing or about-to-play item
    // holds its clock back for a few hundred ms.
    let output = AVPlayerItemVideoOutput(pixelBufferAttributes: nil)
    item.add(output)
    frameOutput = output
    swapTarget = first ? nil : at
    // Replacing the item cancels its seeks. Its callbacks no longer own this seek state;
    // leave waiters queued so the new item's swap completion can drain the latest target.
    displayWatch?.invalidate()
    displayWatch = nil
    seeking = false
    player.replaceCurrentItem(with: item)
    pendingSwap = first ? nil : (started, false)
    // The layer's first frame of the new item: the first frame of all (ready), or the end of a swap.
    readyObservation = playerView.playerLayer.observe(\.isReadyForDisplay, options: [.initial, .new]) { [weak self] layer, _ in
      DispatchQueue.main.async {
        guard let self, layer.isReadyForDisplay, self.player.currentItem === item else { return }
        if !self.ready {
          self.ready = true
          self.firstFrameMs = (CACurrentMediaTime() - started) * 1000
          self.onStatus(PreviewStatus(durationMs: self.durationMs, clipStartsMs: self.clipStartsMs, ready: true, error: nil))
        }
        self.finishSwapIfShown()
      }
    }
    onStatus(PreviewStatus(durationMs: durationMs, clipStartsMs: clipStartsMs, ready: ready, error: nil))
    // An exact seek to the same time shows the new timeline's frame there.
    player.seek(to: at, toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] _ in
      DispatchQueue.main.async {
        guard let self, self.player.currentItem === item else { return }
        let resume = self.resumeAfterSwap
        self.resumeAfterSwap = false
        // The edit is in at the same time: say so (until now the new item read 0), unless a seek
        // came in meanwhile (it reports its own frame).
        if self.swapTarget != nil {
          self.swapTarget = nil
          self.onTime(Self.ms(self.player.currentTime()), resume || self.player.timeControlStatus == .playing)
        }
        self.pendingSwap?.seeked = true
        self.finishSwapIfShown()
        // A seek asked for while the swap landed waited for it: run it now.
        if !self.waiters.isEmpty && !self.seeking { self.seekToChaseTime() }
        if resume { self.player.play() }
      }
    }
  }

  /// A swap is done once its seek has landed and the layer shows the new item: paused, once the
  /// layer hands back the frame it displays (its ready flag can still be the old item's), checked
  /// again every few ms for up to a second.
  private func finishSwapIfShown(attempt: Int = 0) {
    guard let swap = pendingSwap, swap.seeked, playerView.playerLayer.isReadyForDisplay else { return }
    if #available(iOS 16, *), player.timeControlStatus != .playing, attempt < 125,
       playerView.playerLayer.displayedPixelBuffer() == nil {
      DispatchQueue.main.asyncAfter(deadline: .now() + 0.008) { [weak self] in
        guard let self, self.pendingSwap?.started == swap.started else { return }
        self.finishSwapIfShown(attempt: attempt + 1)
      }
      return
    }
    pendingSwap = nil
    lastSwapMs = (CACurrentMediaTime() - swap.started) * 1000
    onStatus(PreviewStatus(durationMs: durationMs, clipStartsMs: clipStartsMs, ready: ready, error: nil))
  }

  /// The preview draws at the view's pixel size (the canvas's shape, fitted inside it), never
  /// larger than the canvas; half the canvas before the view is laid out.
  private func renderSize(_ options: MergeOptions) -> CGSize {
    let bounds = playerView.bounds.size
    let px = UIScreen.main.scale
    let (w, h) = (options.width, options.height)
    let scale = bounds.width > 0 && bounds.height > 0 ? min(1, Double(bounds.width * px) / w, Double(bounds.height * px) / h) : 0.5
    return CGSize(width: max(2, (w * scale / 2).rounded() * 2), height: max(2, (h * scale / 2).rounded() * 2))
  }

  // MARK: Playback

  func prepareVoiceover() throws -> Promise<Void> {
    let promise = Promise<Void>()
    DispatchQueue.main.async {
      let generation = self.voiceoverGeneration
      AVAudioSession.sharedInstance().requestRecordPermission { granted in
        DispatchQueue.main.async {
          guard generation == self.voiceoverGeneration else {
            promise.reject(withError: RuntimeError("Voiceover was closed.")); return
          }
          guard granted else {
            promise.reject(withError: RuntimeError("Allow microphone access in Settings to record voiceover.")); return
          }
          do {
            try self.voiceoverCapture.prepare()
            self.voiceoverCapture.waitForInput { result in
              guard generation == self.voiceoverGeneration else {
                promise.reject(withError: RuntimeError("Voiceover was closed.")); return
              }
              switch result {
              case .success:
                self.onStatus(PreviewStatus(durationMs: self.durationMs, clipStartsMs: self.clipStartsMs, ready: self.ready, error: nil))
                promise.resolve(withResult: ())
              case .failure(let error): promise.reject(withError: error)
              }
            }
          }
          catch { self.voiceoverCapture.cancel(); promise.reject(withError: error) }
        }
      }
    }
    return promise
  }

  func startVoiceover() throws -> Promise<Double> {
    let promise = Promise<Double>()
    DispatchQueue.main.async {
      guard self.ready, !self.rebuilding, !self.changed, !self.seeking, self.pendingSwap == nil, !self.voiceoverRecording, !self.voiceoverStopping,
            self.player.currentItem?.status == .readyToPlay else {
        promise.reject(withError: RuntimeError("Wait for the preview to finish loading before recording.")); return
      }
      self.player.pause()
      let start = Self.ms(self.player.currentTime())
      guard start < self.durationMs - 1 else {
        promise.reject(withError: RuntimeError("Move the playhead before the end to record voiceover.")); return
      }
      let host = CMClockGetTime(CMClockGetHostTimeClock())
      do {
        try self.voiceoverCapture.start(hostSeconds: host.seconds)
        self.discardCompletedVoiceover()
        self.voiceoverStartHost = host.seconds
        self.voiceoverStartMs = start
        self.voiceoverFirstFrameMs = -1
        self.voiceoverPreviousMute = self.player.isMuted
        self.player.isMuted = true
        self.voiceoverRecording = true
        self.resumeAfterSwap = false
        // The paused seek has already landed. An explicit item time would request another
        // seek here; .invalid starts its current frame with the shared host-clock anchor.
        self.player.setRate(1, time: .invalid, atHostTime: host)
        promise.resolve(withResult: start)
      } catch { promise.reject(withError: error) }
    }
    return promise
  }

  func stopVoiceover() throws -> Promise<VoiceoverTake> {
    let promise = Promise<VoiceoverTake>()
    DispatchQueue.main.async {
      if let result = self.voiceoverResult {
        self.resolveVoiceover(promise, result)
      } else if self.voiceoverRecording || self.voiceoverStopping {
        self.voiceoverWaiters.append(promise)
        if self.voiceoverRecording { self.finishVoiceover() }
      } else { promise.reject(withError: RuntimeError("There is no voiceover take to finish.")) }
    }
    return promise
  }

  private func finishVoiceover() {
    guard voiceoverRecording else { return }
    player.pause()
    let end = min(durationMs, max(voiceoverStartMs, Self.ms(player.currentTime())))
    let host = CMClockGetTime(CMClockGetHostTimeClock()).seconds
    if end < durationMs - 1,
      abs((host - voiceoverStartHost) * 1000 - (end - voiceoverStartMs)) > max(20, 1000 / max(1, options.fps)) {
      interruptVoiceover(reason: "Voiceover was cancelled because video playback stalled. Try recording again.")
      return
    }
    voiceoverRecording = false
    voiceoverStopping = true
    player.isMuted = voiceoverPreviousMute
    onTime(end, false)
    let generation = voiceoverGeneration
    let start = voiceoverStartMs
    let startHost = voiceoverStartHost
    let frameMs = voiceoverFirstFrameMs
    voiceoverCapture.stop(hostSeconds: min(host, startHost + (end - start) / 1000)) { [weak self] result in
      guard let self, generation == self.voiceoverGeneration else {
        // Cancellation may run after the writer finished but before this main-thread reply.
        // No JS caller received that file, so it has no owner to dispose it later.
        if case .success(let captured) = result {
          VoiceoverFileOwnership(url: fileURL(captured.uri)).discardIfUnclaimed()
        }
        return
      }
      let take: Result<VoiceoverTake, Error> = result.map { captured in
        let offset = max(0, (captured.firstHostSeconds - startHost) * 1000)
        // The input has a discrete sample grid; never ask storage/preview to trim beyond its
        // last sample when the player's paused time falls a fraction of a sample later.
        let capturedEnd = min(end, start + offset + captured.durationMs)
        return VoiceoverTake(uri: captured.uri, startMs: start + offset, endMs: capturedEnd,
          durationMs: captured.durationMs, sourceStartMs: 0,
          pressToPlaybackMs: frameMs, pressToCaptureMs: offset,
          stopAlignmentMs: captured.durationMs - max(0, end - start - offset), audioVideoOffsetMs: offset)
      }
      self.voiceoverStopping = false
      self.voiceoverResult = take
      if case .success(let captured) = take {
        self.voiceoverResultFile = VoiceoverFileOwnership(url: fileURL(captured.uri))
      }
      let waiting = self.voiceoverWaiters
      self.voiceoverWaiters.removeAll()
      waiting.forEach { self.resolveVoiceover($0, take) }
    }
  }

  private func resolveVoiceover(_ promise: Promise<VoiceoverTake>, _ result: Result<VoiceoverTake, Error>) {
    switch result {
    case .success(let take):
      self.voiceoverResultFile?.transferToHost()
      promise.resolve(withResult: take)
    case .failure(let error): promise.reject(withError: error)
    }
  }

  func cancelVoiceover() throws {
    DispatchQueue.main.async { self.releaseVoiceover() }
  }

  private func discardCompletedVoiceover() {
    voiceoverResultFile?.discardIfUnclaimed()
    voiceoverResultFile = nil
    voiceoverResult = nil
  }

  private func releaseVoiceover(discardCompleted: Bool = false) {
    voiceoverGeneration += 1
    if voiceoverRecording || voiceoverStopping {
      player.pause()
      player.isMuted = voiceoverPreviousMute
    }
    voiceoverRecording = false
    voiceoverStopping = false
    if discardCompleted { discardCompletedVoiceover() }
    voiceoverCapture.cancel()
    let waiting = voiceoverWaiters
    voiceoverWaiters.removeAll()
    waiting.forEach { $0.reject(withError: RuntimeError("Voiceover recording was cancelled.")) }
  }

  private func interruptVoiceover(reason: String = "Voiceover stopped because the microphone was interrupted. Reopen Voiceover to continue.") {
    guard voiceoverCapture.prepared || voiceoverRecording || voiceoverStopping else { return }
    releaseVoiceover(discardCompleted: false)
    onStatus(PreviewStatus(durationMs: durationMs, clipStartsMs: clipStartsMs, ready: ready,
      error: reason))
  }

  func play() throws {
    DispatchQueue.main.async {
      // Start the clock now. play() schedules the start "at a host time in the near future" to
      // allow for media loading (AVPlayer.h, setRate:time:atHostTime:), which held the picture
      // still for 170-300 ms after a paused seek on a local, prepared item; nothing is loading.
      // Allowed because automaticallyWaitsToMinimizeStalling is off.
      // A cold audio output (idle ≥ 5 s, or never started) takes ~125 ms to its first buffer and
      // would drop the opening: start the clock that much later (160 ms covers the wake plus the
      // output latency). Warm plays start now.
      let now = CACurrentMediaTime()
      let cold = self.hasAudio && !Self.audioPlaying && (Self.audioStoppedAt == 0 || now - Self.audioStoppedAt >= Self.coldAfter)
      if self.hasAudio { Self.audioPlaying = true }
      self.player.setRate(1, time: .invalid, atHostTime: CMTimeAdd(CMClockGetTime(CMClockGetHostTimeClock()), cold ? Self.coldLead : .zero))
    }
  }

  private static func audioStopped() {
    if audioPlaying { audioPlaying = false; audioStoppedAt = CACurrentMediaTime() }
  }
  private static let coldAfter = 5.0
  private static let coldLead = CMTime(value: 160, timescale: 1000)

  func pause() throws {
    DispatchQueue.main.async {
      if self.voiceoverRecording { self.interruptVoiceover(reason: "Voiceover was cancelled because video playback stopped. Try recording again.") }
      self.resumeAfterSwap = false
      self.player.pause()
    }
  }

  func seek(timeMs: Double) throws -> Promise<Double> {
    let promise = Promise<Double>()
    DispatchQueue.main.async {
      if self.voiceoverRecording { self.interruptVoiceover(reason: "Voiceover was cancelled because the playhead moved. Try recording again.") }
      self.waiters.append(promise)
      let ms = min(max(0, timeMs), max(0, self.durationMs - 1))
      self.chaseTime = CMTime(value: CMTimeValue((ms * 1000).rounded()), timescale: 1_000_000)
      // A seek replaces a swap's landing time.
      self.swapTarget = nil
      self.seekStart = CACurrentMediaTime()
      if !self.seeking { self.seekToChaseTime() }
    }
    return promise
  }

  /// One seek at a time, always to the latest time asked for; exact unless the person is dragging.
  private func seekToChaseTime() {
    // A ready item can still have its swap's old-position seek in flight. Keep later seeks
    // queued until that seek completes; overlapping AVPlayer seeks can cancel the Undo target
    // and leave the old playhead position on screen. The swap completion drains these waiters.
    if pendingSwap?.seeked == false { return }
    guard let item = player.currentItem, item.status == .readyToPlay else {
      // Not loaded yet (the first timeline): answer with where it is.
      finishSeeks(Self.ms(player.currentTime()))
      return
    }
    seeking = true
    let target = chaseTime
    let tolerance = scrubbing ? CMTime(value: 1, timescale: CMTimeScale(max(1, options.fps.rounded()))) : .zero
    // The frame on screen before this seek: the seek is done once the layer shows another one.
    // Holding it keeps its surface from being reused for the next frame.
    let before = displayedFrame()
    let fromMs = Self.ms(player.currentTime())
    player.seek(to: target, toleranceBefore: tolerance, toleranceAfter: tolerance) { [weak self] _ in
      DispatchQueue.main.async {
        guard let self, self.player.currentItem === item else { return }
        let landedMs = Self.ms(self.player.currentTime())
        self.whenShown(after: before, from: fromMs, to: landedMs) {
          guard self.player.currentItem === item else { return }
          self.seeking = false
          if CMTimeCompare(target, self.chaseTime) == 0 {
            self.lastSeekMs = (CACurrentMediaTime() - self.seekStart) * 1000
            self.finishSeeks(Self.ms(self.player.currentTime()))
          } else {
            // A drag moved on: report the frame now on screen, then chase the newer time.
            self.onTime(landedMs, false)
            self.seekToChaseTime()
          }
        }
      }
    }
  }

  /// The frame the layer shows while paused (iOS returns none while playing).
  private func displayedFrame() -> CVPixelBuffer? {
    guard #available(iOS 16, *), player.timeControlStatus != .playing else { return nil }
    return playerView.playerLayer.displayedPixelBuffer()
  }

  /// Call `done` once the layer shows a frame other than `before`: at once while playing, when
  /// the seek stayed on the same frame (the layer then keeps it), or after a timeout.
  private func whenShown(after before: CVPixelBuffer?, from fromMs: Double, to landedMs: Double, _ done: @escaping () -> Void) {
    guard #available(iOS 16, *), player.timeControlStatus != .playing else { done(); return }
    let frameMs = 1000 / max(1, options.fps)
    let sameFrame = (fromMs / frameMs + 1e-6).rounded(.down) == (landedMs / frameMs + 1e-6).rounded(.down)
    let started = CACurrentMediaTime()
    let baseline = Self.surfaceID(before)
    displayWatch?.invalidate()
    let link = CADisplayLink(target: DisplayLinkProxy { [weak self] link in
      guard let self else { link.invalidate(); return }
      let shown = Self.surfaceID(self.displayedFrame())
      let waited = (CACurrentMediaTime() - started) * 1000
      let changed = shown != 0 && shown != baseline
      let timedOut = waited > (sameFrame ? 40 : 300) || self.player.timeControlStatus == .playing
      guard changed || timedOut else { return }
      link.invalidate()
      if self.displayWatch === link { self.displayWatch = nil }
      _ = before  // held until the new frame is up
      done()
    }, selector: #selector(DisplayLinkProxy.tick(_:)))
    link.add(to: .main, forMode: .common)
    displayWatch = link
  }

  private func finishSeeks(_ timeMs: Double) {
    let waiting = waiters
    waiters.removeAll()
    waiting.forEach { $0.resolve(withResult: timeMs) }
    onTime(timeMs, player.timeControlStatus == .playing)
  }

  func setScrubbing(scrubbing: Bool) throws {
    DispatchQueue.main.async {
      if scrubbing, self.voiceoverRecording { self.interruptVoiceover(reason: "Voiceover was cancelled because the playhead moved. Try recording again.") }
      let wasScrubbing = self.scrubbing
      self.scrubbing = scrubbing
      // Count the frames a drag shows, as playback does.
      if scrubbing { self.startCounting() } else if self.player.timeControlStatus != .playing { self.stopCounting() }
      // Letting go: finish on the exact frame.
      if wasScrubbing, !scrubbing, self.chaseTime.isValid, !self.seeking { self.seekToChaseTime() }
    }
  }

  // MARK: Tests

  /// The frame on screen as a JPEG: the layer's displayed frame when paused (iOS returns none
  /// while playing), the frame the counter last took for display while playing.
  func snapshot() throws -> Promise<String> {
    let promise = Promise<String>()
    DispatchQueue.main.async {
      var buffer = self.lastFrame
      if #available(iOS 16, *), self.player.timeControlStatus != .playing {
        buffer = self.playerView.playerLayer.displayedPixelBuffer()
      }
      guard let buffer else {
        promise.reject(withError: RuntimeError("No frame on screen yet."))
        return
      }
      do {
        promise.resolve(withResult: try Self.writeJPEG(buffer))
      } catch {
        promise.reject(withError: error)
      }
    }
    return promise
  }

  func stats() throws -> PreviewStats {
    PreviewStats(firstFrameMs: firstFrameMs, lastSeekMs: lastSeekMs, lastSwapMs: lastSwapMs, framesShown: framesShown, framesDropped: framesDropped)
  }

  /// Counts new frames while playing or scrubbing, from the item's video output.
  private func startCounting() {
    guard frameCounter == nil else { return }
    let link = CADisplayLink(target: DisplayLinkProxy { [weak self] link in self?.countFrame(link) }, selector: #selector(DisplayLinkProxy.tick(_:)))
    link.add(to: .main, forMode: .common)
    frameCounter = link
  }

  private func countFrame(_ link: CADisplayLink) {
    guard let output = frameOutput else { return }
    let t = output.itemTime(forHostTime: link.targetTimestamp)
    if output.hasNewPixelBuffer(forItemTime: t), let frame = output.copyPixelBuffer(forItemTime: t, itemTimeForDisplay: nil) {
      if voiceoverRecording, voiceoverFirstFrameMs < 0, Self.ms(t) > voiceoverStartMs + 1 {
        voiceoverFirstFrameMs = (CACurrentMediaTime() - voiceoverStartHost) * 1000
      }
      lastFrame = frame
      framesShown += 1
    }
  }

  private func stopCounting() {
    frameCounter?.invalidate()
    frameCounter = nil
    lastFrame = nil
  }

  func onDropView() {
    DispatchQueue.main.async {
      self.releaseVoiceover(discardCompleted: true)
      self.lifecycleObservers.forEach(NotificationCenter.default.removeObserver)
      self.lifecycleObservers.removeAll()
      self.build?.cancel()
      self.displayWatch?.invalidate()
      self.displayWatch = nil
      self.stopCounting()
      if let observer = self.timeObserver { self.player.removeTimeObserver(observer) }
      self.timeObserver = nil
      self.player.pause()
      Self.audioStopped()
      self.player.replaceCurrentItem(with: nil)
      self.frameOutput = nil
    }
  }

  private static func ms(_ t: CMTime) -> Double { t.isNumeric ? t.seconds * 1000 : 0 }

  private static func surfaceID(_ b: CVPixelBuffer?) -> UInt32 {
    guard let b, let s = CVPixelBufferGetIOSurface(b) else { return 0 }
    return IOSurfaceGetID(s.takeUnretainedValue())
  }

  private static func writeJPEG(_ buffer: CVPixelBuffer) throws -> String {
    let image = CIImage(cvPixelBuffer: buffer)
    guard let cg = CIContext().createCGImage(image, from: image.extent) else { throw RuntimeError("Couldn't read the frame.") }
    let url = try Merge.outputURL("frame", extension: "jpg")
    guard let destination = CGImageDestinationCreateWithURL(url as CFURL, UTType.jpeg.identifier as CFString, 1, nil) else {
      throw RuntimeError("Couldn't create the image file.")
    }
    CGImageDestinationAddImage(destination, cg, [kCGImageDestinationLossyCompressionQuality: 0.92] as CFDictionary)
    guard CGImageDestinationFinalize(destination) else { throw RuntimeError("Couldn't write the image file.") }
    return url.absoluteString
  }
}

/// CADisplayLink retains its target; this breaks the cycle.
private final class DisplayLinkProxy {
  let callback: (CADisplayLink) -> Void
  init(_ callback: @escaping (CADisplayLink) -> Void) { self.callback = callback }
  @objc func tick(_ link: CADisplayLink) { callback(link) }
}
