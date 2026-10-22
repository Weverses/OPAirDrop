#!/usr/bin/env python3
"""Exercise Oplus/sun/6.6.118 admission at the install and start entrypoints."""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
KERNEL = '6.6.118-android15-8-g9bc34d5b0c79-abogki537459655-4k'


class CompatibilityTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.module = self.base / 'module'
        self.bin = self.module / 'bin'
        self.bin.mkdir(parents=True)
        self.vendor = self.base / 'vendor'
        self.vendor.mkdir()
        self.tools = self.base / 'tools'
        self.tools.mkdir()
        self.props = self.base / 'props'
        self.props.mkdir()
        self.state = self.base / 'state'
        self.state.mkdir()
        self.modules = self.base / 'modules'
        self.modules.write_text('qca_cld3_peach_v2 12365824 1 - Live 0x0\n'
                                'mac80211 1093632 0 - Live 0x0\ncnss2 42 1 - Live 0x0\n')
        self.marker = self.base / 'mutated'
        for key, value in {
            'ro.product.model': 'ANOTHER_OPLUS_MODEL',
            'ro.product.manufacturer': 'OnePlus', 'ro.product.brand': 'OnePlus',
            'ro.soc.model': 'SM8750', 'ro.board.platform': 'sun',
            'ro.build.version.sdk': '37', 'kernel': KERNEL, 'uid': '0',
        }.items():
            self.prop(key, value)
        for name, body in {
            'getprop': f'cat "{self.props}/$1" 2>/dev/null || true',
            'uname': f'cat "{self.props}/kernel"',
            'id': f'cat "{self.props}/uid"',
            'flock': 'exit 0',
        }.items():
            path = self.tools / name
            path.write_text('#!/bin/sh\n' + body + '\n')
            path.chmod(0o755)
        self.env = dict(os.environ, PATH=str(self.tools) + os.pathsep + os.environ['PATH'])
        shutil.copy2(ROOT / 'module/bin/kernel-identity.sh', self.bin)
        self.live = self.base / 'live'
        self.live.mkdir()
        for name in ('qca_cld3_peach_v2', 'mac80211'):
            (self.live / name).write_text('different-stock-build')
        shutil.copy2(ROOT / 'module/bin/compatibility.sh', self.bin)
        (self.bin / 'common.sh').write_text(f'''STATE='{self.state}'
init_state() {{ :; }}
loaded() {{ grep -q "^$1 " '{self.modules}'; }}
module_note() {{ cat '{self.live}/'$1 2>/dev/null; }}
''')
        (self.bin / 'transfer-power.sh').write_text('')
        (self.bin / 'transfer-idle.sh').write_text(f'touch "{self.marker}"; exit 88\n')
        control = (ROOT / 'module/bin/control.sh').read_text().replace('/system/bin/sh', '/bin/sh')
        (self.bin / 'control.sh').write_text(control)
        # Run the production installer up to the first configuration mutation.
        installer = (ROOT / 'module/customize.sh').read_text().split('# Replacement must not merge', 1)[0]
        installer = installer.replace('[ -e /data/adb/metamodule ]', 'true')
        installer += f'touch "{self.marker}"\n'
        (self.module / 'customize.sh').write_text(installer)
        (self.bin / 'verify.sh').write_text('exit 0\n')
        (self.module / 'module.prop').write_text('version=test\n')

    def prop(self, key, value):
        (self.props / key).write_text(value + '\n')

    def run_shell(self, code, *args):
        return subprocess.run(['sh', '-eu', '-c', code, 'compat-test', *args],
                              env=self.env, capture_output=True, text=True, timeout=10)

    def check(self, function='compatibility_device'):
        return self.run_shell('. "$1/bin/common.sh"; . "$1/bin/kernel-identity.sh"; '
                              '. "$1/bin/compatibility.sh"; ' + function, str(self.module))

    def install(self):
        return self.run_shell('MODPATH=$1; KSU=true; ui_print() { echo "$*"; }; '
                              'abort() { echo "$*" >&2; exit 2; }; . "$1/customize.sh"', str(self.module))

    def start(self):
        return subprocess.run(['sh', str(self.bin / 'control.sh'), 'start'], env=self.env,
                              capture_output=True, text=True, timeout=10)

    def assert_rejected(self, result, reason):
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn(reason, result.stderr)

    def test_other_model_with_matching_baseline_passes_both_entrypoints(self):
        result = self.install()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('ANOTHER_OPLUS_MODEL', result.stdout)
        self.assertTrue(self.marker.exists())
        self.marker.unlink()
        result = self.start()
        self.assertEqual(result.returncode, 88, result.stderr)
        self.assertTrue(self.marker.exists())

    def test_all_oplus_brands_and_vendor_property_fallback(self):
        for brand in ('OPPO', 'OnePlus', 'realme', 'Oplus'):
            with self.subTest(brand=brand):
                self.prop('ro.product.manufacturer', '')
                self.prop('ro.product.brand', '')
                self.prop('ro.product.vendor.brand', brand)
                result = self.check()
                self.assertEqual(result.returncode, 0, result.stderr)

    def test_platform_and_kernel_mismatches_reject_before_mutation(self):
        cases = [('ro.board.platform', 'other', 'sun platform'),
                 ('ro.board.platform', '', 'sun platform'),
                 ('kernel', '6.6.117-android15', '6.6.118'),
                 ('kernel', '6.6.119', '6.6.118'),
                 ('kernel', '6.6.1180', '6.6.118'),
                 ('kernel', '6.6.118.1', '6.6.118'),
                 ('kernel', '', '6.6.118')]
        for prop, value, reason in cases:
            original = (self.props / prop).read_text()
            with self.subTest(prop=prop, value=value):
                self.prop(prop, value)
                for entry in (self.install, self.start):
                    self.assert_rejected(entry(), reason)
                    self.assertFalse(self.marker.exists())
                (self.props / prop).write_text(original)

    def test_non_oplus_rejected(self):
        self.prop('ro.product.manufacturer', 'Xiaomi')
        self.prop('ro.product.brand', 'Xiaomi')
        self.assert_rejected(self.install(), 'Oplus device required')
        self.assert_rejected(self.start(), 'Oplus device required')
        self.assertFalse(self.marker.exists())

    def test_base_version_and_different_kernel_suffixes_pass(self):
        for kernel in ('6.6.118', '6.6.118-android15-custom', '6.6.118-other-build-16k', '6.6.118+'):
            with self.subTest(kernel=kernel):
                self.prop('kernel', kernel)
                result = self.install()
                self.assertEqual(result.returncode, 0, result.stderr)
                result = self.start()
                self.assertEqual(result.returncode, 88, result.stderr)

    def test_model_soc_sdk_and_stock_files_do_not_restrict_admission(self):
        for sdk, soc in [('36', 'SM8750-AB'), ('35', 'other'), ('', '')]:
            with self.subTest(sdk=sdk, soc=soc):
                self.prop('ro.product.model', 'UNLISTED_MODEL')
                self.prop('ro.build.version.sdk', sdk)
                self.prop('ro.soc.model', soc)
                # No original module files or recorded stock build identities.
                (self.bin / 'kernel-identity.sh').write_text('')
                result = self.install()
                self.assertEqual(result.returncode, 0, result.stderr)
                result = self.start()
                self.assertEqual(result.returncode, 88, result.stderr)

    def test_unknown_original_build_is_allowed_during_restore(self):
        code = (self.bin / 'control.sh').read_text().split('case "${1:-status}"', 1)[0]
        code = code.replace('MODDIR=$(CDPATH= cd -- "${0%/*}/.." && pwd)', 'MODDIR=' + str(self.module))
        (self.bin / 'functions.sh').write_text(code)
        result = self.run_shell('. "$1/bin/functions.sh"; restore_state_allowed', str(self.module))
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_restore_accepts_reloaded_originals_with_different_build_ids(self):
        code = (self.bin / 'control.sh').read_text().split('case "${1:-status}"', 1)[0]
        code = code.replace('MODDIR=$(CDPATH= cd -- "${0%/*}/.." && pwd)', 'MODDIR=' + str(self.module))
        (self.bin / 'functions.sh').write_text(code)
        (self.bin / 'provider.sh').write_text('exit 0\n')
        result = self.run_shell('''. "$1/bin/functions.sh"
NOTE_qca_cld3_peach_v2=our-wlan
NOTE_mac80211=our-mac
stop_native() { :; }
restore_session_wifi() { :; }
framework() { :; }
wifi_wait() { :; }
sleep() { :; }
pause_wifi_hal() { :; }
resume_wifi_hal() { :; }
wifi_restore() { :; }
same_boot() { case "$1" in */qca-swapped|*/mac-swapped) return 0;; *) return 1;; esac; }
# Restoring previously reloaded originals must not compare a PJZ110 build ID.
restore_owned
''', str(self.module))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('STOCK_STACK_RESTORED', result.stdout)


if __name__ == '__main__':
    unittest.main(verbosity=2)
