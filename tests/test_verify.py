#!/usr/bin/env python3
"""Verify archive integrity and the real KernelSU post-install file layout."""
from pathlib import Path
import hashlib
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class VerifyTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.base = Path(temp.name)
        payload = {'README.md': b'documentation', 'customize.sh': b'installer',
                   'bin/control.sh': b'runtime', 'system/system_ext/bin/mosey_server': b'server'}
        for name, content in payload.items():
            path = self.base / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)
        (self.base / 'SHA256SUMS').write_text(''.join(
            f'{hashlib.sha256(data).hexdigest()}  {name}\n' for name, data in payload.items()))

    def verify(self, mode='archive'):
        return subprocess.run(['sh', str(ROOT / 'module/bin/verify.sh'), str(self.base), mode],
                              capture_output=True, text=True)

    def test_archive_requires_installation_files(self):
        self.assertEqual(self.verify().returncode, 0)
        (self.base / 'README.md').unlink()
        self.assertNotEqual(self.verify().returncode, 0)

    def test_installed_layout_allows_only_removed_installation_files(self):
        for name in ('README.md', 'customize.sh'):
            (self.base / name).unlink()
        (self.base / 'system/system_ext').rename(self.base / 'system_ext')
        self.assertEqual(self.verify('installed').returncode, 0)
        (self.base / 'bin/control.sh').unlink()
        self.assertNotEqual(self.verify('installed').returncode, 0)

    def test_installed_layout_still_rejects_tampering(self):
        for name in ('README.md', 'customize.sh', 'bin/control.sh'):
            with self.subTest(name=name):
                path = self.base / name
                original = path.read_bytes()
                path.write_bytes(b'tampered')
                self.assertNotEqual(self.verify('installed').returncode, 0)
                path.write_bytes(original)


if __name__ == '__main__':
    unittest.main()
