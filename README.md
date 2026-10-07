# @mieweb/pulse-editor

The native video engine behind [Pulse](https://github.com/mieweb/pulse): fast, simple, and built only on each platform's own media stack.

- **iOS:** AVFoundation (Core Media, Core Video, VideoToolbox)
- **Android:** Media3 (Transformer, Effect, Inspector, Inspector frame)
- **No FFmpeg** on either platform
- A [Nitro module](https://nitro.margelo.com/): Swift and Kotlin, typed specs, no Objective-C++ shim
- Works with Expo and bare React Native through autolinking

## Status: migrating from react-native-video-trim

Pulse is moving every native video method it uses from its react-native-video-trim fork into pulse-editor, **one method at a time**. Each method is added here, switched over in the app, and tested on both phones. `conform` and `thumbnail` are switched over and tested on both phones (2026-10-07), macOS, the Android emulator and the iOS Simulator. `<PulsePreview>`, the player the timeline editor will be built on, shows the export's frames on both phones and meets its speed targets on the iPhone; on the S24 seeks, drags and edits are 2–3× slower than the targets (Media3's decode and composition per seek). RNVT's editor (`showEditor`) is now the only part of the fork Pulse still uses; it moves last.

✅ done · ⏳ left to do · 📋 not started. Devices: **iPhone 17 Pro Max** (iOS) and **Galaxy S24 Ultra, Android 16** (Android); "macOS" = the same iOS code run on a Mac against references; "emulator" = Android 17 emulator.

| # | Step | Replaces (RNVT) | iOS | Android |
|---|---|---|---|---|
| 1 | `probe` | `probeVideo`, `isValidFile` | ✅ iPhone: recordings, upright + letterboxed imports, HDR import, preview sizing, recording rescue · iOS: all 24 fixtures read correctly | ✅ S24: recordings (codec, rotation, fps, bitrate, audio); emulator: fixtures | 
| 2 | `merge`: join, trims, mute | `merge` (copy path) | ✅ iPhone: joins, trims, muted clip, real recordings; in sync in browsers and Photos (current numbers: [Benchmarks](#benchmarks-on-device)) | ✅ S24: joins, trims, muted clip, real recordings; in sync in both (see [Benchmarks](#benchmarks-on-device)) | 
|  | `merge`: cancel | (not possible in RNVT) | ✅ iPhone: cancel mid-merge; macOS: no files left behind | ✅ S24: settles 6–36 ms after the abort, no files left; emulator | 
| 3 | `merge`: edited clips (rotate, flip, crop, 2×, 0.5×) | `merge` with clip edits | ✅ iPhone: rotate + flip + crop + 0.5× + mute, trim + 2× (only edited clips rendered, lengths within a frame); macOS: every rotation/flip/crop frame by frame, exact durations, natural pitch | ✅ S24: rotate + flip, crop, 2×, 0.5×, mute (one hardware encode, lengths within a frame, 5.6–6.2 Mbps); emulator: frames identical to iOS | 
| 4 | `merge`: full encode | the re-encode path | ✅ iPhone: mixed (20 clips) and wild-imports (12 clips: HDR, VFR, Opus, no audio) seed drafts → H.264 1080×1920 30 fps, SDR, audio in sync; macOS: 2 Mbps target → 1.98 Mbps | ✅ S24: mixed draft (20 clips: HEVC, 60 fps, 4K, landscape) in 17.2–17.9 s and wild-imports draft (12 clips, HLG and PQ HDR included) in 13.8–14.1 s, exact lengths; emulator: mixed HEVC / 60 fps / 4K / landscape / 5.1 draft | 
|  | `merge`: RNVT fallback removed | the FFmpeg merge | ✅ in code: the app merges with pulse-editor only, and an error shows the retry state (tsc, lint, tests; not run on a device since) | ✅ same | 
| 5 | `extractAudio` → Whisper captions | `extractAudio` | ✅ iPhone: captions (extract 80–180 ms, VAD 128 ms, Whisper 123 ms); macOS: PCM vs FFmpeg reference within 0.1 ms; local whisper.cpp 1.9.3 gives the same transcripts | ✅ S24: captions (extract 0.4–0.8 s, Whisper 1.5 s on CPU); emulator: length and speech onset exact vs FFmpeg; local whisper.cpp gives the same transcripts | 
| | Cleanups from the whisper.rn audit (VAD comment, real CPU fallback) | | ✅ from whisper.rn 0.7.4's source, not run: the Whisper fallback now asks for the CPU (it only runs if the GPU context fails to load) | ✅ comments only (whisper.rn has no Android GPU backend) | 
| 6 | `conform`: import normalization, HDR | `compress`, `cancelCompress` | ✅ iPhone (2026-10-07): 42 inputs (the fixtures and the real-recording corpus, 4 already matched) all to contract, no decode errors, lip sync 0.0–0.1 ms, cancel settled 377 ms after the abort with nothing left; HDR tone-mapped (levels −7…−48 below the source, the same as RNVT within a level); 1080p re-encodes 5–7× realtime, 4K Dolby Vision 1.5×; faster than RNVT on 33 of 38 (slower on audio-only) · macOS: 51 of 51 sample files to contract | ⏳ S24 (2026-10-07): 38 of 38 converted, HLG / PQ / Dolby Vision included (the emulator couldn't decode them), lip sync 0.0 ms, cancel settled 15 ms, 1080p 4–7× realtime; RNVT's full conform fails on the phone by its own bug. Open: a corrupt source comes out with gaps (746 of 3424 frames; iOS conceals it), two re-encodes carry duplicate timestamps (a 23.976 fps HLG clip and a 4K HEVC one), the full-range level check (see `conform`) · emulator: every 8-bit input | 
| 7 | `thumbnail` | `getFrameAt` | ✅ iPhone (2026-10-07): 39 of 39 covers (8 edits, HDR, rotated, square, slow motion, screen recording, past the end, the corpus), every one at or above RNVT's match to the FFmpeg reference; RNVT fails past the end and on a < 1 s clip and picks wrong frames on two corpus clips; 22–50 ms per cover (10–20 ms slower than RNVT) · macOS + Simulator: every edit matches merge / FFmpeg | ⏳ S24 (2026-10-07): 39 of 39 covers (RNVT fails the < 1 s clip and ignores a mirror), the better match on most; but 160–600 ms per cover (RNVT 90–630: a player per call) — fine for one cover, too slow for a thumbnail strip · emulator: every edit matches RNVT and FFmpeg | 
| 8 | `<PulsePreview>`: composition player | (needed by the editor) | ✅ iPhone (2026-10-07, this code): seeks show the export's frame 79 of 80 (the 1: the sped-up-clip quirk), p95 61–63 ms; first frame 56–122 ms; edits on screen in 41–92 ms, all showing the edit; ~60 pictures/s while dragging; 30 fps, none dropped; play starts in 0 ms (was 169–301). By ear next: the first sound after a play straight from a seek (the audio tap sees it 106–135 ms after `play()`, the picture at +34) (see [`<PulsePreview>`](#pulsepreview)) | ⏳ S24 (2026-10-07): seeks show the export's frame 80 of 80, edits 8 of 8, 29–29.6 fps none dropped, first frame 119–481 ms; but seek p90 239–281 ms, 8–10 pictures/s while dragging, edits on screen 288–491 ms and play starts ~120 ms late — the speed targets are missed; 141 ms of a seek is Media3 decode + composition, 24 ms the view (see [`<PulsePreview>`](#pulsepreview)) · emulator: 60 of 60 in six runs | 
| 9 | Timeline editor UI (React Native), replacing the clip preview and the per-clip editor (see [The timeline editor](#the-timeline-editor-plan)) | `showEditor` | 📋 | 📋 | 
| 10 | File helpers to `expo-file-system` | `deleteFile`, `cleanFiles`, `saveToDocuments` | ✅ in code: `deleteFile` → `File.delete`; `cleanFiles` → a sweep of RNVT's `trimmedVideo*` files (Simulator: removed them, left other files); "Save to Files" dropped, Share's sheet already offers it | ✅ same code; the sweep not yet run on Android | 
| 11 | **Last commit:** remove the fork and FFmpeg (package, Podfile, Gradle, submodule), then merge | the fork | 📋 | 📋 | 

RNVT's file helpers (`deleteFile`, `cleanFiles`, `saveToDocuments`) don't move here: Pulse uses `expo-file-system` for the first two, and dropped its "Save to Files" button (the Share sheet has "Save to Files" on iOS).

Every step keeps Pulse's output unchanged: the same saved clip edits (`editState`), the same 1080×1920 H.264 export, and the same recorder format. Existing drafts keep working. Two deliberate behaviour changes so far: an import whose sound the phone can't decode is now rejected instead of imported silent (`conform`), and the export screen's "Save to Files" button is gone (Share covers it on iOS).

## The timeline editor (plan)

Where the migration is heading. Nothing here is built yet: it follows the steps above.

### Today: three screens
1. **Recorder.** Camera, record, import, and the clip bar (drag to reorder, drag to delete).
2. **Clip preview.** Tap a clip in the bar. Plays the draft across clips with edits applied, with a playhead on the clip bar, play/pause, ✂ edit, 🗑 delete and "Revert edits".
3. **Clip editor.** ✂ opens RNVT's full-screen editor on **one clip**: trim, crop, rotate, flip, mute, speed (presets and recent custom speeds), and undo/redo that reopens where you left off. Save stores the edit as settings (`editState`); nothing is encoded.

Editing a draft means going through screens 2 and 3 once per clip, and screen 3 can't show the clips around the one being edited.

### Target: two screens
1. **Recorder: unchanged.**
2. **Timeline editor**, replacing screens 2 and 3: every clip on one timeline, played by `<PulsePreview>` so the preview is exactly what exports.
   - **Keeps every current feature:** play/pause and scrubbing across clips; trim, crop, rotate, flip, mute and speed (presets and custom speeds) on any clip without leaving the timeline; revert edits; delete; reorder; undo/redo.
   - **Edits apply immediately, with undo as the safety net** (Pulse's editing style): no save step, and still stored as settings, rendered once by `merge` at export.
   - **New on the timeline:** each clip's thumbnails, and later its **audio waveform** (see where people speak, so trims land in the gaps between words) and captions, from per-clip audio (below).
   - **Later:** split a clip into two segments that share one original (Pulse #58).
   - Trim handles come last within the editor work.

### What each piece needs

| Piece | From |
|---|---|
| Playback across clips with edits, preview == export | `<PulsePreview>` (step 8) |
| Thumbnails along each clip | `thumbnail` (done) |
| Waveform and captions on the timeline | `extractAudio` (done) + per-clip audio ([after the migration](#after-the-migration-planned-features)) |
| Rendering the edits at export | `merge` (done) |

## After the migration (planned features)

New features, planned for after RNVT is gone. They aren't part of the migration PR.

### Per-clip audio: waveform and captions
Each clip's audio is analyzed once, when it's saved, and kept. The timeline and the export build from that.

1. **On save** (a recording finishes, or an import finishes converting), one job runs **`extractAudio` once** and writes, from the same buffer:
   - `{clip}.wav`: 16 kHz mono 16-bit PCM (~1.9 MB per minute, ~5% of the clip's video), for Whisper later;
   - `{clip}.wave`: peak + RMS per 10 ms, for the timeline waveform.

   One decode for both, never separate. On an iPhone 17 Pro Max this takes ~5–12 ms per second of audio, on a Galaxy S24 Ultra up to ~65 ms per second, in the background.
2. **Whisper runs later**, as a separate job, when the recorder has been idle a few seconds or the editor opens. whisper.rn reads the saved WAV directly (`transcribe(path)`, VAD `detectSpeech(path)`), with no second decode and no resampling. Words and speech regions are stored in the clip's own time, with the model that made them.
   - **It depends on having a model:** without one, clips still get their WAV and waveform, and when a model is downloaded, clips without words are queued.
   - **Switching models** re-runs Whisper from the saved WAVs; the old words stay until then.
3. **Queue rules:**
   - one clip at a time, and **nothing runs while the camera records**;
   - clips visible in the editor go first;
   - deleting a clip cancels its work and removes its files;
   - existing drafts fill in the first time they're opened.
4. **The timeline and the export build from edits**, so trims, speed, mute and reorder never re-analyze anything:
   - muted clips are skipped;
   - a word is kept when its midpoint is inside the trim window;
   - time maps as `clip start on the timeline + (word time − trim start) / speed`, the same maths as `merge`.

   The export no longer transcribes: its captions are ready when the merge finishes. Hand-edited captions stay draft-level, as today.

Transcribing each clip at its natural speed should also help recognition: whole-draft transcription didn't transcribe a quiet clip slowed to 0.5×, which gives words at 1× in a local run. Not tested in the app yet.

### Cover selector (thumbnail)
A screen to choose the pulse's final thumbnail: the poster that's uploaded with the video and shown on the draft card.

- **A frame from the video:** scrub the whole merged video after the merge (part of the export screen, which is being reworked) and pick the frame. The picked time is saved and the cover is rendered at full size (1080×1920) with `thumbnail`.
- **A picture from the device:** chosen with the platform's native photo picker (`expo-image-picker`, which Pulse already uses for imports).
- **Default** when nothing is picked: the video's first frame.
- **Much later:** a thumbnail-generation method (suggested covers) can plug into the same screen as a third source.

### Whisper tuning
- Test `maxThreads` 4 vs 6 on device (whisper.rn advises against using all cores; its default is 4 on phones with more than 4 cores).
- Test q8_0 models on Android: whisper.rn's guidance says q8 is faster on Android CPUs, and its 0.8 NPU support (Snapdragon 8 Gen 1 and newer) doesn't run q5 models.
- whisper.rn 0.8 for the Android NPU once it's stable.
- Ask whisper.rn to expose whisper.cpp options we could use: built-in VAD inside transcribe, `suppress_nst`, DTW word timestamps, `no_speech_thold`.

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
    mirrored: boolean;    // the display matrix mirrors the frame
    fps: number;          // average; -1 when unknown
    bitrate: number;      // bits per second; -1 when unknown
    bitDepth: number;     // 8, or 10 for HDR sources
    transfer: 'sdr' | 'hlg' | 'pq';
    durationMs: number;   // the video track's own
  };
  audio?: {
    codec: string;        // 'aac' | 'opus', or the platform's id for anything else
    sampleRate: number;
    channels: number;
  };
};
```

`video` and `audio` are missing when the file has no such track. `audio` describes the track Pulse reads: never Apple's spatial-audio APAC track (iPhone videos carry one next to the stereo AAC track), preferring an enabled AAC track. On Android, Media3 doesn't expose APAC tracks at all. `extractAudio` reads the same track.

- **iOS:** AVFoundation's async loaders, requesting several properties per `load(...)` call as Apple recommends. About **3 ms per clip** on a Mac.
- **Android:** Media3 Inspector's `MetadataRetriever`, one per file, closed after use, with its results awaited inside `Promise.async`; Dolby Vision reports its base-layer codec, and the video duration comes from the track header through `MediaExtractorCompat`.
- **Both, on 171 real files** (147 iPhone videos: Dolby Vision HLG, 4K60/120, screen recordings, messaging-app files; plus Pulse's fixtures): every field agrees across the two platforms and with FFprobe, except fps on 6 synthetic fixtures (all round to 30).

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
| **Fast join** | Every clip shares one H.264 format that fits the canvas (AAC audio, any sample rate or channel count), at most 1.6× the chosen bitrate, and no clip has a rendered edit | `AVMutableComposition` with edit-list trims on frame boundaries, passthrough export. No video decoding or encoding; the audio is re-encoded once when it comes from more than one clip (see Audio). |
| **Selective** | Same, but some clips are rotated, flipped, cropped or sped up, and no copied clip reorders frames (camera recordings don't) | Only those clips are rendered, into the draft's own format, two at a time, then everything is joined. |
| **Full encode** | The clips don't share a format that fits, a copied clip would reorder frames, or the selective path fails | Every clip is rendered onto the upright canvas at the chosen bitrate, two at a time, then they're joined. |

- **Rendering:** each clip gets a trimmed slot, `scaleTimeRange` for speed, and a video-composition instruction for rotate → flip → crop → letterboxed fit. Encoding is `AVAssetReader` → `AVAssetWriter` (H.264 High, BT.709, keyframe every 2 s, no B-frames), with frames filled to a constant 30 fps.
- **Why clip by clip:** rendering a whole timeline in one composition stalled AVFoundation's audio mix reader for good (cancel couldn't stop it) when a sped-up clip met a clip in another audio format, in 4 of 5 runs on macOS, and played that clip's audio at 1×. Rendered clip by clip, the same drafts finished 30 of 30 times, with the sped-up clip's audio in sync.
- **No B-frames:** camera recordings have none, and FFmpeg-based players (browsers, servers) mis-decoded a joined file mixing them with rendered clips that had them (reference-frame errors on a real recording). Now FFmpeg decodes it frame for frame, with no errors.
- **Errors** name the clip (`Clip 2 (IMG_1234.MOV): …`); a trim that starts past a clip's end is an error, not the whole clip. On both platforms JS gets the plain message, without Swift's type description or Kotlin's class name and stack trace. Merge files a killed app left in the caches folder are deleted after a day.
- **Audio:** a single clip's audio is copied. Audio from more than one clip, or with a gap (a muted clip, or one without sound), is re-encoded once over the whole timeline, with real silence in the gaps. Copied AAC needs one edit-list entry per clip to drop each clip's priming and end padding: Apple players honour that, FFmpeg-based ones (Chrome, servers) don't. Measured on macOS with Pulse's flash/click sync clips (`assets/dev/sync`), a 4-clip copied join was in sync for AVFoundation but drifted +10.7 ms per clip for FFmpeg (0 → 32 ms); re-encoded, both read 0.0 ms at every event. The cost is the AAC encode: a 2-minute join takes 3.4 s instead of 0.5 s on an M-series Mac (8 minutes: 13.6 s instead of 3 s); not yet timed on an iPhone. (Apple's faster encoder quality settings halve that but cost about 6 dB of signal-to-distortion, so they aren't used.) Because the audio is re-encoded into the draft's layout anyway, clips whose audio differs only in sample rate or channel count now join instead of sending the whole video to a full encode (macOS: a 48 / 44.1 kHz sync draft 12.8 s → 0.77 s, a 2× / 0.5× one 22.6 s → 3.8 s, both still in sync).
- **Trims on the join path snap to the nearest frame:** a copied cut inside a frame is shown by Apple players but dropped by FFmpeg-based ones, which then started the picture up to a frame (16.7 ms measured) after the sound. Rendered clips keep the exact trim.
- **Re-encoded audio keeps its sync everywhere:** it's encoded to an MP4 (not M4A) before the join, so the AAC encoder's priming (2112 samples) is carried into the output's edit list. From an M4A that edit was lost: Apple players still trimmed it, but FFmpeg-based ones played the audio 44 ms late.
- **Bitrate allowance:** 1.6× covers recorder overshoot. Recordings aimed at 5 Mbps average 6–7 Mbps; see Pulse #241.

### How a merge runs (Android)

Media3 can't mix copied and re-encoded clips in one export, and its copy mode isn't frame-accurate at a trimmed start (it begins at the previous keyframe). So Android has two paths:

| Path | When | What happens |
|---|---|---|
| **Fast join** | No clip is trimmed or has a rendered edit, and every clip shares one H.264 format that fits (SDR, 8-bit, at most 1.6× the chosen bitrate, and the same SPS/PPS as the first clip) | One `Transformer` export of an `EditedMediaItemSequence` with `setTransmuxVideo`: video is copied, audio is encoded (see audio sync below). |
| **Full encode** | Anything else, and the fast join's fallback | One hardware encode, with video and audio as parallel sequences. Video: `ClippingConfiguration` trims, `SpeedParameters` (natural pitch), `ScaleAndRotateTransformation` / `Crop` / `Presentation` (letterbox) effects on the upright frame, `setFrameRate` cap, H.264 at the chosen bitrate. Audio: each clip read from its file's start and cut to its window (see audio sync), mixed to the recorder's layout and resampled with the band-limited filter `extractAudio` uses, not Sonic's linear interpolation. Muted and silent clips are gaps of silence. |

- **Shared export code (`transcode/`), for merge and conform:** `Transformer` runs on the main looper, as Media3 requires. Decoder fallback is on, so a decoder that fails to start hands over to the next one. Encoding stays landscape plus a rotation tag (Media3's default, since more encoders support it). Progress comes from polling `getProgress`; cancel calls `Transformer.cancel()` and deletes the partial file.
- **Audio sync:** two Media3 gaps are closed, both measured with flash/click fixtures (audio minus video per event).
  - Each clip's decoded or copied AAC keeps the source encoder's end padding (up to 1024 samples), and Media3 only ever pads short audio, so every clip pushed the next clip's audio later (join of three 4 s clips: 0 → 10.7 → 21.4 ms). Each clip's audio is now cut to the clip's length (`TrimAudioProcessor`); in a join the audio is therefore encoded, because copied AAC can't be cut mid-frame.
  - Media3 declares the output's encoder delay as 1600 samples for `c2.android.aac.encoder` and 0 for any other encoder, but the emulator's encoder primes 2048 (audio 9.4 ms late). Each AAC encoder's priming is now measured once (a tone burst encoded and decoded back, `AacPriming`) and written as the encoder delay.
  - Media3's clipping drops whole AAC frames before a trimmed clip's start, which landed its audio late by the source's priming less where the next frame falls (7–21 ms for FFmpeg AAC, up to 48 ms for Apple AAC), the first trimmed clip shifting the whole track, and by other amounts after a speed change. So a full encode reads each clip's audio from its file's start (a parallel audio sequence) and `TrimAudioProcessor` skips exactly to the window.
  - After: every event within 0.0 ms in joins, 44.1/48 kHz mixes, clips whose audio track is 200 ms longer or shorter than the video, and trims (first or later clip, FFmpeg and Apple AAC, muted clips between); within 3 ms inside 2× and 0.5× clips, with nothing carried into the next clip.
- **Long drafts:** Media3 keeps 400 KB at the front of the file for the index (`moov`), which a draft longer than about 5.5 minutes outgrows (one chunk per sample, ~1.2 KB a second), so the index went to the end and the merge failed its faststart check. Extra space is now kept for the draft's length and the index is moved there after the export (`mdat` doesn't move, so no offsets change): an 8-minute, 80-clip draft joins in 48 s, faststart.
- **Joins check the encoder settings:** a join writes only the first clip's SPS/PPS, so a clip from another encoder setup decoded as garbage with no error (a Constrained Baseline clip after a High one). Clips join only with the same parameter sets (Media3's append rule: identical, or a level no higher); otherwise the draft is encoded.
- **5.1 → stereo** uses the coefficients measured from the iOS merge: front at full level, centre and surrounds at −3 dB, LFE left out (it used to be scaled down by 7.7 dB, and some layouts folded channels onto the wrong side).
- **Verification:** every output is checked with `probe` (H.264, the canvas, the expected duration) and must be faststart. A failed check names any encoder fallback Media3 applied; a clip that can't be read is named (`Clip 2 (IMG_1234.MOV): …`). When a join fails and falls back to an encode, the progress bar carries on from where it was. Merge files a killed app left in the cache are deleted after a day.
- **HDR clips** are tone-mapped to SDR with OpenGL, which needs the GPU's `GL_EXT_YUV_target`. Without it (the emulator), every HDR clip failed with "Video frame processing error"; such a device now reads HDR as SDR (Media3's `HDR_MODE_EXPERIMENTAL_FORCE_INTERPRET_HDR_AS_SDR`: it finishes, with flatter colours) and logs that it did. Not yet run on a device that lacks the extension: the emulator can't decode 10-bit HEVC at all, so HDR there still fails, now with "This phone couldn't decode one of the clips."
- **Apple players got the audio 44 ms early:** Media3's muxer doesn't write the AAC "roll" sample group (`sgpd`/`sbgp`, roll distance −1) that FFmpeg's does, and without it AVFoundation trims Apple's default 2112 priming samples on top of the edit list. Every Android export read 0.0 ms in FFmpeg but −44 ms in AVFoundation (Safari, Photos, iOS viewers), with Pulse's flash/click sync clips on the emulator. The roll group is now added after the export, inside the front `moov`, out of the free space after it (`mdat` doesn't move): 0.0 ms in both. If there's no room it's skipped and logged.
- **Joins feed the audio encoder in larger buffers:** Media3 fills each encoder input buffer before queueing it, and each queued buffer is a round trip to the codec's process (the default holds ~40 ms of PCM). In a join the audio is the only thing encoded, and a 256 KB buffer made joins 21–34% faster on the S24 (2-minute join 16.5 → 11.6 s, 8 minutes 66 → 44 s), sync unchanged. With video also encoding, the larger bursts slowed exports by 5–15%, so encodes keep the default. (Tried and dropped: setting codecs to their maximum operating rate, no change; decoding each clip's audio to a WAV first, 10–27% slower, since decoding wasn't the bottleneck.)
- **One-clip joins are copied:** a single-item composition ignores `setTransmuxVideo` and asks the encoder factory whether video needs encoding, and Media3's default factory says yes whenever encoder settings are given, so a one-clip draft was re-encoded (S24: 5.2 Mbps out of a 0.7 Mbps clip). A join now says no; encodes are unchanged. S24: the clip comes out at its own 0.7 Mbps, still in sync. When a join fails and falls back to an encode, the reason is logged.
- **Export errors are plain:** Media3's message can be a whole codec configuration dump; an export now fails with a short message per Media3 error code (couldn't decode a clip, couldn't encode, couldn't write the file…), and the full exception is logged (`adb logcat -s PulseEditor`), as are each encoder's measured priming and any encoder fallback.
- **Encoder priming** is measured with a 10 ms chirp (a single sharp correlation peak), at most 2 s, once per encoder and layout; a failed measurement falls back to Media3's value and isn't retried.
- **Known difference from iOS:** slowed-down clips come out at a variable frame rate (Media3's frame rate setting only caps it). They play correctly.

### Tested
- **Android, on an emulator (Android 17, arm64, software codecs), same fixtures as iOS:**
  - fast join of 3 clips without re-encoding, and with a muted last clip (silent);
  - trims; every rotation, flip and crop, and combinations (frames identical to iOS);
  - resampling 44.1 → 48 kHz: a 10 kHz tone's image at 13.9 kHz is 117 dB down (Sonic left it 21 dB down); the refactor to shared export code left every video frame and all audio timing unchanged;
  - 2× and 0.5× with natural pitch;
  - a mixed HEVC / 60 fps / 4K / landscape / 5.1-audio draft;
  - cancel.
  - Every output is faststart. The emulator's software encoder writes Baseline profile and didn't hold a 2 Mbps target; real hardware encoders still need checking.
- **Android, on a Galaxy S24 Ultra (Android 16) in Pulse:**
  - fast join of 3 recordings without re-encoding in 0.65 s, exactly the clips' total length;
  - trims, rotate + flip, crop, 2× and 0.5×, and a muted clip: one hardware encode in 1.7–2.1 s, lengths within a frame of the edits, 5.6–6.2 Mbps at a 5 Mbps target;
  - slowed clips come out at a variable frame rate (an export with a 0.5× clip averaged 19.9 fps), as noted above.
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
- **Android:** Media3's extractor feeding `MediaCodec` (off the CPU thread pool), then mono and a band-limited polyphase resampler (windowed sinc; Media3's Sonic interpolates linearly, which aliases). Priming samples are dropped by timestamp, since some decoders already drop them; the output stops at the track's duration. Each buffer is a round trip to the platform codec's process (S24: ~1.5–2.5 ms per AAC frame, one frame per buffer), so on Android 15+ decoders that support it (`FEATURE_MultipleFrames`), extractAudio batches many frames per buffer through the codec's callbacks (the only mode that batches); such a decoder drops the priming itself. S24: a 24 s 5.1 clip 2.0 s → 0.39 s, 48 s of Apple AAC in 0.48 s; clicks in the output within 0.06 ms of their true position for both FFmpeg (1024-sample priming) and Apple (2112) AAC. Elsewhere the one-frame loop keeps the codec full and the downmix copies each buffer out in one go.
- **Known (Android, Opus):** Opus audio comes out about 300 ms short at the start (the platform decoder drops it; the stream's own 6.5 ms pre-skip is handled). Pulse never reads Opus here: imports convert it to AAC first.
- **Level:** channels are summed at −3 dB each (Apple's downmix), and the whole buffer is turned down only if that would clip, never up.

### Tested
- **iOS on macOS, Android on the emulator**, against an FFmpeg reference (`aresample` with a 64-tap filter) on speech clips with 48 kHz stereo, 48 kHz 5.1 and 44.1 kHz mono AAC, and a file without audio:
  - Android matches the reference's length and speech onset to the sample; iOS is within 6 samples (0.4 ms) and 1 sample;
  - waveform correlation 0.99 (iOS) and envelope correlation 1.00 (Android); no clipping on 5.1;
  - a 7 s clip takes ~10 ms on macOS and ~0.3 s on the emulator's software decoder.
- **In Pulse, on an iPhone 17 Pro Max and a Galaxy S24 Ultra**, transcribing merged exports with whisper.rn 0.7.4:
  - the PCM's length matches the video's (14,799 vs 14,800 ms; 12,648 vs 12,660 ms); 80–180 ms on the iPhone and 0.4–0.8 s on the S24 for 6–15 s of audio;
  - the transcripts match a local run of the same whisper.cpp (1.9.3) with whisper.rn's exact settings on the same exports, and the iOS extractor's PCM matches an FFmpeg reference decode to 0.1 ms.

## `conform`

```ts
import { conform } from '@mieweb/pulse-editor';

const result = await conform(uri, {
  width: 1080, height: 1920,         // display canvas: the picture is fitted, letterboxed, centered
  rotation: 90,                      // clockwise tag; at 90/270 frames are coded 1920×1080, like the camera's
  fps: 30, bitrate: 5_000_000,
  audio: { sampleRate: 48000, channels: 1 },  // the recorder's layout
  copyVideo: false,                  // true: keep the video samples, re-encode only the audio
}, { onProgress, signal });          // abort = cancel: stops, deletes partial output, rejects "Conform cancelled"
// → { uri, durationMs, encoded, bitrate }: a faststart MP4 in the caches folder
```

One imported file into the recorder's format, so it can join the camera's own recordings without re-encoding. What to do with a file (pass it through, convert only its audio, or convert it all) stays in Pulse (`decideImport`); `conform` only carries it out.

- **Output:** H.264 High 8-bit SDR BT.709, the canvas at the given rotation tag, a constant frame rate on iOS, AAC in the given layout, faststart.
- **HDR** (HLG, PQ, Dolby Vision) is tone-mapped to SDR, not just re-tagged.
- **Mirrored** sources are flipped back into the pixels.
- **Sound:** the track the platform plays, never Apple's spatial-audio (APAC) track. A file whose sound the device can't decode is **rejected**; it is never conformed silently.
- **Checked before it's returned:** the output is probed (codec, layout, rotation, sound) and must be as long as the source's picture; a decoder that gives up early fails the conform instead of hiding behind a held frame. Leftover conform files in the caches folder are deleted after a day.

### How a conform runs

| | iOS | Android |
|---|---|---|
| Video and audio | merge's single-clip render (`Render`): hardware decode, a video composition rendering into BT.709 (the tone map), letterbox, coded in the tag's orientation, gaps filled to a constant 30 fps, `AVAssetWriter` H.264 + AAC | one `Transformer` export (merge's `transcode/`): `FrameDropEffect` down to the rate (no fill: a 24 fps source stays 24), a flip for mirrored sources (Media3 reads the flag but doesn't apply it), `Presentation` letterbox, then a turn into the coded orientation; GPU tone map where the GPU can (as merge); the audio mixed and resampled into the layout |
| Audio only (`copyVideo`) | merge's join with one clip and its audio re-encoded (passthrough export) | the same export with the video transmuxed |
| Rotation tag | the iPhone camera's matrix (quarter turn plus the translation back to the origin) | written on the muxer's video format (`RotationTagMuxerFactory`), the way the S24 camera writes it; Transformer otherwise picks 90 or 270 itself |
| Sync | merge's audio path | merge's priming fix and AAC roll group |

What RNVT's `compress` did differently: an AVFoundation engine on iOS with FFmpeg as a fallback, FFmpeg only on Android, and a file whose sound couldn't be decoded was imported silent.

### Tested
- **iOS code on macOS**, against the conform sample set (the longest file of each of 53 format groups among 173 files: iPhone Dolby Vision HLG 1080p/4K at 24–120 fps with spatial audio, screen recordings, full-range HEVC, old Pulse exports with 5.1, messaging-app clips, Pulse's import fixtures), with an iPhone recorder target (1920×1080 tagged 90°, 48 kHz mono):
  - all 51 that needed it converted (2 already matched): H.264 8-bit BT.709, 1080×1920 displayed, tagged 90°, 30 fps constant, mono 48 kHz AAC, picture length within 1% of the source, faststart, no FFmpeg decode errors; 0.2–34 s each (4 minutes of 4K HEVC in 34 s);
  - audio-only on six clips (including both phones' own recordings): video samples kept, audio converted, in 30–120 ms;
  - lip sync through a full conversion and an audio-only one: 0.0–0.1 ms in FFmpeg and in AVFoundation (flash/click sync clips);
  - frames from 7 conversions checked by eye against their sources: upright, letterboxed, HDR toned down to SDR;
  - a mirrored source (display matrix with a flip) comes out as players show it, with no mirror left in the tag;
  - an audio-only conform of a sync clip tagged like a recording: 0.0 ms in both;
  - full-range sources keep their colours (mean centre colour within 2–3 levels of the source).
- **Android on the emulator (Android 17, software codecs)**, in Pulse's debug build through the app's own import gate (`conformToContract`), upright target (1080×1920) and the S24's (1920×1080 tagged 90°):
  - every 8-bit input converted: 120 fps slow motion, 60 fps, variable frame rate, 4K, Opus, no audio, WhatsApp, square, a mirrored clip, and real screen recordings, 5.1 exports and messaging clips; H.264 BT.709 limited range at the requested layout and tag, AAC, length within 1%, faststart, no FFmpeg decode errors;
  - lip sync 0.0 ms in FFmpeg and AVFoundation, full conversion (48 and 44.1 kHz sources, both targets) and audio-only (the video copied untouched);
  - cancel: rejects "Conform cancelled" 14–86 ms after the abort, no file left;
  - the emulator can't decode 10-bit HEVC, so its HDR inputs fail with "This phone couldn't decode the video." (needs the S24).
- **Found and changed on the emulator:**
  - Media3 encodes an SDR source in the source's own colour description, so full-range sources (screen recordings, some messaging apps) came out full range. Every Android encode (merge and conform) now asks for BT.709 limited range, what the cameras and iOS write.
  - Still open: on the emulator a full-range source's picture comes out 9–10 levels darker (full range read as limited), with or without that change; limited-range sources keep their levels exactly (180.9 → 180.4). To check on the S24.
- **iPhone videos' second sound track:** every iPhone video carries a spatial-audio (APAC) track next to its stereo AAC, which Android can't decode. Test clips with a normal picture and a real iPhone video's two tracks (APAC second, as iPhones write it, and APAC first and default) keep the AAC sound on macOS, the iOS Simulator and the Android emulator, full and audio-only (output vs the source's AAC: correlation 0.999 at 0 ms).
- **Next to RNVT's `compress`, in Pulse:** on the iOS Simulator both convert to contract, and pulse-editor's cancel settles in 233–353 ms with nothing left behind. On the Android emulator RNVT's full conversion fails by its own bug (its FFmpeg command picks the `mpeg4` encoder and combines options FFmpeg rejects as contradictory); its audio-only one works.
- **Known differences between the platforms:** iOS fills frames to a constant 30 fps; Android drops frames down to 30 but doesn't add any, so a 24 fps or variable source stays variable (a 58 fps screen recording averaged 29.95 fps) and 29.97 fps stays 29.97. Both pass the import contract and the recorder match.
- **iPhone 17 Pro Max (2026-10-07, `ios-ios-conform`)**, in Pulse's debug build next to RNVT's `compress`, 42 inputs (the fixtures above plus the real-recording corpus: iPhone Dolby Vision HLG up to 4K120, screen recordings, 5.1 exports, messaging clips, full-range HEVC, both phones' recordings, the APAC test clips; 4 already matched): every output from both engines to contract, no FFmpeg decode errors; lip sync 0.0 / 0.1 ms (s48 / s44); cancel rejected 1256 ms after the start and settled 377 ms later with nothing left. The brightness check flags the 7 HDR sources at −7…−48 levels, the same as RNVT within a level (the tone map, not a bug). pulse-editor is faster on 33 of 38 (164.7 s against 171.7 s in total), slower on the audio-only conforms (c17 195 vs 153 ms, c20 776 vs 599 ms) and 4–5 % on two re-encodes. ConformJob runtime: 1080p re-encodes 5.0–7.1× realtime, 4K60 HEVC ~3×, 4K Dolby Vision 1.5× (24.5 s), audio-only 33–42×.
- **Galaxy S24 Ultra (2026-10-07, `android-s24-conform-1007`)**, the same 42 inputs through the app's import gate: 38 of 38 converted in ~5 minutes, HLG, PQ and Dolby Vision included (the emulator couldn't decode them; levels −39…−65 below the source = the tone map); lip sync 0.0 ms (s48, s44 and a sync clip tagged like a recording); cancel settled 15 ms after the abort with nothing left; 1080p re-encodes 4.3–7.2× realtime, 4K HEVC 27 s for 158 s, 4K Dolby Vision 19 s for 30 s. RNVT's full conform fails on the phone for every input by its own bug (as on the emulator); its audio-only conforms work and are 2–8× faster than pulse-editor's (c07 324 vs 785 ms). Found on the phone, open: (1) a corrupt source (`export 4.MP4`, 73 FFmpeg decode errors on the Mac) comes out with 746 of 3424 frames and 36 gaps over 0.5 s — Android drops what its decoder can't decode and keeps going, where AVFoundation conceals (the iPhone's output has every frame); (2) a 23.976 fps 10-bit HLG clip comes out at 26.4 fps average with 24 duplicate timestamps, and a 4K HEVC clip at 29.3 fps with 6 — the "doesn't pad frames" difference below plus duplicated frames; the app's recorder-match check flags all three. The full-range level check from the emulator is still to be read on the phone.

## `thumbnail`

```ts
import { thumbnail } from '@mieweb/pulse-editor';

const { uri, width, height, timeMs } = await thumbnail(fileUri, {
  timeMs: 1500,                    // the frame shown at that moment (clamped to the video)
  maxWidth: 192, maxHeight: 256,   // fitted inside, never enlarged; 0 = no limit
  quality: 0.8,                    // JPEG
  rotation: 90, flipped: false,    // the clip's edit, as in MergeClip (optional)
  crop: { x: 0.1, y: 0.2, w: 0.6, h: 0.5 },
});
// uri: a JPEG in the caches folder; timeMs: when that frame starts in the source
```

A clip's cover. The edit is drawn by merge's own geometry, so an edited clip's cover matches its export; HDR is tone-mapped to SDR; the frame is the exact one at `timeMs`, not the nearest keyframe.

- **iOS:** `AVAssetImageGenerator` with zero time tolerance, reading through a video composition built with `MergeGeometry` (BT.709, the tone map), written with ImageIO.
- **Android:** Media3's `FrameExtractor` (`media3-inspector-frame`, exact seek, platform decoder, HDR tone-mapped) with merge's edit effects (`MergeExport.editEffects`) and a `Presentation` to the fitted size; Media3 turns the frame upright itself, and a mirrored source gets its flip from us (as in conform).
- What RNVT's `getFrameAt` did differently: iOS cut the edit out of a decoded frame with its own code and allowed ±100 ms; on Android `MediaMetadataRetriever` with an FFmpeg fallback, which sized un-edited covers before turning them upright (61×108 instead of 144×256 for a portrait recording), ignored mirrored sources, and named files by the second (two covers in one second overwrote each other).

### Tested
- **iOS code on macOS:** every rotation, flip, crop and combination on a clip tagged like a recording matches merge's export of the same edit at the same frame (31–35 dB, two lossy encodes apart); the exact frame asked for (1500 ms → 1500 ms); a time past the end gives the last frame; iPhone HDR (1080p, 4K60, 4K120) tone-mapped, a screen recording, a mirrored clip; 20–30 ms per cover on an M-series Mac (120 ms for the first HDR one).
- **Android on the emulator**, through a Pulse debug build next to RNVT's `getFrameAt` with the same edits: all 8 edits show the same picture as RNVT's (by eye) and match an FFmpeg reference of the edit at 32–36 dB (the crop reference scores 25 dB on both platforms, an artifact of FFmpeg's crop rounding: the iOS and Android crop covers match each other at 36 dB); exact frames; real recordings, screen recordings, a mirrored clip (RNVT: sideways), slow motion; 150–600 ms per cover against RNVT's 50–300 ms (`FrameExtractor` starts a player per call; to be timed on the S24). The emulator can't decode 10-bit HEVC, so HDR waits for the S24.
- **iOS Simulator, in Pulse next to RNVT:** every edit matches the FFmpeg reference at 37–40 dB; 20–160 ms per cover; a PQ HDR clip and a time past the end work where RNVT's `getFrameAt` fails ("Cannot Decode", "Cannot Open").
- **iPhone 17 Pro Max (2026-10-07, `ios-ios-thumb`)**, in Pulse next to RNVT's `getFrameAt`: 39 of 39 covers (8 edits on the recorder-shaped clip, HDR, rotated, square, slow motion, screen recording, past the end, the real-recording corpus), 22–50 ms each (slow motion 192 ms), 10–20 ms slower than RNVT per cover, with the better match to the FFmpeg reference on most; RNVT fails past the end and on a < 1 s clip and picks wrong frames on two corpus clips (15.8 and 5.9 dB).
- **Galaxy S24 Ultra (2026-10-07, `android-s24-thumb-1007`)**: 39 of 39 covers, the better match on most (RNVT's un-edited covers are still 61×108; it fails the < 1 s clip and ignores a mirrored clip's flip, 3.4 dB); 160–600 ms per cover (most 230–300; RNVT 90–630 ms), `FrameExtractor`'s player per call — fine for one cover, too slow for a thumbnail strip (a warm extractor was tried on 2026-10-06 and reverted: Media3 returned the previous request's frame when only the effects changed).

## `<PulsePreview>`

```tsx
import { PulsePreview, type PulsePreviewRef } from '@mieweb/pulse-editor';

<PulsePreview
  style={{ flex: 1 }}
  clips={clips}           // MergeClip[] as merge takes them; keep the array stable (useMemo): a new one rebuilds
  options={options}       // the MergeOptions the draft will be merged with (canvas, fps, ...)
  onStatus={({ durationMs, ready, error }) => {}}  // ready once the first frame is on screen
  onTime={(timeMs, playing) => {}}                  // ~12×/s while playing (the on-screen frame's time), and after every seek, play, pause
  onReady={(ref: PulsePreviewRef) => {}}            // the handle for the methods below
/>

ref.play(); ref.pause();
await ref.seek(timeMs);   // resolves with that frame's time once it's on screen; a newer seek replaces it
ref.setScrubbing(true);   // faster, approximate seeks while a finger drags; false on release lands exactly
```

A native view that plays a draft's clips, every edit applied, as `merge` would export them: the same timeline, the same trims, speeds and geometry, drawn at the view's size. Changing a clip (an edit) swaps the new timeline in at the same moment, holding the last frame until the new one is up. `snapshot()` and `stats()` exist for the bench (frame files and timings) and may go before release.

- **Preview = export:** the preview is built from merge's own code, not a copy of it. On iOS both play `Timeline.compose` (the composition merge renders from); on Android both use merge's per-clip media items and effects. `options` tells the preview which clips merge will copy and which it will render, because the two trim differently (below).
- **"On screen" means on screen:** first frame, seeks and edits resolve when the new frame is displayed, not when it's decoded. iOS waits for the layer to show the new item; Android draws into a `TextureView` and counts the frames that reach it (below).

### How it runs

| | iOS | Android |
|---|---|---|
| Player | `AVPlayer` + `AVPlayerLayer`, the item built from `Timeline.compose` at the view's pixel size | one long-lived Media3 `CompositionPlayer` (experimental API) drawing into a `TextureView`, 100 ms start buffer |
| Seeks | Apple's chase pattern (QA1820): one exact seek at a time, always to the latest time asked for; a tolerance of one frame while scrubbing | exact seek to the start of the frame showing at that time (ExoPlayer shows the first frame at or after the position); Media3's scrubbing mode while dragging |
| Edits | a new item swapped in at the same time; done when the layer shows a frame of it | `setComposition` at the current position (rebuilds the players); done when a frame released after the swap reaches the view |
| Trims | clips merge copies start on the nearest frame (as the join copies them), rendered clips exactly | a trim inside a frame starts on the next whole frame (as Media3's clipping does) |
| "On screen" signal | `isReadyForDisplay` + `displayedPixelBuffer`: a seek is done when the displayed buffer changes (display-link watch, 40 ms cap when the seek stays on the same frame, 300 ms otherwise); while playing, the item's video output | `TextureView.onSurfaceTextureUpdated`, matched to the frame's time through Media3's frame callback |

Why a `TextureView` on Android: `CompositionPlayer`'s frame callback fires when a frame enters its effects pipeline, before rotate, crop and the letterbox are drawn, so with a `SurfaceView` an edit was reported done while the old picture was still showing (4 of 6 edit snapshots wrong on the emulator). The `TextureView` reports every frame that reaches the view; drafts are SDR after `conform`, which a `TextureView` shows like a `SurfaceView`. Its frames carry no timestamp, so they're matched to Media3's callbacks in order.

### Tested
Through Pulse's debug build (bench mode `preview`) with four recorder-shaped test clips that carry each frame's number in the picture (readable through rotate, flip, crop and letterbox), on three drafts: four whole clips (join), trims inside frames (trims), and rotate/flip/crop/speed edits (edits). Each draft: the first frame, 20 seeks (every clip's first, middle and last frame, plus random times) with screenshots at +0, +50 and +150 ms, a 2 s scrub, two edits (rotate 180°, then 1.5×) with screenshots, and 8 s of playback with 4 screenshots. Every screenshot is compared with the **export** of the same draft on the same device.
- **iPhone 17 Pro Max (2026-10-02):** with the first version, `seek()` resolved before the layer showed the frame: 3, 1 and 1 of 20 seek screenshots per draft showed the export's frame at +0 ms, every one at +50 ms (seek p90 46–48 ms: decoded, not yet displayed). Fixed, a seek is done when the layer's displayed buffer changes (watched by a display link; 40 ms cap when the seek stays on the same frame, 300 ms otherwise), the video output is attached to each item, and a drag reports each landed frame through `onTime`: seeks show the export's frame 59 of 60 (the 1: the export shows one frame earlier at the end of the sped-up clip, the same quirk as on the Simulator), p90 64–79 ms; first frame 73–86 ms; edits on screen in 53–81 ms, all 6 showing the edit; ~31 pictures/s while dragging; playback 30.0–30.3 fps with nothing dropped, the frame on screen 33 ms ahead of the reported time. The fix's source was lost from disk that evening and replayed from the session record; the replayed copy is what the Simulator ran on 2026-10-06 and what this commit holds.
- **iOS Simulator (iPhone 17 Pro Max, 2026-10-06, the seek fix):** seeks show the export's frame 58–59 of 60 (the rest: the sped-up-clip quirk), p90 62–73 ms; first frame 31–73 ms; edits on screen in 27–80 ms, all showing the edit; 60 pictures/s while dragging; playback 29.1–29.5 fps, nothing dropped, the frame on screen 0–33 ms from the reported time. Play started 91–190 ms late, stable per draft across six runs (next bullet).
- **Play start on iOS (fixed on the Simulator):** after `play()` the reported time and the picture stood still for ~200 ms: `timeControlStatus` was playing with no waiting reason within 4–5 ms, but the item's timebase kept rate 0 for ~90 ms and then ran up from −108 ms, reaching the position ~210 ms after the call. That is `play()`'s scheduled start "at a host time in the near future … allowing some time for media data loading" (AVPlayer.h); nothing was loading. `play()` now starts the rate at the current host time (`setRate(1, time: .invalid, atHostTime:)`, allowed with `automaticallyWaitsToMinimizeStalling` off): start lag 0 ms on every draft (was 168–206 / 91–104), first new frame 18–49 ms after `play()` (was 216–249), seeks, edits and 29 fps unchanged, a restart after pause and a start inside a GOP also 0 ms. Tried first with no effect (each A/B'd on the Simulator): `preroll(atRate:)` after paused seeks, `playImmediately(atRate:)`, activating the `.playback` audio session at init. Audio with the new start: an audio tap on the item saw the first buffer enter the renderer 16–24 ms after `play()` (5–48 ms with `play()`), PTS 0.0 when playing from 0 in both, so nothing is skipped; sound at the speaker isn't measurable on the Simulator. Not yet run on the iPhone, where the lag was 169–301 ms with `play()`.
- **Android emulator (Android 17, host GPU and host H.264 decoder, 2026-10-06):** six runs. Seeks show the export's frame 60 of 60 in every run, none late; edits 6 of 6 showing the edit; playback 26.6–28.7 fps, nothing dropped; first frame 114–554 ms; seek p90 295–325 ms with the Mac otherwise idle (794–943 ms in the one run that overlapped a Simulator boot), edits 183–366 ms (647–775 ms in that run); 3–8 pictures/s while dragging; the picture starts moving within −55 to +82 ms of `play()`. The frame identity, the edit path and the exact-seek rule hold on Android; the times are the emulator's and say nothing about the S24. These runs replace the software-decoder figures reported before (21–25 fps, seeks 0.6–1.8 s).
- **Frame identity on Android (found on the emulator):** the `TextureView`'s frames carry no timestamp there (`SurfaceTexture.getTimestamp()` = 0), so they're matched to Media3's frame callbacks in order (logcat: "Preview frames matched by order", once per draft). After a drag, frames the player had released but never drew stayed in the queue: the next edit was then never reported done (`lastSwapMs` stayed −1 while the screenshot already showed the edit; 2 of 3 runs before the change) and the settle seek after the drag ran into the 3 s seek timeout. Changed: the queue is cleared when the composition is rebuilt, and entries released more than 300 ms ago are dropped before a frame is matched by order. Since: no unreported edit in three runs (nor in one run before the change, so it was intermittent); the settle timeout still hit 1 of 3 drafts in the final run (4 of 7 before). Whether the S24's frames carry a timestamp decides whether any of this applies there: check its logcat for "matched by order".
- **`onTime` on Android** now reports the time of the frame on screen (the last frame that reached the view) instead of `player.currentPosition`. On the emulator the picture ran 33–133 ms ahead of the reported time while playing before the change (100 ms as often as 67 over 28 screenshots), 67 ms in 23 of 28 after it. What's left is the bench's extrapolation from an 80 ms tick plus the Nitro → JS hop, inferred; the S24 will show whether it's the emulator. iOS: 0–33 ms on the Simulator, 33 ms on the iPhone.
- **Chase seek on Android, tried and reverted:** a native queue that kept one `seekTo` in flight and the latest target waiting. ExoPlayer's scrubbing mode already drops intermediate seeks and waits for a frame before taking the next (`ExoPlayerImplInternal`, the seek queued while scrubbing), and the extra layer cut the emulator's drag picture rate from 5–8 to 1–2 pictures/s. Kept: every seek goes to the player at once, and every caller resolves when the latest target is on screen. From reading Media3 1.11.1, `CompositionPlayer` forwards only the scrubbing on/off flag to its inner players, not `ScrubbingModeParameters`. Each landed seek now logs its split (seek → frame released → on the view; on the emulator ≈ 90 % is decode and composition, 46–55 ms is effects and the view) and the default decoder per codec, for the phone runs.
- **How the drag and start numbers are read:** "pictures/s while dragging" counts the frames that reached the view during the 2 s drag (iOS: the item's video-output buffers; Android: `TextureView` frames); on Android a drag's intermediate frames aren't reported to JS, so the bench's "seeks landed/s" reads lower there than the pictures drawn. "Play-start lag" is wall time minus video time played over 8 s of playback.
- **Found and changed on the way (2026-10-02):** iOS trimmed copied clips at the exact millisecond while merge's join starts them on the nearest frame (the preview showed one frame earlier: 7 of 20 trims seeks matched, now 20 of 20); the Android `SurfaceView` timing above; on Android the preview's audio trim now starts from the seek position (`TrimAudioProcessor`); exports are unaffected (the emulator's trimmed export has its clicks exactly at the trims, the same as iOS's to within the frame rounding).
- **Known differences between the platforms:** a trim inside a frame starts on the nearest frame on iOS (join) and on the next whole frame on Android, so the same draft can export one frame apart; each preview matches its own platform's export.
- **iPhone 17 Pro Max (2026-10-07, this code, `ios-ios-preview-t3`):** seeks show the export's frame 20 / 20 / 19 / 20 of 20 (join, trims, edits, mute; the 1: the sped-up-clip quirk), p95 61–63 ms; first frame 56–122 ms (122 cold); edits on screen in 41–92 ms, all 8 showing the edit; 58–60 pictures/s while dragging (62.5 is the drag's ceiling); playback 30.1–30.2 fps, nothing dropped, 7 of 12 playback screenshots exact and 5 one frame off (all 12 one frame off on 2026-10-02); play starts in 0 ms on every draft, from 0, after a pause and from inside a GOP (169–270 ms on 2026-10-02). Traces on the phone: the clock starts at `play()`, the first new frame reaches the layer 34–50 ms later. **Open, by ear:** after a play straight from a paused seek the first audio buffer reaches a test tap 106–135 ms after `play()` (after a pause, 4–34 ms; Simulator 16–24 ms) — the tap can't tell late from cut, so the fixture's click 0.5 s into the play is to be listened to; `preroll(atRate:)` after the seek doesn't help (the bench presses play 23 ms later, which cancels it; `ios-ios-ab-preroll`). A coarser seek tolerance while dragging has nothing to gain: the drag already seeks with a one-frame tolerance and lands 58–60 of a possible 62.5 pictures/s.
- **Galaxy S24 Ultra (2026-10-07, this code, `android-s24-preview-1007`):** seeks show the export's frame 80 of 80, edits 8 of 8 showing the edit, playback 29.0–29.6 fps with nothing dropped, first frame 119–481 ms (481 cold) — the frame identity holds on hardware. The speed targets are missed: seek p90 239–281 ms (target p95 ≤100), 8–10 pictures/s while dragging (≥20), edits on screen in 288–491 ms (≤400; the 1.5× swaps over), play starts 118–173 ms after a paused seek. Where a seek goes (`seeklog.py`, 85 seeks, hardware `c2.qti.avc.decoder`): seek → frame released median 141 ms (p90 227), released → on screen median 24 ms (p90 38), so it's Media3's decode and composition, not the `TextureView`. The S24's `TextureView` frames carry no timestamp either ("matched by order" once per draft), the settle seek after the trims drag hit the 3 s timeout once, no player-release timeout in the run. Inference: faster drags on Android need a Media3 change (`CompositionPlayer` forwards only the scrubbing flag) or a plain player on one clip for trim drags.
- **Not measured:** lip sync during preview playback on either phone (needs in-app audio capture). The compare benches run the same day were on hot phones at 20 % battery and are not used for the tables below.

## Benchmarks (on device)

Measured on 2026-10-01 on an **iPhone 17 Pro Max** (iOS 26.5.1) and a **Galaxy S24 Ultra** (Android 16), each running a Pulse debug build of #240 with pulse-editor and react-native-video-trim (RNVT, what Pulse merged with before) side by side. The live copy of these tables is on [Pulse #242](https://github.com/mieweb/pulse/issues/242).

**How it's measured.** A temporary bench screen in the Pulse debug build (kept out of the repo) runs the same drafts through both engines on the phone, alternating which goes first, two runs each, and reports the median. RNVT gets exactly what Pulse used to pass it (`merge(urls, { outputExt: 'mp4', ...REELS_TARGET, clipEdits })`). The outputs are pulled to a Mac and checked:

- **Lip sync:** the sync drafts are built from `assets/dev/sync` clips, which flash white and click at the same instant every second. The worst audio − video offset over every event is read twice: as FFmpeg decodes the file (browsers, servers) and as AVFoundation does (Photos, Safari, iOS). Under one frame (33 ms) is in sync; a 2× clip can't do better than half a frame, because the source's frames land between the output's.
- **Duration:** the output against the sum of the clips' windows.
- **Playability:** faststart (`moov` before `mdat`) and FFmpeg decode errors.
- **Drafts:** recorder-shaped test clips (H.264 1080p30 portrait, no B-frames, like real recordings), the seed drafts (`+ s2`–`+ s5`), and each phone's own recording.

**What the phone runs found, and what changed** (all re-measured on the phones):

- Android exports played 44 ms early on Apple players: Media3's muxer omits the AAC roll sample group, so AVFoundation trimmed the encoder priming twice (see How a merge runs (Android)). Fixed: 0.0 ms in both players.
- iOS joins drifted ~10 ms per clip in browsers (copied AAC padding; Apple players were fine). Fixed by re-encoding multi-clip audio: 0.0 ms in both, at the cost of joins taking about twice RNVT's time on the iPhone (1.0 s for a 2-minute join).
- Android extractAudio was ~2 ms per AAC frame (one frame per codec round trip); batching frames on Android 15+ made it 5–9× faster on the S24 (2-minute draft 9.0 → 1.0 s).
- Android one-clip drafts were re-encoded instead of copied; now copied.
- Android joins feed the audio encoder in larger buffers: 21–34% faster on the S24.
- Recordings have no B-frames on either phone, so edited iPhone drafts take the selective path; the recorder-match test clips were regenerated to match.
- Known and accepted: Android joins are audio-pipeline bound (~10× realtime on the S24; the rest of the phone is idle, Media3 processes audio serially). A trimmed clip on Android can end with a 1–2 ms sliver frame at a clip boundary (invisible; FFmpeg's strict check notes it).

### Lip sync and output checks

**iPhone 17 Pro Max (iOS 26.5.1)**: worst audio − video offset over every event, ms (+ = sound late)

| Draft | pulse-editor, browsers (FFmpeg) | pulse-editor, Apple (AVFoundation) | RNVT, browsers | RNVT, Apple | pulse-editor output |
|---|---|---|---|---|---|
| Sync draft: join (4 × 12 s) | 0.0 | 0.0 | 32.0 | 0.0 | faststart, 0 decode errors |
| Sync draft: 48 + 44.1 kHz audio | 0.1 | 0.1 | 32.0 | 0.0 | faststart, 0 decode errors |
| Sync draft: rotate + crop + trim | 0.0 | 0.0 | 26.7 | 0.0 | faststart, 4 decode errors |
| Sync draft: 2× / muted / 0.5×, 48 + 44.1 kHz | -25.0 | -25.0 | 496.5 | -58.5 | faststart, 0 decode errors |
| Sync draft: trimmed starts | 0.0 | 0.0 | 27.4 | 0.0 | faststart, 0 decode errors |

**Galaxy S24 Ultra (Android 16)**: worst audio − video offset over every event, ms (+ = sound late)

| Draft | pulse-editor, browsers (FFmpeg) | pulse-editor, Apple (AVFoundation) | RNVT, browsers | RNVT, Apple | pulse-editor output |
|---|---|---|---|---|---|
| Sync draft: join (4 × 12 s) | 0.0 | 0.0 | -12.0 | -12.0 | faststart, 0 decode errors |
| Sync draft: 48 + 44.1 kHz audio | 0.0 | 0.0 | 15.4 | 15.4 | faststart, 0 decode errors |
| Sync draft: rotate + crop + trim | 0.0 | 0.0 | 10.7 | 10.7 | faststart, 0 decode errors |
| Sync draft: 2× / muted / 0.5×, 48 + 44.1 kHz | -17.5 | -17.5 | -24.0 | -24.0 | faststart, 0 decode errors |
| Sync draft: trimmed starts | 0.0 | 0.0 | -16.6 | -16.6 | faststart, 0 decode errors |

### Speed

**iPhone 17 Pro Max (iOS 26.5.1)**: median of 2 runs each, engines alternating

| Draft | pulse-editor | RNVT | pulse-editor path | Output duration vs clips (pulse-editor / RNVT) |
|---|---|---|---|---|
| Join 3 recorder clips (18 s) | 213 ms | 91 ms | copied | +0 ms / +0 ms |
| Join 20 clips (2 min) | 1.0 s | 584 ms | copied | +0 ms / +0 ms |
| Join 20 clips (8 min) | 4.0 s | 2.1 s | copied | +0 ms / +0 ms |
| 6 clips: trim, rotate+flip, crop, 2×, 0.5× muted | 3.1 s | 2.5 s | re-encoded (all or some clips) | +0 ms / +0 ms |
| 20 clips, 6 in other formats (HEVC, 60 fps, 4K, landscape) | 17.6 s | 9.0 s | re-encoded (all or some clips) | +0 ms / +0 ms |
| 12 hostile imports (HDR, Opus, no audio, VFR, 120 fps…) | 14.4 s | 27.8 s | re-encoded (all or some clips) | -81 ms / +198 ms |
| Sync draft: join (4 × 12 s) | 374 ms | 205 ms | copied | +0 ms / +0 ms |
| Sync draft: trimmed starts | 330 ms | 182 ms | copied | -20 ms / +0 ms |
| Sync draft: 2× / muted / 0.5×, 48 + 44.1 kHz | 2.7 s | 2.3 s | re-encoded (all or some clips) | +0 ms / +0 ms |
| Sync draft: rotate + crop + trim | 3.6 s | 3.0 s | re-encoded (all or some clips) | +0 ms / +0 ms |
| Sync draft: 48 + 44.1 kHz audio | 506 ms | 269 ms | copied | +0 ms / +0 ms |

**Galaxy S24 Ultra (Android 16)**: median of 2 runs each, engines alternating

| Draft | pulse-editor | RNVT | pulse-editor path | Output duration vs clips (pulse-editor / RNVT) |
|---|---|---|---|---|
| Join 3 recorder clips (18 s) | 2.4 s | 3.8 s | copied | +5 ms / +48 ms |
| Join 20 clips (2 min) | 12.2 s | 23.1 s | copied | +0 ms / +320 ms |
| Join 20 clips (8 min) | 44.5 s | 78.6 s | copied | +0 ms / +0 ms |
| 6 clips: trim, rotate+flip, crop, 2×, 0.5× muted | 4.4 s | 7.9 s | re-encoded (all or some clips) | +11 ms / +16 ms |
| 20 clips, 6 in other formats (HEVC, 60 fps, 4K, landscape) | 17.9 s | 29.3 s | re-encoded (all or some clips) | +0 ms / +320 ms |
| 12 hostile imports (HDR, Opus, no audio, VFR, 120 fps…) | 14.1 s | 24.1 s | re-encoded (all or some clips) | +0 ms / +36 ms |
| Sync draft: join (4 × 12 s) | 4.1 s | 8.3 s | copied | +0 ms / +43 ms |
| Sync draft: trimmed starts | 5.3 s | 7.4 s | re-encoded (all or some clips) | +5 ms / +58 ms |
| Sync draft: 2× / muted / 0.5×, 48 + 44.1 kHz | 7.7 s | 8.0 s | re-encoded (all or some clips) | +5 ms / +9 ms |
| Sync draft: rotate + crop + trim | 5.7 s | 8.8 s | re-encoded (all or some clips) | +16 ms / +11 ms |
| Sync draft: 48 + 44.1 kHz audio | 4.5 s | 8.3 s | copied | +0 ms / +34 ms |

### probe and extractAudio

**iPhone 17 Pro Max (iOS 26.5.1)**

| Method | pulse-editor | RNVT |
|---|---|---|
| probe, 26 test files (median, first read) | 12 ms | 108 ms |
| extractAudio, 24 s 5.1 clip | 228 ms | 37 ms |
| extractAudio, 2-minute draft | 464 ms | 82 ms |
| extractAudio, 8 s Opus clip | 89 ms | 25 ms |
| extractAudio, clip without audio | 2 ms | fails |

**Galaxy S24 Ultra (Android 16)**

| Method | pulse-editor | RNVT |
|---|---|---|
| probe, 26 test files (median, first read) | 29 ms | 117 ms |
| extractAudio, 24 s 5.1 clip | 368 ms | 82 ms |
| extractAudio, 2-minute draft | 990 ms | 181 ms |
| extractAudio, 8 s Opus clip | 105 ms | 59 ms |
| extractAudio, clip without audio | 5 ms | fails |

### Real recordings

**iPhone 17 Pro Max (iOS 26.5.1)**, its own recording

| Draft | Time | Path | Output |
|---|---|---|---|
| Real recording × 3 (join) | 216 ms | copied | 23.30 s, 5.4 Mbps, 0 decode errors |
| Real recording alone | 44 ms | copied | 7.77 s, 5.4 Mbps, 0 decode errors |
| Real recordings: as is / rotated / trimmed / 2× | 2.0 s | re-encoded (all or some clips) | 23.93 s, 5.3 Mbps, 0 decode errors |

**Galaxy S24 Ultra (Android 16)**, its own recording

| Draft | Time | Path | Output |
|---|---|---|---|
| Real recording × 3 (join) | 2.5 s | copied | 22.31 s, 6.0 Mbps, 0 decode errors |
| Real recording alone | 886 ms | copied | 7.47 s, 6.0 Mbps, 0 decode errors |
| Real recordings: as is / rotated / trimmed / 2× | 3.4 s | re-encoded (all or some clips) | 23.10 s, 5.5 Mbps, 1 FFmpeg timestamp warning (see below) |

### Where pulse-editor is slower than RNVT, or differs, and why

Every row where RNVT comes out ahead or the outputs differ. "Measured" means a number from these runs or from pulse-editor's own iOS code timed stage by stage on macOS; anything else is marked.

| Case | Numbers | Why | Status |
|---|---|---|---|
| **iOS joins** (3 clips, 2 min, 8 min; sync joins) | 1.7–2.3× RNVT's time (2-minute join 1.0 s vs 0.58 s) | pulse-editor re-encodes the joined audio; RNVT copies it. Measured on macOS for a 2-minute join: 0.67 s of 0.89 s is the audio re-encode, 0.14 s the video copy and export. Copied AAC needs per-clip edit lists that FFmpeg-based players ignore, which is why RNVT's joins are +27–32 ms off in browsers. A single clip has no seams, so its audio is copied (one-clip draft 44 ms). | By design: correct sync everywhere. Apple's faster AAC settings would halve the cost but lose ~6 dB of signal-to-distortion, so they aren't used. |
| **iOS edited drafts** (6-clip edits, 2×/0.5×, rotate + crop + trim) | 1.2× RNVT (3.1 s vs 2.5 s; 2.7 vs 2.3; 3.6 vs 3.0) | Both re-render only the edited clips. pulse-editor also re-encodes the audio over the whole draft: 0.55 s of the edited draft's 3.9 s on macOS. The iPhone gap (0.4–0.6 s) is about that size; that the renders themselves cost the same on both engines is inferred, not measured on the phone. | Same trade as joins. |
| **iOS mixed-format draft** (20 clips, 6 in HEVC / 60 fps / 4K / landscape) | 2.0× RNVT (17.6 s vs 8.5 s) | Clips in another format send pulse-editor to a full encode of all 20 clips (19.0 s on macOS, all of it rendering); its selective path covers edited clips only. RNVT re-encodes the 6 odd clips and copies the 14 recorder clips. Outputs differ too: pulse-editor 2.67 Mbps upright 1080×1920, RNVT 1.49 Mbps 1920×1080 + rotation. | Not worth optimizing before `conform`: imports will be converted to the recorder format at import, so a merge won't see mixed formats. Rendering only the odd clips is possible later if needed. |
| **extractAudio** (both platforms) | RNVT 4–6× faster (2-minute draft: iPhone 0.46 s vs 0.08 s, S24 0.99 s vs 0.18 s) | Not the same job. RNVT's FFmpeg writes a WAV at the source's rate and channels, in-process, with no downmix or resampling. pulse-editor returns 16 kHz mono ready for Whisper: downmixed and band-limited resampled, through AVAssetReader (iOS) or the platform codec (Android, batched on 15+). With RNVT, Pulse's caption path then read that WAV back and resampled it before Whisper, a cost not in RNVT's number (from the old code, not timed here). RNVT also fails on a clip with no audio. | Fine: 260× realtime on the iPhone, 120× on the S24. |
| **Android edited drafts: frame rate** | average 25.7–27.9 fps vs RNVT's constant 30 | Media3's frame-rate setting caps the rate but doesn't fill gaps, so slowed-down clips come out at a variable rate. They play correctly, in sync (0.0 ms; 2× clips −17.5 ms, half a frame). | Known; iOS fills to a constant 30 fps. A constant rate on Android would need a frame-repeat effect. |
| **Android file size** (edited and mixed drafts) | 1.7–5.7× RNVT's size (edits 18.3 MB vs 3.2 MB) | pulse-editor encodes at the export bitrate, 5 Mbps (the recorder's); RNVT's software encode lands at 0.85–3 Mbps. Picture quality hasn't been compared; the higher bitrate is likely the sharper picture, but that's inferred. | Bitrate is a setting (`EXPORT_BITRATE`), not an engine limit. |
| **Output length** | iOS trimmed join −20 ms; iOS hostile imports −81 ms (RNVT +198 ms) | Join trims snap to the nearest frame (≤ half a frame each), so FFmpeg-based players start the picture with the sound. Odd-timing clips (VFR, 120 fps, NTSC 29.97) are resampled to a constant 30 fps, which rounds each clip to whole frames. | By design. |
| **probe on the iPhone** | 12 ms vs RNVT 13–108 ms | RNVT's first read was ~110 ms in most runs and 13 ms in one, so its number is a range. | — |
| **Android exports, absolute speed** | faster than RNVT everywhere, but joins ~10× realtime (2 min in 12.2 s) | Media3 runs the audio pipeline (decode, cut, mix, encode) one buffer at a time on one thread; during a join one core is busy and the rest of the phone is idle (measured with `top` on the S24). Feeding the encoder larger buffers made joins 21–34% faster; pre-decoding the audio didn't help (decoding isn't the bottleneck). | Accepted. Going further means replacing Media3's audio pipeline. |

### Switching a trade-off later

Each choice above is one small, local change. Where it lives, and what changes if you flip it:

| Choice | Where | To switch | What you'd trade |
|---|---|---|---|
| iOS joins re-encode multi-clip audio | `ios/Merge/Join.swift`, `if let audioOut, audioPieces > 1 \|\| audioGap` | Re-encode only on a gap (`audioGap`) to copy audio again | ~2× faster iOS joins; browsers drift ~10 ms per clip again |
| iOS join trims snap to frames | `ios/Merge/Join.swift`, `trimRange(…, frame: frame)` | Pass no `frame` | Exact-ms cuts; FFmpeg-based players start the picture up to a frame late |
| AAC encoder quality (iOS) | `ios/Merge/SampleTransfer.swift`, `EncodeSettings.aac` | Add `AVEncoderAudioQualityKey: AVAudioQuality.low` | ~2× faster audio encode (joins); ~6 dB lower signal-to-distortion |
| Format mismatches → full encode (iOS) | `ios/Merge/MergePlan.swift`, `init`: any join blocker → `.encode` | Render only the mismatched clips, like `.selective` does for edited ones | Mixed-format drafts ~2× faster; worth it only if mixed formats still reach merge after `conform` |
| Join bitrate allowance | `MergePlan.swift` `bitrateTolerance`, `MergePlan.kt` `BITRATE_TOLERANCE` (1.6×) | Lower it | Fewer joins (more re-encodes); smaller files from high-bitrate recordings |
| Export bitrate | Pulse `src/features/export/editor-merge.ts`, `EXPORT_BITRATE` (5 Mbps) | Lower it | Smaller encoded files (Android's are 1.7–5.7× RNVT's); softer picture; recordings above 1.6× of it stop joining |
| Android join audio encoder buffer | `transcode/AacPriming.kt`, `JOIN_AUDIO_INPUT_BYTES` (256 KB, joins only) | Change the size, or apply it to encodes | Joins 21–34% faster with it; encodes got 5–15% slower when it applied to them |
| Android one-clip joins copy video | `transcode/AacPriming.kt`, `videoNeedsEncoding() = !copyVideo && …` | Return the default factory's answer | One-clip drafts re-encoded again |
| Android Apple-player sync (AAC roll group) | `merge/Merge.kt` → `transcode/Mp4.kt` `addAacRollGroup` | Remove the call | Android exports 44 ms early on Apple players |
| Android extractAudio batching | `ExtractAudio.kt`, the `FEATURE_MultipleFrames` check (Android 15+) | Force the one-frame path | 5–9× slower captions audio on the S24 |
| Android HDR without GPU support | `merge/MergeExport.kt`, `hdrMode` | Always use OpenGL tone mapping | HDR clips fail on GPUs without `GL_EXT_YUV_target` instead of coming out flat |
| Android frame rate on speed changes | `merge/MergeExport.kt`, `videoItem` `setFrameRate` (a cap) | Add a frame-repeat effect to fill gaps | Constant 30 fps like iOS; more encoding work |

**Reading the tables:**

- RNVT's iOS joins are faster because they copy the audio, and that is what drifts in browsers (the +27–32 ms above). pulse-editor re-encodes it to stay in sync everywhere.
- The two mixed-format rows (6 clips in other formats, 12 hostile imports) are formats `conform` will turn into recorder-format clips at import, so they won't reach a merge in Pulse once conform lands; they stay here as no-regression checks.
- The 4 decode errors on the iPhone's rotate + crop + trim draft come from the test clips: they're x264-made, copied next to Apple-rendered clips. With Apple-encoded clips, as recordings are, the same draft has 0 (checked on macOS, and the iPhone's own recording has 0 above).
- The S24 edited recording's one FFmpeg warning is a 1.7 ms frame where a trimmed clip ends just after a frame (invisible; players handle it).
- RNVT's extractAudio writes a WAV at the source's rate, while pulse-editor returns 16 kHz mono ready for Whisper (band-limited resampling), so its times aren't like for like. RNVT can't read a clip without audio.
- probe: RNVT's first read was slow in most runs (~110 ms) but 13 ms in one, so treat its probe time as a range.

## Development

Pulse consumes this repo as a git submodule at `modules/pulse-editor` and reads its TypeScript source directly, so there's no build step.

- **Where to edit:** make changes in the submodule inside Pulse, then commit and push here.
- **When the spec changes:** after editing `src/PulseEditor.nitro.ts`, run `yarn install` and `yarn nitrogen`. Commit the regenerated `nitrogen/generated/` files, because Pulse builds from source and needs them.
- **Updating Pulse:** in Pulse, `git add modules/pulse-editor` to record the new commit.
- **Minimum OS versions:** they follow the app's (iOS 16.4, Android 10 / API 29). API 29 is the lowest where Media3 tone-maps HDR (OpenGL); newer APIs are used behind per-method version checks.

## License

MIT
