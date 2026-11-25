import AVFoundation

@main struct VoiceoverMixRegression {
  static func main() async throws {
    let root = URL(fileURLWithPath: CommandLine.arguments[1])
    let video = root.appendingPathComponent("source.mp4")
    let narration = root.appendingPathComponent("narration.caf").path
    let clips = (0..<3).map { _ in MergeClip(uri: video.path, startMs: 0, endMs: 20_000,
      speed: 1, muted: false, rotation: 0, flipped: false, crop: nil) }
    let media = try await Probe.load(video)
    let options = MergeOptions(width: 160, height: 96, fps: 30, bitrate: 500_000,
      audio: MergeAudio(sampleRate: 48_000, channels: 1), voiceover: nil, sourceMuted: false, voiceoverMuted: false)
    let long = [VoiceoverAudio(uri: narration, timelineStartMs: 0, sourceStartMs: 0, sourceEndMs: 60_000)]
    let short = [
      VoiceoverAudio(uri: narration, timelineStartMs: 2000, sourceStartMs: 1000, sourceEndMs: 3000),
      VoiceoverAudio(uri: narration, timelineStartMs: 5000, sourceStartMs: 4000, sourceEndMs: 6000),
      VoiceoverAudio(uri: narration, timelineStartMs: 18_500, sourceStartMs: 2000, sourceEndMs: 5000),
      VoiceoverAudio(uri: narration, timelineStartMs: 39_000, sourceStartMs: 10_000, sourceEndMs: 12_000),
      VoiceoverAudio(uri: narration, timelineStartMs: 58_000, sourceStartMs: 100, sourceEndMs: 5100),
    ]
    for (name, takes, sourceMuted, voiceoverMuted) in [
      ("minute", long, false, false), ("takes", short, false, false),
      ("source-muted", short, true, false), ("voiceover-muted", short, false, true),
      ("both-muted", short, true, true),
    ] {
      var mix = options
      mix.voiceover = takes; mix.sourceMuted = sourceMuted; mix.voiceoverMuted = voiceoverMuted
      // The exact composition played by PulsePreview, encoded only for inspection.
      let target = RenderTarget(size: CGSize(width: 160, height: 96), transform: .identity,
        canvas: CGSize(width: 160, height: 96), fps: 30, bitrate: 500_000, audio: mix.audio)
      let preview = try await Timeline.compose(clips.map { ($0, media) }, target: target,
        voiceover: takes, sourceMuted: sourceMuted, voiceoverMuted: voiceoverMuted)
      precondition(Probe.ms(preview.duration) == 60_000)
      if !preview.audioTracks.isEmpty {
        try await AudioEncode.run(preview.composition, audio: mix.audio,
          to: root.appendingPathComponent("\(name)-preview.mp4"), progress: { _ in })
      }
      let result = try await Merge.run(clips, mix, progress: MergeProgress { _ in })
      let output = root.appendingPathComponent("\(name)-export.mp4")
      try? FileManager.default.removeItem(at: output)
      try FileManager.default.moveItem(at: fileURL(result.uri), to: output)
      precondition(abs(result.durationMs - 60_000) < 50)
      print("\(name): \(result.durationMs) ms, preview tracks=\(preview.audioTracks.count)")
    }
    // A sped-up middle clip shortens only picture/source sound. Narration stays on the draft
    // clock across both boundaries and through the final selective render-and-join path.
    let retimed = [clips[0], MergeClip(uri: video.path, startMs: 0, endMs: 20_000,
      speed: 2, muted: false, rotation: 0, flipped: false, crop: nil), clips[2]]
    let crossing = [
      VoiceoverAudio(uri: narration, timelineStartMs: 19_500, sourceStartMs: 1000, sourceEndMs: 4000),
      VoiceoverAudio(uri: narration, timelineStartMs: 28_500, sourceStartMs: 2000, sourceEndMs: 5000),
      VoiceoverAudio(uri: narration, timelineStartMs: 48_000, sourceStartMs: 3000, sourceEndMs: 7000),
    ]
    var mix = options
    mix.voiceover = crossing; mix.sourceMuted = true
    let target = RenderTarget(size: CGSize(width: 160, height: 96), transform: .identity,
      canvas: CGSize(width: 160, height: 96), fps: 30, bitrate: 500_000, audio: mix.audio)
    let preview = try await Timeline.compose(retimed.map { ($0, media) }, target: target,
      voiceover: crossing, sourceMuted: true)
    precondition(Probe.ms(preview.duration) == 50_000)
    try await AudioEncode.run(preview.composition, audio: mix.audio,
      to: root.appendingPathComponent("retimed-preview.mp4"), progress: { _ in })
    let result = try await Merge.run(retimed, mix, progress: MergeProgress { _ in })
    let output = root.appendingPathComponent("retimed-export.mp4")
    try? FileManager.default.removeItem(at: output)
    try FileManager.default.moveItem(at: fileURL(result.uri), to: output)
    precondition(abs(result.durationMs - 50_000) < 50)
    print("retimed: \(result.durationMs) ms, narration spans sped-up clip boundaries")
  }
}
