import json,pathlib,subprocess,unittest
ROOT=pathlib.Path(__file__).resolve().parents[1]
WEB='10443'
UDP='43127'
class Plan(unittest.TestCase):
 def run_plan(self,*extra,input=None):
  return subprocess.run(['python3','scripts/install-server.py','--plan','--lan-ip','192.168.1.10','--lan-prefix','192.168.1.0/24',*extra],cwd=ROOT,text=True,capture_output=True,input=input)
 def explicit(self,*extra):
  return self.run_plan('--web-port',WEB,'--udp-port',UDP,*extra)
 def test_missing_build_prerequisites_are_installed_after_confirmation(self):
  import sys
  from unittest import mock
  sys.path.insert(0,str(ROOT/'scripts'))
  import importlib.util
  spec=importlib.util.spec_from_file_location('fieldnotes_installer',ROOT/'scripts/install-server.py')
  module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
  installed=[False]
  def which(name):
   return None if not installed[0] and name in ('npm','cc','make') else '/usr/bin/'+name
  def fake_checked(cmd):
   if cmd[:3]==['apt-get','install','-y']:installed[0]=True
  with mock.patch.object(module.shutil,'which',side_effect=which):
   with mock.patch.object(module,'checked',side_effect=fake_checked) as run:
    module.ensure_build_prerequisites()
    self.assertEqual(run.call_args_list[0].args[0],['apt-get','update'])
    self.assertEqual(run.call_args_list[1].args[0],['apt-get','install','-y','--no-install-recommends','npm','build-essential'])
 def test_no_argument_entrypoint_launches_guided_source_install(self):
  script=(ROOT/'scripts/install-server.sh').read_text()
  self.assertIn('--apply --build-from-source --interactive',script)
  self.assertIn('if (( $# == 0 ))',script)
 def test_source_build_is_not_attempted_before_confirmation(self):
  answer='192.168.1.10\n192.168.1.0/24\n\n\n'+WEB+'\n'+UDP+'\nNO\n'
  r=subprocess.run(['python3','scripts/install-server.py','--apply','--build-from-source','--interactive'],cwd=ROOT,text=True,capture_output=True,input=answer)
  self.assertEqual(r.returncode,0,r.stderr)
  self.assertIn('Installation cancelled',r.stdout)
 def test_fully_interactive_plan_prompts_for_network_and_ports(self):
  answer='192.168.1.10\n192.168.1.0/24\n\n\n'+WEB+'\n'+UDP+'\n'
  r=subprocess.run(['python3','scripts/install-server.py','--plan','--interactive'],cwd=ROOT,text=True,capture_output=True,input=answer)
  self.assertEqual(r.returncode,0,r.stderr);plan=json.loads(r.stdout)
  self.assertIn('Server LAN IPv4 address',r.stderr)
  self.assertIn('Management HTTPS port',r.stderr)
  self.assertEqual(plan['router_forward'],'UDP '+UDP+' to 192.168.1.10:'+UDP)
 def test_missing_network_requires_flags_in_noninteractive_mode(self):
  r=subprocess.run(['python3','scripts/install-server.py','--plan','--non-interactive'],cwd=ROOT,text=True,capture_output=True)
  self.assertNotEqual(r.returncode,0);self.assertIn('Choose both LAN settings',r.stderr)
 def test_unconfirmed_apply_makes_no_changes(self):
  answer='192.168.1.10\n192.168.1.0/24\n\n\n'+WEB+'\n'+UDP+'\nNO\n'
  r=subprocess.run(['python3','scripts/install-server.py','--apply','--interactive'],cwd=ROOT,text=True,capture_output=True,input=answer)
  # Safe cancellation occurs before any privileged install work.
  self.assertEqual(r.returncode,0,r.stderr);self.assertIn('Installation cancelled',r.stdout)
 def test_requires_explicit_ports(self):
  for flags in [(),('--web-port',WEB),('--udp-port',UDP)]:
   r=self.run_plan(*flags);self.assertNotEqual(r.returncode,0);self.assertIn('Choose both ports',r.stderr)
 def test_valid_plan_only_exposes_wireguard(self):
  r=self.explicit();self.assertEqual(r.returncode,0,r.stderr);plan=json.loads(r.stdout)
  self.assertTrue(plan['no_public_web']);self.assertIn('UDP '+UDP,plan['router_forward']);self.assertEqual(plan['device_api'],'https://10.82.0.1:8443')
 def test_no_derived_port_option(self):
  r=self.run_plan('--bungee-port','47937');self.assertNotEqual(r.returncode,0);self.assertIn('unrecognized arguments',r.stderr)
 def test_explicit_port_is_used_in_forwarding_and_endpoint_guidance(self):
  r=self.explicit();self.assertEqual(r.returncode,0,r.stderr);plan=json.loads(r.stdout)
  self.assertIn('UDP '+UDP,plan['router_forward']);self.assertIn('DDNS:'+UDP,plan['endpoint_note'])
 def test_both_ports_are_prompted_and_used_in_the_plan(self):
  r=self.run_plan('--interactive',input=WEB+'\n'+UDP+'\n');self.assertEqual(r.returncode,0,r.stderr);plan=json.loads(r.stdout)
  self.assertIn('Management HTTPS port',r.stderr);self.assertIn('WireGuard UDP port',r.stderr)
  self.assertEqual(plan['lan_admin'],'https://192.168.1.10:'+WEB+' (source 192.168.1.0/24)')
  self.assertEqual(plan['router_forward'],'UDP '+UDP+' to 192.168.1.10:'+UDP);self.assertEqual(plan['port_selection'],'Chosen')
  self.assertEqual(plan['apply_port_options'],'--web-port '+WEB+' --udp-port '+UDP)
 def test_prompt_retries_blank_invalid_ports_and_reserves_inner_api(self):
  r=self.run_plan('--interactive',input='\n8443\n'+WEB+'\nbad\n70000\n'+UDP+'\n')
  self.assertEqual(r.returncode,0,r.stderr);self.assertEqual(json.loads(r.stdout)['router_forward'],'UDP '+UDP+' to 192.168.1.10:'+UDP)
 def test_noninteractive_apply_requires_both_explicit_ports(self):
  r=self.run_plan('--apply','--non-interactive');self.assertNotEqual(r.returncode,0);self.assertIn('Choose both ports',r.stderr)
 def test_flags_skip_port_prompts(self):
  r=self.run_plan('--interactive','--web-port',WEB,'--udp-port',UDP,input='')
  self.assertEqual(r.returncode,0,r.stderr);self.assertEqual(r.stderr,'')
 def test_endpoint_follows_chosen_udp_port_and_explicit_nat_mapping(self):
  r=self.run_plan('--interactive','--endpoint','home.example.net',input=WEB+'\n'+UDP+'\n');self.assertEqual(r.returncode,0,r.stderr);plan=json.loads(r.stdout)
  self.assertEqual(plan['wireguard_endpoint'],'home.example.net:'+UDP)
  r=self.explicit('--endpoint','home.example.net:43128');self.assertEqual(r.returncode,0,r.stderr)
  self.assertEqual(json.loads(r.stdout)['router_forward'],'UDP 43128 to 192.168.1.10:'+UDP)
 def test_routed_admin_subnet_can_differ_from_listener_subnet(self):
  r=self.run_plan('--lan-ip','192.168.50.10','--lan-prefix','192.168.20.0/24',
                  '--web-port',WEB,'--udp-port',UDP)
  self.assertEqual(r.returncode,0,r.stderr)
  plan=json.loads(r.stdout)
  self.assertEqual(plan['lan_admin'],'https://192.168.50.10:'+WEB+' (source 192.168.20.0/24)')
 def test_public_listener_refused_even_with_private_admin_sources(self):
  r=self.run_plan('--lan-ip','203.0.113.10','--lan-prefix','192.168.20.0/24',
                  '--web-port',WEB,'--udp-port',UDP)
  self.assertNotEqual(r.returncode,0)
  self.assertIn('management bind IP must be private',r.stderr)
 def test_overlay_conflicting_with_listener_is_rejected_even_if_admin_cidr_differs(self):
  r=self.run_plan('--lan-ip','10.82.0.10','--lan-prefix','192.168.20.0/24',
                  '--web-port',WEB,'--udp-port',UDP)
  self.assertNotEqual(r.returncode,0)
  self.assertIn('overlay',r.stderr)
 def test_overlapping_subnets_rejected(self):self.assertNotEqual(self.explicit('--overlay','192.168.1.0/24').returncode,0)
 def test_malicious_hostname_rejected(self):self.assertNotEqual(self.explicit('--hostname','bad\nreverse_proxy attacker').returncode,0)
 def test_existing_directory_not_overwritten(self):self.assertNotEqual(self.explicit('--prefix',str(ROOT)).returncode,0)
 def test_paths_must_be_distinct(self):self.assertNotEqual(self.explicit('--prefix','/tmp/field-notes-test','--data','/tmp/field-notes-test/child').returncode,0)
 def test_direct_tls_listener_contract_has_no_proxy_service(self):
  unit=(ROOT/'deploy/field-notes.service').read_text();self.assertIn('container-runtime.py run',unit);self.assertIn('Requires=docker.service',unit)
  plan=json.loads(self.explicit().stdout);self.assertFalse(any('caddy' in s or s=='field-notes-tls' for s in plan['services']));self.assertFalse((ROOT/'deploy/Caddyfile.example').exists());self.assertEqual(plan['runtime'],'containers')
if __name__=='__main__':unittest.main()
