# Voiceover prototype (iOS)

`PulsePreview` records draft-level narration while playing its existing timeline. The host
owns the saved media and take list; recording never edits, splits or reorders video clips.

1. Call `prepareVoiceover()` when opening the tool, before enabling its hold button. It
   requests microphone permission, activates a play-and-record audio session, preopens a
   PCM CAF and keeps an input tap running between takes.
2. Hold: call `startVoiceover()`. Native code starts the prepared player and selects input
   samples using one host-clock anchor, returning the player's starting timeline position.
   Playback is temporarily muted while recording; `MergeOptions` mute settings are untouched.
3. Release: call `stopVoiceover()`. Video pauses immediately. Capture includes samples
   through that pause's timeline position, including a buffer delivered after the release.
   Completion waits for a buffer covering that cutoff, with a one-second failure bound.
   Move/copy the returned CAF into durable storage before changing the saved take list.
4. Call `cancelVoiceover()` when leaving. It removes an unfinished take and microphone tap,
   stops the audio engine and restores the previous audio-session category. An unsaved
   completed cache file remains available for a failed-save retry. The host removes it on final
   disposal; the next start also cleans the preceding completed cache file after it was saved.

Backgrounding, audio interruption/route loss, an unexpected playback pause or stall, seeking,
scrubbing and changing the composition cancel an in-progress take rather than saving audio
against a stopped or changed picture. Successful re-preparation clears the interruption status.
Input host timestamps must also remain continuous: a gap or reversal larger than 1 ms or two
sample frames (whichever is greater) rejects the take instead of compressing narration timing.
Changes to saved takes rebuild the composition; wait for its ready status and seek completion
before starting another capture. Recording rejects during a pending build, swap or seek.

`VoiceoverTake.startMs` is the first captured sample's actual timeline position. `endMs` is
the paused player position, capped to the last available sample; `durationMs` is source length
and `sourceStartMs` its source trim. The input sample grid can move an edge by less than one
sample. Repeated stop calls return the same completed take, allowing the host to retry saving.

Preview and export take the same `MergeOptions.voiceover` entries
(`uri`, `timelineStartMs`, `sourceStartMs`, `sourceEndMs`), plus independent `sourceMuted` and
`voiceoverMuted` flags. Unmuted tracks mix without automatic source ducking. Takes may span
clip boundaries and overlap. Android capture rejects as unsupported; Android preview/export
reject active narration rather than silently dropping it.

## Timing evidence

Each take reports milliseconds measured from the native start command:

| Field                | Meaning                                                                            |
| -------------------- | ---------------------------------------------------------------------------------- |
| `pressToPlaybackMs`  | First moving frame observed by the preview display callback; −1 if none            |
| `pressToCaptureMs`   | First retained input sample's host timestamp minus the native command time         |
| `stopAlignmentMs`    | Captured sample duration minus the paused player's corresponding timeline duration |
| `audioVideoOffsetMs` | Input sample clock relative to the scheduled video clock                           |

These exclude JS touch delivery and do not measure acoustic microphone-to-picture latency.
Physical microphone, speaker/headphone latency and real one-minute spoken narration still
require device review; a simulator build or synthetic buffer test does not establish them.

Run `sh scripts/check-voiceover-samples.sh` on macOS for the actual PCM writer regression:
one minute, five successive short takes and a seek/retake at 44.1 and 48 kHz. It decodes every
CAF sample to check for omission, repetition, reordering and accumulated timestamp drift,
and checks that empty captures and missing/reordered input buffers fail. Mixing/export
validation is documented by the host app.
