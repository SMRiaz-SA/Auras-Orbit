#!/usr/bin/env python3
"""Tests whether the actual page-window regression suite detects deliberate broken implementations.
All mutations exist only in temporary files; release source is never modified.
"""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[3]
source = root/'app/src/main/java/com/auras/orbit/ui/PageWindow.java'
regression = root/'tools/quality/jvm/src/audit/PageWindowRegression.java'
original = source.read_text(encoding='utf-8')
mutations = {
    'off_by_one_last_page': ('(total - 1) / pageSize', 'total / pageSize'),
    'incorrect_window_end': ('(long) start + pageSize', '(long) start + pageSize + 1'),
    'bad_negative_page_clamp': ('Math.max(0, Math.min(lastPage, requestedPage))',
                                'Math.min(lastPage, requestedPage)'),
}
with tempfile.TemporaryDirectory(prefix='orbit-mutation-') as temp:
    workspace = Path(temp)
    killed = 0
    for name,(old,new) in mutations.items():
        assert original.count(old)==1, f'Mutation point missing: {name}'
        mutated = workspace/'PageWindow.java'
        mutated.write_text(original.replace(old,new,1),encoding='utf-8')
        classes = workspace / ('classes_'+name)
        classes.mkdir()
        result=subprocess.run(['javac','--release','17','-encoding','UTF-8',
            '-d',str(classes),str(mutated),str(regression)],capture_output=True,text=True)
        if result.returncode != 0:
            raise RuntimeError(f'Invalid mutation {name}: {result.stderr[-1200:]}')
        run=subprocess.run(['java','-Xmx512m','-cp',str(classes),'audit.PageWindowRegression'],
            capture_output=True,text=True,timeout=45)
        if run.returncode == 0:
            raise AssertionError(f'Regression suite missed mutation {name}')
        if 'AssertionError' not in run.stderr:
            raise AssertionError(f'Unexpected failure type for {name}: {run.stderr[-1200:]}')
        killed+=1
        print(f'MUTATION_DETECTED name={name}')
print(f'PAGE_WINDOW_MUTATION_SMOKE_PASS detected={killed}/{len(mutations)}')
