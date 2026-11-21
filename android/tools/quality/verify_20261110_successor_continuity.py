#!/usr/bin/env python3
"""Validate exact uploaded 697-file custody, approved successor modifications, release HOLD.
No external ZIP needed: exact predecessor per-entry hashes are sealed with the successor.
"""
from pathlib import Path
import hashlib,json
root=Path(__file__).resolve().parents[3]
directory=root/'audit-evidence/2026-11-10/compatibility-first-successor'
pinned=json.loads((directory/'PINNED_UPLOADED_697_SHA256.json').read_text())
allow=json.loads((directory/'APPROVED_SUCCESSOR_CHANGES.json').read_text())
expected_zip='9782b22d2b0740f32651822ab6f58ef89a4f7317af12b4c70103f589ea3076b4'
assert pinned['zip_sha256']==expected_zip and allow['input_zip_sha256']==expected_zip
assert pinned['file_count']==697 and len(pinned['files'])==697
changed=[]
for relative,original_sha in pinned['files'].items():
    path=root/relative
    assert path.is_file(),'Predecessor file missing: '+relative
    if hashlib.sha256(path.read_bytes()).hexdigest()!=original_sha: changed.append(relative)
approved=set(allow['modified_original_files'])
assert set(changed)==approved,'Unapproved/predecessor-changed files: '+repr({'unexpected':sorted(set(changed)-approved),'stale':sorted(approved-set(changed))})
# The original visual resource art and binary CS3 corpus stay byte-identical.
assert all(not path.startswith('android/app/src/main/res/') for path in changed), 'res/ asset changed'
assert all(not path.startswith('android/app/src/androidTest/assets/cs3-corpus/') for path in changed), 'test .cs3 corpus changed'
manifest=json.loads((root/'SOURCE_REBUILD_MANIFEST.json').read_text())
assert manifest['release_approved'] is False,'Release must remain HOLD'
assert manifest.get('current_predecessor_archive_sha256')==expected_zip
print('NOV10_COMPAT_SUCCESSOR_CONTINUITY_PASS original=697 changed=%d unauthorized=0 resource_art=UNCHANGED plugin_corpus=UNCHANGED release=HOLD'%len(changed))
