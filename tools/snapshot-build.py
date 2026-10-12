#!/usr/bin/env python3
"""Freeze corresponding source, licenses and pinned build inputs for each APK candidate."""
import hashlib, json, pathlib, sys, zipfile

root = pathlib.Path(__file__).resolve().parent.parent
output = root / sys.argv[2]
manifest = output / 'source-inputs.json'
directories = ['.github', 'app', 'core', 'server', 'server-power', 'third_party', 'vendor', 'licenses', 'tools', 'tests', 'docs']
def inputs():
    files = [root / name for name in ['.gitignore', '.gitattributes', 'README.md', 'LICENSE', 'build.gradle', 'settings.gradle', 'gradle.properties']]
    for name in directories:
        files.extend(p for p in (root / name).rglob('*') if p.is_file() and '__pycache__' not in p.parts and p.suffix != '.pyc')
    return sorted(set(files))
if sys.argv[1] == 'snapshot':
    records = {}
    output.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output / '金甲虫ADB助手-对应源码及许可.zip', 'w', zipfile.ZIP_DEFLATED) as archive:
        for path in inputs():
            relative = path.relative_to(root).as_posix()
            if path.suffix.lower() in {'.keystore', '.jks', '.p12', '.pfx', '.pem', '.key'} or path.name.startswith('.env'):
                raise SystemExit('Refusing private credential in source archive: ' + relative)
            if path.is_symlink():
                raise SystemExit('Unexpected linked build input: ' + relative)
            data = path.read_bytes()
            records[relative] = hashlib.sha256(data).hexdigest()
            archive.writestr(relative, data)
    manifest.write_text(json.dumps(records, ensure_ascii=False, indent=2) + '\n')
elif sys.argv[1] == 'verify':
    changed = []
    recorded = json.loads(manifest.read_text())
    changed.extend(sorted({p.relative_to(root).as_posix() for p in inputs()} - set(recorded)))
    for relative, expected in recorded.items():
        path = root / relative
        if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != expected:
            changed.append(relative)
    if changed:
        raise SystemExit('Build inputs changed during compilation; discard this candidate and rebuild: ' + ', '.join(changed))
else:
    raise SystemExit('Use snapshot or verify, followed by the build output directory')
