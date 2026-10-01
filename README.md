# @mieweb/pulse-editor

The native video engine behind [Pulse](https://github.com/mieweb/pulse): fast, simple, and built only on each platform's own media stack.

- **iOS:** AVFoundation (Core Media, Core Video, VideoToolbox)
- **Android:** Media3 (Transformer, Effect, Inspector)
- **No FFmpeg** on either platform
- A [Nitro module](https://nitro.margelo.com/): Swift and Kotlin, typed specs, no Objective-C++ shim
- Works with Expo and bare React Native through autolinking

## Status: migrating from react-native-video-trim

Pulse is moving every native video method it uses from its react-native-video-trim fork into pulse-editor, **one method at a time**. Each method is added here, switched over in the app, and tested on a device before the next one starts. The trim editor UI moves last.

✅ done · ⏳ left to do · 📋 not started. Devices: **iPhone 17 Pro Max** (iOS) and **Galaxy S24 Ultra, Android 16** (Android); "macOS" = the same iOS code run on a Mac against references; "emulator" = Android 17 emulator.

| # | Step | Replaces (RNVT) | iOS | Android |
|---|---|---|---|---|
| 1 | `probe` | `probeVideo`, `isValidFile` | ✅ iPhone: recordings, upright + letterboxed imports, HDR import, preview sizing, recording rescue · iOS: all 24 fixtures read correctly | ✅ S24: recordings (codec, rotation, fps, bitrate, audio); emulator: fixtures | 
| 2 | `merge`: join, trims, mute | `merge` (copy path) | ✅ iPhone: joins, trims, muted clip, real recordings; in sync in browsers and Photos (current numbers: [Benchmarks](#benchmarks-on-device)) | ✅ S24: joins, trims, muted clip, real recordings; in sync in both (see [Benchmarks](#benchmarks-on-device)) | 
|  | `merge`: cancel | (not possible in RNVT) | ✅ iPhone: cancel mid-merge; macOS: no files left behind | ✅ emulator · ⏳ S24: next device run | 
| 3 | `merge`: edited clips (rotate, flip, crop, 2×, 0.5×) | `merge` with clip edits | ✅ iPhone: rotate + flip + crop + 0.5× + mute, trim + 2× (only edited clips rendered, lengths within a frame); macOS: every rotation/flip/crop frame by frame, exact durations, natural pitch | ✅ S24: rotate + flip, crop, 2×, 0.5×, mute (one hardware encode, lengths within a frame, 5.6–6.2 Mbps); emulator: frames identical to iOS | 
| 4 | `merge`: full encode | the re-encode path | ✅ iPhone: mixed (20 clips) and wild-imports (12 clips: HDR, VFR, Opus, no audio) seed drafts → H.264 1080×1920 30 fps, SDR, audio in sync; macOS: 2 Mbps target → 1.98 Mbps | ✅ emulator: mixed HEVC / 60 fps / 4K / landscape / 5.1 draft · ⏳ S24 | 
|  | `merge`: RNVT fallback removed | the FFmpeg merge | ✅ in code: the app merges with pulse-editor only, and an error shows the retry state (tsc, lint, tests; not run on a device since) | ✅ same | 
| 5 | `extractAudio` → Whisper captions | `extractAudio` | ✅ iPhone: captions (extract 80–180 ms, VAD 128 ms, Whisper 123 ms); macOS: PCM vs FFmpeg reference within 0.1 ms; local whisper.cpp 1.9.3 gives the same transcripts | ✅ S24: captions (extract 0.4–0.8 s, Whisper 1.5 s on CPU); emulator: length and speech onset exact vs FFmpeg; local whisper.cpp gives the same transcripts | 
| | Cleanups from the whisper.rn audit (VAD comment, real CPU fallback) | | ✅ from whisper.rn 0.7.4's source, not run: the Whisper fallback now asks for the CPU (it only runs if the GPU context fails to load) | ✅ comments only (whisper.rn has no Android GPU backend) | 
| 6 | `conform`: import normalization, HDR | `compress`, `cancelCompress` | 📋 | 📋 (RNVT's fails on the S24 today) | 
| 7 | `thumbnail` | `getFrameAt` | 📋 | 📋 | 
| 8 | `<PulsePreview>`: composition player | (needed by the editor) | 📋 | 📋 | 
| 9 | Timeline editor UI (React Native), replacing the clip preview and the per-clip editor (see [The timeline editor](#the-timeline-editor-plan)) | `showEditor` | 📋 | 📋 | 
| 10 | File helpers to `expo-file-system` | `deleteFile`, `cleanFiles`, `saveToDocuments` | 📋 | 📋 | 
| 11 | **Last commit:** remove the fork and FFmpeg (package, Podfile, Gradle, submodule), then merge | the fork | 📋 | 📋 | 

RNVT's file helpers (`deleteFile`, `cleanFiles`, `saveToDocuments`) don't move here; Pulse uses `expo-file-system` for those.

Every step keeps Pulse's output unchanged: the same saved clip edits (`editState`), the same 1080×1920 H.264 export, and the same recorder format. Existing drafts keep working.

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
| Thumbnails along each clip | `thumbnail` (step 7) |
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
