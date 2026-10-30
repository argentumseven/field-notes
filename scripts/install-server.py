#!/usr/bin/env python3
"""Fresh-install Linux helper. --plan performs validation and writes nothing.
It never replaces an existing installation. HTTPS is served directly by Rust.
"""
import argparse, getpass, ipaddress, json, os, pathlib, pwd, re, shutil, socket, subprocess, sys, time, urllib.request

def arguments():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--apply',action='store_true');p.add_argument('--plan',action='store_true')
    p.add_argument('--lan-ip',required=True);p.add_argument('--lan-prefix',required=True)
    p.add_argument('--web-port',type=int,default=9443);p.add_argument('--endpoint');p.add_argument('--hostname',default='notes.home.arpa');p.add_argument('--udp-port',type=int,default=51831)
    p.add_argument('--prefix',default='/opt/field-notes');p.add_argument('--data',default='/var/lib/field-notes')
    p.add_argument('--config',default='/etc/field-notes')
    p.add_argument('--overlay',default='10.82.0.0/24');p.add_argument('--binary');return p.parse_args()

def checked(cmd,**kwargs):return subprocess.run(cmd,check=True,**kwargs)
def main():
    a=arguments();lan=ipaddress.IPv4Address(a.lan_ip);prefix=ipaddress.IPv4Network(a.lan_prefix);overlay=ipaddress.IPv4Network(a.overlay)
    private=[ipaddress.IPv4Network(x) for x in ('10.0.0.0/8','172.16.0.0/12','192.168.0.0/16')]
    if not any(prefix.subnet_of(n) for n in private) or lan not in prefix or not any(overlay.subnet_of(n) for n in private) or overlay.prefixlen!=24 or overlay.overlaps(prefix):raise ValueError('Use a private LAN address within its prefix and a nonoverlapping private /24 overlay.')
    if not re.fullmatch(r'[a-z0-9]+(?:[.-][a-z0-9]+)+',a.hostname) or len(a.hostname)>200:raise ValueError('Invalid hostname')
    if not 1024<=a.udp_port<=65535 or not 1024<=a.web_port<=65535 or a.web_port==8443:raise ValueError('Choose unprivileged ports; reserve 8443 for the tunnel API')
    endpoint=a.endpoint or f'{lan}:{a.udp_port}'
    if not re.fullmatch(r'[A-Za-z0-9.-]+:[0-9]{1,5}',endpoint) or not 1<=int(endpoint.rsplit(':',1)[1])<=65535:raise ValueError('Invalid reachable WireGuard endpoint')
    destinations=[pathlib.Path(x) for x in (a.prefix,a.data,a.config)]
    if a.apply and pathlib.Path('/var/lib/field-notes-peer-helper').exists():raise ValueError('Existing peer-helper state found; use the documented upgrade/recovery procedure instead of a fresh install.')
    for d in destinations:
        if not re.fullmatch(r'/[A-Za-z0-9/_.-]+',str(d)) or not d.is_absolute() or '..' in d.parts or len(d.parts)<3 or d.is_symlink():raise ValueError('Use explicit absolute installation directories without symlinks or traversal')
        if d.exists():raise ValueError(f'Existing destination refused: {d}. Back up and use a reviewed upgrade procedure.')
        for other in destinations:
            if other!=d and other in d.parents:raise ValueError('Installation directories must not overlap')
    for ip in ('127.0.0.1',str(lan)):
        if ipaddress.ip_address(ip) in overlay:raise ValueError('Overlay conflicts with host addresses')
    root=pathlib.Path(__file__).resolve().parents[1]
    binary=pathlib.Path(a.binary) if a.binary else (root/'field-notes-server' if (root/'field-notes-server').exists() else root/'target/release/field-notes-server')
    plan={'binary':str(binary),'lan_admin':f'https://{lan}:{a.web_port} (source {prefix})','device_api':f'https://{overlay.network_address+1}:8443','router_forward':f'UDP {a.udp_port} to {lan}:{a.udp_port}','directories':[str(p) for p in destinations],'services':['field-notes','wg-quick@fieldnotes','field-notes-firewall','field-notes-peers.timer','field-notes-certificates.timer'],'no_public_web':True,'remote_access':bool(a.endpoint),'endpoint_note':'Provide --endpoint your-home-DDNS:51831 for access away from home; the LAN UDP alternative is enrolled automatically.'}
    if not a.apply:print(json.dumps(plan,indent=2));return
    if os.geteuid()!=0:raise ValueError('--apply requires root; review --plan first')
    if not binary.is_file():raise ValueError('Build the release binary with scripts/build/server.sh first')
    checked([str(binary),'help'],stdout=subprocess.DEVNULL)
    for name in ('fieldnotes',):
        try:pwd.getpwnam(name)
        except KeyError:pass
        else:raise ValueError(f'Existing service account {name}; fresh installation refused')
    os_release=pathlib.Path('/etc/os-release').read_text()
    if not re.search(r'^ID=(?:debian|ubuntu)$',os_release,re.M):raise ValueError('This installer supports Debian/Ubuntu only')
    for path in [pathlib.Path('/etc/wireguard/fieldnotes.conf'),*[pathlib.Path('/etc/systemd/system')/name for name in ['field-notes.service','field-notes-peers.service','field-notes-peers.timer','field-notes-firewall.service','field-notes-certificates.service','field-notes-certificates.timer']]]:
        if path.exists():raise ValueError(f'Existing configuration refused: {path}')
    for port,kind in [(a.web_port,socket.SOCK_STREAM),(a.udp_port,socket.SOCK_DGRAM)]:
        with socket.socket(socket.AF_INET,kind) as sock:sock.bind((str(lan),port))
    password=getpass.getpass('Initial owner password (12+ characters): ')
    if len(password.encode())<12 or len(password.encode())>256:raise ValueError('Password length must be 12–256 bytes')
    if password!=getpass.getpass('Confirm password: '):raise ValueError('Passwords do not match')
    name=input('Owner user name [owner]: ').strip() or 'owner'
    if not re.fullmatch(r'[A-Za-z0-9._ -]{2,64}',name):raise ValueError('Use an owner name with 2–64 letters, numbers, spaces, dots, underscores or hyphens')
    checked(['apt-get','update']);checked(['apt-get','install','-y','wireguard-tools','nftables','openssl'])
    install,data,config=destinations
    for p in destinations:p.mkdir(mode=0o700,parents=True)
    checked(['useradd','--system','--home-dir',str(data),'--shell','/usr/sbin/nologin','fieldnotes'])
    user=pwd.getpwnam('fieldnotes')
    os.chown(data,user.pw_uid,user.pw_gid)
    install.chmod(0o755);os.chown(config,0,user.pw_gid);config.chmod(0o750)
    shutil.copy2(binary,install/'field-notes-server');(install/'field-notes-server').chmod(0o755)
    checked([str(install/'field-notes-server'),'init','--data',str(data),'--key-file',str(config/'master.key'),'--name',name],input=password.encode())
    for path in data.iterdir():os.chown(path,user.pw_uid,user.pw_gid);path.chmod(0o600)
    os.chown(config/'master.key',0,user.pw_gid);(config/'master.key').chmod(0o640)
    shutil.copy2(root/'scripts/tls.py',install/'tls.py');(install/'tls.py').chmod(0o755)
    private=checked(['wg','genkey'],capture_output=True,text=True).stdout.strip()
    wgfile=pathlib.Path('/etc/wireguard/fieldnotes.conf')
    if wgfile.exists():raise ValueError('Existing WireGuard interface configuration; refusing replacement')
    wgfile.parent.mkdir(mode=0o700,exist_ok=True)
    fd=os.open(wgfile,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
    with os.fdopen(fd,'w') as f:f.write(f'[Interface]\nAddress = {overlay.network_address+1}/24\nListenPort = {a.udp_port}\nPrivateKey = {private}\n')
    del private
    nft=f'''table inet fieldnotes {{
 chain input {{
  type filter hook input priority -10; policy accept;
  iifname "fieldnotes" ip daddr {overlay.network_address+1} tcp dport 8443 accept
  iifname "fieldnotes" drop
  ip daddr {lan} tcp dport {a.web_port} ip saddr != {prefix} drop
 }}
 chain forward {{ type filter hook forward priority -10; policy accept; iifname "fieldnotes" drop; oifname "fieldnotes" drop; }}
}}
'''
    (config/'firewall.nft').write_text(nft)
    firewall='''#!/usr/bin/python3
import subprocess,pathlib
exists=subprocess.run(['/usr/sbin/nft','list','table','inet','fieldnotes'],capture_output=True).returncode==0
rules=('delete table inet fieldnotes\\n' if exists else '')+pathlib.Path(CONFIG).read_text()
subprocess.run(['/usr/sbin/nft','-f','-'],input=rules,text=True,check=True)
'''.replace('CONFIG',repr(str(config/'firewall.nft')))
    (install/'firewall.py').write_text(firewall);(install/'firewall.py').chmod(0o755)
    shutil.copy2(root/'scripts/tls.py',install/'tls.py')
    checked(['python3',str(install/'tls.py'),'init','--directory',str(config/'tls'),'--group','fieldnotes','--name',str(lan),'--name',a.hostname,'--name',str(overlay.network_address+1)])
    server_public=checked(['wg','pubkey'],input=wgfile.read_text().split('PrivateKey = ',1)[1].splitlines()[0].encode(),capture_output=True).stdout.decode().strip()
    public_config={'bootstrap_url':f'https://{lan}:{a.web_port}/api/enrollment','endpoint':endpoint,'lan_endpoint':f'{lan}:{a.udp_port}','server_key':server_public,'api':f'https://{overlay.network_address+1}:8443','ca_pem':(config/'tls/ca.crt').read_text(),'overlay':str(overlay)}
    (config/'network.json').write_text(json.dumps(public_config));os.chown(config/'network.json',0,user.pw_gid);(config/'network.json').chmod(0o640)
    helper_state=pathlib.Path('/var/lib/field-notes-peer-helper');helper_state.mkdir(mode=0o700)
    helper_config={'desired':str(data/'peers.json'),'status':'/run/field-notes-peers/status.json','state':str(helper_state/'state.json'),'interface':'fieldnotes','overlay':str(overlay),'uid':user.pw_uid,'gid':user.pw_gid}
    (config/'peer-helper.json').write_text(json.dumps(helper_config));(config/'peer-helper.json').chmod(0o600)
    shutil.copy2(root/'scripts/peer-agent.py',install/'peer-agent.py')
    app_unit=(root/'deploy/field-notes.service').read_text().replace('/opt/field-notes',str(install)).replace('/var/lib/field-notes',str(data)).replace('/etc/field-notes',str(config)).replace('192.168.1.10',str(lan)).replace('192.168.1.0/24',str(prefix)).replace('10.82.0.1',str(overlay.network_address+1)).replace(':9443',f':{a.web_port}')
    systemd=pathlib.Path('/etc/systemd/system')
    units={'field-notes.service':app_unit,'field-notes-firewall.service':f'''[Unit]
Description=Field Notes peer isolation
Before=wg-quick@fieldnotes.service field-notes.service
[Service]
Type=oneshot
RemainAfterExit=yes
ExecStart={install}/firewall.py
[Install]
WantedBy=multi-user.target
''','field-notes-peers.service':f'''[Unit]
Description=Apply validated Field Notes peer enrollment
StartLimitIntervalSec=0
After=wg-quick@fieldnotes.service
Requires=wg-quick@fieldnotes.service
[Service]
Type=oneshot
TimeoutStartSec=30
User=root
Group=fieldnotes
ExecStart=/usr/bin/python3 {install}/peer-agent.py --config {config}/peer-helper.json
RuntimeDirectory=field-notes-peers
RuntimeDirectoryMode=0750
RuntimeDirectoryPreserve=yes
UMask=0077
NoNewPrivileges=yes
ProtectSystem=strict
ProtectHome=yes
PrivateTmp=yes
CapabilityBoundingSet=CAP_NET_ADMIN CAP_DAC_OVERRIDE CAP_CHOWN CAP_FOWNER
ReadWritePaths=/etc/wireguard /run/field-notes-peers {helper_state}
LimitCORE=0
''','field-notes-peers.timer':'''[Unit]
Description=Reconcile Field Notes peers
[Timer]
OnBootSec=3
OnUnitActiveSec=2
AccuracySec=1
[Install]
WantedBy=timers.target
'''}
    units['field-notes-certificates.service']=f"""[Unit]
Description=Renew the local Field Notes TLS certificate when needed
[Service]
Type=oneshot
User=root
Group=fieldnotes
ExecStart=/usr/bin/python3 {install}/tls.py renew --if-needed --directory {config}/tls --group fieldnotes --name {lan} --name {a.hostname} --name {overlay.network_address+1}
UMask=0077
NoNewPrivileges=yes
ProtectSystem=strict
ProtectHome=yes
PrivateTmp=yes
PrivateNetwork=yes
CapabilityBoundingSet=
ReadWritePaths={config}/tls
LimitCORE=0
"""
    units['field-notes-certificates.timer']="""[Unit]
Description=Check the local Field Notes certificate daily
[Timer]
OnBootSec=10min
OnCalendar=daily
RandomizedDelaySec=1h
Persistent=yes
[Install]
WantedBy=timers.target
"""
    for filename,contents in units.items():
        target=systemd/filename
        if target.exists():raise ValueError(f'Existing unit refused: {target}')
        target.write_text(contents)
    checked(['systemd-analyze','verify',*[str(systemd/filename) for filename in units]])
    checked(['systemctl','daemon-reload']);checked(['systemctl','enable','--now','field-notes-firewall','wg-quick@fieldnotes','field-notes','field-notes-peers.timer','field-notes-certificates.timer'])
    checked(['systemctl','is-active','field-notes','field-notes-peers.timer','field-notes-certificates.timer'])
    import ssl,http.cookiejar
    context=ssl.create_default_context(cafile=str(config/'tls/ca.crt'));context.minimum_version=ssl.TLSVersion.TLSv1_3
    opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPSHandler(context=context),urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    failure=None
    for attempt in range(30):
        try:
            request=urllib.request.Request(f'https://{lan}:{a.web_port}/api/login',data=json.dumps({'name':name,'password':password,'device':'Installer readiness check'}).encode(),headers={'Content-Type':'application/json'})
            with opener.open(request,timeout=3) as response:login=json.load(response)
            if login['actor']['role']!='owner':raise ValueError('Authenticated readiness returned an unexpected role')
            with opener.open(f'https://{lan}:{a.web_port}/api/snapshot',timeout=3) as response:snapshot=json.load(response)
            if snapshot['actor']['user_id']!=login['actor']['user_id']:raise ValueError('Readiness account mismatch')
            logout=urllib.request.Request(f'https://{lan}:{a.web_port}/api/logout',data=b'{}',headers={'Content-Type':'application/json','X-Field-CSRF':login['csrf']})
            with opener.open(logout,timeout=3) as response:response.read()
            failure=None;break
        except (OSError,ValueError,KeyError) as e:failure=e;time.sleep(.2)
    del password
    if failure is not None:raise ValueError('Services started but authenticated readiness failed; inspect service logs before using the installation')
    print(json.dumps(plan,indent=2));print('Configure the one UDP forward. Verify and trust the public CA on administrator browsers; phones pin it through enrollment.')
    print('Public CA:',config/'tls/ca.crt')
    print('Open the management URL, create household users, and use Devices → Enroll device. No Caddy, proxy or load balancer is installed.')

if __name__=='__main__':
    try:main()
    except (ValueError,subprocess.CalledProcessError,OSError) as e:print(f'Installation stopped: {e}',file=sys.stderr);sys.exit(1)
