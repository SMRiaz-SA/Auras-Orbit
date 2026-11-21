#!/usr/bin/env python3
"""Deterministic source-line inventory and review routing; NOT an Android/semantic correctness proof.

Reads text files under the inherited source project. Each line is indexed with a stable
SHA-256, file coordinate and search-rule review tags; no secrets or source text are
copied into the index. Humans must still investigate the tagged sites.
"""
import collections
import gzip
import io
import hashlib
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
PROJECT = ROOT
OUT = ROOT / 'audit-evidence' / 'line-level-2026-11-10'
TEXT_EXT = {'.java', '.kt', '.kts', '.xml', '.go', '.gradle', '.sh', '.py',
            '.ps1', '.bat', '.md', '.json', '.tsv', '.txt', '.toml', '.properties',
            '.yml', '.yaml', '.mod', '.sum', '.patch', '.sql', '.css', '.html',
            '.gitignore', '.pro', '.cfg'}
RULES = {
    'STREAM_PROGRESS': r'\.read\s*\(|\.write\s*\(|readBytes\s*\(',
    'TASK_EXECUTION': r'Executors\.|ThreadPoolExecutor|ScheduledThreadPoolExecutor|\.execute\s*\(|\.submit\s*\(',
    'ERROR_BOUNDARY': r'\bcatch\s*\(|\bfinally\b|\bthrow\b|\.onFailure\s*\(',
    'INTEGER_BOUNDARY': r'Integer\.MAX_VALUE|Long\.MAX_VALUE|\.size\(\)\s*[+*]|start\s*\+',
    'TRUST_NETWORK': r'https?://|HttpURLConnection|OkHttp|WebView|addJavascriptInterface|DexClassLoader|loadClass\s*\(',
    'PERSISTENCE': r'SQLite|RoomDatabase|database\.execSQL|\.beginTransaction\s*\(|\.commit\s*\(',
    'CRYPTO_SECRET': r'Cipher\.|SecretKey|KeyStore|password|accessToken|authorization|Bearer\s',
    'ANDROID_LIFECYCLE': r'\bonCreate\s*\(|\bonDestroy\s*\(|\bonStop\s*\(|registerReceiver|unregisterReceiver|observeForever',
    'UNBOUNDED_QUEUE_CANDIDATE': r'newSingleThreadExecutor|newFixedThreadPool|LinkedBlockingQueue\s*<|newCachedThreadPool',
    'SUPPRESSED_EXCEPTION': r'catch\s*\([^)]*\)\s*\{\s*\}',
}
COMPILED = {name: re.compile(pattern, re.IGNORECASE if name == 'CRYPTO_SECRET' else 0)
            for name, pattern in RULES.items()}

def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    files, lines, nonblank, tagged = 0, 0, 0, 0
    tags = collections.Counter()
    per_file = []
    locations = []
    gz_file = OUT / 'source_line_ledger.jsonl.gz'
    with io.TextIOWrapper(gzip.GzipFile(filename=str(gz_file), mode='wb', compresslevel=9, mtime=0), encoding='utf-8') as ledger:
        for path in sorted(p for p in PROJECT.rglob('*') if p.is_file()
                           and 'audit-evidence' not in p.relative_to(PROJECT).parts
                           and p.name != 'SOURCE_REBUILD_MANIFEST.json'
                           and (p.suffix.lower() in TEXT_EXT or p.name in {'gradlew','.gitignore'})):
            if path.stat().st_size > 8 * 1024 * 1024:
                raise ValueError('unexpected oversized text file: '+str(path))
            data = path.read_bytes()
            if b'\x00' in data:
                raise ValueError('binary file advertised as text: '+str(path))
            content = data.decode('utf-8-sig', errors='replace')
            relative = path.relative_to(ROOT).as_posix()
            file_tags = collections.Counter()
            count = 0
            for count, line in enumerate(content.splitlines(), 1):
                names = [name for name, rgx in COMPILED.items() if rgx.search(line)]
                for name in names:
                    tags[name] += 1
                    file_tags[name] += 1
                    if name in ('UNBOUNDED_QUEUE_CANDIDATE', 'SUPPRESSED_EXCEPTION'):
                        locations.append({'file': relative, 'line': count, 'tag': name})
                lines += 1
                if line.strip(): nonblank += 1
                if names: tagged += 1
                ledger.write(json.dumps({'file': relative, 'line': count,
                    'sha256': hashlib.sha256(line.encode('utf-8')).hexdigest(),
                    'tags': names}, ensure_ascii=False, separators=(',', ':'))+'\n')
            files += 1
            per_file.append({'file': relative, 'lines': count,
                             'sha256': hashlib.sha256(data).hexdigest(),
                             'review_tags': dict(file_tags)})
    summary = {'scope':'Every UTF-8 text source/config/document line in rebuilt bundle, excluding generated audit-evidence, with listed suffixes',
        'method':'SHA-256 line inventory + regex risk-routing; NOT an end-to-end manual semantic proof',
        'files':files,'lines':lines,'nonblank_lines':nonblank,'tagged_lines':tagged,
        'rules':RULES,'tag_counts':dict(tags),'flagged_locations':locations,
        'file_index':per_file,'ledger':gz_file.name}
    (OUT / 'source_line_audit_summary.json').write_text(json.dumps(summary, indent=2)+'\n',encoding='utf-8')
    print('SOURCE_LINE_INVENTORY files=%d lines=%d nonblank=%d flagged=%d' % (files, lines, nonblank, tagged))
    print('REVIEW_TAG_COUNTS '+json.dumps(dict(tags), sort_keys=True))
    print('REVIEW_CANDIDATES '+str(len(locations)))
    return 0

if __name__=='__main__':
    sys.exit(main())
