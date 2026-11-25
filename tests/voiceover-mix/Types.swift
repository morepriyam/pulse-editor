// The Nitro transport structs only. Compile the shipping AVFoundation implementation without
// the React Native bridge so timing/mixing can be exercised on a Mac with generated fixtures.
import AVFoundation

enum Transfer { case sdr, hlg, pq }
struct ProbeVideo { let codec: String; let width, height, rotation: Double; let mirrored: Bool; let fps, bitrate, bitDepth: Double; let transfer: Transfer; let durationMs: Double }
struct ProbeAudio { let codec: String; let sampleRate, channels: Double }
struct ProbeResult { let durationMs: Double; let video: ProbeVideo?; let audio: ProbeAudio? }
struct MergeCrop { let x, y, w, h: Double }
struct MergeClip { let uri: String; let startMs, endMs, speed: Double; let muted: Bool; let rotation: Double; let flipped: Bool; let crop: MergeCrop? }
struct MergeAudio { let sampleRate, channels: Double }
struct VoiceoverAudio { let uri: String; let timelineStartMs, sourceStartMs, sourceEndMs: Double }
struct MergeOptions { let width, height, fps, bitrate: Double; let audio: MergeAudio; var voiceover: [VoiceoverAudio]?; var sourceMuted: Bool?; var voiceoverMuted: Bool? }
struct MergeResult { let uri: String; let durationMs: Double; let encoded: Bool; let bitrate: Double }

func fileURL(_ uri: String) -> URL {
  if uri.hasPrefix("file://") {
    let path = String(uri.dropFirst("file://".count))
    return URL(fileURLWithPath: path.removingPercentEncoding ?? path)
  }
  return URL(fileURLWithPath: uri)
}
