#!/usr/bin/env python3
"""Exercise power ownership, restoration, and uncertain transfer boundaries."""
from pathlib import Path
import os
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class Performance(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.state = self.base / 'state'
        session = self.state / 'coex-session'
        session.mkdir(parents=True)
        (session / 'boot').write_text('boot\n')
        (session / 'class').write_text('cross5g\n')
        self.iface = self.base / 'wlan0'
        self.iface.mkdir()
        (self.iface / 'ifindex').write_text('32\n')
        self.power = self.base / 'power'
        self.power.write_text('on')
        (self.base / 'bssid').write_text('44:f7:70:f7:fe:8b')
        (self.base / 'frequency').write_text('5805')
        tools = self.base / 'tools'
        tools.mkdir()
        timeout = tools / 'timeout'
        timeout.write_text('#!/bin/sh\nshift; exec "$@"\n')
        timeout.chmod(0o755)
        iw = tools / 'iw'
        iw.write_text('''#!/usr/bin/env python3
import os, pathlib, sys
b=pathlib.Path(os.environ['PERF_TEST']);a=sys.argv[1:]
if (b/'iw-failed').exists():sys.exit(1)
if a==['dev','wlan0','link']:
 print('Connected to '+(b/'bssid').read_text()+' (on wlan0)')
 print('  freq: '+(b/'frequency').read_text())
elif a==['dev','wlan0','get','power_save']:print('Power save: '+(b/'power').read_text())
elif a[:4]==['dev','wlan0','set','power_save']:
 if (b/'set-failed').exists():sys.exit(1)
 (b/'power').write_text(a[4])
 with (b/'writes').open('a') as out:out.write(a[4]+'\\n')
else:sys.exit(2)
''')
        iw.chmod(0o755)
        code = (ROOT / 'tools/coex-transfer-performance.sh').read_text()
        code = code.replace('/system/bin/', str(tools) + '/')
        code = code.replace('/sys/class/net/wlan0', str(self.iface))
        self.script = self.base / 'performance.sh'
        self.script.write_text(code)
        self.env = dict(os.environ, PERF_TEST=str(self.base))

    def run_steps(self, steps):
        code = f'''STATE='{self.state}'
boot_id() {{ echo boot; }}
same_boot() {{ [ -r "$1" ] && [ "$(cat "$1")" = boot ]; }}
. '{self.script}'
{steps}
'''
        run = subprocess.run(['sh', '-eu', '-c', code], env=self.env,
                             capture_output=True, text=True)
        self.assertEqual(run.returncode, 0, run.stdout + run.stderr)
        return run.stdout

    def test_active_then_idle_restores_once(self):
        self.run_steps('t=1000; performance_tick busy; t=2000; performance_tick busy')
        self.assertEqual(self.power.read_text(), 'off')
        self.assertEqual((self.base / 'writes').read_text(), 'off\n')
        self.run_steps('t=3000; performance_tick idle; performance_tick idle')
        self.assertEqual(self.power.read_text(), 'on')
        self.assertEqual((self.base / 'writes').read_text(), 'off\non\n')
        self.assertFalse((self.state / 'transfer-performance').exists())

    def test_pending_same149_and_other_bands_do_not_change_power(self):
        self.run_steps('t=1000; performance_tick pending')
        for band in ['same149', 'band24', 'band6']:
            (self.state / 'coex-session/class').write_text(band)
            self.run_steps('t=1000; performance_tick busy')
        self.assertEqual(self.power.read_text(), 'on')
        self.assertFalse((self.base / 'writes').exists())

    def test_preexisting_off_is_not_owned_or_restored(self):
        self.power.write_text('off')
        self.run_steps('t=1000; performance_tick busy; performance_tick idle')
        self.assertEqual(self.power.read_text(), 'off')
        self.assertFalse((self.base / 'writes').exists())

    def test_unknown_state_has_bounded_lease(self):
        self.run_steps('t=1000; performance_tick busy; t=15000; performance_tick unknown')
        self.assertEqual(self.power.read_text(), 'off')
        self.run_steps('t=17000; performance_tick unknown')
        self.assertEqual(self.power.read_text(), 'on')

    def test_new_association_is_not_overwritten(self):
        self.run_steps('t=1000; performance_tick busy')
        (self.base / 'bssid').write_text('00:11:22:33:44:55')
        self.run_steps('performance_restore')
        self.assertEqual(self.power.read_text(), 'off')
        self.assertEqual((self.base / 'writes').read_text(), 'off\n')
        self.assertFalse((self.state / 'transfer-performance').exists())

    def test_unreadable_state_keeps_snapshot_for_retry(self):
        self.run_steps('t=1000; performance_tick busy')
        (self.base / 'iw-failed').touch()
        self.run_steps('performance_restore')
        self.assertTrue((self.state / 'transfer-performance').exists())
        (self.base / 'iw-failed').unlink()
        self.run_steps('performance_restore')
        self.assertEqual(self.power.read_text(), 'on')

    def test_failed_set_does_not_disable_recorder(self):
        (self.base / 'set-failed').touch()
        self.run_steps('t=1000; performance_tick busy')
        self.assertEqual(self.power.read_text(), 'on')
        self.assertFalse((self.state / 'transfer-performance').exists())

    def test_old_boot_snapshot_does_not_restore_new_boot(self):
        self.run_steps('t=1000; performance_tick busy')
        (self.state / 'transfer-performance/boot').write_text('old-boot')
        self.run_steps('performance_restore')
        self.assertEqual(self.power.read_text(), 'off')
        self.assertFalse((self.state / 'transfer-performance').exists())


if __name__ == '__main__':
    unittest.main()
