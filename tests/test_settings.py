#!/usr/bin/env python3
from pathlib import Path
import importlib.util,json,unittest
HERE=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('base_settings',HERE/'settings_base.py')
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
class Settings(m.Settings):
 def setUp(self):
  super().setUp()
  self.exports=self.base/'Download/OPAirShare'
  self.script.write_text((HERE.parent/'module/bin/settings.sh').read_text().replace('/sys/module/qca_cld3_peach_v2/parameters',str(self.params)).replace('/system/bin/sh','sh').replace('/sdcard/Download/OPAirShare',str(self.exports)).replace('/system/bin/settings',str(self.tools/'settings')).replace('/system/bin/setprop',str(self.tools/'setprop')))
 def test_log_export_uses_fixed_directory_and_includes_status(self):
  (self.state/'boot-start.log').write_text('BOOT_READY\n')
  (self.state/'supervisor.log').write_text('DISCOVERY_READY\n')
  (self.state/'startup-trace.log').write_text('STARTUP stage=COEX_READY elapsed_ms=42125\n')
  data=json.loads(self.run_api('export-logs').stdout);file=Path(data['path'])
  self.assertEqual(file.parent,self.exports);self.assertTrue(file.is_file())
  content=file.read_text();self.assertIn('BOOT_READY',content);self.assertIn('DISCOVERY_READY',content);self.assertIn('test-r25',content);self.assertIn('[status]',content)
  self.assertIn('STARTUP stage=COEX_READY elapsed_ms=42125',content)
 def test_export_rejects_extra_path_argument(self):
  self.run_api('export-logs','/wrong-path',ok=False);self.assertFalse(self.exports.exists())
 def test_name_default_property_reset_and_model_fallback(self):
  data=json.loads(self.run_api('status').stdout)
  self.assertEqual(data['airdrop_name'],'OnePlus 13');self.assertEqual(data['airdrop_name_custom'],'')
  self.run_api('name-set','自定义手机'.encode().hex())
  data=json.loads(self.run_api('name-reset').stdout)
  self.assertEqual(data['airdrop_name'],'OnePlus 13');self.assertFalse((self.base/'name-store').exists())
  self.env['TEST_MARKET_NAME']=''
  self.assertEqual(json.loads(self.run_api('status').stdout)['airdrop_name'],'PJZ110')
 def test_receive_directory_persistence_reset_and_active_session(self):
  self.assertEqual(json.loads(self.run_api('status').stdout)['receive_directory'],'')
  (self.state/'ui-session').touch();self.env['TEST_TRANSFER_BUSY']='9'
  for path in ['Download/接收 📱', 'Documents/AirDrop', 'Download/$(touch nope); `id`']:
   data=json.loads(self.run_api('receive-directory-set',path.encode().hex()).stdout)
   self.assertEqual(data['receive_directory'],path)
   self.assertEqual(data['airdrop_name'],'OnePlus 13')
   self.assertEqual(json.loads(self.run_api('status').stdout)['receive_directory'],path)
  self.assertFalse((self.base/'nope').exists())
  self.assertEqual(json.loads(self.run_api('receive-directory-reset').stdout)['receive_directory'],'')
 def test_receive_directory_invalid_paths_preserve_previous_setting(self):
  self.run_api('receive-directory-set','Download/safe'.encode().hex())
  for path in ['', '/sdcard/Download/x','Android/data/x','Download/../x','Download/.OPAirShare-incoming','Download//x','Download/x/','Download/ x','Download/x.','Download/a:b','Download/a\\b','Download/a\nb','Download/\u0080','Download/\u2028','Download/'+'a'*201, 'Download/'+('a'*180+'/')*3+'x']:
   self.run_api('receive-directory-set',path.encode().hex(),ok=False)
  for value in ['a','zz','c0af','eda080','f4908080','61;touch nope']:
   self.run_api('receive-directory-set',value,ok=False)
  self.run_api('receive-directory-reset','extra',ok=False)
  self.run_api('receive-directory-set','Download/new'.encode().hex(),'extra',ok=False)
  self.assertEqual(json.loads(self.run_api('status').stdout)['receive_directory'],'Download/safe')
  self.env['TEST_NAME_WRITE_FAIL']='1'
  self.run_api('receive-directory-reset',ok=False)
  self.assertEqual(json.loads(self.run_api('status').stdout)['receive_directory'],'Download/safe')
  (self.base/'directory-store').write_text('c0af')
  self.assertEqual(json.loads(self.run_api('status').stdout)['receive_directory'],'')
 def test_name_unicode_quotes_and_shell_text_are_literal_and_survive_session(self):
  name="一加 13 📱 ' \" $HOME $(touch nope); `id`"
  (self.state/'ui-session').touch();self.env['TEST_TRANSFER_BUSY']='9'
  data=json.loads(self.run_api('name-set',name.encode().hex()).stdout)
  self.assertEqual(data['airdrop_name'],name);self.assertEqual(data['airdrop_name_custom'],name)
  self.assertEqual(data['editable'],'off')
  self.assertEqual(json.loads(self.run_api('status').stdout)['airdrop_name'],name)
  self.assertFalse((self.base/'nope').exists())
 def test_name_invalid_utf8_controls_lengths_and_arguments_preserve_previous(self):
  self.run_api('name-set','safe'.encode().hex())
  bad=['','a','zz','00','0a','c0af','eda080','f4908080','c280','e280a8','20','e38080',('x'*65).encode().hex(),('📱'*65).encode().hex(),'61; touch nope']
  for value in bad:
   with self.subTest(value=value):
    self.run_api('name-set',value,ok=False)
    self.assertEqual((self.base/'name-store').read_text(),'safe'.encode().hex())
  self.run_api('name-set','61','extra',ok=False);self.run_api('name-reset','extra',ok=False)
  for name in ['x'*64,'📱'*64]:
   self.assertEqual(json.loads(self.run_api('name-set',name.encode().hex()).stdout)['airdrop_name'],name)
 def test_name_write_failure_and_invalid_persisted_value(self):
  self.run_api('name-set','safe'.encode().hex());self.env['TEST_NAME_WRITE_FAIL']='1'
  self.run_api('name-set','new'.encode().hex(),ok=False);self.run_api('name-reset',ok=False)
  self.assertEqual(json.loads(self.run_api('status').stdout)['airdrop_name'],'safe')
  (self.base/'name-store').write_text('c0af')
  self.assertEqual(json.loads(self.run_api('status').stdout)['airdrop_name'],'OnePlus 13')
 def test_removed_coexistence_commands_cannot_disable_radio_policy(self):
  self.run_api('apply','on','auto','vht','off')
  for args in [('coexist','off'),('coexist','on'),('apply','off','auto','fast','on')]:
   self.run_api(*args,ok=False)
  data=json.loads(self.run_api('status').stdout)
  self.assertEqual((data['coexist_requested'],data['scope'],data['profile']),('on','auto','vht'))
  self.assertFalse((self.state/'coex-off').exists())
 def test_snapshot_json_preserves_unusual_text_without_awk_assignments(self):
  name='-- -f a=b \\n \\t "quoted" 📱'
  data=json.loads(self.run_api('name-set',name.encode().hex()).stdout)
  self.assertEqual(data['airdrop_name'],name)
 def test_running_apps_skip_package_manager_queries(self):
  (self.tools/'pidof').write_text('#!/bin/sh\necho 42\n')
  (self.tools/'pidof').chmod(0o755)
  with (self.bin/'common.sh').open('a') as f:
   f.write(f'\nframework() {{ case "$*" in *"pm path"*) echo unexpected_pm >> "{self.base}/pm-calls";return 1;;*) cat "{self.base}/wifi";;esac; }}\n')
  data=json.loads(self.run_api('status').stdout)
  self.assertEqual((data['quickshare'],data['oshare']),('running','running'))
  self.assertFalse((self.base/'pm-calls').exists())
if __name__=='__main__':unittest.main(verbosity=2)
