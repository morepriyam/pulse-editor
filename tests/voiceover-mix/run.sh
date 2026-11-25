#!/bin/bash
set -euo pipefail
# Host-only AVFoundation regression. No phone, microphone, signing or simulator lease.
module_root="$(cd "$(dirname "$0")/../.." && pwd)"
output_dir="${1:-$(mktemp -d /tmp/pulse-voiceover-mix.XXXXXX)}"
mkdir -p "$output_dir"
ffmpeg -hide_banner -loglevel error -y -f lavfi -i color=c=blue:s=160x96:r=30:d=20 \
  -f lavfi -i sine=frequency=220:sample_rate=48000:duration=20 -c:v libx264 -pix_fmt yuv420p -bf 0 \
  -c:a aac -ac 1 -movflags +faststart -shortest "$output_dir/source.mp4"
ffmpeg -hide_banner -loglevel error -y -f lavfi -i sine=frequency=880:sample_rate=48000:duration=60 \
  -c:a pcm_s16le -ac 1 "$output_dir/narration.caf"
sources=()
for source in "$module_root/ios/Merge/"*.swift; do
  [[ "$source" == */MergeJob.swift ]] || sources+=("$source")
done
swiftc -swift-version 5 -suppress-warnings -parse-as-library \
  "$module_root/tests/voiceover-mix/Types.swift" \
  "$module_root/ios/Probe.swift" "${sources[@]}" \
  "$module_root/tests/voiceover-mix/Main.swift" -o "$output_dir/mix-regression"
"$output_dir/mix-regression" "$output_dir"
python3 "$module_root/tests/voiceover-mix/verify.py" "$output_dir"
printf 'Native audio evidence: %s\n' "$output_dir"
