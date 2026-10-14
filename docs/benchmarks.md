[README](../README.md) · [Methods](methods.md) · [Benchmarks](benchmarks.md) · [Differences](differences.md) · [Timeline editor](timeline-editor.md) · [Migration](migration.md) · [Roadmap](roadmap.md)

# Benchmarks (on device)

Measured on 2026-10-13 on an **iPhone 17 Pro Max** (iOS 26.5.1) and a **Galaxy S24 Ultra** (Android 16), each running a Pulse debug build of #240 with pulse-editor and react-native-video-trim (RNVT, what Pulse merged with before) side by side; the real-recording tables are from 2026-10-01. The same day each phone also ran a release build of the same code ([Debug and release builds](#debug-and-release-builds)). Runs on 2026-10-14 (Debug, the iPhone and a **Pixel 6a**, Android 17 beta) cover #12 and #14; the S24 hasn't run them. The live copy of these tables is on [Pulse #242](https://github.com/mieweb/pulse/issues/242).

**How it's measured.** A temporary bench screen in the Pulse debug build (kept out of the repo) runs the same drafts through both engines on the phone, alternating which goes first, two runs each, and reports the median. RNVT gets exactly what Pulse used to pass it (`merge(urls, { outputExt: 'mp4', ...REELS_TARGET, clipEdits })`). The outputs are pulled to a Mac and checked:

- **Lip sync:** the sync drafts are built from `assets/dev/sync` clips, which flash white and click at the same instant every second. The worst audio − video offset over every event is read twice: as FFmpeg decodes the file (browsers, servers) and as AVFoundation does (Photos, Safari, iOS). Under one frame (33 ms) is in sync; a 2× clip can't do better than half a frame, because the source's frames land between the output's.
- **Duration:** the output against the sum of the clips' windows.
- **Playability:** faststart (`moov` before `mdat`) and FFmpeg decode errors.
- **Drafts:** recorder-shaped test clips (H.264 1080p30 portrait, no B-frames, like real recordings), the seed drafts (`+ s2`–`+ s5`), and each phone's own recording.

**What the phone runs found, and what changed** (all re-measured on the phones):

- Android exports played 44 ms early on Apple players: Media3's muxer omits the AAC roll sample group, so AVFoundation trimmed the encoder priming twice (see [How a merge runs (Android)](methods.md#how-a-merge-runs-android)). Fixed: 0.0 ms in both players.
- iOS joins drifted ~10 ms per clip in browsers (copied AAC padding; Apple players were fine). Fixed by re-encoding multi-clip audio: 0.0 ms in both, at the cost of joins taking 1.3–2.3× RNVT's time on the iPhone (2-minute join 1.6 s against 1.2 s on 2026-10-13, 1.0 against 0.58 s on 2026-10-01).
- Android extractAudio was ~2 ms per AAC frame (one frame per codec round trip); batching frames on Android 15+ made it 5–9× faster on the S24 (2-minute draft 9.0 → 1.0 s).
- Android one-clip drafts were re-encoded instead of copied; now copied.
- Android joins feed the audio encoder in larger buffers: 21–34% faster on the S24. Since (2026-10-13), Android 15+ also decodes the audio in large batches: the 2-minute join 12.2 → 8.6 s against 2026-10-01 while RNVT's own time on the phone stayed 23.1–23.2 s, lip sync unchanged.
- iOS selective merges lost frames in FFmpeg-based readers at copied joins trimmed at their start (a second sample description, [trac #6584](https://trac.ffmpeg.org/ticket/6584)), which a server re-encode such as PulseVault's then holds over (up to 267 ms). Those clips are now rendered too, and a rendered clip ends at its slot (a 2× clip of 100.5 frames was written 101, so every later clip started 16 ms late): #14, iPhone 2026-10-14, 20-clip export 53 → 0 decode errors (6305 → 6319 frames, the timeline's count), 40-clip 107 → 0 (12516 → 12548), `sync-select` 4 → 0, which now takes 5.6 s (was 3.7).
- On Android a seek to a clip that started on a fractional ms showed the previous clip's last frame. Every trim is now whole source frames and every clip a whole ms on both platforms (#12): Pixel 6a 2026-10-14, trims 20 of 20 seeks on the export's frame (was 13), 20 clips 30 of 30 (28), 40 clips 38 of 38 (30).
- Recordings have no B-frames on either phone, so edited iPhone drafts take the selective path; the recorder-match test clips were regenerated to match.
- Known and accepted: Android joins are audio-pipeline bound (~10× realtime on the S24 on 2026-10-01; the 3-clip, 2- and 8-minute joins 12–14× realtime with the batched decoder in a debug APK and 18–21× in a release APK on 2026-10-13 (the joins bench; the compare run's 8-minute join reads 11.7×); the rest of the phone is idle, Media3 processes audio serially). A trimmed clip on Android can end with a 1–2 ms sliver frame at a clip boundary (invisible; FFmpeg's strict check notes it).
- **Debug and release builds** (2026-10-13, same code, same day): the S24's release APK joins 25–35% faster than its debug APK; its re-encoded drafts, preview and seeks are no faster. iPhone Release merges land within −19…+18% of Debug, mostly slower in Release (7 of 11 drafts slower, 4 faster); `extractAudio` is 15–43% faster in Release. So these debug tables hold for iOS merges; on Android a release APK joins about a third faster (debug 1.3–1.5× release) ([Debug and release builds](#debug-and-release-builds)).
- **The iPhone was slower on 2026-10-13 than on 2026-10-01 / 2026-10-07, for both engines:** copied joins 1.2–1.9× slower with pulse-editor and 1.1–2.3× with RNVT, whose code hasn't changed (its submodule pointer is the same); pulse-editor's encodes 1.0–1.1×; covers 2.3× slower for both engines (median, Release, against 2026-10-07's Debug run) and conforms 1.1×. The S24 on the same day: RNVT within −3…+11% of 2026-10-01. Inference: the iPhone, not the code; the cause isn't known (same iOS 26.5.1; whether Low Power Mode was on wasn't checked). The ratios between the engines held (pulse-editor's iPhone joins 1.3–1.9× RNVT's, 1.7–2.3× on 2026-10-01).

Where pulse-editor is slower than RNVT or differs, and why: [Differences](differences.md).

## Lip sync and output checks

**iPhone 17 Pro Max (iOS 26.5.1)**, 2026-10-13 (`ios-ios-compare-1013`): worst audio − video offset over every event, ms (+ = sound late)

| Draft | pulse-editor, browsers (FFmpeg) | pulse-editor, Apple (AVFoundation) | RNVT, browsers | RNVT, Apple | pulse-editor output |
|---|---|---|---|---|---|
| Sync draft: join (4 × 12 s) | 0.0 | 0.0 | 32.0 | 0.0 | faststart, 0 decode errors |
| Sync draft: 48 + 44.1 kHz audio | 0.1 | 0.1 | 32.0 | 0.0 | faststart, 0 decode errors |
| Sync draft: rotate + crop + trim | 0.0 | 0.0 | 26.7 | 0.0 | faststart, 4 decode errors (0 with #14, below) |
| Sync draft: 2× / muted / 0.5×, 48 + 44.1 kHz | -25.0 | -25.0 | 496.5 | -34.0 to -58.5 * | faststart, 0 decode errors |
| Sync draft: trimmed starts | 0.0 | 0.0 | 27.4 | 0.0 | faststart, 0 decode errors |

**Galaxy S24 Ultra (Android 16)**, 2026-10-13 (`android-s24-compare-1013h-1`): worst audio − video offset over every event, ms (+ = sound late)

| Draft | pulse-editor, browsers (FFmpeg) | pulse-editor, Apple (AVFoundation) | RNVT, browsers | RNVT, Apple | pulse-editor output |
|---|---|---|---|---|---|
| Sync draft: join (4 × 12 s) | 0.0 | 0.0 | -12.0 | -12.0 | faststart, 0 decode errors |
| Sync draft: 48 + 44.1 kHz audio | 0.0 | 0.0 | 15.4 | 15.4 | faststart, 0 decode errors |
| Sync draft: rotate + crop + trim | 0.0 | 0.0 | 10.7 | 10.7 | faststart, 0 decode errors |
| Sync draft: 2× / muted / 0.5×, 48 + 44.1 kHz | -17.5 | -17.5 | -24.0 | -24.0 | faststart, 0 decode errors |
| Sync draft: trimmed starts | 0.0 | 0.0 | -16.6 | -16.6 | faststart, 0 decode errors |

Every value on both phones, for both engines, is the same as on 2026-10-01, except the one marked *: the AVFoundation read of RNVT's 2× draft gives −34.0 or −58.5 ms on the same file from one run of the checker to the next (2026-10-01: −58.5; pulse-editor's values don't move); the iPhone's Release run (`ios-ios-rel-compare-1013`) and the S24's sync drafts in a separate run (`android-s24-sync-1013`, including an edited draft with a 2× clip: −17.5 ms) give the same again.

**2026-10-14 quick passes, with #12 and #14** (Debug; iPhone `ios-ios-quick-drift-1014`, Pixel 6a on Android 17 beta `android-pixel-quick-wms1014-1`; the S24 hasn't run them):

| | iPhone 17 Pro Max | Pixel 6a |
|---|---|---|
| Lip sync, every merge path | 0.0 / ±0.1 ms, unchanged | 0.0 ms, unchanged |
| Sync draft with a 2× clip | −25.0 ms | −17.5 ms |
| Decode errors | 0 (`sync-select` was 4) | 0 |

On both phones the trimmed join comes out 16–21 ms shorter than asked: its ends are at the nearest frame (the Pixel's was +5 ms before #12).

## Speed

**iPhone 17 Pro Max (iOS 26.5.1)**, 2026-10-13 (`ios-ios-compare-1013`): median of 2 runs each (the slower of the two), engines alternating

| Draft | pulse-editor | RNVT | pulse-editor path | Output duration vs clips (pulse-editor / RNVT) |
|---|---|---|---|---|
| Join 3 recorder clips (18 s) | 397 ms | 205 ms | copied | +0 ms / +0 ms |
| Join 20 clips (2 min) | 1.6 s | 1.2 s | copied | +0 ms / +0 ms |
| Join 20 clips (8 min) | 7.5 s | 4.8 s | copied | +0 ms / +0 ms |
| 6 clips: trim, rotate+flip, crop, 2×, 0.5× muted | 3.2 s | 2.9 s | re-encoded (all or some clips) | +0 ms / +0 ms |
| 20 clips, 6 in other formats (HEVC, 60 fps, 4K, landscape) | 18.6 s | 12.2 s | re-encoded (all or some clips) | +0 ms / +0 ms |
| 12 hostile imports (HDR, Opus, no audio, VFR, 120 fps…) | 15.8 s | 17.5 s | re-encoded (all or some clips) | -81 ms / -37 ms |
| Sync draft: join (4 × 12 s) | 454 ms | 235 ms | copied | +0 ms / +0 ms |
| Sync draft: trimmed starts | 383 ms | 204 ms | copied | -20 ms / +0 ms |
| Sync draft: 2× / muted / 0.5×, 48 + 44.1 kHz | 2.8 s | 2.5 s | re-encoded (all or some clips) | +0 ms / +0 ms |
| Sync draft: rotate + crop + trim | 3.7 s | 4.2 s | re-encoded (all or some clips) | +0 ms / +0 ms |
| Sync draft: 48 + 44.1 kHz audio | 680 ms | 403 ms | copied | +0 ms / +0 ms |

**Galaxy S24 Ultra (Android 16)**, 2026-10-13 (`android-s24-compare-1013h-1`): median of 2 runs each (the slower of the two), engines alternating

| Draft | pulse-editor | RNVT | pulse-editor path | Output duration vs clips (pulse-editor / RNVT) |
|---|---|---|---|---|
| Join 3 recorder clips (18 s) | 1.8 s | 3.8 s | copied | +5 ms / +48 ms |
| Join 20 clips (2 min) | 8.6 s | 23.2 s | copied | +0 ms / +320 ms |
| Join 20 clips (8 min) | 41.0 s | 81.2 s | copied | +0 ms / +0 ms |
| 6 clips: trim, rotate+flip, crop, 2×, 0.5× muted | 4.2 s | 8.2 s | re-encoded (all or some clips) | +11 ms / +16 ms |
| 20 clips, 6 in other formats (HEVC, 60 fps, 4K, landscape) | 18.7 s | 32.5 s | re-encoded (all or some clips) | +0 ms / +320 ms |
| 12 hostile imports (HDR, Opus, no audio, VFR, 120 fps…) | 14.6 s | 25.6 s | re-encoded (all or some clips) | +0 ms / +36 ms |
| Sync draft: join (4 × 12 s) | 3.6 s | 8.2 s | copied | +0 ms / +43 ms |
| Sync draft: trimmed starts | 5.1 s | 7.2 s | re-encoded (all or some clips) | +5 ms / +58 ms |
| Sync draft: 2× / muted / 0.5×, 48 + 44.1 kHz | 5.6 s | 7.8 s | re-encoded (all or some clips) | +5 ms / +9 ms |
| Sync draft: rotate + crop + trim | 6.0 s | 8.9 s | re-encoded (all or some clips) | +16 ms / +11 ms |
| Sync draft: 48 + 44.1 kHz audio | 3.7 s | 8.2 s | copied | +0 ms / +34 ms |

On the S24 against 2026-10-01: pulse-editor's copied drafts (the joins and the two copied sync drafts) 8–29% faster with the batched audio decoder, though the join test files were heavier on 2026-10-13 (2.9 against 2.2 MB for 3 clips); the 2× draft 28% faster; its other encodes −3…+5%; RNVT's own times −3…+11%. pulse-editor's time is 0.37–0.71× RNVT's on every draft (0.50–0.96× on 2026-10-01).

**Galaxy S24 Ultra (Android 16)**, 2026-10-13, debug APK against release APK: pulse-editor only, a separate joins bench, median of 3 runs (`android-s24-joins-1013`, `android-s24-rel-joins-1013e-1`). Its debug times differ a little from the compare run above on the same drafts (3-clip join 1.4 vs 1.8 s, 8-minute 37.3 vs 41.0 s, sync join 3.1 vs 3.6 s): two runs, two medians

| Draft | Debug APK | Release APK | Release vs debug |
|---|---|---|---|
| Join 3 recorder clips (18 s) | 1.4 s | 1.0 s | −29% |
| Join 20 clips (2 min) | 8.6 s (14× realtime) | 5.8 s (21×) | −32% |
| Join 20 clips (8 min) | 37.3 s (13×) | 24.2 s (20×) | −35% |
| Sync draft: join (4 × 12 s) | 3.1 s | 2.4 s | −25% |
| Sync draft: trimmed starts (re-encoded) | 5.1 s | 5.5 s | +9% |
| Sync draft: rotate + crop + trim (re-encoded) | 5.8 s | 6.0 s | +2% |

## Debug and release builds

The tables above are from debug builds (Android: a debuggable APK; iOS: the Debug configuration). On 2026-10-13 each phone also ran a release build of the same code, the same day.

- **iPhone, Release vs Debug** (`ios-ios-rel-compare-1013` vs `ios-ios-compare-1013`, both engines, 2 runs each): pulse-editor's merges −19…+18%, mostly slower in Release: 7 of 11 drafts slower, 4 faster (48 + 44.1 kHz sync draft 549 vs 680 ms, −19%; 3-clip join 364 vs 397 ms; 2-minute join 1880 vs 1595 ms, +18%; 8-minute join 8.8 vs 7.5 s, edited draft 3.5 vs 3.2 s, mixed 20.0 vs 18.6 s); RNVT the same picture. Inference: merges are bound by AVFoundation, not by Swift built without optimization, so the Debug tables stand. `extractAudio` is faster in Release (2-minute draft 391 vs 690 ms, 24 s 5.1 clip 176 vs 262 ms, Opus 141 vs 165 ms; inference: its resampling loop is Swift that Debug doesn't optimize). Lip sync, durations and decode errors identical. The preview in Release: [Preview](#preview-pulsepreview), in line with Debug on 2026-10-07.
- **S24, release APK vs debug APK** (non-debuggable; results read from logcat, so timings only: output files and screenshots stay on the phone): copied joins 25–35% faster (table above), re-encoded drafts +2…+9%, preview seeks, drags and edits within each other's spread ([Preview](#preview-pulsepreview)), the plain-player seek bench no faster (paused seek p95 142–157 against 115–155 ms). Inference: a join's time is Media3's audio pipeline running as Java/Kotlin, which runs slower in a debuggable app; encodes, decodes and seeks wait on the hardware codecs.

## probe and extractAudio

**iPhone 17 Pro Max (iOS 26.5.1)**, 2026-10-13 (Debug; Release in brackets, `ios-ios-rel-compare-1013`)

| Method | pulse-editor | RNVT |
|---|---|---|
| probe, 26 test files (median, first read) | 8 ms (8) | 106 ms (106) |
| extractAudio, 24 s 5.1 clip | 262 ms (176) | 63 ms (60) |
| extractAudio, 2-minute draft | 690 ms (391) | 103 ms (100) |
| extractAudio, 8 s Opus clip | 165 ms (141) | 37 ms (20) |
| extractAudio, clip without audio | 4 ms (3) | fails |

**Galaxy S24 Ultra (Android 16)**, 2026-10-13

| Method | pulse-editor | RNVT |
|---|---|---|
| probe, 26 test files (median, first read) | 24 ms | 117 ms |
| extractAudio, 24 s 5.1 clip | 402 ms | 98 ms |
| extractAudio, 2-minute draft | 1.0 s | 211 ms |
| extractAudio, 8 s Opus clip | 140 ms | 47 ms |
| extractAudio, clip without audio | 7 ms | fails |

S24 `extractAudio` in a quick run the same day (`android-s24-quick-1013g-2`): 51–126× realtime over five clips with sound, and a merge cancelled mid-way settled 12 ms after the abort with nothing left. The iPhone's own-recording clip failed to extract for both engines on 2026-10-13 (not in the table; not investigated; 46 ms on 2026-10-01).

## Real recordings

**iPhone 17 Pro Max (iOS 26.5.1)**, its own recording, 2026-10-01

| Draft | Time | Path | Output |
|---|---|---|---|
| Real recording × 3 (join) | 216 ms | copied | 23.30 s, 5.4 Mbps, 0 decode errors |
| Real recording alone | 44 ms | copied | 7.77 s, 5.4 Mbps, 0 decode errors |
| Real recordings: as is / rotated / trimmed / 2× | 2.0 s | re-encoded (all or some clips) | 23.93 s, 5.3 Mbps, 0 decode errors |

**Galaxy S24 Ultra (Android 16)**, its own recording, 2026-10-01

| Draft | Time | Path | Output |
|---|---|---|---|
| Real recording × 3 (join) | 2.5 s | copied | 22.31 s, 6.0 Mbps, 0 decode errors |
| Real recording alone | 886 ms | copied | 7.47 s, 6.0 Mbps, 0 decode errors |
| Real recordings: as is / rotated / trimmed / 2× | 3.4 s | re-encoded (all or some clips) | 23.10 s, 5.5 Mbps, 1 FFmpeg timestamp warning (see below) |

## Preview (`<PulsePreview>`)

Bench mode `preview`, as described under [`<PulsePreview>` → Tested](methods.md#tested-4): four drafts (join, trims, edits, all-muted) of recorder-shaped clips carrying each frame's number, 20 seeks each with screenshots against the export of the same draft, a 2 s drag, two edits, 8 s of playback, then play-start rows. 2026-10-13.

| (target) | iPhone 17 Pro Max, Release (`ios-ios-rel-preview-1013`) | S24 with the drag layer, debug APK (`android-s24-preview-1013o-1`) | S24, composition only, debug APK (`android-s24-preview-1013`) | S24, composition only, release APK (`android-s24-rel-preview-1013e-1`, timings only) |
|---|---|---|---|---|
| Seeks on the export's frame | 77 of 80 (the 3: the export lags a frame at the end of the sped-up clip; the preview shows the expected frame) | 80 of 80 | 80 of 80 | not checked |
| Seek p90 (p95 ≤ 100 ms) | 56–64 ms | 202–281 ms (paused seeks stay on the composition) | 199–294 ms | 206–247 ms |
| First frame (≤ 300 iPhone / ≤ 500 S24) | 61–86 ms | 85–192 ms | 119–260 ms | 112–218 ms |
| Edit on screen (≤ 250 / ≤ 400) | 42–99 ms, 8 of 8 showing the edit | 165–354 ms, 8 of 8 showing the edit | 245–407 ms, 8 of 8 (the trims draft's 1.5× swap: 407) | 246–369 ms |
| Playback (≥ 29 fps, ≤ 1% dropped) | 30.1–30.2 fps, 0 dropped | 28.7–29.2 fps, 0 dropped (trims 28.7) | 29.0–29.4 fps, 0 dropped | 29.2–29.5 fps, 0 dropped |
| Picture vs reported time while playing | 10 of 16 exact, 6 one frame off | 7 of 16 exact, 8 one frame off, 1 two | 33–67 ms ahead (11 of 16 at 67) | not read |
| While dragging (≥ 20 pictures/s) | 54–62 pictures/s | 30.2–31.3 seeks landed/s, 51–54 view updates/s; a drag into another clip shows it in 63–162 ms; the drag picture matched the composition's frame at the same time in 16 of 16 checked pairs (PSNR ≥ 51.8 dB) | 9.4–12 pictures/s, 0.5 seeks landed/s | 9.4–13.5 pictures/s |
| Settle after the drag | 33–100 ms | 120–189 ms, no timeouts | 147–191 ms (2026-10-07: a 3 s timeout on the trims draft) | 145–272 ms |
| Play-start lag | 1 ms; 161 ms on a cold play (first play, or after ≥ 5 s paused) by design: the cold-audio lead; the all-muted draft 1 ms throughout | first play 128–245 ms, after a pause 10–120, after a paused seek 120–154 | first play 175–219 ms, after a pause 69–78, after a paused seek 136–239 | first play 169–247 ms, after a pause 37–105, after a paused seek 138–206 |
| Lip sync while playing | not measured | not measured | not measured | not measured |

- **iPhone:** every target met in Release, as in Debug on 2026-10-07 (seek p95 61–63 ms, first frame 56–122, edits 41–92, 58–60 pictures/s). The cold-audio lead gates on the clock: in Release a 4 s pause starts at once and a 6 s pause holds 161 ms (`ios-ios-rel-audio-1013`), as the 5 s rule says. On main (Debug, `ios-ios-main-preview-1013`, the join and all-muted drafts): seeks 40 of 40 on the export's frame, p90 59–64 ms; first frame 54–72 ms; edits 43–148 ms, all showing the edit; 48–61 pictures/s while dragging; 30.1–30.2 fps, nothing dropped; the 160 ms lead only on cold plays with sound (the join draft's first play and its play after 10 s paused), every warm play and every play of the all-muted draft 0 ms.
- **S24, composition only:** the frames are right (80 of 80 seeks, 8 of 8 edits) and first frame, playback and edits meet their targets (one 1.5× swap at 407 ms); paused seeks (p90 199–294 ms) and drags (9–13 pictures/s) don't, in either build (drags: the drag layer, below; paused seeks: accepted). The settle seek after a drag lands in 145–272 ms (was a 3 s timeout). The restart after a pause reads +37–105 ms, where 2026-10-07 read impossible negatives (−22…−89): `onTime` reports the frame on screen while paused too, so these lags are honest.
- **Plain ExoPlayer on the same phone** (seek bench, `android-s24-seekbench-1013b` debug and `android-s24-rel-seekbench-1013e-1` release, one 12 s recorder-shaped clip, four player setups each): exact drags 25–27.5 new pictures/s (passes ≥ 20), paused seeks p95 115–157 ms (misses ≤ 100); low-latency decoding isn't supported by the S24's `c2.qti.avc.decoder`. So a plain player fixes drags and halves the paused-seek time, without reaching the seek target.
- **Play start after a seek on the S24 (accepted):** 120–239 ms in every build and with or without the drag layer, against 10–120 ms after a pause and 1 ms on the iPhone. From reading Media3 1.11's code (not measured): every seek flushes the audio sink, which releases the AudioTrack, and the picture follows the audio clock, which stands still until the new track has played its first sample. A low-latency AudioTrack for the preview was tried on the phone and left out (no measurable gain). Accepted, as the paused seeks are (Media3 `CompositionPlayer`, tried 2026-10-13: paused seek p90 200–340 ms, play ~150 ms after a paused seek).
- **Android drag layer** (pulse-editor #10, 2026-10-13): while a finger drags, a plain ExoPlayer on the dragged clip draws the pictures; paused seeks, edits and playback stay on the composition. Run `android-s24-preview-1013o-1` (the merged branch's head, debug APK): about 31 seeks land per second while dragging (the composition alone: 0.5); a drag into another clip shows it in 63–162 ms; the drag picture matched the composition's frame at the same time in 16 of 16 checked pairs (PSNR ≥ 51.8 dB); the settle after release 120–189 ms with no timeouts; seeks still 80 of 80 on the export's frame and edits 8 of 8. Tried and left out: paused tap-seeks on the layer (wrong frames and settle timeouts, `android-s24-preview-1013j-1`: 14 and 16 of 20 seeks right on two drafts) and a low-latency AudioTrack (above).
- **Pixel 6a playback** (Android 17 beta, Debug, 2026-10-13/14): about 22 fps (19.4–22.7), short of the ≥ 29 target. In a Perfetto trace, Media3's `Surface.setFrameRate(30)` is forwarded by the `TextureView` to `setRequestedFrameRate` (API 35+), so the app draws every 33 ms; frames arrive 15–50 ms apart, two often land in one draw and the older is never shown. #13 (a draft, waiting for an S24 run) asks for the high frame-rate category while playing: the view then votes `high` (749 times, never 30; `pixel-play-1014`) but the display still drops to 30 Hz in stretches; 24.1 fps on `join` (2026-10-14), 22.9–27.0 over 6 drafts (2026-10-13). Accepted for now.

**2026-10-14, with #12 and #14 (Debug):** preview against export, seeks whose snapshot equals the export's frame (`ios-ios-wms-1014`, `android-pixel-preview-wms1014-1`, `android-pixel-oddjoin-1014b`; the S24 hasn't run these):

| Draft | Pixel 6a (Android 17 beta) | iPhone 17 Pro Max |
|---|---|---|
| join / trims / edits / mute | 20 / 20 / 20 / 20 of 20 (trims 13 with whole frames but fractional lengths) | 20 / 20 / 19 / 20 |
| 20 clips | 30 of 30 (28 before #12) | 26–27 of 30 |
| 40 clips | 38 of 38 (30 before #12) | 38 of 38 |
| `trimedits` (trimmed + rotated, 2×, flipped + cropped, ½×) | 17 of 20 | 16 of 20 (13 before #14) |
| `oddjoin` (untrimmed clips, lengths not whole ms) | 20 of 20 | 20 of 20 |

Every miss is in the 2× clip (the sped-up-clip quirk).

## Reading the tables

- RNVT's iOS joins are faster because they copy the audio, and that is what drifts in browsers (the +27–32 ms above). pulse-editor re-encodes it to stay in sync everywhere.
- The two mixed-format rows (6 clips in other formats, 12 hostile imports) are formats `conform` will turn into recorder-format clips at import, so they won't reach a merge in Pulse once conform lands; they stay here as no-regression checks.
- The 4 decode errors on the iPhone's rotate + crop + trim draft are FFmpeg's at copied joins trimmed at their start, next to rendered clips ([trac #6584](https://trac.ffmpeg.org/ticket/6584)), not the x264-made test clips as first thought. With #14 those clips are rendered too, and the iPhone's quick pass reads 0 on `sync-select` (4 before; that it is this draft is inferred from the same 4 errors and 3.7 s).
- The S24 edited recording's one FFmpeg warning is a 1.7 ms frame where a trimmed clip ends just after a frame (invisible; players handle it).
- RNVT's extractAudio writes a WAV at the source's rate, while pulse-editor returns 16 kHz mono ready for Whisper (band-limited resampling), so its times aren't like for like. RNVT can't read a clip without audio.
- probe: RNVT's first read was slow in most runs (~110 ms) but 13 ms in one, so treat its probe time as a range.
