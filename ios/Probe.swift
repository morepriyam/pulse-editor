import AVFoundation
import CoreMedia

/// Container and track metadata straight from AVFoundation's async loaders: no decoding, no
/// sample scan (imprecise timing is enough for durations), so a probe costs a few milliseconds.
enum Probe {
  /// A probed file plus the tracks it was read from, for callers that go on to use them (merge).
  struct Media {
    let asset: AVURLAsset
    let videoTrack: AVAssetTrack?
    let audioTrack: AVAssetTrack?
    let result: ProbeResult
  }

  static func read(_ url: URL) async throws -> ProbeResult {
    try await load(url).result
  }

  static func load(_ url: URL) async throws -> Media {
    let asset = AVURLAsset(url: url, options: [AVURLAssetPreferPreciseDurationAndTimingKey: false])
    let (duration, tracks) = try await asset.load(.duration, .tracks)
    guard !tracks.isEmpty else {
      throw NSError(domain: "PulseEditor", code: 1, userInfo: [NSLocalizedDescriptionKey: "No media tracks in \(url.lastPathComponent)"])
    }

    let videoTrack = tracks.first(where: { $0.mediaType == .video })
    let audioTrack = tracks.first(where: { $0.mediaType == .audio })
    var video: ProbeVideo? = nil
    if let videoTrack {
      video = try await readVideo(videoTrack)
    }
    var audio: ProbeAudio? = nil
    if let audioTrack {
      audio = try await readAudio(audioTrack)
    }
    return Media(
      asset: asset, videoTrack: videoTrack, audioTrack: audioTrack,
      result: ProbeResult(durationMs: ms(duration), video: video, audio: audio))
  }

  private static func readVideo(_ track: AVAssetTrack) async throws -> ProbeVideo {
    let (size, transform, fps, dataRate, timeRange, formats) = try await track.load(
      .naturalSize, .preferredTransform, .nominalFrameRate, .estimatedDataRate, .timeRange,
      .formatDescriptions)
    let format = formats.first
    let extensions = format.flatMap { CMFormatDescriptionGetExtensions($0) as? [CFString: Any] } ?? [:]
    let transferTag = extensions[kCMFormatDescriptionExtension_TransferFunction] as? String

    let transfer: Transfer
    if transferTag == kCMFormatDescriptionTransferFunction_ITU_R_2100_HLG as String {
      transfer = .hlg
    } else if transferTag == kCMFormatDescriptionTransferFunction_SMPTE_ST_2084_PQ as String {
      transfer = .pq
    } else {
      transfer = .sdr
    }
    // h264 omits BitsPerComponent (8-bit); HEVC sets it, 10 for HDR sources.
    let bitDepth = (extensions[kCMFormatDescriptionExtension_BitsPerComponent] as? NSNumber)?.intValue
      ?? (transfer == .sdr ? 8 : 10)

    // Clockwise display rotation from the track matrix (UIKit's y-down space), snapped to 90°.
    let degrees = atan2(Double(transform.b), Double(transform.a)) * 180 / .pi
    let rotation = (Int((degrees / 90).rounded()) * 90 % 360 + 360) % 360
    let mirrored = transform.a * transform.d - transform.b * transform.c < 0

    return ProbeVideo(
      codec: format.map { videoCodec(CMFormatDescriptionGetMediaSubType($0)) } ?? "",
      width: Double(size.width),
      height: Double(size.height),
      rotation: Double(rotation),
      mirrored: mirrored,
      fps: fps > 0 ? Double(fps) : -1,
      bitrate: dataRate > 0 ? Double(dataRate).rounded() : -1,
      bitDepth: Double(bitDepth),
      transfer: transfer,
      durationMs: ms(timeRange.duration))
  }

  private static func readAudio(_ track: AVAssetTrack) async throws -> ProbeAudio? {
    let formats = try await track.load(.formatDescriptions)
    guard let format = formats.first,
          let asbd = CMAudioFormatDescriptionGetStreamBasicDescription(format)?.pointee else { return nil }
    return ProbeAudio(
      codec: audioCodec(asbd.mFormatID),
      sampleRate: asbd.mSampleRate,
      channels: Double(asbd.mChannelsPerFrame))
  }

  static func ms(_ time: CMTime) -> Double {
    time.isNumeric ? (time.seconds * 1000).rounded() : -1
  }

  private static func videoCodec(_ subtype: FourCharCode) -> String {
    switch subtype {
    case kCMVideoCodecType_H264: return "h264"
    case kCMVideoCodecType_HEVC, kCMVideoCodecType_HEVCWithAlpha: return "hevc"
    default: return fourCC(subtype)
    }
  }

  private static func audioCodec(_ id: AudioFormatID) -> String {
    switch id {
    case kAudioFormatMPEG4AAC, kAudioFormatMPEG4AAC_HE, kAudioFormatMPEG4AAC_HE_V2: return "aac"
    case kAudioFormatOpus: return "opus"
    default: return fourCC(id)
    }
  }

  private static func fourCC(_ code: FourCharCode) -> String {
    let bytes = [24, 16, 8, 0].map { UInt8((code >> $0) & 0xff) }
    return (String(bytes: bytes, encoding: .ascii) ?? "\(code)").trimmingCharacters(in: .whitespaces).lowercased()
  }
}
