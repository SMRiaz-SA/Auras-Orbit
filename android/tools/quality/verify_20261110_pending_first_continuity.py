#!/usr/bin/env python3
"""Verify every byte of the exact 710-file CS3 compatibility-first parent.

This verifier is intentionally independent of historical successor allowlists; it
never treats a previous verifier's expected original changes as current authority.
"""
from pathlib import Path
import hashlib
import json

root = Path(__file__).resolve().parents[3]
evidence = root / 'audit-evidence/2026-11-10/pending-first-successor'
pin = json.loads((evidence / 'PINNED_COMPAT_SOURCE_710_SHA256.json').read_text())
approval = json.loads((evidence / 'APPROVED_PENDING_FIRST_CHANGES.json').read_text())
assert pin['input_zip_sha256'] == approval['input_zip_sha256'], 'Parent ZIP identity mismatch'
assert pin['file_count'] == 710 and len(pin['files']) == 710, 'Parent inventory incomplete'

def digest(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()

actual = {p.relative_to(root).as_posix(): digest(p) for p in root.rglob('*') if p.is_file()}
original = set(pin['files'])
missing = sorted(original - set(actual))
assert not missing, 'Predecessor files removed: ' + repr(missing)
changed = sorted(p for p in original if actual[p] != pin['files'][p])
added = sorted(set(actual) - original)
assert changed == sorted(approval['modified_original_files']), ('Unexpected changed originals:', changed)
assert added == sorted(approval['added_files']), ('Unexpected new files:', added)
assert all(not p.startswith('android/app/src/main/res/') for p in changed), 'Accepted artwork changed'
assert all(not p.startswith('android/app/src/androidTest/assets/cs3-corpus/') for p in changed), 'CS3 corpus changed'
assert all(not p.startswith('visual/') for p in changed), 'Visual review fixture changed'
assert sum(1 for p in actual if p.endswith('.cs3')) >= 19, '19-package CS3 fixture missing'
assert approval['release_approved'] is False, 'No APK/device release evidence'
print(f'PENDING_FIRST_CONTINUITY_PASS parent=710 current={len(actual)} modified={len(changed)} '
      f'added={len(added)} deleted=0 unauthorized=0 art=UNCHANGED cs3_corpus=UNCHANGED release=HOLD')
