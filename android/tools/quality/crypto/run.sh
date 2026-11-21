#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MAIN="$(cd "$HERE/../../.." && pwd)/app/src/main/java"
TMPDIR="$(mktemp -d)"
trap 'rm -rf "$TMPDIR"' EXIT
mapfile -d '' sources < <(find "$HERE/src" -name '*.java' -print0)
mkdir -p "$TMPDIR/classes"
javac --release 17 -encoding UTF-8 -d "$TMPDIR/classes" \
  "${sources[@]}" "${ARCHIVE_CODEC_SOURCE:-$MAIN/com/auras/orbit/backup/OrbitLibraryArchiveCodec.java}"
java -Xmx256m -cp "$TMPDIR/classes" audit.OrbitCryptoProbe

java -Xmx128m -cp "$TMPDIR/classes" audit.ArchiveWriteBudgetRegression
