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
import re
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


def accounts(host,record):
    """Read names/roles/status as the app UID, without stopping it or reading hashes."""
    if record is None or record['runtime']!='containers' or record['phase'] not in ('installing','installed'):
        raise ValueError('An existing inventoried container installation is required')
    settings=validate(record['docker']['settings'])
    folder=os.open(host.path(record['data']),os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
    try:
        info=os.fstat(folder)
        if info.st_uid!=settings['uid'] or info.st_mode&0o022:raise ValueError('Unsafe data directory ownership or permissions')
        code='''import json,os,sqlite3,stat,sys
from contextlib import closing
os.fchdir(int(sys.argv[1]))
if os.geteuid()==0:
 try:
  os.chroot('.');os.chdir('/')
  os.setgroups([]);os.setgid(int(sys.argv[3]));os.setuid(int(sys.argv[2]))
 except OSError:raise SystemExit(71)
os.close(int(sys.argv[1]))
info=os.stat('field-notes.sqlite3',follow_symlinks=False)
if not stat.S_ISREG(info.st_mode) or info.st_nlink!=1 or info.st_uid!=os.geteuid() or info.st_mode&0o077:
 raise SystemExit(72)
try:
 with closing(sqlite3.connect('file:field-notes.sqlite3?mode=ro',uri=True,timeout=5)) as db:
  db.execute('PRAGMA trusted_schema=OFF')
  db.execute('PRAGMA temp_store=MEMORY')
  rows=db.execute('SELECT name,role,active FROM users ORDER BY name COLLATE NOCASE').fetchall()
except sqlite3.Error as error:
 print(getattr(error,'sqlite_errorname','SQLITE_ERROR'));raise SystemExit(73)
print(json.dumps([dict(name=n,role=r,active=bool(a)) for n,r,a in rows]))
'''
        if os.geteuid() not in (0,settings['uid']):raise ValueError('Run account inspection as host root')
        try:
            # Load the interpreter/SQLite before restricting its root to the
            # data directory, then discard root and all supplementary groups.
            # SQLite canonicalizes paths, so fchdir alone cannot support data
            # beneath a root-private ancestor. No outside directory FDs or
            # terminal input remain; only result/error pipes are inherited.
            result=subprocess.run(['/usr/bin/python3','-I','-B','-c',code,str(folder),str(settings['uid']),str(settings['gid'])],pass_fds=(folder,),stdin=subprocess.DEVNULL,capture_output=True,text=True,check=True,timeout=10)
        except subprocess.CalledProcessError as error:
            if error.returncode==71:raise ValueError('Account inspection requires host-root permission to isolate the reader and drop privileges') from None
            if error.returncode==72:raise ValueError('Unsafe database ownership, mode or link count') from None
            if error.returncode==73:
                reason=error.stdout.strip()
                if not re.fullmatch(r'SQLITE_[A-Z_]+',reason):reason='SQLITE_ERROR'
                raise ValueError('Cannot read installed accounts ('+reason+'); no installation was created') from None
            raise ValueError('Cannot read installed accounts; check database ownership and schema. No installation was created.') from None
        return json.loads(result.stdout)
    finally:os.close(folder)


def select_account(rows,name,rename_to=None):
    # Match SQLite NOCASE: ASCII folds, other Unicode letters stay distinct.
    def key(value):return ''.join(c.lower() if c.isascii() else c for c in value)
    target=next((row for row in rows if key(row['name'])==key(name) and row['active']),None)
    if target is None:raise ValueError('No active account with that name; no password changed. Use --list-users to see existing names.')
    if rename_to is not None:
        if not re.fullmatch(r'[A-Za-z0-9._ -]{2,64}',rename_to):raise ValueError('Use a new name with 2–64 letters, numbers, spaces, dots, underscores or hyphens')
        if any(key(row['name'])==key(rename_to) and row is not target for row in rows):raise ValueError('That account name is already in use; no changes made')
    return target


def recover(host,record,name,password,engine=None,rename_to=None):
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
    target=select_account(accounts(host,record),name,rename_to)
    original_name=target['name'];final_name=rename_to if rename_to is not None else original_name
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
                    target=db.execute('SELECT id FROM users WHERE name=? AND active=1',(original_name,)).fetchone()
                    if target is None:raise ValueError('No active account with that name; no password changed')
                    uid=target[0]
                    conflict=db.execute('SELECT id FROM users WHERE name=?',(final_name,)).fetchone()
                    if conflict is not None and conflict[0]!=uid:raise ValueError('That account name is already in use; no changes made')
                    db.execute('UPDATE users SET name=?,hash=? WHERE id=?',(final_name,hashed,uid))
                    db.execute('DELETE FROM sessions WHERE user_id=? AND id NOT IN (SELECT session_id FROM device_sessions)',(uid,))
                    db.execute('DELETE FROM login_limits WHERE key IN (?,?)',('name:'+original_name.lower(),'name:'+final_name.lower()))
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
    return final_name


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config',default='/etc/field-notes')
    parser.add_argument('--name',default='admin',help='Existing account name (default: admin)')
    parser.add_argument('--list-users',action='store_true',help='Read existing names, roles and active status; no passwords or service interruption')
    parser.add_argument('--rename-to',help='Also rename the selected account, keeping its ID, role and pairings')
    args=parser.parse_args()
    if os.geteuid()!=0:raise ValueError('Run local password recovery as root')
    host=Host();record=load(host,args.config)
    if args.list_users:
        if args.rename_to is not None:raise ValueError('--list-users cannot rename an account')
        rows=accounts(host,record)
        for row in rows:print(json.dumps(row['name'])+' · '+row['role']+' · '+('active' if row['active'] else 'disabled'))
        if not rows:print('No existing accounts. No installation was created.')
        return
    select_account(accounts(host,record),args.name,args.rename_to)
    password=prompt('New password (12+ characters; Escape cancels): ',secret=True)
    if password!=prompt('Confirm new password: ',secret=True):raise ValueError('Passwords do not match; nothing changed')
    lock=os.open(host.path(args.config+'/lifecycle.lock'),os.O_WRONLY|os.O_CREAT|os.O_NOFOLLOW,0o600)
    try:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        final_name=recover(host,record,args.name,password,rename_to=args.rename_to)
    finally:os.close(lock)
    print('Password changed. Sign in as '+final_name+'. Existing phone pairings and tasks are retained.')


if __name__=='__main__':
    try:main()
    except (Cancelled,KeyboardInterrupt):print('Password recovery cancelled.',file=sys.stderr);sys.exit(130)
    except subprocess.SubprocessError:
        raise SystemExit('Password recovery stopped: a local Docker/systemd operation failed. Check service status; no credentials were logged.') from None
    except (OSError,ValueError,sqlite3.Error) as error:
        raise SystemExit('Password recovery stopped: '+str(error)) from None
