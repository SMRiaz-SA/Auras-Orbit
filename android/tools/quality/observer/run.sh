#!/bin/bash
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MAIN="$HERE/../../../app/src/main/java"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/classes"
mapfile -d '' sources < <(find "$HERE/src" -name '*.java' -print0)
for f in com/auras/orbit/jellyfin/JellyfinModels.java com/auras/orbit/jellyfin/JellyfinPlaybackObserver.java; do
  sources+=("$MAIN/$f")
done
javac --release 17 -d "$TMP/classes" "${sources[@]}"
java -cp "$TMP/classes" audit.ObserverCallbackRaceRegression
