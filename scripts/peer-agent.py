#!/usr/bin/env python3
"""Apply a private, validated peer manifest to the dedicated Field Notes interface.
This host helper accepts no command names, interface names, routes or paths from
the web manifest. PSKs travel through a private file descriptor, never argv/logs.
"""
import argparse,base64,fcntl,hashlib,ipaddress,json,os,pathlib,stat,subprocess,tempfile,time,uuid

class ReconcileError(ValueError):pass

def key(value):
    if not isinstance(value,str) or len(value)!=44:raise ReconcileError('Invalid peer key')
    try:raw=base64.b64decode(value,validate=True)
    except ValueError:raise ReconcileError('Invalid peer key') from None
    if len(raw)!=32 or raw==bytes(32) or base64.b64encode(raw).decode()!=value:raise ReconcileError('Invalid peer key')
    return value

def read_private(path,owner,maximum=262144):
    fd=os.open(path,os.O_RDONLY|os.O_NOFOLLOW)
    try:
        info=os.fstat(fd)
        if not stat.S_ISREG(info.st_mode) or info.st_uid!=owner or info.st_mode&0o077 or info.st_size>maximum:raise ReconcileError('Unsafe private manifest ownership, permissions or size')
        with os.fdopen(fd,'rb',closefd=False) as file:data=file.read(maximum+1)
        if len(data)>maximum:raise ReconcileError('Manifest too large')
        return data
    finally:os.close(fd)

def manifest(raw,network):
    value=json.loads(raw)
    if not isinstance(value,dict) or set(value)!={'version','peers'} or type(value['version']) is not int or value['version']!=1:raise ReconcileError('Unsupported manifest')
    if not isinstance(value['peers'],list) or len(value['peers'])>253:raise ReconcileError('Too many peers')
    peers=[];keys=set();addresses=set();ids=set()
    for row in value['peers']:
        if not isinstance(row,dict) or set(row)!={'id','public_key','address','preshared_key'}:raise ReconcileError('Invalid peer fields')
        identifier=str(uuid.UUID(row['id']));public=key(row['public_key']);secret=key(row['preshared_key'])
        address=ipaddress.IPv4Interface(row['address'])
        if address.network.prefixlen!=32 or address.ip not in network or int(address.ip)-int(network.network_address) not in range(2,255):raise ReconcileError('Peer address must be an allocated client /32')
        if public in keys or address.ip in addresses or identifier in ids:raise ReconcileError('Duplicate peer identity or address')
        keys.add(public);addresses.add(address.ip);ids.add(identifier)
        peers.append(dict(id=identifier,public_key=public,address=str(address),preshared_key=secret))
    return peers

def atomic_json(path,value,mode=0o600,gid=None):
    path=pathlib.Path(path)
    with tempfile.NamedTemporaryFile(mode='w',dir=path.parent,prefix='.peer-',delete=False) as file:
        temporary=pathlib.Path(file.name)
        try:
            os.fchmod(file.fileno(),mode)
            if gid is not None:os.fchown(file.fileno(),os.geteuid(),gid)
            json.dump(value,file,separators=(',',':'));file.flush();os.fsync(file.fileno())
            os.replace(temporary,path)
            fd=os.open(path.parent,os.O_RDONLY|os.O_DIRECTORY)
            try:os.fsync(fd)
            finally:os.close(fd)
        finally:temporary.unlink(missing_ok=True)

def reconcile(config,runner=None):
    fields={'desired','status','state','interface','overlay','uid','gid'}
    if set(config) not in (fields,fields|{'deployment'}):raise ReconcileError('Invalid host configuration')
    if 'deployment' in config:
        from container_deployment import Engine,validate
        settings=validate(json.loads(read_private(config['deployment'],0,32768)))
        if settings['project']!='field-notes' or config['desired']!=settings['data']+'/peers.json' or config['status']!=settings['runtime']+'/status.json' or config['uid']!=settings['uid'] or config['gid']!=settings['gid'] or config['overlay']!=settings['overlay']:
            raise ReconcileError('Peer helper and container deployment disagree')
        if runner is None:runner=Engine().peer_runner(settings)
    if runner is None:runner=subprocess.run
    if config['interface']!='fieldnotes':raise ReconcileError('Only the dedicated fieldnotes interface is supported')
    network=ipaddress.IPv4Network(config['overlay'])
    if network.prefixlen!=24 or not any(network.subnet_of(ipaddress.IPv4Network(n)) for n in ('10.0.0.0/8','172.16.0.0/12','192.168.0.0/16')):raise ReconcileError('A private /24 is required')
    status_path=pathlib.Path(config['status']);state_path=pathlib.Path(config['state'])
    for path in (pathlib.Path(config['desired']),status_path,state_path):
        if not path.is_absolute() or '..' in path.parts or path.is_symlink():raise ReconcileError('Unsafe host path')
    raw=read_private(config['desired'],config['uid']);peers=manifest(raw,network);digest=hashlib.sha256(raw).hexdigest()
    with open(status_path.parent/'lock','a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX)
        previous=json.loads(read_private(state_path,os.geteuid())) if state_path.exists() else {'managed':[]}
        managed={key(p) for p in previous['managed']}
        result=runner(['/usr/bin/wg','show','fieldnotes','allowed-ips'],check=True,capture_output=True,text=True)
        current={}
        for line in result.stdout.splitlines():
            parts=line.split()
            if len(parts)!=2:raise ReconcileError('Unexpected WireGuard state')
            current[key(parts[0])]=parts[1]
        desired={p['public_key']:p['address'] for p in peers}
        if set(current)-managed-set(desired):raise ReconcileError('Unmanaged peers need host review before automatic enrollment')
        changed=previous.get('digest')!=digest or current!=desired
        if changed:
            for public in sorted(set(current)-set(desired)):
                runner(['/usr/bin/wg','set','fieldnotes','peer',public,'remove'],check=True,capture_output=True)
            for peer in peers:
                with tempfile.TemporaryFile(mode='w+b') as secret:
                    secret.write((peer['preshared_key']+'\n').encode());secret.flush();secret.seek(0)
                    runner(['/usr/bin/wg','set','fieldnotes','peer',peer['public_key'],'preshared-key',f'/proc/self/fd/{secret.fileno()}','allowed-ips',peer['address']],pass_fds=(secret.fileno(),),check=True,capture_output=True)
            runner(['/usr/bin/wg-quick','save','fieldnotes'],check=True,capture_output=True)
            atomic_json(state_path,{'version':1,'digest':digest,'managed':sorted(desired)})
        acknowledgement={'version':1,'digest':digest,'applied_at':int(time.time()),'peers':[{k:p[k] for k in ('id','public_key','address')} for p in peers],'error':None}
        atomic_json(status_path,acknowledgement,0o640,config['gid'])
        return acknowledgement

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--config',required=True);args=parser.parse_args()
    if os.geteuid()!=0:raise ReconcileError('Run only as the dedicated root host service')
    config=json.loads(read_private(args.config,0,16384))
    try:reconcile(config)
    except (ValueError,OSError,subprocess.SubprocessError) as error:
        reason=str(error) if isinstance(error,ReconcileError) else ('WireGuard command failed' if isinstance(error,subprocess.SubprocessError) else 'Host peer file access failed')
        # A failed reconciliation is never acknowledged as applied. No key,
        # private manifest, subprocess stderr or arbitrary exception is logged.
        atomic_json(config['status'],{'version':1,'digest':None,'peers':[],'error':reason},0o640,config['gid'])
        raise ReconcileError('Peer reconciliation failed; no successful acknowledgement issued') from None

if __name__=='__main__':
    try:main()
    except (ValueError,OSError,subprocess.SubprocessError):raise SystemExit('Field Notes peer reconciliation failed; no peer secrets were logged.')
