"""Read the Linux host's existing clock service. Never changes time or opens sockets."""
import json, math, os, pathlib, subprocess, time

def uptime(): return time.clock_gettime(time.CLOCK_BOOTTIME)
def boot_id(): return pathlib.Path('/proc/sys/kernel/random/boot_id').read_text().strip()
def run(command):
    return subprocess.run(command, capture_output=True, text=True, timeout=2, check=True,
                          env={'PATH':'/usr/sbin:/usr/bin:/sbin:/bin','LC_ALL':'C'}).stdout.strip()
def collect(runner=run):
    report={'version':1,'captured_at':time.time(),'captured_uptime':uptime(),'boot_id':boot_id(),
            'status':'unknown','source':'unavailable','offset_seconds':None}
    try:
        synced=runner(['/usr/bin/timedatectl','show','--property=NTPSynchronized','--value'])
        if synced in ('yes','no'):
            report.update(status='synchronized' if synced=='yes' else 'unsynchronized',source='systemd')
    except (OSError,subprocess.SubprocessError): pass
    try:
        tracking=runner(['/usr/bin/chronyc','tracking'])
        values={k.strip():v.strip() for line in tracking.splitlines() if ':' in line for k,v in [line.split(':',1)]}
        offset=float(values['System time'].split()[0])
        if not math.isfinite(offset): raise ValueError('Invalid clock offset')
        report.update(source='chrony',offset_seconds=offset,
                      status='synchronized' if values.get('Leap status')=='Normal' and abs(offset)<=1 else 'unsynchronized')
    except (OSError,subprocess.SubprocessError,ValueError,KeyError,IndexError): pass
    return report

def refresh_due(path):
    try:
        value=json.loads(pathlib.Path(path).read_text())
        age=uptime()-value['captured_uptime']
        return value.get('boot_id')!=boot_id() or not 0<=age<60
    except (OSError,ValueError,KeyError,TypeError): return True
