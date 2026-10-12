#!/usr/bin/env python3
"""Compile unchanged MainActivity lifecycle fragments with deterministic in-memory UI doubles.
This is source-fragment regression coverage, not Android Activity/runtime verification.
"""
from pathlib import Path
import sys
root = Path(__file__).resolve().parents[3]
source = (root / 'app/src/main/java/io/github/devicebridge/android/MainActivity.java').read_text()

def block(marker):
    start = source.index(marker)
    opened = source.index('{', start)
    depth, state, escaped = 0, 'code', False
    i = opened
    while i < len(source):
        c = source[i]
        next_c = source[i + 1] if i + 1 < len(source) else ''
        if state in ('string', 'char'):
            if escaped: escaped = False
            elif c == '\\': escaped = True
            elif c == ('"' if state == 'string' else "'"): state = 'code'
        elif state == 'line':
            if c == '\n': state = 'code'
        elif state == 'comment':
            if c == '*' and next_c == '/': state = 'code'; i += 1
        elif c == '/' and next_c == '/': state = 'line'; i += 1
        elif c == '/' and next_c == '*': state = 'comment'; i += 1
        elif c == '"': state = 'string'
        elif c == "'": state = 'char'
        elif c == '{': depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0: return source[start:i + 1]
        i += 1
    raise ValueError('Unclosed source block: ' + marker)

assert 'if(page>=0)cancelScopedWork();' in block('private void disposeModules()')
assert 'cancelScopedWork();' in block('@Override protected void onDestroy()')
markers = ['private static final class ModulePage', 'private void invalidateFileModule()',
           'private interface ScopedJob<T>', 'private void cancelScopedWork()',
           'private void finishScopedWork(', 'private <T> void workScoped(',
           '@Override protected void onResume()']
template = (root / 'tests/navigation/lifecycle/Nav02LifecycleTests.java.template').read_text()
assert template.count('/* PRODUCTION_FRAGMENTS */') == 1
out = Path(sys.argv[1]); out.mkdir(parents=True, exist_ok=True)
(out / 'Nav02LifecycleTests.java').write_text(template.replace('/* PRODUCTION_FRAGMENTS */', '\n'.join(block(m) for m in markers)))
print('Extracted unchanged MainActivity lifecycle blocks; dispose/destroy call-site contracts verified.')
