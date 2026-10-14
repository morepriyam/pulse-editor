<div align="center">

# @mieweb/pulse-editor

**The native video engine behind [Pulse](https://github.com/mieweb/pulse): fast, simple, and built only on each platform's own media stack.**

![iOS 16.4+](https://img.shields.io/badge/iOS-16.4%2B-000000?logo=apple&logoColor=white)
![Android API 29+](https://img.shields.io/badge/Android-API%2029%2B-3DDC84?logo=android&logoColor=white)
![Nitro Modules 0.37](https://img.shields.io/badge/Nitro%20Modules-0.37-8A2BE2)
![AVFoundation](https://img.shields.io/badge/iOS-AVFoundation-147EFB)
![Media3 1.11.1](https://img.shields.io/badge/Android-Media3%201.11.1-4285F4)
![No FFmpeg](https://img.shields.io/badge/FFmpeg-none-brightgreen)
![License: MIT](https://img.shields.io/badge/license-MIT-blue)

[Methods](docs/methods.md) • [Benchmarks](docs/benchmarks.md) • [Differences](docs/differences.md) • [Timeline editor](docs/timeline-editor.md) • [Migration](docs/migration.md) • [Roadmap](docs/roadmap.md)

</div>

## ✨ Features

| | |
|---|---|
| 🔎 **[`probe`](docs/methods.md#probe)**: a file's metadata without decoding it (codec, rotation, mirror, fps, bitrate, HDR transfer, the audio track Pulse reads) | 🎬 **[`merge`](docs/methods.md#merge)**: clips into one faststart MP4 with trims, rotate, flip, crop, speed (natural pitch) and mute; copies what it can, renders only what it must; cancellable |
| 🔄 **[`conform`](docs/methods.md#conform)**: one import into the recorder's format, HDR tone-mapped to SDR, mirrors fixed; a file whose sound can't be decoded is rejected, never imported silent | 🖼️ **[`thumbnail`](docs/methods.md#thumbnail)**: the exact frame at a time, with the clip's edit drawn by merge's own geometry |
| 🎙️ **[`extractAudio`](docs/methods.md#extractaudio)**: 16 kHz mono PCM for Whisper, decoded in memory, no file | ▶️ **[`<PulsePreview>`](docs/methods.md#pulsepreview)**: a native view that plays the draft as `merge` will export it; seeks and edits resolve when the frame is on screen |

- 🚫 **No FFmpeg** on either platform: AVFoundation (Core Media, Core Video, VideoToolbox) on iOS, Media3 (Transformer, Effect, Inspector, Inspector frame) on Android.
- ⚡ **A [Nitro module](https://nitro.margelo.com/):** Swift and Kotlin, typed specs, no Objective-C++ shim.
- 📦 **Expo autolinking:** Pulse (Expo SDK 57) links it from its `modules/` folder; bare React Native isn't tested.
- 🎯 **In sync everywhere:** audio − video within 0.1 ms on every merge path (under a frame with a 2× clip), read the way browsers (FFmpeg) and Apple players (AVFoundation) read it ([Benchmarks](docs/benchmarks.md#lip-sync-and-output-checks)).

## 📱 Platforms

| | iOS | Android |
|---|---|---|
| Minimum | iOS 16.4 | Android 10 (API 29), the lowest where Media3 tone-maps HDR |
| Media stack | AVFoundation | Media3 1.11.1 |
| Native code | Swift | Kotlin |
| Run on | iPhone 17 Pro Max (iOS 26.5.1), iOS Simulator, macOS | Galaxy S24 Ultra (Android 16), Pixel 6a (Android 17 beta), Android 17 emulator |

## 📦 Install

Not on npm yet (version 0.1.0). Pulse adds the repo as a git submodule and reads its TypeScript source directly, so there's no build step:

```sh
git submodule add https://github.com/morepriyam/pulse-editor.git modules/pulse-editor
npm install react-native-nitro-modules@^0.37.1
```

Pulse then points TypeScript and Metro at the source (`tsconfig.json` `paths`, `metro.config.js` resolver); Expo's autolinking picks up the native code from `modules/`:

```json
"paths": { "@mieweb/pulse-editor": ["./modules/pulse-editor/src/index.tsx"] }
```

## 🚀 Quick start

```tsx
import { merge, PulsePreview, type MergeClip, type MergeOptions } from '@mieweb/pulse-editor';

const options: MergeOptions = {
  width: 1080, height: 1920, fps: 30,         // the canvas
  bitrate: 5_000_000,                         // bps for anything encoded
  audio: { sampleRate: 48000, channels: 1 },  // the recorder's layout
};
const clips: MergeClip[] = [
  { uri: 'file:///…/a.mp4', startMs: 0, endMs: 0, speed: 1, muted: false, rotation: 0, flipped: false }, // whole clip
  { uri: 'file:///…/b.mp4', startMs: 500, endMs: 4200, speed: 2, muted: false, rotation: 90, flipped: true },
];

// The preview shows exactly what the export will be. Keep `clips` stable (useMemo): a new array rebuilds.
<PulsePreview
  style={{ flex: 1 }}
  clips={clips}
  options={options}
  onStatus={({ durationMs, ready, error }) => {}}
  onTime={(timeMs, playing) => {}}
  onReady={(ref) => ref.play()}
/>;

// The export: the same clips and options.
const controller = new AbortController();
const { uri, durationMs, encoded } = await merge(clips, options, {
  onProgress: (p) => {},       // 0–1, never goes backwards
  signal: controller.signal,   // abort = cancel: deletes partial output, rejects "Merge cancelled"
});
```

## 🧩 API at a glance

| Call | What it does | Returns |
|---|---|---|
| `probe(uri)` | Reads metadata without decoding | `{ durationMs, video?, audio? }` |
| `merge(clips, options, { onProgress, signal })` | Joins clips in order, each with its edit | `{ uri, durationMs, encoded, bitrate }` |
| `conform(uri, options, { onProgress, signal })` | One import into the recorder's format (`copyVideo: true` converts only the audio) | `{ uri, durationMs, encoded, bitrate }` |
| `thumbnail(uri, { timeMs, maxWidth, maxHeight, quality, rotation?, flipped?, crop? })` | A JPEG cover of the exact frame, edit applied | `{ uri, width, height, timeMs }` |
| `extractAudio(uri, sampleRate = 16000)` | 16-bit mono PCM in memory, for whisper.rn's `transcribeData` | `{ data: ArrayBuffer, sampleRate, durationMs }` |
| `<PulsePreview clips options onStatus onTime onReady />` | Plays the draft with every edit, as merge would export it | a ref: `play()`, `pause()`, `seek(ms)` (resolves when the frame is on screen), `setScrubbing(bool)` |

Every type, path and test log: [Methods](docs/methods.md).

## 📊 Benchmarks

Headline numbers, Debug builds unless marked, from Pulse's on-device bench. Full tables, methods and run IDs: [Benchmarks](docs/benchmarks.md); where RNVT is faster or the platforms differ, and why: [Differences](docs/differences.md).

| | iPhone 17 Pro Max | Galaxy S24 Ultra | Pixel 6a |
|---|---|---|---|
| Lip sync, worst audio − video (2× clip) | 0.0–0.1 ms (−25.0) · 10-13, 10-14 | 0.0 ms (−17.5) · 10-13 | 0.0 ms (−17.5) · 10-14 |
| 2-minute join, 20 clips (RNVT) | 1.6 s (1.2 s) · 10-13 | 8.6 s (23.2 s); release APK 5.8 s · 10-13 | — |
| Edited draft: trim, rotate + flip, crop, 2×, 0.5× muted (RNVT) | 3.2 s (2.9 s) · 10-13 | 4.2 s (8.2 s) · 10-13 | — |
| iOS exports read by FFmpeg (20 / 40 clips) | 53 → 0 / 107 → 0 decode errors (#14) · 10-14 | — | — |
| `conform`, 1080p re-encode | 5.0–7.1× realtime · 10-07 | 4.3–7.2× realtime · 10-07 | — |
| `thumbnail`, per cover | 22–50 ms · 10-07 | median 15 ms; a file's first 144–516 ms · 10-13 | — |
| `extractAudio`, 2-minute draft | 690 ms (Release 391 ms) · 10-13 | 1.0 s · 10-13 | — |
| `probe`, median first read | 8 ms · 10-13 | 24 ms · 10-13 | — |
| Preview seeks on the export's frame | 77 of 80, Release · 10-13; misses only in the 2× clip · 10-14 | 80 of 80 · 10-13 | 20 of 20 per draft, 20 clips 30 of 30, 40 clips 38 of 38 · 10-14 |
| Preview seek p90 (target p95 ≤ 100 ms) | 56–64 ms, Release · 10-13 | 202–281 ms, accepted · 10-13 | — |
| Preview while dragging (≥ 20/s) | 54–62 pictures/s, Release · 10-13 | ~31 seeks landed/s (drag layer) · 10-13 | — |
| Preview playback (≥ 29 fps) | 30.1–30.2 fps · 10-13 | 28.7–29.2 fps · 10-13 | ~22 fps (24–27 with #13, a draft) · 10-13/14 |

Dates are 2026. The S24 hasn't run the 2026-10-14 changes (#12, #14) yet.

## 🗺️ Status & roadmap

Pulse is moving every native video method it uses from its react-native-video-trim (RNVT) fork into pulse-editor, **one method at a time**: added here, switched over in the app, tested on the phones. Full status, with every device result per step: [Migration](docs/migration.md).

| # | Step | Replaces (RNVT) | iOS | Android |
|---|---|---|---|---|
| 1 | `probe` | `probeVideo`, `isValidFile` | ✅ | ✅ |
| 2 | `merge`: join, trims, mute, cancel | `merge` (copy path) | ✅ | ✅ |
| 3 | `merge`: edited clips (rotate, flip, crop, 2×, 0.5×) | `merge` with clip edits | ✅ | ✅ |
| 4 | `merge`: full encode; RNVT fallback removed | the re-encode path, the FFmpeg merge | ✅ | ✅ |
| 5 | `extractAudio` → Whisper captions | `extractAudio` | ✅ | ✅ |
| 6 | `conform`: import normalization, HDR | `compress`, `cancelCompress` | ✅ | ✅ |
| 7 | `thumbnail` | `getFrameAt` | ✅ | ✅ |
| 8 | `<PulsePreview>`: composition player | (needed by the editor) | ✅ | ✅ (paused seek and play after a paused seek: accepted Media3 limits) |
| 9 | Timeline editor UI | `showEditor` | 📋 | 📋 |
| 10 | File helpers to `expo-file-system` | `deleteFile`, `cleanFiles`, `saveToDocuments` | ✅ | ✅ (the sweep not yet run on Android) |
| 11 | **Last commit:** remove the fork and FFmpeg, then merge | the fork | 📋 | 📋 |

✅ done · ⏳ left to do · 📋 not started.

- **Next: the [timeline editor](docs/timeline-editor.md)**, one screen replacing the clip preview and RNVT's per-clip editor, played by `<PulsePreview>`. Its first engine PR adds per-clip slot starts to the preview's status; its first commit films lip sync during preview playback.
- **Open:** #13 (draft) keeps Android preview drawing at full rate while playing; it waits for an S24 run.
- **After the migration:** per-clip audio (waveform and captions), a cover selector, Whisper tuning ([Roadmap](docs/roadmap.md)).

## 🔬 How it's tested

- **On the phones:** a bench screen in Pulse's debug build (kept out of the repo) runs the same drafts through pulse-editor and RNVT, with recorder-shaped clips that carry each frame's number and flash/click sync clips; outputs are pulled to a Mac and checked for lip sync, duration, faststart and FFmpeg decode errors ([how it's measured](docs/benchmarks.md)).
- **The preview** is compared screenshot by screenshot with the export of the same draft on the same device ([`<PulsePreview>` tests](docs/methods.md#tested-4)).
- **Off the phones:** the iOS code on macOS against FFmpeg references, the Android code on the Android 17 emulator, the iOS Simulator ([Methods](docs/methods.md)).

## 🛠️ Development

Pulse consumes this repo as a git submodule at `modules/pulse-editor` and reads its TypeScript source directly, so there's no build step.

- **Where to edit:** make changes in the submodule inside Pulse, then commit and push here.
- **When the spec changes:** after editing `src/PulseEditor.nitro.ts`, run `yarn install` and `yarn nitrogen`. Commit the regenerated `nitrogen/generated/` files, because Pulse builds from source and needs them.
- **Updating Pulse:** in Pulse, `git add modules/pulse-editor` to record the new commit.
- **Minimum OS versions:** they follow the app's (iOS 16.4, Android 10 / API 29). API 29 is the lowest where Media3 tone-maps HDR (OpenGL); newer APIs are used behind per-method version checks.

## 📄 License

MIT
