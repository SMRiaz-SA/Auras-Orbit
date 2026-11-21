#!/usr/bin/env python3
"""Verify this Nov-09 reconciled source package against the pinned incoming bundle.

Checks are integrity/structure checks, NOT Android linkage or runtime compatibility.
"""
import csv
import hashlib
import json
import sys
import zipfile
from pathlib import Path
from xml.etree import ElementTree as ET
from collections import Counter

ROOT = Path(__file__).resolve().parents[3]
ANDROID = ROOT / 'android'
MANIFEST = ROOT / 'SOURCE_REBUILD_MANIFEST.json'
issues = []

def check(ok, explanation):
    if not ok: issues.append(explanation)

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

check(MANIFEST.is_file(), 'missing source rebuild manifest')
if not MANIFEST.is_file():
    print('SOURCE_VERIFICATION_FAIL: '+str(issues));sys.exit(1)
source = json.loads(MANIFEST.read_text(encoding='utf-8'))
expected = {r['path']:r for r in source['files']}
actual = {p.relative_to(ROOT).as_posix():p for p in ROOT.rglob('*')
          if p.is_file() and p != MANIFEST and not p.name.startswith('.DS_Store')}
check(set(expected) == set(actual), 'packaged file list differs from source rebuild manifest')
for rel,entry in expected.items():
    p=actual.get(rel)
    if p is not None:
        check(p.stat().st_size == entry['size'] and sha(p) == entry['sha256'],
              f'changed package file: {rel}')

baseline = {}
with (ROOT/'SHA256_MANIFEST.csv').open(newline='',encoding='utf-8-sig') as f:
    for entry in csv.DictReader(f):
        baseline[entry['Path']] = (int(entry['Bytes']),entry['SHA256'].lower())
check(len(baseline)==475, f'9 Nov incoming baseline file count expected 475, got {len(baseline)}')
changed = set()
for rel,(size,digest) in baseline.items():
    p = actual.get(rel)
    check(p is not None, 'missing 9 November input: '+rel)
    if p is not None and (p.stat().st_size != size or sha(p) != digest):
        changed.add(rel)
check(changed == set(source['modified_nov09_baseline']), 'unexpected changed 9 Nov baseline set')
check(not source.get('release_approved',False), 'release gate must remain HOLD')

archive_dir = ANDROID/'app/src/androidTest/assets/cs3-corpus'
archives = sorted(archive_dir.glob('*.cs3'))
check(len(archives)==19, f'pinned cs3 archive count {len(archives)}, expected 19')
corpus_manifest = list(csv.DictReader((archive_dir/'manifest.tsv').open(encoding='utf-8-sig'),delimiter='\t'))
archive_hashes={p.name:sha(p) for p in archives}
for row in corpus_manifest:
    name = row.get('archive_file') or row.get('file') or row.get('name') or list(row.values())[0]
    check(name in archive_hashes, f'corpus manifest item missing: {name}')
    if name in archive_hashes:
        h = row.get('sha256') or row.get('archive_sha256') or list(row.values())[2]
        check(h.lower()==archive_hashes[name],f'plugin hash mismatch: {name}')
for p in archives:
    try:
        with zipfile.ZipFile(p) as z:
            check(z.testzip() is None,f'plugin CRC: {p.name}')
            check('classes.dex' in z.namelist(),f'no DEX: {p.name}')
            check(z.read('classes.dex')[:4]==b'dex\n',f'bad DEX signature: {p.name}')
    except (OSError,zipfile.BadZipFile) as exc:
        issues.append(f'plugin archive failure: {p.name}: {exc}')

xref=ROOT/'project/CS3_19_ARCHIVE_EXACT_SYMBOL_CROSSWALK.tsv'
if xref.is_file():
    with xref.open(encoding='utf-8-sig', newline='') as f:
        refs=list(csv.DictReader((line for line in f if line.strip() and not line.startswith('#')),delimiter='\t'))
    check(len(refs)==1577,f'crosswalk expected 1577 rows, got {len(refs)}')
    for row in refs:
        name=row['archive_file']; digest=row['archive_sha256'].lower()
        check(archive_hashes.get(name)==digest,f'crosswalk archive mismatch: {name}')
    unresolved=Counter(row['pinned_status'] for row in refs)
else:
    issues.append('crosswalk is missing')
    unresolved={}

xml_docs = list((ANDROID/'app/src/main').rglob('*.xml'))+list((ANDROID/'app/src/androidTest').rglob('*.xml'))
for p in xml_docs:
    try: ET.parse(p)
    except ET.ParseError as err: issues.append(f'XML failure {p.name}: {err}')

src=ANDROID/'app/src/main/java'
shell=(src/'com/auras/orbit/ui/OrbitShellActivity.java').read_text()
player=(src/'com/auras/orbit/player/OrbitPlayerActivity.java').read_text()
cs3=(src/'com/auras/orbit/extensions/CS3PluginRuntime.java').read_text()
build=(ANDROID/'app/build.gradle.kts').read_text()
checks={
 'actor_metadata_9nov': 'beginProviderActorMetadataLookup' in shell,
 'actor_page_bounded': 'PageWindow.of(metadata.credits.size()' in shell and
         'ACTOR_CREDITS_PAGE_SIZE = 256' in shell,
 'actor_page_navigation': 'provider_actor_credits_next' in shell and
         'provider_actor_credits_previous' in shell,
 'streaming_provider_playback': 'createDeferredProviderPlaybackIntent' in player and
         'startDeferredProviderResolution' in player,
 'handoff_expiry_monotonic': 'PlaybackHandoffExpiry.isExpired' in player,
 'bounded_poster_executor': 'new BoundedTaskExecutor("orbit-poster", 3, 96)' in shell,
 'bounded_jellyfin_executor': 'BoundedFutureExecutor' in (src/'com/auras/orbit/jellyfin/JellyfinClient.java').read_text(),
 'subtitle_archive_limit': 'SubtitleArchiveGuard' in (src/'com/lagradost/cloudstream3/subtitles/SubtitleResource.kt').read_text(),
 'cs3_normal_loading_without_global_crosswalk': 'private static boolean isOrdinaryPluginRuntimeEnabled()' in cs3
           and 'return true;' in cs3.split('private static boolean isOrdinaryPluginRuntimeEnabled()',1)[1][:180]
           and 'buildConfigField("boolean", "CS3_ACCEPTANCE_BUILD", "false")' in build,
 'new_tmdb_repository_present': (src/'com/auras/orbit/metadata/TmdbActorMetadataRepository.kt').is_file(),
 'native_gradle_wrapper': b'\r\n' not in (ANDROID/'gradlew').read_bytes(),
 'provider_order_admission': 'MAX_PROVIDERS_PER_BATCH = 256' in
     (src/'com/auras/orbit/providers/ProviderOperationCoordinator.java').read_text(),
 'link_field_admission': 'MAX_VERIFIER_CHARS = 16_384' in
     (src/'com/auras/orbit/providers/ProviderLinkCandidate.java').read_text(),
 'actor_credit_single_size_snapshot': 'int count = values.size();' in
     (src/'com/auras/orbit/metadata/ActorMetadata.java').read_text(),
 'collection_admission_test_wired': 'audit.ProviderCollectionAdmissionRegression' in
     (ANDROID/'tools/quality/jvm/run.sh').read_text(),

 'home_section_adversarial_test_wired': 'MAX_DISPLAY_ITEMS = 64' in (src/'com/auras/orbit/providers/HomeSection.java').read_text()
        and 'audit.HomeSectionAdversarialAdmissionRegression' in (ANDROID/'tools/quality/jvm/run.sh').read_text()
        and (ANDROID/'tools/quality/jvm/src/audit/HomeSectionAdversarialAdmissionRegression.java').is_file(),
 'playback_list_admission_test_wired': (ANDROID/'tools/quality/playback-admission/run.sh').is_file()
        and 'playback-admission/run.sh' in (ANDROID/'tools/quality/reconcile_all.sh').read_text(),
 'media_metadata_scan_budgets': 'METADATA_INPUT_SCAN_MULTIPLIER' in (src/'com/auras/orbit/providers/MediaItem.java').read_text(),
 'media_artwork_header_safety': 'isRestrictedTransportHeader' in (src/'com/auras/orbit/providers/MediaItem.java').read_text(),
 'jellyfin_pagination_distinct_offset': 'jellyfinPageState.nextServerIndex' in shell
        and 'JellyfinPageAccumulator.append' in shell
        and (src/'com/auras/orbit/jellyfin/JellyfinPageAccumulator.java').is_file(),
 'jellyfin_library_async_epoch': 'jellyfinLibraryRequestEpoch' in shell
        and 'libraryEpoch != jellyfinLibraryRequestEpoch' in shell,
 'jellyfin_array_admission': 'JellyfinResponseBudgets.requireCount' in (src/'com/auras/orbit/jellyfin/JellyfinClient.java').read_text(),
 'jellyfin_explicit_library_limit': 'jellyfinPageState.limitReached' in shell,
}
for title,value in checks.items():check(value, 'source wiring failed: '+title)
if issues:
    print('SOURCE_VERIFICATION_FAIL errors='+str(len(issues)))
    for i in issues[:50]: print('  '+i)
    sys.exit(1)
print('SOURCE_VERIFICATION_PASS files=%d incoming_files=%d modified_incoming=%d xml=%d cs3=%d crosswalk=%d checks=%d' %
      (len(expected),len(baseline),len(changed),len(xml_docs),len(archives),len(refs),len(checks)))
print('CROSSWALK_STATUS '+str(dict(unresolved)))
