#!/usr/bin/env python3
"""Exercise actual GMS filtering/preparation and boot mounts with isolated fixtures."""
from pathlib import Path
import hashlib
import os
import shutil
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
RESTRICTED = {'cn.google.services', 'com.google.android.feature.services_updater'}


class GmsUnlockTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='opairshare-gms-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.module = self.root / 'module with spaces'
        (self.module / 'bin').mkdir(parents=True)
        (self.module / 'module.prop').write_text('id=opairshare\n')
        for name in ('setup-gms.sh', 'gms-unlock.sh', 'gms-paths.txt', 'gms-filter.awk', 'root-runtime.sh'):
            shutil.copy2(ROOT / 'module/bin' / name, self.module / 'bin' / name)
        self.device = self.root / 'device'
        self.device.mkdir()
        self.fakebin = self.root / 'commands'
        self.fakebin.mkdir()
        self.calls = self.root / 'calls'
        self.env = dict(os.environ, PATH=str(self.fakebin) + ':' + os.environ['PATH'], CALLS=str(self.calls))
        self.command('chown', 'exit 0')
        self.command('chcon', 'exit "${FAIL_CONTEXT:-0}"')
        self.command('mount', '''
printf 'mount' >> "$CALLS"
for arg do printf ' [%s]' "$arg" >> "$CALLS"; done
printf '\\n' >> "$CALLS"
[ "${FAIL_MOUNT:-0}" = 0 ] || exit 1
if [ "$2" = remount,bind,ro ]; then exit "${FAIL_REMOUNT:-0}"; fi
exit 0''')
        self.command('umount', 'echo "umount $*" >> "$CALLS"')
        self.targets = (self.module / 'bin/gms-paths.txt').read_text().splitlines()

    def command(self, name, text):
        path = self.fakebin / name
        path.write_text('#!/bin/sh\n' + text + '\n')
        path.chmod(0o755)

    def source(self, target, xml):
        path = self.device / target.lstrip('/')
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(xml)
        return path

    def run_script(self, name):
        return subprocess.run(['sh', str(self.module / 'bin' / name), str(self.module), str(self.device)],
                              env=self.env, capture_output=True, text=True)

    def prepare(self):
        result = self.run_script('setup-gms.sh')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        return result

    def overlay(self, target):
        return self.module / 'gms-unlock/overlays' / target.lstrip('/')

    def test_all_supported_paths_preserve_originals_and_other_xml(self):
        xml = '''<?xml version="1.0" encoding="utf-8"?>
<permissions><feature name="cn.google.services"/><feature name="keep.feature"/>
<feature version="1"
 name = 'com.google.android.feature.services_updater'
/><permission name="keep.permission"/><feature name="unrelated.services_updater"/>
</permissions>'''
        originals = {target: self.source(target, xml) for target in self.targets}
        self.prepare()
        self.assertEqual((self.module / 'gms-unlock/mounts.list').read_text().splitlines(), self.targets)
        for target, path in originals.items():
            self.assertEqual(path.read_text(), xml)
            output = ET.fromstring(self.overlay(target).read_bytes())
            self.assertEqual({f.attrib['name'] for f in output.findall('feature')}, {'keep.feature', 'unrelated.services_updater'})
            self.assertEqual(output.find('permission').attrib['name'], 'keep.permission')
        result = self.run_script('gms-unlock.sh')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('mounted=11 failed=0', result.stdout)
        self.assertEqual(self.calls.read_text().count('mount ['), 22)

    def test_oneplus_snapshot_empty_result_and_idempotence(self):
        target = '/my_product/etc/permissions/oplus_google_cn_gms_features.xml'
        source = self.source(target, '<?xml version="1.0"?><permissions><feature name="cn.google.services"/><feature name="com.google.android.feature.services_updater"/></permissions>')
        before = source.read_bytes()
        self.prepare()
        first = self.overlay(target).read_bytes()
        self.assertEqual(len(ET.fromstring(first)), 0)
        self.prepare()
        self.assertEqual(self.overlay(target).read_bytes(), first)
        self.assertEqual(source.read_bytes(), before)
        self.assertEqual(self.run_script('gms-unlock.sh').returncode, 0)
        self.assertIn('remount,bind,ro', self.calls.read_text())

    def test_no_matching_configs_is_successful_noop(self):
        self.assertIn('未找到', self.prepare().stdout)
        result = self.run_script('gms-unlock.sh')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('mounted=0 failed=0', result.stdout)
        self.assertFalse(self.calls.exists())

    def test_clean_configs_still_get_overlay_for_standalone_handoff(self):
        xml = '<permissions><feature name="keep.feature"/></permissions>'
        target = self.targets[0]
        self.source(target, xml)
        self.prepare()
        self.assertEqual(self.overlay(target).read_text(), xml)
        self.assertEqual(self.run_script('gms-unlock.sh').returncode, 0)
        self.assertTrue(self.calls.exists())

    def test_boot_mount_does_not_require_hash_manifest(self):
        self.source(self.targets[0], '<permissions/>')
        self.prepare()
        (self.module / 'gms-unlock/SHA256SUMS').unlink()
        self.assertEqual(self.run_script('gms-unlock.sh').returncode, 0)
        self.assertTrue(self.calls.exists())

    def test_non_allowlisted_target_rejected_even_with_recomputed_hash(self):
        self.prepare()
        work = self.module / 'gms-unlock'
        mounts = work / 'mounts.list'
        mounts.write_text('/system/etc/other.xml\n')
        (work / 'SHA256SUMS').write_text(hashlib.sha256(mounts.read_bytes()).hexdigest() + '  mounts.list\n')
        self.assertNotEqual(self.run_script('gms-unlock.sh').returncode, 0)
        self.assertFalse(self.calls.exists())

    def test_readonly_failure_undoes_new_bind(self):
        self.source(self.targets[0], '<permissions/>')
        self.prepare()
        self.env['FAIL_REMOUNT'] = '1'
        result = self.run_script('gms-unlock.sh')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('umount ', self.calls.read_text())
        self.assertIn('mounted=0 failed=1', result.stdout)

    def test_mount_failure_is_reported(self):
        self.source(self.targets[0], '<permissions/>')
        self.prepare()
        self.env['FAIL_MOUNT'] = '1'
        result = self.run_script('gms-unlock.sh')
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn('remount,bind,ro', self.calls.read_text())
        self.assertIn('mounted=0 failed=1', result.stdout)

    def test_preparation_failure_preserves_previous_overlay(self):
        target = self.targets[0]
        source = self.source(target, '<permissions/>')
        self.prepare()
        before = self.overlay(target).read_bytes()
        source.write_text('<permissions><feature name="keep.feature"/></permissions>')
        self.env['FAIL_CONTEXT'] = '1'
        result = self.run_script('setup-gms.sh')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.overlay(target).read_bytes(), before)
        self.assertEqual(list(self.module.glob('.gms-unlock.*')), [])


if __name__ == '__main__':
    unittest.main()
