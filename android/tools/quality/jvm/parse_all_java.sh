#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
TEMP="$(mktemp -d)"
trap 'rm -rf "$TEMP"' EXIT
javac --release 17 -encoding UTF-8 -d "$TEMP" "$HERE/src/audit/JavaSyntaxParse.java"
java -Xmx1024m -cp "$TEMP" audit.JavaSyntaxParse "$ROOT"
