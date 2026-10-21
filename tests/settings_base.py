#!/usr/bin/env python3
"""Run the production settings shell against a temporary Android-like fixture."""
from pathlib import Path
import json, os, subprocess, tempfile, unittest

HERE=Path(__file__).resolve().parent
ROOT=HERE.parent

class Settings(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.base=Path(self.tmp.name);self.state=self.base/'state';self.state.mkdir()
        self.params=self.base/'parameters';self.params.mkdir()
        self.module=self.base/'module';self.bin=self.module/'bin';self.bin.mkdir(parents=True)
        self.tools=self.base/'tools';self.tools.mkdir()
        self.common=f'''STATE='{self.state}'
PKG=com.google.android.mosey
init_state() {{ :; }}
same_boot() {{ return 1; }}
pid_is() {{ return 1; }}
framework() {{ cat '{self.base}/wifi'; }}
'''
        (self.bin/'common.sh').write_text(self.common)
        (self.module/'module.prop').write_text('version=test-r25\n')
        (self.base/'wifi').write_text('Wifi is connected to "V\\quote\\\""\nFrequency: 5745MHz\n')
        (self.bin/'transfer-idle.sh').write_text('#!/bin/sh\nexit "${TEST_TRANSFER_BUSY:-0}"\n')
        for name,code in [('id','echo 0'),('flock','exit "${TEST_LOCK_BUSY:-0}"')]:
            p=self.tools/name;p.write_text('#!/bin/sh\n'+code+'\n');p.chmod(0o755)
        self.env=dict(os.environ,PATH=str(self.tools)+os.pathsep+os.environ['PATH'])
        for name in ['airdrop-name.sh']:
            (self.bin/name).write_text((ROOT/'module/bin'/name).read_text().replace('/system/bin/',str(self.tools)+'/'))
        for name,code in [
            ('getprop','case "$1" in ro.vendor.oplus.market.enname) echo "${TEST_MARKET_NAME-OnePlus 13}";;ro.product.model) echo PJZ110;;esac'),
            ('xxd','exec /usr/bin/xxd "$@"'),
            ('settings', 'case "$1" in get) cat "$NAME_STORE" 2>/dev/null || echo null;;put) [ "${TEST_NAME_WRITE_FAIL:-0}" = 0 ] || exit 1; printf "%s" "$4" > "$NAME_STORE";;delete) [ "${TEST_NAME_WRITE_FAIL:-0}" = 0 ] || exit 1; rm -f "$NAME_STORE";;esac')]:
            tool=self.tools/name;tool.write_text('#!/bin/sh\n'+code+'\n');tool.chmod(0o755)
        self.env['NAME_STORE']=str(self.base/'name-store')
        self.script=self.bin/'settings.sh'
        self.script.write_text((ROOT/'module/bin/settings.sh').read_text().replace('/sys/module/qca_cld3_peach_v2/parameters',str(self.params)).replace('/system/bin/sh','sh').replace('/system/bin/settings',str(self.tools/'settings')))
        self.turbo=(ROOT/'module/bin/turbo.sh').read_text().replace('/sys/module/qca_cld3_peach_v2/parameters',str(self.params))
        (self.bin/'turbo.sh').write_text(self.turbo)
        self.original={'opairshare_channel_state':'cookie=1 busy=0 fault=0 error=0 accepting=0 vdev=255 freq=0',
            'opairshare_awdl_rx_mac_filter':'used=0 armed=0 pending=0 active=0 fault=0', 'opairshare_bridge_pending':'0',
            'opairshare_agg_stats':'stats','opairshare_tx_pack_stats':'stats',
            'passthru_awdl_amsdu':'2','passthru_awdl_amsdu_max':'5','passthru_awdl_amsdu_bytes':'7935',
            'passthru_awdl_phy':'3','passthru_awdl_mcs':'8','passthru_awdl_nss':'2',
            'passthru_awdl_collect_us':'1000','passthru_awdl_bw':'20'}
        for key,value in self.original.items():(self.params/key).write_text(value+'\n')
        (self.state/'autostart').touch();(self.state/'turbo-fast').touch()
    def run_api(self,*args,ok=True):
        r=subprocess.run(['sh',str(self.script),*args],env=self.env,capture_output=True,text=True)
        if ok:self.assertEqual(r.returncode,0,r.stderr+r.stdout)
        else:self.assertNotEqual(r.returncode,0,r.stdout)
        return r
    def params_now(self):return {p.name:p.read_text().strip() for p in self.params.iterdir()}
    def markers(self):return sorted(p.name for p in self.state.iterdir() if p.is_file() and not p.name.endswith('.flock'))
    def test_status_is_json_and_escapes_network(self):
        data=json.loads(self.run_api('status').stdout)
        self.assertEqual(data['channel'],'149');self.assertEqual(data['profile'],'fast')
        self.assertEqual(data['network'],'"V\\quote\\\""')
        self.assertEqual(data['editable'],'on');self.assertEqual(data['scope'],'auto')
    def test_apply_profile_scope_coexist_and_autostart(self):
        data=json.loads(self.run_api('apply','on','same149','vht','off').stdout)
        self.assertEqual((data['profile'],data['scope'],data['autostart']),('vht','same149','off'))
        self.assertEqual(self.params_now()['passthru_awdl_nss'],'1')
        self.assertEqual(self.params_now()['passthru_awdl_collect_us'],'0')
        self.assertTrue((self.state/'coex-same149-only').exists())
        data=json.loads(self.run_api('apply','off','auto','fast','on').stdout)
        self.assertEqual(data['coexist'],'off');self.assertEqual(data['scope'],'auto')
        self.assertEqual(self.params_now()['passthru_awdl_nss'],'2')
    def test_active_or_uncertain_session_rejects_all_changes(self):
        for name in ['ui-session','coex-session','coex-session.preparing']:
            p=self.state/name
            if name=='ui-session':p.touch()
            else:p.mkdir()
            self.assertEqual(json.loads(self.run_api('status').stdout)['editable'],'off')
            self.run_api('apply','off','same149','off','off',ok=False)
            self.assertEqual(self.params_now(),self.original)
            p.unlink() if p.is_file() else p.rmdir()
        self.env['TEST_TRANSFER_BUSY']='9'
        self.run_api('apply','off','same149','off','off',ok=False)
        self.assertEqual(self.markers(),['autostart','turbo-fast'])
    def test_invalid_input_and_injection_do_not_change_settings(self):
        for args in [('apply','on;touch hacked','auto','fast','on'),('apply','on','149','fast','on'),
                     ('apply','on','auto','1000','on'),('apply','on','auto','fast','on','extra'),('shell','echo hacked')]:
            self.run_api(*args,ok=False)
            self.assertEqual(self.params_now(),self.original)
            self.assertEqual(self.markers(),['autostart','turbo-fast'])
    def test_failed_live_profile_restores_all_knobs_and_markers(self):
        broken=self.turbo.replace("set_p() { printf '%s'", "set_p() { [ \"$1\" != passthru_awdl_mcs ] || return 1; printf '%s'")
        (self.bin/'turbo.sh').write_text(broken)
        r=self.run_api('apply','off','same149','vht','off',ok=False)
        self.assertIn('还原',r.stderr)
        self.assertEqual(self.params_now(),self.original)
        self.assertEqual(self.markers(),['autostart','turbo-fast'])
    def test_lock_contention_and_pending_data_reject(self):
        self.env['TEST_LOCK_BUSY']='1'
        self.run_api('apply','off','auto','vht','on',ok=False)
        self.env.pop('TEST_LOCK_BUSY')
        (self.params/'opairshare_bridge_pending').write_text('2\n')
        self.run_api('apply','off','auto','vht','on',ok=False)
        self.assertEqual(self.markers(),['autostart','turbo-fast'])
    def test_offline_save_persists_for_next_start(self):
        for p in self.params.iterdir():p.unlink()
        self.params.rmdir()
        data=json.loads(self.run_api('apply','on','auto','off','off').stdout)
        self.assertEqual(data['profile'],'off');self.assertEqual(data['autostart'],'off')
    def test_stock_driver_does_not_block_offline_preferences(self):
        for p in self.params.iterdir():p.unlink()
        data=json.loads(self.run_api('status').stdout)
        self.assertEqual(data['editable'],'on');self.assertEqual(data['ready'],'off')
        data=json.loads(self.run_api('apply','on','same149','fast','off').stdout)
        self.assertEqual(data['scope'],'same149');self.assertEqual(data['autostart'],'off')

    def test_same149_scope_refuses_channel40_before_mutation(self):
        # Execute production coexistence entry, including its shared settings lock.
        for key in ['passthru_awdl_allow_concurrent','passthru_mgmt_home_channel']:(self.params/key).write_text('N\n')
        (self.base/'wifi').write_text('Frequency: 5200MHz\n')
        (self.state/'coex-same149-only').touch()
        tools=self.tools
        (tools/'getprop').write_text('#!/bin/sh\nexit 0\n');(tools/'getprop').chmod(0o755)
        (tools/'setprop').write_text('#!/bin/sh\nexit 0\n');(tools/'setprop').chmod(0o755)
        p=self.bin/'coex-session.sh';p.write_text((ROOT/'module/bin/coex-session.sh').read_text().replace('/sys/module/qca_cld3_peach_v2/parameters',str(self.params)))
        r=subprocess.run(['sh',str(p),'prepare'],env=self.env,capture_output=True,text=True)
        self.assertNotEqual(r.returncode,0);self.assertIn('same149-only',r.stdout)
        self.assertFalse((self.state/'coex-session').exists())
        self.assertEqual((self.params/'passthru_awdl_allow_concurrent').read_text().strip(),'N')

if __name__=='__main__':unittest.main(verbosity=2)
