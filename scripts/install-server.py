#!/usr/bin/env python3
"""Fresh-install Linux helper. --plan performs validation and writes nothing.
It never replaces an existing installation or modifies an existing Caddy site.
"""
import argparse, getpass, ipaddress, json, os, pathlib, pwd, re, shutil, socket, subprocess, sys, time, urllib.request

def arguments():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--apply',action='store_true');p.add_argument('--plan',action='store_true')
    p.add_argument('--lan-ip',required=True);p.add_argument('--lan-prefix',required=True)
    p.add_argument('--hostname',default='notes.home.arpa');p.add_argument('--udp-port',type=int,default=51831)
    p.add_argument('--prefix',default='/opt/field-notes');p.add_argument('--data',default='/var/lib/field-notes')
    p.add_argument('--config',default='/etc/field-notes');p.add_argument('--tls-data',default='/var/lib/field-notes-tls')
    p.add_argument('--overlay',default='10.82.0.0/24');p.add_argument('--binary');return p.parse_args()

def checked(cmd,**kwargs):return subprocess.run(cmd,check=True,**kwargs)
def main():
    a=arguments();lan=ipaddress.IPv4Address(a.lan_ip);prefix=ipaddress.IPv4Network(a.lan_prefix);overlay=ipaddress.IPv4Network(a.overlay)
    if not lan.is_private or lan not in prefix or not overlay.is_private or overlay.prefixlen!=24 or overlay.overlaps(prefix):raise ValueError('Use a private LAN address within its prefix and a nonoverlapping private /24 overlay.')
    if not re.fullmatch(r'[a-z0-9]+(?:[.-][a-z0-9]+)+',a.hostname) or len(a.hostname)>200:raise ValueError('Invalid hostname')
    if not 1024<=a.udp_port<=65535:raise ValueError('Choose a UDP port between 1024 and 65535')
    destinations=[pathlib.Path(x) for x in (a.prefix,a.data,a.config,a.tls_data)]
    for d in destinations:
        if not d.is_absolute() or '..' in d.parts or len(d.parts)<3 or d.is_symlink():raise ValueError('Use explicit absolute installation directories without symlinks or traversal')
        if d.exists():raise ValueError(f'Existing destination refused: {d}. Back up and use a reviewed upgrade procedure.')
        for other in destinations:
            if other!=d and other in d.parents:raise ValueError('Installation directories must not overlap')
    for ip in ('127.0.0.1',str(lan)):
        if ipaddress.ip_address(ip) in overlay:raise ValueError('Overlay conflicts with host addresses')
    root=pathlib.Path(__file__).resolve().parents[1]
    binary=pathlib.Path(a.binary) if a.binary else (root/'field-notes-server' if (root/'field-notes-server').exists() else root/'target/release/field-notes-server')
    plan={'binary':str(binary),'lan_admin':f'https://{a.hostname} (bind {lan}:443, source {prefix})','device_api':f'https://{overlay.network_address+1}:8443','router_forward':f'UDP {a.udp_port} to {lan}:{a.udp_port}','directories':[str(p) for p in destinations],'services':['field-notes','wg-quick@fieldnotes','field-notes-firewall','field-notes-tls'],'no_public_web':True}
    if not a.apply:print(json.dumps(plan,indent=2));return
    if os.geteuid()!=0:raise ValueError('--apply requires root; review --plan first')
    if not binary.is_file():raise ValueError('Build the release binary with scripts/build/server.sh first')
    checked([str(binary),'help'],stdout=subprocess.DEVNULL)
    for name in ('fieldnotes','fieldnotes-tls'):
        try:pwd.getpwnam(name)
        except KeyError:pass
        else:raise ValueError(f'Existing service account {name}; fresh installation refused')
    os_release=pathlib.Path('/etc/os-release').read_text()
    if not re.search(r'^ID=(?:debian|ubuntu)$',os_release,re.M):raise ValueError('This installer supports Debian/Ubuntu only')
    for path in [pathlib.Path('/etc/wireguard/fieldnotes.conf'),*[pathlib.Path('/etc/systemd/system')/name for name in ['field-notes.service','field-notes-tls.service','field-notes-firewall.service']]]:
        if path.exists():raise ValueError(f'Existing configuration refused: {path}')
    for port,kind in [(443,socket.SOCK_STREAM),(a.udp_port,socket.SOCK_DGRAM)]:
        with socket.socket(socket.AF_INET,kind) as sock:sock.bind((str(lan),port))
    password=getpass.getpass('Initial owner password (12+ characters): ')
    if len(password.encode())<12 or len(password.encode())>256:raise ValueError('Password length must be 12–256 bytes')
    if password!=getpass.getpass('Confirm password: '):raise ValueError('Passwords do not match')
    name=input('Owner user name [owner]: ').strip() or 'owner'
    if not re.fullmatch(r'[A-Za-z0-9._ -]{2,64}',name):raise ValueError('Use an owner name with 2–64 letters, numbers, spaces, dots, underscores or hyphens')
    new_caddy=shutil.which('caddy') is None
    # Do not let installing Caddy start an unrelated default HTTP listener.
    if new_caddy:checked(['systemctl','mask','--runtime','caddy.service'])
    checked(['apt-get','update']);checked(['apt-get','install','-y','wireguard-tools','nftables','caddy'])
    if new_caddy:
        checked(['systemctl','disable','caddy.service'])
        checked(['systemctl','unmask','--runtime','caddy.service'])
    install,data,config,tls_data=destinations
    for p in destinations:p.mkdir(mode=0o700,parents=True)
    for name,home in [('fieldnotes',data),('fieldnotes-tls',tls_data)]:checked(['useradd','--system','--home-dir',str(home),'--shell','/usr/sbin/nologin',name])
    user=pwd.getpwnam('fieldnotes');tls_user=pwd.getpwnam('fieldnotes-tls')
    for d,u in [(data,user),(tls_data,tls_user)]:os.chown(d,u.pw_uid,u.pw_gid)
    install.chmod(0o755);config.chmod(0o755)
    shutil.copy2(binary,install/'field-notes-server');(install/'field-notes-server').chmod(0o755)
    checked([str(install/'field-notes-server'),'init','--data',str(data),'--key-file',str(config/'master.key'),'--name',name],input=password.encode())
    for path in [config/'master.key',*data.iterdir()]:os.chown(path,user.pw_uid,user.pw_gid);path.chmod(0o600)
    shutil.copy2(root/'scripts/peer.py',install/'peer.py');(install/'peer.py').chmod(0o755)
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
  ip daddr {lan} tcp dport 443 ip saddr != {prefix} drop
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
    caddy=(root/'deploy/Caddyfile.example').read_text().replace('192.168.1.10',str(lan)).replace('notes.home.arpa',a.hostname).replace('10.82.0.1',str(overlay.network_address+1))
    (config/'Caddyfile').write_text(caddy);(config/'Caddyfile').chmod(0o644)
    app_unit=(root/'deploy/field-notes.service').read_text().replace('/opt/field-notes',str(install)).replace('/var/lib/field-notes',str(data)).replace('/etc/field-notes',str(config)).replace('notes.home.arpa',a.hostname)
    systemd=pathlib.Path('/etc/systemd/system')
    units={'field-notes.service':app_unit,'field-notes-firewall.service':f'''[Unit]
Description=Field Notes peer isolation
Before=wg-quick@fieldnotes.service field-notes-tls.service
[Service]
Type=oneshot
RemainAfterExit=yes
ExecStart={install}/firewall.py
[Install]
WantedBy=multi-user.target
''','field-notes-tls.service':f'''[Unit]
Description=Field Notes LAN and device HTTPS
After=network-online.target wg-quick@fieldnotes.service field-notes-firewall.service field-notes.service
Requires=wg-quick@fieldnotes.service field-notes-firewall.service
[Service]
User=fieldnotes-tls
Group=fieldnotes-tls
Environment=XDG_DATA_HOME={tls_data}
ExecStart=/usr/bin/caddy run --config {config}/Caddyfile --adapter caddyfile
Restart=on-failure
RestartSec=5
UMask=0077
ProtectSystem=strict
ProtectHome=yes
PrivateTmp=yes
NoNewPrivileges=yes
AmbientCapabilities=CAP_NET_BIND_SERVICE
CapabilityBoundingSet=CAP_NET_BIND_SERVICE
ReadWritePaths={tls_data}
LimitCORE=0
[Install]
WantedBy=multi-user.target
'''}
    for filename,contents in units.items():
        target=systemd/filename
        if target.exists():raise ValueError(f'Existing unit refused: {target}')
        target.write_text(contents)
    checked(['systemd-analyze','verify',*[str(systemd/filename) for filename in units]])
    checked(['systemctl','daemon-reload']);checked(['systemctl','enable','--now','field-notes-firewall','wg-quick@fieldnotes','field-notes','field-notes-tls'])
    checked(['systemctl','is-active','field-notes','field-notes-tls'])
    opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
    failure=None
    for attempt in range(30):
        try:
            request=urllib.request.Request('http://127.0.0.1:8788/api/login',data=json.dumps({'name':name,'password':password,'device':'Installer readiness check'}).encode(),headers={'Content-Type':'application/json'})
            with opener.open(request,timeout=3) as response:token=json.load(response)['token']
            request=urllib.request.Request('http://127.0.0.1:8788/api/snapshot',headers={'Authorization':'Bearer '+token})
            with opener.open(request,timeout=3) as response:snapshot=json.load(response)
            if snapshot['protocol']!=1 or snapshot['actor']['role']!='owner':raise ValueError('Authenticated readiness check returned an unexpected identity/protocol')
            request=urllib.request.Request('http://127.0.0.1:8788/api/logout',data=b'{}',headers={'Content-Type':'application/json','Authorization':'Bearer '+token})
            with opener.open(request,timeout=3) as response:response.read()
            failure=None;break
        except (OSError,ValueError,KeyError) as e:failure=e;time.sleep(.2)
    del password
    if failure is not None:raise ValueError('Services started but authenticated readiness failed; inspect service logs before using the installation')
    print(json.dumps(plan,indent=2));print('Configure the stated UDP forward and LAN DNS hostname. Trust the public Caddy root certificate on administrator clients after checking its fingerprint.')
    print('CA certificate:',tls_data/'caddy/pki/authorities/local/root.crt')
    print('Use the web interface to create members. Approve each native public key with peer.py; see docs/RUNNING.md. Keep the master key separately for recovery.')

if __name__=='__main__':
    try:main()
    except (ValueError,subprocess.CalledProcessError,OSError) as e:print(f'Installation stopped: {e}',file=sys.stderr);sys.exit(1)
