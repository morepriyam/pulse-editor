#!/bin/sh
set -eu
module_directory=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
check_directory=$(mktemp -d "${TMPDIR:-/tmp}/pulse-voiceover-check.XXXXXX")
trap 'rm -rf "$check_directory"' EXIT HUP INT TERM
xcrun swiftc "$module_directory/ios/VoiceoverSamples.swift" \
  "$module_directory/tests/voiceover-samples.swift" -o "$check_directory/check"
"$check_directory/check"
