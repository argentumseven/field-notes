"""Private gateway: fail-closed ingress, no new outbound traffic, no forwarding.
The Rust app has no NET_ADMIN capability and cannot change these rules.
"""
import ipaddress
import json
import os
import pathlib
import signal
import subprocess
import time


def rules(settings):
    overlay = ipaddress.IPv4Network(settings['overlay'])
    admin = ipaddress.IPv4Network(settings['lan_prefix'])
    private=[ipaddress.IPv4Network(value) for value in ('10.0.0.0/8','172.16.0.0/12','192.168.0.0/16')]
    if overlay.prefixlen != 24 or not any(overlay.subnet_of(value) for value in private) or overlay.overlaps(admin):
        raise ValueError('Use separate private /24 tunnel and management networks')
    if not any(admin.subnet_of(value) for value in private):
        raise ValueError('Management requires a private LAN prefix')
    web, udp = settings['web_port'], settings['udp_port']
    if any(type(port) is not int or not 1024 <= port <= 65535 for port in (web, udp)) or web == 8443:
        raise ValueError('Invalid selected listener ports')
    server = overlay.network_address + 1
    # Tunnel classification precedes conntrack: established phone traffic still
    # cannot reach management, other peers, host services or the Internet.
    return f'''table inet fieldnotes {{
 chain input {{
  type filter hook input priority 0; policy drop;
  iifname "fieldnotes" ip saddr {overlay} ip daddr {server} tcp dport 8443 accept
  iifname "fieldnotes" counter drop
  ip saddr {overlay} drop
  iifname "lo" accept
  ct state established,related accept
  iifname "eth0" udp dport {udp} accept
  iifname "eth0" ip saddr {admin} tcp dport {web} accept
 }}
 chain output {{
  type filter hook output priority 0; policy drop;
  ip daddr 127.0.0.11 counter drop
  oifname "lo" accept
  ct state established,related accept
  counter drop
 }}
 chain forward {{ type filter hook forward priority 0; policy drop; }}
}}
'''


def main():
    settings = json.loads(pathlib.Path('/private/listeners.json').read_text())
    # Load the complete policy atomically before opening the tunnel. No shell,
    # hostname, proxy or resolver configuration is accepted from app data.
    subprocess.run(['nft', '-f', '-'], input=rules(settings), text=True, check=True)
    subprocess.run(['wg-quick', 'up', '/private/fieldnotes.conf'], check=True)
    pathlib.Path('/run/field-notes-ready').write_text('ready\n')
    running = True
    def stop(*_):
        nonlocal running
        running = False
    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    try:
        while running:
            time.sleep(1)
    finally:
        pathlib.Path('/run/field-notes-ready').unlink(missing_ok=True)
        subprocess.run(['ip', 'link', 'delete', 'dev', 'fieldnotes'], check=False)


if __name__ == '__main__':
    main()
