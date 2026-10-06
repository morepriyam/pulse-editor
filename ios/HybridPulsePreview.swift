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
  private var changed = false
  private var durationMs = 0.0
  private var ready = false

  var clips: [MergeClip] = [] { didSet { changed = true } }
  var options = MergeOptions(width: 1080, height: 1920, fps: 30, bitrate: 5_000_000, audio: MergeAudio(sampleRate: 48_000, channels: 2)) {
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
        self.onTime(Self.ms(p.currentTime()), playing)
        if playing { self.startCounting() } else if !self.scrubbing { self.stopCounting() }
      }
    }
    timeObserver = player.addPeriodicTimeObserver(forInterval: CMTime(value: 1, timescale: 12), queue: .main) { [weak self] t in
      guard let self, self.player.timeControlStatus == .playing else { return }
      self.onTime(Self.ms(t), true)
    }
  }

  func afterUpdate() {
    guard changed else { return }
    changed = false
    DispatchQueue.main.async { self.rebuild() }
  }

  // MARK: Timeline

  /// Load new clips (once each), build the composition and swap it in at the current position.
  private func rebuild() {
    let list = clips
    let options = self.options
    let size = renderSize(options)
    build?.cancel()
    build = Task { @MainActor [weak self] in
      guard let self else { return }
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
        let snapped = try await Merge.copied(list, items.map { $0.media }, options: options)
        let composed = try await Timeline.compose(items, target: target, snapped: snapped)
        try Task.checkCancellation()
        let item = AVPlayerItem(asset: composed.composition.copy() as! AVComposition)
        item.videoComposition = composed.videoComposition.copy() as? AVVideoComposition
        item.audioTimePitchAlgorithm = .spectral  // natural pitch, as the export
        item.seekingWaitsForVideoCompositionRendering = true
        self.durationMs = Self.ms(composed.duration)
        self.swap(to: item, started: started)
      } catch is CancellationError {
      } catch {
        self.onStatus(PreviewStatus(durationMs: 0, ready: false, error: error.localizedDescription))
      }
    }
  }

  private func swap(to item: AVPlayerItem, started: Double) {
    let first = player.currentItem == nil
    let at = first ? CMTime.zero : CMTimeMinimum(player.currentTime(), CMTime(value: CMTimeValue(max(0, durationMs - 1)), timescale: 1000))
    let wasPlaying = player.timeControlStatus == .playing
    statusObservation = item.observe(\.status) { [weak self] item, _ in
      DispatchQueue.main.async {
        guard let self, item.status == .failed else { return }
        self.onStatus(PreviewStatus(durationMs: self.durationMs, ready: self.ready, error: item.error?.localizedDescription ?? "The preview couldn't play."))
      }
    }
    // The counter's output goes on with the item: adding one to a playing or about-to-play item
    // holds its clock back for a few hundred ms.
    let output = AVPlayerItemVideoOutput(pixelBufferAttributes: nil)
    item.add(output)
    frameOutput = output
    player.replaceCurrentItem(with: item)
    pendingSwap = first ? nil : (started, false)
    // The layer's first frame of the new item: the first frame of all (ready), or the end of a swap.
    readyObservation = playerView.playerLayer.observe(\.isReadyForDisplay, options: [.initial, .new]) { [weak self] layer, _ in
      DispatchQueue.main.async {
        guard let self, layer.isReadyForDisplay, self.player.currentItem === item else { return }
        if !self.ready {
          self.ready = true
          self.firstFrameMs = (CACurrentMediaTime() - started) * 1000
          self.onStatus(PreviewStatus(durationMs: self.durationMs, ready: true, error: nil))
        }
        self.finishSwapIfShown()
      }
    }
    onStatus(PreviewStatus(durationMs: durationMs, ready: ready, error: nil))
    // An exact seek to the same time shows the new timeline's frame there.
    player.seek(to: at, toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] _ in
      DispatchQueue.main.async {
        guard let self, self.player.currentItem === item else { return }
        self.pendingSwap?.seeked = true
        self.finishSwapIfShown()
        if wasPlaying { self.player.play() }
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

  func play() throws {
    DispatchQueue.main.async {
      // Start the clock now. play() schedules the start "at a host time in the near future" to
      // allow for media loading (AVPlayer.h, setRate:time:atHostTime:), which held the picture
      // still for 170-300 ms after a paused seek on a local, prepared item; nothing is loading.
      // Allowed because automaticallyWaitsToMinimizeStalling is off.
      self.player.setRate(1, time: .invalid, atHostTime: CMClockGetTime(CMClockGetHostTimeClock()))
    }
  }

  func pause() throws {
    DispatchQueue.main.async { self.player.pause() }
  }

  func seek(timeMs: Double) throws -> Promise<Double> {
    let promise = Promise<Double>()
    DispatchQueue.main.async {
      self.waiters.append(promise)
      let ms = min(max(0, timeMs), max(0, self.durationMs - 1))
      self.chaseTime = CMTime(value: CMTimeValue((ms * 1000).rounded()), timescale: 1_000_000)
      self.seekStart = CACurrentMediaTime()
      if !self.seeking { self.seekToChaseTime() }
    }
    return promise
  }

  /// One seek at a time, always to the latest time asked for; exact unless the person is dragging.
  private func seekToChaseTime() {
    guard let item = player.currentItem, item.status == .readyToPlay else {
      // Not loaded yet: answer with where it is; the timeline swap seeks on its own.
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
        guard let self else { return }
        let landedMs = Self.ms(self.player.currentTime())
        self.whenShown(after: before, from: fromMs, to: landedMs) {
          if CMTimeCompare(target, self.chaseTime) == 0 {
            self.seeking = false
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
      self.scrubbing = scrubbing
      // Count the frames a drag shows, as playback does.
      if scrubbing { self.startCounting() } else if self.player.timeControlStatus != .playing { self.stopCounting() }
      // Letting go: finish on the exact frame.
      if !scrubbing, self.chaseTime.isValid, !self.seeking { self.seekToChaseTime() }
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
      self.build?.cancel()
      self.displayWatch?.invalidate()
      self.displayWatch = nil
      self.stopCounting()
      if let observer = self.timeObserver { self.player.removeTimeObserver(observer) }
      self.timeObserver = nil
      self.player.pause()
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
