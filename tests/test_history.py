#!/usr/bin/env python3
from pathlib import Path
import json,os,subprocess,tempfile,unittest
HERE=Path(__file__).resolve().parent
class Recorder(unittest.TestCase):
 def fixture(self,points,confirmed=None,previous=None):
  temp=tempfile.TemporaryDirectory();self.addCleanup(temp.cleanup);b=Path(temp.name);state=b/'state';state.mkdir();(state/'stack-boot').write_text('boot')
  interface=b/'net';(interface/'statistics').mkdir(parents=True)
  (interface/'ifindex').write_text('7');(interface/'statistics/tx_bytes').write_text('0');(interface/'statistics/rx_bytes').write_text('0')
  (b/'clock').write_text('1000');(b/'keep').write_text('1');(b/'step').write_text('0');(b/'points').write_text(json.dumps(points))
  (b/'provider').write_text('Unavailable dump' if confirmed is None else 'ExternalSharingService state:\nActive Transfers ('+('1' if confirmed else '0')+')\nReceive provider transferring: false\nOPAIRSHARE_RECEIVE busy=0')
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
live=b/'state/speed-history/live.json'
if live.exists():
 with (b/'live-samples').open('a') as out:out.write(live.read_text().strip()+'\\n')
if i>=len(points):(b/'keep').write_text('0')
else:
 p=points[i];(b/'clock').write_text(str(p[4] if len(p)>4 else 2000+i*1000));(b/'net/ifindex').write_text(str(p[0]));(b/'net/statistics/tx_bytes').write_text(str(p[1]));(b/'net/statistics/rx_bytes').write_text(str(p[2]));(b/'step').write_text(str(i+1))
 if len(p)>3:
  state=p[3]
  if state=='unknown':(b/'provider').write_text('Unavailable dump')
  else:
   phase={'pending':'waiting','active':'active','done':'idle'}.get(state)
   (b/'provider').write_text('ExternalSharingService state:\\nActive Transfers ('+('1' if state in ('busy','pending','active','done') else '0')+')\\nReceive provider transferring: false\\nOPAIRSHARE_RECEIVE busy='+('1' if state=='publishing' else '0')+('\\nOPAIRSHARE_HISTORY state='+phase if phase else ''))
''');p.chmod(0o755)
  script=(HERE.parent/'module/bin/speed-history.sh').read_text().replace('/sys/class/net/mosey0',str(interface)).replace('awk \'{printf "%.0f",$1*1000}\' /proc/uptime',f'cat {b}/clock')
  script=script.replace('/system/bin/timeout 3 /system/bin/dumpsys activity service "$COMP"',f'cat {b}/provider')
  p=module/'bin/speed-history.sh';p.write_text(script)
  if previous:
   d=state/'speed-history';d.mkdir();(d/'records.jsonl').write_text(''.join(json.dumps(r)+'\n' for r in previous))
  env=dict(os.environ,PATH=str(tools)+os.pathsep+os.environ['PATH'],FIXTURE=str(b))
  r=subprocess.run(['sh',str(p),'run'],env=env,capture_output=True,text=True);self.assertEqual(r.returncode,0,r.stdout+r.stderr)
  status=subprocess.run(['sh',str(p),'status'],env=env,capture_output=True,text=True);self.assertEqual(status.returncode,0,status.stderr)
  result=json.loads(status.stdout)
  result['_samples']=[json.loads(line) for line in (b/'live-samples').read_text().splitlines()]
  return result
 def test_bulk_exact_bytes_rates_and_shutdown_flush(self):
  s=self.fixture([(7,100000,50000),(7,200000,100000),(7,300000,150000)])
  r=s['history'][0];self.assertEqual((r['sent_bytes'],r['received_bytes']),(300000,150000));self.assertEqual(r['peak_tx_bps'],100000);self.assertEqual(r['average_rx_bps'],50000);self.assertEqual(r['duration_s'],3);self.assertEqual(r['kind'],'traffic');self.assertEqual(s['live']['state'],'waiting')
 def test_short_confirmed_transfer_is_saved(self):
  r=self.fixture([(7,100,200)],True)['history'][0];self.assertEqual(r['kind'],'transfer');self.assertEqual(r['sent_bytes'],100)
 def test_confirmation_wait_does_not_count_time_or_handshake_bytes(self):
  points=[(7,i*10000,i*1000,'pending') for i in range(1,21)]
  points += [(7,1200000,120000,'active'),(7,2200000,220000,'active'),(7,3200000,320000,'done')]
  s=self.fixture(points,False);r=s['history'][0]
  self.assertEqual(len(s['history']),1);self.assertEqual(r['duration_s'],3)
  self.assertEqual(r['sent_bytes'],3000000);self.assertEqual(r['average_tx_bps'],1000000)
  self.assertTrue(all(p['state']=='waiting' for p in s['_samples'][:21]))
 def test_rejected_or_timed_out_invitation_creates_no_history(self):
  s=self.fixture([(7,i*10000,0,'pending') for i in range(1,11)]+[(7,110000,0,'done')],False)
  self.assertEqual(s['history'],[])
 def test_unknown_dump_after_wait_does_not_start_timer(self):
  s=self.fixture([(7,10000,0,'pending')]+[(7,10000+i*10000,0,'unknown') for i in range(1,9)],False)
  self.assertEqual(s['history'],[])
  self.assertTrue(all(p['state']=='waiting' for p in s['_samples']))
 def test_long_pause_after_acceptance_is_still_counted(self):
  points=[(7,10000,0,'pending')]*5
  points += [(7,1010000,0,'active')]*9+[(7,2010000,0,'active'),(7,2010000,0,'done')]
  s=self.fixture(points,False);r=s['history'][0]
  self.assertEqual(r['duration_s'],10);self.assertEqual(r['sent_bytes'],2000000)
 def test_acceptance_starts_clock_even_before_first_payload(self):
  points=[(7,0,0,'pending')]*5+[(7,0,0,'active')]*3+[(7,1000000,0,'active'),(7,1000000,0,'done')]
  r=self.fixture(points,False)['history'][0]
  self.assertEqual(r['duration_s'],4);self.assertEqual(r['average_tx_bps'],250000)
 def test_discovery_noise_is_filtered(self):self.assertEqual(self.fixture([(7,100,200),(7,1000,2000)])['history'],[])
 def test_new_interface_splits_sessions_without_negative_delta(self):
  s=self.fixture([(7,100000,0),(8,0,0),(8,0,200000)])
  self.assertEqual(len(s['history']),2);self.assertEqual(s['history'][0]['sent_bytes'],100000);self.assertEqual(s['history'][1]['received_bytes'],200000)
 def test_idle_flush_does_not_add_idle_tail_to_average(self):
  r=self.fixture([(7,100000,0)]+[(7,100000,0)]*7)['history'][0];self.assertEqual(r['duration_s'],1);self.assertEqual(r['average_tx_bps'],100000)
 def test_history_is_bounded(self):
  s=self.fixture([(7,100000,0)],previous=[{'id':str(i)} for i in range(100)])
  self.assertEqual(len(s['history']),100);self.assertEqual(s['history'][0]['id'],'1')
 def test_finished_transfer_closes_despite_continuous_discovery_traffic(self):
  points=[(7,1000000,0,'busy'),(7,2000000,0,'busy')]
  points += [(7,2000000+i*1000,0,'idle') for i in range(1,10)]
  s=self.fixture(points,True)
  transfers=[r for r in s['history'] if r['kind']=='transfer']
  self.assertEqual(len(transfers),1)
  self.assertLess(transfers[0]['duration_s'],5)
  self.assertLess(transfers[0]['sent_bytes'],2010000)
  self.assertTrue(all(r.get('kind')!='transfer' for r in s['_samples'][-4:]))
 def test_unknown_provider_state_does_not_falsely_end_transfer(self):
  points=[(7,1000000,0,'busy')]+[(7,1000000+i*1000,0,'unknown') for i in range(1,7)]
  r=self.fixture(points,True)['history'][0]
  self.assertEqual(r['kind'],'transfer');self.assertEqual(r['duration_s'],7)
 def test_publication_busy_retains_transfer_until_files_are_saved(self):
  points=[(7,1000000,0,'busy')]+[(7,1000000+i*1000,0,'publishing') for i in range(1,5)]
  r=self.fixture(points,True)['history'][0]
  self.assertEqual(r['kind'],'transfer');self.assertEqual(r['duration_s'],5)
 def test_discovery_before_and_after_transfer_is_not_transfer_time(self):
  points=[(7,i*1000,0,'idle') for i in range(1,11)]
  points += [(7,10000+i*1000000,0,'busy') for i in range(1,5)]
  points += [(7,4010000+i*1000,0,'idle') for i in range(1,11)]
  s=self.fixture(points,False)
  self.assertEqual(len(s['history']),1)
  r=s['history'][0]
  self.assertEqual(r['kind'],'transfer');self.assertEqual(r['duration_s'],5)
  self.assertEqual(r['sent_bytes'],4001000)
 def test_busy_transition_discards_earlier_unrecognized_discovery(self):
  points=[(7,i*1000,0,'unknown') for i in range(1,11)]
  points += [(7,1010000,0,'busy'),(7,2010000,0,'busy')]
  r=self.fixture(points)['history'][0]
  self.assertEqual(r['kind'],'transfer');self.assertEqual(r['duration_s'],2)
  self.assertEqual(r['sent_bytes'],2000000)
 def test_two_transfers_on_same_interface_have_separate_durations(self):
  points=[(7,1000000,0,'busy'),(7,2000000,0,'busy')]
  points += [(7,2000000,0,'idle')]*8
  points += [(7,3000000,0,'busy'),(7,4000000,0,'busy')]
  s=self.fixture(points,True)
  self.assertEqual([r['duration_s'] for r in s['history']],[2,2])
  self.assertEqual([r['sent_bytes'] for r in s['history']],[2000000,2000000])
 def test_active_transfer_with_long_pause_is_not_split(self):
  points=[(7,1000000,0,'busy')]*9+[(7,2000000,0,'busy')]
  r=self.fixture(points,True)['history'][0]
  self.assertEqual(r['duration_s'],10);self.assertEqual(r['sent_bytes'],2000000)
 def test_final_data_sample_is_kept_when_provider_becomes_idle(self):
  s=self.fixture([(7,1000000,0,'busy'),(7,2000000,0,'busy'),(7,3000000,0,'idle')],True)
  self.assertEqual(s['history'][0]['sent_bytes'],3000000)
  self.assertEqual(s['history'][0]['duration_s'],3)
 def test_live_rates_do_not_require_a_confirmed_transfer(self):
  s=self.fixture([(7,100000,200000,'idle')],False)
  self.assertEqual(s['history'],[])
  self.assertEqual(s['_samples'][-1]['state'],'waiting')
  self.assertEqual(s['_samples'][-1]['tx_bps'],100000)
  self.assertEqual(s['_samples'][-1]['rx_bps'],200000)
 def test_rates_and_duration_use_actual_monotonic_sample_interval(self):
  s=self.fixture([(7,4000000,2000000,'busy',3000),(7,10000000,5000000,'idle',6000)],True)
  r=s['history'][0]
  self.assertEqual(r['duration_s'],5);self.assertEqual(r['average_tx_bps'],2000000)
  self.assertEqual(s['_samples'][1]['tx_bps'],2000000)
  self.assertEqual(s['_samples'][1]['rx_bps'],1000000)
 def test_transfer_start_on_alternate_sample_keeps_first_data(self):
  s=self.fixture([(7,1000,0,'idle'),(7,1001000,0,'busy'),(7,2001000,0,'busy')],False)
  r=s['history'][0]
  self.assertEqual(r['duration_s'],2);self.assertEqual(r['sent_bytes'],2000000)
 def test_bulk_rates_do_not_overflow_android_int32(self):
  s=self.fixture([(7,22000000,11000000,'busy',3000),(7,44000000,22000000,'idle',5000)],True)
  r=s['history'][0]
  self.assertEqual(r['peak_tx_bps'],11000000);self.assertEqual(r['peak_rx_bps'],5500000)
  self.assertEqual(r['average_tx_bps'],11000000);self.assertEqual(r['average_rx_bps'],5500000)
 def test_counters_and_totals_can_cross_four_gigabytes(self):
  r=self.fixture([(7,2000000000,3000000000),(7,4500000000,6000000000)])['history'][0]
  self.assertEqual(r['sent_bytes'],4500000000);self.assertEqual(r['received_bytes'],6000000000)
  self.assertEqual(r['peak_tx_bps'],2500000000);self.assertEqual(r['peak_rx_bps'],3000000000)
 def test_half_second_samples_keep_peak_above_average(self):
  s=self.fixture([(7,22000000,11000000,'busy',1500),(7,33000000,33000000,'busy',2000),(7,55000000,44000000,'idle',2500)],True)
  for r in s['history']+s['_samples']:
   if 'average_tx_bps' in r:
    self.assertGreaterEqual(r['peak_tx_bps'],r['average_tx_bps']);self.assertGreaterEqual(r['peak_rx_bps'],r['average_rx_bps'])
  self.assertEqual(s['history'][0]['duration_s'],1.5)
 def test_rate_rounding_matches_average_rounding(self):
  r=self.fixture([(7,2,2,'busy',4000)],True)['history'][0]
  self.assertEqual(r['peak_tx_bps'],r['average_tx_bps'])
 def test_nul_separated_worker_command_is_recognized(self):
  with tempfile.TemporaryDirectory() as directory:
   b=Path(directory);module=b/'module';(module/'bin').mkdir(parents=True)
   state=b/'state';d=state/'speed-history';d.mkdir(parents=True);(d/'pid').write_text('42')
   proc=b/'proc/42';proc.mkdir(parents=True)
   (proc/'stat').write_text('42 (sh) S 1\n')
   (proc/'cmdline').write_bytes(b'/system/bin/sh\0'+str(module/'bin/speed-history.sh').encode()+b'\0run\0')
   common=(HERE.parent/'module/bin/common.sh').read_text().replace('STATE=/data/adb/opairshare',f'STATE={state}').replace('/proc/',str(b/'proc')+'/')
   (module/'bin/common.sh').write_text(common)
   script=(HERE.parent/'module/bin/speed-history.sh').read_text()
   script=script.replace('/system/bin/sh "$MODDIR/bin/speed-history.sh" run >> "$D/worker.log" 2>&1 < /dev/null &','echo unexpected_worker_launch >&2; exit 1')
   p=module/'bin/speed-history.sh';p.write_text(script)
   tools=b/'tools';tools.mkdir();timeout=tools/'timeout';timeout.write_text('#!/bin/sh\nshift\nexec "$@"\n');timeout.chmod(0o755)
   env=dict(os.environ,PATH=str(tools)+os.pathsep+os.environ['PATH'])
   result=subprocess.run(['sh',str(p),'status'],env=env,capture_output=True,text=True,check=True)
   self.assertEqual(json.loads(result.stdout)['recorder'],'on')
   result=subprocess.run(['sh',str(p),'start'],env=env,capture_output=True,text=True,check=True)
   self.assertEqual(result.stderr,'')
if __name__=='__main__':unittest.main(verbosity=2)
