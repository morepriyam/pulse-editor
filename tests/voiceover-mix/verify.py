#!/usr/bin/env python3
"""Inspect actual native AAC output: both tones, silence, timing, and preview/export agreement."""
import array
import cmath
import json
import math
from pathlib import Path
import subprocess
import sys

root = Path(sys.argv[1])
rate = 48_000

def audio(path):
    raw = subprocess.check_output([
        'ffmpeg', '-v', 'error', '-i', str(path), '-map', '0:a:0', '-ac', '1', '-ar', str(rate),
        '-f', 'f32le', '-',
    ])
    samples = array.array('f')
    samples.frombytes(raw)
    return samples

def magnitude(samples, at, hz, length=0.05):
    window = samples[round(at * rate):round((at + length) * rate)]
    assert window, (at, len(samples))
    phase = cmath.exp(-2j * math.pi * hz / rate)
    p = 1 + 0j
    value = 0j
    for sample in window:
        value += sample * p
        p *= phase
    return abs(value) * 2 / len(window)

short_slots = [(2, 4), (5, 7), (18.5, 21.5), (39, 41), (58, 60)]
summary = {}
for name in ['minute', 'takes', 'source-muted', 'voiceover-muted']:
    preview = audio(root / f'{name}-preview.mp4')
    exported = audio(root / f'{name}-export.mp4')
    assert abs(len(preview) / rate - 60) < .05
    assert abs(len(exported) / rate - 60) < .05
    # Compare first and last windows as well as across every video clip boundary. A difference
    # here catches voice-to-picture drift and additional AAC priming introduced only by export.
    differences = []
    for begin in [.1, 2.1, 5.1, 19.9, 20.1, 39.9, 40.1, 58.1, 59.8]:
        start, end = round(begin * rate), round((begin + .1) * rate)
        rms = math.sqrt(sum((a - b) ** 2 for a, b in zip(preview[start:end], exported[start:end])) / (end - start))
        assert rms < .002, (name, begin, 'preview/export difference', rms)
        differences.append(rms)
    for at in [.3, 2.3, 4.3, 5.3, 7.3, 18.7, 20.3, 22.3, 39.3, 40.3, 42.3, 58.3, 59.7]:
        source = magnitude(exported, at, 220)
        voice = magnitude(exported, at, 880)
        source_expected = name != 'source-muted'
        voice_expected = name == 'minute' or (name != 'voiceover-muted' and any(a <= at < b for a, b in short_slots))
        assert (source > .04) == source_expected, (name, at, 'source', source)
        assert (voice > .04) == voice_expected, (name, at, 'voice', voice)
    summary[name] = {'durationMs': round(len(exported) / rate * 1000, 3),
                     'maxPreviewExportRmsDifference': max(differences)}

# Locate the first and fifth take starts on a 5 ms grid. These bracket a one-minute timeline;
# both must stay aligned, including after crossing video clip boundaries and source trims.
for label, path in [('preview', root / 'takes-preview.mp4'), ('export', root / 'takes-export.mp4')]:
    samples = audio(path)
    errors = []
    for expected in [2, 5, 18.5, 39, 58]:
        windows = [(expected - .05 + step * .005) for step in range(21)]
        onset = next(at for at in windows if magnitude(samples, at, 880, .005) > .04)
        errors.append(round((onset - expected) * 1000, 2))
    assert max(abs(error) for error in errors) <= 15, (label, errors)
    summary[f'{label}TakeStartErrorsMs'] = errors

# Speed edits shorten the second clip to ten seconds. Narration crosses the resulting 20s/30s
# boundaries at unchanged speed/placement and the final take is clipped at fifty seconds.
retimed_preview = audio(root / 'retimed-preview.mp4')
retimed_export = audio(root / 'retimed-export.mp4')
assert abs(len(retimed_export) / rate - 50) < .05
for at, expected in [(19, False), (19.7, True), (20.3, True), (22.7, False),
                     (28.7, True), (30.3, True), (31.7, False), (48.3, True), (49.7, True)]:
    assert (magnitude(retimed_export, at, 880) > .04) == expected, ('retimed', at)
    start, end = round(at * rate), round((at + .05) * rate)
    rms = math.sqrt(sum((a - b) ** 2 for a, b in zip(retimed_preview[start:end], retimed_export[start:end])) / (end - start))
    assert rms < .002, ('retimed preview/export', at, rms)
summary['retimed'] = {'durationMs': round(len(retimed_export) / rate * 1000, 3),
                      'crossedEditedClipBoundariesMs': [20_000, 30_000]}

probe = json.loads(subprocess.check_output([
    'ffprobe', '-v', 'error', '-show_streams', '-of', 'json', str(root / 'both-muted-export.mp4'),
]))
assert not [stream for stream in probe['streams'] if stream['codec_type'] == 'audio']
(root / 'measurements.json').write_text(json.dumps(summary, indent=2) + '\n')
print(json.dumps(summary, indent=2))
