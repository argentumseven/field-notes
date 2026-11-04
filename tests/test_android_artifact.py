import hashlib,io,json,pathlib,sys,tempfile,types,unittest,urllib.error
from unittest import mock
ROOT=pathlib.Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT/'scripts'))
from android_artifact import verify,verify_signed,fetch_public,copy_verified,ReleaseRedirect
import android_artifact

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

class PublicAndroidArtifact(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory();self.root=pathlib.Path(self.tmp.name);self.source=self.root/'source';self.source.mkdir()
  self.info=AndroidArtifact().bundle(self.source);self.info.update(signing='release',source_commit='c'*40,source_dirty=False)
  (self.source/'android.json').write_text(json.dumps(self.info))
  self.release=dict(version=self.info['version'],android_version_code=self.info['version_code'],public_android_signer_sha256=self.info['signer_sha256'])
  (self.root/'release.json').write_text(json.dumps(self.release));self.output=self.root/'published';self.calls=[]
  def open_asset(request,timeout):
   self.calls.append((request.full_url,timeout));return io.BytesIO((self.source/request.full_url.rsplit('/',1)[1]).read_bytes())
  self.opener=types.SimpleNamespace(open=open_asset)
  self.inspection=mock.patch.object(android_artifact,'inspect',return_value=('',self.info['signer_sha256'],'release'));self.inspect=self.inspection.start()
 def tearDown(self):self.inspection.stop();self.tmp.cleanup()
 def test_version_pinned_public_download_preserves_every_published_byte_and_metadata(self):
  self.assertEqual(fetch_public(self.root,self.output,self.opener),self.info)
  self.assertEqual(len(self.calls),4)
  for url,timeout in self.calls:self.assertTrue(url.startswith('https://github.com/argentumseven/field-notes/releases/download/v0.3.1/'));self.assertEqual(timeout,60)
  for name in android_artifact.FILES:self.assertEqual((self.output/name).read_bytes(),(self.source/name).read_bytes())
  self.assertFalse(list(self.root.glob('.android-release-*')))
 def test_corrupt_or_missing_release_never_leaves_a_partial_bundle_or_creates_a_key(self):
  (self.source/'field-notes.apk').write_bytes(b'corrupt')
  with self.assertRaisesRegex(ValueError,'checksum'):fetch_public(self.root,self.output,self.opener)
  self.assertFalse(self.output.exists())
  with mock.patch.object(self.opener,'open',side_effect=urllib.error.URLError('offline')):
   with self.assertRaisesRegex(ValueError,'No replacement signing key'):fetch_public(self.root,self.output,self.opener)
  class Interrupted(io.BytesIO):
   def read(self,size):
    if self.tell():raise OSError('connection lost after partial response')
    return super().read(min(size,8))
  with mock.patch.object(self.opener,'open',return_value=Interrupted(b'partial metadata')):
   with self.assertRaisesRegex(ValueError,'No replacement signing key'):fetch_public(self.root,self.output,self.opener)
  self.assertFalse(self.output.exists());self.assertFalse(list(self.root.glob('.android-release-*')))
 def test_actual_signature_not_a_downloaded_report_controls_identity(self):
  self.inspect.return_value=('', 'b'*64,'release')
  with self.assertRaisesRegex(ValueError,'Actual APK signature'):fetch_public(self.root,self.output,self.opener)
  self.inspect.side_effect=ValueError('invalid cryptographic signature')
  with self.assertRaisesRegex(ValueError,'cryptographic signature'):fetch_public(self.root,self.output,self.opener)
  self.assertFalse(self.output.exists())
 def test_correctly_signed_other_key_wrong_code_and_dirty_public_build_are_refused(self):
  self.release['public_android_signer_sha256']='b'*64
  with self.assertRaisesRegex(ValueError,'pinned public'):verify_signed(self.source,self.release,True)
  self.release['public_android_signer_sha256']=self.info['signer_sha256'];self.release['android_version_code']+=1
  with self.assertRaisesRegex(ValueError,'version code'):verify_signed(self.source,self.release,True)
  self.release['android_version_code']-=1;self.info['source_dirty']=True;(self.source/'android.json').write_text(json.dumps(self.info))
  with self.assertRaisesRegex(ValueError,'clean tagged'):verify_signed(self.source,self.release,True)
 def test_rebundling_preserves_apk_source_instead_of_attributing_it_to_the_server_checkout(self):
  self.assertEqual(copy_verified(self.source,self.output,self.release),self.info)
  for name in android_artifact.FILES:self.assertEqual((self.output/name).read_bytes(),(self.source/name).read_bytes())
 def test_existing_output_and_oversized_metadata_are_not_accepted(self):
  self.output.mkdir()
  with self.assertRaisesRegex(ValueError,'already exists'):fetch_public(self.root,self.output,self.opener)
  self.assertFalse(self.calls);self.output.rmdir();(self.source/'android.json').write_bytes(b' '*(64*1024+1))
  with self.assertRaisesRegex(ValueError,'size limit'):fetch_public(self.root,self.output,self.opener)
  self.assertFalse(self.output.exists())
 def test_redirect_cannot_downgrade_tls_or_leave_the_release_hosts(self):
  handler=ReleaseRedirect()
  for url in ('http://github.com/file','https://retailer.example/file','https://user:secret@github.com/file'):
   with self.assertRaisesRegex(ValueError,'redirect'):handler.redirect_request(None,None,302,'',{},url)

if __name__=='__main__':unittest.main()
