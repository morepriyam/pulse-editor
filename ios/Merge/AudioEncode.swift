import AVFoundation

/// Decode an asset's audio timeline and encode it as one AAC track. Gaps in the timeline come out
/// as real silence, which is why the join uses this for muted clips: the output's audio is then
/// continuous instead of relying on players to honour an empty stretch in the edit list.
enum AudioEncode {
  static func run(_ asset: AVAsset, audio: MergeAudio, to output: URL, progress: @escaping @Sendable (Double) -> Void) async throws {
    try? FileManager.default.removeItem(at: output)
    let tracks = try await asset.loadTracks(withMediaType: .audio)
    let duration = try await asset.load(.duration)

    let reader = try AVAssetReader(asset: asset)
    let readerOutput = AVAssetReaderAudioMixOutput(audioTracks: tracks, audioSettings: EncodeSettings.pcm(audio))
    readerOutput.alwaysCopiesSampleData = false
    reader.add(readerOutput)

    // MP4, not M4A: in an MP4 the encoder's priming (2112 samples) goes into the edit list, which
    // the join's export carries over. From an M4A it's lost on export: Apple players still trim
    // it, but FFmpeg-based ones (Chrome, servers) then play the audio 44 ms late.
    let writer = try AVAssetWriter(outputURL: output, fileType: .mp4)
    let input = AVAssetWriterInput(mediaType: .audio, outputSettings: EncodeSettings.aac(audio))
    input.expectsMediaDataInRealTime = false
    writer.add(input)

    try await SampleTransfer.run(
      reader: reader, writer: writer, pairs: [(readerOutput, input)], duration: duration, progress: progress)
  }
}
