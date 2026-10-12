"""Host-only guardrails. Real SDK compilation/signature checks run separately in CI."""
import importlib.util
import json
import os
import pathlib
import shutil
import subprocess
import tempfile
import unittest
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('version', ROOT / 'tools/version.py')
version = importlib.util.module_from_spec(spec)
spec.loader.exec_module(version)


class VersionTests(unittest.TestCase):
    def read(self, name='1.2.3', code='12'):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / 'build.gradle'
            path.write_text(f"versionName '{name}'\nversionCode {code}\n")
            return version.read_version(path)

    def test_current_version(self):
        self.assertGreater(version.read_version()['version_code'], 0)

    def test_release(self):
        self.assertEqual(self.read(), {'version': '1.2.3', 'version_code': 12, 'prerelease': False})

    def test_prerelease(self):
        self.assertTrue(self.read('1.2.3-rc.1')['prerelease'])

    def test_invalid_names(self):
        for name in ['1.2', 'v1.2.3', '01.2.3', '1.2.3-01', '1.2.3+meta', '1.2.3/evil', '1.2.3-$(echo x)']:
            with self.subTest(name=name), self.assertRaises(ValueError):
                self.read(name)

    def test_invalid_codes(self):
        for code in ['0', '-1', '2100000001', 'codeVariable']:
            with self.subTest(code=code), self.assertRaises(ValueError):
                self.read(code=code)

    def test_tag(self):
        data = self.read('1.2.3-rc.1')
        version.validate_tag('v1.2.3-rc.1', data)
        for tag in ['1.2.3-rc.1', 'v1.2.3', 'v1.2.4']:
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                version.validate_tag(tag, data)

    def test_duplicate_values(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / 'build.gradle'
            path.write_text("versionName '1.2.3'\nversionName '2.0.0'\nversionCode 1\n")
            with self.assertRaises(ValueError):
                version.read_version(path)


class SnapshotTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = pathlib.Path(self.tmp.name)
        for directory in ['.github/workflows', 'app', 'core', 'server', 'server-power', 'third_party', 'vendor', 'licenses', 'tools', 'tests', 'docs', 'build']:
            (self.root / directory).mkdir(parents=True, exist_ok=True)
        for filename in ['.gitignore', '.gitattributes', 'README.md', 'LICENSE', 'build.gradle', 'settings.gradle', 'gradle.properties']:
            (self.root / filename).write_text('fixture')
        shutil.copyfile(ROOT / 'tools/snapshot-build.py', self.root / 'tools/snapshot-build.py')
        (self.root / '.github/workflows/ci.yml').write_text('workflow fixture')
        (self.root / 'build/debug.keystore').write_text('never distribute this fixture')

    def run_snapshot(self, command):
        return subprocess.run(['python3', 'tools/snapshot-build.py', command, 'build'], cwd=self.root, capture_output=True, text=True)

    def test_archive_includes_workflow_and_excludes_build_keys(self):
        result = self.run_snapshot('snapshot')
        self.assertEqual(result.returncode, 0, result.stderr)
        with zipfile.ZipFile(self.root / 'build/金甲虫ADB助手-对应源码及许可.zip') as archive:
            self.assertIn('.github/workflows/ci.yml', archive.namelist())
            self.assertNotIn('build/debug.keystore', archive.namelist())
        self.assertEqual(self.run_snapshot('verify').returncode, 0)

    def test_changed_input_rejected(self):
        self.assertEqual(self.run_snapshot('snapshot').returncode, 0)
        (self.root / 'README.md').write_text('changed')
        self.assertNotEqual(self.run_snapshot('verify').returncode, 0)

    def test_added_input_rejected(self):
        self.assertEqual(self.run_snapshot('snapshot').returncode, 0)
        (self.root / 'app/added.txt').write_text('new')
        self.assertNotEqual(self.run_snapshot('verify').returncode, 0)

    def test_private_key_input_rejected(self):
        (self.root / 'app/private.jks').write_text('fake test key')
        self.assertNotEqual(self.run_snapshot('snapshot').returncode, 0)


class SigningTests(unittest.TestCase):
    def test_missing_credentials_fail_closed(self):
        env = {key: value for key, value in os.environ.items() if not key.startswith('RELEASE_')}
        env['ANDROID_HOME'] = '/nonexistent-sdk'
        result = subprocess.run(['bash', 'tools/sign-apk.sh', 'absent.apk', 'absent-output.apk'], cwd=ROOT, env=env, capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('RELEASE_KEYSTORE', result.stderr)

    def test_shell_syntax(self):
        for script in ['tools/build-apk.sh', 'tools/build-servers.sh', 'tools/sign-apk.sh', 'tools/test-release-signing.sh']:
            subprocess.run(['bash', '-n', script], cwd=ROOT, check=True)


if __name__ == '__main__':
    unittest.main()
