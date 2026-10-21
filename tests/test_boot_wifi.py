#!/usr/bin/env python3
"""Exercise saved Wi-Fi intent and asynchronous framework toggle completion."""
from pathlib import Path
import json
import os
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class BootWifi(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.tools = self.base / 'tools'
        self.tools.mkdir()
        dispatch = self.tools / 'dispatch.py'
        dispatch.write_text('''#!/usr/bin/env python3
import json, os, pathlib, sys
base=pathlib.Path(os.environ['WIFI_TEST_BASE']);name=pathlib.Path(sys.argv[0]).name;a=sys.argv[1:]
if name=='timeout':os.execv(a[1],a[1:])
state=json.loads((base/'fixture').read_text())
if name=='cmd':
 values=state['runtime'];value=values.pop(0) if len(values)>1 else values[0]
 (base/'fixture').write_text(json.dumps(state))
 print({'0':'Wifi is disabled','1':'Wifi is enabled'}.get(value,'unavailable'))
elif name=='settings':
 if state.get('settings_fail'):sys.exit(1)
 print(state['saved'])
elif name=='svc':
 with (base/'requests').open('a') as out:out.write(' '.join(a)+'\\n')
 sys.exit(state.get('request_rc',0))
elif name=='sleep':pass
else:raise RuntimeError((name,a))
''')
        dispatch.chmod(0o755)
        for name in ['timeout', 'cmd', 'settings', 'svc', 'sleep']:
            (self.tools / name).symlink_to(dispatch)
        code = (ROOT / 'module/bin/common.sh').read_text()
        self.common = self.base / 'common.sh'
        (self.base/'uptime').write_text('42.125 10.00\n')
        code = code.replace('STATE=/data/adb/opairshare', "STATE='" + str(self.base) + "'")
        code = code.replace('/proc/uptime', str(self.base/'uptime'))
        self.common.write_text(code.replace('/system/bin/', str(self.tools) + '/'))
        self.env = dict(os.environ, WIFI_TEST_BASE=str(self.base),
                        PATH=str(self.tools) + os.pathsep + os.environ['PATH'])

    def run_wifi(self, operation, *args, runtime=('0',), saved='1', **flags):
        (self.base / 'fixture').write_text(json.dumps(dict(runtime=list(runtime), saved=saved, **flags)))
        return subprocess.run(['sh', '-c', '. "$1"; shift; "$@"', 'test',
                               str(self.common), operation, *args], env=self.env,
                              capture_output=True, text=True, timeout=10)

    def test_boot_transition_preserves_enabled_preference(self):
        result = self.run_wifi('wifi_startup_state')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), '1')
        self.assertIn('runtime=0 persisted=1 desired=1', result.stderr)

    def test_saved_off_and_airplane_values_are_respected(self):
        for saved, expected in [('0', '0'), ('1', '1'), ('2', '1'), ('3', '0')]:
            with self.subTest(saved=saved):
                result = self.run_wifi('wifi_startup_state', runtime=('1',), saved=saved)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(result.stdout.strip(), expected)

    def test_unavailable_setting_falls_back_to_runtime(self):
        for flags in [dict(saved='null'), dict(settings_fail=True)]:
            result = self.run_wifi('wifi_startup_state', runtime=('1',), **flags)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stdout.strip(), '1')

    def test_unknown_runtime_aborts_before_toggling(self):
        result = self.run_wifi('wifi_startup_state', runtime=('unknown',))
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((self.base / 'requests').exists())

    def test_restore_waits_for_async_enable_and_sends_one_request(self):
        result = self.run_wifi('wifi_restore', '1', runtime=('0', 'unknown', '0', '1'))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('WIFI_RESTORE_CONFIRMED desired=1', result.stdout)
        self.assertEqual((self.base / 'requests').read_text(), 'wifi enable\n')
        trace=(self.base/'startup-trace.log').read_text().splitlines()
        self.assertEqual(trace, ['STARTUP stage=WIFI_RESTORE_REQUEST elapsed_ms=42125',
                                 'STARTUP stage=WIFI_RESTORE_CONFIRMED elapsed_ms=42125'])

    def test_restore_off_waits_for_disabled(self):
        result = self.run_wifi('wifi_restore', '0', runtime=('1', '0'))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('WIFI_RESTORE_CONFIRMED desired=0', result.stdout)
        self.assertEqual((self.base / 'requests').read_text(), 'wifi disable\n')

    def test_successful_command_without_radio_recovery_is_failure(self):
        result = self.run_wifi('wifi_restore', '1')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('WIFI_STATE_TIMEOUT desired=1 actual=0', result.stderr)
        self.assertNotIn('WIFI_RESTORE_CONFIRMED', result.stdout)
        self.assertEqual((self.base / 'requests').read_text(), 'wifi enable\n')

    def test_rejected_request_and_invalid_value_fail(self):
        result = self.run_wifi('wifi_restore', '1', request_rc=1)
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn('WIFI_RESTORE_CONFIRMED', result.stdout)
        (self.base / 'requests').unlink()
        result = self.run_wifi('wifi_restore', 'unknown')
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((self.base / 'requests').exists())


if __name__ == '__main__':
    unittest.main(verbosity=2)
