#!/usr/bin/env python3
"""Source custody verification against the *exact* inherited REV-003 ZIP.
Requires the prior archive supplied as argument. Generated revision evidence itself is excluded
from successor SHA membership; ZIP integrity and external SHA cover the complete package.
"""
from pathlib import Path
import hashlib,json,sys,zipfile
ROOT=Path(__file__).resolve().parents[3]
if len(sys.argv)!=2:
    raise SystemExit('Usage: python verify_rev004_continuity.py /path/to/REV-003.zip')
manifest=json.loads((ROOT/'audit-evidence/2026-11-10/rev004/SOURCE_CHANGE_MANIFEST.json').read_text())
parent=Path(sys.argv[1])
assert hashlib.sha256(parent.read_bytes()).hexdigest()==manifest['parent_sha256'],'Parent ZIP changed'
with zipfile.ZipFile(parent) as z:
    old={n.removeprefix('AurasOrbit_Source/'):hashlib.sha256(z.read(n)).hexdigest()
         for n in z.namelist() if n.startswith('AurasOrbit_Source/') and not n.endswith('/')}
assert len(old)==manifest['parent_file_count']
mod={v['path']:v for v in manifest['modified']}
for path,expected in old.items():
    p=ROOT/path
    assert p.is_file(),f'Deleted predecessor {path}'
    actual=hashlib.sha256(p.read_bytes()).hexdigest()
    if path in mod:
        assert expected==mod[path]['before_sha256'] and actual==mod[path]['after_sha256'],f'Changed hash {path}'
    else:
        assert actual==expected,f'Unauthorized predecessor change {path}'
for addition in manifest['added']:
    path=addition['path']
    assert path not in old and hashlib.sha256((ROOT/path).read_bytes()).hexdigest()==addition['sha256'],f'Addition changed {path}'
assert not manifest['removed'] and not manifest['protected_changed']
recorded={v['path'] for v in manifest['added']}
excluded={'audit-evidence/2026-11-10/rev004/SOURCE_CHANGE_MANIFEST.json',
          'audit-evidence/2026-11-10/rev004/OFFLINE_TEST_RESULTS.json',
          'android/tools/quality/verify_rev004_continuity.py'}
all_new={p.relative_to(ROOT).as_posix() for p in ROOT.rglob('*') if p.is_file()}-set(old)
assert all_new-recorded-excluded==set(),f'Unaccounted successor additions: {all_new-recorded-excluded}'
print(f"REV004_CONTINUITY_PASS parent_files={len(old)} changed={len(mod)} added={len(manifest['added'])} protected={manifest['protected_file_count']}")
