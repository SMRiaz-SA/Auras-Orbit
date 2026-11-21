#!/usr/bin/env python3
"""Check 657-file pinned predecessor continuity without mutating historical audit records."""
import hashlib
import json
from pathlib import Path
ROOT=Path(__file__).resolve().parents[3]
PREFIX=ROOT/'audit-evidence/direct-build-2026-11-09'
original=json.loads((PREFIX/'PINNED_INPUT_657_HASHES.json').read_text())
approved=json.loads((PREFIX/'APPROVED_SOURCE_CHANGES.json').read_text())
assert original['file_count']==657
assert original['input_zip_sha256']=='75ed90bf969cbcd7a2237db2d3a98fb17a5a6d8767147f737658ffe8ae8f64d1'
changed=[]
for rel,digest in original['baseline_files'].items():
    path=ROOT/rel
    if not path.is_file():raise AssertionError('Missing predecessor file: '+rel)
    if hashlib.sha256(path.read_bytes()).hexdigest()!=digest:changed.append(rel)
if set(changed)!=set(approved['modified_predecessor_files']):
    raise AssertionError('Source changed without approval or approval stale: ' +
                         repr({'unexpected':sorted(set(changed)-set(approved['modified_predecessor_files'])),
                               'stale':sorted(set(approved['modified_predecessor_files'])-set(changed))}))
assert not json.loads((ROOT/'SOURCE_REBUILD_MANIFEST.json').read_text())['release_approved']
print('DIRECT_BUILD_657_CONTINUITY_PASS preserved=657 changed=%d missing=0 unauthorized=0' %len(changed))
