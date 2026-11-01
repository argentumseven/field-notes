import importlib.util,json,pathlib,shutil,sys,tempfile,types,unittest
ROOT=pathlib.Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT/'scripts'))
spec=importlib.util.spec_from_file_location('publisher',ROOT/'scripts/publish-android.py');publisher=importlib.util.module_from_spec(spec);spec.loader.exec_module(publisher)
import test_android_artifact as artifact_fixture

class PublishAndroid(unittest.TestCase):
 def fixture(self,folder,clean=True):
  info=artifact_fixture.AndroidArtifact().bundle(folder);info.update(source_dirty=not clean,source_commit='c'*40)
  (folder/'android.json').write_text(json.dumps(info));return info
 def test_preview_and_dirty_build_never_contact_github(self):
  with tempfile.TemporaryDirectory() as tmp:
   folder=pathlib.Path(tmp);self.fixture(folder,False);calls=[]
   self.assertEqual(len(publisher.publish(folder,'v0.3.1')['assets']),4)
   with self.assertRaises(ValueError):publisher.publish(folder,'v0.3.1',True,lambda *a,**k:calls.append(a))
   self.assertFalse(calls)
 def test_interrupted_own_draft_resumes_without_overwrite_and_publishes_only_after_verification(self):
  with tempfile.TemporaryDirectory() as tmp:
   folder=pathlib.Path(tmp);self.fixture(folder);calls=[];present={'field-notes.apk'}
   def run(args,**kw):
    calls.append(args)
    if args[0]=='git':return types.SimpleNamespace(stdout='' if 'status' in args else 'c'*40+'\n')
    action=args[2]
    if action=='view':out=json.dumps({'isDraft':True,'targetCommitish':'c'*40,'assets':[{'name':n} for n in present]})
    elif action=='download':
     destination=pathlib.Path(args[args.index('--dir')+1])
     for name in present:shutil.copyfile(folder/name,destination/name)
     out=''
    elif action=='upload':
     self.assertNotIn('--clobber',args);present.update(pathlib.Path(a).name for a in args[6:]);out=''
    elif action=='edit':self.assertEqual(present,publisher.FILES);out=''
    else:raise AssertionError(args)
    return types.SimpleNamespace(stdout=out)
   self.assertTrue(publisher.publish(folder,'v0.3.1',True,run)['published'])
   self.assertEqual(calls[-1][2],'edit');self.assertFalse(any('delete' in a or '--clobber' in a for a in calls))
 def test_unrelated_release_source_is_never_changed(self):
  with tempfile.TemporaryDirectory() as tmp:
   folder=pathlib.Path(tmp);self.fixture(folder);calls=[]
   def run(args,**kw):
    calls.append(args)
    if args[0]=='git':out='' if 'status' in args else 'c'*40+'\n'
    else:out=json.dumps({'isDraft':True,'targetCommitish':'d'*40,'assets':[]})
    return types.SimpleNamespace(stdout=out)
   with self.assertRaises(ValueError):publisher.publish(folder,'v0.3.1',True,run)
   self.assertFalse(any(a[0]=='gh' and a[2] in ('upload','edit','create') for a in calls))

if __name__=='__main__':unittest.main()
