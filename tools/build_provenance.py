"""Verify recorded build products before staging a distribution."""
import hashlib
import json
import re
import zipfile

SOURCE_NAME = '金甲虫ADB助手-对应源码及许可.zip'
SERVERS = ('assets/metadata-server.jar', 'assets/scrcpy-server.jar')


def stream_sha(stream):
    digest = hashlib.sha256()
    for chunk in iter(lambda: stream.read(1024 * 1024), b''):
        digest.update(chunk)
    return digest.hexdigest()


def file_sha(path):
    with path.open('rb') as stream:
        return stream_sha(stream)


def archive_hashes(archive, omit_signatures=False):
    result = {}
    seen = set()
    for entry in archive.infolist():
        name = entry.filename
        if name in seen:
            raise ValueError('Duplicate archive entry: ' + name)
        seen.add(name)
        # APK Signing Blocks are outside ZIP entries. Only JAR signature records
        # may differ inside the archive; arbitrary META-INF content is payload.
        signature = re.fullmatch(r'META-INF/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC))', name, re.I)
        if omit_signatures and (signature or (name == 'META-INF/' and entry.is_dir())):
            continue
        with archive.open(entry) as stream:
            result[name] = stream_sha(stream)
    return result


def verify_source_archive(archive, manifest):
    if not isinstance(manifest, dict) or not manifest:
        raise ValueError('Missing source input manifest')
    for name, digest in manifest.items():
        if not isinstance(name, str) or not isinstance(digest, str) or not re.fullmatch('[0-9a-f]{64}', digest):
            raise ValueError('Invalid source input manifest entry')
    if archive_hashes(archive) != manifest:
        raise ValueError('Source archive contents do not match the input manifest')


def verify_build_products(build, apk, mode, version):
    if mode not in ('debug', 'release'):
        raise ValueError('Unsupported distribution mode')
    record = json.loads((build / 'outputs/构建来源.json').read_text(encoding='utf-8'))
    expected_signing = 'debug' if mode == 'debug' else 'unsigned'
    for key, expected in {'package': 'io.github.devicebridge.goldenbeetle',
                          'version': version['version'], 'version_code': version['version_code'],
                          'signing': expected_signing, 'corresponding_source': SOURCE_NAME}.items():
        if record.get(key) != expected:
            raise ValueError('Build provenance mismatch: ' + key)
    suffix = '-unsigned' if mode == 'release' else ''
    original = build / 'outputs' / ('金甲虫ADB助手-' + version['version'] + suffix + '.apk')
    source = build / SOURCE_NAME
    manifest_path = build / 'source-inputs.json'
    for path, field in ((original, 'apk_sha256'), (source, 'source_archive_sha256'),
                        (manifest_path, 'source_input_manifest_sha256')):
        if file_sha(path) != record.get(field):
            raise ValueError('Recorded build artifact changed: ' + field)
    manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
    with zipfile.ZipFile(source) as archive:
        verify_source_archive(archive, manifest)
    with zipfile.ZipFile(original) as archive:
        original_payload = archive_hashes(archive, omit_signatures=True)
    with zipfile.ZipFile(apk) as archive:
        payload = archive_hashes(archive, omit_signatures=True)
    if payload != original_payload:
        raise ValueError('Distributed APK payload differs from the recorded build')
    if mode == 'debug' and file_sha(apk) != record['apk_sha256']:
        raise ValueError('Debug APK differs from the recorded build')
    servers = {name: payload.get(name) for name in SERVERS}
    if (servers != record.get('bundled_servers') or
            any(digest is None or digest != manifest.get('app/src/main/' + name)
                for name, digest in servers.items())):
        raise ValueError('Bundled servers do not match the recorded source inputs')
    return record
