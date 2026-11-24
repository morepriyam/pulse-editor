import AVFoundation
import CoreGraphics
import ImageIO

/// A still image as one clip in the recorder's format (see `ImageClipOptions`), so it can join the
/// camera's recordings without re-encoding them:
/// - decoded with ImageIO at most canvas-sized (a 48 MP photo is never decoded whole), upright
///   (EXIF orientation applied), as SDR (an HDR photo's gain map is left out);
/// - drawn once into an sRGB picture in the coded orientation the rotation tag turns upright
///   (1920×1080 tagged 90° for a 1080×1920 canvas), fitted and centered on the background colour,
///   transparency composited onto it;
/// - that picture appended at every frame time (90 frames for 3 s at 30 fps, constant rate) to an
///   AVAssetWriter with merge's H.264 settings, and zeroed PCM to its AAC encoder in the output's
///   layout, as long as the picture;
/// - the output checked like a conform's: H.264 8-bit SDR, canvas, tag, rate, sound, length.
/// An image this device can't decode (a format ImageIO doesn't read) is rejected.
enum ImageClip {
  static func run(_ uri: String, _ options: ImageClipOptions, progress: MergeProgress) async throws -> ConformResult {
    Merge.removeStaleOutputs()
    progress(0)
    try validate(options)
    let degrees = (Int(options.rotation.rounded()) % 360 + 360) % 360
    guard degrees % 90 == 0 else { throw MergeError.invalid("The rotation must be 0, 90, 180 or 270.") }
    let fps = Int32(options.fps.rounded())
    let background = try color(options.background)
    let frames = Int((options.durationMs / 1000 * Double(fps)).rounded())
    let canvas = CGSize(width: options.width.rounded(), height: options.height.rounded())
    let coded = degrees % 180 == 0 ? canvas : CGSize(width: canvas.height, height: canvas.width)

    let image = try decode(fileURL(uri), maxPixelSize: max(canvas.width, canvas.height))
    try Task.checkCancellation()
    let picture = try draw(image, canvas: canvas, coded: coded, degrees: degrees, background: background)

    let output = try Merge.outputURL("image")
    do {
      try await write(
        picture, frames: frames, fps: fps, coded: coded, transform: Conform.tag(degrees, coded: coded),
        options: options, to: output
      ) { progress($0 * 0.98) }
      let out = try await verify(output, options: options, degrees: degrees, frames: frames, fps: fps)
      progress(1)
      return ConformResult(
        uri: output.absoluteString, durationMs: out.durationMs, encoded: true, bitrate: out.video?.bitrate ?? -1)
    } catch {
      try? FileManager.default.removeItem(at: output)
      throw error
    }
  }

  /// A guard, not the check: `imageClip` in JS checks the options and says what's wrong. This
  /// keeps a direct native call from trapping on a NaN or huge number (converting it to an
  /// integer) or making a clip JS would refuse; the limits are JS's.
  private static func validate(_ o: ImageClipOptions) throws {
    func within(_ value: Double, _ range: ClosedRange<Double>) -> Bool { value.isFinite && range.contains(value) }
    func side(_ value: Double) -> Bool { within(value, 2...4096) && value.truncatingRemainder(dividingBy: 2) == 0 }
    guard side(o.width), side(o.height), within(o.rotation, -360...360), within(o.fps, 1...240),
          within(o.bitrate, 1...1_000_000_000),
          within(o.audio.sampleRate, 8000...192_000), o.audio.sampleRate.rounded() == o.audio.sampleRate,
          o.audio.channels == 1 || o.audio.channels == 2,
          within(o.durationMs, 0...60_000), (o.durationMs / 1000 * o.fps.rounded()).rounded() >= 2 else {
      throw MergeError.invalid("The image clip's options are invalid (imageClip() says which).")
    }
  }

  private static let unreadable = MergeError.failed("This image can't be read on this device.")

  /// The image upright and at most `maxPixelSize` on its long side (never scaled up here), as SDR.
  private static func decode(_ url: URL, maxPixelSize: CGFloat) throws -> CGImage {
    // A photo-library (ph://, assets-library://) or remote URI must be copied to a file first.
    guard url.isFileURL else {
      throw MergeError.invalid("The image must be a local file (a file:// URI or a path), not \(url.scheme ?? "this URI").")
    }
    if !FileManager.default.fileExists(atPath: url.path) {
      throw MergeError.invalid("The image's file is missing.")
    }
    guard let source = CGImageSourceCreateWithURL(url as CFURL, [kCGImageSourceShouldCache: false] as CFDictionary),
          CGImageSourceGetCount(source) > 0 else { throw unreadable }
    var options: [CFString: Any] = [
      // From the full image, not a small embedded thumbnail; EXIF orientation applied.
      kCGImageSourceCreateThumbnailFromImageAlways: true,
      kCGImageSourceCreateThumbnailWithTransform: true,
      kCGImageSourceThumbnailMaxPixelSize: Int(maxPixelSize.rounded()),
      kCGImageSourceShouldCacheImmediately: true,
    ]
    if #available(iOS 17.0, macOS 14.0, *) {
      options[kCGImageSourceDecodeRequest] = kCGImageSourceDecodeToSDR
    }
    // The primary image: a HEIC can hold several, and the one shown isn't always the first.
    guard let image = CGImageSourceCreateThumbnailAtIndex(source, CGImageSourceGetPrimaryImageIndex(source), options as CFDictionary),
          image.width > 0, image.height > 0 else { throw unreadable }
    return image
  }

  /// `#RRGGBB` (or `#RGB`) → an opaque sRGB colour.
  static func color(_ hex: String) throws -> CGColor {
    var digits = hex.trimmingCharacters(in: .whitespaces)
    if digits.hasPrefix("#") { digits.removeFirst() }
    if digits.count == 3 { digits = digits.map { "\($0)\($0)" }.joined() }
    guard digits.count == 6, digits.allSatisfy(\.isHexDigit), let value = UInt32(digits, radix: 16),
          let space = CGColorSpace(name: CGColorSpace.sRGB) else {
      throw MergeError.invalid("The background must be a colour like #000000.")
    }
    let channel = { (shift: UInt32) in CGFloat((value >> shift) & 0xFF) / 255 }
    return CGColor(colorSpace: space, components: [channel(16), channel(8), channel(0), 1])!
  }

  /// The one picture of the clip: the canvas filled with the background, the image fitted and
  /// centered on it, laid into the coded frame.
  private static func draw(
    _ image: CGImage, canvas: CGSize, coded: CGSize, degrees: Int, background: CGColor
  ) throws -> CVPixelBuffer {
    let attributes: [CFString: Any] = [
      kCVPixelBufferCGImageCompatibilityKey: true,
      kCVPixelBufferCGBitmapContextCompatibilityKey: true,
      kCVPixelBufferIOSurfacePropertiesKey: [:] as [CFString: Any],
    ]
    var created: CVPixelBuffer?
    guard CVPixelBufferCreate(
            kCFAllocatorDefault, Int(coded.width), Int(coded.height), kCVPixelFormatType_32BGRA,
            attributes as CFDictionary, &created) == kCVReturnSuccess, let buffer = created else {
      throw MergeError.failed("Couldn't create the image clip's picture.")
    }
    CVPixelBufferLockBaseAddress(buffer, [])
    defer { CVPixelBufferUnlockBaseAddress(buffer, []) }
    guard let space = CGColorSpace(name: CGColorSpace.sRGB),
          let context = CGContext(
            data: CVPixelBufferGetBaseAddress(buffer), width: Int(coded.width), height: Int(coded.height),
            bitsPerComponent: 8, bytesPerRow: CVPixelBufferGetBytesPerRow(buffer), space: space,
            bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue)
    else { throw MergeError.failed("Couldn't create the image clip's picture.") }

    // Drawing happens in the upright canvas; this lays it into the coded frame (a P3 or grey image
    // is converted to sRGB as it's drawn).
    context.concatenate(codedFromCanvas(degrees, canvas: canvas))
    context.interpolationQuality = .high
    context.setFillColor(background)
    context.fill(CGRect(origin: .zero, size: canvas))
    let scale = min(canvas.width / CGFloat(image.width), canvas.height / CGFloat(image.height))
    let fitted = CGSize(width: (CGFloat(image.width) * scale).rounded(), height: (CGFloat(image.height) * scale).rounded())
    context.draw(image, in: CGRect(
      x: ((canvas.width - fitted.width) / 2).rounded(), y: ((canvas.height - fitted.height) / 2).rounded(),
      width: fitted.width, height: fitted.height))

    // Tagged as the output is (sRGB and BT.709 share primaries), so the encoder only converts
    // RGB to Y'CbCr with the BT.709 matrix.
    CVBufferSetAttachment(buffer, kCVImageBufferColorPrimariesKey, kCVImageBufferColorPrimaries_ITU_R_709_2, .shouldPropagate)
    CVBufferSetAttachment(buffer, kCVImageBufferTransferFunctionKey, kCVImageBufferTransferFunction_ITU_R_709_2, .shouldPropagate)
    CVBufferSetAttachment(buffer, kCVImageBufferYCbCrMatrixKey, kCVImageBufferYCbCrMatrix_ITU_R_709_2, .shouldPropagate)
    return buffer
  }

  /// Upright canvas → coded frame, in Core Graphics' y-up space: turned counter-clockwise by the
  /// tag's degrees (the tag turns it back clockwise on display), then moved back to the origin.
  private static func codedFromCanvas(_ degrees: Int, canvas: CGSize) -> CGAffineTransform {
    let turn: CGAffineTransform
    switch degrees {
    case 90: turn = CGAffineTransform(a: 0, b: 1, c: -1, d: 0, tx: 0, ty: 0)
    case 180: turn = CGAffineTransform(a: -1, b: 0, c: 0, d: -1, tx: 0, ty: 0)
    case 270: turn = CGAffineTransform(a: 0, b: -1, c: 1, d: 0, tx: 0, ty: 0)
    default: return .identity
    }
    return MergeGeometry.anchored(turn, size: canvas)
  }

  /// Writes `frames` copies of the picture at 1/fps steps and silence as long, interleaved by
  /// time: each turn appends to whichever input is behind, or to the other when that one isn't
  /// ready, and waits briefly when neither is. Each input is marked finished as soon as its last
  /// sample is in. Cancelling stops and deletes the partial file.
  private static func write(
    _ picture: CVPixelBuffer, frames: Int, fps: Int32, coded: CGSize, transform: CGAffineTransform,
    options: ImageClipOptions, to output: URL, progress: (Double) -> Void
  ) async throws {
    try? FileManager.default.removeItem(at: output)
    let writer = try AVAssetWriter(outputURL: output, fileType: .mp4)
    writer.shouldOptimizeForNetworkUse = true
    let videoInput = AVAssetWriterInput(
      mediaType: .video, outputSettings: EncodeSettings.h264(size: coded, fps: Double(fps), bitrate: options.bitrate))
    videoInput.expectsMediaDataInRealTime = false
    videoInput.transform = transform
    let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: videoInput, sourcePixelBufferAttributes: nil)
    writer.add(videoInput)

    let duration = CMTime(value: CMTimeValue(frames), timescale: fps)
    let silence = try Silence(options.audio, duration: duration)
    let audioInput = AVAssetWriterInput(
      mediaType: .audio, outputSettings: EncodeSettings.aac(options.audio), sourceFormatHint: silence.format)
    audioInput.expectsMediaDataInRealTime = false
    writer.add(audioInput)

    guard writer.startWriting() else { throw writer.error ?? MergeError.failed("Couldn't start writing.") }
    writer.startSession(atSourceTime: .zero)
    do {
      var frame = 0
      while frame < frames || !silence.done {
        try Task.checkCancellation()
        guard writer.status == .writing else { break }
        let videoAt = frame < frames ? Double(frame) / Double(fps) : .infinity
        let videoFirst = videoAt <= silence.nextSeconds
        var appended = false
        for video in videoFirst ? [true, false] : [false, true] {
          if video, frame < frames, videoInput.isReadyForMoreMediaData {
            guard adaptor.append(picture, withPresentationTime: CMTime(value: CMTimeValue(frame), timescale: fps)) else { break }
            frame += 1
            progress(Double(frame) / Double(frames))
            // Finished at once: the writer would otherwise hold the remaining silence back to
            // interleave it with frames that never come (at 1–2 fps the last frame starts a
            // second or more before the sound ends).
            if frame == frames { videoInput.markAsFinished() }
            appended = true
            break
          }
          if !video, !silence.done, audioInput.isReadyForMoreMediaData {
            guard let sample = try silence.next(), audioInput.append(sample) else { break }
            if silence.done { audioInput.markAsFinished() }
            appended = true
            break
          }
        }
        if !appended, writer.status == .writing { try await Task.sleep(nanoseconds: 2_000_000) }
      }
      if writer.status != .writing {
        throw writer.error ?? MergeError.failed("Writing the image clip failed.")
      }
      guard frame == frames else { throw MergeError.failed("The image clip came out with \(frame) of \(frames) frames.") }
      writer.endSession(atSourceTime: duration)
      await writer.finishWriting()
      guard writer.status == .completed else {
        throw writer.error ?? MergeError.failed("Writing the image clip failed.")
      }
      progress(1)
    } catch {
      if writer.status == .writing { writer.cancelWriting() }
      try? FileManager.default.removeItem(at: output)
      throw error
    }
  }

  /// Zeroed 16-bit PCM in the output's layout, in 1024-frame buffers, up to `duration`.
  private final class Silence {
    let format: CMAudioFormatDescription
    private let sampleRate: Int
    private let bytesPerFrame: Int
    private let total: Int
    private var written = 0

    init(_ audio: MergeAudio, duration: CMTime) throws {
      let channels = EncodeSettings.audioChannels(audio.channels)
      sampleRate = Int(audio.sampleRate.rounded())
      guard sampleRate > 0, let tag = EncodeSettings.layoutTag(channels) else {
        throw MergeError.invalid("The audio layout must have a positive sample rate.")
      }
      bytesPerFrame = 2 * channels
      var asbd = AudioStreamBasicDescription(
        mSampleRate: Float64(sampleRate), mFormatID: kAudioFormatLinearPCM,
        mFormatFlags: kLinearPCMFormatFlagIsSignedInteger | kLinearPCMFormatFlagIsPacked,
        mBytesPerPacket: UInt32(bytesPerFrame), mFramesPerPacket: 1, mBytesPerFrame: UInt32(bytesPerFrame),
        mChannelsPerFrame: UInt32(channels), mBitsPerChannel: 16, mReserved: 0)
      var layout = AudioChannelLayout()
      layout.mChannelLayoutTag = tag
      var created: CMAudioFormatDescription?
      guard CMAudioFormatDescriptionCreate(
              allocator: kCFAllocatorDefault, asbd: &asbd, layoutSize: MemoryLayout<AudioChannelLayout>.size,
              layout: &layout, magicCookieSize: 0, magicCookie: nil, extensions: nil,
              formatDescriptionOut: &created) == noErr, let created else {
        throw MergeError.failed("Couldn't set up the image clip's sound.")
      }
      format = created
      total = Int((duration.seconds * Double(sampleRate)).rounded())
    }

    var done: Bool { written >= total }
    /// When the next buffer starts, seconds (infinity once done).
    var nextSeconds: Double { done ? .infinity : Double(written) / Double(sampleRate) }

    func next() throws -> CMSampleBuffer? {
      guard !done else { return nil }
      let count = min(1024, total - written)
      let bytes = count * bytesPerFrame
      var block: CMBlockBuffer?
      var sample: CMSampleBuffer?
      guard CMBlockBufferCreateWithMemoryBlock(
              allocator: kCFAllocatorDefault, memoryBlock: nil, blockLength: bytes, blockAllocator: kCFAllocatorDefault,
              customBlockSource: nil, offsetToData: 0, dataLength: bytes, flags: kCMBlockBufferAssureMemoryNowFlag,
              blockBufferOut: &block) == noErr, let block,
            CMBlockBufferFillDataBytes(with: 0, blockBuffer: block, offsetIntoDestination: 0, dataLength: bytes) == noErr,
            CMAudioSampleBufferCreateReadyWithPacketDescriptions(
              allocator: kCFAllocatorDefault, dataBuffer: block, formatDescription: format, sampleCount: count,
              presentationTimeStamp: CMTime(value: CMTimeValue(written), timescale: CMTimeScale(sampleRate)),
              packetDescriptions: nil, sampleBufferOut: &sample) == noErr, let sample
      else { throw MergeError.failed("Couldn't create the image clip's sound.") }
      written += count
      return sample
    }
  }

  /// The output must be what was asked for: H.264 8-bit SDR on the canvas with the tag, at the
  /// frame rate, the sound in the requested layout, and the picture `frames` / fps long (within
  /// half a frame, as on Android).
  private static func verify(
    _ output: URL, options: ImageClipOptions, degrees: Int, frames: Int, fps: Int32
  ) async throws -> ProbeResult {
    let out = try await Probe.load(output).result
    guard let v = out.video else { throw MergeError.failed("The image clip has no picture.") }
    let (w, h) = degrees % 180 != 0 ? (v.height, v.width) : (v.width, v.height)
    guard v.codec == "h264", v.transfer == .sdr, v.bitDepth <= 8, !v.mirrored, v.rotation == Double(degrees),
          w == options.width.rounded(), h == options.height.rounded() else {
      throw MergeError.failed(
        "The image clip came out \(v.codec) \(Int(v.width))x\(Int(v.height)) rotated \(Int(v.rotation)).")
    }
    if v.fps > 0, v.fps.rounded() != Double(fps) {
      throw MergeError.failed("The image clip came out at \(v.fps.rounded()) fps, not \(fps).")
    }
    guard let a = out.audio, a.codec == "aac", a.sampleRate == options.audio.sampleRate.rounded(),
          a.channels == Double(EncodeSettings.audioChannels(options.audio.channels)) else {
      throw MergeError.failed("The image clip's sound didn't come out as asked.")
    }
    let want = Double(frames) * 1000 / Double(fps)
    if !(v.durationMs > 0) || Swift.abs(v.durationMs - want) > 500 / Double(fps) {
      throw MergeError.failed(
        "The image clip's picture is \(Int(v.durationMs.rounded())) ms, expected \(Int(want.rounded())) ms.")
    }
    return out
  }
}
