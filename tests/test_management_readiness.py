import pathlib,sys,types,unittest
from unittest import mock
ROOT=pathlib.Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT/'scripts'))
from test_containers import DockerFixture,settings
import test_upgrade as upgrade_tests

class LocalManagementProbe(unittest.TestCase):
 def test_routed_probe_verifies_version_and_tls_with_public_ca_on_stdin_only(self):
  c={**settings(),'lan_ip':'192.168.50.10','lan_prefix':'192.168.20.0/24'}
  engine=DockerFixture(c);objects=engine.objects(c)
  with mock.patch.object(engine,'objects',return_value=objects),mock.patch.object(engine,'call') as call:
   call.side_effect=[types.SimpleNamespace(stdout='Field Notes 0.3.3\nCommands: init, serve\n'),types.SimpleNamespace(stdout='Verified TLS 1.3 and management source restriction\n')]
   engine.check_management_locally(c,'PUBLIC CA ONLY','0.3.3')
  args=call.call_args_list
  self.assertEqual(args[0].args[0],['exec',engine.ids['app'],'/usr/local/bin/field-notes-server','help'])
  self.assertEqual(args[1].args[0][:3],['exec','--interactive',engine.ids['gateway']])
  self.assertEqual(args[1].kwargs['input'],'PUBLIC CA ONLY')
  code=args[1].args[0][5]
  self.assertIn('TLSv1_3',code);self.assertIn('HTTP/1.1 403',code)
  self.assertNotIn('PUBLIC CA ONLY',' '.join(args[1].args[0]))
  self.assertEqual(c['lan_prefix'],'192.168.20.0/24')
 def test_wrong_runtime_or_foreign_namespace_never_probes(self):
  engine=DockerFixture();objects=engine.objects(settings())
  with mock.patch.object(engine,'objects',return_value=objects),mock.patch.object(engine,'call',return_value=types.SimpleNamespace(stdout='Field Notes 0.0.0\n')) as call:
   with self.assertRaisesRegex(ValueError,'version'):engine.check_management_locally(settings(),'PUBLIC CA','0.3.3')
   self.assertEqual(call.call_count,1)
  engine.wrong_label=True
  with self.assertRaisesRegex(ValueError,'another installation'):engine.check_management_locally(settings(),'PUBLIC CA','0.3.3')

class RoutedUpgradeReadiness(unittest.TestCase):
 setUp=upgrade_tests.Upgrade.setUp
 tearDown=upgrade_tests.Upgrade.tearDown
 execute=upgrade_tests.Upgrade.execute
 preflight=upgrade_tests.Upgrade.preflight
 # Reuse the real temporary installation/backup/rollback fixture, with the
 # real readiness decision and only the host-local TLS transport controlled.
 def test_routed_upgrade_uses_namespace_probe_without_opening_forbidden_lan(self):
  from test_upgrade import upgrade
  c=self.host.record['docker']['settings'];c.update(lan_ip='192.168.50.10',lan_prefix='192.168.20.0/24')
  self.host.path(c['config']+'/tls/ca.crt').write_text('PUBLIC CA')
  with mock.patch.object(upgrade,'Engine') as constructor,mock.patch.object(upgrade.urllib.request,'build_opener') as opener:
   self.execute(health=upgrade.readiness)
   self.assertEqual(constructor.return_value.check_management_locally.call_args.args[1:3],('PUBLIC CA','0.3.2'))
   opener.assert_not_called()
 def test_failed_routed_tls_probe_rolls_back(self):
  from test_upgrade import upgrade
  c=self.host.record['docker']['settings'];c.update(lan_ip='192.168.50.10',lan_prefix='192.168.20.0/24')
  self.host.path(c['config']+'/tls/ca.crt').write_text('PUBLIC CA')
  with mock.patch.object(upgrade,'Engine') as constructor,mock.patch.object(upgrade.time,'sleep'):
   constructor.return_value.check_management_locally.side_effect=ValueError('TLS/source policy rejected')
   with self.assertRaisesRegex(ValueError,'previous programs/settings were restored'):self.execute(health=upgrade.readiness)
