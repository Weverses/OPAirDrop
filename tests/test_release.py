#!/usr/bin/env python3
"""Validate update metadata against the actual packaged module identity and hashes."""
from pathlib import Path
import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools'))
from release import emit_metadata, update_url


class ReleaseTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.archive = self.root / 'OPAirDrop-v1.0.2-PJZ110.zip'
        self.output = self.root / 'metadata'

    def write_zip(self, *, overrides=None, tamper=None, omit=None, duplicate=None):
        props = {'id': 'opairshare', 'name': 'OPAirDrop', 'version': '1.0.2', 'versionCode': '10002',
                 'updateJson': update_url('Weverses/OPAirDrop')}
        props.update(overrides or {})
        payload = {'module.prop': ''.join(f'{k}={v}\n' for k, v in props.items()).encode(),
                   'service.sh': b'#!/system/bin/sh\nexit 0\n',
                   'CHANGELOG.md': '# 更新日志\n\n## 1.0.2\n\n- 支持 KernelSU 在线更新。\n\n## 1.0.1\n\n- 内置 GMS。\n'.encode()}
        manifest = ''.join(f'{hashlib.sha256(value).hexdigest()}  {name}\n' for name, value in payload.items() if name != omit)
        if tamper:
            payload[tamper] += b'# tampered\n'
        with zipfile.ZipFile(self.archive, 'w') as z:
            for name, value in payload.items():
                z.writestr(name, value)
            z.writestr('SHA256SUMS', manifest)
            if duplicate:
                z.writestr(duplicate, payload[duplicate])

    def test_update_points_to_exact_matching_release_assets(self):
        self.write_zip()
        result = emit_metadata(self.archive, self.output, tag='v1.0.2')
        self.assertEqual(result, {
            'version': '1.0.2', 'versionCode': 10002,
            'zipUrl': 'https://github.com/Weverses/OPAirDrop/releases/download/v1.0.2/OPAirDrop-v1.0.2-PJZ110.zip',
            'changelog': 'https://github.com/Weverses/OPAirDrop/releases/download/v1.0.2/changelog.md'})
        self.assertEqual(json.loads((self.output / 'update.json').read_text()), result)
        notes = (self.output / 'changelog.md').read_text()
        self.assertIn('支持 KernelSU 在线更新', notes)
        self.assertNotIn('内置 GMS', notes)
        self.assertEqual((self.output / (self.archive.name + '.sha256')).read_text(),
                         hashlib.sha256(self.archive.read_bytes()).hexdigest() + '  ' + self.archive.name + '\n')

    def test_wrong_release_tag_or_repository_fails_without_output(self):
        self.write_zip()
        for kwargs in ({'tag': 'v1.0.1'}, {'repository': 'another/repo'}):
            with self.subTest(kwargs=kwargs), self.assertRaises(ValueError):
                emit_metadata(self.archive, self.output, **kwargs)
            self.assertFalse(self.output.exists())

    def test_renamed_archive_uses_actual_asset_name(self):
        for name, encoded in [('OPAirDrop-v1.0.31-8Elite.zip', 'OPAirDrop-v1.0.31-8Elite.zip'),
                              ('自定义 module #1.zip', '%E8%87%AA%E5%AE%9A%E4%B9%89%20module%20%231.zip')]:
            with self.subTest(name=name):
                self.archive = self.root / name
                self.write_zip()
                result = emit_metadata(self.archive, self.output, tag='v1.0.2')
                self.assertEqual(result['zipUrl'],
                                 'https://github.com/Weverses/OPAirDrop/releases/download/v1.0.2/' + encoded)
                self.assertEqual((self.output / (name + '.sha256')).read_text(),
                                 hashlib.sha256(self.archive.read_bytes()).hexdigest() + '  ' + name + '\n')

    def run_archive_directory(self):
        return subprocess.run([sys.executable, str(ROOT / 'tools/release.py'),
                               '--archive-dir', str(self.root), '--out', str(self.output),
                               '--tag', 'v1.0.2'], capture_output=True, text=True)

    def test_archive_directory_accepts_arbitrary_filename(self):
        self.archive = self.root / '随便命名的模块.zip'
        self.write_zip()
        result = self.run_archive_directory()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout), json.loads((self.output / 'update.json').read_text()))

    def test_archive_directory_rejects_missing_or_multiple_zips(self):
        result = self.run_archive_directory()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('found 0', result.stderr)
        self.write_zip()
        (self.root / 'another.zip').write_bytes(self.archive.read_bytes())
        result = self.run_archive_directory()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('found 2', result.stderr)
        self.assertIn('another.zip', result.stderr)
        self.assertFalse(self.output.exists())

    def test_renaming_cannot_bypass_release_tag_validation(self):
        self.archive = self.root / 'custom.zip'
        self.write_zip(overrides={'version': '1.0.3'})
        with self.assertRaisesRegex(ValueError, 'Release tag'):
            emit_metadata(self.archive, self.output, tag='v1.0.2')
        self.assertFalse(self.output.exists())

    def test_modified_payload_fails_before_creating_metadata(self):
        self.write_zip(tamper='service.sh')
        with self.assertRaisesRegex(ValueError, 'checksum mismatch'):
            emit_metadata(self.archive, self.output)
        self.assertFalse(self.output.exists())

    def test_unhashed_payload_is_rejected(self):
        self.write_zip(omit='service.sh')
        with self.assertRaisesRegex(ValueError, 'manifest does not cover'):
            emit_metadata(self.archive, self.output)
        self.assertFalse(self.output.exists())

    def test_wrong_module_or_version_rejected(self):
        for overrides in ({'id': 'other'}, {'versionCode': '-1'}, {'versionCode': '2147483648'},
                          {'version': '1.0.2-beta'}):
            with self.subTest(overrides=overrides):
                self.write_zip(overrides=overrides)
                with self.assertRaises(ValueError):
                    emit_metadata(self.archive, self.output)
                self.assertFalse(self.output.exists())

    def test_duplicate_zip_entries_rejected(self):
        import warnings
        with warnings.catch_warnings():
            warnings.simplefilter('ignore', UserWarning)
            self.write_zip(duplicate='module.prop')
        with self.assertRaisesRegex(ValueError, 'Duplicate ZIP entries'):
            emit_metadata(self.archive, self.output)


if __name__ == '__main__':
    unittest.main()
