import copy,hashlib,importlib.util,json,os,pathlib,subprocess,sys,tempfile,types,unittest
from unittest import mock
ROOT=pathlib.Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT/'scripts'))
import server_installation as installation
import test_uninstall as tree
import test_android_artifact as artifacts
from test_containers import settings
spec=importlib.util.spec_from_file_location('upgrader',ROOT/'scripts/upgrade-server.py');upgrade=importlib.util.module_from_spec(spec);spec.loader.exec_module(upgrade)

class UpgradeHost(tree.Fixture):
 def run(self,args,**kwargs):
  if args[:2] in (['/usr/bin/systemctl','stop'],['/usr/bin/systemctl','start']):
   self.calls.append((args,kwargs))
   for name in args[2:]:
    if args[1]=='stop':self.running.discard(name)
    else:self.running.add(name)
   return types.SimpleNamespace(stdout='',returncode=0)
  return super().run(args,**kwargs)

class FakeEngine:
 def __init__(self):self.calls=[];self.fail_backup=False;self.foreign=False;self.missing_images=set();self.image_checks=[];self.runtime_healthy=True
 def call(self,args,**kw):
  self.calls.append(args)
  if args[0]=='run':
   if isinstance(self.fail_backup,Exception):raise self.fail_backup
   if self.fail_backup:raise ValueError('injected backup failure')
   path=next(a for a in args if a.startswith('type=bind,src=') and a.endswith(',dst=/backup')).split('src=',1)[1].rsplit(',dst=',1)[0]
   (pathlib.Path(path)/'workspace.backup').write_bytes(b'Encrypted private backup fixture')
  return types.SimpleNamespace(stdout='',returncode=0)
 def objects(self,settings):
  if self.foreign:raise ValueError('foreign namespace member')
  return {}
 def image_inventory(self,*a):return {}
 def verify_images(self,settings):
  self.image_checks.append(copy.deepcopy(settings['images']))
  for role,image in settings['images'].items():
   if image in self.missing_images:
    error=subprocess.CalledProcessError(1,['docker','image','inspect','PRIVATE-ARGV'],stderr='No such image: PRIVATE-OUTPUT')
    error.field_notes_operation='Docker image inspect';error.field_notes_component=role
    raise error
 def healthy(self,*a):return self.runtime_healthy
 def stop(self,*a):self.objects(a[0]);self.calls.append(['stop-owned'])

class Upgrade(unittest.TestCase):
 def recovery_ready(self,phase='installed'):
  import shutil
  installed=self.host.path(self.host.record['prefix']+'/downloads');shutil.copytree(self.folder/'downloads',installed)
  info=json.loads((installed/'android.json').read_text());info.update(version='0.3.0',version_code=3);(installed/'android.json').write_text(json.dumps(info))
  database=self.host.path(self.host.record['data']+'/field-notes.sqlite3');database.write_bytes(b'SQLite format 3\x00ENCRYPTED WORKSPACE FIXTURE')
  self.host.record['phase']=phase;self.host.write_record(self.host.record)
  return database
 def execute_recovery(self,health=lambda *a:None):
  with mock.patch.object(upgrade.removal,'preflight',side_effect=self.preflight),mock.patch.object(upgrade.os,'chown'):
   return upgrade.apply(self.host,self.host.record,self.folder,self.engine,health,recover=True)
 def test_recovery_uses_verified_target_image_when_old_image_is_unavailable_and_retains_raw_snapshot(self):
  database=self.recovery_ready('installing');original=database.read_bytes();key=self.host.path(self.host.record['config']+'/master.key').read_bytes()
  self.engine.missing_images.update(self.host.record['docker']['settings']['images'].values())
  result=self.execute_recovery();backup=pathlib.Path(result['backup'])
  command=next(c for c in self.engine.calls if c[0]=='run')
  self.assertEqual(command[command.index('backup')-1],result['settings']['images']['app'])
  self.assertIn('--network=none',command);self.assertIn('--read-only',command);self.assertIn('--pull=never',command)
  self.assertEqual((backup/'pre-recovery-state/field-notes.sqlite3').read_bytes(),original)
  self.assertEqual(database.read_bytes(),original);self.assertEqual(self.host.path(self.host.record['config']+'/master.key').read_bytes(),key)
  self.assertEqual(installation.load(self.host)['phase'],'installed');self.assertEqual(self.host.bungee.read_text(),'BUNGEE MUST SURVIVE\n')
 def test_same_version_recovery_requires_identical_server_and_unchanged_android_code(self):
  self.recovery_ready();release=self.folder/'release.json';value=json.loads(release.read_text());value.update(version='0.3.0',android_version_code=3);release.write_text(json.dumps(value))
  index=self.folder/'images.json';value=json.loads(index.read_text())
  for role in value:value[role]['tag']='field-notes-'+role+':0.3.0'
  index.write_text(json.dumps(value))
  info=self.folder/'downloads/android.json';value=json.loads(info.read_text());value.update(version='0.3.0',version_code=3);info.write_text(json.dumps(value))
  with self.assertRaisesRegex(ValueError,'same-version replacements'):upgrade.preview(self.host.record,self.folder,self.host)
  with self.assertRaisesRegex(ValueError,'identical server executable'):upgrade.preview(self.host.record,self.folder,self.host,recover=True)
  (self.folder/'field-notes-server').write_bytes(self.host.path(self.host.record['prefix']+'/field-notes-server').read_bytes())
  self.assertTrue(upgrade.preview(self.host.record,self.folder,self.host,recover=True)['recover_runtime'])
  value=json.loads(info.read_text());value['version_code']=4;info.write_text(json.dumps(value));value=json.loads(release.read_text());value['android_version_code']=4;release.write_text(json.dumps(value))
  with self.assertRaisesRegex(ValueError,'cannot change the Android version code'):upgrade.preview(self.host.record,self.folder,self.host,recover=True)
 def test_recovery_refuses_empty_workspace_incomplete_inventory_and_different_signer_before_stop(self):
  database=self.recovery_ready();database.unlink()
  with self.assertRaisesRegex(ValueError,'database is missing'):self.execute_recovery()
  self.assertFalse(self.engine.calls)
  database.write_bytes(b'SQLite format 3\x00RETAINED');self.host.record['unit_hashes'].pop('field-notes-peers.timer')
  with self.assertRaisesRegex(ValueError,'complete installed service inventory'):self.execute_recovery()
  self.assertFalse(self.engine.calls)
 def test_recovery_rollback_keeps_original_snapshot_and_any_accepted_database_writes(self):
  database=self.recovery_ready('installing');original=database.read_bytes()
  def fail(*args):database.write_bytes(original+b'ACCEPTED AFTER START');raise ValueError('injected failed readiness')
  with self.assertRaisesRegex(ValueError,'previous programs/settings were restored'):self.execute_recovery(fail)
  self.assertEqual(database.read_bytes(),original+b'ACCEPTED AFTER START')
  snapshots=list(self.host.path(self.host.record['config']).glob('upgrade-*/pre-recovery-state/field-notes.sqlite3'))
  self.assertEqual(len(snapshots),1);self.assertEqual(snapshots[0].read_bytes(),original)
  self.assertEqual(installation.load(self.host)['phase'],'installing')
 def test_source_build_refuses_missing_original_signer_before_building(self):
  installed=self.host.path(self.host.record['prefix']+'/downloads');installed.mkdir();artifacts.AndroidArtifact().bundle(installed)
  with mock.patch.dict(os.environ,{'FIELD_NOTES_TOOLS':str(pathlib.Path(self.tmp.name)/'tools')}):
   with self.assertRaisesRegex(ValueError,'Original Android signing key is missing'):
    upgrade.build_from_source(self.host,self.host.record,self.folder,lambda *a,**k:self.fail('must not build'),engine=self.engine)
  self.assertFalse(self.engine.calls)
 def test_source_upgrade_uses_persistent_signer_and_checks_preview_before_apply(self):
  installed=self.host.path(self.host.record['prefix']+'/downloads');installed.mkdir();artifacts.AndroidArtifact().bundle(installed)
  tools=pathlib.Path(self.tmp.name)/'tools';key=tools/'android-user/debug.keystore';key.parent.mkdir(parents=True);key.write_bytes(b'private disposable signing fixture')
  calls=[]
  with mock.patch.dict(os.environ,{'FIELD_NOTES_TOOLS':str(tools)}),mock.patch.object(upgrade,'preview',return_value={}) as preview:
   folder=upgrade.build_from_source(self.host,self.host.record,self.folder,lambda *a,**k:calls.append((a,k)),engine=self.engine)
  self.assertEqual([row[0][0] for row in calls],[['bash','scripts/setup/cloud.sh','--server'],['bash','scripts/build/server.sh']])
  self.assertEqual(calls[1][1]['env']['FIELD_NOTES_ANDROID_LEGACY_KEYSTORE'],str(key))
  self.assertEqual(calls[1][1]['env']['FIELD_NOTES_ANDROID_LEGACY_SIGNER'],'a'*64)
  self.assertEqual(key.read_bytes(),b'private disposable signing fixture');self.assertEqual(key.stat().st_mode&0o777,0o600)
  preview.assert_called_once_with(self.host.record,folder,self.host);self.assertFalse(self.engine.calls)
 def test_public_source_upgrade_uses_exact_published_apk_without_a_local_key(self):
  installed=self.host.path(self.host.record['prefix']+'/downloads');installed.mkdir();info=artifacts.AndroidArtifact().bundle(installed)
  release=json.loads((self.folder/'release.json').read_text());release['public_android_signer_sha256']=info['signer_sha256'];(self.folder/'release.json').write_text(json.dumps(release))
  tools=pathlib.Path(self.tmp.name)/'empty-tools';calls=[]
  with mock.patch.dict(os.environ,{'FIELD_NOTES_TOOLS':str(tools),'FIELD_NOTES_ANDROID_APK':'stale-other-signer.apk','FIELD_NOTES_ANDROID_DOWNLOADS':'stale-downloads'}),mock.patch.object(upgrade,'preview',return_value={}) as preview:
   folder=upgrade.build_from_source(self.host,self.host.record,self.folder,lambda *a,**k:calls.append((a,k)),engine=self.engine)
  self.assertEqual([row[0][0] for row in calls],[['bash','scripts/setup/cloud.sh','--server'],['bash','scripts/build/server.sh']])
  self.assertFalse((tools/'android-user/debug.keystore').exists())
  for _,kwargs in calls:self.assertNotIn('FIELD_NOTES_ANDROID_APK',kwargs['env']);self.assertNotIn('FIELD_NOTES_ANDROID_DOWNLOADS',kwargs['env'])
  preview.assert_called_once_with(self.host.record,folder,self.host);self.assertFalse(self.engine.calls)
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory();self.host=UpgradeHost(self.tmp.name);self.engine=FakeEngine()
  r=self.host.record;r.update(runtime='containers',legacy=False)
  c=settings();c.update(installation=r['id'],prefix=r['prefix'],data=r['data'],config=r['config'],uid=r['uid'],gid=r['gid'])
  r['docker']={'settings':c,'tags':{'app':'field-notes-app:0.3.0','gateway':'field-notes-gateway:0.3.0'}}
  self.host.path(r['config']+'/master.key').chmod(0o600)
  for name,data in [('gateway/fieldnotes.conf',b'EXISTING PRIVATE HUB KEY AND PORT'),('tls/private/ca.key',b'EXISTING PRIVATE CA KEY'),('network.json',b'EXISTING ENDPOINTS AND PINNED CA')]:
   path=self.host.path(r['config']+'/'+name);path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(data);path.chmod(0o600)
  for name,value in [('deployment.json',c),('compose.json',{})]:self.host.path(r['config']+'/'+name).write_text(json.dumps(value))
  self.host.write_record(r);self.folder=pathlib.Path(self.tmp.name)/'bundle';self.folder.mkdir();(self.folder/'scripts').mkdir()
  for name in upgrade.HELPERS:(self.folder/'scripts'/name).write_text('new helper\n')
  (self.folder/'field-notes-server').write_bytes(b'new binary');(self.folder/'images.tar.gz').write_bytes(b'opaque offline images')
  (self.folder/'images.tar.gz.sha256').write_text(hashlib.sha256(b'opaque offline images').hexdigest()+'  images.tar.gz\n')
  (self.folder/'images.json').write_text(json.dumps({role:{'tag':'field-notes-'+role+':0.3.2','id':'sha256:'+char*64} for role,char in [('app','c'),('gateway','d')]}))
  (self.folder/'release.json').write_text(json.dumps(dict(version='0.3.2',android_version_code=5,schema=2,protocol=1)))
  downloads=self.folder/'downloads';downloads.mkdir();info=artifacts.AndroidArtifact().bundle(downloads);info.update(version='0.3.2',version_code=5);(downloads/'android.json').write_text(json.dumps(info))
 def tearDown(self):self.tmp.cleanup()
 def preflight(self,*args):
  # Real preflight independently covered by uninstall tests; no executor host is targeted.
  if self.engine.foreign:raise ValueError('foreign namespace member')
 def execute(self,health=lambda *a:None):
  with mock.patch.object(upgrade.removal,'preflight',side_effect=self.preflight),mock.patch.object(upgrade.os,'chown'):
   return upgrade.apply(self.host,self.host.record,self.folder,self.engine,health)
 def test_preview_preserves_ports_keys_and_has_no_mutations(self):
  before=self.host.path(self.host.record['config']+'/master.key').read_bytes()
  plan=upgrade.preview(self.host.record,self.folder,self.host)
  self.assertEqual(plan['settings']['web_port'],10443);self.assertEqual(plan['settings']['udp_port'],43127)
  self.assertEqual(before,self.host.path(self.host.record['config']+'/master.key').read_bytes());self.assertFalse(self.engine.calls)
 def test_success_keeps_database_identity_users_and_hub_material_and_inventories_new_images(self):
  identities={name:self.host.path(self.host.record['config']+'/'+name).read_bytes() for name in ('gateway/fieldnotes.conf','tls/private/ca.key','network.json')}
  key=self.host.path(self.host.record['config']+'/master.key').read_bytes();data=self.host.path(self.host.record['data']+'/household.sqlite3').read_bytes()
  result=self.execute();self.assertTrue(result['upgraded'])
  self.assertEqual(key,self.host.path(self.host.record['config']+'/master.key').read_bytes());self.assertEqual(data,self.host.path(self.host.record['data']+'/household.sqlite3').read_bytes())
  record=json.loads(self.host.path(self.host.record['config']+'/installation.json').read_text());self.assertEqual(record['id'],self.host.record['id']);self.assertEqual(record['wireguard_public_key'],self.host.record['wireguard_public_key']);self.assertEqual(record['docker']['tags']['app'],'field-notes-app:0.3.2')
  self.assertTrue((pathlib.Path(result['backup'])/'data/workspace.backup').is_file())
  for name,data in identities.items():self.assertEqual(data,self.host.path(self.host.record['config']+'/'+name).read_bytes())
  self.assertEqual(self.host.bungee.read_text(),'BUNGEE MUST SURVIVE\n')
 def test_readiness_failure_rolls_back_programs_settings_and_record_without_erasing_accepted_data(self):
  original=self.host.path(self.host.record['prefix']+'/field-notes-server').read_bytes()
  def failed(*a):raise ValueError('injected readiness failure')
  with self.assertRaisesRegex(ValueError,'previous programs/settings were restored'):self.execute(failed)
  self.assertEqual(original,self.host.path(self.host.record['prefix']+'/field-notes-server').read_bytes());self.assertEqual(installation.load(self.host)['docker'],self.host.record['docker'])
  self.assertTrue(self.host.path(self.host.record['data']+'/household.sqlite3').exists())
 def test_backup_failure_does_not_replace_programs_and_old_service_is_resumed(self):
  self.engine.fail_backup=True
  with self.assertRaises(ValueError):self.execute()
  self.assertIn('field-notes.service',self.host.running);self.assertEqual(self.host.path(self.host.record['prefix']+'/field-notes-server').read_text(),'installed program\n')
  report_path=next(self.host.path(self.host.record['config']).glob('upgrade-*/failure.json'))
  report=json.loads(report_path.read_text())
  self.assertEqual(report['step'],'creating the encrypted backup');self.assertTrue(report['previous_runtime_healthy']);self.assertTrue(report['programs_settings_restored'])
  self.assertEqual(report_path.stat().st_mode&0o777,0o600)
  self.assertTrue(any(args==['/usr/bin/systemctl','reset-failed','field-notes.service'] for args,kw in self.host.calls))
 def test_missing_either_old_image_refuses_before_services_backups_or_data_change(self):
  old=self.host.record['docker']['settings'];original=self.host.path(self.host.record['config']+'/installation.json').read_bytes()
  for role in ('app','gateway'):
   with self.subTest(role=role):
    self.engine.missing_images={old['images'][role]}
    with self.assertRaisesRegex(ValueError,'Normal upgrade requires both pinned rollback images') as raised:self.execute()
    self.assertIn(role,str(raised.exception));self.assertIn('--recover-runtime',str(raised.exception));self.assertNotIn('PRIVATE',str(raised.exception))
    self.assertFalse(self.engine.calls)
    self.assertFalse(any(args[:2] in (['/usr/bin/systemctl','stop'],['/usr/bin/systemctl','start']) for args,kw in self.host.calls))
    self.assertFalse(list(self.host.path(self.host.record['config']).glob('upgrade-*')))
    self.assertEqual(self.host.path(self.host.record['config']+'/installation.json').read_bytes(),original)
 def test_missing_images_refuse_before_source_build_or_signing_key_changes(self):
  self.engine.missing_images.update(self.host.record['docker']['settings']['images'].values())
  with self.assertRaisesRegex(ValueError,'Normal upgrade requires both pinned rollback images'):
   upgrade.build_from_source(self.host,self.host.record,self.folder,lambda *a,**k:self.fail('must not build'),engine=self.engine)
  self.assertFalse(self.engine.calls)
 def test_rollback_images_are_rechecked_after_target_staging_before_stopping_services(self):
  old=self.host.record['docker']['settings'];verify=self.engine.verify_images;old_checks=0
  def race(settings):
   nonlocal old_checks
   if settings['images']==old['images']:
    old_checks+=1
    if old_checks==2:self.engine.missing_images.add(old['images']['app'])
   return verify(settings)
  with mock.patch.object(self.engine,'verify_images',side_effect=race):
   with self.assertRaisesRegex(ValueError,'Normal upgrade requires both pinned rollback images'):self.execute()
  self.assertEqual(old_checks,2)
  self.assertFalse(any(args[:2]==['/usr/bin/systemctl','stop'] for args,kw in self.host.calls))
  self.assertFalse(any(args[0] in ('run','stop-owned') for args in self.engine.calls))
  self.assertEqual(self.host.path(self.host.record['prefix']+'/field-notes-server').read_text(),'installed program\n')
 def test_failure_reports_backup_step_and_safe_reason_without_command_output_or_keys(self):
  error=subprocess.CalledProcessError(125,['docker','run','SECRET-ARGV'],stderr='permission denied: SECRET-OUTPUT')
  error.field_notes_operation='Docker operation';self.engine.fail_backup=error
  with self.assertRaisesRegex(ValueError,'creating the encrypted backup') as raised:self.execute()
  report=next(self.host.path(self.host.record['config']).glob('upgrade-*/failure.json')).read_text()
  for message in (str(raised.exception),report):
   self.assertIn('permissions failure',message);self.assertNotIn('SECRET',message)
  self.assertTrue(json.loads(report)['previous_runtime_healthy'])
 def test_rollback_does_not_report_healthy_when_only_systemctl_start_succeeded(self):
  self.engine.runtime_healthy=False;self.engine.fail_backup=True
  with mock.patch.object(upgrade.time,'monotonic',side_effect=[0,31]):
   with self.assertRaisesRegex(ValueError,'previous runtime is not confirmed healthy'):self.execute()
  report=json.loads(next(self.host.path(self.host.record['config']).glob('upgrade-*/failure.json')).read_text())
  self.assertTrue(report['programs_settings_restored']);self.assertFalse(report['previous_runtime_healthy']);self.assertIn('rollback_reason',report)
 def test_missing_old_images_during_rollback_keep_newly_accepted_data_and_report_unhealthy(self):
  data=self.host.path(self.host.record['data']+'/household.sqlite3');before=data.read_bytes()
  def fail(*args):
   data.write_bytes(before+b'ACCEPTED EDIT')
   self.engine.missing_images.update(self.host.record['docker']['settings']['images'].values())
   raise ValueError('new readiness failed')
  with self.assertRaisesRegex(ValueError,'previous runtime is not confirmed healthy'):self.execute(fail)
  report=json.loads(next(self.host.path(self.host.record['config']).glob('upgrade-*/failure.json')).read_text())
  self.assertEqual(report['step'],'checking the new runtime');self.assertIn('pinned runtime image is missing',report['rollback_reason'])
  self.assertEqual(data.read_bytes(),before+b'ACCEPTED EDIT')
 def test_failed_rollback_keeps_checkpoint_and_does_not_claim_settings_restored(self):
  def fail(*args):self.engine.foreign=True;raise ValueError('new readiness failed')
  with self.assertRaisesRegex(ValueError,'rollback did not complete'):self.execute(fail)
  checkpoint=next(self.host.path(self.host.record['config']).glob('upgrade-*/checkpoint.json'))
  report=json.loads(checkpoint.with_name('failure.json').read_text())
  self.assertFalse(report['programs_settings_restored']);self.assertFalse(report['previous_runtime_healthy'])
  self.assertTrue((checkpoint.parent/'data/workspace.backup').is_file())
 def test_recent_compatible_versions_are_supported_without_permitting_replacements_or_downgrades(self):
  release_path=self.folder/'release.json';release=json.loads(release_path.read_text());release['version']='0.7.0';release_path.write_text(json.dumps(release))
  info_path=self.folder/'downloads/android.json';info=json.loads(info_path.read_text());info['version']='0.7.0';info_path.write_text(json.dumps(info))
  index_path=self.folder/'images.json';index=json.loads(index_path.read_text())
  for role in index:index[role]['tag']='field-notes-'+role+':0.7.0'
  index_path.write_text(json.dumps(index))
  self.assertEqual(upgrade.bundle_info(self.folder,'0.6.0')[0]['version'],'0.7.0')
  self.assertEqual(upgrade.bundle_info(self.folder,'0.7.0',recover=True)[0]['version'],'0.7.0')
  with self.assertRaisesRegex(ValueError,'same-version replacements'):upgrade.bundle_info(self.folder,'0.7.0')
  with self.assertRaisesRegex(ValueError,'downgrades'):upgrade.bundle_info(self.folder,'0.8.0',recover=True)
 def test_incompatible_schema_downgrade_corrupt_archive_and_foreign_namespace_refuse(self):
  release=self.folder/'release.json';original=release.read_text();data=json.loads(original);data['schema']=3;release.write_text(json.dumps(data))
  with self.assertRaises(ValueError):upgrade.preview(self.host.record,self.folder,self.host)
  release.write_text(original);r=copy.deepcopy(self.host.record);r['docker']['tags']['app']='field-notes-app:0.3.2'
  with self.assertRaises(ValueError):upgrade.preview(r,self.folder,self.host)
  (self.folder/'images.tar.gz').write_bytes(b'corrupt')
  with self.assertRaises(ValueError):self.execute()
  (self.folder/'images.tar.gz').write_bytes(b'opaque offline images');self.engine.foreign=True
  with self.assertRaises(ValueError):self.execute()
  self.assertFalse(any(c[0] in ('run','stop-owned') for c in self.engine.calls))
 def test_changed_apk_signer_is_refused_before_service_stop(self):
  import shutil
  installed=self.host.path(self.host.record['prefix']+'/downloads');shutil.copytree(self.folder/'downloads',installed)
  data=json.loads((installed/'android.json').read_text());data['signer_sha256']='b'*64;(installed/'android.json').write_text(json.dumps(data));(installed/'field-notes.apk.signer.txt').write_text('Signer #1 certificate SHA-256 digest: '+'b'*64+'\n')
  with self.assertRaisesRegex(ValueError,'APK signer changed'):upgrade.preview(self.host.record,self.folder,self.host)
  self.assertFalse(self.engine.calls)
 def test_old_image_inventory_is_scoped_for_later_uninstall(self):
  self.execute();record=json.loads(self.host.path(self.host.record['config']+'/installation.json').read_text())
  history=upgrade.removal.historical_images(self.host,record);self.assertEqual(history,[self.host.record['docker']])
 def test_foreign_upgrade_history_cannot_authorize_image_removal(self):
  result=self.execute();path=pathlib.Path(result['backup'])/'installation.json';record=json.loads(path.read_text());record['id']='12345678-1234-4234-8234-123456789abc';record['docker']['settings']['installation']=record['id'];path.write_text(json.dumps(record))
  with self.assertRaises(ValueError):upgrade.removal.historical_images(self.host,self.host.record)

if __name__=='__main__':unittest.main()
