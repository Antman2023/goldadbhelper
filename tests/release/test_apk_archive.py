"""In-memory APK assembly tests, independent of an Android SDK or temp directories."""
import importlib.util
import io
from pathlib import Path
import struct
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('apk_archive', Path(__file__).resolve().parents[2] / 'tools/apk_archive.py')
apk_archive = importlib.util.module_from_spec(spec)
spec.loader.exec_module(apk_archive)


class ApkArchiveTests(unittest.TestCase):
    def fixture(self, names):
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, 'w') as archive:
            for index, name in enumerate(names):
                info = zipfile.ZipInfo(name, (2024, 1, 2, 3, 4, 6))
                # Explicit assignment preserves the Windows aapt2 wire spelling
                # even on hosts whose ZipInfo constructor normalizes separators.
                info.filename = info.orig_filename = name
                info.compress_type = zipfile.ZIP_DEFLATED if index % 2 else zipfile.ZIP_STORED
                info.external_attr = 0o100644 << 16
                archive.writestr(info, bytes(range(256)) * 4)
        buffer.seek(0)
        return buffer

    def test_normalizes_both_headers_and_preserves_contents(self):
        source = self.fixture(['resources.arsc', 'assets/third_party\\license\\NOTICE', 'assets/中文.txt'])
        output = io.BytesIO()
        with zipfile.ZipFile(source) as original, zipfile.ZipFile(output, 'w') as target:
            before = [(info.filename, info.orig_filename) for info in original.infolist()]
            apk_archive.copy_apk_entries(original, target)
            self.assertEqual(before, [(info.filename, info.orig_filename) for info in original.infolist()])
        with zipfile.ZipFile(source) as original, zipfile.ZipFile(output) as result:
            self.assertIsNone(result.testzip())
            data = output.getvalue()
            for old, new in zip(original.infolist(), result.infolist()):
                self.assertEqual(old.orig_filename.replace('\\', '/'), new.orig_filename)
                self.assertEqual(original.read(old), result.read(new))
                self.assertEqual(old.compress_type, new.compress_type)
                self.assertEqual(old.date_time, new.date_time)
                self.assertEqual(old.external_attr, new.external_attr)
                header = struct.unpack_from('<4s5H3I2H', data, new.header_offset)
                name = data[new.header_offset + 30:new.header_offset + 30 + header[9]]
                self.assertEqual(new.orig_filename, name.decode('utf-8' if header[2] & 0x800 else 'cp437'))
                self.assertEqual((new.CRC, new.compress_size, new.file_size), header[6:9])

    def test_normalization_collision_fails_before_copying(self):
        source = self.fixture(['assets/a\\b', 'assets/a/b'])
        with zipfile.ZipFile(source) as original, zipfile.ZipFile(io.BytesIO(), 'w') as target:
            with self.assertRaisesRegex(ValueError, 'Duplicate APK entry'):
                apk_archive.copy_apk_entries(original, target)
            self.assertEqual([], target.infolist())

    def test_empty_archive(self):
        with zipfile.ZipFile(self.fixture([])) as original, zipfile.ZipFile(io.BytesIO(), 'w') as target:
            apk_archive.copy_apk_entries(original, target)
            self.assertEqual([], target.infolist())


if __name__ == '__main__':
    unittest.main()
