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
            'passthru_awdl_rx_ring_select': '0',
            **{key: 'N' for key in ['passthru_awdl_allow_concurrent', 'passthru_mgmt_home_channel']},
            **{key: '0' for key in ['passthru_awdl_amsdu', 'passthru_awdl_phy', 'passthru_awdl_mcs', 'passthru_awdl_nss', 'passthru_awdl_collect_us']},
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
elif name=='getprop':
 p=b/('prop-'+a[0]);print(p.read_text() if p.exists() else '')
elif name=='setprop':
 if a[0]=='debug.opairshare.receive.ready':
  (b/'ready').write_text(a[1]);log('ready:'+a[1])
 else:(b/('prop-'+a[0])).write_text(a[1]);log('property:'+a[0]+':'+a[1])
elif name=='id':print('0')
elif name=='pidof':
 if (b/'provider').exists():print('42')
elif name=='flock':
 if f.get('settings_locked') and a[-1]=='6':sys.exit(1)
elif name=='sleep':
 log('tick:%d ready:%s provider:%s'%(tick,(b/'ready').read_text() if (b/'ready').exists() else '?',(b/'provider').exists()))
 tick+=1;(b/'tick').write_text(str(tick))
 if tick>=len(frames):(s/'disabled').touch()
 else:
  if frames[tick].get('kill_provider'):(b/'provider').unlink(missing_ok=True)
  if 'coex_off' in frames[tick]:
   if frames[tick]['coex_off']:(s/'coex-off').touch()
   else:(s/'coex-off').unlink(missing_ok=True)
elif name=='dumpsys':
 if a[:2]==['activity','activities']:
  fg=f.get('foreground','closed')
  if fg=='open':print('topResumedActivity= com.coloros.oshare/.ui.ShareActivity t9}')
  elif fg=='documents':print('topResumedActivity= com.android.documentsui/.PickerActivity t9}\\nActivityRecord com.google.android.gms/.nearby.sharing.ShareActivity t9}')
  elif fg=='closed':print('topResumedActivity= com.android.launcher/.Launcher t2}')
 elif a[:3]==['activity','services','com.coloros.oshare']:
  if (b/'oshare').exists():print('ServiceRecord com.coloros.oshare/com.oplus.oshare.OShareServer')
  if (b/'oshare-delegate').exists():print('ServiceRecord com.coloros.oshare isFgsDelegate=true')
 elif a[:2]==['activity','service']:
  if f.get('transfer_unknown'):print('Unrecognized provider dump');sys.exit(0)
  print('ExternalSharingService state:\\nActive Transfers (0)\\nReceive provider transferring: false')
  print('OPAIRSHARE_RECEIVE busy='+str(f.get('busy',0)))
elif name=='am':
 log('oshare:start');(b/'oshare').touch();print('Starting service: Intent { }')
elif name=='cmd':
 if a==['wifi','status']:
  if f.get('wifi_error'):sys.exit(1)
  if f.get('wifi_malformed'):print('unavailable');sys.exit(0)
  if not f.get('wifi_enabled',True):print('Wifi is disabled');sys.exit(0)
  print('Wifi is enabled')
  if f.get('connected',True):
   print('Wifi is connected to test-network')
   print('Supplicant state: %s, Frequency: %sMHz, Net ID: %s,'%(f.get('supplicant','COMPLETED'),f.get('frequency',5745),f.get('network',7)))
  sys.exit(0)
 action=a[-1];log('oshare:delegate:'+action)
 if f.get('delegate_fail'):sys.exit(1)
 if action=='start':(b/'oshare-delegate').touch()
 else:(b/'oshare-delegate').unlink(missing_ok=True)
elif name=='service':
 log('native:stop')
 (b/'parameters/opairshare_channel_state').write_text('busy=0 fault=0 error=0 accepting=0 vdev=255 freq=0')
 (b/'parameters/opairshare_awdl_rx_mac_filter').write_text('used=0 armed=0 pending=0 active=0 fault=0')
 print('Parcel(00000000)')
elif name=='app_process':
 op=a[2];path=pathlib.Path(a[3]);log('wifi:'+op)
 if op=='capture':path.write_text('boot')
 else:path.unlink()
elif name=='provider.sh':
 log('provider:'+a[0])
 if a[0]=='ensure':
  if f.get('provider_fail'):sys.exit(5)
  (b/'provider').touch()
  (b/'parameters/opairshare_channel_state').write_text('busy=0 fault=0 error=0 accepting=1 vdev=3 freq=5745')
  (b/'parameters/opairshare_awdl_rx_mac_filter').write_text('used=1 armed=1 pending=0 active=1 fault=0 freq=5745 ')
 else:(b/'provider').unlink(missing_ok=True)
elif name in ['speed-history.sh','opairshare-socket-buffer']:pass
else:raise RuntimeError((name,a))
''')
        self.dispatch.chmod(0o755)
        for name in ['timeout', 'settings', 'setprop', 'getprop', 'id', 'pidof', 'flock',
                     'sleep', 'dumpsys', 'am', 'cmd', 'service', 'app_process']:
            (self.tools / name).symlink_to(self.dispatch)
        for name in ['provider.sh', 'speed-history.sh']:
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
startup_trace() {{ :; }}
kernel_matches() {{ return 0; }}
pid_is() {{ return 0; }}
framework() {{ "$@"; }}
''')
        for name in ['ui-lifecycle.sh', 'receive-session.sh', 'transfer-idle.sh', 'coex-session.sh', 'coex-wifi.sh']:
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

    def test_boot_with_native_receive_off_starts_background_without_activity(self):
        log = self.run_lifecycle([{'setting': '0'}] * 4)
        self.assertIn('property:debug.opairshare.discovery:same149', log)
        self.assertIn('provider:ensure', log)
        self.assertIn('tick:0 ready:1 provider:True', log)
        self.assertEqual(log.count('oshare:start'), 1)
        self.assertEqual(log.count('oshare:delegate:start'), 1)
        self.assertNotIn('wifi:', log)
        self.assertNotIn('provider:release', log)
        self.assertEqual((self.base / 'ready').read_text(), '0')

    def test_switching_native_modes_preserves_background_session(self):
        log = self.run_lifecycle([{'setting': '2'}, {'setting': '1'}, {'setting': '2'}] +
                                 [{'setting': '0'}] * 4)
        self.assertIn('tick:0 ready:1 provider:True', log)
        self.assertIn('tick:1 ready:1 provider:True', log)
        self.assertIn('tick:2 ready:1 provider:True', log)
        self.assertEqual(log.count('property:debug.opairshare.discovery:same149'), 1)
        self.assertEqual(log.count('oshare:start'), 1)
        self.assertNotIn('provider:release', log)
        self.assertNotIn('wifi:', log)

    def test_native_settings_errors_do_not_gate_module_owned_reception(self):
        log = self.run_lifecycle([{'setting': setting} for setting in ['0', 'null', '3', 'ERROR']])
        self.assertEqual(log.count('property:debug.opairshare.discovery:same149'), 1)
        for tick in range(4):
            self.assertIn(f'tick:{tick} ready:1 provider:True', log)

    def test_pending_receive_survives_coex_off_until_idle(self):
        log = self.run_lifecycle([{'setting': '2'}] +
                                 [{'coex_off': True, 'busy': 1}] * 4 +
                                 [{'coex_off': True}] * 2)
        lines = log.splitlines()
        release = lines.index('provider:release')
        self.assertGreater(release, lines.index('tick:4 ready:0 provider:True'))
        self.assertIn('property:debug.opairshare.discovery:', log)
        self.assertNotIn('wifi:', log)
        self.assertFalse((self.state / 'ui-session').exists())
        self.assertGreater(lines.index('oshare:delegate:stop'), lines.index('tick:4 ready:0 provider:True'))

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

    def test_unsupported_startup_never_advertises(self):
        log = self.run_lifecycle([{'setting': '2', 'frequency': 5200}] * 2)
        self.assertNotIn('provider:ensure', log)
        self.assertNotIn('ready:1', log)

    def test_channel40_suspends_then_channel149_restores_background(self):
        log = self.run_lifecycle([{'frequency': 5200}, {}, {}])
        self.assertIn('tick:0 ready:0 provider:False', log)
        self.assertIn('tick:1 ready:1 provider:True', log)
        self.assertFalse((self.state / 'coex-off').exists())
        self.assertFalse((self.state / 'coex-wifi-blocked').exists())
        self.assertNotIn('wifi:', log)

    def test_channel_change_closes_gate_and_waits_for_busy_transfer(self):
        log = self.run_lifecycle([{}] + [{'frequency': 5200, 'busy': 1}] * 2 +
                                 [{'frequency': 5200}, {}, {}])
        lines = log.splitlines()
        self.assertIn('tick:1 ready:0 provider:True', lines)
        self.assertIn('tick:2 ready:0 provider:True', lines)
        self.assertGreater(lines.index('provider:release'), lines.index('tick:2 ready:0 provider:True'))
        self.assertIn('tick:3 ready:0 provider:False', lines)
        self.assertIn('tick:4 ready:1 provider:True', lines)
        self.assertEqual(log.count('property:debug.opairshare.discovery:same149'), 2)
        self.assertNotIn('wifi:', log)

    def test_disconnect_reconnect_during_transfer_rebuilds_after_idle(self):
        log = self.run_lifecycle([{}, {'connected': False, 'busy': 1},
                                 {'connected': False, 'transfer_unknown': True},
                                 {'busy': 1}, {}, {}, {}])
        lines = log.splitlines()
        for tick in [1, 2, 3]:
            self.assertIn(f'tick:{tick} ready:0 provider:True', lines)
        self.assertGreater(lines.index('provider:release'), lines.index('tick:3 ready:0 provider:True'))
        self.assertIn('tick:5 ready:1 provider:True', lines)
        self.assertEqual(log.count('provider:release'), 1)
        self.assertNotIn('wifi:', log)

    def test_same149_network_change_rebuilds_without_wifi_reconnection(self):
        log = self.run_lifecycle([{}, {'network': 8, 'busy': 1},
                                 {'network': 8}, {'network': 8}, {'network': 8}])
        lines = log.splitlines()
        self.assertIn('tick:1 ready:0 provider:True', lines)
        self.assertIn('tick:2 ready:0 provider:False', lines)
        self.assertIn('tick:3 ready:1 provider:True', lines)
        self.assertEqual((self.state / 'coex-session/network').read_text().strip(), '8')
        self.assertNotIn('wifi:', log)

    def test_open_ui_downgrades_to_legacy_and_upgrades_after_channel_returns(self):
        log = self.run_lifecycle([{'foreground': 'open'},
                                 {'foreground': 'open', 'frequency': 5200},
                                 {'foreground': 'open', 'frequency': 5200},
                                 {'foreground': 'open'}, {'foreground': 'open'}, {}])
        lines = log.splitlines()
        self.assertIn('tick:1 ready:0 provider:False', lines)
        self.assertIn('tick:2 ready:0 provider:True', lines)
        self.assertIn('tick:3 ready:0 provider:False', lines)
        self.assertIn('tick:4 ready:1 provider:True', lines)
        self.assertEqual(log.count('wifi:capture'), 1)
        self.assertEqual(log.count('wifi:restore'), 1)
        self.assertFalse((self.state / 'session-wifi').exists())

    def test_manual_off_during_suspension_prevents_auto_recovery(self):
        log = self.run_lifecycle([{}, {'frequency': 5200}, {'coex_off': True}, {}, {}])
        self.assertEqual(log.count('provider:ensure'), 1)
        self.assertIn('tick:4 ready:0 provider:False', log)
        self.assertTrue((self.state / 'coex-off').exists())

    def test_unknown_wifi_never_starts_legacy_fallback_and_recovers(self):
        log = self.run_lifecycle([{}, {'wifi_error': True, 'foreground': 'open'},
                                 {'wifi_malformed': True, 'foreground': 'open'}, {}, {}])
        self.assertIn('tick:1 ready:0 provider:False', log)
        self.assertIn('tick:2 ready:0 provider:False', log)
        self.assertIn('tick:3 ready:1 provider:True', log)
        self.assertNotIn('wifi:', log)

    def test_background_disable_can_drain_while_settings_holds_lock(self):
        log = self.run_lifecycle([{}, {'coex_off': True, 'settings_locked': True}, {}])
        self.assertIn('tick:1 ready:0 provider:False', log)
        self.assertEqual(log.count('provider:release'), 1)
        self.assertFalse((self.state / 'ui-session').exists())

    def test_ready_rechecks_live_connection_and_network(self):
        (self.state / 'ui-session').write_text('boot')
        p = self.state / 'coex-session'
        p.mkdir()
        (p / 'boot').write_text('boot')
        (p / 'network').write_text('7')
        for frame in [{'frequency': 5200}, {'connected': False}, {'network': 8},
                      {'wifi_error': True}, {'supplicant': 'ASSOCIATING'}, {}]:
            with self.subTest(frame=frame):
                self.frames([frame])
                result = self.run_script('receive-session.sh', 'ready')
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(result.stdout.strip(), '0' if frame else '1')

    def test_ready_requires_current_boot_independently_of_native_setting(self):
        for state in ['0', '1', '2']:
            self.frames([{'setting': state}])
            (self.state / 'ui-session').write_text('boot')
            p = self.state / 'coex-session'
            p.mkdir(exist_ok=True)
            (p / 'network').write_text('7')
            (p / 'frequency').write_text('5745')
            for boot in ['old-boot', 'boot']:
                (p / 'boot').write_text(boot)
                result = self.run_script('receive-session.sh', 'ready')
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(result.stdout.strip(), '1' if boot == 'boot' else '0')

    def test_disabled_removed_or_unowned_module_never_requests_receive(self):
        self.frames([{'setting': '2'}])
        for path in [self.state/'disabled', self.module/'disable', self.module/'remove', self.state/'coex-off']:
            path.touch()
            self.assertEqual(self.run_script('receive-session.sh', 'wanted').stdout.strip(), '0')
            path.unlink()
        (self.state/'stack-boot').write_text('old-boot')
        self.assertEqual(self.run_script('receive-session.sh', 'wanted').stdout.strip(), '0')

    def test_host_delegate_failure_keeps_receive_admission_closed(self):
        log = self.run_lifecycle([{'delegate_fail': True}] * 3)
        self.assertIn('oshare:delegate:start', log)
        self.assertNotIn('ready:1', log)

    def test_clearing_admission_retains_busy_host_and_release_checks_idle(self):
        self.frames([{'busy': 1}])
        (self.state/'receive-delegate-owned').write_text('boot')
        (self.base/'oshare-delegate').touch()
        self.assertEqual(self.run_script('receive-session.sh', 'clear').returncode, 0)
        self.assertTrue((self.base/'oshare-delegate').exists())
        self.assertEqual(self.run_script('receive-session.sh', 'release-host').returncode, 9)
        self.frames([{'busy': 0}])
        self.assertEqual(self.run_script('receive-session.sh', 'release-host').returncode, 0)
        self.assertFalse((self.base/'oshare-delegate').exists())

    def test_pending_dump_overrides_native_idle(self):
        self.frames([{'busy': 1}])
        result = self.run_script('transfer-idle.sh')
        self.assertEqual(result.returncode, 9)
        self.frames([{'busy': 0}])
        self.assertEqual(self.run_script('transfer-idle.sh').returncode, 0)


if __name__ == '__main__':
    unittest.main(verbosity=2)
