# Voiceover mix regression

On macOS with Xcode command-line tools, FFmpeg and Python 3 installed, run:

```sh
tests/voiceover-mix/run.sh /tmp/pulse-voiceover-mix
```

The harness compiles the shipping `Timeline`, `Join`, `AudioEncode`, `Render` and merge
implementation against AVFoundation. Only the Nitro transport structs are replaced with plain
Swift structs; this does not exercise the React Native bridge or microphone capture.

It creates three 20-second video clips with a 220 Hz source tone and a separate one-minute
880 Hz narration recording. It compares the actual preview composition's encoded sound with
the merged MP4 for one long take, five trimmed takes crossing video boundaries, independent
source/narration mutes, and complete silence when both tracks are muted. A second pass speeds
up the middle clip to 2× and verifies narration across the resulting 20s/30s boundaries, including
the selective-render export path. The last take extends
past the picture and must be clipped at its end. The inspector checks both frequencies in
narrated regions and gaps, all five take starts at 5 ms resolution, and PCM agreement near both
ends and video boundaries. `measurements.json` and the media remain in the requested output
directory for inspection.

This establishes composition/export timing and mixing on the host AVFoundation implementation.
Press/capture latency, microphone-to-picture offset, interruption behavior and drift during a
real one-minute recording require separate simulator/device capture evidence.
