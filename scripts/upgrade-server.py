#!/usr/bin/env python3
"""Upgrade an inventoried container installation without changing its identities.
Preview by default. Supports schema-2/protocol-1 releases only; no migration or erasure.
"""
import argparse,copy,fcntl,hashlib,importlib.util,ipaddress,json,os,pathlib,pwd,re,shlex,shutil,ssl,subprocess,sys,tempfile,time,urllib.request,uuid
sys.dont_write_bytecode=True
from android_artifact import verify as verify_android
from container_deployment import Engine,compose,validate,docker_failure
from server_installation import Host,load,RECORD,uninstall_wrapper
HERE=pathlib.Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('field_notes_removal',HERE/'uninstall-server.py');removal=importlib.util.module_from_spec(spec);spec.loader.exec_module(removal)
HELPERS=('uninstall-server.py','server_installation.py','container_deployment.py','container-runtime.py','peer-agent.py','tls.py','android_artifact.py','upgrade-server.py','terminal_input.py','reset-password.py','clock_health.py')

def require_rollback_images(engine,settings):
 """A normal upgrade must be able to back up and restart the old runtime."""
 try:engine.verify_images(settings)
 except (ValueError,subprocess.SubprocessError) as error:
  reason=docker_failure(error) if isinstance(error,subprocess.SubprocessError) else 'Installed runtime images failed verification'
  raise ValueError(reason+'. Normal upgrade requires both pinned rollback images; no services or data were changed. If the runtime is already stopped because its images are missing, use --recover-runtime with a verified compatible bundle. Do not reinstall or reset pairing.') from None

def failure_reason(error):
 """Never copy arbitrary command output, argv or exception text into a report."""
 if isinstance(error,subprocess.SubprocessError):
  if getattr(error,'field_notes_operation',None):return docker_failure(error)
  if isinstance(error,subprocess.TimeoutExpired):return 'Host command timed out'
  code=getattr(error,'returncode',None)
  return 'Host command failed (exit '+str(code if isinstance(code,int) else 'unknown')+')'
 if isinstance(error,PermissionError):return 'Filesystem permission denied'
 if isinstance(error,OSError):return 'Filesystem operation failed (errno '+str(error.errno if isinstance(error.errno,int) else 'unknown')+')'
 if isinstance(error,ValueError):return 'Validation or readiness check failed'
 return 'Operation failed'

def resume_previous_runtime(host,engine,settings):
 engine.verify_images(settings)
 host.command(['/usr/bin/systemctl','reset-failed','field-notes.service'])
 host.command(['/usr/bin/systemctl','start','field-notes','field-notes-peers.timer','field-notes-certificates.timer'])
 deadline=time.monotonic()+30
 while True:
  if engine.healthy(settings):return
  if time.monotonic()>=deadline:raise ValueError('Previous runtime did not become healthy')
  time.sleep(.5)

def build_from_source(host,record,root=HERE.parent,runner=subprocess.run,recover=False,engine=None):
 if record is None or record['phase'] not in (('installed','installing') if recover else ('installed',)) or record['runtime']!='containers':raise ValueError('Complete the retained installation check before upgrading')
 if not recover:require_rollback_images(engine or Engine(runner=host.runner),record['docker']['settings'])
 installed_folder=host.path(record['prefix']+'/downloads');installed=verify_android(installed_folder)
 release=json.loads((root/'release.json').read_text())
 env=os.environ.copy();env.setdefault('FIELD_NOTES_TOOLS',str(pathlib.Path(pwd.getpwuid(os.geteuid()).pw_dir)/'.cache/field-notes'))
 env.pop('FIELD_NOTES_ANDROID_APK',None);env.pop('FIELD_NOTES_ANDROID_DOWNLOADS',None)
 legacy=verify_android(installed_folder/'legacy') if (installed_folder/'legacy').exists() else installed if installed['signer_sha256']!=release.get('public_android_signer_sha256') else None
 if legacy is not None:
  signing=pathlib.Path(env.get('FIELD_NOTES_ANDROID_LEGACY_KEYSTORE',str(pathlib.Path(env['FIELD_NOTES_TOOLS'])/'android-user/debug.keystore')))
  if signing.is_symlink() or not signing.is_file():raise ValueError('Original Android signing key is missing. Restore it; no replacement key or new pairing will be created')
  if signing.stat().st_uid!=os.geteuid():raise ValueError('Original Android signing key must belong to the build user')
  if signing.parent==pathlib.Path(env["FIELD_NOTES_TOOLS"])/"android-user":signing.parent.chmod(0o700)
  signing.chmod(0o600)
  print('Keeping the original local signer in a compatibility APK alongside the unchanged public GitHub APK.',flush=True)
  env['FIELD_NOTES_ANDROID_LEGACY_KEYSTORE']=str(signing.resolve());env['FIELD_NOTES_ANDROID_LEGACY_SIGNER']=legacy['signer_sha256']
 else:
  env.pop('FIELD_NOTES_ANDROID_LEGACY_KEYSTORE',None);env.pop('FIELD_NOTES_ANDROID_LEGACY_SIGNER',None)
  print('Using the matching public GitHub APK; no Android signing key is needed on this server.',flush=True)
 runner(['bash','scripts/setup/cloud.sh','--server'],cwd=root,env=env,check=True)
 runner(['bash','scripts/build/server.sh'],cwd=root,env=env,check=True)
 folder=root/('dist/server/linux-'+os.uname().machine)
 if recover:preview(record,folder,host,recover=True)
 else:preview(record,folder,host) # Verify the signer before any service is stopped.
 return folder

def wrapper(config):
 return '#!/usr/bin/env bash\nset -euo pipefail\nexec python3 -B "$(dirname "${BASH_SOURCE[0]}")/upgrade-server.py" --config '+shlex.quote(config)+' "$@"\n'

def bundle_info(folder,current,recover=False):
 folder=pathlib.Path(folder).resolve();release=json.loads((folder/'release.json').read_text())
 if release['schema']!=2 or release['protocol']!=1:raise ValueError('This upgrader supports only compatible schema 2/protocol 1; use a separately reviewed migration')
 def version(value):
  if not re.fullmatch(r'\d+\.\d+\.\d+',value):raise ValueError('Invalid release version')
  return tuple(map(int,value.split('.')))
 if current not in ('0.3.0','0.3.1','0.3.2','0.3.3','0.3.4','0.3.5','0.3.6','0.3.7','0.4.0','0.5.0','0.6.0','0.7.0','0.8.0','0.8.1','0.9.0','0.9.1','0.10.0','0.11.0','0.11.1','0.11.2') or version(release['version'])<version(current) or (version(release['version'])==version(current) and not recover):raise ValueError('Only a newer compatible container release may be applied; downgrades and same-version replacements are refused')
 info=verify_android(folder/'downloads',release['version'])
 if info['version_code']!=release['android_version_code']:raise ValueError('APK version code differs from release')
 index=json.loads((folder/'images.json').read_text())
 if set(index)!={'app','gateway'}:raise ValueError('Both offline images are required')
 for role,entry in index.items():
  if entry.get('tag')!='field-notes-'+role+':'+release['version'] or not re.fullmatch('sha256:[a-f0-9]{64}',entry.get('id','')):raise ValueError('Invalid image manifest')
 for name in ('field-notes-server','images.tar.gz','images.json','release.json',*('scripts/'+n for n in HELPERS)):
  file=folder/name
  if file.is_symlink() or not file.is_file():raise ValueError('Missing or symlinked upgrade file: '+name)
 expected=(folder/'images.tar.gz.sha256').read_text().split()[0]
 with (folder/'images.tar.gz').open('rb') as file:actual=hashlib.file_digest(file,'sha256').hexdigest()
 if actual!=expected:raise ValueError('Offline image archive checksum mismatch')
 return release,info,index

def preview(record,folder,host=None,recover=False):
 if record is None or record['phase'] not in (('installed','installing') if recover else ('installed',)) or record['runtime']!='containers' or record['legacy']:raise ValueError('Upgrade requires an installed inventoried container deployment; native/partial installations are not adopted')
 current=record['docker']['tags']['app'].split(':')[1]
 release,apk,images=bundle_info(folder,current,recover)
 old=validate(record['docker']['settings'])
 existing=(host.path(record['prefix']) if host else pathlib.Path(record['prefix']))/'downloads'
 if existing.exists():
  old_signers={verify_android(existing)['signer_sha256']};new_signers={apk['signer_sha256']}
  if (existing/'legacy').exists():old_signers.add(verify_android(existing/'legacy')['signer_sha256'])
  if (pathlib.Path(folder)/'downloads/legacy').exists():new_signers.add(verify_android(pathlib.Path(folder)/'downloads/legacy')['signer_sha256'])
  if not old_signers.issubset(new_signers):raise ValueError('APK signer changed. Supply the matching original-signer compatibility APK; do not uninstall or reset phone pairing')
 if recover:
  if host is None:raise ValueError('Runtime recovery needs the existing host inventory')
  required={'field-notes.service','field-notes-peers.service','field-notes-peers.timer','field-notes-certificates.service','field-notes-certificates.timer'}
  if not required.issubset(record['unit_hashes']):raise ValueError('Runtime recovery requires a complete installed service inventory')
  installed=verify_android(existing,current)
  database=host.path(record['data']+'/field-notes.sqlite3')
  if not database.is_file():raise ValueError('Existing Field Notes database is missing; recovery will not initialize an empty workspace')
  with database.open('rb') as file:
   if file.read(16)!=b'SQLite format 3\x00':raise ValueError('Existing Field Notes database is invalid; recovery will not initialize an empty workspace')
  if release['version']==current:
   if apk['version_code']!=installed['version_code']:raise ValueError('Same-version recovery cannot change the Android version code')
   previous=host.path(record['prefix']+'/field-notes-server');candidate=pathlib.Path(folder)/'field-notes-server'
   with previous.open('rb') as a,candidate.open('rb') as b:
    if hashlib.file_digest(a,'sha256').digest()!=hashlib.file_digest(b,'sha256').digest():raise ValueError('Same-version recovery requires the identical server executable; use a newer compatible bundle')
 new=copy.deepcopy(old);new['images']={role:entry['id'] for role,entry in images.items()}
 return dict(from_version=current,to_version=release['version'],apk_version_code=apk['version_code'],apk_signer=apk['signer_sha256'],apk_sha256=apk['sha256'],recover_runtime=recover,preserve=['database and queued server work','users/password hashes','CA and recovery master key','WireGuard hub key, phone peers and PSKs','management/UDP/API ports and network settings'],management=f"https://{old['lan_ip']}:{old['web_port']}",admin_browser_check_required=ipaddress.ip_address(old['lan_ip']) not in ipaddress.ip_network(old['lan_prefix']),settings=new,tags={role:entry['tag'] for role,entry in images.items()})

def write(path,data,mode=0o600):
 with tempfile.NamedTemporaryFile(dir=path.parent,prefix='.upgrade-',delete=False) as file:
  temporary=pathlib.Path(file.name)
  try:
   os.fchmod(file.fileno(),mode);file.write(data);file.flush();os.fsync(file.fileno());os.replace(temporary,path)
  finally:temporary.unlink(missing_ok=True)
 fd=os.open(path.parent,os.O_RDONLY|os.O_DIRECTORY)
 try:os.fsync(fd)
 finally:os.close(fd)

def readiness(settings,apk,host):
 if ipaddress.ip_address(settings['lan_ip']) not in ipaddress.ip_network(settings['lan_prefix']):
  engine=Engine(runner=host.runner);ca=host.path(settings['config']+'/tls/ca.crt').read_text()
  for attempt in range(30):
   try:engine.check_management_locally(settings,ca,apk['version']);return
   except (OSError,ValueError,subprocess.SubprocessError):time.sleep(.2)
  raise ValueError('Updated runtime/TLS/source-policy readiness failed; administrator browser check must come from its allowed CIDR')
 ca=host.path(settings['config']+'/tls/ca.crt');ctx=ssl.create_default_context(cafile=str(ca));ctx.minimum_version=ssl.TLSVersion.TLSv1_3
 opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPSHandler(context=ctx));origin=f"https://{settings['lan_ip']}:{settings['web_port']}"
 for attempt in range(30):
  try:
   with opener.open(origin+'/health',timeout=3) as reply:health=json.load(reply)
   with opener.open(origin+'/downloads/android',timeout=3) as reply:download=json.load(reply)
   if health.get('version')!=apk['version'] or download.get('artifact')!=apk:raise ValueError('Updated server/version/APK readiness mismatch')
   return
  except (OSError,ValueError):time.sleep(.2)
 raise ValueError('New TLS service/APK did not become ready')

def apply(host,record,folder,engine=None,health=readiness,recover=False):
 folder=pathlib.Path(folder).resolve();plan=preview(record,folder,host,recover);engine=engine or Engine(runner=host.runner)
 removal.preflight(host,record,False);old=record['docker']['settings'];engine.objects(old)
 if not recover:require_rollback_images(engine,old)
 config=host.path(record['config']);prefix=host.path(record['prefix'])
 master=config/'master.key';meta=master.stat()
 if master.is_symlink() or meta.st_uid not in (host.owner,record['uid']) or meta.st_mode&0o777 not in (0o600,0o640):raise ValueError('Recovery key ownership/mode requires review; never replace the key')
 data_bytes=sum(p.stat().st_size for p in host.path(record['data']).rglob('*') if p.is_file())
 shared_disk=config.stat().st_dev==host.path(record['data']).stat().st_dev
 required=sum(p.stat().st_size for p in prefix.rglob('*') if p.is_file())+data_bytes*((4 if recover else 3) if shared_disk else (2 if recover else 1))+256*1024*1024
 if shutil.disk_usage(config).free<required:raise ValueError('Insufficient room for the private program snapshot and encrypted backup')
 if not shared_disk and shutil.disk_usage(host.path(record['data'])).free<data_bytes*2+64*1024*1024:raise ValueError('Insufficient room on the data disk for the streaming backup snapshot')
 backup=config/('upgrade-'+uuid.uuid4().hex);backup.mkdir(mode=0o700)
 for name in ('deployment.json','compose.json',RECORD):shutil.copy2(config/name,backup/name)
 target_record=copy.deepcopy(record);target_record['docker']={'settings':plan['settings'],'tags':plan['tags']}
 write(backup/'target-installation.json',json.dumps(target_record).encode())
 engine.call(['load','--input',str(folder/'images.tar.gz')],timeout=180);engine.verify_images(plan['settings'])
 stage=pathlib.Path(tempfile.mkdtemp(prefix='.field-notes-upgrade-',dir=prefix.parent));stage.chmod(0o755)
 try:
  shutil.copy2(folder/'field-notes-server',stage/'field-notes-server');(stage/'field-notes-server').chmod(0o755)
  for name in HELPERS:
   source=HERE/name if recover and name in ('upgrade-server.py','container-runtime.py','container_deployment.py') else folder/'scripts'/name
   shutil.copy2(source,stage/name);(stage/name).chmod(0o755)
  (stage/'uninstall-server.sh').write_text(uninstall_wrapper(record['config']));(stage/'uninstall-server.sh').chmod(0o755)
  (stage/'upgrade-server.sh').write_text(wrapper(record['config']));(stage/'upgrade-server.sh').chmod(0o755)
  shutil.copytree(folder/'downloads',stage/'downloads')
  # Recheck after loading/staging: do not stop a running service if an external
  # image cleanup removed its rollback pins during preparation.
  if not recover:require_rollback_images(engine,old)
  step='stopping services'
  try:
   host.command(['/usr/bin/systemctl','stop','field-notes-peers.timer','field-notes-peers.service','field-notes-certificates.timer','field-notes-certificates.service'])
   host.command(['/usr/bin/systemctl','stop','field-notes'])
   engine.stop(old) # Recheck ownership, namespace membership and completed removal.
   if recover:
    step='saving the raw recovery snapshot'
    # Keep quiesced encrypted database/WAL bytes before opening them with the
    # verified target. This snapshot is retained, never restored over new writes.
    host.check_tree(record['data'])
    if any(p.is_symlink() for p in host.path(record['data']).rglob('*')):raise ValueError('Review symlinks in the data directory before runtime recovery')
    shutil.copytree(host.path(record['data']),backup/'pre-recovery-state',symlinks=True)
    (backup/'pre-recovery-state').chmod(0o700)
   step='preparing the encrypted backup'
   # Repair the earlier owner/group mode bug without generating/replacing any key.
   os.chown(master,record['uid'],record['gid']);master.chmod(0o600)
   destination=backup/'data';destination.mkdir(mode=0o700);os.chown(destination,record['uid'],record['gid'])
   backup_image=plan['settings']['images']['app'] if recover else old['images']['app']
   step='creating the encrypted backup'
   engine.call(['run','--rm','--pull=never','--network=none','--read-only','--cap-drop=ALL','--security-opt=no-new-privileges:true','--user',f"{record['uid']}:{record['gid']}",'--mount',f"type=bind,src={host.path(record['data'])},dst=/state",'--mount',f'type=bind,src={master},dst=/key,readonly','--mount',f'type=bind,src={destination},dst=/backup',backup_image,'backup','--data','/state','--key-file','/key','--file','/backup/workspace.backup'],timeout=180)
   step='checking the encrypted backup'
   saved=destination/'workspace.backup'
   if saved.is_symlink() or not saved.is_file() or {p.name for p in destination.iterdir()}!={'workspace.backup'}:raise ValueError('Encrypted pre-upgrade backup was not produced safely')
   fd=os.open(saved,os.O_RDONLY|os.O_NOFOLLOW)
   try:os.fchown(fd,host.owner,host.owner);os.fchmod(fd,0o600)
   finally:os.close(fd)
   os.chown(destination,host.owner,host.owner)
   step='writing the prepared checkpoint'
   # A durable checkpoint is kept outside the program directory. Keys/config are not regenerated.
   write(backup/'checkpoint.json',json.dumps({'installation':record['id'],'from':plan['from_version'],'to':plan['to_version'],'phase':'prepared'}).encode())
   step='replacing programs and settings'
   displaced=backup/'displaced-programs';os.replace(prefix,displaced);os.replace(stage,prefix)
   settings=plan['settings'];write(config/'deployment.json',json.dumps(settings).encode());write(config/'compose.json',json.dumps(compose(settings)).encode())
   updated=copy.deepcopy(record);updated['docker']={'settings':settings,'tags':plan['tags']};host.write_record(updated)
   step='starting the new runtime'
   host.command(['/usr/bin/systemctl','reset-failed','field-notes.service'])
   host.command(['/usr/bin/systemctl','start','field-notes','field-notes-peers.timer','field-notes-certificates.timer'])
   step='checking the new runtime'
   health(settings,verify_android(folder/'downloads',plan['to_version']),host)
   step='completing the checkpoint'
   if recover and updated['phase']=='installing':updated['phase']='installed';host.write_record(updated)
   write(backup/'checkpoint.json',json.dumps({'installation':record['id'],'from':plan['from_version'],'to':plan['to_version'],'phase':'completed'}).encode())
   # Keep old images available for explicit rollback. No shared cache is pruned.
   return {**plan,'backup':str(backup),'upgraded':True}
  except Exception as error:
   report={'step':step,'reason':failure_reason(error),'programs_settings_restored':False,'previous_runtime_healthy':False}
   try:
    host.command(['/usr/bin/systemctl','stop','field-notes-peers.timer','field-notes-peers.service','field-notes-certificates.timer','field-notes-certificates.service'])
    host.command(['/usr/bin/systemctl','stop','field-notes'])
    engine.stop(plan['settings'])
    if (backup/'displaced-programs').exists():
     failed=backup/'failed-programs'
     if prefix.exists():os.replace(prefix,failed)
     os.replace(backup/'displaced-programs',prefix)
    for name in ('deployment.json','compose.json',RECORD):write(config/name,(backup/name).read_bytes())
    report['programs_settings_restored']=True
    resume_previous_runtime(host,engine,old)
    report['previous_runtime_healthy']=True
   except Exception as rollback_error:report['rollback_reason']=failure_reason(rollback_error)
   status='previous programs/settings were restored; the previous runtime is healthy.' if report['previous_runtime_healthy'] else 'previous programs/settings were restored, but the previous runtime is not confirmed healthy.' if report['programs_settings_restored'] else 'rollback did not complete; keep the checkpoint for recovery.'
   report_path=backup/'failure.json'
   try:write(report_path,json.dumps(report,indent=2).encode())
   except OSError:report_path=backup
   raise ValueError('Upgrade failed during '+step+': '+report['reason']+'; '+status+' Database and all identity keys were retained. Review '+str(report_path)) from None
 finally:
  if stage.exists():shutil.rmtree(stage)

def main():
 parser=argparse.ArgumentParser(description=__doc__);source=parser.add_mutually_exclusive_group(required=True);source.add_argument('--bundle');source.add_argument('--build-from-source',action='store_true',help='With --apply, build the next server bundle using the public APK or the retained custom development signer');parser.add_argument('--config',default='/etc/field-notes');mode=parser.add_mutually_exclusive_group();mode.add_argument('--plan',action='store_true');mode.add_argument('--apply',action='store_true');parser.add_argument('--recover-runtime',action='store_true',help='Recover a stopped runtime using a verified compatible bundle and retained data; never initializes a new workspace');args=parser.parse_args()
 host=Host();record=load(host,args.config)
 if args.build_from_source:
  if not args.apply or os.geteuid()!=0:raise ValueError('--build-from-source requires --apply as root; it never creates a fresh installation')
  if record is None or record['phase'] not in (('installed','installing') if args.recover_runtime else ('installed',)) or record['runtime']!='containers':raise ValueError('Complete container-runtime.py check --complete-installation before upgrading')
  removal.preflight(host,record,False)
  args.bundle=str(build_from_source(host,record,recover=True) if args.recover_runtime else build_from_source(host,record))
 print(json.dumps(preview(record,args.bundle,host,args.recover_runtime),indent=2))
 removal.preflight(host,record,False)
 if not args.apply:return
 if os.geteuid()!=0:raise ValueError('--apply requires root')
 lock=os.open(host.path(record['config']+'/lifecycle.lock'),os.O_WRONLY|os.O_CREAT|os.O_NOFOLLOW,0o600)
 try:
  fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB);current=load(host,args.config)
  if current!=record:raise ValueError('Installation changed during upgrade preparation')
  print(json.dumps(apply(host,current,args.bundle,recover=args.recover_runtime),indent=2))
 finally:os.close(lock)

if __name__=='__main__':
 try:main()
 except subprocess.SubprocessError as error:raise SystemExit('Upgrade stopped: '+failure_reason(error)) from None
 except (OSError,ValueError,KeyError,TypeError) as error:raise SystemExit('Upgrade stopped: '+str(error)) from None
