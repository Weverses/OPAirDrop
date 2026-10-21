#!/usr/bin/env python3
"""Exercise the installer with isolated filesystem/framework fixtures, no phone writes."""
from pathlib import Path
import os
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class InstallerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.adb = self.root / 'adb'
        self.system = self.root / 'system/bin'
        self.system.mkdir(parents=True)
        self.base = self.root / 'module'
        (self.base / 'companion').mkdir(parents=True)
        (self.base / 'companion/OShareAirDrop.apk').write_bytes(b'fake-apk')
        self.state = self.adb / 'opairshare'
        self.script = self.root / 'install.sh'
        text = (ROOT / 'module/bin/install-oshare.sh').read_text()
        self.script.write_text(text.replace('/data/adb', str(self.adb)).replace('/system/bin', str(self.system)))
        self.env = dict(os.environ, PATH=str(self.system) + ':' + os.environ['PATH'], BOOT_COMPLETE='1')
        self.command('getprop', 'echo "$BOOT_COMPLETE"')
        self.command('pm', '''
echo "$*" >> "$CALLS"
case "$1" in
 install) cat >/dev/null; if [ "${FAIL_PM:-0}" = 1 ]; then echo 'Failure [INSTALL_FAILED_TEST]'; exit 1; fi; echo Success;;
 path) echo package:/data/app/oshare/base.apk;;
esac''')
        self.command('app_process', 'echo "activation $*" >> "$CALLS"; exit "${FAIL_ACTIVATION:-0}"')
        self.command('cmp', 'exit "${APK_MATCHES:-1}"')
        self.env['CALLS'] = str(self.root / 'calls')

    def command(self, name, text):
        path = self.system / name
        path.write_text('#!/bin/sh\n' + text + '\n')
        path.chmod(0o755)

    def lsposed(self, database=True):
        prop = self.adb / 'modules/zygisk_lsposed/module.prop'
        prop.parent.mkdir(parents=True)
        prop.write_text('id=zygisk_lsposed\nname=LSPosed\n')
        if database:
            db = self.adb / 'lspd/config/modules_config.db'
            db.parent.mkdir(parents=True)
            db.touch()

    def run_script(self, phase='install'):
        return subprocess.run(['sh', str(self.script), str(self.base), phase], env=self.env, capture_output=True, text=True)

    def test_absent_lsposed_skips_package_manager(self):
        result = self.run_script()
        self.assertEqual(result.returncode, 0)
        self.assertIn('未检测到 LSPosed', result.stdout)
        self.assertFalse((self.root / 'calls').exists())
        self.assertFalse((self.state / 'oshare-setup-pending').exists())

    def test_normal_install_activates_and_clears_pending(self):
        self.lsposed()
        result = self.run_script()
        self.assertEqual(result.returncode, 0, result.stderr)
        calls = (self.root / 'calls').read_text()
        self.assertIn('install -r --user 0 -S 8', calls)
        self.assertIn('activation', calls)
        self.assertFalse((self.state / 'oshare-setup-pending').exists())
        self.assertFalse((self.state / 'oshare-reboot-required').exists())

    def test_recovery_defers_then_boot_requests_reboot(self):
        self.lsposed()
        self.env['BOOT_COMPLETE'] = '0'
        self.assertEqual(self.run_script().returncode, 10)
        self.assertFalse((self.root / 'calls').exists())
        self.assertTrue((self.state / 'oshare-setup-pending').exists())
        self.env['BOOT_COMPLETE'] = '1'
        self.assertEqual(self.run_script('boot').returncode, 0)
        self.assertTrue((self.state / 'oshare-reboot-required').exists())

    def test_activation_retry_reuses_identical_installed_apk(self):
        self.lsposed()
        self.env['APK_MATCHES'] = '0'
        result = self.run_script('boot')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        calls = (self.root / 'calls').read_text()
        self.assertNotIn('install -r', calls)
        self.assertIn('activation', calls)

    def test_package_failure_never_activates(self):
        self.lsposed()
        self.env['FAIL_PM'] = '1'
        self.assertEqual(self.run_script().returncode, 1)
        self.assertNotIn('activation', (self.root / 'calls').read_text())
        self.assertTrue((self.state / 'oshare-setup-pending').exists())

    def test_activation_failure_keeps_pending_and_bounds_boot_retry(self):
        self.lsposed()
        self.env['FAIL_ACTIVATION'] = '1'
        self.assertEqual(self.run_script().returncode, 1)
        for _ in range(3):
            self.assertEqual(self.run_script('boot').returncode, 1)
            self.assertTrue((self.state / 'oshare-setup-pending').exists())
        (self.root / 'calls').unlink()
        self.assertEqual(self.run_script('boot').returncode, 1)
        self.assertFalse((self.state / 'oshare-setup-pending').exists())
        self.assertFalse((self.root / 'calls').exists())

    def test_missing_database_defers_activation(self):
        self.lsposed(database=False)
        self.assertEqual(self.run_script().returncode, 10)
        self.assertNotIn('activation', (self.root / 'calls').read_text())
        self.assertTrue((self.state / 'oshare-setup-pending').exists())


if __name__ == '__main__':
    unittest.main()
