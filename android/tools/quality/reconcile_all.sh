#!/usr/bin/env bash
# All supported deterministic offline checks. Android compilation is a separate release gate.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
bash tools/quality/jvm/run.sh
bash tools/quality/observer/run.sh
bash tools/quality/playback-admission/run.sh
bash tools/quality/jvm/parse_all_java.sh
bash tools/quality/kotlin/run_subtitle.sh
bash tools/quality/kotlin/run_provider_metadata.sh
python3 tools/quality/kotlin/verify_cs3_cached_context.py
python3 tools/quality/kotlin/verify_cs3_episode_budgets.py
python3 tools/quality/kotlin/verify_cs3_episode_index.py
bash tools/quality/kotlin/run.sh
bash tools/quality/crypto/run.sh
python3 tools/quality/url/run.py
python3 tools/quality/line_audit.py
python3 tools/quality/metadata/verify_cache_schema.py
python3 tools/quality/verify_parity_contract.py
python3 tools/quality/generate_manifest.py
python3 tools/quality/verify_source.py
# Preserve every file from the exact 680-file direct-build predecessor.
# Earlier 610/657-file continuity scripts remain unmodified as historical tools.
python3 tools/quality/verify_20261110_successor_continuity.py
