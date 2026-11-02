#!/usr/bin/env python3
"""Local root recovery for an existing Field Notes account, including 0.3.3.

Uses the installed offline app image's Argon2id implementation. Does not rebuild
Android, replace server/phone keys, change roles, or revoke phone sessions.
"""
import argparse
from contextlib import closing
import fcntl
import json
import os
import pathlib
import sqlite3
import stat
import subprocess
import sys
import syslog
import tempfile

sys.dont_write_bytecode=True
from container_deployment import Engine, validate
from server_installation import Host, load, digest, TIMERS
from terminal_input import prompt, Cancelled


def recover(host,record,name,password,engine=None):
    if record is None or record['runtime']!='containers' or record['phase'] not in ('installing','installed'):
        raise ValueError('An existing inventoried container installation is required')
    if not 12<=len(password.encode())<=256 or '\n' in password or '\r' in password:
        raise ValueError('Use a password of 12–256 bytes without line breaks')
    settings=validate(record['docker']['settings'])
    if json.loads(host.trusted(record['config']+'/deployment.json'))!=settings:
        raise ValueError('Deployment and installation inventory disagree')
    for unit,expected in record['unit_hashes'].items():
        if digest(host.trusted('/etc/systemd/system/'+unit))!=expected:
            raise ValueError('Review changed service configuration before recovery: '+unit)
    engine=engine or Engine(runner=host.runner)
    engine.objects(settings);engine.verify_images(settings)
    # Hash in a disposable, disconnected container. No secret appears in argv,
    # environment, history, Compose files or output. Works with older images.
    with tempfile.TemporaryDirectory(prefix='.password-recovery-',dir=host.path(record['config'])) as scratch:
        os.chown(scratch,settings['uid'],settings['gid']);os.chmod(scratch,0o700)
        engine.call(['run','--interactive','--rm','--network=none','--read-only','--cap-drop=ALL',
            '--security-opt=no-new-privileges:true','--user',f"{settings['uid']}:{settings['gid']}",
            '--mount',f'type=bind,source={scratch},target=/recovery',settings['images']['app'],
            'init','--data','/recovery/data','--key-file','/recovery/master.key','--name','recovery'],input=password)
        with closing(sqlite3.connect(pathlib.Path(scratch)/'data/field-notes.sqlite3')) as db:
            hashed=db.execute('SELECT hash FROM users WHERE name=?',('recovery',)).fetchone()[0]
    if not hashed.startswith('$argon2id$v=19$'):raise ValueError('Installed image did not produce an Argon2id hash')
    units=(*TIMERS,'field-notes.service');restart=[]
    for unit in units:
        state=host.command(['/usr/bin/systemctl','show','--property=ActiveState','--value',unit]).stdout.strip()
        if state in ('active','activating','reloading'):restart.append(unit)
    try:
        host.command(['/usr/bin/systemctl','stop',*units])
        engine.stop(settings)  # Owned containers only; no concurrent app writes.
        database=host.path(record['data']+'/field-notes.sqlite3')
        info=database.stat()
        if not stat.S_ISREG(info.st_mode) or info.st_nlink!=1 or info.st_uid!=record['uid'] or info.st_mode&0o077:
            raise ValueError('Unsafe database ownership, mode or link count')
        try:
            with closing(sqlite3.connect(database.as_uri()+'?mode=rw',uri=True,timeout=10)) as db:
                with db:
                    db.execute('PRAGMA foreign_keys=ON');db.execute('BEGIN IMMEDIATE')
                    target=db.execute('SELECT id FROM users WHERE name=? AND active=1',(name,)).fetchone()
                    if target is None:raise ValueError('No active account with that name; no password changed')
                    uid=target[0]
                    db.execute('UPDATE users SET hash=? WHERE id=?',(hashed,uid))
                    db.execute('DELETE FROM sessions WHERE user_id=? AND id NOT IN (SELECT session_id FROM device_sessions)',(uid,))
                    db.execute('DELETE FROM login_limits WHERE key=?',('name:'+name.lower(),))
        finally:
            # A stopped store may acquire root-created SQLite sidecars. Keep
            # these bounded known files usable by the original app identity.
            for suffix in ('-wal','-shm','-journal'):
                sidecar=host.path(str(pathlib.PurePosixPath(record['data'])/('field-notes.sqlite3'+suffix)))
                if sidecar.exists():
                    if not sidecar.is_file() or sidecar.stat().st_nlink!=1:raise ValueError('Unsafe SQLite sidecar')
                    os.chown(sidecar,record['uid'],record['gid']);sidecar.chmod(0o600)
        # Host-root recovery is recorded in the host journal, separately from
        # the encrypted in-app audit. No password, hash or account name is logged.
        syslog.syslog(syslog.LOG_AUTHPRIV|syslog.LOG_NOTICE,'Field Notes: local root password recovery completed; browser sessions invalidated, phone pairings retained')
    finally:
        if restart:host.command(['/usr/bin/systemctl','start',*reversed(restart)])


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config',default='/etc/field-notes')
    parser.add_argument('--name',default='admin',help='Existing account name (default: admin)')
    args=parser.parse_args()
    if os.geteuid()!=0:raise ValueError('Run local password recovery as root')
    host=Host();record=load(host,args.config)
    password=prompt('New password (12+ characters; Escape cancels): ',secret=True)
    if password!=prompt('Confirm new password: ',secret=True):raise ValueError('Passwords do not match; nothing changed')
    lock=os.open(host.path(args.config+'/lifecycle.lock'),os.O_WRONLY|os.O_CREAT|os.O_NOFOLLOW,0o600)
    try:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        recover(host,record,args.name,password)
    finally:os.close(lock)
    print('Password changed. Sign in as '+args.name+'. Existing phone pairings and tasks are retained.')


if __name__=='__main__':
    try:main()
    except (Cancelled,KeyboardInterrupt):print('Password recovery cancelled.',file=sys.stderr);sys.exit(130)
    except subprocess.SubprocessError:
        raise SystemExit('Password recovery stopped: a local Docker/systemd operation failed. Check service status; no credentials were logged.') from None
    except (OSError,ValueError,sqlite3.Error) as error:
        raise SystemExit('Password recovery stopped: '+str(error)) from None
