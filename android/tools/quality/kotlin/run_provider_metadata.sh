#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
kotlinc "$ROOT/app/src/main/java/com/auras/orbit/providers/Cs3ProviderMetadata.kt" \
    "$HERE/provider-metadata-stubs/MainAPI.kt" \
    "$HERE/ProviderMetadataFailureRegression.kt" -include-runtime -d "$OUT/metadata.jar"
java -Xmx192m -jar "$OUT/metadata.jar"
