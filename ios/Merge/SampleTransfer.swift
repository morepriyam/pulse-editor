import AVFoundation

/// Moves samples from an AVAssetReader's outputs into an AVAssetWriter's inputs until every
/// output is drained, then finishes the file. Each output/input pair is fed with
/// `requestMediaDataWhenReady` on one serial queue, so all outputs are consumed together (a
/// reader with several outputs stalls if one isn't read) and appends, cancelReading and
/// cancelWriting never run concurrently, as AVFoundation requires. Cancelling the calling task
/// stops the transfer and deletes the partial file.
///
/// `frameDuration` makes the first pair's video constant-frame-rate: a video composition only
/// emits a frame where its source has one (a 0.5× clip comes out at 15 fps even with a 1/30 frame
/// duration), so each gap is filled by repeating the previous frame at that rate.
///
/// Every track runs to `duration`: the reader stops at the last real sample, so a stream that ends
/// early is padded (the last frame held, or silence), e.g. a muted clip at the end of a timeline.
enum SampleTransfer {
  static func run(
    reader: AVAssetReader, writer: AVAssetWriter,
    pairs: [(output: AVAssetReaderOutput, input: AVAssetWriterInput)],
    duration: CMTime, frameDuration: CMTime? = nil, progress: @escaping @Sendable (Double) -> Void
  ) async throws {
    let session = Session(reader: reader, writer: writer, pairs: pairs)
    guard reader.startReading() else { throw reader.error ?? MergeError.failed("Couldn't start reading.") }
    guard writer.startWriting() else {
      reader.cancelReading()
      throw writer.error ?? MergeError.failed("Couldn't start writing.")
    }
    writer.startSession(atSourceTime: .zero)
    let seconds = duration.seconds

    await withTaskCancellationHandler {
      await withTaskGroup(of: Void.self) { group in
        for index in session.pairs.indices {
          group.addTask {
            await withCheckedContinuation { (done: CheckedContinuation<Void, Never>) in
              let pair = session.pairs[index]
              let fill = index == 0 ? frameDuration : nil
              pair.input.requestMediaDataWhenReady(on: session.queue) {
                let pair = session.pairs[index]
                let state = session.states[index]
                while pair.input.isReadyForMoreMediaData {
                  // Repeated frames queued to fill a gap go first.
                  if !state.pending.isEmpty {
                    guard pair.input.append(state.pending.removeFirst()) else {
                      pair.input.markAsFinished()
                      session.reader.cancelReading()
                      done.resume()
                      return
                    }
                    continue
                  }
                  guard session.reader.status == .reading, session.writer.status == .writing,
                        let sample = pair.output.copyNextSampleBuffer() else {
                    // Drained: pad to the full duration once, then finish.
                    if !state.padded, session.reader.status == .completed, let last = state.last {
                      state.padded = true
                      state.pending = Self.padding(after: last, to: duration, video: fill)
                      if !state.pending.isEmpty { continue }
                    }
                    pair.input.markAsFinished()
                    done.resume()
                    return
                  }
                  if let fill, let previous = state.last {
                    state.pending = Self.repeats(of: previous, before: sample, every: fill)
                  }
                  state.last = sample
                  state.pending.append(sample)
                  guard pair.input.append(state.pending.removeFirst()) else {
                    pair.input.markAsFinished()
                    session.reader.cancelReading()
                    done.resume()
                    return
                  }
                  // The first pair (video, when there is one) drives the progress.
                  if index == 0, seconds > 0 {
                    progress(CMSampleBufferGetPresentationTimeStamp(sample).seconds / seconds)
                  }
                }
              }
            }
          }
        }
      }
    } onCancel: {
      session.queue.async { session.reader.cancelReading() }
    }

    if Task.isCancelled {
      session.queue.sync { writer.cancelWriting() }
      throw CancellationError()
    }
    if writer.status == .failed {
      throw writer.error ?? MergeError.failed("Writing failed.")
    }
    guard reader.status == .completed else {
      session.queue.sync { writer.cancelWriting() }
      throw reader.error ?? MergeError.failed("Reading failed.")
    }
    await writer.finishWriting()
    guard writer.status == .completed else {
      throw writer.error ?? MergeError.failed("Writing failed.")
    }
    progress(1)
  }

  /// Copies of `previous` retimed to fill the gap before `next`, one every `step`.
  private static func repeats(of previous: CMSampleBuffer, before next: CMSampleBuffer, every step: CMTime) -> [CMSampleBuffer] {
    repeats(of: previous, until: CMSampleBufferGetPresentationTimeStamp(next), every: step)
  }

  private static func repeats(of previous: CMSampleBuffer, until end: CMTime, every step: CMTime) -> [CMSampleBuffer] {
    var t = CMSampleBufferGetPresentationTimeStamp(previous) + step
    var frames: [CMSampleBuffer] = []
    // Only whole missing frames: a gap under 1.5 steps is ordinary timing jitter.
    while (end - t).seconds >= step.seconds * 0.5 {
      var timing = CMSampleTimingInfo(duration: step, presentationTimeStamp: t, decodeTimeStamp: .invalid)
      var copy: CMSampleBuffer?
      if CMSampleBufferCreateCopyWithNewTiming(
        allocator: nil, sampleBuffer: previous, sampleTimingEntryCount: 1,
        sampleTimingArray: &timing, sampleBufferOut: &copy) == noErr, let copy {
        frames.append(copy)
      }
      t = t + step
    }
    return frames
  }

  /// What runs a drained stream up to `end`: the last video frame held at the frame rate, or
  /// silent PCM in the last audio buffer's format.
  private static func padding(after last: CMSampleBuffer, to end: CMTime, video step: CMTime?) -> [CMSampleBuffer] {
    if let step { return repeats(of: last, until: end, every: step) }
    guard let format = CMSampleBufferGetFormatDescription(last),
          let asbd = CMAudioFormatDescriptionGetStreamBasicDescription(format)?.pointee,
          asbd.mFormatID == kAudioFormatLinearPCM, asbd.mBytesPerFrame > 0 else { return [] }
    let start = CMSampleBufferGetPresentationTimeStamp(last) + CMSampleBufferGetDuration(last)
    var remaining = Int(((end - start).seconds * asbd.mSampleRate).rounded())
    var t = start
    var buffers: [CMSampleBuffer] = []
    while remaining > 0 {
      let frames = min(remaining, 4096)
      let bytes = frames * Int(asbd.mBytesPerFrame)
      var block: CMBlockBuffer?
      var sample: CMSampleBuffer?
      guard CMBlockBufferCreateWithMemoryBlock(
              allocator: nil, memoryBlock: nil, blockLength: bytes, blockAllocator: nil,
              customBlockSource: nil, offsetToData: 0, dataLength: bytes,
              flags: kCMBlockBufferAssureMemoryNowFlag, blockBufferOut: &block) == noErr, let block,
            CMBlockBufferFillDataBytes(with: 0, blockBuffer: block, offsetIntoDestination: 0, dataLength: bytes) == noErr,
            CMAudioSampleBufferCreateReadyWithPacketDescriptions(
              allocator: nil, dataBuffer: block, formatDescription: format, sampleCount: frames,
              presentationTimeStamp: t, packetDescriptions: nil, sampleBufferOut: &sample) == noErr, let sample
      else { break }
      buffers.append(sample)
      remaining -= frames
      t = t + CMTime(value: CMTimeValue(frames), timescale: CMTimeScale(asbd.mSampleRate))
    }
    return buffers
  }

  /// The reader/writer pair, touched only on `queue` once the transfer starts.
  private final class Session: @unchecked Sendable {
    let reader: AVAssetReader
    let writer: AVAssetWriter
    let pairs: [(output: AVAssetReaderOutput, input: AVAssetWriterInput)]
    let states: [PairState]
    let queue = DispatchQueue(label: "pulse-editor.transfer")

    init(reader: AVAssetReader, writer: AVAssetWriter, pairs: [(output: AVAssetReaderOutput, input: AVAssetWriterInput)]) {
      self.reader = reader
      self.writer = writer
      self.pairs = pairs
      self.states = pairs.map { _ in PairState() }
    }
  }

  /// Per-pair frame-filling state, touched only on the session queue.
  private final class PairState {
    var last: CMSampleBuffer?
    var pending: [CMSampleBuffer] = []
    var padded = false
  }
}

/// Encoder settings shared by everything pulse-editor encodes.
enum EncodeSettings {
  /// Standard AAC layouts; any other channel count is encoded as stereo.
  static func audioChannels(_ requested: Double) -> Int {
    layoutTag(Int(requested)) != nil ? Int(requested) : 2
  }

  /// Linear PCM for reading audio, in the layout it will be encoded with.
  static func pcm(_ audio: MergeAudio) -> [String: Any] {
    let channels = audioChannels(audio.channels)
    return [
      AVFormatIDKey: kAudioFormatLinearPCM, AVSampleRateKey: audio.sampleRate,
      AVNumberOfChannelsKey: channels, AVChannelLayoutKey: layout(channels),
      AVLinearPCMBitDepthKey: 16, AVLinearPCMIsFloatKey: false,
      AVLinearPCMIsBigEndianKey: false, AVLinearPCMIsNonInterleaved: false,
    ]
  }

  static func aac(_ audio: MergeAudio) -> [String: Any] {
    let channels = audioChannels(audio.channels)
    return [
      AVFormatIDKey: kAudioFormatMPEG4AAC, AVSampleRateKey: audio.sampleRate,
      AVNumberOfChannelsKey: channels, AVChannelLayoutKey: layout(channels),
    ]
  }

  /// H.264 High, the chosen average bitrate, a keyframe every 2 s, BT.709 SDR.
  static func h264(size: CGSize, fps: Double, bitrate: Double) -> [String: Any] {
    [
      AVVideoCodecKey: AVVideoCodecType.h264,
      AVVideoWidthKey: Int(size.width),
      AVVideoHeightKey: Int(size.height),
      AVVideoCompressionPropertiesKey: [
        AVVideoAverageBitRateKey: Int(bitrate),
        AVVideoProfileLevelKey: AVVideoProfileLevelH264HighAutoLevel,
        AVVideoH264EntropyModeKey: AVVideoH264EntropyModeCABAC,
        AVVideoExpectedSourceFrameRateKey: Int(fps.rounded()),
        AVVideoMaxKeyFrameIntervalDurationKey: 2,
      ],
      AVVideoColorPropertiesKey: [
        AVVideoColorPrimariesKey: AVVideoColorPrimaries_ITU_R_709_2,
        AVVideoTransferFunctionKey: AVVideoTransferFunction_ITU_R_709_2,
        AVVideoYCbCrMatrixKey: AVVideoYCbCrMatrix_ITU_R_709_2,
      ],
    ]
  }

  private static func layoutTag(_ channels: Int) -> AudioChannelLayoutTag? {
    [1: kAudioChannelLayoutTag_Mono, 2: kAudioChannelLayoutTag_Stereo, 6: kAudioChannelLayoutTag_MPEG_5_1_D][channels]
  }

  private static func layout(_ channels: Int) -> Data {
    var layout = AudioChannelLayout()
    layout.mChannelLayoutTag = layoutTag(channels)!
    return Data(bytes: &layout, count: MemoryLayout<AudioChannelLayout>.size)
  }
}
