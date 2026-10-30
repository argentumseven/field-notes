#!/usr/bin/env python3
"""Root-only peer admission helper. Never accepts a private key on the command line."""
import argparse,base64,fcntl,ipaddress,json,os,pathlib,re,subprocess,sys

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('action',choices=['add','remove','connection'])
    p.add_argument('--public-key',required=True);p.add_argument('--address',required=True);p.add_argument('--overlay',default='10.82.0.0/24')
    p.add_argument('--endpoint');p.add_argument('--ca-file');a=p.parse_args()
    key=base64.b64decode(a.public_key,validate=True)
    if len(key)!=32 or key==bytes(32):raise ValueError('Invalid public key')
    network=ipaddress.IPv4Network(a.overlay);address=ipaddress.IPv4Address(a.address)
    if not network.is_private or network.prefixlen!=24 or address not in network or int(address)-int(network.network_address) not in range(2,255):raise ValueError('Use an allocated /24 client address ending in 2–254')
    if os.geteuid()!=0:raise ValueError('Run on the server as root')
    with open('/run/field-notes-peer.lock','a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX)
        dump=subprocess.check_output(['wg','show','fieldnotes','allowed-ips'],text=True)
        peers={row.split()[0]:row.split()[1] for row in dump.splitlines() if len(row.split())==2}
        assigned=str(address)+'/32'
        if a.action=='add':
            if assigned in peers.values() and peers.get(a.public_key)!=assigned:raise ValueError('Address is already assigned to another peer')
            subprocess.run(['wg','set','fieldnotes','peer',a.public_key,'allowed-ips',assigned],check=True)
            subprocess.run(['wg-quick','save','fieldnotes'],check=True)
        elif a.action=='remove':
            subprocess.run(['wg','set','fieldnotes','peer',a.public_key,'remove'],check=True)
            subprocess.run(['wg-quick','save','fieldnotes'],check=True)
        else:
            if peers.get(a.public_key)!=assigned:raise ValueError('Approve this peer/address before generating a connection file')
            if not a.endpoint or not a.ca_file:raise ValueError('--endpoint and --ca-file are required')
            if not re.fullmatch(r'[a-zA-Z0-9.\-\[\]:]+:[0-9]{1,5}',a.endpoint):raise ValueError('Invalid endpoint')
            server=subprocess.check_output(['wg','show','fieldnotes','public-key'],text=True).strip()
            print(json.dumps({'address':assigned,'endpoint':a.endpoint,'server_key':server,'api':f'https://{network.network_address+1}:8443','ca_pem':pathlib.Path(a.ca_file).read_text()},indent=2))
if __name__=='__main__':
    try:main()
    except (ValueError,OSError,subprocess.CalledProcessError) as e:print(f'Peer operation stopped: {e}',file=sys.stderr);sys.exit(1)
