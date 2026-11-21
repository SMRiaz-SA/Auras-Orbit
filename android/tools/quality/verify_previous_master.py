#!/usr/bin/env python3
"""Verify immediate predecessor source continuity and approved successor modifications.

The predecessor ZIP need not be present: this source package embeds SHA-256 digests
captured from its ZIP entries before any modifications. This is not a signature.
"""
import hashlib
import json
from pathlib import Path

root = Path(__file__).resolve().parents[3]
reference = root/'audit-evidence/collection-admission-cycle-2026-11-09/immediate_master_hashes.json'
snapshot = json.loads(reference.read_text(encoding='utf-8'))
prior = snapshot['baseline_files']
allowed = set(snapshot['allowed_modified_paths'])
assert len(prior) == 592, f'Unexpected immediate predecessor count: {len(prior)}'
assert allowed <= set(prior), 'Modified path policy includes a non-predecessor path'
modified=[]
for name, record in prior.items():
    path=root/name
    if not path.is_file():
        raise AssertionError('Immediate predecessor file missing: '+name)
    digest=hashlib.sha256(path.read_bytes()).hexdigest()
    if digest != record['sha256']:
        if name not in allowed:
            raise AssertionError('Unapproved predecessor modification: '+name)
        modified.append(name)
if set(modified) != allowed:
    raise AssertionError('Changed-set differs from approved immediate predecessor list')
print('IMMEDIATE_MASTER_CONTINUITY_PASS baseline=%d modified=%d unapproved=0 removed=0' %
      (len(prior),len(modified)))
