import hashlib,json,pathlib,sys,tempfile,unittest
ROOT=pathlib.Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT/'scripts'))
from android_artifact import verify

class AndroidArtifact(unittest.TestCase):
 def bundle(self,folder):
  data=b'PK synthetic artifact; real signatures verified by the package command';digest=hashlib.sha256(data).hexdigest()
  info=dict(version='0.3.1',version_code=4,architecture='arm64-v8a',signing='development',sha256=digest,signer_sha256='a'*64,source_commit=None,source_dirty=True)
  (folder/'field-notes.apk').write_bytes(data);(folder/'android.json').write_text(json.dumps(info))
  (folder/'field-notes.apk.sha256').write_text(digest+'  field-notes.apk\n');(folder/'field-notes.apk.signer.txt').write_text('Signer #1 certificate SHA-256 digest: '+'a'*64+'\n')
  return info
 def test_checksum_version_architecture_and_signer_report_are_bound(self):
  with tempfile.TemporaryDirectory() as tmp:
   folder=pathlib.Path(tmp);info=self.bundle(folder);self.assertEqual(verify(folder,'0.3.1'),info)
   with self.assertRaises(ValueError):verify(folder,'9.9.9')
   (folder/'field-notes.apk').write_bytes(b'different bytes')
   with self.assertRaises(ValueError):verify(folder)
   self.bundle(folder);(folder/'field-notes.apk.signer.txt').write_text('no verified signer')
   with self.assertRaises(ValueError):verify(folder)
 def test_missing_extra_and_symlink_files_are_refused(self):
  with tempfile.TemporaryDirectory() as tmp:
   folder=pathlib.Path(tmp);self.bundle(folder);(folder/'unexpected').write_text('unrelated')
   with self.assertRaises(ValueError):verify(folder)
   (folder/'unexpected').unlink();(folder/'field-notes.apk').unlink();(folder/'field-notes.apk').symlink_to('/etc/passwd')
   with self.assertRaises(ValueError):verify(folder)

if __name__=='__main__':unittest.main()
