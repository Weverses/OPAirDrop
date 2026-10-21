#!/usr/bin/env python3
from pathlib import Path
import json,os,subprocess,tempfile,unittest
HERE=Path(__file__).resolve().parent
class Recorder(unittest.TestCase):
 def fixture(self,points,confirmed=False,previous=None):
  temp=tempfile.TemporaryDirectory();self.addCleanup(temp.cleanup);b=Path(temp.name);state=b/'state';state.mkdir();(state/'stack-boot').write_text('boot')
  interface=b/'net';(interface/'statistics').mkdir(parents=True)
  (interface/'ifindex').write_text('7');(interface/'statistics/tx_bytes').write_text('0');(interface/'statistics/rx_bytes').write_text('0')
  (b/'clock').write_text('1000');(b/'keep').write_text('1');(b/'step').write_text('0');(b/'points').write_text(json.dumps(points))
  (b/'provider').write_text('Active Transfers (1)' if confirmed else 'Active Transfers (0)')
  module=b/'module';(module/'bin').mkdir(parents=True)
  (module/'bin/common.sh').write_text(f'''STATE='{state}'
COMP=provider
init_state() {{ :; }}
same_boot() {{ [ "$(cat '{b}/keep')" = 1 ]; }}
pid_is() {{ return 1; }}
''')
  tools=b/'tools';tools.mkdir();p=tools/'flock';p.write_text('#!/bin/sh\nexit 0\n');p.chmod(0o755)
  p=tools/'sleep';p.write_text('''#!/usr/bin/env python3
from pathlib import Path
import os,json
b=Path(os.environ['FIXTURE']);i=int((b/'step').read_text());points=json.loads((b/'points').read_text())
if i>=len(points):(b/'keep').write_text('0')
else:
 p=points[i];(b/'clock').write_text(str(2000+i*1000));(b/'net/ifindex').write_text(str(p[0]));(b/'net/statistics/tx_bytes').write_text(str(p[1]));(b/'net/statistics/rx_bytes').write_text(str(p[2]));(b/'step').write_text(str(i+1))
''');p.chmod(0o755)
  script=(HERE.parent/'module/bin/speed-history.sh').read_text().replace('/sys/class/net/mosey0',str(interface)).replace('awk \'{printf "%.0f",$1*1000}\' /proc/uptime',f'cat {b}/clock')
  script=script.replace('/system/bin/timeout 3 /system/bin/dumpsys activity service "$COMP"',f'cat {b}/provider')
  p=module/'bin/speed-history.sh';p.write_text(script)
  if previous:
   d=state/'speed-history';d.mkdir();(d/'records.jsonl').write_text(''.join(json.dumps(r)+'\n' for r in previous))
  env=dict(os.environ,PATH=str(tools)+os.pathsep+os.environ['PATH'],FIXTURE=str(b))
  r=subprocess.run(['sh',str(p),'run'],env=env,capture_output=True,text=True);self.assertEqual(r.returncode,0,r.stdout+r.stderr)
  status=subprocess.run(['sh',str(p),'status'],env=env,capture_output=True,text=True);self.assertEqual(status.returncode,0,status.stderr)
  return json.loads(status.stdout)
 def test_bulk_exact_bytes_rates_and_shutdown_flush(self):
  s=self.fixture([(7,100000,50000),(7,200000,100000),(7,300000,150000)])
  r=s['history'][0];self.assertEqual((r['sent_bytes'],r['received_bytes']),(300000,150000));self.assertEqual(r['peak_tx_bps'],100000);self.assertEqual(r['average_rx_bps'],50000);self.assertEqual(r['duration_s'],3);self.assertEqual(r['kind'],'traffic');self.assertEqual(s['live']['state'],'waiting')
 def test_short_confirmed_transfer_is_saved(self):
  r=self.fixture([(7,100,200)],True)['history'][0];self.assertEqual(r['kind'],'transfer');self.assertEqual(r['sent_bytes'],100)
 def test_discovery_noise_is_filtered(self):self.assertEqual(self.fixture([(7,100,200),(7,1000,2000)])['history'],[])
 def test_new_interface_splits_sessions_without_negative_delta(self):
  s=self.fixture([(7,100000,0),(8,0,0),(8,0,200000)])
  self.assertEqual(len(s['history']),2);self.assertEqual(s['history'][0]['sent_bytes'],100000);self.assertEqual(s['history'][1]['received_bytes'],200000)
 def test_idle_flush_does_not_add_idle_tail_to_average(self):
  r=self.fixture([(7,100000,0)]+[(7,100000,0)]*7)['history'][0];self.assertEqual(r['duration_s'],1);self.assertEqual(r['average_tx_bps'],100000)
 def test_history_is_bounded(self):
  s=self.fixture([(7,100000,0)],previous=[{'id':str(i)} for i in range(100)])
  self.assertEqual(len(s['history']),100);self.assertEqual(s['history'][0]['id'],'1')
if __name__=='__main__':unittest.main(verbosity=2)
