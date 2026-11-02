"""Real Rust hashing/login/store with host commands confined to temporary trees."""
import importlib.util,json,os,pathlib,sqlite3,subprocess,sys,tempfile,types,unittest,time,urllib.request
from unittest import mock
ROOT=pathlib.Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT/'scripts'))
from server_installation import Host,inventory,digest
from test_containers import settings
spec=importlib.util.spec_from_file_location('recovery',ROOT/'scripts/reset-password.py');recovery=importlib.util.module_from_spec(spec);spec.loader.exec_module(recovery)
BINARY=ROOT/'target/debug/field-notes-server'

class LocalHost(Host):
 def __init__(self,root):super().__init__(root,owner=os.getuid());self.calls=[]
 def command(self,args,**kwargs):self.calls.append(args);return types.SimpleNamespace(stdout='active\n')

class Image:
 def __init__(self):self.calls=[]
 def objects(self,s):return {}
 def verify_images(self,s):pass
 def stop(self,s):self.calls.append('stop')
 def call(self,args,**kwargs):
  mount=args[args.index('--mount')+1];scratch=mount.split('source=')[1].split(',')[0]
  return subprocess.run([str(BINARY),'init','--data',scratch+'/data','--key-file',scratch+'/master.key','--name','recovery'],input=kwargs['input'],text=True,capture_output=True,check=True)

class PasswordRecovery(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory();self.host=LocalHost(self.tmp.name);self.engine=Image()
  self.settings=settings();self.settings.update(uid=os.getuid(),gid=os.getgid())
  self.record=inventory('/opt/field-notes','/var/lib/field-notes','/etc/field-notes')
  self.record.update(uid=os.getuid(),gid=os.getgid(),phase='installing',runtime='containers',docker={'settings':self.settings,'tags':{'app':'field-notes-app:0.3.3','gateway':'field-notes-gateway:0.3.3'}})
  for key in ('config','data','prefix'):self.host.path(self.record[key]).mkdir(parents=True,mode=0o700)
  self.host.path('/etc/systemd/system').mkdir(parents=True)
  text='[Unit]\nDescription=Field Notes fixture\n';self.host.path('/etc/systemd/system/field-notes.service').write_text(text);self.record['unit_hashes']={'field-notes.service':digest(text.encode())}
  self.host.path('/etc/field-notes/deployment.json').write_text(json.dumps(self.settings))
  self.data=self.host.path(self.record['data']);self.key=self.host.path('/etc/field-notes/master.key')
  subprocess.run([str(BINARY),'init','--data',str(self.data),'--key-file',str(self.key)],input=b'original-recovery-test-password!',capture_output=True,check=True)
  self.db=self.data/'field-notes.sqlite3'
  with sqlite3.connect(self.db) as db:
   uid=db.execute('SELECT id FROM users').fetchone()[0]
   for session in ('browser','phone'):db.execute('INSERT INTO sessions VALUES(?,?,?,9999999999,0,0)',(session,uid,session))
   db.execute("INSERT INTO device_sessions VALUES('phone','fixture-device')")
   db.execute("INSERT INTO login_limits VALUES('name:admin',8,9999999999)")
  self.saved_key=self.key.read_bytes()
 def tearDown(self):self.tmp.cleanup()
 def reset(self,name='admin'):
  with mock.patch.object(recovery.syslog,'syslog'):
   recovery.recover(self.host,self.record,name,'replacement-recovery-password!',self.engine)
 def test_password_reset_invalidates_only_browsers_and_keeps_keys_identity_role(self):
  with sqlite3.connect(self.db) as db:before=db.execute('SELECT id,hash,role FROM users').fetchone()
  self.reset()
  with sqlite3.connect(self.db) as db:
   after=db.execute('SELECT id,hash,role FROM users').fetchone()
   self.assertEqual(after[0],before[0]);self.assertEqual(after[2],before[2]);self.assertNotEqual(after[1],before[1]);self.assertTrue(after[1].startswith('$argon2id$v=19$'))
   self.assertEqual(db.execute('SELECT id FROM sessions').fetchall(),[('phone',)])
   self.assertEqual(db.execute('SELECT * FROM login_limits').fetchall(),[])
  self.assertEqual(self.key.read_bytes(),self.saved_key)
  self.assertIn(['/usr/bin/systemctl','start','field-notes.service','field-notes-certificates.timer','field-notes-peers.timer'],self.host.calls)
  from test_tls import port
  admin,device=port(),port()
  child=subprocess.Popen([str(BINARY),'serve','--dev','--data',str(self.data),'--key-file',str(self.key),'--admin-bind',f'127.0.0.1:{admin}','--device-bind',f'127.0.0.1:{device}','--origin',f'http://127.0.0.1:{admin}'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
  try:
   opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
   for _ in range(100):
    try:
     with opener.open(f'http://127.0.0.1:{admin}/health',timeout=1):break
    except OSError:time.sleep(.02)
   request=urllib.request.Request(f'http://127.0.0.1:{device}/api/login',data=json.dumps({'name':'admin','password':'replacement-recovery-password!','device':'recovery test'}).encode(),headers={'Content-Type':'application/json'})
   with opener.open(request,timeout=3) as response:self.assertEqual(json.load(response)['actor']['name'],'admin')
  finally:child.terminate();child.wait(timeout=5)
 def test_unknown_account_changes_nothing_and_restarts_services(self):
  with sqlite3.connect(self.db) as db:before=list(db.iterdump())
  with self.assertRaisesRegex(ValueError,'No active account'):self.reset('missing')
  with sqlite3.connect(self.db) as db:self.assertEqual(list(db.iterdump()),before)
  self.assertEqual(self.host.calls[-1][1],'start')
 def test_failed_transaction_rolls_back_password_and_sessions(self):
  with sqlite3.connect(self.db) as db:
   before=db.execute('SELECT hash FROM users').fetchone()[0]
   db.execute("CREATE TRIGGER fail_recovery BEFORE DELETE ON sessions BEGIN SELECT RAISE(ABORT,'injected failure'); END")
  with self.assertRaises(sqlite3.IntegrityError):self.reset()
  with sqlite3.connect(self.db) as db:
   self.assertEqual(db.execute('SELECT hash FROM users').fetchone()[0],before);self.assertEqual(db.execute('SELECT COUNT(*) FROM sessions').fetchone()[0],2)
  self.assertEqual(self.host.calls[-1][1],'start')

if __name__=='__main__':unittest.main()
