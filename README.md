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
| 1 | `probe` | `probeVideo`, `isValidFile` | ✅ Tested on device | Built, not device-tested |
| 2 | `merge`: fast join (trims, mute), cancel | `merge` (no-re-encode path), `onMergeProgress` | ✅ Tested on device | Next |
| 3 | `merge`: edited clips (rotate, flip, crop, speed): selective render | `merge` with `clipEdits` | Built, checked on macOS; device test pending | Next (one full encode) |
| 4 | `merge`: full encode (clips off the recorder's format, lower bitrate) | the re-encode fallback | Built, checked on macOS; device test pending | Next |
| 5 | `conform`: import normalization, including HDR | `compress`, `cancelCompress` | Planned | Planned |
| 6 | `thumbnail`, `extractAudio` | `getFrameAt`, `extractAudio` | Planned | Planned |
| 7 | `<PulsePreview>`: composition player | the preview screen | Planned | Planned |
| 8 | Multi-clip editor UI, in React Native on `<PulsePreview>` | `showEditor` (RNVT's native trim screen) | **Last** | **Last** |

Until a method is complete on both platforms, Pulse falls back to RNVT for whatever pulse-editor rejects (today: `merge` on Android).

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
- **Bitrate allowance:** 1.6× covers recorder overshoot. Recordings aimed at 5 Mbps average 6–7 Mbps; see Pulse #241.

### Tested
- **iOS, on device (Pulse):**
  - fast join of recorded drafts, trims and a muted middle clip;
  - seed drafts of 2 and 8 minutes (0.66 s and 2.4–5.2 s);
  - cancel.
- **iOS, on macOS with the same code**, using a clip tagged like an iPhone recording:
  - every rotation, flip and crop, and combinations, checked frame by frame;
  - 2× and 0.5× have exact durations, a constant 30 fps and natural pitch;
  - muted first, middle and last clips are silent in FFmpeg;
  - mixed HEVC / 60 fps / 4K / landscape drafts encode to H.264 1080×1920 at 30 fps, faststart;
  - a 2 Mbps target gives 1.98 Mbps;
  - cancel mid-render leaves no files.

## Development

Pulse consumes this repo as a git submodule at `modules/pulse-editor` and reads its TypeScript source directly, so there's no build step.

- **Where to edit:** make changes in the submodule inside Pulse, then commit and push here.
- **When the spec changes:** after editing `src/PulseEditor.nitro.ts`, run `yarn install` and `yarn nitrogen`. Commit the regenerated `nitrogen/generated/` files, because Pulse builds from source and needs them.
- **Updating Pulse:** in Pulse, `git add modules/pulse-editor` to record the new commit.
- **Minimum OS versions:** they follow the app's (iOS 16.4, Android API 24). Newer APIs are used behind per-method version checks, so older phones keep working.

## License

MIT
