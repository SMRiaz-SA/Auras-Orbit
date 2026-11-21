#!/usr/bin/env python3
"""Exact 680-file predecessor continuity, without relying on the ZIP at runtime.
Read-only test: accepts only explicitly named differences; enforces release HOLD.
"""
from pathlib import Path
import hashlib,json,sys
root=Path(__file__).resolve().parents[3]
base=json.loads((root/'audit-evidence/2026-11-10/PINNED_DIRECT_BUILD_680_HASHES.json').read_text())
approved=json.loads((root/'audit-evidence/2026-11-10/APPROVED_PREDECESSOR_CHANGES.json').read_text())
assert base['file_count']==680
assert base['source_archive_sha256']=='7c3cb9fb557af1e64820c980ac540f4b3d82f0ab14835f9bdd4a10b9b86d9e50'
changed=[]
for rel, digest in base['baseline_files'].items():
    path=root/rel
    if not path.is_file():raise AssertionError('Predecessor source file missing: '+rel)
    if hashlib.sha256(path.read_bytes()).hexdigest()!=digest:changed.append(rel)
expected=set(approved['modified_predecessor_files'])
if set(changed)!=expected:
    raise AssertionError('Unexpected predecessor changes '+repr({'unexpected':sorted(set(changed)-expected),'stale':sorted(expected-set(changed))}))
manifest=json.loads((root/'SOURCE_REBUILD_MANIFEST.json').read_text())
assert not manifest['release_approved'], 'Do not lift production hold from offline-only results'
print('NOV10_680_PREDECESSOR_CONTINUITY_PASS preserved=680 changed=%d unauthorized=0 release=HOLD'%len(changed))
