#!/usr/bin/env python3
"""Host-only supervisor. A failed gateway/app restarts the pair as one unit."""
import argparse
import json
import pathlib
import signal
import subprocess
import sys
import time
sys.dont_write_bytecode=True
from container_deployment import Engine, validate, docker_failure
from server_installation import Host, validate as validate_record, digest, TIMERS


def check_installation(host,settings,complete=False,engine=None):
    """Verify a retained installation; finalize only after explicit checks pass."""
    import fcntl,os
    from android_artifact import verify
    config=settings['config']
    lock=os.open(host.path(config+'/lifecycle.lock'),os.O_WRONLY|os.O_CREAT|os.O_NOFOLLOW,0o600)
    try:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        record=validate_record(json.loads(host.trusted(config+'/installation.json')))
        if record['runtime']!='containers' or record['phase'] not in ('installing','installed') or record['docker']['settings']!=settings:
            raise ValueError('Deployment and retained installation inventory disagree')
        required={'field-notes.service','field-notes-peers.service','field-notes-peers.timer','field-notes-certificates.service','field-notes-certificates.timer'}
        if set(record['unit_hashes'])!=required:raise ValueError('Incomplete service inventory')
        for unit,expected in record['unit_hashes'].items():
            if digest(host.trusted('/etc/systemd/system/'+unit))!=expected:raise ValueError('Changed service: '+unit)
        engine=engine or Engine(runner=host.runner)
        engine.verify_images(settings)
        host.command(['/usr/bin/systemctl','is-active','field-notes.service',*TIMERS])
        apk=verify(host.path(settings['prefix']+'/downloads'))
        if any(tag.split(':')[-1]!=apk['version'] for tag in record['docker']['tags'].values()):raise ValueError('Runtime and APK versions disagree')
        engine.check_management_locally(settings,host.trusted(config+'/tls/ca.crt').decode(),apk['version'])
        if complete and record['phase']=='installing':
            record['phase']='installed';host.write_record(record)
        return record
    finally:os.close(lock)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action',choices=['run','stop','check'])
    parser.add_argument('--config',default='/etc/field-notes/deployment.json')
    parser.add_argument('--complete-installation',action='store_true',help='After successful checks, complete a retained installation that failed readiness')
    args=parser.parse_args()
    host=Host();settings=validate(json.loads(host.trusted(args.config)))
    engine=Engine()
    if args.complete_installation and args.action!='check':raise ValueError('--complete-installation requires check')
    if args.action=='check':
        check_installation(host,settings,args.complete_installation,engine)
        print('Runtime version, local TLS, source restriction and bundled APK verified.')
        print(f"Sign in from an allowed LAN client: https://{settings['lan_ip']}:{settings['web_port']}")
        return
    if args.action=='stop':engine.stop(settings);return
    # A previous process's acknowledgement cannot describe this new namespace.
    pathlib.Path(settings['runtime']).mkdir(mode=0o750,parents=True,exist_ok=True)
    import os
    os.chown(settings['runtime'],0,settings['gid']);os.chmod(settings['runtime'],0o750)
    pathlib.Path(settings['runtime'],'status.json').unlink(missing_ok=True)
    running=True
    def stop(*_):
        nonlocal running
        running=False
    signal.signal(signal.SIGTERM,stop);signal.signal(signal.SIGINT,stop)
    try:
        engine.start(settings,pathlib.Path(settings['config'])/'compose.json')
        while running:
            if not engine.healthy(settings):raise ValueError('Runtime pair failed; recreating the private namespace')
            time.sleep(2)
    except Exception:
        try:print(engine.diagnostics(settings),file=sys.stderr,flush=True)
        except Exception:pass
        # Keep the failed container's logs inspectable until the next start.
        # Stop both members so a failed app never leaves its gateway alone.
        try:engine.stop(settings,remove=False)
        except subprocess.SubprocessError as cleanup:
            print('Additional cleanup failure: '+docker_failure(cleanup),file=sys.stderr,flush=True)
        except Exception:
            print('Additional cleanup failed; inspect the owned runtime resources.',file=sys.stderr,flush=True)
        raise
    else:engine.stop(settings)


if __name__=='__main__':
    try:main()
    except (OSError,ValueError) as error:raise SystemExit('Field Notes runtime stopped: '+str(error)) from None
    except subprocess.SubprocessError as error:raise SystemExit('Field Notes runtime stopped: '+docker_failure(error)+'. Inspect: docker --host=unix:///var/run/docker.sock logs --tail 40 field-notes-app') from None
