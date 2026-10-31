"""Root-owned installation inventory and bounded host operations.

The alternate filesystem root/runner are Python test seams, never CLI options.
Only the dedicated Field Notes resource names can be managed here.
"""
import base64
import grp
import hashlib
import ipaddress
import json
import os
import pathlib
import pwd
import re
import shlex
import shutil
import stat
import subprocess
import tempfile
import uuid

UNITS = ('field-notes.service', 'field-notes-peers.service', 'field-notes-peers.timer',
         'field-notes-certificates.service', 'field-notes-certificates.timer', 'field-notes-firewall.service')
TIMERS = ('field-notes-peers.timer', 'field-notes-certificates.timer')
STOP_ORDER = (*TIMERS, 'field-notes-peers.service', 'field-notes-certificates.service',
              'field-notes.service', 'wg-quick@fieldnotes.service', 'field-notes-firewall.service')
PROGRAMS = ('field-notes-server', 'peer-agent.py', 'tls.py', 'firewall.py',
            'uninstall-server.sh', 'uninstall-server.py', 'server_installation.py',
            'container-runtime.py', 'container_deployment.py')
WG_CONFIG = '/etc/wireguard/fieldnotes.conf'
HELPER_STATE = '/var/lib/field-notes-peer-helper'
RUNTIME = '/run/field-notes-peers'
RECORD = 'installation.json'
PROTECTED = {'/', '/etc', '/etc/ssh', '/etc/systemd', '/etc/systemd/system', '/etc/wireguard',
             '/opt', '/var', '/var/lib', '/var/lib/systemd', '/run', '/tmp', '/srv', '/usr', '/home', '/root'}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def directory(value):
    if not isinstance(value, str) or not re.fullmatch(r'/[A-Za-z0-9/_.-]+', value):
        raise ValueError('Use an explicit absolute installation directory')
    path = pathlib.PurePosixPath(value)
    if str(path) != value or '..' in path.parts or len(path.parts) < 3 or value in PROTECTED:
        raise ValueError('Refusing a shared, root, or noncanonical installation directory: ' + value)
    return value


def validate(record):
    expected = {'version', 'application', 'id', 'prefix', 'data', 'config', 'uid', 'gid',
                'unit_hashes', 'wireguard_public_key', 'phase', 'legacy', 'runtime', 'docker'}
    if not isinstance(record, dict) or set(record) != expected or record['version'] != 1 or record['application'] != 'field-notes':
        raise ValueError('Unsupported Field Notes installation inventory')
    if str(uuid.UUID(record['id'])) != record['id'] or type(record['legacy']) is not bool:
        raise ValueError('Invalid installation identity')
    paths = [pathlib.PurePosixPath(directory(record[key])) for key in ('prefix', 'data', 'config')]
    for index, path in enumerate(paths):
        for other in paths[index + 1:]:
            if path == other or path in other.parents or other in path.parents:
                raise ValueError('Installation directories overlap')
        for reserved in (WG_CONFIG, HELPER_STATE, RUNTIME, '/var/lib/systemd/timers', '/etc/systemd/system'):
            fixed = pathlib.PurePosixPath(reserved)
            if path == fixed or path in fixed.parents or fixed in path.parents:
                raise ValueError('Installation directory overlaps a fixed host resource')
    for key in ('uid', 'gid'):
        if record[key] is not None and (type(record[key]) is not int or record[key] <= 0):
            raise ValueError('Refusing a privileged or invalid service identity')
    if record['phase'] not in ('installing', 'installed', 'removing', 'removed'):
        raise ValueError('Invalid installation phase')
    if not isinstance(record['unit_hashes'], dict) or set(record['unit_hashes']) - set(UNITS):
        raise ValueError('Inventory contains an unrelated service')
    if any(not isinstance(value, str) or not re.fullmatch('[a-f0-9]{64}', value) for value in record['unit_hashes'].values()):
        raise ValueError('Invalid service inventory digest')
    public = record['wireguard_public_key']
    if public is not None:
        key_bytes = base64.b64decode(public, validate=True)
        if len(key_bytes) != 32 or base64.b64encode(key_bytes).decode() != public:
            raise ValueError('Invalid WireGuard public identity')
    if record['runtime'] not in ('native','containers'):
        raise ValueError('Unsupported installation runtime')
    if record['runtime']=='native' and record['docker'] is not None:
        raise ValueError('Native installation contains container resources')
    if record['runtime']=='containers':
        from container_deployment import validate as validate_deployment
        deployment=record['docker']
        if not isinstance(deployment,dict) or set(deployment)!={'settings','tags'}:
            raise ValueError('Invalid container inventory')
        c=validate_deployment(deployment['settings'])
        if c['installation']!=record['id'] or c['project']!='field-notes' or any(c[key]!=record[key] for key in ('prefix','data','config','uid','gid')):
            raise ValueError('Container deployment differs from installation identity')
        if set(deployment['tags'])!={'app','gateway'} or any(not re.fullmatch('field-notes-'+role+r':\d+\.\d+\.\d+',tag) for role,tag in deployment['tags'].items()):
            raise ValueError('Unrelated image tags in inventory')
    return record


def inventory(prefix, data, config):
    return validate(dict(version=1, application='field-notes', id=str(uuid.uuid4()), prefix=str(prefix),
                         data=str(data), config=str(config), uid=None, gid=None, unit_hashes={},
                         wireguard_public_key=None, phase='installing', legacy=False, runtime='native', docker=None))


def uninstall_wrapper(config):
    directory(str(config))
    return ('#!/usr/bin/env bash\nset -euo pipefail\n'
            'exec python3 -B "$(dirname "${BASH_SOURCE[0]}")/uninstall-server.py" --config '
            + shlex.quote(str(config)) + ' "$@"\n')


class Host:
    def __init__(self, root='/', runner=subprocess.run, owner=0):
        self.root = pathlib.Path(root)
        self.runner = runner
        self.owner = owner

    def path(self, logical):
        value = pathlib.PurePosixPath(logical)
        if not value.is_absolute() or '..' in value.parts:
            raise ValueError('Unsafe host path')
        result = self.root.joinpath(*value.parts[1:])
        # Never follow a redirected parent, including a dangling symlink.
        for part in (result, *result.parents):
            if part == self.root.parent:
                break
            if part.is_symlink():
                raise ValueError('Refusing symlink in managed path: ' + str(logical))
        return result

    def command(self, args, **kwargs):
        return self.runner(args, check=True, capture_output=True, text=True, **kwargs)

    def json_command(self, args):
        return json.loads(self.command(args).stdout)

    def trusted(self, logical, maximum=262144):
        path = self.path(logical)
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
        try:
            info = os.fstat(fd)
            if not stat.S_ISREG(info.st_mode) or info.st_uid != self.owner or info.st_mode & 0o022 or info.st_size > maximum:
                raise ValueError('Unsafe ownership, mode or size: ' + logical)
            with os.fdopen(fd, 'rb', closefd=False) as file:
                return file.read(maximum + 1)
        finally:
            os.close(fd)

    def write_record(self, record):
        validate(record)
        folder = self.path(record['config'])
        with tempfile.NamedTemporaryFile(dir=folder, prefix='.installation-', delete=False) as file:
            temporary = pathlib.Path(file.name)
            try:
                os.fchmod(file.fileno(), 0o600)
                file.write(json.dumps(record, sort_keys=True).encode())
                file.flush()
                os.fsync(file.fileno())
                os.replace(temporary, folder / RECORD)
                descriptor = os.open(folder, os.O_RDONLY | os.O_DIRECTORY)
                try:
                    os.fsync(descriptor)
                finally:
                    os.close(descriptor)
            finally:
                temporary.unlink(missing_ok=True)

    def account(self):
        try:
            return pwd.getpwnam('fieldnotes')
        except KeyError:
            return None

    def group(self):
        try:
            return grp.getgrnam('fieldnotes')
        except KeyError:
            return None

    def other_group_users(self, gid):
        return [user.pw_name for user in pwd.getpwall() if user.pw_gid == gid and user.pw_name != 'fieldnotes']

    def mounts(self):
        result = []
        for line in pathlib.Path('/proc/self/mountinfo').read_text().splitlines():
            raw = line.split()[4]
            result.append(pathlib.Path(re.sub(r'\\([0-7]{3})', lambda match: chr(int(match[1], 8)), raw)))
        return result

    def check_parents(self, logical):
        path = self.path(logical)
        for parent in path.parents:
            if parent == self.root.parent:
                break
            if parent.exists():
                info = parent.stat()
                if info.st_uid != self.owner or info.st_mode & 0o022 and not info.st_mode & stat.S_ISVTX:
                    raise ValueError('Installation parent is writable by another user: ' + logical)

    def check_tree(self, logical):
        path = self.path(logical)
        if not path.exists():
            return
        if not path.is_dir():
            raise ValueError('Managed directory is not a directory: ' + logical)
        for mount in self.mounts():
            if mount == path or path in mount.parents:
                raise ValueError('Mounted storage needs separate explicit cleanup; refusing to cross mount: ' + logical)
        if not shutil.rmtree.avoids_symlink_attacks:
            raise ValueError('This Python platform lacks safe directory removal')

    def tree(self, logical):
        self.check_tree(logical)
        path = self.path(logical)
        if path.exists():
            shutil.rmtree(path)

    def private_retained(self, logical):
        path = self.path(logical)
        if path.exists():
            # Protect the entire retained tree without following/chowning its
            # children (which could include hardlinks or external symlinks).
            os.chown(path, self.owner, self.owner, follow_symlinks=False)
            path.chmod(0o700)


def load(host, config=None):
    """Read the inventory, or adopt a strictly identified pre-inventory install."""
    main = '/etc/systemd/system/field-notes.service'
    inferred = None
    if host.path(main).exists():
        raw = host.trusted(main).decode()
        descriptions=re.findall(r'^Description=(.*)$',raw,re.M)
        if descriptions not in (['Field Notes private household workspace'],['Field Notes private household workspace (containers)']):
            raise ValueError('The service name belongs to an unrecognized installation')
        commands=re.findall(r'^ExecStart=(.*)$',raw,re.M)
        if len(commands)!=1:raise ValueError('Ambiguous service command')
        words=shlex.split(commands[0])
        def option(name):
            if words.count(name)!=1:raise ValueError('Missing or ambiguous service setting: '+name)
            return words[words.index(name)+1]
        if words[:2]==['/usr/bin/python3','-B'] and len(words)>3 and words[2].endswith('/container-runtime.py') and words[3]=='run':
            if '\nUser=root\n' not in raw or '\nGroup=root\n' not in raw:
                raise ValueError('Unrecognized container supervisor identity')
            deployment=pathlib.PurePosixPath(option('--config'))
            if deployment.name!='deployment.json':raise ValueError('Unrecognized container deployment path')
            inferred=(str(pathlib.PurePosixPath(words[2]).parent),None,str(deployment.parent))
        elif len(words)>1 and words[1]=='serve' and words[0].endswith('/field-notes-server'):
            if '\nUser=fieldnotes\n' not in raw or '\nGroup=fieldnotes\n' not in raw:
                raise ValueError('Unrecognized content service identity')
            key=pathlib.PurePosixPath(option('--key-file'))
            if key.name!='master.key':raise ValueError('Unrecognized recovery-key location')
            inferred=(str(pathlib.PurePosixPath(words[0]).parent),option('--data'),str(key.parent))
        else:raise ValueError('Unrecognized content service executable')
        if config is not None and config!=inferred[2]:raise ValueError('Selected configuration does not match the service')
        config=inferred[2]
    config = config or '/etc/field-notes'
    directory(config)
    record_path = config + '/' + RECORD
    if host.path(record_path).exists():
        record = validate(json.loads(host.trusted(record_path)))
        if record['config'] != config or inferred is not None and any(inferred[index] is not None and record[key]!=inferred[index] for index,key in enumerate(('prefix','data','config'))):
            raise ValueError('Inventory and installed service disagree')
        return record
    if inferred is None:
        # Do not guess at unknown data directories or claim they were purged.
        candidates = (config, '/opt/field-notes', '/var/lib/field-notes', WG_CONFIG, HELPER_STATE, RUNTIME, *('/etc/systemd/system/'+unit for unit in UNITS))
        if any(host.path(path).exists() for path in candidates):
            raise ValueError('Untracked files remain; a recognized service or retained installation inventory is required')
        return None
    if inferred[1] is None:raise ValueError('Container installation inventory is required')
    record = inventory(*inferred)
    record['legacy'] = True
    record['phase'] = 'installed'
    for unit in UNITS:
        path = '/etc/systemd/system/' + unit
        if host.path(path).exists():
            raw = host.trusted(path)
            if not re.search(rb'Description=.*Field Notes', raw):
                raise ValueError('Unrecognized legacy service: ' + unit)
            record['unit_hashes'][unit] = digest(raw)
    account = host.account()
    if account:
        record.update(uid=account.pw_uid, gid=account.pw_gid)
    if host.path(WG_CONFIG).exists():
        record['wireguard_public_key'] = wireguard_identity(host, host.trusted(WG_CONFIG), record)
    return validate(record)


def wireguard_identity(host, raw, record):
    section = None
    private = None
    network = None
    for original in raw.decode().splitlines():
        line = original.split('#', 1)[0].strip()
        if not line:
            continue
        if line in ('[Interface]', '[Peer]'):
            section = line
            continue
        if '=' not in line:
            raise ValueError('Unrecognized WireGuard configuration')
        name, value = (part.strip() for part in line.split('=', 1))
        allowed = {'Address', 'ListenPort', 'PrivateKey'} if section == '[Interface]' else {'PublicKey', 'PresharedKey', 'AllowedIPs', 'Endpoint', 'PersistentKeepalive'} if section == '[Peer]' else set()
        if name not in allowed:
            raise ValueError('Unexpected WireGuard option or executable hook: ' + name)
        if section == '[Interface]' and name == 'PrivateKey':
            if private is not None:
                raise ValueError('Multiple WireGuard private identities')
            private = value
        if section == '[Interface]' and name == 'Address':
            address = ipaddress.IPv4Interface(value)
            if address.network.prefixlen != 24 or address.ip != address.network.network_address + 1 or not address.ip.is_private:
                raise ValueError('Unexpected Field Notes overlay')
            network = address.network
        if name == 'AllowedIPs':
            peer = ipaddress.IPv4Interface(value)
            if network is None or peer.ip not in network or peer.network.prefixlen != 32:
                raise ValueError('Unexpected peer route; refusing wg-quick shutdown')
    if private is None or network is None or len(base64.b64decode(private, validate=True)) != 32:
        raise ValueError('Missing WireGuard identity or overlay')
    return host.command(['/usr/bin/wg', 'pubkey'], input=private + '\n').stdout.strip()
