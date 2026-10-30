#!/usr/bin/env python3
"""Host-side local CA and server certificate provisioning. No proxy or ACME service.
CA private keys stay in a separate directory readable only by the invoking host
administrator. The application receives a read-only leaf key and public CA only.
"""
import argparse, fcntl, grp, hashlib, ipaddress, os, pathlib, re, subprocess, tempfile

def checked(args):
    result = subprocess.run(['openssl', *args], check=True, capture_output=True)
    return result.stdout

def provision(root, names, group=None, renew=False, if_needed=False):
    root=pathlib.Path(root)
    if not root.is_absolute() or root.is_symlink():raise ValueError('Use an absolute certificate directory without symlinks')
    if not names:raise ValueError('At least one certificate name/IP is required')
    sans=[]
    for name in names:
        try:sans.append('IP:'+str(ipaddress.ip_address(name)))
        except ValueError:
            if not re.fullmatch(r'[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?',name):raise ValueError('Invalid certificate DNS name')
            sans.append('DNS:'+name)
    gid=grp.getgrnam(group).gr_gid if group else os.getgid()
    if not renew and root.exists():raise ValueError('Certificate directory exists; initialization refused')
    if renew and not (root/'private/ca.key').is_file():raise ValueError('Existing CA key required for renewal')
    root.mkdir(mode=0o750,parents=True,exist_ok=renew);os.chown(root,os.getuid(),gid)
    private=root/'private';private.mkdir(mode=0o700,exist_ok=renew)
    with open(private/'lock','a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX)
        if renew and if_needed and (root/'server.crt').is_file():
            check=subprocess.run(['openssl','x509','-checkend',str(30*86400),'-noout','-in',str(root/'server.crt')],capture_output=True)
            if check.returncode==0:return hashlib.sha256(checked(['x509','-in',str(root/'ca.crt'),'-outform','DER'])).hexdigest()
        with tempfile.TemporaryDirectory(prefix='tls-',dir=private) as work:
            work=pathlib.Path(work)
            if not renew:
                checked(['req','-x509','-newkey','ec','-pkeyopt','ec_paramgen_curve:P-256','-noenc','-sha256','-days','3650','-subj','/CN=Field Notes local authority','-addext','basicConstraints=critical,CA:TRUE,pathlen:0','-addext','keyUsage=critical,keyCertSign,cRLSign','-keyout',str(private/'ca.key'),'-out',str(root/'ca.crt')])
                (private/'ca.key').chmod(0o600);(root/'ca.crt').chmod(0o644)
            checked(['req','-new','-newkey','ec','-pkeyopt','ec_paramgen_curve:P-256','-noenc','-subj','/CN=Field Notes','-keyout',str(work/'server.key'),'-out',str(work/'server.csr')])
            ext=work/'extensions';ext.write_text('basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature\nextendedKeyUsage=serverAuth\nsubjectAltName='+','.join(sans)+'\n')
            checked(['x509','-req','-in',str(work/'server.csr'),'-CA',str(root/'ca.crt'),'-CAkey',str(private/'ca.key'),'-set_serial','0x'+os.urandom(16).hex(),'-days','365','-sha256','-extfile',str(ext),'-out',str(work/'server.crt')])
            # A server reload accepts only matching key/certificate pairs. If a
            # crash occurs between replacements it keeps the last valid pair.
            for name in ('server.key','server.crt'):
                p=work/name;os.chown(p,os.getuid(),gid);p.chmod(0o640 if name.endswith('key') else 0o644)
                os.replace(p,root/name)
            directory=os.open(root,os.O_RDONLY|os.O_DIRECTORY)
            try:os.fsync(directory)
            finally:os.close(directory)
    der=checked(['x509','-in',str(root/'ca.crt'),'-outform','DER'])
    return hashlib.sha256(der).hexdigest()

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('action',choices=['init','renew']);p.add_argument('--directory',required=True);p.add_argument('--name',action='append',required=True);p.add_argument('--group');p.add_argument('--if-needed',action='store_true');a=p.parse_args()
    os.umask(0o077)
    print('Public CA SHA-256:',provision(a.directory,a.name,a.group,a.action=='renew',a.if_needed))
    print('Trust only ca.crt on administrator devices; retain the private CA key separately for renewal/recovery.')

if __name__=='__main__':
    try:main()
    except (ValueError,OSError,subprocess.CalledProcessError) as e:raise SystemExit('TLS provisioning stopped: '+str(e))
