import importlib.util,json,pathlib,socket,ssl,subprocess,tempfile,time,unittest,urllib.request,urllib.error
ROOT=pathlib.Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('tls_host',ROOT/'scripts/tls.py');tls=importlib.util.module_from_spec(spec);spec.loader.exec_module(tls)

def port():
 with socket.socket() as s:s.bind(('127.0.0.1',0));return s.getsockname()[1]

class DirectTls(unittest.TestCase):
 def test_private_ca_direct_https_and_actual_source_acl(self):
  with tempfile.TemporaryDirectory(prefix='field-notes-tls-') as root:
   root=pathlib.Path(root);certs=root/'tls'
   fingerprint=tls.provision(certs,['127.0.0.1','notes.home.arpa'])
   self.assertEqual(len(fingerprint),64);self.assertEqual((certs/'private/ca.key').stat().st_mode&0o777,0o600)
   with self.assertRaises(ValueError):tls.provision(certs,['127.0.0.1'])
   self.assertEqual(tls.provision(certs,['127.0.0.1','notes.home.arpa'],renew=True),fingerprint)
   unchanged=(certs/'server.crt').read_bytes()
   self.assertEqual(tls.provision(certs,['127.0.0.1','notes.home.arpa'],renew=True,if_needed=True),fingerprint)
   self.assertEqual((certs/'server.crt').read_bytes(),unchanged)
   binary=str(ROOT/'target/debug/field-notes-server');data=root/'data';key=root/'master.key'
   subprocess.run([binary,'init','--data',str(data),'--key-file',str(key),'--name','owner'],input=b'only-a-disposable-tls-password',check=True,capture_output=True)
   for allow,expected in [('127.0.0.1/32',200),('192.168.1.0/24',403)]:
    admin,device=port(),port()
    child=subprocess.Popen([binary,'serve','--data',str(data),'--key-file',str(key),'--admin-bind',f'127.0.0.1:{admin}','--device-bind',f'127.0.0.1:{device}','--admin-allow',allow,'--origin',f'https://127.0.0.1:{admin}','--tls-cert',str(certs/'server.crt'),'--tls-key',str(certs/'server.key')],stdout=subprocess.DEVNULL,stderr=subprocess.PIPE)
    try:
     context=ssl.create_default_context(cafile=str(certs/'ca.crt'));context.minimum_version=ssl.TLSVersion.TLSv1_3
     opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPSHandler(context=context))
     request=urllib.request.Request(f'https://127.0.0.1:{admin}/health',headers={'X-Forwarded-For':'192.168.1.15','X-Real-IP':'192.168.1.15'})
     status=None
     for _ in range(100):
      try:
       with opener.open(request,timeout=2) as response:status=response.status
       break
      except urllib.error.HTTPError as e:status=e.code;break
      except OSError:
       if child.poll() is not None:self.fail(child.stderr.read().decode())
       time.sleep(.05)
     self.assertEqual(status,expected)
     # Negotiating only TLS 1.2 must fail, without plaintext fallback.
     old=ssl.create_default_context(cafile=str(certs/'ca.crt'));old.maximum_version=ssl.TLSVersion.TLSv1_2
     with self.assertRaises(ssl.SSLError):
      with socket.create_connection(('127.0.0.1',admin),timeout=2) as connection:old.wrap_socket(connection,server_hostname='127.0.0.1')
    finally:child.terminate();child.communicate(timeout=5)

if __name__=='__main__':unittest.main()
