#!/usr/bin/env python3
"""Upgrade an inventoried container installation without changing its identities.
Preview by default. Supports schema-2/protocol-1 releases only; no migration or erasure.
"""
import argparse,copy,fcntl,hashlib,importlib.util,json,os,pathlib,re,shlex,shutil,ssl,subprocess,sys,tempfile,time,urllib.request,uuid
sys.dont_write_bytecode=True
from android_artifact import verify as verify_android
from container_deployment import Engine,compose,validate
from server_installation import Host,load,RECORD,uninstall_wrapper
HERE=pathlib.Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('field_notes_removal',HERE/'uninstall-server.py');removal=importlib.util.module_from_spec(spec);spec.loader.exec_module(removal)
HELPERS=('uninstall-server.py','server_installation.py','container_deployment.py','container-runtime.py','peer-agent.py','tls.py','android_artifact.py','upgrade-server.py')

def wrapper(config):
 return '#!/usr/bin/env bash\nset -euo pipefail\nexec python3 -B "$(dirname "${BASH_SOURCE[0]}")/upgrade-server.py" --config '+shlex.quote(config)+' "$@"\n'

def bundle_info(folder,current):
 folder=pathlib.Path(folder).resolve();release=json.loads((folder/'release.json').read_text())
 if release['schema']!=2 or release['protocol']!=1:raise ValueError('This upgrader supports only compatible schema 2/protocol 1; use a separately reviewed migration')
 def version(value):
  if not re.fullmatch(r'\d+\.\d+\.\d+',value):raise ValueError('Invalid release version')
  return tuple(map(int,value.split('.')))
 if current not in ('0.3.0','0.3.1','0.3.2','0.3.3') or version(release['version'])<=version(current):raise ValueError('Only a newer compatible container release may be applied; downgrades and same-version replacements are refused')
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

def preview(record,folder,host=None):
 if record is None or record['phase']!='installed' or record['runtime']!='containers' or record['legacy']:raise ValueError('Upgrade requires an installed inventoried container deployment; native/partial installations are not adopted')
 current=record['docker']['tags']['app'].split(':')[1]
 release,apk,images=bundle_info(folder,current)
 old=validate(record['docker']['settings'])
 existing=(host.path(record['prefix']) if host else pathlib.Path(record['prefix']))/'downloads'
 if existing.exists() and verify_android(existing)['signer_sha256']!=apk['signer_sha256']:
  raise ValueError('APK signer changed. Rebuild with the existing stable signer; do not uninstall or reset phone pairing')
 new=copy.deepcopy(old);new['images']={role:entry['id'] for role,entry in images.items()}
 return dict(from_version=current,to_version=release['version'],apk_version_code=apk['version_code'],apk_signer=apk['signer_sha256'],apk_sha256=apk['sha256'],preserve=['database and queued server work','users/password hashes','CA and recovery master key','WireGuard hub key, phone peers and PSKs','management/UDP/API ports and network settings'],management=f"https://{old['lan_ip']}:{old['web_port']}",settings=new,tags={role:entry['tag'] for role,entry in images.items()})

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

def apply(host,record,folder,engine=None,health=readiness):
 folder=pathlib.Path(folder).resolve();plan=preview(record,folder,host);engine=engine or Engine(runner=host.runner)
 removal.preflight(host,record,False);old=record['docker']['settings'];engine.objects(old)
 config=host.path(record['config']);prefix=host.path(record['prefix'])
 master=config/'master.key';meta=master.stat()
 if master.is_symlink() or meta.st_uid not in (host.owner,record['uid']) or meta.st_mode&0o777 not in (0o600,0o640):raise ValueError('Recovery key ownership/mode requires review; never replace the key')
 required=sum(p.stat().st_size for p in prefix.rglob('*') if p.is_file())+sum(p.stat().st_size for p in host.path(record['data']).rglob('*') if p.is_file())+256*1024*1024
 if shutil.disk_usage(config).free<required:raise ValueError('Insufficient room for the private program snapshot and encrypted backup')
 backup=config/('upgrade-'+uuid.uuid4().hex);backup.mkdir(mode=0o700)
 for name in ('deployment.json','compose.json',RECORD):shutil.copy2(config/name,backup/name)
 target_record=copy.deepcopy(record);target_record['docker']={'settings':plan['settings'],'tags':plan['tags']}
 write(backup/'target-installation.json',json.dumps(target_record).encode())
 engine.call(['load','--input',str(folder/'images.tar.gz')],timeout=180);engine.verify_images(plan['settings'])
 stage=pathlib.Path(tempfile.mkdtemp(prefix='.field-notes-upgrade-',dir=prefix.parent));stage.chmod(0o755)
 try:
  shutil.copy2(folder/'field-notes-server',stage/'field-notes-server');(stage/'field-notes-server').chmod(0o755)
  for name in HELPERS:shutil.copy2(folder/'scripts'/name,stage/name);(stage/name).chmod(0o755)
  (stage/'uninstall-server.sh').write_text(uninstall_wrapper(record['config']));(stage/'uninstall-server.sh').chmod(0o755)
  (stage/'upgrade-server.sh').write_text(wrapper(record['config']));(stage/'upgrade-server.sh').chmod(0o755)
  shutil.copytree(folder/'downloads',stage/'downloads')
  try:
   host.command(['/usr/bin/systemctl','stop','field-notes-peers.timer','field-notes-peers.service','field-notes-certificates.timer','field-notes-certificates.service'])
   host.command(['/usr/bin/systemctl','stop','field-notes'])
   engine.stop(old) # Recheck ownership, namespace membership and completed removal.
   # Repair the earlier owner/group mode bug without generating/replacing any key.
   os.chown(master,record['uid'],record['gid']);master.chmod(0o600)
   destination=backup/'data';destination.mkdir(mode=0o700);os.chown(destination,record['uid'],record['gid'])
   engine.call(['run','--rm','--network=none','--read-only','--cap-drop=ALL','--security-opt=no-new-privileges:true','--user',f"{record['uid']}:{record['gid']}",'--mount',f"type=bind,src={host.path(record['data'])},dst=/state",'--mount',f'type=bind,src={master},dst=/key,readonly','--mount',f'type=bind,src={destination},dst=/backup',old['images']['app'],'backup','--data','/state','--key-file','/key','--file','/backup/workspace.backup'],timeout=180)
   saved=destination/'workspace.backup'
   if saved.is_symlink() or not saved.is_file() or {p.name for p in destination.iterdir()}!={'workspace.backup'}:raise ValueError('Encrypted pre-upgrade backup was not produced safely')
   fd=os.open(saved,os.O_RDONLY|os.O_NOFOLLOW)
   try:os.fchown(fd,host.owner,host.owner);os.fchmod(fd,0o600)
   finally:os.close(fd)
   os.chown(destination,host.owner,host.owner)
   # A durable checkpoint is kept outside the program directory. Keys/config are not regenerated.
   write(backup/'checkpoint.json',json.dumps({'installation':record['id'],'from':plan['from_version'],'to':plan['to_version'],'phase':'prepared'}).encode())
   displaced=backup/'displaced-programs';os.replace(prefix,displaced);os.replace(stage,prefix)
   settings=plan['settings'];write(config/'deployment.json',json.dumps(settings).encode());write(config/'compose.json',json.dumps(compose(settings)).encode())
   updated=copy.deepcopy(record);updated['docker']={'settings':settings,'tags':plan['tags']};host.write_record(updated)
   host.command(['/usr/bin/systemctl','start','field-notes','field-notes-peers.timer','field-notes-certificates.timer'])
   health(settings,verify_android(folder/'downloads',plan['to_version']),host)
   write(backup/'checkpoint.json',json.dumps({'installation':record['id'],'from':plan['from_version'],'to':plan['to_version'],'phase':'completed'}).encode())
   # Keep old images available for explicit rollback. No shared cache is pruned.
   return {**plan,'backup':str(backup),'upgraded':True}
  except Exception:
   host.command(['/usr/bin/systemctl','stop','field-notes-peers.timer','field-notes-peers.service','field-notes-certificates.timer','field-notes-certificates.service'])
   host.command(['/usr/bin/systemctl','stop','field-notes'])
   engine.stop(plan['settings'])
   if (backup/'displaced-programs').exists():
    failed=backup/'failed-programs'
    if prefix.exists():os.replace(prefix,failed)
    os.replace(backup/'displaced-programs',prefix)
   for name in ('deployment.json','compose.json',RECORD):write(config/name,(backup/name).read_bytes())
   host.command(['/usr/bin/systemctl','start','field-notes','field-notes-peers.timer','field-notes-certificates.timer'])
   raise ValueError('Upgrade failed; previous programs/settings were restored. Database and all identity keys were retained. Review '+str(backup)) from None
 finally:
  if stage.exists():shutil.rmtree(stage)

def main():
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--bundle',required=True);parser.add_argument('--config',default='/etc/field-notes');mode=parser.add_mutually_exclusive_group();mode.add_argument('--plan',action='store_true');mode.add_argument('--apply',action='store_true');args=parser.parse_args()
 host=Host();record=load(host,args.config);print(json.dumps(preview(record,args.bundle),indent=2))
 removal.preflight(host,record,False)
 if not args.apply:return
 if os.geteuid()!=0:raise ValueError('--apply requires root')
 lock=os.open(host.path(record['config']+'/lifecycle.lock'),os.O_WRONLY|os.O_CREAT|os.O_NOFOLLOW,0o600)
 try:
  fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB);current=load(host,args.config)
  if current!=record:raise ValueError('Installation changed during upgrade preparation')
  print(json.dumps(apply(host,current,args.bundle),indent=2))
 finally:os.close(lock)

if __name__=='__main__':
 try:main()
 except (OSError,ValueError,KeyError,TypeError,subprocess.SubprocessError) as error:raise SystemExit('Upgrade stopped: '+str(error))
