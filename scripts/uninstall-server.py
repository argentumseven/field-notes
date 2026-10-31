#!/usr/bin/env python3
"""Remove only the recorded Field Notes Linux installation; preview by default."""
import argparse
import fcntl
import json
import os
import pathlib
import re
import sys
import subprocess

sys.dont_write_bytecode = True
from server_installation import (Host, HELPER_STATE, PROGRAMS, RECORD, RUNTIME, STOP_ORDER,
                                 TIMERS, UNITS, WG_CONFIG, digest, load, wireguard_identity)


def state(host, unit):
    result = host.command(['/usr/bin/systemctl', 'show', unit, '--property=LoadState,ActiveState,FragmentPath'])
    return dict(line.split('=', 1) for line in result.stdout.splitlines() if '=' in line)


def resources(host):
    links = host.json_command(['/usr/sbin/ip', '-j', '-d', 'link', 'show'])
    interfaces = [row for row in links if row.get('ifname') == 'fieldnotes']
    if len(interfaces) > 1:
        raise ValueError('Ambiguous Field Notes interface')
    tables = host.json_command(['/usr/sbin/nft', '-j', 'list', 'tables'])
    table = any(row.get('table', {}).get('family') == 'inet' and row['table'].get('name') == 'fieldnotes'
                for row in tables.get('nftables', []))
    return interfaces, table


def stop_order(record):
    return STOP_ORDER if record['runtime']=='native' else tuple(unit for unit in STOP_ORDER if unit not in ('wg-quick@fieldnotes.service','field-notes-firewall.service'))


def engine(host):
    from container_deployment import Engine
    return Engine(runner=host.runner)


def preflight(host, record, purge):
    host.check_parents(record['config'])
    for key in ('prefix', 'data', 'config'):
        path = host.path(record[key])
        host.check_parents(record[key])
        if path.exists():
            allowed = {host.owner, record['uid']} if key == 'data' else {host.owner}
            if not path.is_dir() or path.stat().st_uid not in allowed or path.stat().st_mode & 0o022:
                raise ValueError('Unsafe managed directory ownership or permissions: ' + record[key])
            if purge or key == 'prefix':
                host.check_tree(record[key])
    prefix = host.path(record['prefix'])
    if prefix.exists():
        for file in prefix.iterdir():
            if file.name=='__pycache__' and file.is_dir() and not file.is_symlink():
                modules={pathlib.Path(name).stem for name in PROGRAMS if name.endswith('.py')}
                for cached in file.iterdir():
                    match=re.fullmatch(r'(.+)\.cpython-\d+(?:\.opt-\d+)?\.pyc',cached.name)
                    if not match or match[1] not in modules or cached.is_symlink() or not cached.is_file() or cached.stat().st_uid!=host.owner or cached.stat().st_mode&0o022:
                        raise ValueError('Unexpected Python cache; review it before uninstall')
                continue
            if file.name not in PROGRAMS or file.is_symlink() or not file.is_file() or file.stat().st_uid != host.owner or file.stat().st_mode & 0o022:
                raise ValueError('Unexpected program directory entry; move/review it first: ' + str(file))
    for unit in UNITS:
        for base in ('/etc/systemd/system/', '/run/systemd/system/'):
            if host.path(base + unit + '.d').exists():
                raise ValueError('Review customized service drop-ins before uninstall: ' + unit)
        path = '/etc/systemd/system/' + unit
        if host.path(path).exists():
            expected = record['unit_hashes'].get(unit)
            if expected is None or digest(host.trusted(path)) != expected:
                raise ValueError('Service differs from the installation inventory: ' + unit)
    for unit in (('wg-quick@fieldnotes.service','wg-quick@.service') if record['runtime']=='native' else ()):
        for base in ('/etc/systemd/system/','/run/systemd/system/'):
            if host.path(base+unit).exists() or host.path(base+unit+'.d').exists():
                raise ValueError('Review the customized WireGuard unit before uninstall: '+unit)
    for unit in stop_order(record):
        current = state(host, unit)
        fragment = current.get('FragmentPath', '')
        if unit=='wg-quick@fieldnotes.service' and fragment and fragment not in ('/lib/systemd/system/wg-quick@.service','/usr/lib/systemd/system/wg-quick@.service'):
            raise ValueError('WireGuard service is loaded from an unrecognized location')
        if unit in UNITS and fragment and fragment != '/etc/systemd/system/' + unit:
            raise ValueError('Service is loaded from an unrelated location: ' + unit)
    if record['runtime']=='containers':
        runtime=record['docker'];client=engine(host)
        client.objects(runtime['settings']);client.image_inventory(runtime['settings'],runtime['tags'])
    else:
        if host.path(WG_CONFIG).exists():
            public = wireguard_identity(host, host.trusted(WG_CONFIG), record)
            if record['wireguard_public_key'] != public:
                raise ValueError('WireGuard configuration belongs to a different server identity')
        interfaces, table = resources(host)
        if interfaces:
            if interfaces[0].get('linkinfo', {}).get('info_kind') != 'wireguard':
                raise ValueError('The fieldnotes interface is not WireGuard')
            public = host.command(['/usr/bin/wg', 'show', 'fieldnotes', 'public-key']).stdout.strip()
            if not record['wireguard_public_key'] or public != record['wireguard_public_key']:
                raise ValueError('Live WireGuard interface belongs to another identity')
        if table and not record['legacy']:
            value = host.json_command(['/usr/sbin/nft', '-j', 'list', 'table', 'inet', 'fieldnotes'])
            comments = [row.get('table', {}).get('comment') for row in value.get('nftables', [])
                        if row.get('table', {}).get('name') == 'fieldnotes']
            if comments != ['Field Notes ' + record['id']]:
                raise ValueError('The nftables table does not have this installation identity')
    account = host.account()
    if account:
        if account.pw_uid == 0 or record['uid'] not in (None, account.pw_uid) or record['gid'] not in (None, account.pw_gid) or account.pw_dir != record['data'] or account.pw_shell not in ('/usr/sbin/nologin', '/sbin/nologin'):
            raise ValueError('The fieldnotes account has been reused or changed')
    group = host.group()
    if group and (group.gr_gid == 0 or record['gid'] not in (None, group.gr_gid) or group.gr_mem or host.other_group_users(group.gr_gid)):
        raise ValueError('The fieldnotes group is shared or has changed')
    for fixed in (HELPER_STATE, RUNTIME):
        path = host.path(fixed)
        if path.exists() and (not path.is_dir() or path.stat().st_uid != host.owner or path.stat().st_mode & 0o022):
            raise ValueError('Unsafe helper state directory: ' + fixed)
        host.check_tree(fixed)
    for timer in TIMERS:
        stamp = '/var/lib/systemd/timers/stamp-' + timer
        if host.path(stamp).exists():
            host.trusted(stamp)
    return account, group


def plan(record, purge):
    return dict(mode='purge' if purge else 'remove', installation=record['id'],
                stop=list(stop_order(record)), remove_programs=record['prefix'], remove_account='fieldnotes',
                remove_interface='fieldnotes' if record['runtime']=='native' else 'inside owned gateway container', remove_firewall_table='inet fieldnotes' if record['runtime']=='native' else 'inside owned gateway namespace',
                containers=record['docker']['settings']['project'] if record['runtime']=='containers' else None,
                remove_units=list(UNITS), remove_helper_state=[HELPER_STATE, RUNTIME],
                delete_data=[record['data'], record['config']] if purge else [],
                retain_data=[] if purge else [record['data'], record['config']],
                recovery='Preserve data, master key, CA and a private copy of WireGuard configuration; retained directories become root-only.' if not purge else 'Planner records, attachments, retained backups inside these directories and server keys will be deleted.',
                external_cleanup=['Remove this server\'s UDP forwarding rule from your router.',
                                  'Remove its CA from administrator browsers/devices if no longer needed.',
                                  'External backups, downloaded bundles, OS journal/history and phone installations are managed separately.'],
                shared_packages_removed=False)


def apply(host, record, purge=False):
    # Recheck after taking the configuration lock. Any failed command aborts;
    # retain the inventory/programs until system resources are safely stopped.
    preflight(host, record, purge)
    record['phase'] = 'removing'
    host.write_record(record)
    for unit in stop_order(record):
        if state(host, unit).get('LoadState') != 'not-found':
            host.command(['/usr/bin/systemctl', 'disable', '--now', unit])
            if state(host, unit).get('ActiveState') not in ('inactive', 'failed'):
                raise ValueError('Service did not stop: ' + unit)
    if record['runtime']=='containers':
        runtime=record['docker'];client=engine(host)
        client.stop(runtime['settings']);client.remove_images(runtime['settings'],runtime['tags'])
    else:
        interfaces, table = resources(host)
        if interfaces:
            # A manually raised interface may not belong to an active wg-quick unit.
            # Removing this verified device also removes its connected routes.
            host.command(['/usr/sbin/ip', 'link', 'delete', 'dev', 'fieldnotes'])
        if resources(host)[0]:
            raise ValueError('Field Notes tunnel is still present; firewall retained')
        if table:
            host.command(['/usr/sbin/nft', 'delete', 'table', 'inet', 'fieldnotes'])
        if resources(host)[1]:
            raise ValueError('Field Notes firewall table is still present')
        # Move the hub key out of wg-quick's auto-start location on normal removal.
        wgfile = host.path(WG_CONFIG)
        if not purge and wgfile.exists():
            raw = host.trusted(WG_CONFIG)
            recovery = host.path(record['config'] + '/recovery')
            recovery.mkdir(mode=0o700, exist_ok=True)
            if recovery.stat().st_uid != host.owner or recovery.stat().st_mode & 0o077:
                raise ValueError('Unsafe recovery directory')
            copy = recovery / ('fieldnotes-' + digest(raw)[:16] + '.conf')
            if copy.exists():
                if copy.is_symlink() or copy.read_bytes() != raw:
                    raise ValueError('Existing WireGuard recovery copy differs')
            else:
                descriptor = os.open(copy, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
                with os.fdopen(descriptor, 'wb') as file:
                    file.write(raw)
                    file.flush()
                    os.fsync(file.fileno())
            descriptor = os.open(recovery, os.O_RDONLY | os.O_DIRECTORY)
            try:
                os.fsync(descriptor)
            finally:
                os.close(descriptor)
        wgfile.unlink(missing_ok=True)
    for unit in UNITS:
        host.path('/etc/systemd/system/' + unit).unlink(missing_ok=True)
    for timer in TIMERS:
        host.path('/var/lib/systemd/timers/stamp-' + timer).unlink(missing_ok=True)
    host.command(['/usr/bin/systemctl', 'daemon-reload'])
    # reset-failed is scoped; never clear another application's failed status.
    for unit in stop_order(record):
        if state(host, unit).get('ActiveState') == 'failed':
            host.command(['/usr/bin/systemctl', 'reset-failed', unit])
    host.tree(RUNTIME)
    host.tree(HELPER_STATE)
    for key in ('data', 'config'):
        host.private_retained(record[key])
    if host.account():
        host.command(['/usr/sbin/userdel', 'fieldnotes'])  # no -r and no --force
    if host.group():
        host.command(['/usr/sbin/groupdel', 'fieldnotes'])
    if host.account() or host.group():
        raise ValueError('The dedicated service identity is still present')
    # Delete programs only after service/network/account cleanup succeeds.
    host.tree(record['prefix'])
    if purge:
        host.tree(record['data'])
        host.tree(record['config'])
    else:
        record['phase'] = 'removed'
        host.write_record(record)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--plan', action='store_true', help='Preview only; this is the default')
    mode.add_argument('--apply', action='store_true', help='Apply removal as root')
    parser.add_argument('--config', help='Configuration directory, needed after removal when using a custom location')
    parser.add_argument('--purge', action='store_true', help='Also delete planner data, configuration and server recovery keys')
    parser.add_argument('--confirm-purge', help='For irreversible --apply --purge, supply DELETE-FIELD-NOTES')
    args = parser.parse_args()
    host = Host()
    record = load(host, args.config)
    if record is None:
        unchecked=[]
        try:interfaces, table = resources(host)
        except FileNotFoundError:
            # Container-only installs do not require host nft/ip tools. With
            # no inventory there is no authority to alter legacy resources.
            interfaces=pathlib.Path('/sys/class/net/fieldnotes').exists();table=False
            unchecked.append('Legacy host firewall: inspection tool unavailable')
        from container_deployment import Engine,DOCKER
        if pathlib.Path(DOCKER).exists() and pathlib.Path('/var/run/docker.sock').exists():
            client=Engine(runner=host.runner)
            for kind,name in (('container','field-notes-app'),('container','field-notes-gateway'),('network','field-notes')):
                flags=['--all'] if kind=='container' else []
                if client.call([kind,'ls',*flags,'--filter','name=^'+('/' if kind=='container' else '')+name+'$','--format','{{.ID}}']).stdout.strip():
                    raise ValueError('Untracked Field Notes container resources remain; no removal scope can be established')
        if interfaces or table or host.account() or host.group() or any(state(host, unit).get('ActiveState') in ('active','activating','deactivating') for unit in UNITS):
            raise ValueError('Untracked Field Notes host resources remain; no removal scope can be established')
        print(json.dumps({'status': 'no recorded installation' if unchecked else 'already absent', 'changed': False, 'unchecked':unchecked}))
        return
    preflight(host, record, args.purge)
    print(json.dumps(plan(record, args.purge), indent=2))
    if not args.apply:
        return
    if os.geteuid() != 0:
        raise ValueError('--apply requires root; review --plan first')
    if args.purge and args.confirm_purge != 'DELETE-FIELD-NOTES':
        if not sys.stdin.isatty():
            raise ValueError('Purge requires --confirm-purge DELETE-FIELD-NOTES')
        if input('Delete planner data and all server keys? Type DELETE-FIELD-NOTES: ').strip() != 'DELETE-FIELD-NOTES':
            raise ValueError('Purge cancelled')
    lock = os.open(host.path(record['config'] + '/lifecycle.lock'), os.O_WRONLY | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    try:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        if not host.path(record['config'] + '/' + RECORD).exists():
            host.write_record(record)  # Adopt the verified pre-inventory installation.
        current = load(host, record['config'])
        if current is None or current['id'] != record['id']:
            raise ValueError('Installation changed while removal was being prepared')
        apply(host, current, args.purge)
    finally:
        os.close(lock)
    print('Field Notes purged.' if args.purge else 'Field Notes removed. Planner data and recovery keys are retained root-only.')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, KeyError, IndexError, TypeError, subprocess.CalledProcessError) as error:
        raise SystemExit('Uninstall stopped: ' + str(error))
