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

# Source contracts supplement the executable fragment harness. Builders themselves need Android.
for name in ('information','tools','remote','files','apps','processes','terminal'):
    body=block('private void '+name+'(LinearLayout body)')
    assert 'content.' not in body and 'Ui.fill(content,' not in body, name
    assert 'activateModule(' not in body and 'generation++' not in body, name
    if name in ('information','files','apps','processes'):
        assert 'initialModuleRead(' in body and 'AdbSession target=viewSession;' in body, name
assert 'initialModuleRead(' in block('private void wirelessTool(')
assert 'final AdbSession bound=viewSession;' in block('private void wirelessTool(')
assert 'sendTerminal(' not in block('private void terminal(LinearLayout body)')
assert 'new RemoteSettingsView(this,()->startActivity(' in block('private void remote(LinearLayout body)')
assert 'appList(body,Collections.emptyList())' in block('private void apps(LinearLayout body)')
assert 'new DeviceInfoView(this,Collections.emptyMap()' in block('private void information(LinearLayout body)')
assert 'TextView preview=' not in block('private ModulePage modulePage(')
assert 'startModuleRead(entry);' in block('private void activateModule(')
assert 'if(index==6){terminalExitRequested=false;sendTerminal(null);}' in block('private void activateModule(')
markers = ['private static final class ModulePage', 'private ModulePage modulePage(',
           'private void activateModule(', 'private void startModuleRead(', 'private boolean currentModule(',
           'private <T> void initialModuleRead(', 'private <T> void work(',
           'private interface ScopedJob<T>', 'private void cancelScopedWork()',
           'private void finishScopedWork(', 'private <T> void workScoped(']
template = (root / 'tests/navigation/neighbor/NeighborPreparationTests.java.template').read_text()
assert template.count('/* PRODUCTION_FRAGMENTS */') == 1
out = Path(sys.argv[1]); out.mkdir(parents=True, exist_ok=True)
(out / 'NeighborPreparationTests.java').write_text(template.replace('/* PRODUCTION_FRAGMENTS */', '\n'.join(block(m) for m in markers)))
print('Extracted unchanged module creation/activation/read/lifecycle blocks; builder ownership and deferred-I/O source contracts verified.')
