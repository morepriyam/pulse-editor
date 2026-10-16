<div align="center">

<!-- logo: docs/assets/logo.svg (alt="pulse-editor logo", width="120") -->

# @mieweb/pulse-editor

**Native video editing for React Native and Expo: trim, join, crop, rotate and speed up clips, with a live preview that matches the export. No FFmpeg.**

![iOS 16.4+](https://img.shields.io/badge/iOS-16.4%2B-000000?logo=apple&logoColor=white)
![Android API 29+](https://img.shields.io/badge/Android-API%2029%2B-3DDC84?logo=android&logoColor=white)
![Nitro Modules 0.37](https://img.shields.io/badge/Nitro%20Modules-0.37-8A2BE2)
![AVFoundation](https://img.shields.io/badge/iOS-AVFoundation-147EFB)
![Media3 1.11.1](https://img.shields.io/badge/Android-Media3%201.11.1-4285F4)
![No FFmpeg](https://img.shields.io/badge/FFmpeg-none-brightgreen)
![License: MIT](https://img.shields.io/badge/license-MIT-blue)

[Methods](docs/methods.md) • [Benchmarks](docs/benchmarks.md) • [Differences](docs/differences.md) • [Timeline editor](docs/timeline-editor.md) • [Migration](docs/migration.md) • [Roadmap](docs/roadmap.md)

</div>

pulse-editor is a video editing engine for React Native and Expo apps, written as a [Nitro module](https://nitro.margelo.com/) in Swift and Kotlin. It edits on the phone with each platform's own media stack, AVFoundation on iOS and Media3 Transformer on Android, so there is no FFmpeg binary to ship. It joins clips into one MP4 with trims, rotate, flip, crop, speed and mute; converts imports (HDR included) into the recorder's format; renders thumbnails; extracts audio for Whisper; and plays the edited draft in a native `<PulsePreview>` view that shows the frames the export will have. It is the engine behind [Pulse](https://github.com/mieweb/pulse), an Expo app for recording and editing short videos on the device.

<!-- demo: docs/assets/demo.gif (alt="A draft of several clips played in PulsePreview, trimmed and sped up, then exported with merge"; ≤ 5 MB, 320 px wide) -->

## Why pulse-editor

- 🚫 **No FFmpeg.** FFmpegKit (`ffmpeg-kit-react-native`) is retired and its repo archived; pulse-editor uses only the system frameworks: AVFoundation (Core Media, Core Video, VideoToolbox) on iOS, Media3 (Transformer, Effect, Inspector) on Android.
- 🎯 **The preview matches the export.** `<PulsePreview>` and `merge` take the same clips and options. A seek showed the export's frame 80 of 80 times on a Galaxy S24 Ultra and 77 of 80 on an iPhone 17 Pro Max (Release); every miss was in a 2× clip.
- 🔊 **In sync everywhere.** Audio − video within 0.1 ms on every merge path, read the way browsers (FFmpeg) and Apple players (AVFoundation) read it; a 2× clip is under a frame.
- ⚡ **Fast on the phone.** A 2-minute, 20-clip join takes 1.6 s on the iPhone (Debug) and 5.8 s on the S24 (release APK).
- 🧩 **Typed and cancellable.** Nitro specs with TypeScript types, progress that never goes backwards, and `AbortController` cancel that deletes partial output.

## When to use it, and when not

**Use it** when your React Native or Expo app records or imports phone video and needs to cut, join and export it on the device, show the edited draft before exporting, or feed the audio to Whisper.

**Look elsewhere (for now)** if you need:
- **`npm install`:** it isn't on npm yet (0.1.0). Today you add it as a git submodule.
- **A ready-made editor screen:** pulse-editor has no editor UI yet; a [timeline editor](docs/timeline-editor.md) is planned.
- **Any codec or container:** it reads what the phone's own decoders read and exports H.264 + AAC MP4 (`conform` with `copyVideo: true` keeps the source's video codec). Filters, text, music, transitions and GIFs aren't in the API.
- **Bare React Native, Expo Go, web, or older phones:** it's tested only in Pulse's Expo SDK 57 development build (native code doesn't run in Expo Go), on iOS 16.4+ and Android 10+.

## Compared with the alternatives

Checked 2026-10-14 against each project's own README, docs and npm page.

| | pulse-editor | [react-native-video-trim](https://github.com/maitrungduc1410/react-native-video-trim) | [ffmpeg-kit-react-native](https://github.com/arthenica/ffmpeg-kit) |
|---|---|---|---|
| Media engine | AVFoundation (iOS), Media3 (Android) | depends on FFmpegKit (the `min` package by default, per its install guide) | FFmpeg |
| Status | 0.1.0, not on npm | 8.2.4 on npm (2026-10-05) | retired (binaries removed in 2025); repo archived 2026-07-02; npm package deprecated |
| Editor UI | not yet ([planned](docs/timeline-editor.md)) | native trimmer screen with a thumbnail timeline | none |
| Join clips | `merge`, with per-clip trims and edits | `merge`, among its headless APIs | through FFmpeg commands |
| Live preview of the edited, multi-clip draft | `<PulsePreview>` | not listed in its README | none |
| Head-to-head numbers | against Pulse's fork of react-native-video-trim ([Benchmarks](docs/benchmarks.md), [Differences](docs/differences.md)) | — | — |

Other packages people find: [react-native-video-processing](https://github.com/shahen94/react-native-video-processing) (last npm release 2022-09-08), FFmpegKitNext (ffmpeg-kit's README names it a source-only continuation), and [react-native-compressor](https://github.com/numandev1/react-native-compressor) (compression, a different job). Where pulse-editor is slower than Pulse's react-native-video-trim fork, and why: [Differences](docs/differences.md).

## ✨ Features

| | |
|---|---|
| 🔎 **[`probe`](docs/methods.md#probe)**: a file's metadata without decoding it (codec, rotation, mirror, fps, bitrate, HDR transfer, the audio track Pulse reads) | 🎬 **[`merge`](docs/methods.md#merge)**: clips into one faststart MP4 with trims, rotate, flip, crop, speed (natural pitch) and mute; copies what it can, renders only what it must; cancellable |
| 🔄 **[`conform`](docs/methods.md#conform)**: one import into the recorder's format, HDR tone-mapped to SDR, mirrors fixed; a file whose sound can't be decoded is rejected, never imported silent | 🖼️ **[`thumbnail`](docs/methods.md#thumbnail)**: the exact frame at a time, with the clip's edit drawn by merge's own geometry |
| 🎙️ **[`extractAudio`](docs/methods.md#extractaudio)**: 16 kHz mono PCM for Whisper, decoded in memory, no file | ▶️ **[`<PulsePreview>`](docs/methods.md#pulsepreview)**: a native view that plays the draft as `merge` will export it; seeks and edits resolve when the frame is on screen |

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
  onStatus={({ durationMs, clipStartsMs, ready, error }) => {}}
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
| `thumbnail(uri, { timeMs?, maxWidth?, maxHeight?, quality?, rotation?, flipped?, crop? })` | A JPEG cover of the exact frame, edit applied (defaults: first frame, no size limit, quality 0.8) | `{ uri, width, height, timeMs }` |
| `extractAudio(uri, { sampleRate })` | 16-bit mono PCM in memory (`sampleRate` defaults to 16000), for whisper.rn's `transcribeData` | `{ data: ArrayBuffer, sampleRate, durationMs }` |
| `<PulsePreview clips options onStatus onTime onReady />` | Plays the draft with every edit, as merge would export it | a ref: `play()`, `pause()`, `seek(ms)` (resolves with the frame's time once it's on screen), `setScrubbing(bool)` |

Every type, path and test log: [Methods](docs/methods.md).

## 📊 Benchmarks

Headline numbers, Debug builds unless marked, from Pulse's on-device bench; RNVT is Pulse's react-native-video-trim fork. Full tables, methods and run IDs: [Benchmarks](docs/benchmarks.md); where RNVT is faster or the platforms differ, and why: [Differences](docs/differences.md).

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

Pulse is moving every native video method it uses from its react-native-video-trim (RNVT) fork into pulse-editor, **one method at a time**: added here, switched over in the app, tested on the phones. Every device result per step: [Migration](docs/migration.md).

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

- **Next: the [timeline editor](docs/timeline-editor.md)**, one screen replacing the clip preview and RNVT's per-clip editor, played by `<PulsePreview>`. The preview's status already reports each clip's start (`clipStartsMs`); its first commit films lip sync during preview playback.
- **Open:** #13 (draft) keeps Android preview drawing at full rate while playing; it waits for an S24 run.
- **After the migration:** per-clip audio (waveform and captions), a cover selector, Whisper tuning ([Roadmap](docs/roadmap.md)).

## ❓ FAQ

**How do I trim and merge videos in React Native without FFmpeg?**
Pass the clips, each with its trim and edit, to `merge`: it uses AVFoundation on iOS and Media3 Transformer on Android, copies what it can and re-encodes only what it must.

**What should I use now that FFmpegKit is retired?**
If your app trims, joins, crops, rotates or changes the speed of phone recordings, pulse-editor covers that with the platform frameworks, as long as a git submodule works for you until it's on npm. If you need FFmpeg's filters or codecs the phone can't decode, you still need an FFmpeg build.

**Does it work with Expo?**
Yes, in a development build: Pulse uses it on Expo SDK 57 through Expo's autolinking. It doesn't run in Expo Go, and bare React Native isn't tested.

**Can I install it from npm?**
Not yet. Version 0.1.0 is consumed as a git submodule (see [Install](#-install)).

**Does the preview really match the export?**
It's tested screenshot by screenshot against the export of the same draft on the same device: 80 of 80 seeks on the S24, 77 of 80 on the iPhone (Release), every miss in a 2× clip.

**Is there an editor UI?**
Not yet. pulse-editor is the engine; a timeline editor built on `<PulsePreview>` is next ([plan](docs/timeline-editor.md)).

## 🔬 How it's tested

- **On the phones:** a bench screen in Pulse's debug build (kept out of the repo) runs the same drafts through pulse-editor and RNVT, with recorder-shaped clips that carry each frame's number and flash/click sync clips; outputs are pulled to a Mac and checked for lip sync, duration, faststart and FFmpeg decode errors ([how it's measured](docs/benchmarks.md)).
- **The preview** is compared screenshot by screenshot with the export of the same draft on the same device ([`<PulsePreview>` tests](docs/methods.md#tested-4)).
- **Off the phones:** the iOS code on macOS against FFmpeg references, the Android code on the Android 17 emulator, the iOS Simulator ([Methods](docs/methods.md)).

## 🛠️ Development

Pulse consumes this repo as a git submodule at `modules/pulse-editor` and reads its TypeScript source directly, so there's no build step.

- **Where to edit:** make changes in the submodule inside Pulse, then commit and push here.
- **When the spec changes:** after editing `src/PulseEditor.nitro.ts` or `src/PulsePreview.nitro.ts`, run `yarn install` and `yarn nitrogen`. Commit the regenerated `nitrogen/generated/` files, because Pulse builds from source and needs them.
- **Updating Pulse:** in Pulse, `git add modules/pulse-editor` to record the new commit.
- **Minimum OS versions:** they follow the app's (iOS 16.4, Android 10 / API 29). API 29 is the lowest where Media3 tone-maps HDR (OpenGL); newer APIs are used behind per-method version checks.

## 🤝 Contributing

Issues and pull requests are welcome, especially device results from phones not listed above. ⭐ Star the repo to follow it to its first npm release.

## 📄 License

MIT
