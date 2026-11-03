import importlib.util,json,os,pathlib,subprocess,sys,tempfile,types,unittest
from unittest import mock
ROOT=pathlib.Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT/'scripts'))
from server_installation import Host,inventory,digest
from test_containers import settings
spec=importlib.util.spec_from_file_location('runtime_check',ROOT/'scripts/container-runtime.py');runtime=importlib.util.module_from_spec(spec);spec.loader.exec_module(runtime)

class RuntimeCheck(unittest.TestCase):
 def test_cleanup_failure_preserves_the_original_failed_start(self):
  import contextlib,io
  with tempfile.TemporaryDirectory() as root:
   c=settings();c['runtime']=root+'/runtime'
   first=subprocess.CalledProcessError(1,['docker','image','inspect'],stderr='No such image: private-fixture-output')
   second=subprocess.CalledProcessError(2,['docker','container','stop'],stderr='private-fixture-output')
   engine=mock.Mock();engine.start.side_effect=first;engine.diagnostics.return_value='app: missing';engine.stop.side_effect=second
   host=mock.Mock();host.trusted.return_value=json.dumps(c).encode();output=io.StringIO()
   with mock.patch.object(runtime,'Host',return_value=host),mock.patch.object(runtime,'Engine',return_value=engine),mock.patch.object(runtime.sys,'argv',['container-runtime.py','run']),mock.patch('os.chown'),contextlib.redirect_stderr(output):
    with self.assertRaises(subprocess.CalledProcessError) as caught:runtime.main()
   self.assertIs(caught.exception,first);self.assertNotIn('private-fixture-output',output.getvalue())
 def test_failed_readiness_retains_phase_and_success_can_explicitly_complete_it(self):
  with tempfile.TemporaryDirectory() as root:
   host=Host(root=root,owner=os.getuid(),runner=lambda *args,**kw:types.SimpleNamespace(stdout='active'))
   record=inventory('/opt/field-notes','/var/lib/field-notes','/etc/field-notes');c=settings();c['installation']=record['id']
   record.update(uid=c['uid'],gid=c['gid'],runtime='containers',docker={'settings':c,'tags':{'app':'field-notes-app:0.3.3','gateway':'field-notes-gateway:0.3.3'}})
   host.path(record['config']).mkdir(parents=True);host.path('/etc/systemd/system').mkdir(parents=True)
   for unit in ('field-notes.service','field-notes-peers.service','field-notes-peers.timer','field-notes-certificates.service','field-notes-certificates.timer'):
    content='[Unit]\nDescription=Test '+unit+'\n';host.path('/etc/systemd/system/'+unit).write_text(content);record['unit_hashes'][unit]=digest(content.encode())
   host.path(record['config']+'/tls').mkdir();host.path(record['config']+'/tls/ca.crt').write_text('fixture public CA')
   host.write_record(record);before=host.trusted(record['config']+'/installation.json')
   engine=mock.Mock();engine.check_management_locally.side_effect=ValueError('TLS unavailable')
   with mock.patch('android_artifact.verify',return_value={'version':'0.3.3'}):
    with self.assertRaisesRegex(ValueError,'TLS unavailable'):runtime.check_installation(host,c,True,engine)
    self.assertEqual(host.trusted(record['config']+'/installation.json'),before)
    engine.check_management_locally.side_effect=None
    self.assertEqual(runtime.check_installation(host,c,False,engine)['phase'],'installing')
    self.assertEqual(host.trusted(record['config']+'/installation.json'),before)
    result=runtime.check_installation(host,c,True,engine)
   expected=dict(record,phase='installed');self.assertEqual(result,expected)
   self.assertEqual(json.loads(host.trusted(record['config']+'/installation.json')),expected)

if __name__=='__main__':unittest.main()
