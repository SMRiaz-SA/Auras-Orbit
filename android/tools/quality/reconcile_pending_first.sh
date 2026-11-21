#!/usr/bin/env bash
# Executable offline suite for the successor; historical hash-bound audits remain untouched.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
bash tools/quality/jvm/run.sh
bash tools/quality/jvm/run_page_bounds.sh
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
python3 tools/quality/metadata/verify_cache_schema.py
python3 tools/quality/metadata/verify_android_cache_contract.py
python3 tools/quality/verify_20261110_pending_first_continuity.py
printf 'PENDING_FIRST_OFFLINE_SUITE_PASS (Android APK + device qualification not included)\n'
