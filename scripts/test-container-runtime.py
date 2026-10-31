#!/usr/bin/env python3
"""Isolated real Docker/TLS/WireGuard test. Never targets the production project.
A fixture-only userspace WG/TUN image exercises kernels without WireGuard.
Production images do not include that binary or its device permissions.
"""
import base64
import http.cookiejar
import importlib.util
import json
import os
import pathlib
import secrets
import shutil
import socket
import ssl
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import uuid
import zlib
import struct

sys.dont_write_bytecode=True
HERE=pathlib.Path(__file__).resolve().parent
ROOT=HERE.parent
sys.path.insert(0,str(HERE))
from container_deployment import Engine,compose,PRIVATE_ENV


def module(name,file):
    spec=importlib.util.spec_from_file_location(name,file);value=importlib.util.module_from_spec(spec);spec.loader.exec_module(value);return value


def port():
    with socket.socket() as s:s.bind(('172.17.0.1',0));return s.getsockname()[1]


def png():
    def chunk(kind,data):return struct.pack('>I',len(data))+kind+data+struct.pack('>I',zlib.crc32(kind+data)&0xffffffff)
    return b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',1,1,8,2,0,0,0))+chunk(b'IDAT',zlib.compress(b'\0\x11\x22\x33'))+chunk(b'IEND',b'')


def main():
    engine=Engine();suffix=uuid.uuid4().hex[:12];project='field-notes-test-'+suffix
    temporary=pathlib.Path(tempfile.mkdtemp(prefix=project+'-',dir=ROOT/'dist'))
    settings=None;peer_id=None;external_id=None;fixture_tag='field-notes-gateway-fixture:'+suffix
    gateway_image='field-notes-gateway:0.3.0';peer_dir=temporary/'peer'
    private_dirs=[]
    try:
        build=temporary/'fixture-image';build.mkdir()
        shutil.copy2(ROOT/'dist/container-test/wireguard-go',build/'wireguard-go')
        (build/'entry.py').write_text("import os,sys\nos.makedirs('/dev/net',exist_ok=True)\nif not os.path.exists('/dev/net/tun'):os.mknod('/dev/net/tun',0o20600,os.makedev(10,200))\nsys.path.insert(0,'/usr/local/lib/field-notes')\nimport gateway\ngateway.main()\n")
        (build/'Dockerfile').write_text('FROM '+gateway_image+'\nRUN --mount=type=secret,id=build_ca,required=true,mode=0444 apt-get -o Acquire::https::CaInfo=/run/secrets/build_ca update && apt-get -o Acquire::https::CaInfo=/run/secrets/build_ca install -y --no-install-recommends python3 && rm -rf /var/lib/apt/lists/*\nCOPY wireguard-go /usr/local/bin/wireguard-go\nCOPY entry.py /fixture-entry.py\nRUN chmod 755 /usr/local/bin/wireguard-go\nENTRYPOINT ["python3","-B","/fixture-entry.py"]\n')
        env=os.environ.copy();env['BUILDX_CONFIG']=env.get('BUILDX_CONFIG',str(ROOT.parent/'.cache/field-notes/docker-buildx'))
        from container_deployment import DOCKER,SELECTORS
        env={k:v for k,v in env.items() if k not in SELECTORS}
        subprocess.run([DOCKER,'--host=unix:///var/run/docker.sock','build','--secret','id=build_ca,src=/etc/ssl/certs/ca-certificates.crt','--tag',fixture_tag,str(build)],check=True,env=env,stdout=subprocess.DEVNULL)
        fixture_id=engine.read(['image','inspect',fixture_tag])[0]['Id']
        images=json.loads((ROOT/'dist/containers/images.json').read_text())
        data=temporary/'data';config=temporary/'config';runtime=temporary/'runtime'
        for p in (data,config,runtime):p.mkdir(mode=0o750)
        key=config/'master.key'
        engine.call(['run','--interactive','--rm','--network=none','--read-only','--cap-drop=ALL',
            '--security-opt=no-new-privileges:true','--user',f'{os.getuid()}:{os.getgid()}',
            '--mount',f'type=bind,source={data},target=/state',
            '--mount',f'type=bind,source={config},target=/keys',images['app']['id'],
            'init','--data','/state','--key-file','/keys/master.key','--name','owner'],input='container-fixture-password-only')
        tls=module('container_tls',HERE/'tls.py');tls.provision(config/'tls',['172.17.0.1','10.82.0.1'],issued_subdirectory=True)
        web,udp=port(),port()
        settings=dict(version=1,installation=str(uuid.uuid4()),project=project,prefix=str(temporary/'program'),
                      data=str(data),config=str(config),runtime=str(runtime),uid=os.getuid(),gid=os.getgid(),
                      lan_ip='172.17.0.1',lan_prefix='172.16.0.0/12',overlay='10.82.0.0/24',
                      web_port=web,udp_port=udp,images={'app':images['app']['id'],'gateway':fixture_id})
        gateway=config/'gateway';gateway.mkdir(mode=0o700);private_dirs.append(gateway)
        private=engine.call(['run','--rm','--network=none','--cap-drop=ALL','--entrypoint=wg',gateway_image,'genkey']).stdout.strip()
        public=engine.call(['run','--interactive','--rm','--network=none','--cap-drop=ALL','--entrypoint=wg',gateway_image,'pubkey'],input=private+'\n').stdout.strip()
        (gateway/'fieldnotes.conf').write_text(f'[Interface]\nAddress = 10.82.0.1/24\nListenPort = {udp}\nPrivateKey = {private}\nMTU = 1280\n')
        (gateway/'fieldnotes.conf').chmod(0o600)
        (gateway/'listeners.json').write_text(json.dumps({k:settings[k] for k in ('overlay','lan_prefix','web_port','udp_port')}));(gateway/'listeners.json').chmod(0o600)
        network=dict(bootstrap_url=f'https://172.17.0.1:{web}/api/enrollment',endpoint=f'172.17.0.1:{udp}',
                     lan_endpoint=f'172.17.0.1:{udp}',server_key=public,api='https://10.82.0.1:8443',
                     ca_pem=(config/'tls/ca.crt').read_text(),overlay=settings['overlay'])
        (config/'network.json').write_text(json.dumps(network));(config/'network.json').chmod(0o640)
        def ownership(path,to,gid=None):
            gid=to if gid is None else gid
            code='import os;os.chown("/fixture",'+str(to)+','+str(gid)+');\nfor p,ds,fs in os.walk("/fixture"):\n for n in ds+fs:os.chown(os.path.join(p,n),'+str(to)+','+str(gid)+')'
            engine.call(['run','--rm','--network=none','--cap-drop=ALL','--cap-add=CHOWN','--mount','type=bind,source='+str(path)+',target=/fixture','--entrypoint=python3',gateway_image,'-c',code])
        ownership(gateway,0)
        specification=compose(settings)
        specification['services']['gateway']['cap_add'].append('MKNOD')
        specification['services']['gateway']['device_cgroup_rules']=['c 10:200 rwm']
        file=config/'compose.json';file.write_text(json.dumps(specification))
        try:engine.start(settings,file)
        except subprocess.CalledProcessError as error:
            print(error.stderr,file=sys.stderr)
            for role in ('gateway','app'):
                try:
                    logs=engine.call(['logs',project+'-'+role]);print(logs.stdout+logs.stderr,file=sys.stderr)
                except subprocess.CalledProcessError as log_error:print(log_error.stderr,file=sys.stderr)
            raise
        objects=engine.objects(settings);app=objects['app'];gw=objects['gateway']
        assert app['Config']['User']==f'{os.getuid()}:{os.getgid()}'
        assert app['HostConfig']['ReadonlyRootfs'] and app['HostConfig']['CapDrop']==['ALL']
        assert not app['HostConfig']['CapAdd'] and not app['HostConfig']['Privileged']
        assert app['HostConfig']['Memory']==512*1024*1024
        mounts={m['Destination']:m for m in app['Mounts']}
        assert '/private' not in mounts and '/var/run/docker.sock' not in mounts
        assert mounts['/tls']['Source']==str(config/'tls/issued') and not mounts['/tls']['RW']
        assert mounts['/secrets/master.key']['RW'] is False
        print('PASS: actual nonroot/read-only app, dropped capabilities, memory bound and private-key mount separation',flush=True)
        external_id=engine.call(['run','--detach','--name',project+'-external','--network=bridge','--cap-drop=ALL','--user=10001:10001','--read-only','--entrypoint=python3',fixture_tag,'-m','http.server','8080','--bind','0.0.0.0']).stdout.strip()
        external_ip=engine.read(['container','inspect',external_id])[0]['NetworkSettings']['Networks']['bridge']['IPAddress']
        assert external_ip,'Controlled external endpoint did not start'
        context=ssl.create_default_context(cafile=str(config/'tls/ca.crt'));context.minimum_version=ssl.TLSVersion.TLSv1_3
        opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPSHandler(context=context),urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        anonymous=urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPSHandler(context=context))
        origin=f'https://172.17.0.1:{web}';csrf=None
        def request(path,body=None):
            headers={'Content-Type':'application/json'}
            bootstrap=path in ('/api/enrollment/claim','/api/enrollment/status','/api/enrollment/cancel')
            if csrf and not bootstrap:headers['X-Field-CSRF']=csrf
            req=urllib.request.Request(origin+path,data=json.dumps(body).encode() if body is not None else None,headers=headers)
            try:
                with (anonymous if bootstrap else opener).open(req,timeout=10) as response:return response.status,json.load(response)
            except urllib.error.HTTPError as error:return error.code,json.load(error)
        status,login=request('/api/login',{'name':'owner','password':'container-fixture-password-only','device':'Isolated container fixture'})
        assert status==200,'Management TLS/login did not work'
        csrf=login['csrf'];actor=login['actor']
        assert request('/api/reauth',{'password':'container-fixture-password-only'})[0]==200
        status,invitation=request('/api/enrollment/invitations',{'user_id':actor['user_id']});assert status==200
        payload=json.loads(base64.urlsafe_b64decode(invitation['code'].split('#')[1]+'=='))
        client_private=engine.call(['run','--rm','--network=none','--cap-drop=ALL','--entrypoint=wg',gateway_image,'genkey']).stdout.strip()
        client_public=engine.call(['run','--interactive','--rm','--network=none','--cap-drop=ALL','--entrypoint=wg',gateway_image,'pubkey'],input=client_private+'\n').stdout.strip()
        secret=secrets.token_hex(32)
        status,claim=request('/api/enrollment/claim',{'token':payload['token'],'claim_secret':secret,'public_key':client_public,'name':'Container phone fixture'});assert status==200
        assert request('/api/enrollment/devices/'+claim['id']+'/approve',{'verification':claim['verification']})[0]==200
        helper=module('container_peers',HERE/'peer-agent.py')
        cfg={'desired':str(data/'peers.json'),'status':str(runtime/'status.json'),'state':str(temporary/'helper-state'),
             'interface':'fieldnotes','overlay':settings['overlay'],'uid':os.getuid(),'gid':os.getgid()}
        helper.reconcile(cfg,engine.peer_runner(settings))
        # Production acknowledgements are written by the root host helper. The
        # app deliberately refuses an acknowledgement owned by its own UID.
        ownership(runtime/'status.json',0,os.getgid())
        status,ready=request('/api/enrollment/status',{'id':claim['id'],'claim_secret':secret});assert status==200 and ready['state']=='ready'
        peer_dir.mkdir(mode=0o700);private_dirs.append(peer_dir)
        shutil.copy2(config/'tls/ca.crt',peer_dir/'ca.crt')
        phone=ready['configuration']
        (peer_dir/'phone.conf').write_text(f'[Interface]\nAddress = 10.82.0.2/32\nPrivateKey = {client_private}\nMTU = 1280\n[Peer]\nPublicKey = {public}\nPresharedKey = {phone["preshared_key"]}\nEndpoint = 172.17.0.1:{udp}\nAllowedIPs = 10.82.0.0/24, {external_ip}/32\nPersistentKeepalive = 25\n')
        (peer_dir/'phone.conf').chmod(0o600);ownership(peer_dir,0)
        command=['run','--detach','--name',project+'-phone','--network=bridge','--cap-drop=ALL','--cap-add=NET_ADMIN','--cap-add=MKNOD','--device-cgroup-rule=c 10:200 rwm','--read-only','--tmpfs=/run:mode=0700','--tmpfs=/tmp:size=8m','--mount','type=bind,source='+str(peer_dir)+',target=/peer,readonly']
        for k,v in PRIVATE_ENV.items():command+=['--env',k+'='+v]
        command+=['--entrypoint=sh',fixture_tag,'-c','mkdir -p /dev/net && mknod /dev/net/tun c 10 200 && wg-quick up /peer/phone.conf && sleep 900']
        peer_id=engine.call(command).stdout.strip()
        script='''import json,sys,ssl,urllib.request,urllib.error
c=json.load(sys.stdin);ctx=ssl.create_default_context(cafile='/peer/ca.crt');ctx.minimum_version=ssl.TLSVersion.TLSv1_3
op=urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPSHandler(context=ctx))
h={'Content-Type':'application/json'}
if c.get('token'):h['Authorization']='Bearer '+c['token']
r=urllib.request.Request('https://10.82.0.1:8443'+c['path'],data=json.dumps(c['body']).encode() if c.get('body') is not None else None,headers=h)
try:
 with op.open(r,timeout=8) as response:print(json.dumps({'status':response.status,'body':json.load(response)}))
except urllib.error.HTTPError as e:
 raw=e.read();print(json.dumps({'status':e.code,'body':json.loads(raw) if raw else {}}))
'''
        def peer(path,body=None,token=None):
            result=engine.call(['exec','-i',peer_id,'python3','-c',script],input=json.dumps(dict(path=path,body=body,token=token)))
            return json.loads(result.stdout)
        activated=None
        for attempt in range(20):
            try:
                activated=peer('/api/device/activate',{'id':claim['id'],'activation_secret':ready['activation_secret']});break
            except subprocess.CalledProcessError:time.sleep(.5)
        if not activated or activated['status']!=200:
            logs=engine.call(['logs',peer_id]);print(logs.stdout+logs.stderr,file=sys.stderr)
            print('Activation status:',activated and activated['status'],file=sys.stderr)
            for identifier,interface in ((gw['Id'],'fieldnotes'),(peer_id,'phone')):
                for field in ('allowed-ips','endpoints','latest-handshakes','transfer'):
                    result=engine.call(['exec',identifier,'wg','show',interface,field]);print(field,result.stdout,file=sys.stderr)
            raise AssertionError('Source-bound activation through real WireGuard failed')
        token=activated['body']['token'];snapshot=peer('/api/snapshot',token=token)['body']
        def send(action,target,content):
            return peer('/api/commands',dict(operation_id=str(uuid.uuid4()),epoch=snapshot['epoch'],generation=snapshot['generation'],action=action,target=target,expected_revision=None,data=content),token)
        task=str(uuid.uuid4());assert send('task.create',task,{'title':'Private container task','priority':1})['status']==200
        image=str(uuid.uuid4());assert send('image.create',image,{'task_id':task,'name':'fixture.png','bytes':base64.b64encode(png()).decode()})['status']==200
        assert peer('/api/enrollment/devices',token=token)['status'] in (403,404)
        print('PASS: direct management TLS, approval, real private WireGuard activation, task/image writes and private-route enforcement',flush=True)
        deny='''import socket,json
results=[]
for host,port in [('10.82.0.1',%d),('10.82.0.1',22),('10.82.0.3',8443),('%s',8080)]:
 s=socket.socket();s.settimeout(1)
 try:s.connect((host,port));results.append(False)
 except OSError:results.append(True)
 finally:s.close()
print(json.dumps(results))
'''%(web,external_ip)
        assert all(json.loads(engine.call(['exec',peer_id,'python3','-c',deny]).stdout))
        print('PASS: modified phone cannot reach management, SSH, another peer or the host LAN through the tunnel',flush=True)
        def counters():
            nft=json.loads(engine.call(['exec',gw['Id'],'nft','-j','list','chain','inet','fieldnotes','output']).stdout)
            return sum(expr.get('counter',{}).get('packets',0) for row in nft['nftables'] for expr in row.get('rule',{}).get('expr',[]))
        before=counters()
        probe='''import socket,json,os
s=socket.socket();s.settimeout(1)
try:s.connect(('%s',8080));raise Exception('Unexpected egress')
except OSError:pass
s.close()
s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM)
try:s.sendto(b'blocked',('127.0.0.11',53))
except OSError:pass
s.close()
print('blocked')
'''
        probe=probe%external_ip
        probe_args=['run','--rm','--network=container:'+gw['Id'],'--user',f'{os.getuid()}:{os.getgid()}','--cap-drop=ALL','--read-only','--entrypoint=python3',fixture_tag,'-c',probe]
        try:assert engine.call(probe_args).stdout.strip()=='blocked'
        except subprocess.CalledProcessError as error:
            print(error.stderr,file=sys.stderr);raise
        assert counters()>=before+2,'Egress attempts did not hit the enclave deny rules'
        print('PASS: actual app-UID outbound TCP and Docker DNS attempts hit firewall drop counters',flush=True)
        engine.stop(settings)
        print('PASS: owned app/gateway/network removal leaves no deployment resources',flush=True)
    except Exception:
        if settings:
            for role in ('app','gateway'):
                try:
                    logs=engine.call(['logs',project+'-'+role]);print(logs.stdout+logs.stderr,file=sys.stderr)
                except Exception:pass
            try:print(engine.call(['exec',project+'-gateway','nft','list','ruleset']).stdout,file=sys.stderr)
            except Exception:pass
        raise
    finally:
        if external_id:
            try:engine.call(['container','rm','--force',external_id])
            except Exception:pass
        if peer_id:
            try:engine.call(['container','rm','--force',peer_id])
            except Exception:pass
        if settings:
            try:engine.stop(settings)
            except Exception:pass
        for folder in private_dirs:
            if folder.exists():
                try:
                    code=f'import os;os.chown("/fixture",{os.getuid()},{os.getgid()});\nfor p,ds,fs in os.walk("/fixture"):\n for n in ds+fs:os.chown(os.path.join(p,n),{os.getuid()},{os.getgid()})'
                    engine.call(['run','--rm','--network=none','--cap-drop=ALL','--cap-add=CHOWN','--mount','type=bind,source='+str(folder)+',target=/fixture','--entrypoint=python3',gateway_image,'-c',code])
                except Exception:pass
        try:engine.call(['image','rm',fixture_tag])
        except Exception:pass
        shutil.rmtree(temporary)


if __name__=='__main__':
    main()
