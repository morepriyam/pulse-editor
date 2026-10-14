[README](../README.md) · [Methods](methods.md) · [Benchmarks](benchmarks.md) · [Differences](differences.md) · [Timeline editor](timeline-editor.md) · [Migration](migration.md) · [Roadmap](roadmap.md)

# After the migration (planned features)

New features, planned for after RNVT is gone. They aren't part of the migration PR.

## Per-clip audio: waveform and captions
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

## Cover selector (thumbnail)
A screen to choose the pulse's final thumbnail: the poster that's uploaded with the video and shown on the draft card.

- **A frame from the video:** scrub the whole merged video after the merge (part of the export screen, which is being reworked) and pick the frame. The picked time is saved and the cover is rendered at full size (1080×1920) with `thumbnail`.
- **A picture from the device:** chosen with the platform's native photo picker (`expo-image-picker`, which Pulse already uses for imports).
- **Default** when nothing is picked: the video's first frame.
- **Much later:** a thumbnail-generation method (suggested covers) can plug into the same screen as a third source.

## Whisper tuning
- Test `maxThreads` 4 vs 6 on device (whisper.rn advises against using all cores; its default is 4 on phones with more than 4 cores).
- Test q8_0 models on Android: whisper.rn's guidance says q8 is faster on Android CPUs, and its 0.8 NPU support (Snapdragon 8 Gen 1 and newer) doesn't run q5 models.
- whisper.rn 0.8 for the Android NPU once it's stable.
- Ask whisper.rn to expose whisper.cpp options we could use: built-in VAD inside transcribe, `suppress_nst`, DTW word timestamps, `no_speech_thold`.
