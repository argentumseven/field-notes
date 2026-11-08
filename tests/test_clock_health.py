import importlib.util,json,pathlib,subprocess,sys,tempfile,unittest
from unittest.mock import patch
sys.path.insert(0,str(pathlib.Path(__file__).resolve().parents[1]/'scripts'))
import clock_health as c
class ClockHealthTests(unittest.TestCase):
 def test_existing_systemd_service_reports_sync(self):
  def run(cmd):
   if 'timedatectl' in cmd[0]:return 'yes'
   raise FileNotFoundError()
  self.assertEqual(c.collect(run)['status'],'synchronized')
 def test_no_service_is_unknown_not_healthy(self):
  def run(cmd):raise FileNotFoundError()
  self.assertEqual(c.collect(run)['status'],'unknown')
 def test_unsynchronized_service_is_visible(self):
  def run(cmd):
   if 'timedatectl' in cmd[0]:return 'no'
   raise subprocess.TimeoutExpired(cmd,2)
  self.assertEqual(c.collect(run)['status'],'unsynchronized')
 def test_chrony_leap_and_offset_sanity(self):
  for offset,leap,status in [('0.001','Normal','synchronized'),('7.5','Normal','unsynchronized'),('0.0','Not synchronised','unsynchronized')]:
   def run(cmd):
    if 'chronyc' in cmd[0]:return f'System time     : {offset} seconds slow of NTP time\nLeap status     : {leap}'
    return 'yes'
   self.assertEqual(c.collect(run)['status'],status)
 def test_nonfinite_offsets_do_not_replace_valid_systemd_status(self):
  for value in ['nan','inf','garbage']:
   def run(cmd):return f'System time : {value} seconds\nLeap status : Normal' if 'chronyc' in cmd[0] else 'no'
   self.assertEqual(c.collect(run)['status'],'unsynchronized')
 def test_samples_rate_limit_by_boot_uptime_not_wall_clock(self):
  with tempfile.TemporaryDirectory() as d,patch.object(c,'uptime',return_value=1000),patch.object(c,'boot_id',return_value='boot'):
   p=pathlib.Path(d)/'clock.json'
   for boot,tick,due in [('boot',990,False),('boot',900,True),('boot',1100,True),('old',990,True)]:
    p.write_text(json.dumps({'boot_id':boot,'captured_uptime':tick}));self.assertEqual(c.refresh_due(p),due)
 def test_missing_or_corrupt_sample_refreshes(self):
  with tempfile.TemporaryDirectory() as d:
   p=pathlib.Path(d)/'clock.json';self.assertTrue(c.refresh_due(p));p.write_text('{');self.assertTrue(c.refresh_due(p))
