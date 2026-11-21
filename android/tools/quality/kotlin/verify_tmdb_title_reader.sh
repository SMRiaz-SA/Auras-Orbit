#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MAIN="$HERE/../../../app/src/main/java"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
mkdir -p "$OUT/classes"
javac --release 17 -d "$OUT/classes" "$MAIN/com/auras/orbit/metadata/TitleCatalogRecord.java"
kotlinc -cp "$OUT/classes" \
  "$MAIN/com/auras/orbit/metadata/TmdbTitleMetadataRepository.kt" \
  "$HERE/tmdb-reader-stubs/"*.kt -include-runtime -d "$OUT/test.jar"
java -Xmx128m -cp "$OUT/classes:$OUT/test.jar" probe.TitleReaderProbeKt
