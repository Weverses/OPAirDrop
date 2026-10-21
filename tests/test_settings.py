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
  self.script.write_text((HERE.parent/'module/bin/settings.sh').read_text().replace('/sys/module/qca_cld3_peach_v2/parameters',str(self.params)).replace('/system/bin/sh','sh').replace('/sdcard/Download/OPAirShare',str(self.exports)))
 def test_log_export_uses_fixed_directory_and_includes_status(self):
  (self.state/'boot-start.log').write_text('BOOT_READY\n')
  (self.state/'supervisor.log').write_text('DISCOVERY_READY\n')
  data=json.loads(self.run_api('export-logs').stdout);file=Path(data['path'])
  self.assertEqual(file.parent,self.exports);self.assertTrue(file.is_file())
  content=file.read_text();self.assertIn('BOOT_READY',content);self.assertIn('DISCOVERY_READY',content);self.assertIn('test-r25',content);self.assertIn('[status]',content)
 def test_export_rejects_extra_path_argument(self):
  self.run_api('export-logs','/wrong-path',ok=False);self.assertFalse(self.exports.exists())
if __name__=='__main__':unittest.main(verbosity=2)
