#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
TEMP="$(mktemp -d)"; trap 'rm -rf "$TEMP"' EXIT
find "$HERE/stubs" "$HERE/src" -name '*.java' > "$TEMP/sources.txt"
echo "${PLAYBACK_SOURCE:-$ROOT/app/src/main/java/com/auras/orbit/player/PlaybackRequest.java}" >> "$TEMP/sources.txt"
mkdir -p "$TEMP/classes"
javac --release 17 -encoding UTF-8 -d "$TEMP/classes" @"$TEMP/sources.txt"
java -Xmx128m -cp "$TEMP/classes" audit.PlaybackCollectionAdmissionRegression
