#!/usr/bin/env python3
"""Host-only supervisor. A failed gateway/app restarts the pair as one unit."""
import argparse
import json
import pathlib
import signal
import sys
import time
sys.dont_write_bytecode=True
from container_deployment import Engine, validate
from server_installation import Host


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action',choices=['run','stop'])
    parser.add_argument('--config',required=True)
    args=parser.parse_args()
    host=Host();settings=validate(json.loads(host.trusted(args.config)))
    engine=Engine()
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
    finally:engine.stop(settings)


if __name__=='__main__':
    try:main()
    except Exception:raise SystemExit('Field Notes container runtime stopped; inspect container state. No secrets were logged.') from None
