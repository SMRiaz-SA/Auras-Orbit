#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_ROOT="$(cd "$HERE/../../.." && pwd)"
TMPDIR="$(mktemp -d)"
trap 'rm -rf "$TMPDIR"' EXIT
kotlinc "$ANDROID_ROOT/app/src/main/java/com/auras/orbit/cs3/BoundedLevenshtein.kt" \
    "$HERE/BoundedLevenshteinFuzz.kt" -include-runtime -d "$TMPDIR/fuzz.jar"
java -Xmx512m -jar "$TMPDIR/fuzz.jar"
