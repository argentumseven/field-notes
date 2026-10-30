import json,pathlib,subprocess,unittest
ROOT=pathlib.Path(__file__).resolve().parents[1]
class Plan(unittest.TestCase):
 def run_plan(self,*extra):
  return subprocess.run(['python3','scripts/install-server.py','--plan','--lan-ip','192.168.1.10','--lan-prefix','192.168.1.0/24',*extra],cwd=ROOT,text=True,capture_output=True)
 def test_valid_plan_only_exposes_wireguard(self):
  r=self.run_plan();self.assertEqual(r.returncode,0,r.stderr);plan=json.loads(r.stdout)
  self.assertTrue(plan['no_public_web']);self.assertIn('UDP 51831',plan['router_forward']);self.assertEqual(plan['device_api'],'https://10.82.0.1:8443')
 def test_overlapping_subnets_rejected(self):self.assertNotEqual(self.run_plan('--overlay','192.168.1.0/24').returncode,0)
 def test_malicious_hostname_rejected(self):self.assertNotEqual(self.run_plan('--hostname','bad\nreverse_proxy attacker').returncode,0)
 def test_existing_directory_not_overwritten(self):self.assertNotEqual(self.run_plan('--prefix',str(ROOT)).returncode,0)
 def test_paths_must_be_distinct(self):self.assertNotEqual(self.run_plan('--prefix','/tmp/field-notes-test','--data','/tmp/field-notes-test/child').returncode,0)
 def test_firewall_and_caddy_have_independent_listener_contract(self):
  c=(ROOT/'deploy/Caddyfile.example').read_text();self.assertIn('bind 192.168.1.10',c);self.assertIn('bind 10.82.0.1',c);self.assertIn('skip_install_trust',c);self.assertNotIn(':80',c)
if __name__=='__main__':unittest.main()
