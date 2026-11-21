#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_ROOT="$(cd "$HERE/../../.." && pwd)"
TMPDIR="$(mktemp -d)"
trap 'rm -rf "$TMPDIR"' EXIT
mkdir -p "$TMPDIR/classes"
javac --release 17 -d "$TMPDIR/classes" "$ANDROID_ROOT/app/src/main/java/com/lagradost/cloudstream3/subtitles/SubtitleArchiveGuard.java"
kotlinc -classpath "$TMPDIR/classes" \
  "$ANDROID_ROOT/app/src/main/java/com/lagradost/cloudstream3/subtitles/SubtitleResource.kt" \
  "$HERE"/subtitle-stubs/*.kt "$HERE/SubtitleResourceLifecycleRegression.kt" \
  -include-runtime -d "$TMPDIR/subtitle.jar"
java -Xmx384m -cp "$TMPDIR/classes:$TMPDIR/subtitle.jar" audit.SubtitleResourceLifecycleRegression
