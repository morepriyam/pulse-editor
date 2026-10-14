[README](../README.md) · [Methods](methods.md) · [Benchmarks](benchmarks.md) · [Differences](differences.md) · [Timeline editor](timeline-editor.md) · [Migration](migration.md) · [Roadmap](roadmap.md)

# Methods

How each method runs on each platform, and what was tested where. Headline numbers are in the [README](../README.md#-benchmarks), the full tables in [Benchmarks](benchmarks.md).

- [`probe`](#probe): metadata
- [`merge`](#merge): join and edit clips
- [`extractAudio`](#extractaudio): PCM for Whisper
- [`conform`](#conform): imports into the recorder's format
- [`thumbnail`](#thumbnail): covers
- [`<PulsePreview>`](#pulsepreview): the composition player

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
- Android: the S24's recordings (codec, rotation, fps, bitrate, audio) and the emulator's fixtures (see [the status table](migration.md)).

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
| **Selective** | Same, but some clips are rotated, flipped, cropped or sped up, and no copied clip reorders frames (camera recordings don't) | Only those clips are rendered, into the draft's own format, two at a time, then everything is joined. On iOS, copied clips trimmed at their start are rendered too: next to rendered clips, FFmpeg drops the frames before each of their joins ([trac #6584](https://trac.ffmpeg.org/ticket/6584)). |
| **Full encode** | The clips don't share a format that fits, a copied clip would reorder frames, or the selective path fails | Every clip is rendered onto the upright canvas at the chosen bitrate, two at a time, then they're joined. |

- **Rendering:** each clip gets a trimmed slot, `scaleTimeRange` for speed, and a video-composition instruction for rotate → flip → crop → letterboxed fit. Encoding is `AVAssetReader` → `AVAssetWriter` (H.264 High, BT.709, keyframe every 2 s, no B-frames), with frames filled to a constant 30 fps.
- **Why clip by clip:** rendering a whole timeline in one composition stalled AVFoundation's audio mix reader for good (cancel couldn't stop it) when a sped-up clip met a clip in another audio format, in 4 of 5 runs on macOS, and played that clip's audio at 1×. Rendered clip by clip, the same drafts finished 30 of 30 times, with the sped-up clip's audio in sync.
- **No B-frames:** camera recordings have none, and FFmpeg-based players (browsers, servers) mis-decoded a joined file mixing them with rendered clips that had them (reference-frame errors on a real recording). Now FFmpeg decodes it frame for frame, with no errors.
- **Errors** name the clip (`Clip 2 (IMG_1234.MOV): …`); a trim that starts past a clip's end is an error, not the whole clip. On both platforms JS gets the plain message, without Swift's type description or Kotlin's class name and stack trace. Merge files a killed app left in the caches folder are deleted after a day.
- **Audio:** a single clip's audio is copied. Audio from more than one clip, or with a gap (a muted clip, or one without sound), is re-encoded once over the whole timeline, with real silence in the gaps. Copied AAC needs one edit-list entry per clip to drop each clip's priming and end padding: Apple players honour that, FFmpeg-based ones (Chrome, servers) don't. Measured on macOS with Pulse's flash/click sync clips (`assets/dev/sync`), a 4-clip copied join was in sync for AVFoundation but drifted +10.7 ms per clip for FFmpeg (0 → 32 ms); re-encoded, both read 0.0 ms at every event. The cost is the AAC encode: a 2-minute join takes 3.4 s instead of 0.5 s on an M-series Mac (8 minutes: 13.6 s instead of 3 s); not yet timed on an iPhone. (Apple's faster encoder quality settings halve that but cost about 6 dB of signal-to-distortion, so they aren't used.) Because the audio is re-encoded into the draft's layout anyway, clips whose audio differs only in sample rate or channel count now join instead of sending the whole video to a full encode (macOS: a 48 / 44.1 kHz sync draft 12.8 s → 0.77 s, a 2× / 0.5× one 22.6 s → 3.8 s, both still in sync).
- **Trims on the join path snap to the nearest frame:** a copied cut inside a frame is shown by Apple players but dropped by FFmpeg-based ones, which then started the picture up to a frame (16.7 ms measured) after the sound. Rendered clips snap the same way (every clip is whole source frames; a trim inside one frame keeps that frame), so the preview and every path cut on the same frames. Each clip's timeline length is then cut down to a whole ms (its end moves under 1 ms earlier), as on Android, so both export the same lengths.
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
- **Audio is decoded in large batches on Android 15+** (`BatchedAudioDecoder`, merge and conform): Transformer's own audio decoder queues one access unit per codec buffer and takes one decoded frame at a time, each a round trip to the codec's process (S24: ~2 ms per AAC frame). With the platform's large audio frames (`FEATURE_MultipleFrames`), one input buffer carries ~64 KB of access units and one output buffer up to 256 KB of PCM, as `extractAudio` already does; video, and a decoder that can't batch or doesn't start this way, stay on Media3's factory. S24 (2026-10-13): copied joins 16–40 % faster than on 2026-10-01 (2-minute join 12.2 → 8.6 s), lip sync unchanged (see [Benchmarks](benchmarks.md)); logcat says which decoder was used (`audio decoder: batched (c2.android.aac.decoder, input 64 KB, output 256 KB)`).
- **One-clip joins are copied:** a single-item composition ignores `setTransmuxVideo` and asks the encoder factory whether video needs encoding, and Media3's default factory says yes whenever encoder settings are given, so a one-clip draft was re-encoded (S24: 5.2 Mbps out of a 0.7 Mbps clip). A join now says no; encodes are unchanged. S24: the clip comes out at its own 0.7 Mbps, still in sync. When a join fails and falls back to an encode, the reason is logged.
- **Export errors are plain:** Media3's message can be a whole codec configuration dump; an export now fails with a short message per Media3 error code (couldn't decode a clip, couldn't encode, couldn't write the file…), and the full exception is logged (`adb logcat -s PulseEditor`), as are each encoder's measured priming and any encoder fallback.
- **Encoder priming** is measured with a 10 ms chirp (a single sharp correlation peak), at most 2 s, once per encoder and layout; a failed measurement falls back to Media3's value and isn't retried.
- **Known difference from iOS:** slowed-down clips come out at a variable frame rate (Media3's frame rate setting only caps it). They play correctly.

### Tested
- **iOS selective merges, read the way a server re-encode reads them (2026-10-14, iPhone 17 Pro Max, Debug, `ios-ios-wms-1014`, `ios-ios-drift-1014`, `ios-ios-quick-drift-1014`):** copied clips trimmed at their start are now rendered when other clips are, so the file has no pre-roll edits. Decoded by FFmpeg through the edit list, the 20-clip draft's export has 0 errors and 6319 frames, the timeline's count (before: 53 errors, 6305 frames), the 40-clip draft's 0 and 12548 (before: 107, 12516), and the quick pass's `sync-select` 0 (before: 4); every export has one video edit. `sync-select` takes 5.6 s (3.7 s before: its trimmed copied clips are rendered now). A rendered clip whose slot ends inside a frame (a trimmed 2× clip, 100.5 frames) was written a frame long, so every later clip started 16 ms late; it now ends at its slot (`trimedits` export 27.383 s, the timeline's; was 27.400). Preview against export on `trimedits` (trimmed + rotated, 2×, flipped + cropped, ½×): 16 of 20 (was 13), the 4 in the 2× clip. Lip sync on every path unchanged (0.0 / ±0.1 ms; speed −25.0 ms).
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
- **Checked before it's returned:** the output is probed (codec, layout, rotation, sound) and must be as long as the source's picture; a decoder that gives up early fails the conform instead of hiding behind a held frame. On Android an output with fewer than 80 % of the frames a clean decode gives (the source's samples from time 0 on, put through the frame drop's own rule) is rejected too: the platform decoder skips what it can't decode and carries on. Leftover conform files in the caches folder are deleted after a day.

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
  - Still open: on the emulator a full-range source's picture comes out 9–10 levels darker (full range read as limited), with or without that change; limited-range sources keep their levels exactly (180.9 → 180.4). It doesn't happen on the S24 (2026-10-13, below).
- **iPhone videos' second sound track:** every iPhone video carries a spatial-audio (APAC) track next to its stereo AAC, which Android can't decode. Test clips with a normal picture and a real iPhone video's two tracks (APAC second, as iPhones write it, and APAC first and default) keep the AAC sound on macOS, the iOS Simulator and the Android emulator, full and audio-only (output vs the source's AAC: correlation 0.999 at 0 ms).
- **Next to RNVT's `compress`, in Pulse:** on the iOS Simulator both convert to contract, and pulse-editor's cancel settles in 233–353 ms with nothing left behind. On the Android emulator RNVT's full conversion fails by its own bug (its FFmpeg command picks the `mpeg4` encoder and combines options FFmpeg rejects as contradictory); its audio-only one works.
- **Known differences between the platforms:** iOS fills frames to a constant 30 fps; Android drops frames down to 30 but doesn't add any, so a 24 fps or variable source stays variable (a 58 fps screen recording averaged 29.95 fps) and 29.97 fps stays 29.97. Both pass the import contract and the recorder match.
- **iPhone 17 Pro Max (2026-10-07, `ios-ios-conform`)**, in Pulse's debug build next to RNVT's `compress`, 42 inputs (the fixtures above plus the real-recording corpus: iPhone Dolby Vision HLG up to 4K120, screen recordings, 5.1 exports, messaging clips, full-range HEVC, both phones' recordings, the APAC test clips; 4 already matched): every output from both engines to contract, no FFmpeg decode errors; lip sync 0.0 / 0.1 ms (s48 / s44); cancel rejected 1256 ms after the start and settled 377 ms later with nothing left. The brightness check flags the 7 HDR sources at −7…−48 levels, the same as RNVT within a level (the tone map, not a bug). pulse-editor is faster on 33 of 38 (164.7 s against 171.7 s in total), slower on the audio-only conforms (c17 195 vs 153 ms, c20 776 vs 599 ms) and 4–5 % on two re-encodes. ConformJob runtime: 1080p re-encodes 5.0–7.1× realtime, 4K60 HEVC ~3×, 4K Dolby Vision 1.5× (24.5 s), audio-only 33–42×.
- **Galaxy S24 Ultra (2026-10-07, `android-s24-conform-1007`)**, the same 42 inputs through the app's import gate: 38 of 38 converted in ~5 minutes, HLG, PQ and Dolby Vision included (the emulator couldn't decode them; levels −39…−65 below the source = the tone map); lip sync 0.0 ms (s48, s44 and a sync clip tagged like a recording); cancel settled 15 ms after the abort with nothing left; 1080p re-encodes 4.3–7.2× realtime, 4K HEVC 27 s for 158 s, 4K Dolby Vision 19 s for 30 s. RNVT's full conform fails on the phone for every input by its own bug (as on the emulator); its audio-only conforms work and are 2–8× faster than pulse-editor's (c07 324 vs 785 ms). Found on the phone: (1) a corrupt source (`export 4.MP4`, 73 FFmpeg decode errors on the Mac) comes out with 746 of 3424 frames and 36 gaps over 0.5 s — Android drops what its decoder can't decode and keeps going, where AVFoundation conceals (the iPhone's output has every frame; Android rejects it since, below); (2) a 23.976 fps 10-bit HLG clip comes out at 26.4 fps average and a 4K HEVC clip at 29.3 fps — the source's own variable timing kept (the "doesn't pad frames" difference above), no decode errors; the bench's decode check had misread the variable timing as duplicate timestamps (the null muxer rounding them to the nominal rate), and the app's recorder-match check flags the rate.
- **Galaxy S24 Ultra (2026-10-13, `android-s24-fc-1013`)**: Android `conform` now reads the source's video sample times (nothing is decoded, but the whole file is read) and rejects the output when it has fewer than 80 % of the frames expected after the frame drop, so a damaged file fails closed instead of importing with holes. The corrupt source is rejected ("This phone couldn't decode all of the video (746 of 3430 frames)"); the six odd-timing inputs pass with every frame written (logcat `conform frames:` 239 of 239 expected for slow motion, the screen recording, VFR and NTSC, 720 of 720 for the 24 s 5.1 clip, 645 of 647 for the full-range HEVC screen recording c05, where 647 is the first version's rate ratio); no level shift on any (the emulator's 9–10 levels darker on full-range sources doesn't happen on the phone); cancel settled 3 ms after the abort, nothing left. On the emulator the same corrupt file fails earlier (its software decoder stalls and the export's 25 s watchdog aborts). Since then `expected` is the frame drop's own rule (Media3 1.11.1 keeps whole frames, so a constant 47 fps source keeps 235 of 470 at 30, not 300, and a burst of 60 fps then 1 fps keeps fewer than its average rate suggests), and only samples from time 0 on count (Transformer drops an edit list's preroll); on the Mac, from each file's timestamps, that rule gives what the S24 wrote for all six clean inputs (c05: 645, as written) and 3430 for the corrupt one. On the S24 with that rule (`android-s24-conform-pe-1013k-1`, see the status table) every clean source kept every frame (the 60 fps screen recording 645 of 645, a 60 fps clip 600 of 600) and the corrupt one was rejected (746 of 3430 frames).

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
- **Android:** Media3's `FrameExtractor` (`media3-inspector-frame`, exact seek, platform decoder, HDR tone-mapped), then the edit drawn on the bitmap with merge's geometry (rotate → flip → crop, as `MergeExport.editEffects`) and scaled to the fitted size; Media3 turns the frame upright itself, and a mirrored source gets its flip from us (as in conform). The extractor of the last file is kept for 4 s: Media3's extractors share one player that prepares again whenever the file changes, so later covers of the same file (a cover picker, a strip) pay only a seek; the file's size and modification time are part of its identity, so a file rewritten at the same path is read fresh. Its effects don't depend on the edit (only the mirror flip and a 1920 px bound), which is what lets one extractor serve every edit; covers run one at a time. The bound is applied before the edit, so on Android a cover larger than 1920 px, or a tight crop of a 4K source, comes out smaller than asked (iOS gives the full size).
- What RNVT's `getFrameAt` did differently: iOS cut the edit out of a decoded frame with its own code and allowed ±100 ms; on Android `MediaMetadataRetriever` with an FFmpeg fallback, which sized un-edited covers before turning them upright (61×108 instead of 144×256 for a portrait recording), ignored mirrored sources, and named files by the second (two covers in one second overwrote each other).

### Tested
- **iOS code on macOS:** every rotation, flip, crop and combination on a clip tagged like a recording matches merge's export of the same edit at the same frame (31–35 dB, two lossy encodes apart); the exact frame asked for (1500 ms → 1500 ms); a time past the end gives the last frame; iPhone HDR (1080p, 4K60, 4K120) tone-mapped, a screen recording, a mirrored clip; 20–30 ms per cover on an M-series Mac (120 ms for the first HDR one).
- **Android on the emulator**, through a Pulse debug build next to RNVT's `getFrameAt` with the same edits: all 8 edits show the same picture as RNVT's (by eye) and match an FFmpeg reference of the edit at 32–36 dB (the crop reference scores 25 dB on both platforms, an artifact of FFmpeg's crop rounding: the iOS and Android crop covers match each other at 36 dB); exact frames; real recordings, screen recordings, a mirrored clip (RNVT: sideways), slow motion; 150–600 ms per cover against RNVT's 50–300 ms (`FrameExtractor` starts a player per call; S24 times below). The emulator can't decode 10-bit HEVC, so HDR waits for the S24.
- **iOS Simulator, in Pulse next to RNVT:** every edit matches the FFmpeg reference at 37–40 dB; 20–160 ms per cover; a PQ HDR clip and a time past the end work where RNVT's `getFrameAt` fails ("Cannot Decode", "Cannot Open").
- **iPhone 17 Pro Max (2026-10-07, `ios-ios-thumb`)**, in Pulse next to RNVT's `getFrameAt`: 37 of 37 covers (8 edits on the recorder-shaped clip, HDR, rotated, square, slow motion, screen recording, past the end, the real-recording corpus), 22–50 ms each (slow motion 192 ms), 10–20 ms slower than RNVT per cover, with the better match to the FFmpeg reference on most; RNVT fails past the end and on a < 1 s clip and picks wrong frames on two corpus clips (15.8 and 5.9 dB).
- **Galaxy S24 Ultra (2026-10-07, `android-s24-thumb-1007`)**: 37 of 37 covers, the better match on most (RNVT's un-edited covers are still 61×108; it fails the < 1 s clip and ignores a mirrored clip's flip, 3.4 dB); 160–600 ms per cover (most 230–300; RNVT 90–630 ms), `FrameExtractor`'s player per call — fine for one cover, too slow for a thumbnail strip (a warm extractor was tried on 2026-10-06 and reverted: Media3 returned the previous request's frame when only the effects changed); since 2026-10-13 one warm extractor per file, with the edit drawn on the bitmap (next bullet).
- **Galaxy S24 Ultra (2026-10-13, `android-s24-thumb-1013`, one warm extractor per file, the edit drawn on the bitmap)**: the same covers as on 2026-10-07 (37 in both runs' result files), each 3 times: median per call 241 → 18 ms; repeats of a cover 9–44 ms (median 13); the 7 other edits of the recorder-shaped clip after its first cover 25–39 ms; the first cover of a file still pays the player, 136–481 ms (2026-10-07: 130–593). The match to the FFmpeg frame is the 2026-10-07 one within 0.9 dB on every item (the 8 edits within 0.2 dB); RNVT 113–563 ms, failing on c13 as on 2026-10-07.

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

A native view that plays a draft's clips, every edit applied, as `merge` would export them: the same timeline, the same trims, speeds and geometry, drawn at the view's size. Changing a clip (an edit) swaps the new timeline in at the same moment, holding the last frame until the new one is up. `snapshot()` and `stats()` exist for the bench (frame files and timings) and may go before release. So does `seekBench(uri, options)` (Android; rejects on iOS): a plain Media3 `ExoPlayer` on one file, no effects, drawing into a `TextureView` laid over the app for one pass, timing its prewarm, paused exact seeks and scrubbing-mode drags to on screen — the yardstick for what a plain player on the dragged clip would gain over `CompositionPlayer`.

- **Preview = export:** the preview is built from merge's own code, not a copy of it. On iOS both play `Timeline.compose` (the composition merge renders from); on Android both use merge's per-clip media items and effects. `options` tells the preview which clips merge will copy and which it will render, so it shows what that merge would make (since #12 both trim on the same frames, below).
- **"On screen" means on screen:** first frame, seeks and edits resolve when the new frame is displayed, not when it's decoded. iOS waits for the layer to show the new item; Android draws into a `TextureView` and counts the frames that reach it (below).

### How it runs

| | iOS | Android |
|---|---|---|
| Player | `AVPlayer` + `AVPlayerLayer`, the item built from `Timeline.compose` at the view's pixel size | one long-lived Media3 `CompositionPlayer` (experimental API) drawing into a `TextureView`, 100 ms start buffer |
| Seeks | Apple's chase pattern (QA1820): one exact seek at a time, always to the latest time asked for; a tolerance of one frame while scrubbing | exact seek to the start of the frame showing at that time (ExoPlayer shows the first frame at or after the position); Media3's scrubbing mode while dragging |
| Edits | a new item swapped in at the same time; done when the layer shows a frame of it | `setComposition` at the current position (rebuilds the players); done when a frame released after the swap reaches the view |
| Trims | both ends snap to the nearest source frame, copied or rendered (`Timeline.compose`); the clip's length is cut down to a whole ms | both ends snap to the nearest source frame (`MergePlan.windowUs`) and every clip's length is cut down to a whole ms, so each clip starts on a whole ms with its first frame, which a ms seek can reach |
| "On screen" signal | `isReadyForDisplay` + `displayedPixelBuffer`: a seek is done when the displayed buffer changes (display-link watch, 40 ms cap when the seek stays on the same frame, 300 ms otherwise); while playing, the item's video output | `TextureView.onSurfaceTextureUpdated`, matched to the frame's time through Media3's frame callback |

Why a `TextureView` on Android: `CompositionPlayer`'s frame callback fires when a frame enters its effects pipeline, before rotate, crop and the letterbox are drawn, so with a `SurfaceView` an edit was reported done while the old picture was still showing (4 of 6 edit snapshots wrong on the emulator). The `TextureView` reports every frame that reaches the view; drafts are SDR after `conform`, which a `TextureView` shows like a `SurfaceView`. Its frames carry no timestamp, so they're matched to Media3's callbacks in order.

### Tested
Through Pulse's debug build (bench mode `preview`) with four recorder-shaped test clips that carry each frame's number in the picture (readable through rotate, flip, crop and letterbox), on three drafts: four whole clips (join), trims inside frames (trims), and rotate/flip/crop/speed edits (edits). Each draft: the first frame, 20 seeks (every clip's first, middle and last frame, plus random times) with screenshots at +0, +50 and +150 ms, a 2 s scrub, two edits (rotate 180°, then 1.5×) with screenshots, and 8 s of playback with 4 screenshots. Every screenshot is compared with the **export** of the same draft on the same device.
- **iPhone 17 Pro Max (2026-10-02):** with the first version, `seek()` resolved before the layer showed the frame: 3, 1 and 1 of 20 seek screenshots per draft showed the export's frame at +0 ms, every one at +50 ms (seek p90 46–48 ms: decoded, not yet displayed). Fixed, a seek is done when the layer's displayed buffer changes (watched by a display link; 40 ms cap when the seek stays on the same frame, 300 ms otherwise), the video output is attached to each item, and a drag reports each landed frame through `onTime`: seeks show the export's frame 59 of 60 (the 1: the export shows one frame earlier at the end of the sped-up clip, the same quirk as on the Simulator), p90 64–79 ms; first frame 73–86 ms; edits on screen in 53–81 ms, all 6 showing the edit; ~31 pictures/s while dragging; playback 30.0–30.3 fps with nothing dropped, the frame on screen 33 ms ahead of the reported time. The fix's source was lost from disk that evening and replayed from the session record; the replayed copy is what the Simulator ran on 2026-10-06 and what this commit holds.
- **iOS Simulator (iPhone 17 Pro Max, 2026-10-06, the seek fix):** seeks show the export's frame 58–59 of 60 (the rest: the sped-up-clip quirk), p90 62–73 ms; first frame 31–73 ms; edits on screen in 27–80 ms, all showing the edit; 60 pictures/s while dragging; playback 29.1–29.5 fps, nothing dropped, the frame on screen 0–33 ms from the reported time. Play started 91–190 ms late, stable per draft across six runs (next bullet).
- **Play start on iOS (fixed on the Simulator):** after `play()` the reported time and the picture stood still for ~200 ms: `timeControlStatus` was playing with no waiting reason within 4–5 ms, but the item's timebase kept rate 0 for ~90 ms and then ran up from −108 ms, reaching the position ~210 ms after the call. That is `play()`'s scheduled start "at a host time in the near future … allowing some time for media data loading" (AVPlayer.h); nothing was loading. `play()` now starts the rate at the current host time (`setRate(1, time: .invalid, atHostTime:)`, allowed with `automaticallyWaitsToMinimizeStalling` off): start lag 0 ms on every draft (was 168–206 / 91–104), first new frame 18–49 ms after `play()` (was 216–249), seeks, edits and 29 fps unchanged, a restart after pause and a start inside a GOP also 0 ms. Tried first with no effect (each A/B'd on the Simulator): `preroll(atRate:)` after paused seeks, `playImmediately(atRate:)`, activating the `.playback` audio session at init. Audio with the new start: an audio tap on the item saw the first buffer enter the renderer 16–24 ms after `play()` (5–48 ms with `play()`), PTS 0.0 when playing from 0 in both, so nothing is skipped; sound at the speaker isn't measurable on the Simulator. On the iPhone since (2026-10-07, below): 0 ms, where it was 169–301 ms with `play()`.
- **Android emulator (Android 17, host GPU and host H.264 decoder, 2026-10-06):** six runs. Seeks show the export's frame 60 of 60 in every run, none late; edits 6 of 6 showing the edit; playback 26.6–28.7 fps, nothing dropped; first frame 114–554 ms; seek p90 295–325 ms with the Mac otherwise idle (794–943 ms in the one run that overlapped a Simulator boot), edits 183–366 ms (647–775 ms in that run); 3–8 pictures/s while dragging; the picture starts moving within −55 to +82 ms of `play()`. The frame identity, the edit path and the exact-seek rule hold on Android; the times are the emulator's and say nothing about the S24. These runs replace the software-decoder figures reported before (21–25 fps, seeks 0.6–1.8 s).
- **Frame identity on Android (found on the emulator):** the `TextureView`'s frames carry no timestamp there (`SurfaceTexture.getTimestamp()` = 0), so they're matched to Media3's frame callbacks in order (logcat: "Preview frames matched by order", once per draft). After a drag, frames the player had released but never drew stayed in the queue: the next edit was then never reported done (`lastSwapMs` stayed −1 while the screenshot already showed the edit; 2 of 3 runs before the change) and the settle seek after the drag ran into the 3 s seek timeout. Changed: the queue is cleared when the composition is rebuilt, and entries released more than 300 ms ago are dropped before a frame is matched by order. Since: no unreported edit in three runs (nor in one run before the change, so it was intermittent); the settle timeout still hit 1 of 3 drafts in the final run (4 of 7 before). The S24's frames carry no timestamp either ("matched by order" once per draft, 2026-10-07 below), so this applies there too.
- **`onTime` on Android** now reports the time of the frame on screen (the last frame that reached the view) instead of `player.currentPosition`. On the emulator the picture ran 33–133 ms ahead of the reported time while playing before the change (100 ms as often as 67 over 28 screenshots), 67 ms in 23 of 28 after it. What's left is the bench's extrapolation from an 80 ms tick plus the Nitro → JS hop, inferred. On the S24 (2026-10-13, [Preview](benchmarks.md#preview-pulsepreview)): 33–67 ms ahead with the composition only (11 of 16 at 67), 7 of 16 exact and 8 one frame off with the drag layer. iOS: 0–33 ms on the Simulator, 33 ms on the iPhone.
- **Chase seek on Android, tried and reverted:** a native queue that kept one `seekTo` in flight and the latest target waiting. ExoPlayer's scrubbing mode already drops intermediate seeks and waits for a frame before taking the next (`ExoPlayerImplInternal`, the seek queued while scrubbing), and the extra layer cut the emulator's drag picture rate from 5–8 to 1–2 pictures/s. Kept: every seek goes to the player at once, and every caller resolves when the latest target is on screen. From reading Media3 1.11.1, `CompositionPlayer` forwards only the scrubbing on/off flag to its inner players, not `ScrubbingModeParameters`. Each landed seek now logs its split (seek → frame released → on the view; on the emulator ≈ 90 % is decode and composition, 46–55 ms is effects and the view) and the default decoder per codec, for the phone runs.
- **A plain `ExoPlayer` on the same phone (`seekBench`, Galaxy S24 Ultra, 2026-10-13):** one 12 s recorder-shaped clip, four passes with a fresh player each (plain, low-latency decoding asked for, paused seeks in scrubbing mode, plain again). Debug APK (`android-s24-seekbench-1013b`): paused exact seeks p50 70 / p90 113 / p95 115 ms in the first pass (p95 115–155 over the four), against the composition player's p90 239–281 ms on 2026-10-07; exact drags 25–27.5 new pictures/s (forward 27.5 in the first pass; distinct frames, which in these runs equalled the view updates in every drag), against 8–10; a 0–250 ms tolerance gives 19–21.5/s and a 1 s tolerance or closest-keyframe seeks 1/s. Release APK (`android-s24-rel-seekbench-1013e-1`): paused seek p95 142–157 ms, exact drags 25.5–27/s — no faster. The S24's `c2.qti.avc.decoder` doesn't support low-latency decoding. So a plain player passes the drag target (≥ 20 pictures/s exact) and halves the paused-seek time without reaching ≤ 100 ms. The bench's settle seek after each drag timed out on every drag in both builds. A likely cause, from reading the code in review: the drag's releases that never drew stayed queued, and with frames matched by order (23–25 per pass in both runs) the settle frame was matched to one of them; the queue is now cleared before each seek, not yet re-run.
- **How the drag and start numbers are read:** "pictures/s while dragging" counts the frames that reached the view during the 2 s drag (iOS: the item's video-output buffers; Android: `TextureView` frames); on Android a drag's intermediate frames aren't reported to JS, so the bench's "seeks landed/s" reads lower there than the pictures drawn. "Play-start lag" is wall time minus video time played over 8 s of playback.
- **Found and changed on the way (2026-10-02):** iOS trimmed copied clips at the exact millisecond while merge's join starts them on the nearest frame (the preview showed one frame earlier: 7 of 20 trims seeks matched, now 20 of 20); the Android `SurfaceView` timing above; on Android the preview's audio trim now starts from the seek position (`TrimAudioProcessor`); exports are unaffected (the emulator's trimmed export has its clicks exactly at the trims, the same as iOS's to within the frame rounding).
- **Trims are whole frames on both platforms:** both ends snap to the nearest source frame (iOS: on the track's shortest frame duration, `minFrameDuration`; Android: on its average rate; the same grid for constant-rate recordings, while a variable-rate clip's cuts can land inside frames on either), in the window merge and the preview both read, and a trim inside one frame is that frame. Every clip's timeline length is then cut down to a whole ms (under 1 ms off its end), so every clip starts on a whole ms; on Android a ms seek to a fractional start showed the previous clip's last frame or skipped the first one. Phones (2026-10-14, Debug, iPhone 17 Pro Max `ios-ios-wms-1014`, Pixel 6a `android-pixel-preview-wms1014-1`): Pixel trims 20 of 20 seeks against the export (13 of 20 with whole frames but fractional lengths), 20 clips 30 of 30 (28), 40 clips 38 of 38 (30), join, edits and mute 20 of 20 each; iPhone unchanged (20, 20, 19, 20; 20 clips 26–27 of 30, the misses in the 2× clip; 40 clips 38 of 38); `trimedits` (trimmed + rotated, 2×, flipped + cropped, ½×) Pixel 17 of 20, iPhone 16 of 20 with #14 (was 13), every miss in the 2× clip. Pixel quick pass (`android-pixel-quick-wms1014-1`): lip sync on every path as before (0.0 ms; speed −17.5), 0 decode errors; the trimmed join is 16 ms shorter than asked (was +5: its ends moved to the nearest frame). A join of untrimmed clips whose lengths aren't whole ms (`oddjoin`: 11966.667, 11925.333, 11900 and 11866.667 ms) is clipped to the same windows: the Pixel's export starts its clips at 11.966, 23.891 and 35.791 s, the preview's slots (`android-pixel-oddjoin-1014b`, 20 of 20 seeks against the export). iOS cuts an untrimmed clip at its video track's end, Android at the file's (here the audio runs 25 ms longer on one clip), so the two platforms' slot lengths can differ by a recording's audio/video length gap; each matches its own export (the iPhone's `oddjoin` starts 11.966, 23.866 and 35.766 s; 20 of 20 seeks). The S24 hasn't run these changes.
- **iPhone 17 Pro Max (2026-10-07, this code, `ios-ios-preview-t3`):** seeks show the export's frame 20 / 20 / 19 / 20 of 20 (join, trims, edits, mute; the 1: the sped-up-clip quirk), p95 61–63 ms; first frame 56–122 ms (122 cold); edits on screen in 41–92 ms, all 8 showing the edit; 58–60 pictures/s while dragging (62.5 is the drag's ceiling); playback 30.1–30.2 fps, nothing dropped, 7 of 12 playback screenshots exact and 5 one frame off (all 12 one frame off on 2026-10-02); play starts in 0 ms on every draft, from 0, after a pause and from inside a GOP (169–270 ms on 2026-10-02). Traces on the phone: the clock starts at `play()`, the first new frame reaches the layer 34–50 ms later. **Open, by ear:** after a play straight from a paused seek the first audio buffer reaches a test tap 106–135 ms after `play()` (after a pause, 4–34 ms; Simulator 16–24 ms) — the tap can't tell late from cut, so the fixture's click 0.5 s into the play is to be listened to; `preroll(atRate:)` after the seek doesn't help (the bench presses play 23 ms later, which cancels it; `ios-ios-ab-preroll`). A coarser seek tolerance while dragging has nothing to gain: the drag already seeks with a one-frame tolerance and lands 58–60 of a possible 62.5 pictures/s. **Since (iPhone, 2026-10-13):** the late first sound is the idle audio output, not the seek. The first buffer reached the tap 121 ms after `play()` on the first play and 125 ms after ~10 s paused, but 2 ms right after a seek, 2–9 ms after 2–4 s paused and 62 ms after 6 s (`ios-ios-audio-step0`, `ios-ios-audio-idle-1013`), with the clock already running, so the opening ~120–160 ms of sound was lost (from the trace). When the output is cold (nothing played yet, or ≥ 5 s since audio stopped) and the draft has sound, `play()` now starts the clock 160 ms in the future: the first buffer arrives 115–126 ms after `play()`, before the clock starts, at the play position (nothing cut, from the trace; still to be heard), and the picture holds 161 ms on those plays; every other play starts in 1 ms, and a draft without sound never waits (`ios-ios-audio-coldlead-1013b`; Release `ios-ios-rel-audio-1013`: a 4 s pause starts at once, a 6 s pause holds 161 ms). The rest of the preview in Release (`ios-ios-rel-preview-1013`): seeks show the export's frame 77 of 80 (the 3: the sped-up-clip quirk), p90 56–64 ms; first frame 61–86 ms; edits on screen in 42–99 ms, all 8 showing the edit; 54–62 pictures/s while dragging; 30.1–30.2 fps, nothing dropped.
- **Galaxy S24 Ultra (2026-10-07, this code, `android-s24-preview-1007`):** seeks show the export's frame 80 of 80, edits 8 of 8 showing the edit, playback 29.0–29.6 fps with nothing dropped, first frame 119–481 ms (481 cold) — the frame identity holds on hardware. The speed targets are missed: seek p90 239–281 ms (target p95 ≤100), 8–10 pictures/s while dragging (≥20), edits on screen in 288–491 ms (≤400; the 1.5× swaps over), play starts 118–173 ms after a paused seek. Where a seek goes (`seeklog.py`, 85 seeks, hardware `c2.qti.avc.decoder`): seek → frame released median 141 ms (p90 227), released → on screen median 24 ms (p90 38), so it's Media3's decode and composition, not the `TextureView`. The S24's `TextureView` frames carry no timestamp either ("matched by order" once per draft), the settle seek after the trims drag hit the 3 s timeout once, no player-release timeout in the run. Inference: faster drags on Android need a Media3 change (`CompositionPlayer` forwards only the scrubbing flag) or a plain player on one clip for trim drags; the drag layer (#10) is that plain player (2026-10-13, below).
- **Galaxy S24 Ultra (2026-10-13, `android-s24-preview-1013`), two fixes from that run:** (1) the settle seek after a drag could go to the millisecond of a seek already on its way, which ExoPlayer skips, so that seek's frame was the only one coming and after a drag it could be lost (the trims draft's 3 s timeout); a seek to the millisecond last sent to the player now has a throwaway seek to another millisecond right before it, so it's a real seek. The settle after the drag now lands in 147–191 ms on every draft (2026-10-07: 189–232 ms, and the 3014 ms timeout on trims). Measured with the first version of that rule (the repeat itself went 1 ms earlier, compared with the seek in flight, not while scrubbing) and a 30 s back buffer since removed; the current rule, changed after review, ran in the drag-layer run (`android-s24-preview-1013o-1`, #10's head, which contains it): settle 120–189 ms, no timeouts. (2) A frame that reaches the view while paused with no seek waiting (released before a pause and drawn after it, or an edit swapped in) now updates `onTime`, so the reported time is the frame on screen while paused too: the restart after a pause reads +69…+78 ms, where 2026-10-07 read −89…+11 ms (a negative start lag is impossible: the reported paused time wasn't the frame on screen). Otherwise as on 2026-10-07: seeks show the export's frame 80 of 80, edits 8 of 8 showing the edit (on screen in 245–407 ms), playback 29.0–29.4 fps with nothing dropped, first frame 119–260 ms; seek p90 199–294 ms and 9.4–12 pictures/s while dragging miss the targets (drags: the drag layer since; paused seeks: accepted, see [Preview](benchmarks.md#preview-pulsepreview)).
- **Not measured:** lip sync during preview playback on either phone (needs in-app audio capture). The compare benches run on 2026-10-07 were on hot phones at 20 % battery and are not used for the tables below.
