"""Distribution binding with real ZIP parsing and an in-memory filesystem.

Signature verification itself belongs to apksigner in package-artifacts.py.
"""
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import unittest
from unittest.mock import patch
import warnings
import zipfile

spec = importlib.util.spec_from_file_location('build_provenance', Path(__file__).resolve().parents[2] / 'tools/build_provenance.py')
provenance = importlib.util.module_from_spec(spec)
spec.loader.exec_module(provenance)
ZipFile = zipfile.ZipFile


def digest(data):
    return hashlib.sha256(data).hexdigest()


def archive(entries):
    output = io.BytesIO()
    with ZipFile(output, 'w') as result, warnings.catch_warnings():
        warnings.simplefilter('ignore', UserWarning)  # Deliberate duplicate-entry fixture.
        for name, data in entries:
            result.writestr(name, data)
    return output.getvalue()


class MemoryPath:
    def __init__(self, files, name):
        self.files, self.name = files, name

    def __truediv__(self, name):
        return MemoryPath(self.files, self.name + '/' + name)

    def open(self, mode='rb'):
        return io.BytesIO(self.files[self.name])

    def read_text(self, encoding='utf-8'):
        return self.files[self.name].decode(encoding)


class BuildProvenanceTests(unittest.TestCase):
    def setUp(self):
        self.files = {}
        self.build = MemoryPath(self.files, 'build')
        self.version = {'version': '1.2.3', 'version_code': 12}
        self.payload = {'classes.dex': b'dex fixture', 'AndroidManifest.xml': b'manifest fixture',
                        provenance.SERVERS[0]: b'metadata fixture', provenance.SERVERS[1]: b'screen fixture'}
        self.inputs = {'README.md': b'source fixture', **{'app/src/main/' + name: self.payload[name] for name in provenance.SERVERS}}

    def prepare(self, mode='debug'):
        self.mode = mode
        suffix = '-unsigned' if mode == 'release' else ''
        self.original = 'build/outputs/金甲虫ADB助手-1.2.3' + suffix + '.apk'
        self.candidate = 'build/distribution.apk'
        self.files[self.original] = archive(self.payload.items())
        candidate = dict(self.payload)
        if mode == 'release':
            candidate.update({'META-INF/MANIFEST.MF': b'JAR manifest', 'META-INF/RELEASE.SF': b'signature file',
                              'META-INF/RELEASE.RSA': b'certificate fixture'})
        self.files[self.candidate] = self.files[self.original] if mode == 'debug' else archive(candidate.items())
        self.files['build/' + provenance.SOURCE_NAME] = archive(self.inputs.items())
        self.files['build/source-inputs.json'] = json.dumps({name: digest(data) for name, data in self.inputs.items()}).encode()
        self.record = {'package': 'io.github.devicebridge.goldenbeetle', **self.version,
                       'signing': 'unsigned' if mode == 'release' else 'debug',
                       'corresponding_source': provenance.SOURCE_NAME,
                       'apk_sha256': digest(self.files[self.original]),
                       'source_archive_sha256': digest(self.files['build/' + provenance.SOURCE_NAME]),
                       'source_input_manifest_sha256': digest(self.files['build/source-inputs.json']),
                       'bundled_servers': {name: digest(self.payload[name]) for name in provenance.SERVERS}}

    def verify(self):
        self.files['build/outputs/构建来源.json'] = json.dumps(self.record).encode()
        # Only file access is replaced; hashing, ZIP CRCs and all validation use production code.
        with patch.object(provenance.zipfile, 'ZipFile', side_effect=lambda path: ZipFile(path.open())):
            return provenance.verify_build_products(self.build, MemoryPath(self.files, self.candidate), self.mode, self.version)

    def test_debug_and_signed_release_payloads(self):
        for mode in ('debug', 'release'):
            with self.subTest(mode=mode):
                self.prepare(mode)
                self.assertEqual(self.record, self.verify())

    def test_record_fields_must_match(self):
        for key, value in [('package', 'wrong'), ('version', '2.0.0'), ('version_code', 13),
                           ('signing', 'unsigned'), ('corresponding_source', '../other.zip')]:
            with self.subTest(key=key):
                self.prepare(); self.record[key] = value
                with self.assertRaisesRegex(ValueError, 'provenance mismatch'):
                    self.verify()

    def test_replaced_artifacts_are_not_silently_rehashed(self):
        for item in ('source', 'manifest', 'apk'):
            with self.subTest(item=item):
                self.prepare()
                path = {'source': 'build/' + provenance.SOURCE_NAME, 'manifest': 'build/source-inputs.json', 'apk': self.original}[item]
                self.files[path] += b'changed'
                with self.assertRaisesRegex(ValueError, 'artifact changed'):
                    self.verify()

    def test_source_contents_must_match_even_if_recorded_zip_hash_is_updated(self):
        for alteration in ('changed', 'missing', 'extra', 'duplicate'):
            with self.subTest(alteration=alteration):
                self.prepare()
                entries = list(self.inputs.items())
                if alteration == 'changed': entries[0] = ('README.md', b'other source')
                if alteration == 'missing': entries.pop()
                if alteration == 'extra': entries.append(('unexpected.txt', b'extra'))
                if alteration == 'duplicate': entries.append(entries[0])
                content = archive(entries)
                self.files['build/' + provenance.SOURCE_NAME] = content
                self.record['source_archive_sha256'] = digest(content)
                with self.assertRaises(ValueError):
                    self.verify()

    def test_release_payload_cannot_change_during_signing(self):
        for alteration in ('dex', 'missing', 'meta_inf', 'duplicate'):
            with self.subTest(alteration=alteration):
                self.prepare('release')
                entries = list(self.payload.items())
                if alteration == 'dex': entries[0] = ('classes.dex', b'different executable')
                if alteration == 'missing': entries.pop()
                if alteration == 'meta_inf': entries.append(('META-INF/services/custom.Provider', b'executable config'))
                if alteration == 'duplicate': entries.append(entries[0])
                self.files[self.candidate] = archive(entries)
                with self.assertRaises(ValueError):
                    self.verify()

    def test_debug_re_signing_is_not_accepted(self):
        self.prepare()
        self.files[self.candidate] = archive([*self.payload.items(), ('META-INF/OTHER.RSA', b'other certificate')])
        with self.assertRaisesRegex(ValueError, 'Debug APK differs'):
            self.verify()

    def test_helper_must_match_source_manifest(self):
        self.prepare('release')
        changed = dict(self.payload); changed[provenance.SERVERS[0]] = b'different helper'
        self.files[self.original] = self.files[self.candidate] = archive(changed.items())
        self.record['apk_sha256'] = digest(self.files[self.original])
        self.record['bundled_servers'][provenance.SERVERS[0]] = digest(b'different helper')
        with self.assertRaisesRegex(ValueError, 'Bundled servers'):
            self.verify()

    def test_corrupt_zip_payload_is_rejected(self):
        self.prepare()
        data = self.files['build/' + provenance.SOURCE_NAME].replace(b'source fixture', b'broken fixture')
        self.files['build/' + provenance.SOURCE_NAME] = data
        self.record['source_archive_sha256'] = digest(data)
        with self.assertRaises(zipfile.BadZipFile):
            self.verify()


if __name__ == '__main__':
    unittest.main()
