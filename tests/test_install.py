import json,pathlib,subprocess,unittest
ROOT=pathlib.Path(__file__).resolve().parents[1]
class Plan(unittest.TestCase):
 def run_plan(self,*extra,input=None):
  return subprocess.run(['python3','scripts/install-server.py','--plan','--lan-ip','192.168.1.10','--lan-prefix','192.168.1.0/24',*extra],cwd=ROOT,text=True,capture_output=True,input=input)
 def test_valid_plan_only_exposes_wireguard(self):
  r=self.run_plan();self.assertEqual(r.returncode,0,r.stderr);plan=json.loads(r.stdout)
  self.assertTrue(plan['no_public_web']);self.assertIn('UDP 49272',plan['router_forward']);self.assertEqual(plan['device_api'],'https://10.82.0.1:8443')
 def test_port_can_follow_the_actual_bungee_listener(self):
  r=self.run_plan('--bungee-port','47937');self.assertEqual(r.returncode,0,r.stderr);plan=json.loads(r.stdout)
  self.assertEqual(plan['router_forward'],'UDP 47938 to 192.168.1.10:47938');self.assertIn('DDNS:47938',plan['endpoint_note'])
  self.assertNotEqual(self.run_plan('--bungee-port','65535').returncode,0)
  self.assertNotEqual(self.run_plan('--bungee-port','49271','--udp-port','50000').returncode,0)
 def test_explicit_port_is_used_in_forwarding_and_endpoint_guidance(self):
  r=self.run_plan('--udp-port','50000');self.assertEqual(r.returncode,0,r.stderr);plan=json.loads(r.stdout)
  self.assertIn('UDP 50000',plan['router_forward']);self.assertIn('DDNS:50000',plan['endpoint_note'])
 def test_both_ports_are_prompted_and_used_in_the_plan(self):
  r=self.run_plan('--interactive',input='10443\n49274\n');self.assertEqual(r.returncode,0,r.stderr);plan=json.loads(r.stdout)
  self.assertIn('Management HTTPS port',r.stderr);self.assertIn('WireGuard UDP port',r.stderr)
  self.assertEqual(plan['lan_admin'],'https://192.168.1.10:10443 (source 192.168.1.0/24)')
  self.assertEqual(plan['router_forward'],'UDP 49274 to 192.168.1.10:49274');self.assertEqual(plan['port_selection'],'Chosen')
  self.assertEqual(plan['apply_port_options'],'--web-port 10443 --udp-port 49274')
 def test_prompt_retries_invalid_ports_and_reserves_the_inner_api(self):
  r=self.run_plan('--interactive',input='8443\n10443\nbad\n70000\n49274\n');self.assertEqual(r.returncode,0,r.stderr)
  self.assertEqual(json.loads(r.stdout)['router_forward'],'UDP 49274 to 192.168.1.10:49274')
 def test_noninteractive_apply_requires_both_explicit_ports_before_any_mutation(self):
  r=self.run_plan('--apply','--non-interactive');self.assertNotEqual(r.returncode,0);self.assertIn('Choose both ports',r.stderr)
 def test_enter_confirms_suggested_defaults_and_flags_skip_port_prompts(self):
  r=self.run_plan('--interactive',input='\n\n');self.assertEqual(r.returncode,0,r.stderr);self.assertEqual(json.loads(r.stdout)['port_selection'],'Chosen')
  r=self.run_plan('--interactive','--web-port','10443','--bungee-port','47937',input='');self.assertEqual(r.returncode,0,r.stderr)
  self.assertEqual(r.stderr,'');self.assertEqual(json.loads(r.stdout)['apply_port_options'],'--web-port 10443 --udp-port 47938')
 def test_endpoint_follows_the_chosen_udp_port_or_reports_explicit_nat_mapping(self):
  r=self.run_plan('--interactive','--endpoint','home.example.net',input='10443\n49274\n');self.assertEqual(r.returncode,0,r.stderr);plan=json.loads(r.stdout)
  self.assertEqual(plan['wireguard_endpoint'],'home.example.net:49274');self.assertEqual(plan['router_forward'],'UDP 49274 to 192.168.1.10:49274')
  r=self.run_plan('--web-port','10443','--udp-port','50000','--endpoint','home.example.net:50001');self.assertEqual(r.returncode,0,r.stderr)
  self.assertEqual(json.loads(r.stdout)['router_forward'],'UDP 50001 to 192.168.1.10:50000')
 def test_overlapping_subnets_rejected(self):self.assertNotEqual(self.run_plan('--overlay','192.168.1.0/24').returncode,0)
 def test_malicious_hostname_rejected(self):self.assertNotEqual(self.run_plan('--hostname','bad\nreverse_proxy attacker').returncode,0)
 def test_existing_directory_not_overwritten(self):self.assertNotEqual(self.run_plan('--prefix',str(ROOT)).returncode,0)
 def test_paths_must_be_distinct(self):self.assertNotEqual(self.run_plan('--prefix','/tmp/field-notes-test','--data','/tmp/field-notes-test/child').returncode,0)
 def test_direct_tls_listener_contract_has_no_proxy_service(self):
  unit=(ROOT/'deploy/field-notes.service').read_text();self.assertIn('--admin-bind 192.168.1.10:9443',unit);self.assertIn('--device-bind 10.82.0.1:8443',unit);self.assertIn('--tls-cert',unit)
  plan=json.loads(self.run_plan().stdout);self.assertFalse(any('caddy' in s or s=='field-notes-tls' for s in plan['services']));self.assertFalse((ROOT/'deploy/Caddyfile.example').exists())
if __name__=='__main__':unittest.main()
