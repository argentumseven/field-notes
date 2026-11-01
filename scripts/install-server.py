#!/usr/bin/env python3
"""Fresh-install Linux helper. --plan performs validation and writes nothing.
It never replaces an existing installation. HTTPS is served directly by Rust.
"""
import argparse, fcntl, getpass, grp, hashlib, ipaddress, json, os, pathlib, pwd, re, shutil, socket, subprocess, sys, tempfile, time, urllib.request

sys.dont_write_bytecode = True
from server_installation import Host, inventory, digest, UNITS, uninstall_wrapper

def arguments():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--apply', action='store_true')
    p.add_argument('--plan', action='store_true')
    p.add_argument('--lan-ip', help='Server LAN IPv4 address (prompted in terminal)')
    p.add_argument('--lan-prefix', help='Trusted LAN subnet in CIDR notation (prompted in terminal)')
    p.add_argument('--web-port', type=int, help='Chosen LAN management HTTPS port (no default)')
    p.add_argument('--endpoint', help='Reachable home DDNS/IP, optionally :external-port; omitted port follows your WireGuard choice')
    p.add_argument('--hostname', default='notes.home.arpa')
    p.add_argument('--udp-port', type=int, help='Chosen WireGuard transport UDP port (no default)')
    p.add_argument('--prefix', default='/opt/field-notes')
    p.add_argument('--data', default='/var/lib/field-notes')
    p.add_argument('--config', default='/etc/field-notes')
    p.add_argument('--overlay', help='Private WireGuard /24 overlay (default 10.82.0.0/24)')
    p.add_argument('--binary')
    mode = p.add_mutually_exclusive_group()
    mode.add_argument('--interactive', dest='interactive', action='store_true', default=None, help='Prompt for missing network settings and ports, including in --plan')
    mode.add_argument('--non-interactive', dest='interactive', action='store_false', help='Require explicit LAN settings and both ports; do not prompt')
    return p.parse_args()

def prompt_port(label, management=False):
    while True:
        print(f'{label} (1024–65535, required): ', end='', file=sys.stderr, flush=True)
        try:
            raw = input().strip()
        except EOFError as error:
            raise ValueError('Port selection requires a terminal, or explicit --web-port and --udp-port options.') from error
        if raw.isascii() and raw.isdigit() and 1024 <= int(raw) <= 65535 and not (management and int(raw) == 8443):
            return int(raw)
        print('Enter a port from 1024 to 65535; management cannot use the reserved inner API port 8443.', file=sys.stderr)

def select_ports(a, interactive):
    if interactive:
        if a.web_port is None:
            a.web_port = prompt_port('Management HTTPS port (LAN only)', management=True)
        if a.udp_port is None:
            a.udp_port = prompt_port('WireGuard UDP port (router forwarding)')
    if a.web_port is None or a.udp_port is None:
        raise ValueError('Choose both ports: use a terminal/--interactive or provide --web-port and --udp-port explicitly.')

def prompt_required(label, validator):
    while True:
        try:
            raw = input(f'{label} (required): ').strip()
        except EOFError as error:
            raise ValueError('Network settings require a terminal, or explicit --lan-ip and --lan-prefix options.') from error
        if raw:
            try:
                validator(raw)
                return raw
            except ValueError:
                pass
        print(f'Enter a valid {label.lower()}.', file=sys.stderr)

def prompt_optional(label):
    try:
        return input(label).strip() or None
    except EOFError as error:
        raise ValueError('Complete the interactive network questions, or supply the settings explicitly.') from error

def select_network(a, interactive):
    missing_network = a.lan_ip is None or a.lan_prefix is None
    if interactive:
        if a.lan_ip is None:
            a.lan_ip = prompt_required('Server LAN IPv4 address', ipaddress.IPv4Address)
        if a.lan_prefix is None:
            a.lan_prefix = prompt_required('Trusted LAN subnet in CIDR notation', ipaddress.IPv4Network)
        if missing_network and a.endpoint is None:
            a.endpoint = prompt_optional('Public DDNS/IP endpoint (blank for LAN-only): ')
        if missing_network and a.overlay is None:
            a.overlay = prompt_optional('Private WireGuard /24 overlay (blank for 10.82.0.0/24): ')
    if a.lan_ip is None or a.lan_prefix is None:
        raise ValueError('Choose both LAN settings: use a terminal/--interactive or provide --lan-ip and --lan-prefix explicitly.')
    if a.overlay is None:
        a.overlay = '10.82.0.0/24'

def checked(cmd,**kwargs):return subprocess.run(cmd,check=True,**kwargs)
def main():
    a=arguments()
    interactive = a.interactive if a.interactive is not None else sys.stdin.isatty()
    select_network(a, interactive)
    select_ports(a, interactive)
    lan=ipaddress.IPv4Address(a.lan_ip);prefix=ipaddress.IPv4Network(a.lan_prefix);overlay=ipaddress.IPv4Network(a.overlay)
    private=[ipaddress.IPv4Network(x) for x in ('10.0.0.0/8','172.16.0.0/12','192.168.0.0/16')]
    if not any(prefix.subnet_of(n) for n in private) or lan not in prefix or not any(overlay.subnet_of(n) for n in private) or overlay.prefixlen!=24 or overlay.overlaps(prefix):raise ValueError('Use a private LAN address within its prefix and a nonoverlapping private /24 overlay.')
    if not re.fullmatch(r'[a-z0-9]+(?:[.-][a-z0-9]+)+',a.hostname) or len(a.hostname)>200:raise ValueError('Invalid hostname')
    if not 1024<=a.udp_port<=65535 or not 1024<=a.web_port<=65535 or a.web_port==8443:raise ValueError('Choose unprivileged ports; reserve 8443 for the tunnel API')
    endpoint=a.endpoint or str(lan)
    if re.fullmatch(r'[A-Za-z0-9.-]+',endpoint):endpoint=f'{endpoint}:{a.udp_port}'
    if not re.fullmatch(r'[A-Za-z0-9.-]+:[0-9]{1,5}',endpoint) or not 1<=int(endpoint.rsplit(':',1)[1])<=65535:raise ValueError('Invalid reachable WireGuard endpoint')
    external_port=int(endpoint.rsplit(':',1)[1])
    destinations=[pathlib.Path(x) for x in (a.prefix,a.data,a.config)]
    if a.apply and pathlib.Path('/var/lib/field-notes-peer-helper').exists():raise ValueError('Existing peer-helper state found; use the documented upgrade/recovery procedure instead of a fresh install.')
    for d in destinations:
        if not re.fullmatch(r'/[A-Za-z0-9/_.-]+',str(d)) or not d.is_absolute() or '..' in d.parts or len(d.parts)<3 or d.is_symlink():raise ValueError('Use explicit absolute installation directories without symlinks or traversal')
        if d.exists():raise ValueError(f'Existing destination refused: {d}. Back up and use a reviewed upgrade procedure.')
        for other in destinations:
            if other!=d and other in d.parents:raise ValueError('Installation directories must not overlap')
    ownership=inventory(*destinations)
    host=Host()
    for ip in ('127.0.0.1',str(lan)):

        if ipaddress.ip_address(ip) in overlay:raise ValueError('Overlay conflicts with host addresses')
    root=pathlib.Path(__file__).resolve().parents[1]
    binary=pathlib.Path(a.binary) if a.binary else (root/'field-notes-server' if (root/'field-notes-server').exists() else root/'target/release/field-notes-server')
    plan={'port_selection':'Chosen','apply_port_options':f'--web-port {a.web_port} --udp-port {a.udp_port}','runtime':'containers','requires':['Docker Engine (rootful)','Docker Compose 2.24+','WireGuard kernel support'],'binary':str(binary),'lan_admin':f'https://{lan}:{a.web_port} (source {prefix})','device_api':f'https://{overlay.network_address+1}:8443','wireguard_endpoint':endpoint,'router_forward':f'UDP {external_port} to {lan}:{a.udp_port}','directories':[str(p) for p in destinations],'services':['field-notes','field-notes-peers.timer','field-notes-certificates.timer'],'no_public_web':True,'remote_access':bool(a.endpoint),'endpoint_note':f'Provide --endpoint your-home-DDNS:{a.udp_port} for access away from home; the LAN UDP alternative is enrolled automatically.'}
    if not a.apply:
        print(json.dumps(plan,indent=2))
        return
    if os.geteuid()!=0:
        raise ValueError('--apply requires root; review --plan first')
    if interactive:
        print('Installation plan (no changes made yet):')
        print(json.dumps(plan,indent=2))
        try:
            confirmation = input('Type INSTALL to apply this plan (anything else cancels): ').strip()
        except EOFError as error:
            raise ValueError('Installation not confirmed; no changes made.') from error
        if confirmation != 'INSTALL':
            print('Installation cancelled; no changes made.')
            return
    for destination in destinations:host.check_parents(str(destination))
    if not binary.is_file():raise ValueError('Build or unpack the complete server bundle first')
    for name in ('fieldnotes',):
        try:pwd.getpwnam(name)
        except KeyError:pass
        else:raise ValueError(f'Existing service account {name}; fresh installation refused')
    try:grp.getgrnam('fieldnotes')
    except KeyError:pass
    else:raise ValueError('Existing fieldnotes group; fresh installation refused')
    os_release=pathlib.Path('/etc/os-release').read_text()
    if not re.search(r'^ID=(?:debian|ubuntu)$',os_release,re.M):raise ValueError('This installer supports Debian/Ubuntu only')
    for path in [pathlib.Path('/etc/wireguard/fieldnotes.conf'),*[pathlib.Path('/etc/systemd/system')/name for name in UNITS]]:
        if path.exists():raise ValueError(f'Existing configuration refused: {path}')
    for port,kind in [(a.web_port,socket.SOCK_STREAM),(a.udp_port,socket.SOCK_DGRAM)]:
        with socket.socket(socket.AF_INET,kind) as sock:sock.bind((str(lan),port))
    password=getpass.getpass('Initial web management owner password (12+ characters): ')
    if len(password.encode())<12 or len(password.encode())>256:raise ValueError('Password length must be 12–256 bytes')
    if password!=getpass.getpass('Confirm password: '):raise ValueError('Passwords do not match')
    name=input('Owner user name [owner]: ').strip() or 'owner'
    if not re.fullmatch(r'[A-Za-z0-9._ -]{2,64}',name):raise ValueError('Use an owner name with 2–64 letters, numbers, spaces, dots, underscores or hyphens')
    from container_deployment import Engine,compose,validate as validate_deployment
    engine=Engine()
    info=engine.read(['info','--format','{{json .}}'])
    if any('rootless' in value or 'userns' in value for value in info.get('SecurityOptions',[])):
        raise ValueError('Use a rootful Docker engine without remapped bind-volume UIDs on this VM; existing daemon policy is not changed')
    engine.call(['compose','version'])
    version=json.loads((root/'release.json').read_text())['version']
    image_index=root/'images.json'
    image_archive=root/'images.tar.gz'
    if not image_index.exists():
        image_index=root/'dist/containers/images.json'
        image_archive=root/f'dist/containers/field-notes-{version}-linux-{os.uname().machine}-images.tar.gz'
    if not image_index.is_file() or not image_archive.is_file():
        raise ValueError('Build the current server/container bundle first; both offline runtime images are required')
    images=json.loads(image_index.read_text())
    from android_artifact import verify as verify_android
    download_source=root/'downloads'
    if not download_source.exists():download_source=root/f'dist/server/linux-{os.uname().machine}/downloads'
    download_info=verify_android(download_source,version)
    if download_info['version_code']!=json.loads((root/'release.json').read_text())['android_version_code']:raise ValueError('Bundled APK version code does not match')
    if set(images)!={'app','gateway'} or any(images[role].get('tag')!=f'field-notes-{role}:{version}' or not re.fullmatch(r'sha256:[a-f0-9]{64}',images[role].get('id','')) for role in images):
        raise ValueError('Invalid offline image manifest')
    expected=image_archive.with_suffix(image_archive.suffix+'.sha256').read_text().split()[0]
    with image_archive.open('rb') as archive:
        if hashlib.file_digest(archive,'sha256').hexdigest()!=expected:raise ValueError('Offline container archive checksum does not match')
    # A selected namespace must be free; never adopt or remove another project.
    for kind,name in [('container','field-notes-app'),('container','field-notes-gateway'),('network','field-notes')]:
        docker_args=[kind,'ls']+(['--all'] if kind=='container' else [])+['--filter','name=^'+('/' if kind=='container' else '')+name+'$','--format','{{.ID}}']
        if engine.call(docker_args).stdout.strip():raise ValueError('Existing container resource refused: '+name)
    checked(['apt-get','update']);checked(['apt-get','install','-y','wireguard-tools','openssl','kmod'])
    checked(['modprobe','wireguard'])
    install,data,config=destinations
    config.mkdir(mode=0o700,parents=True)
    lock=os.open(config/'lifecycle.lock',os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
    fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
    host.write_record(ownership)
    install.mkdir(mode=0o700,parents=True);data.mkdir(mode=0o700,parents=True)
    checked(['useradd','--system','--user-group','--home-dir',str(data),'--shell','/usr/sbin/nologin','fieldnotes'])
    user=pwd.getpwnam('fieldnotes')
    settings=validate_deployment(dict(version=1,installation=ownership['id'],project='field-notes',
        prefix=str(install),data=str(data),config=str(config),runtime='/run/field-notes-peers',
        uid=user.pw_uid,gid=user.pw_gid,lan_ip=str(lan),lan_prefix=str(prefix),overlay=str(overlay),
        web_port=a.web_port,udp_port=a.udp_port,images={role:images[role]['id'] for role in images}))
    ownership.update(uid=user.pw_uid,gid=user.pw_gid,runtime='containers',docker={'settings':settings,'tags':{role:images[role]['tag'] for role in images}})
    host.write_record(ownership)
    engine.call(['load','--input',str(image_archive)],timeout=180)
    engine.verify_images(settings)
    install.chmod(0o755);os.chown(config,0,user.pw_gid);config.chmod(0o750)
    # A private, empty CLI configuration avoids reliance on /root (hidden by
    # systemd ProtectHome) or another service's Docker registry/proxy settings.
    (config/'docker').mkdir(mode=0o700)
    shutil.copy2(binary,install/'field-notes-server');(install/'field-notes-server').chmod(0o755)
    for helper in ('server_installation.py','uninstall-server.py','container_deployment.py','container-runtime.py','peer-agent.py','tls.py','android_artifact.py','upgrade-server.py'):
        shutil.copy2(root/'scripts'/helper,install/helper);(install/helper).chmod(0o644 if '_' in helper else 0o755)
    (install/'uninstall-server.sh').write_text(uninstall_wrapper(config));(install/'uninstall-server.sh').chmod(0o755)
    (install/'upgrade-server.sh').write_text('#!/usr/bin/env bash\nset -euo pipefail\nexec python3 -B "$(dirname "${BASH_SOURCE[0]}")/upgrade-server.py" --config '+str(config)+' "$@"\n');(install/'upgrade-server.sh').chmod(0o755)
    shutil.copytree(download_source,install/'downloads');(install/'downloads').chmod(0o755)
    for path in (install/'downloads').iterdir():path.chmod(0o644)
    # Execute initialization with the image's own libc, so the Debian/Ubuntu
    # host does not need to match the builder's dynamic-linker version.
    os.chown(data,user.pw_uid,user.pw_gid)
    with tempfile.TemporaryDirectory(prefix='.initialize-',dir=config) as key_directory:
        os.chown(key_directory,user.pw_uid,user.pw_gid)
        engine.call(['run','--interactive','--rm','--network=none','--read-only','--cap-drop=ALL',
            '--security-opt=no-new-privileges:true','--user',f'{user.pw_uid}:{user.pw_gid}',
            '--mount',f'type=bind,source={data},target=/state',
            '--mount',f'type=bind,source={key_directory},target=/keys',settings['images']['app'],
            'init','--data','/state','--key-file','/keys/master.key','--name',name],input=password)
        os.replace(pathlib.Path(key_directory)/'master.key',config/'master.key')
    # Directories need traversal; attachments must remain writable by the app.
    for folder,dirs,files in os.walk(data):
        os.chown(folder,user.pw_uid,user.pw_gid);os.chmod(folder,0o700)
        for filename in files:
            path=pathlib.Path(folder)/filename;os.chown(path,user.pw_uid,user.pw_gid);path.chmod(0o600)
    # The vault requires owner-only keys. Its app-owned key is mounted read-only
    # and kept outside app-writable state; CA/hub keys remain host-root-only.
    os.chown(config/'master.key',user.pw_uid,user.pw_gid);(config/'master.key').chmod(0o600)
    gateway=config/'gateway';gateway.mkdir(mode=0o700)
    private=checked(['wg','genkey'],capture_output=True,text=True).stdout.strip()
    server_public=checked(['wg','pubkey'],input=private.encode(),capture_output=True).stdout.decode().strip()
    ownership['wireguard_public_key']=server_public;host.write_record(ownership)
    fd=os.open(gateway/'fieldnotes.conf',os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
    with os.fdopen(fd,'w') as file:file.write(f'[Interface]\nAddress = {overlay.network_address+1}/24\nListenPort = {a.udp_port}\nPrivateKey = {private}\nMTU = 1280\n')
    del private
    (gateway/'listeners.json').write_text(json.dumps({'overlay':str(overlay),'lan_prefix':str(prefix),'web_port':a.web_port,'udp_port':a.udp_port}));(gateway/'listeners.json').chmod(0o600)
    checked(['python3',str(install/'tls.py'),'init','--issued-subdirectory','--directory',str(config/'tls'),'--group','fieldnotes','--name',str(lan),'--name',a.hostname,'--name',str(overlay.network_address+1)])
    public_config={'bootstrap_url':f'https://{lan}:{a.web_port}/api/enrollment','endpoint':endpoint,'lan_endpoint':f'{lan}:{a.udp_port}','server_key':server_public,'api':f'https://{overlay.network_address+1}:8443','ca_pem':(config/'tls/ca.crt').read_text(),'overlay':str(overlay)}
    (config/'network.json').write_text(json.dumps(public_config));os.chown(config/'network.json',0,user.pw_gid);(config/'network.json').chmod(0o640)
    (config/'deployment.json').write_text(json.dumps(settings));(config/'deployment.json').chmod(0o600)
    (config/'compose.json').write_text(json.dumps(compose(settings),indent=2));(config/'compose.json').chmod(0o600)
    helper_state=pathlib.Path('/var/lib/field-notes-peer-helper');helper_state.mkdir(mode=0o700)
    helper_config={'desired':str(data/'peers.json'),'status':'/run/field-notes-peers/status.json','state':str(helper_state/'state.json'),'interface':'fieldnotes','overlay':str(overlay),'uid':user.pw_uid,'gid':user.pw_gid,'deployment':str(config/'deployment.json')}
    (config/'peer-helper.json').write_text(json.dumps(helper_config));(config/'peer-helper.json').chmod(0o600)
    app_unit=(root/'deploy/field-notes.service').read_text().replace('/opt/field-notes',str(install)).replace('/etc/field-notes',str(config))
    units={'field-notes.service':app_unit,'field-notes-peers.service':f"""[Unit]
Description=Apply validated Field Notes container peers
After=field-notes.service
Requires=field-notes.service
[Service]
Type=oneshot
TimeoutStartSec=30
User=root
Group=fieldnotes
Environment=DOCKER_CONFIG={config}/docker
ExecStart=/usr/bin/python3 -B {install}/peer-agent.py --config {config}/peer-helper.json
RuntimeDirectory=field-notes-peers
RuntimeDirectoryMode=0750
RuntimeDirectoryPreserve=yes
UMask=0077
NoNewPrivileges=yes
ProtectSystem=strict
ProtectHome=yes
PrivateTmp=yes
CapabilityBoundingSet=CAP_DAC_OVERRIDE CAP_CHOWN CAP_FOWNER
ReadWritePaths=/run/field-notes-peers {helper_state}
LimitCORE=0
""",'field-notes-peers.timer':"""[Unit]
Description=Reconcile Field Notes container peers
[Timer]
OnBootSec=3
OnUnitActiveSec=2
AccuracySec=1
[Install]
WantedBy=timers.target
""",'field-notes-certificates.service':f"""[Unit]
Description=Renew the local Field Notes TLS certificate
[Service]
Type=oneshot
User=root
Group=fieldnotes
ExecStart=/usr/bin/python3 -B {install}/tls.py renew --if-needed --issued-subdirectory --directory {config}/tls --group fieldnotes --name {lan} --name {a.hostname} --name {overlay.network_address+1}
UMask=0077
NoNewPrivileges=yes
ProtectSystem=strict
ProtectHome=yes
PrivateTmp=yes
PrivateNetwork=yes
CapabilityBoundingSet=
ReadWritePaths={config}/tls
LimitCORE=0
""",'field-notes-certificates.timer':"""[Unit]
Description=Check the local Field Notes certificate daily
[Timer]
OnBootSec=10min
OnCalendar=daily
RandomizedDelaySec=1h
Persistent=yes
[Install]
WantedBy=timers.target
"""}
    systemd=pathlib.Path('/etc/systemd/system')
    for filename,contents in units.items():
        ownership['unit_hashes'][filename]=digest(contents.encode());host.write_record(ownership)
        fd=os.open(systemd/filename,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o644)
        with os.fdopen(fd,'w') as unit_file:unit_file.write(contents)
    checked(['systemd-analyze','verify',*[str(systemd/filename) for filename in units]])
    checked(['systemctl','daemon-reload']);checked(['systemctl','enable','--now','field-notes','field-notes-peers.timer','field-notes-certificates.timer'])
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
            with opener.open(f'https://{lan}:{a.web_port}/downloads/android',timeout=3) as response:download=json.load(response)
            if download.get('available') is not True or download.get('artifact')!=download_info:raise ValueError('Bundled Android download readiness failed')
            logout=urllib.request.Request(f'https://{lan}:{a.web_port}/api/logout',data=b'{}',headers={'Content-Type':'application/json','X-Field-CSRF':login['csrf']})
            with opener.open(logout,timeout=3) as response:response.read()
            failure=None;break
        except (OSError,ValueError,KeyError) as e:failure=e;time.sleep(.2)
    del password
    if failure is not None:raise ValueError('Services started but authenticated readiness failed; inspect service logs before using the installation')
    ownership['phase']='installed';host.write_record(ownership)
    print('Uninstall preview:',str(install/'uninstall-server.sh'),'--plan')
    print(json.dumps(plan,indent=2));print('Configure the one UDP forward. Verify and trust the public CA on administrator browsers; phones pin it through enrollment.')
    print('Public CA:',config/'tls/ca.crt')
    print('Android APK:',f'https://{lan}:{a.web_port}/downloads/field-notes.apk','(also on Sign in and in Management)')
    print('Open the management URL, create household users, and use Devices → Enroll device. No Caddy, proxy or load balancer is installed.')

if __name__=='__main__':
    try:main()
    except (ValueError,subprocess.CalledProcessError,OSError) as e:print(f'Installation stopped: {e}',file=sys.stderr);sys.exit(1)
