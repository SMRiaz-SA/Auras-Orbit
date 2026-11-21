#!/usr/bin/env python3
"""Rebuild the complete distributable hash manifest after all source/test changes."""
import csv
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / 'SOURCE_REBUILD_MANIFEST.json'
SOURCE = 'AurasOrbit_CompleteSource_REBUILT.zip'
SOURCE_SHA = '75ed90bf969cbcd7a2237db2d3a98fb17a5a6d8767147f737658ffe8ae8f64d1'
REFERENCE = 'AURAS_ORBIT_ANDROID_CS3_LINE_LEVEL_FAILURE_FIRST_RECONCILED_COMPLETE_SOURCE_2026-11-08.zip'
REFERENCE_SHA = 'de47c645eedbaadeca40fddb75a71c1a6f94bdb0b434c7ddaecfc6d1199eb1ad'
sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
files = []
for p in sorted(ROOT.rglob('*')):
    if not p.is_file() or p == OUT: continue
    files.append({'path':p.relative_to(ROOT).as_posix(), 'size':p.stat().st_size,
                  'sha256':sha(p)})
by_path = {e['path']:e for e in files}
original = {}
with (ROOT/'SHA256_MANIFEST.csv').open(encoding='utf-8-sig', newline='') as f:
    for entry in csv.DictReader(f):
        original[entry['Path']] = (int(entry['Bytes']),entry['SHA256'].lower())
assert len(original) == 475
missing = sorted(set(original)-set(by_path))
assert not missing, f'Original files missing: {missing[:10]}'
modified = sorted(p for p,(length,digest) in original.items()
                  if by_path[p]['size'] != length or by_path[p]['sha256'] != digest)
result = {
  'schema': 'auras-orbit-complete-source-manifest-v1',
  'input_source_archive': SOURCE,
  'input_source_sha256': SOURCE_SHA,
  'hardening_reference_archive': REFERENCE,
  'hardening_reference_sha256': REFERENCE_SHA,
  'release_approved': False,
  'source_authority': '2026-11-10 compatibility-first successor of exact uploaded 697-entry source SHA256 9782b22d2b0740f32651822ab6f58ef89a4f7317af12b4c70103f589ea3076b4; original source unchanged',
  'current_predecessor_archive_sha256': '9782b22d2b0740f32651822ab6f58ef89a4f7317af12b4c70103f589ea3076b4',
  'implementation_status': 'CS3 compatibility-first source slice and episode UI pagination, P5 metadata-cache schema research, P6/P7 standalone policies; P0-P10 runtime and integrated closure pending',
  'source_baseline_file_count': len(original),
  'modified_nov09_baseline': modified,
  'new_files': sorted(set(by_path)-set(original)),
  'removed_files': [],
  'files': files,
}
OUT.write_text(json.dumps(result,sort_keys=True,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')
print('SOURCE_MANIFEST_CREATED files=%d original=%d changed=%d new=%d' %
      (len(files),len(original),len(modified),len(result['new_files'])))
