#!/usr/bin/env python3
"""Verify all files from exact 610-file 09 Nov execution input remain present.

Approved changes must match the declared list. The historical prior-master
verifier is preserved but does NOT represent the new successor's baseline.
This is an internal continuity check, not a cryptographic signature.
"""
import hashlib
import json
from pathlib import Path

root = Path(__file__).resolve().parents[3]
record = root / 'audit-evidence/audit-to-closure-execution-2026-11-09/input_610_file_hashes.json'
reference = json.loads(record.read_text(encoding='utf-8'))
assert len(reference['baseline_files']) == 610, 'Unexpected input file count'
assert reference['input_archive_sha256'] == '11156f61a5b47e23310ef655deeeeaf48f03134044a5a0c21b2cb2c1700a2dec'
changed = set()
for name, old_hash in reference['baseline_files'].items():
    file = root / name
    if not file.is_file():
        raise AssertionError('Lost baseline file: ' + name)
    with file.open('rb') as stream:
        sha = hashlib.sha256()
        for chunk in iter(lambda: stream.read(1048576), b''):
            sha.update(chunk)
    if sha.hexdigest() != old_hash:
        changed.add(name)
allowed = set(reference['approved_changed_baseline_paths'])
if changed != allowed:
    raise AssertionError('Unapproved or missing change: unexpected=' + str(sorted(changed - allowed))
                         + ' not_changed=' + str(sorted(allowed - changed)))
print(f'AUDIT_BASELINE_CONTINUITY_PASS baseline={len(reference["baseline_files"])} modified={len(changed)} missing=0 unapproved=0')
