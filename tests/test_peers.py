import base64,importlib.util,json,os,pathlib,subprocess,tempfile,types,unittest,uuid
ROOT=pathlib.Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('peers',ROOT/'scripts/peer-agent.py');peers=importlib.util.module_from_spec(spec);spec.loader.exec_module(peers)
def key():return base64.b64encode(os.urandom(32)).decode()
class Peers(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory();self.root=pathlib.Path(self.tmp.name);self.cfg={'desired':str(self.root/'desired'),'status':str(self.root/'status'),'state':str(self.root/'state'),'interface':'fieldnotes','overlay':'10.82.0.0/24','uid':os.getuid(),'gid':os.getgid()};self.peer={'id':str(uuid.uuid4()),'public_key':key(),'address':'10.82.0.2/32','preshared_key':key()};self.calls=[];self.current=''
 def tearDown(self):self.tmp.cleanup()
 def write(self,rows):
  path=pathlib.Path(self.cfg['desired']);path.write_text(json.dumps({'version':1,'peers':rows}));path.chmod(0o600)
 def runner(self,args,**kwargs):
  self.calls.append(args)
  if 'preshared-key' in args:
   fd=kwargs['pass_fds'][0];self.assertEqual(os.read(fd,100).decode().strip(),self.peer['preshared_key']);self.assertNotIn(self.peer['preshared_key'],' '.join(args))
  return types.SimpleNamespace(stdout=self.current)
 def test_provision_ack_and_revocation_are_scoped(self):
  self.write([self.peer]);ack=peers.reconcile(self.cfg,self.runner);self.assertIsNone(ack['error']);self.assertNotIn('preshared_key',json.dumps(ack));self.assertTrue(any('/usr/bin/wg-quick'==c[0] for c in self.calls))
  self.current=self.peer['public_key']+'\t10.82.0.2/32\n';self.calls=[];peers.reconcile(self.cfg,self.runner);self.assertEqual(len(self.calls),1)
  self.write([]);peers.reconcile(self.cfg,self.runner);self.assertTrue(any(c[-1]=='remove' for c in self.calls));self.assertEqual(json.loads(pathlib.Path(self.cfg['status']).read_text())['peers'],[])
 def test_failures_never_publish_success(self):
  self.write([self.peer])
  def fail(args,**kwargs):
   if args[1]=='set':raise subprocess.CalledProcessError(1,args)
   return types.SimpleNamespace(stdout='')
  with self.assertRaises(subprocess.CalledProcessError):peers.reconcile(self.cfg,fail)
  self.assertFalse(pathlib.Path(self.cfg['status']).exists());self.assertFalse(pathlib.Path(self.cfg['state']).exists())
 def test_reject_routes_reserved_addresses_duplicates_and_unknown_fields(self):
  for address in ['0.0.0.0/0','192.168.1.2/32','10.82.0.1/32','10.82.0.0/32','10.82.0.255/32','10.82.0.2/24']:
   self.write([{**self.peer,'address':address}])
   with self.assertRaises(ValueError):peers.reconcile(self.cfg,self.runner)
  self.write([self.peer,self.peer])
  with self.assertRaises(ValueError):peers.reconcile(self.cfg,self.runner)
  self.write([{**self.peer,'command':'something'}])
  with self.assertRaises(ValueError):peers.reconcile(self.cfg,self.runner)
  self.assertEqual(self.calls,[])
 def test_manifest_symlinks_and_exposed_secret_files_rejected(self):
  self.write([self.peer]);path=pathlib.Path(self.cfg['desired']);path.chmod(0o644)
  with self.assertRaises(ValueError):peers.reconcile(self.cfg,self.runner)
  path.chmod(0o600);path.rename(self.root/'secret');path.symlink_to(self.root/'secret')
  with self.assertRaises(ValueError):peers.reconcile(self.cfg,self.runner)
 def test_other_interfaces_and_unmanaged_peers_are_not_modified(self):
  self.write([self.peer])
  with self.assertRaises(ValueError):peers.reconcile({**self.cfg,'interface':'bungee'},self.runner)
  self.current=key()+'\t10.82.0.99/32\n'
  with self.assertRaises(ValueError):peers.reconcile(self.cfg,self.runner)
  self.assertEqual(len(self.calls),1)
if __name__=='__main__':unittest.main()
