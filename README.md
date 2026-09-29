# @mieweb/pulse-editor

The native video engine behind [Pulse](https://github.com/mieweb/pulse): fast, simple, and built only on each platform's own media stack.

- **iOS:** AVFoundation (Core Media, Core Video, VideoToolbox)
- **Android:** Media3 (Transformer, Effect, Inspector)
- **No FFmpeg** on either platform
- A [Nitro module](https://nitro.margelo.com/): Swift and Kotlin, typed specs, no Objective-C++ shim
- Works with Expo and bare React Native through autolinking

## Status: migrating from react-native-video-trim

Pulse is moving every native video method it uses from its react-native-video-trim fork into pulse-editor, **one method at a time**. Each method is added here, switched over in the app, and tested on a device before the next one starts. The trim editor UI moves last.

| Step | pulse-editor | Replaces (RNVT) | iOS | Android |
|---|---|---|---|---|
| 1 | `probe` | `probeVideo`, `isValidFile` | ✅ Tested on device | ✅ Tested on device (Galaxy S24 Ultra, Android 16) |
| 2 | `merge`: fast join (trims, mute), cancel | `merge` (no-re-encode path), `onMergeProgress` | ✅ Tested on device | ✅ Tested on device (join, trims, mute); cancel on emulator |
| 3 | `merge`: edited clips (rotate, flip, crop, speed): selective render | `merge` with `clipEdits` | ✅ Tested on device (iPhone 17 Pro Max) | ✅ Tested on device (one hardware encode) |
| 4 | `merge`: full encode (clips off the recorder's format, lower bitrate) | the re-encode fallback | ✅ Tested on device | ✅ Tested on emulator |
| 5 | `conform`: import normalization, including HDR | `compress`, `cancelCompress` | Planned | Planned |
| 6 | `extractAudio` (feeds whisper.rn's `transcribeData` / `detectSpeechData`) | `extractAudio` | ✅ Tested on device | ✅ Tested on device |
| 7 | `thumbnail` | `getFrameAt` | Planned | Planned |
| 8 | `<PulsePreview>`: composition player | the preview screen | Planned | Planned |
| 9 | Multi-clip editor UI, in React Native on `<PulsePreview>` | `showEditor` (RNVT's native trim screen) | **Last** | **Last** |

Pulse still falls back to RNVT if pulse-editor's `merge` throws, until both platforms have been tested on real devices.

RNVT's file helpers (`deleteFile`, `cleanFiles`, `saveToDocuments`) don't move here; Pulse uses `expo-file-system` for those.

Every step keeps Pulse's output unchanged: the same saved clip edits (`editState`), the same 1080×1920 H.264 export, and the same recorder format. Existing drafts keep working.

## `probe`

```ts
import { probe } from '@mieweb/pulse-editor';

const result = await probe('file:///…/clip.mp4');
```

Reads a local file's metadata without decoding it. Accepts a `file://` URI or a bare path, and rejects when the platform can't open the file.

```ts
type ProbeResult = {
  durationMs: number;
  video?: {
    codec: string;        // 'h264' | 'hevc', or the platform's id for anything else
    width: number;        // coded (before rotation)
    height: number;
    rotation: number;     // clockwise: 0 | 90 | 180 | 270
    mirrored: boolean;    // iOS only; always false on Android
    fps: number;          // average; -1 when unknown
    bitrate: number;      // bits per second; -1 when unknown
    bitDepth: number;     // 8, or 10 for HDR sources
    transfer: 'sdr' | 'hlg' | 'pq';
    durationMs: number;   // the video track's own (the container's on Android)
  };
  audio?: {
    codec: string;        // 'aac' | 'opus', or the platform's id for anything else
    sampleRate: number;
    channels: number;
  };
};
```

`video` and `audio` are missing when the file has no such track.

- **iOS:** AVFoundation's async loaders, requesting several properties per `load(...)` call as Apple recommends. About **2 ms per clip**.
- **Android:** Media3 Inspector's `MetadataRetriever`, one per file, closed after use, with its results awaited inside `Promise.async`.

### Tested on iOS

- All 24 of Pulse's fixture clips read correctly on iOS, including HDR (HLG and PQ), Opus audio, variable frame rate, 120 fps slow motion, screen recordings, and 90° and 270° rotations. A file that isn't media is rejected.
- In the Pulse app on an iPhone:
  - portrait, landscape and HDR imports come out upright and correctly letterboxed;
  - a draft mixing recordings and imports exports quickly;
  - the seed drafts export;
  - preview sizing is correct;
  - a recording interrupted by an alarm is kept.
- Builds with no warnings on iOS (device SDK) and Android.
- Android hasn't been run on a device yet.

## `merge`

```ts
import { merge } from '@mieweb/pulse-editor';

const controller = new AbortController();
const result = await merge(clips, options, {
  onProgress: (p) => {},        // 0–1, never goes backwards
  signal: controller.signal,    // abort = cancel: stops, deletes partial output, rejects "Merge cancelled"
});
```

Joins clips, in order, into one faststart MP4, applying each clip's edit.

```ts
type MergeClip = {
  uri: string;               // file:// URI or bare path
  startMs: number;           // trim window in the source; endMs <= startMs = whole clip
  endMs: number;
  speed: number;             // 0.25–4, natural pitch
  muted: boolean;
  rotation: number;          // clockwise: 0 | 90 | 180 | 270
  flipped: boolean;          // mirror horizontally, after the rotation
  crop?: { x: number; y: number; w: number; h: number }; // normalized, in the rotated + flipped frame
};
type MergeOptions = {
  width: number; height: number; fps: number;           // the canvas, e.g. 1080×1920 @ 30
  bitrate: number;                                       // bps for anything encoded
  audio: { sampleRate: number; channels: number };       // layout for anything encoded
};
type MergeResult = { uri: string; durationMs: number; encoded: boolean; bitrate: number };
```

### How a merge runs (iOS)

| Path | When | What happens |
|---|---|---|
| **Fast join** | Every clip shares one H.264/AAC format that fits the canvas, at most 1.6× the chosen bitrate, and no clip has a rendered edit | `AVMutableComposition` with frame-accurate edit-list trims, passthrough export. No video decoding or encoding. |
| **Selective** | Same, but some clips are rotated, flipped, cropped or sped up | Only those clips are rendered, into the draft's own format, two at a time, then everything is joined. |
| **Full encode** | The clips don't share a format that fits, or the selective path fails | The whole timeline is encoded once onto the upright canvas at the chosen bitrate. |

- **Rendering:** each clip gets a trimmed slot, `scaleTimeRange` for speed, and a video-composition instruction for rotate → flip → crop → letterboxed fit. Encoding is `AVAssetReader` → `AVAssetWriter` (H.264 High, BT.709, keyframe every 2 s), with frames filled to a constant 30 fps.
- **Audio:** copied when possible. If the timeline has a gap (a muted clip, or one without sound) or mixes encoders, the audio alone is re-encoded with real silence in the gaps. The reason: a gap left as an MP4 empty edit plays as silence in Apple players, but FFmpeg-based players (Chrome, most servers) skip it and play the next clip's audio early.
- **Re-encoded audio keeps its sync everywhere:** it's encoded to an MP4 (not M4A) before the join, so the AAC encoder's priming (2112 samples) is carried into the output's edit list. From an M4A that edit was lost: Apple players still trimmed it, but FFmpeg-based ones played the audio 44 ms late.
- **Bitrate allowance:** 1.6× covers recorder overshoot. Recordings aimed at 5 Mbps average 6–7 Mbps; see Pulse #241.

### How a merge runs (Android)

Media3 can't mix copied and re-encoded clips in one export, and its copy mode isn't frame-accurate at a trimmed start (it begins at the previous keyframe). So Android has two paths:

| Path | When | What happens |
|---|---|---|
| **Fast join** | No clip is trimmed or has a rendered edit, and every clip shares one H.264/AAC format that fits, at most 1.6× the chosen bitrate | One `Transformer` export of an `EditedMediaItemSequence` with `setTransmuxVideo`. Audio is copied too, unless a clip is muted or has no sound; then audio alone is encoded, with generated silence. |
| **Full encode** | Anything else, and the fast join's fallback | One hardware encode of the whole timeline: `ClippingConfiguration` trims, `SpeedParameters` (natural pitch), `ScaleAndRotateTransformation` / `Crop` / `Presentation` (letterbox) effects on the upright frame, `setFrameRate` cap, H.264 at the chosen bitrate via `DefaultEncoderFactory`. Audio is mixed to the recorder's layout, with an explicit 5.1 → stereo downmix. |

- **Threading:** `Transformer` runs on the main looper, as Media3 requires. Progress comes from polling `getProgress`; cancel calls `Transformer.cancel()` and deletes the partial file.
- **Verification:** every output is checked with `probe` (H.264, the canvas, the expected duration) and must be faststart.
- **Known difference from iOS:** slowed-down clips come out at a variable frame rate (Media3's frame rate setting only caps it). They play correctly.

### Tested
- **Android, on an emulator (Android 17, arm64, software codecs), same fixtures as iOS:**
  - fast join of 3 clips without re-encoding, and with a muted last clip (silent);
  - trims; every rotation, flip and crop, and combinations (frames identical to iOS);
  - 2× and 0.5× with natural pitch;
  - a mixed HEVC / 60 fps / 4K / landscape / 5.1-audio draft;
  - cancel.
  - Every output is faststart. The emulator's software encoder writes Baseline profile and didn't hold a 2 Mbps target; real hardware encoders still need checking.
- **Android, on a Galaxy S24 Ultra (Android 16) in Pulse:**
  - fast join of 3 recordings without re-encoding in 0.65 s, exactly the clips' total length;
  - trims, rotate + flip, crop, 2× and 0.5×, and a muted clip: one hardware encode in 1.7–2.1 s, lengths within a frame of the edits, 5.6–6.2 Mbps at a 5 Mbps target;
  - slowed clips come out at a variable frame rate (the 0.5× part plays at ~15 fps), as noted above.
- **iOS, on device (Pulse):**
  - fast join of recorded drafts, trims and a muted middle clip;
  - seed drafts of 2 and 8 minutes (0.66 s and 2.4–5.2 s);
  - cancel;
  - full encode on an iPhone 17 Pro Max: Pulse's mixed seed draft (20 clips: HEVC, 60 fps, 4K, landscape, 5.1 audio) and wild-imports draft (12 clips: HDR, VFR, 120 fps, Opus, no audio) come out H.264 High 1080×1920 at a constant 30 fps, SDR, 4.2–4.9 Mbps, faststart, audio in sync; 120.00 s for 120.00 s of clips, and 95.67 s for 96.00 s (the odd-timing clips resampled to 30 fps);
  - on an iPhone 17 Pro Max: a 3-clip join in 90 ms; trim + 2× in 0.4 s and rotate + flip + crop + 0.5× + mute in 2.2–2.5 s, only the edited clips rendered; lengths within a frame of the edits.
- **Audio sync, every iOS path** (join, trim, muted clip, selective, selective with a muted clip, full encode), run on macOS with real iPhone recordings: the output's audio matches the original recording within 0.1 ms read by AVFoundation and by FFmpeg. Confirmed on an iPhone export.
- **iOS, on macOS with the same code**, using a clip tagged like an iPhone recording:
  - every rotation, flip and crop, and combinations, checked frame by frame;
  - 2× and 0.5× have exact durations, a constant 30 fps and natural pitch;
  - muted first, middle and last clips are silent in FFmpeg;
  - mixed HEVC / 60 fps / 4K / landscape drafts encode to H.264 1080×1920 at 30 fps, faststart;
  - a 2 Mbps target gives 1.98 Mbps;
  - cancel mid-render leaves no files.

## `extractAudio`

```ts
import { extractAudio } from '@mieweb/pulse-editor';

const { data, sampleRate, durationMs } = await extractAudio(uri); // default sampleRate 16000
// data: ArrayBuffer of 16-bit signed little-endian mono PCM (empty when there's no audio track)
await whisperContext.transcribeData(data, options);   // whisper.rn, no WAV file
await vadContext.detectSpeechData(data);
```

What whisper.cpp takes, decoded in memory: no FFmpeg, no intermediate file, and whisper.rn's JSI reads the `ArrayBuffer` Nitro hands back without a copy on our side.

- **iOS:** one `AVAssetReader` pass, where Apple's converter decodes, downmixes and resamples (filtered) together.
- **Android:** Media3's extractor feeding `MediaCodec`, then mono and a band-limited polyphase resampler (windowed sinc; Media3's Sonic interpolates linearly, which aliases). Priming samples are dropped by timestamp, since some decoders already drop them; the output stops at the track's duration.
- **Level:** channels are summed at −3 dB each (Apple's downmix), and the whole buffer is turned down only if that would clip, never up.

### Tested
- **iOS on macOS, Android on the emulator**, against an FFmpeg reference (`aresample` with a 64-tap filter) on speech clips with 48 kHz stereo, 48 kHz 5.1 and 44.1 kHz mono AAC, and a file without audio:
  - Android matches the reference's length and speech onset to the sample; iOS is within 6 samples (0.4 ms) and 1 sample;
  - waveform correlation 0.99 (iOS) and envelope correlation 1.00 (Android); no clipping on 5.1;
  - a 7 s clip takes ~10 ms on macOS and ~0.3 s on the emulator's software decoder.
- **In Pulse, on an iPhone 17 Pro Max and a Galaxy S24 Ultra**, transcribing merged exports with whisper.rn 0.7.4:
  - the PCM's length matches the video's (14,799 vs 14,800 ms; 12,648 vs 12,660 ms); 80–180 ms on the iPhone and 0.4–0.8 s on the S24 for 6–15 s of audio;
  - the transcripts match a local run of the same whisper.cpp (1.9.3) with whisper.rn's exact settings on the same exports, and the iOS extractor's PCM matches an FFmpeg reference decode to 0.1 ms.

## Development

Pulse consumes this repo as a git submodule at `modules/pulse-editor` and reads its TypeScript source directly, so there's no build step.

- **Where to edit:** make changes in the submodule inside Pulse, then commit and push here.
- **When the spec changes:** after editing `src/PulseEditor.nitro.ts`, run `yarn install` and `yarn nitrogen`. Commit the regenerated `nitrogen/generated/` files, because Pulse builds from source and needs them.
- **Updating Pulse:** in Pulse, `git add modules/pulse-editor` to record the new commit.
- **Minimum OS versions:** they follow the app's (iOS 16.4, Android API 24). Newer APIs are used behind per-method version checks, so older phones keep working.

## License

MIT
