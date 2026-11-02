"""Execute the real install flow through Rust account initialization.

Host administration/Docker are confined to fakes; all files live in a temporary
bundle and destination tree. This catches variables overwritten between the
owner prompt and the eventual container command.
"""
import contextlib,hashlib,importlib.util,io,json,os,pathlib,sqlite3,subprocess,sys,tempfile,types,unittest
from unittest import mock
ROOT=pathlib.Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT/'scripts'))
spec=importlib.util.spec_from_file_location('account_installer',ROOT/'scripts/install-server.py');installer=importlib.util.module_from_spec(spec);spec.loader.exec_module(installer)

class Initialized(Exception):pass

class InstallAccounts(unittest.TestCase):
 def test_entered_owner_survives_resource_checks_and_reaches_real_database(self):
  for entered,expected in [('admin','admin'),('','admin'),('My Admin','My Admin')]:
   with self.subTest(entered=entered),tempfile.TemporaryDirectory(prefix='field-notes-install-account-') as temporary:
    root=pathlib.Path(temporary);bundle=root/'bundle';bundle.mkdir();(bundle/'scripts').mkdir();(bundle/'downloads').mkdir()
    (bundle/'field-notes-server').write_text('bundle executable fixture')
    release=json.loads((ROOT/'release.json').read_text());(bundle/'release.json').write_text(json.dumps(release))
    images={role:{'tag':f'field-notes-{role}:'+release['version'],'id':'sha256:'+char*64} for role,char in [('app','a'),('gateway','b')]}
    (bundle/'images.json').write_text(json.dumps(images));archive=b'isolated image fixture';(bundle/'images.tar.gz').write_bytes(archive)
    (bundle/'images.tar.gz.sha256').write_text(hashlib.sha256(archive).hexdigest()+'  images.tar.gz\n')
    for helper in (ROOT/'scripts').glob('*.py'):(bundle/'scripts'/helper.name).write_text('host helper fixture\n')
    prefix,data,config=[root/key for key in ('program','data','config')]
    uid,gid=os.getuid(),os.getgid();created=[False]
    class Host:
     def check_parents(self,path):pass
     def write_record(self,record):pass
    class Engine:
     def read(self,args):return {'SecurityOptions':[]}
     def verify_images(self,c):pass
     def call(self,args,**kw):
      if args[0]=='run':
       mounts=[args[i+1] for i,arg in enumerate(args) if arg=='--mount']
       key_dir=next(m.split('source=')[1].split(',')[0] for m in mounts if m.endswith('target=/keys'))
       subprocess.run([str(ROOT/'target/debug/field-notes-server'),'init','--data',str(data),'--key-file',key_dir+'/master.key','--name',args[args.index('--name')+1]],input=kw['input'],text=True,check=True,capture_output=True)
       raise Initialized()
      return types.SimpleNamespace(stdout='')
    def account(name):
     if not created[0]:raise KeyError(name)
     return types.SimpleNamespace(pw_uid=uid,pw_gid=gid)
    def checked(args,**kw):
     if args[0]=='useradd':created[0]=True
     return types.SimpleNamespace(stdout='')
    args=['install-server.py','--apply','--non-interactive','--lan-ip','192.168.50.10','--lan-prefix','192.168.20.0/24','--web-port','10443','--udp-port','43127','--prefix',str(prefix),'--data',str(data),'--config',str(config)]
    with contextlib.ExitStack() as stack:
     stack.enter_context(mock.patch.object(installer,'__file__',str(bundle/'scripts/install-server.py')))
     stack.enter_context(mock.patch.object(sys,'argv',args))
     stack.enter_context(mock.patch.object(installer,'Host',Host))
     stack.enter_context(mock.patch('container_deployment.Engine',Engine))
     stack.enter_context(mock.patch('android_artifact.verify',return_value={'version_code':release['android_version_code']}))
     stack.enter_context(mock.patch.object(installer,'prompt',side_effect=['test-install-password!','test-install-password!',entered]))
     stack.enter_context(mock.patch.object(installer,'checked',side_effect=checked))
     stack.enter_context(mock.patch.object(installer.pwd,'getpwnam',side_effect=account))
     stack.enter_context(mock.patch.object(installer.grp,'getgrnam',side_effect=KeyError))
     stack.enter_context(mock.patch.object(installer.os,'geteuid',return_value=0))
     stack.enter_context(mock.patch.object(installer.os,'chown'))
     stack.enter_context(mock.patch.object(installer.socket,'socket'))
     stack.enter_context(contextlib.redirect_stdout(io.StringIO()))
     with self.assertRaises(Initialized):installer.main()
    with sqlite3.connect(data/'field-notes.sqlite3') as db:
     self.assertEqual(db.execute('SELECT name,role FROM users').fetchall(),[(expected,'owner')])

if __name__=='__main__':unittest.main()
