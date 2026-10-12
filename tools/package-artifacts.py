#!/usr/bin/env python3
"""Verify build products and stage only explicitly allowed distributable files."""
import argparse
import hashlib
import json
import os
import pathlib
import re
import shutil
import subprocess
import zipfile
from version import read_version

ROOT = pathlib.Path(__file__).resolve().parent.parent


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mode', choices=['debug', 'release'], required=True)
    args = parser.parse_args()
    os.chdir(ROOT)
    version = read_version()
    name = version['version']
    build = pathlib.Path('build')
    apk = build / ('release-signed.apk' if args.mode == 'release' else f'outputs/金甲虫ADB助手-{name}.apk')
    bt = pathlib.Path(os.environ['ANDROID_HOME']) / 'build-tools/35.0.0'
    subprocess.run([str(bt / 'zipalign'), '-c', '-p', '4', str(apk)], check=True)
    cert = subprocess.check_output([str(bt / 'apksigner'), 'verify', '--verbose', '--print-certs', str(apk)], text=True)
    fingerprint = re.search(r'^Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]{64})$', cert, re.M)
    if not fingerprint:
        raise SystemExit('No verified signing certificate found')
    fingerprint = fingerprint[1].lower()
    if args.mode == 'release' and fingerprint != os.environ.get('RELEASE_CERT_SHA256', '').replace(':', '').lower():
        raise SystemExit('Unexpected release signing certificate')
    badging = subprocess.check_output([str(bt / 'aapt'), 'dump', 'badging', str(apk)], text=True)
    for field in ["name='io.github.devicebridge.goldenbeetle'", f"versionCode='{version['version_code']}'", f"versionName='{name}'"]:
        if field not in badging.splitlines()[0]:
            raise SystemExit('APK package/version does not match app/build.gradle')
    with zipfile.ZipFile(apk) as archive:
        for required in ['classes.dex', 'classes2.dex', 'assets/metadata-server.jar', 'assets/scrcpy-server.jar', 'lib/arm64-v8a/libconscrypt_jni.so', 'lib/armeabi-v7a/libconscrypt_jni.so']:
            if required not in archive.namelist() or not archive.getinfo(required).file_size:
                raise SystemExit('Missing APK component: ' + required)
    subprocess.run(['python3', 'tools/snapshot-build.py', 'verify', str(build)], check=True)
    dist = pathlib.Path('dist')
    if dist.exists() and any(dist.iterdir()):
        raise SystemExit('dist/ must be empty; remove old distribution outputs before packaging')
    dist.mkdir(exist_ok=True)
    suffix = '-debug' if args.mode == 'debug' else ''
    apk_name = f'goldadbhelper-{name}{suffix}.apk'
    source_name = f'goldadbhelper-{name}-source.zip'
    source = build / '金甲虫ADB助手-对应源码及许可.zip'
    shutil.copyfile(apk, dist / apk_name)
    shutil.copyfile(source, dist / source_name)
    shutil.copyfile(build / 'source-inputs.json', dist / 'source-inputs.json')
    (dist / 'signing-certificate.txt').write_text(cert, encoding='utf-8')
    metadata = json.loads((build / 'outputs/构建来源.json').read_text())
    metadata.update(version)
    metadata.update({
        'commit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip(),
        'tag': os.environ.get('GITHUB_REF_NAME') if os.environ.get('GITHUB_REF_TYPE') == 'tag' else None,
        'signing': args.mode,
        'certificate_sha256': fingerprint,
        'apk': apk_name,
        'apk_sha256': sha(apk),
        'corresponding_source': source_name,
        'source_archive_sha256': sha(source),
        'source_input_manifest_sha256': sha(build / 'source-inputs.json'),
        'verification': 'APK structure, version, signature, alignment and build inputs checked. Host tests do not replace Android runtime/device validation.',
        'toolchain': {'java': '17 (CI)', 'android_platform': 35, 'build_tools': '35.0.0'},
    })
    (dist / 'build-metadata.json').write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    (dist / 'SHA256SUMS.txt').write_text(''.join(f'{sha(p)}  {p.name}\n' for p in sorted(dist.iterdir())), encoding='utf-8')
    print(f'Packaged {apk_name} and matching source; signing certificate SHA-256: {fingerprint}')


if __name__ == '__main__':
    main()
