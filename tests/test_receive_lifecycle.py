#!/usr/bin/env python3
"""Exercise production receiver/radio ownership with Android command fixtures."""
from pathlib import Path
import json
import os
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class ReceiveLifecycle(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.module = self.base / 'module'
        self.bin = self.module / 'bin'
        self.bin.mkdir(parents=True)
        self.state = self.base / 'state'
        self.state.mkdir()
        self.tools = self.base / 'tools'
        self.tools.mkdir()
        self.params = self.base / 'parameters'
        self.params.mkdir()
        self.log = self.base / 'commands'
        self.log.touch()
        for name, value in {
            'opairshare_bridge_pending': '0',
            'opairshare_channel_state': 'busy=0 fault=0 error=0 accepting=0 vdev=255 freq=0',
            'opairshare_awdl_rx_mac_filter': 'used=0 armed=0 pending=0 active=0 fault=0',
        }.items():
            (self.params / name).write_text(value)
        self.dispatch = self.tools / 'dispatch.py'
        self.dispatch.write_text('''#!/usr/bin/env python3
import json, os, pathlib, sys
b=pathlib.Path(os.environ['RX_TEST_BASE']);s=b/'state';name=pathlib.Path(sys.argv[0]).name;a=sys.argv[1:]
frames=json.loads((b/'frames').read_text());tick=int((b/'tick').read_text())
f=frames[min(tick,len(frames)-1)]
def log(v):
 with (b/'commands').open('a') as out:out.write(v+'\\n')
if name=='timeout':
 if a[0]=='-k':a=a[2:]
 os.execvp(a[1],a[1:])
elif name=='settings':
 if f.get('setting')=='ERROR':sys.exit(1)
 print(f.get('setting','0'))
elif name=='setprop':
 (b/'ready').write_text(a[1]);log('ready:'+a[1])
elif name=='id':print('0')
elif name=='pidof':
 if (b/'provider').exists():print('42')
elif name=='flock':pass
elif name=='sleep':
 log('tick:%d ready:%s provider:%s'%(tick,(b/'ready').read_text() if (b/'ready').exists() else '?',(b/'provider').exists()))
 tick+=1;(b/'tick').write_text(str(tick))
 if tick>=len(frames):(s/'disabled').touch()
 elif frames[tick].get('kill_provider'):(b/'provider').unlink(missing_ok=True)
elif name=='dumpsys':
 if a[:2]==['activity','activities']:
  fg=f.get('foreground','closed')
  if fg=='open':print('topResumedActivity= com.coloros.oshare/.ui.ShareActivity t9}')
  elif fg=='documents':print('topResumedActivity= com.android.documentsui/.PickerActivity t9}\\nActivityRecord com.google.android.gms/.nearby.sharing.ShareActivity t9}')
  elif fg=='closed':print('topResumedActivity= com.android.launcher/.Launcher t2}')
 elif a[:3]==['activity','services','com.coloros.oshare']:
  if (b/'oshare').exists():print('ServiceRecord com.coloros.oshare/com.oplus.oshare.OShareServer')
 elif a[:2]==['activity','service']:
  print('ExternalSharingService state:\\nActive Transfers (0)\\nReceive provider transferring: false')
  print('OPAIRSHARE_RECEIVE busy='+str(f.get('busy',0)))
elif name=='am':
 log('oshare:start');(b/'oshare').touch();print('Starting service: Intent { }')
elif name=='service':print('Parcel(00000000)')
elif name=='app_process':
 op=a[2];path=pathlib.Path(a[3]);log('wifi:'+op)
 if op=='capture':path.write_text('boot')
 else:path.unlink()
elif name=='provider.sh':
 log('provider:'+a[0])
 if a[0]=='ensure':
  if f.get('provider_fail'):sys.exit(5)
  (b/'provider').touch()
 else:(b/'provider').unlink(missing_ok=True)
elif name=='coex-session.sh':
 log('coex:'+a[0]);p=s/'coex-session'
 if a[0]=='prepare':
  if f.get('coex_fail'):sys.exit(8)
  if not (s/'coex-off').exists():p.mkdir();(p/'boot').write_text('boot')
 elif a[0]=='restore' and p.exists():(p/'boot').unlink();p.rmdir()
elif name in ['speed-history.sh','opairshare-socket-buffer']:pass
else:raise RuntimeError((name,a))
''')
        self.dispatch.chmod(0o755)
        for name in ['timeout', 'settings', 'setprop', 'id', 'pidof', 'flock',
                     'sleep', 'dumpsys', 'am', 'service', 'app_process']:
            (self.tools / name).symlink_to(self.dispatch)
        for name in ['provider.sh', 'coex-session.sh', 'speed-history.sh']:
            # ui-lifecycle executes these with sh, so use an executable wrapper.
            wrapper = self.bin / name
            target = self.tools / name
            target.symlink_to(self.dispatch)
            wrapper.write_text(f'#!/bin/sh\nexec "{target}" "$@"\n')
        native = self.module / 'payload/native'
        native.mkdir(parents=True)
        (native / 'opairshare-socket-buffer').symlink_to(self.dispatch)
        (self.bin / 'kernel-identity.sh').write_text('')
        (self.bin / 'common.sh').write_text(f'''STATE='{self.state}'
PKG=com.google.android.mosey
COMP=$PKG/.ExternalSharingService
SERVICE=test.native
boot_id() {{ echo boot; }}
same_boot() {{ [ -r "$1" ] && [ "$(cat "$1")" = boot ]; }}
init_state() {{ :; }}
kernel_matches() {{ return 0; }}
pid_is() {{ return 0; }}
framework() {{ "$@"; }}
''')
        for name in ['ui-lifecycle.sh', 'receive-session.sh', 'transfer-idle.sh']:
            code = (ROOT / 'module/bin' / name).read_text()
            code = code.replace('/system/bin/sh', '/bin/sh')
            code = code.replace('/system/bin/', str(self.tools) + '/')
            code = code.replace('/sys/module/qca_cld3_peach_v2/parameters', str(self.params))
            (self.bin / name).write_text(code)
        for name in ['stack-boot', 'server-boot']:
            (self.state / name).write_text('boot')
        self.env = dict(os.environ, RX_TEST_BASE=str(self.base),
                        PATH=str(self.tools) + os.pathsep + os.environ['PATH'])

    def frames(self, frames):
        (self.base / 'frames').write_text(json.dumps(frames))
        (self.base / 'tick').write_text('0')

    def run_script(self, name, *args):
        return subprocess.run(['sh', str(self.bin / name), *args], env=self.env,
                              capture_output=True, text=True, timeout=10)

    def run_lifecycle(self, frames):
        self.frames(frames)
        result = self.run_script('ui-lifecycle.sh')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        return self.log.read_text()

    def test_everyone_holds_background_coex_without_wifi_restore(self):
        log = self.run_lifecycle([{'setting': '1'}] * 4)
        self.assertIn('coex:prepare', log)
        self.assertIn('provider:ensure', log)
        self.assertIn('tick:0 ready:1 provider:True', log)
        self.assertEqual(log.count('oshare:start'), 1)
        self.assertNotIn('wifi:', log)
        self.assertNotIn('provider:release', log)
        self.assertEqual((self.base / 'ready').read_text(), '0')

    def test_contacts_holds_background_and_switching_modes_preserves_session(self):
        log = self.run_lifecycle([{'setting': '2'}, {'setting': '1'}, {'setting': '2'}] +
                                 [{'setting': '0'}] * 4)
        self.assertIn('tick:0 ready:1 provider:True', log)
        self.assertIn('tick:1 ready:1 provider:True', log)
        self.assertIn('tick:2 ready:1 provider:True', log)
        self.assertEqual(log.count('coex:prepare'), 1)
        self.assertEqual(log.count('oshare:start'), 1)
        self.assertIn('provider:release', log)
        self.assertNotIn('wifi:', log)

    def test_off_errors_and_unknown_never_open_background_receiver(self):
        for setting in ['0', 'null', '3', 'ERROR']:
            with self.subTest(setting=setting):
                (self.state / 'disabled').unlink(missing_ok=True)
                self.log.write_text('')
                log = self.run_lifecycle([{'setting': setting}] * 3)
                self.assertNotIn('provider:ensure', log)
                self.assertNotIn('coex:prepare', log)
                self.assertNotIn('ready:1', log)

    def test_pending_receive_survives_visibility_off_until_idle(self):
        log = self.run_lifecycle([{'setting': '2'}] +
                                 [{'setting': '0', 'busy': 1}] * 4 +
                                 [{'setting': '0'}] * 2)
        lines = log.splitlines()
        release = lines.index('provider:release')
        self.assertGreater(release, lines.index('tick:4 ready:0 provider:True'))
        self.assertIn('coex:restore', log)
        self.assertNotIn('wifi:', log)
        self.assertFalse((self.state / 'ui-session').exists())

    def test_legacy_mode_does_not_open_background_receive(self):
        (self.state / 'coex-off').touch()
        log = self.run_lifecycle([{'setting': '2'}] * 4)
        self.assertNotIn('provider:ensure', log)
        self.assertNotIn('wifi:', log)
        self.assertNotIn('ready:1', log)

    def test_legacy_foreground_and_its_document_picker_remain_owned(self):
        (self.state / 'coex-off').touch()
        log = self.run_lifecycle([{'foreground': 'open'}, {'foreground': 'documents'}] +
                                 [{'foreground': 'closed'}] * 4)
        lines = log.splitlines()
        self.assertIn('wifi:capture', lines)
        self.assertIn('wifi:restore', lines)
        self.assertGreater(lines.index('provider:release'),
                           lines.index('tick:1 ready:0 provider:True'))
        self.assertNotIn('ready:1', lines)

    def test_provider_death_and_failed_recovery_close_admission(self):
        log = self.run_lifecycle([{'setting': '2'},
                                 {'setting': '2', 'kill_provider': True, 'provider_fail': True},
                                 {'setting': '2', 'provider_fail': True}])
        self.assertIn('tick:0 ready:1 provider:True', log)
        self.assertIn('tick:1 ready:0 provider:False', log)

    def test_coex_prepare_failure_never_advertises(self):
        log = self.run_lifecycle([{'setting': '2', 'coex_fail': True}] * 2)
        self.assertNotIn('provider:ensure', log)
        self.assertNotIn('ready:1', log)

    def test_ready_rechecks_native_setting_and_current_boot(self):
        for state in ['0', '1', '2']:
            self.frames([{'setting': state}])
            (self.state / 'ui-session').write_text('boot')
            p = self.state / 'coex-session'
            p.mkdir(exist_ok=True)
            for boot in ['old-boot', 'boot']:
                (p / 'boot').write_text(boot)
                result = self.run_script('receive-session.sh', 'ready')
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(result.stdout.strip(), '1' if state in ['1', '2'] and boot == 'boot' else '0')

    def test_pending_dump_overrides_native_idle(self):
        self.frames([{'busy': 1}])
        result = self.run_script('transfer-idle.sh')
        self.assertEqual(result.returncode, 9)
        self.frames([{'busy': 0}])
        self.assertEqual(self.run_script('transfer-idle.sh').returncode, 0)


if __name__ == '__main__':
    unittest.main(verbosity=2)
