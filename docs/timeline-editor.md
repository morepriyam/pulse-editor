[README](../README.md) · [Methods](methods.md) · [Benchmarks](benchmarks.md) · [Differences](differences.md) · [Timeline editor](timeline-editor.md) · [Migration](migration.md) · [Roadmap](roadmap.md)

# The timeline editor (plan)

Where the migration is heading. Nothing here is built yet beyond a spike (below): it follows the [migration steps](migration.md).

## Today: three screens
1. **Recorder.** Camera, record, import, and the clip bar (drag to reorder, drag to delete).
2. **Clip preview.** Tap a clip in the bar. Plays the draft across clips with edits applied, with a playhead on the clip bar, play/pause, ✂ edit, 🗑 delete and "Revert edits".
3. **Clip editor.** ✂ opens RNVT's full-screen editor on **one clip**: trim, crop, rotate, flip, mute, speed (presets and recent custom speeds), and undo/redo that reopens where you left off. Save stores the edit as settings (`editState`); nothing is encoded.

Editing a draft means going through screens 2 and 3 once per clip, and screen 3 can't show the clips around the one being edited.

## Target: two screens
1. **Recorder: unchanged.**
2. **Timeline editor**, replacing screens 2 and 3: every clip on one timeline, played by `<PulsePreview>` so the preview is exactly what exports.
   - **Keeps every current feature:** play/pause and scrubbing across clips; trim, crop, rotate, flip, mute and speed (presets and custom speeds) on any clip without leaving the timeline; revert edits; delete; reorder; undo/redo.
   - **Edits apply immediately, with undo as the safety net** (Pulse's editing style): no save step, and still stored as settings, rendered once by `merge` at export.
   - **New on the timeline:** each clip's thumbnails, and later its **audio waveform** (see where people speak, so trims land in the gaps between words) and captions, from per-clip audio (below).
   - **Later:** split a clip into two segments that share one original (Pulse #58).
   - Trim handles come last within the editor work.

## What each piece needs

| Piece | From |
|---|---|
| Playback across clips with edits, preview == export | `<PulsePreview>` ([step 8](migration.md)) |
| Thumbnails along each clip | `thumbnail` (done) |
| Waveform and captions on the timeline | `extractAudio` (done) + per-clip audio ([after the migration](roadmap.md)) |
| Rendering the edits at export | `merge` (done) |
| Each clip's position on the timeline | the engine's slots, from `<PulsePreview>`'s status (to add, below) |

## What the editor takes from the engine
- **Trims:** a trim handle snaps to the nearest source frame, and every clip's length is a whole ms. The engine owns that rule (`merge` and `<PulsePreview>` read the same window, #12); the editor shows the clip the engine returns instead of applying a rule of its own.
- **Clip positions:** each clip's slot on the timeline comes from the engine, not from `probe`'s `durationMs`. iOS cuts an untrimmed clip at its video track's end and Android at the file's, so the two platforms' slots differ by a recording's audio/video length gap (`oddjoin`, 2026-10-14: the iPhone's clips start at 11.966 / 23.866 / 35.766 s, the Pixel's at 11.966 / 23.891 / 35.791 s; each matches its own export).
- **Gap in the API:** `<PulsePreview>`'s status gives only the total `durationMs`. Both platforms already compute each clip's slot (iOS: `Timeline`'s slots; Android: `slots`); exposing their starts (e.g. `clipStartsMs: number[]`) is the editor's first engine PR (a spec change, so a `yarn nitrogen` regen).
- **The editor's first commit:** a lip-sync film of preview playback and an audio session / background check (no numbers yet).

## Timeline spike (2026-10-13)
Measured on a spike, not on the editor.

| | iPhone 17 Pro Max | Pixel 6a |
|---|---|---|
| Strip | 120 fps | 61 fps |
| Seeks while dragging | 30–60/s | 8–22/s (1 s keyframes) |
| Settle after release | 25–108 ms | ~400 ms |
