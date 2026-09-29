import AVFoundation

/// Decode an asset's audio timeline and encode it as one AAC track. Gaps in the timeline come out
/// as real silence, which is why the join uses this for muted clips: a gap left as an MP4 empty
/// edit plays as silence in Apple players, but FFmpeg-based players (Chrome, most servers) ignore
/// mid-track empty edits and shift the following audio early.
enum AudioEncode {
  static func run(_ asset: AVAsset, audio: MergeAudio, to output: URL, progress: @escaping @Sendable (Double) -> Void) async throws {
    try? FileManager.default.removeItem(at: output)
    let tracks = try await asset.loadTracks(withMediaType: .audio)
    let duration = try await asset.load(.duration)
    let channels = layoutTag(Int(audio.channels)) != nil ? Int(audio.channels) : 2
    let layout = layoutData(layoutTag(channels)!)

    let reader = try AVAssetReader(asset: asset)
    let pcm: [String: Any] = [
      AVFormatIDKey: kAudioFormatLinearPCM, AVSampleRateKey: audio.sampleRate,
      AVNumberOfChannelsKey: channels, AVChannelLayoutKey: layout,
      AVLinearPCMBitDepthKey: 16, AVLinearPCMIsFloatKey: false,
      AVLinearPCMIsBigEndianKey: false, AVLinearPCMIsNonInterleaved: false,
    ]
    let readerOutput = AVAssetReaderAudioMixOutput(audioTracks: tracks, audioSettings: pcm)
    readerOutput.alwaysCopiesSampleData = false
    reader.add(readerOutput)

    let writer = try AVAssetWriter(outputURL: output, fileType: .m4a)
    let input = AVAssetWriterInput(mediaType: .audio, outputSettings: [
      AVFormatIDKey: kAudioFormatMPEG4AAC, AVSampleRateKey: audio.sampleRate,
      AVNumberOfChannelsKey: channels, AVChannelLayoutKey: layout,
    ])
    input.expectsMediaDataInRealTime = false
    writer.add(input)

    guard reader.startReading() else { throw reader.error ?? MergeError.failed("Couldn't read the audio.") }
    guard writer.startWriting() else { throw writer.error ?? MergeError.failed("Couldn't write the audio.") }
    writer.startSession(atSourceTime: .zero)

    let pipe = Pipe(reader: reader, output: readerOutput, writer: writer, input: input)
    await withTaskCancellationHandler {
      await withCheckedContinuation { (done: CheckedContinuation<Void, Never>) in
        pipe.input.requestMediaDataWhenReady(on: pipe.queue) {
          while pipe.input.isReadyForMoreMediaData {
            guard pipe.reader.status == .reading, let sample = pipe.output.copyNextSampleBuffer() else {
              pipe.input.markAsFinished()
              done.resume()
              return
            }
            pipe.input.append(sample)
            let t = CMSampleBufferGetPresentationTimeStamp(sample)
            if duration.seconds > 0 { progress(t.seconds / duration.seconds) }
          }
        }
      }
    } onCancel: {
      pipe.queue.async { pipe.reader.cancelReading() }
    }

    if Task.isCancelled || reader.status == .cancelled {
      pipe.queue.sync { pipe.writer.cancelWriting() }
      throw CancellationError()
    }
    guard reader.status == .completed else {
      writer.cancelWriting()
      throw reader.error ?? MergeError.failed("Couldn't read the audio.")
    }
    await writer.finishWriting()
    guard writer.status == .completed else {
      throw writer.error ?? MergeError.failed("Couldn't write the audio.")
    }
  }

  /// The reader/writer pair, used only on `queue` once the transfer starts: AVAssetWriter requires
  /// appends, cancelReading and cancelWriting to be serialized, which is what makes sharing it
  /// with the cancellation handler safe.
  private final class Pipe: @unchecked Sendable {
    let reader: AVAssetReader
    let output: AVAssetReaderOutput
    let writer: AVAssetWriter
    let input: AVAssetWriterInput
    let queue = DispatchQueue(label: "pulse-editor.audio-encode")

    init(reader: AVAssetReader, output: AVAssetReaderOutput, writer: AVAssetWriter, input: AVAssetWriterInput) {
      self.reader = reader
      self.output = output
      self.writer = writer
      self.input = input
    }
  }

  /// Standard AAC layouts; anything else is encoded as stereo.
  private static func layoutTag(_ channels: Int) -> AudioChannelLayoutTag? {
    [1: kAudioChannelLayoutTag_Mono, 2: kAudioChannelLayoutTag_Stereo, 6: kAudioChannelLayoutTag_MPEG_5_1_D][channels]
  }

  private static func layoutData(_ tag: AudioChannelLayoutTag) -> Data {
    var layout = AudioChannelLayout()
    layout.mChannelLayoutTag = tag
    return Data(bytes: &layout, count: MemoryLayout<AudioChannelLayout>.size)
  }
}
