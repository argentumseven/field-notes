"""Container specification and ownership-checked Docker operations.

App and gateway share only a network namespace. No Docker socket, hub private
key, CA private key or writable host configuration is mounted into the app.
"""
import ipaddress
import json
import os
import pathlib
import re
import shutil
import subprocess
import uuid

DOCKER = shutil.which('docker') or '/usr/bin/docker'
SELECTORS = {'DOCKER_HOST', 'DOCKER_CONTEXT', 'DOCKER_TLS', 'DOCKER_TLS_VERIFY', 'DOCKER_CERT_PATH'}
LABEL = 'org.fieldnotes.installation'
ROLE = 'org.fieldnotes.component'
PRIVATE_ENV = {name: '' for name in ('HTTP_PROXY', 'HTTPS_PROXY', 'ALL_PROXY', 'http_proxy', 'https_proxy', 'all_proxy')}
PRIVATE_ENV.update(NO_PROXY='*', no_proxy='*')


def validate(settings):
    fields = {'version','installation','project','prefix','data','config','runtime','uid','gid',
              'lan_ip','lan_prefix','overlay','web_port','udp_port','images'}
    if not isinstance(settings,dict) or set(settings)!=fields or settings['version']!=1:
        raise ValueError('Unsupported container deployment settings')
    if str(uuid.UUID(settings['installation']))!=settings['installation']:
        raise ValueError('Invalid deployment identity')
    if not re.fullmatch(r'field-notes(?:-test-[a-f0-9]{12})?',settings['project']):
        raise ValueError('Invalid container project scope')
    from server_installation import directory
    for name in ('prefix','data','config','runtime'):
        directory(settings[name])
    lan=ipaddress.IPv4Address(settings['lan_ip']);prefix=ipaddress.IPv4Network(settings['lan_prefix'])
    overlay=ipaddress.IPv4Network(settings['overlay'])
    private=[ipaddress.IPv4Network(value) for value in ('10.0.0.0/8','172.16.0.0/12','192.168.0.0/16')]
    # Bind on the host's private LAN address; authorize clients by their
    # independent, possibly routed VLAN/source network.
    if not any(lan in value for value in private) or not any(prefix.subnet_of(value) for value in private):
        raise ValueError('Management bind IP and allowed source CIDR must be private (RFC 1918)')
    if overlay.prefixlen != 24 or not any(overlay.subnet_of(value) for value in private) or overlay.overlaps(prefix) or lan in overlay:
        raise ValueError('Use a separate private WireGuard /24 overlay')
    for field in ('uid','gid'):
        if type(settings[field]) is not int or settings[field]<=0:
            raise ValueError('App must run as a nonroot identity')
    for field in ('web_port','udp_port'):
        if type(settings[field]) is not int or not 1024<=settings[field]<=65535:
            raise ValueError('Choose unprivileged listener ports')
    if settings['web_port']==8443:
        raise ValueError('8443 is the dedicated inner API')
    if not isinstance(settings['images'],dict) or set(settings['images'])!={'app','gateway'}:
        raise ValueError('Both runtime images are required')
    if any(not re.fullmatch(r'sha256:[a-f0-9]{64}',value) for value in settings['images'].values()):
        raise ValueError('Runtime images must be immutable local image IDs')
    return settings


def compose(settings):
    c=validate(settings);project=c['project'];labels={LABEL:c['installation']}
    def bind(source,target,readonly=False):
        return dict(type='bind',source=source,target=target,read_only=readonly,bind={'create_host_path':False})
    common=dict(restart='no',cap_drop=['ALL'],security_opt=['no-new-privileges:true'],read_only=True,
                pids_limit=64,ulimits={'core':{'soft':0,'hard':0}},environment=PRIVATE_ENV,pull_policy='never')
    gateway={**common,'image':c['images']['gateway'],'container_name':project+'-gateway',
             'labels':{**labels,ROLE:'gateway'},'cap_add':['NET_ADMIN'],
             'mem_limit':'128m','memswap_limit':'128m','cpus':0.5,
             'ports':[f"{c['lan_ip']}:{c['web_port']}:{c['web_port']}/tcp",f"0.0.0.0:{c['udp_port']}:{c['udp_port']}/udp"],
             'dns':['127.0.0.1'],'tmpfs':['/run:mode=0700,size=8m'],
             'sysctls':{'net.ipv4.ip_forward':'0','net.ipv6.conf.all.forwarding':'0'},
             'volumes':[bind(c['config']+'/gateway','/private',True)],'networks':['private'],
             'healthcheck':{'test':['CMD-SHELL','test -f /run/field-notes-ready && wg show fieldnotes >/dev/null'],
                            'interval':'2s','timeout':'2s','retries':20}}
    api=str(ipaddress.IPv4Network(c['overlay']).network_address+1)
    app={**common,'image':c['images']['app'],'container_name':project+'-app',
         'labels':{**labels,ROLE:'app'},'user':f"{c['uid']}:{c['gid']}",
         'network_mode':'service:gateway','depends_on':{'gateway':{'condition':'service_healthy'}},
         'mem_limit':'512m','memswap_limit':'512m','cpus':1.0,'tmpfs':['/tmp:mode=1777,size=16m,noexec,nosuid'],
         'volumes':[bind(c['data'],'/state'),bind(c['config']+'/master.key','/secrets/master.key',True),
                    bind(c['config']+'/network.json','/config/network.json',True),
                    bind(c['config']+'/tls/issued','/tls',True),bind(c['runtime'],'/peer-status',True),bind(c['prefix']+'/downloads','/downloads',True)],
         'command':['serve','--container-network','--data','/state','--key-file','/secrets/master.key',
                    '--admin-bind',f"0.0.0.0:{c['web_port']}",'--admin-allow',c['lan_prefix'],
                    '--device-bind',f'{api}:8443','--origin',f"https://{c['lan_ip']}:{c['web_port']}",
                    '--tls-cert','/tls/server.crt','--tls-key','/tls/server.key',
                    '--network-config','/config/network.json','--peer-status','/peer-status/status.json','--downloads','/downloads']}
    return {'name':project,'services':{'gateway':gateway,'app':app},
            'networks':{'private':{'name':project,'driver':'bridge','enable_ipv6':False,
                                   'labels':{**labels,ROLE:'network'}}}}


def docker_failure(error):
    """Report a bounded operation/reason, never Docker output or configuration."""
    operation=getattr(error,'field_notes_operation','Docker command')
    component=getattr(error,'field_notes_component',None)
    if component in ('app','gateway'):operation+=' ('+component+')'
    if isinstance(error,subprocess.TimeoutExpired):return operation+' timed out'
    code=getattr(error,'returncode',None)
    code=str(code) if isinstance(code,int) else 'unknown'
    diagnostic=(getattr(error,'stderr','') or '')
    if isinstance(diagnostic,bytes):diagnostic=diagnostic.decode(errors='replace')
    diagnostic=diagnostic.lower()
    reasons=(('no such image','the pinned runtime image is missing'),
             ('cannot connect to the docker daemon','the local Docker daemon is unavailable'),
             ('permission denied','Docker reported a permissions failure'),
             ('read-only file system','Docker attempted to write a read-only path'),
             ('no such container','a container disappeared during the operation'),
             ('port is already allocated','the selected listener port is already allocated'),
             ('address already in use','the selected listener address/port is already in use'),
             ('cannot assign requested address','the selected LAN address is unavailable'),
             ('has active endpoints','the private network still has active endpoints'))
    reason=next((message for needle,message in reasons if needle in diagnostic),'inspect the local Docker error for this operation')
    return operation+' failed (exit '+code+'): '+reason


class Engine:
    def __init__(self, runner=subprocess.run):
        self.runner=runner
    def call(self,args,**kwargs):
        # Runtime controls always target the local engine; no context can point
        # uninstall at another machine. Registry credentials remain untouched.
        env={key:value for key,value in os.environ.items() if key not in SELECTORS}
        try:
            return self.runner([DOCKER,'--host=unix:///var/run/docker.sock',*args],check=True,
                               capture_output=True,text=True,env=env,**kwargs)
        except (subprocess.CalledProcessError,subprocess.TimeoutExpired) as error:
            allowed={('image','inspect'),('image','ls'),('image','rm'),('container','ls'),
                     ('container','inspect'),('container','stop'),('container','rm'),
                     ('network','ls'),('network','inspect'),('network','rm')}
            prefix=tuple(args[:2])
            error.field_notes_operation='Docker '+(' '.join(prefix) if prefix in allowed else 'compose up' if args and args[0]=='compose' else 'operation')
            raise
    def read(self,args):
        return json.loads(self.call(args).stdout)
    def objects(self,settings):
        c=validate(settings);result={}
        for role in ('app','gateway'):
            name=c['project']+'-'+role
            rows=self.call(['container','ls','--all','--filter','name=^/'+name+'$','--format','{{.ID}}']).stdout.split()
            if len(rows)>1:raise ValueError('Ambiguous container name: '+name)
            if rows:
                row=self.read(['container','inspect',rows[0]])[0]
                if row.get('Name')!='/'+name or row['Config'].get('Labels',{}).get(LABEL)!=c['installation'] or row['Config']['Labels'].get(ROLE)!=role:
                    raise ValueError('Container name belongs to another installation: '+name)
                result[role]=row
        names=self.call(['network','ls','--filter','name=^'+c['project']+'$','--format','{{.ID}}']).stdout.split()
        if len(names)>1:raise ValueError('Ambiguous private network')
        if names:
            row=self.read(['network','inspect',names[0]])[0]
            if row.get('Name')!=c['project'] or row.get('Labels',{}).get(LABEL)!=c['installation'] or row['Labels'].get(ROLE)!='network':
                raise ValueError('Network name belongs to another installation')
            own={value['Id'] for value in result.values()}
            if set(row.get('Containers',{}))-own:
                raise ValueError('Private network contains an unrelated container')
            result['network']=row
        if 'gateway' in result:
            ids=self.call(['container','ls','--all','--no-trunc','--format','{{.ID}}']).stdout.split()
            if ids:
                lines=self.call(['container','inspect','--format','{{.Id}} {{.HostConfig.NetworkMode}}',*ids]).stdout.splitlines()
                own={value['Id'] for key,value in result.items() if key!='network'}
                for line in lines:
                    identifier,mode=line.split(maxsplit=1)
                    if mode in ('container:'+result['gateway']['Id'],'container:'+c['project']+'-gateway') and identifier not in own:
                        raise ValueError('An unrelated container shares the private namespace')
        return result
    def image_inventory(self,settings,tags):
        result={}
        for role,tag in tags.items():
            # Tags can move during a rebuild. Only the inventoried digest can
            # authorize cleanup; an unrelated replacement tag is never removed.
            try:row=self.read(['image','inspect',settings['images'][role]])[0]
            except subprocess.CalledProcessError as error:
                if 'no such image' in (error.stderr or '').lower():continue
                raise
            if row.get('Id')!=settings['images'][role] or row['Config'].get('Labels',{}).get(ROLE)!=role or row['Config']['Labels'].get('org.fieldnotes.runtime')!='1':
                raise ValueError('Unrecognized Field Notes runtime image')
            result[role]=row
        return result
    def remove_images(self,settings,tags):
        for role,row in self.image_inventory(settings,tags).items():
            aliases=row.get('RepoTags') or []
            if tags[role] not in aliases and aliases:continue # Other aliases are not ours.
            users=self.call(['container','ls','--all','--filter','ancestor='+settings['images'][role],'--format','{{.ID}}']).stdout.split()
            if users:raise ValueError('Runtime image is still used by another container; cache retained')
            self.call(['image','rm',tags[role] if tags[role] in aliases else settings['images'][role]])  # no force and no global prune
    def verify_images(self,settings):
        for role,image in validate(settings)['images'].items():
            try:row=self.read(['image','inspect',image])[0]
            except subprocess.SubprocessError as error:
                error.field_notes_component=role
                raise
            if row.get('Id')!=image or row['Config'].get('Labels',{}).get(ROLE)!=role or row['Config']['Labels'].get('org.fieldnotes.runtime')!='1':
                raise ValueError('Runtime image is not the expected Field Notes component')
    def stop(self,settings,remove=True):
        objects=self.objects(settings)
        for role in ('app','gateway'):
            if role in objects:
                self.call(['container','stop','--time','20',objects[role]['Id']])
                if remove:self.call(['container','rm',objects[role]['Id']])
        if remove and 'network' in objects:self.call(['network','rm',objects['network']['Id']])
        remaining=self.objects(settings)
        if remove and remaining:raise ValueError('Field Notes container resources remain')
    def start(self,settings,compose_path):
        self.verify_images(settings)
        self.stop(settings)  # Namespace holder and app are always recreated together.
        self.call(['compose','--project-name',settings['project'],'--file',str(compose_path),
                   'up','--detach','--wait','--wait-timeout','90','--pull','never','--no-build'],timeout=120)
        objects=self.objects(settings)
        if set(objects)!={'app','gateway','network'}:raise ValueError('Incomplete container deployment')
        return objects
    def healthy(self,settings):
        objects=self.objects(settings)
        return all(objects.get(role,{}).get('State',{}).get('Running') for role in ('app','gateway')) and objects['gateway']['State'].get('Health',{}).get('Status')=='healthy'
    def diagnostics(self,settings):
        """Bounded owned-container state; never dump config, environment or logs."""
        objects=self.objects(settings);lines=[]
        for role in ('app','gateway'):
            state=objects.get(role,{}).get('State')
            if state is None:lines.append(role+': missing');continue
            # Select enum/numeric/boolean fields rather than Docker's free-text
            # Error field or arbitrary user-controlled application output.
            status=state.get('Status','unknown')
            if status not in ('created','running','paused','restarting','removing','exited','dead'):status='unknown'
            lines.append(f"{role}: {status}; exit={int(state.get('ExitCode',0))}; OOM={bool(state.get('OOMKilled',False))}")
        return '\n'.join(lines)
    def check_management_locally(self,settings,ca_pem,version):
        """Probe the owned namespace when this host is outside the admin CIDR.

        The public CA travels on stdin. No password, ACL exception, network
        reconfiguration or extra container is needed. Loopback MUST get HTTP
        403 from Rust after a correctly verified TLS 1.3 handshake.
        """
        c=validate(settings);objects=self.objects(c)
        if set(objects)!={'app','gateway','network'} or not all(objects[role]['State'].get('Running') for role in ('app','gateway')) or objects['gateway']['State'].get('Health',{}).get('Status')!='healthy':
            raise ValueError('Owned app/gateway are not ready')
        actual=self.call(['exec',objects['app']['Id'],'/usr/local/bin/field-notes-server','help'],timeout=10).stdout.splitlines()
        if not actual or actual[0]!='Field Notes '+version:
            raise ValueError('Running server version does not match the APK bundle')
        code=r'''
import socket,ssl,sys
ctx=ssl.create_default_context(cadata=sys.stdin.read(65537));ctx.minimum_version=ssl.TLSVersion.TLSv1_3
with socket.create_connection(('127.0.0.1',int(sys.argv[1])),timeout=3) as raw:
 with ctx.wrap_socket(raw,server_hostname=sys.argv[2]) as conn:
  conn.sendall(('GET /health HTTP/1.1\r\nHost: '+sys.argv[2]+':'+sys.argv[1]+'\r\nConnection: close\r\n\r\n').encode('ascii'))
  response=b''
  while b'\r\n' not in response and len(response)<4096:
   block=conn.recv(256)
   if not block:break
   response+=block
  if not response.startswith(b'HTTP/1.1 403 '):raise SystemExit('Management loopback must be denied by the source policy')
print('Verified TLS 1.3 and management source restriction')
'''
        self.call(['exec','--interactive',objects['gateway']['Id'],'python3','-c',code,str(c['web_port']),c['lan_ip']],input=ca_pem,timeout=10)
    def peer_runner(self,settings):
        def run(args,**kwargs):
            gateway=self.objects(settings).get('gateway')
            if not gateway or not gateway['State'].get('Running'):
                raise ValueError('Private gateway is not running')
            # No persistence inside the container: the durable, bounded desired
            # manifest is reapplied after every fresh gateway namespace.
            if args==['/usr/bin/wg-quick','save','fieldnotes']:
                return subprocess.CompletedProcess(args,0,stdout='')
            if args[:3]!=['/usr/bin/wg','show','fieldnotes'] and args[:3]!=['/usr/bin/wg','set','fieldnotes']:
                raise ValueError('Unsupported peer-helper command')
            arguments=args[1:]
            data=None
            fds=kwargs.pop('pass_fds',())
            if 'preshared-key' in arguments:
                index=arguments.index('preshared-key')+1
                if len(fds)!=1 or arguments[index]!=f'/proc/self/fd/{fds[0]}':raise ValueError('Invalid private peer descriptor')
                data=os.read(fds[0],128)
                arguments[index]='/dev/stdin'
            kwargs.pop('check',None);kwargs.pop('capture_output',None);text=kwargs.pop('text',False)
            if data is not None:data=data.decode()  # Docker stdin, never argv/environment/logs.
            result=self.call(['exec','-i',gateway['Id'],'wg',*arguments],input=data,**kwargs)
            if not text:result.stdout=result.stdout.encode()
            return result
        return run
