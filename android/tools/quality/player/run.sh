#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_ROOT="$(cd "$HERE/../../.." && pwd)"
TMPDIR="$(mktemp -d)"
trap 'rm -rf "$TMPDIR"' EXIT
mkdir -p "$TMPDIR/classes"
javac --release 17 -encoding UTF-8 -d "$TMPDIR/classes" \
  "$ANDROID_ROOT/app/src/main/java/com/auras/orbit/player/OriginBoundStreamProgress.java" \
  "$HERE/src/com/auras/orbit/player/OriginBoundStreamProgressRegression.java"
java -cp "$TMPDIR/classes" com.auras.orbit.player.OriginBoundStreamProgressRegression
