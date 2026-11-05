"""Build/verify fixed public APK downloads. Signing keys never enter the bundle."""
import argparse,hashlib,json,os,pathlib,re,shutil,subprocess,tempfile,urllib.error,urllib.parse,urllib.request

FILES={'field-notes.apk','field-notes.apk.sha256','field-notes.apk.signer.txt','android.json'}

def inspect(apk,release):
    """Verify the APK itself; a checksum or downloaded signer report is not trust."""
    sdk=pathlib.Path(os.environ['ANDROID_HOME'])/'build-tools/35.0.0'
    report=subprocess.check_output([str(sdk/'apksigner'),'verify','--print-certs',str(apk)],text=True)
    badging=subprocess.check_output([str(sdk/'aapt'),'dump','badging',str(apk)],text=True)
    expected=f"package: name='org.fieldnotes.app' versionCode='{release['android_version_code']}' versionName='{release['version']}'"
    if not badging.startswith(expected) or re.findall(r"^native-code: (.+)$",badging,re.M)!=["'arm64-v8a'"]:raise ValueError('Provide the matching signed arm64 Field Notes APK')
    signers=re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]{64})$',report,re.M)
    if len(signers)!=1:raise ValueError('Expected one verified APK signer')
    return report,signers[0].lower(),'development' if 'application-debuggable' in badging else 'release'

def verify(folder,version=None,allow_legacy=True):
    folder=pathlib.Path(folder)
    if folder.is_symlink() or not folder.is_dir():raise ValueError('Incomplete Android download bundle')
    names={p.name for p in folder.iterdir()}
    if names!=FILES and not (allow_legacy and names==FILES|{'legacy'}):raise ValueError('Incomplete Android download bundle')
    for name in FILES:
        if (folder/name).is_symlink() or not (folder/name).is_file():raise ValueError('Download files must be regular files')
    info=json.loads((folder/'android.json').read_text())
    if set(info)!={'version','version_code','architecture','signing','sha256','signer_sha256','source_commit','source_dirty'}:raise ValueError('Unexpected Android artifact metadata')
    if not re.fullmatch(r'\d+\.\d+\.\d+',info['version']) or version and info['version']!=version:raise ValueError('APK and server versions differ')
    if type(info['version_code']) is not int or info['version_code']<1 or info['architecture']!='arm64-v8a' or info['signing'] not in ('development','release'):raise ValueError('Invalid Android version, architecture or signing label')
    for key in ('sha256','signer_sha256'):
        if not re.fullmatch('[a-f0-9]{64}',info[key]):raise ValueError('Invalid APK digest')
    if type(info['source_dirty']) is not bool or info['source_commit'] is not None and not re.fullmatch('[a-f0-9]{40}',info['source_commit']):raise ValueError('Invalid source metadata')
    apk=folder/'field-notes.apk'
    if not 1<=apk.stat().st_size<=150*1024*1024:raise ValueError('Unexpected APK size')
    with apk.open('rb') as file:digest=hashlib.file_digest(file,'sha256').hexdigest()
    if digest!=info['sha256'] or (folder/'field-notes.apk.sha256').read_text()!=digest+'  field-notes.apk\n':raise ValueError('APK checksum mismatch')
    if re.findall(r'^Signer #1 certificate SHA-256 digest: ([a-fA-F0-9]{64})$',(folder/'field-notes.apk.signer.txt').read_text(),re.M)!=[info['signer_sha256']]:raise ValueError('Signer report mismatch')
    if 'legacy' in names:
        legacy=verify(folder/'legacy',info['version'],allow_legacy=False)
        if legacy['version_code']!=info['version_code'] or legacy['signer_sha256']==info['signer_sha256']:raise ValueError('Legacy APK must match this version/code and retain a distinct original signer')
    return info

def verify_signed(folder,release,public=False):
    info=verify(folder,release['version'])
    if info['version_code']!=release['android_version_code']:raise ValueError('APK version code differs from release')
    _,signer,signing=inspect(pathlib.Path(folder)/'field-notes.apk',release)
    if signer!=info['signer_sha256'] or signing!=info['signing']:raise ValueError('Actual APK signature differs from artifact metadata')
    if public:
        pin=release.get('public_android_signer_sha256','')
        if not re.fullmatch('[a-f0-9]{64}',pin) or signer!=pin:raise ValueError('APK does not use the pinned public signing certificate')
        if signing!='release' or info['source_dirty'] or not info['source_commit']:raise ValueError('Public APK must be a clean tagged release build')
    if (pathlib.Path(folder)/'legacy').exists():verify_signed(pathlib.Path(folder)/'legacy',release)
    return info

def add_legacy(folder,release,key,expected_signer,alias='androiddebugkey'):
    """Keep the official bytes unchanged; sign an explicitly requested companion
    with an existing household key. That private key never enters the bundle."""
    folder=pathlib.Path(folder);key=pathlib.Path(key)
    main=verify_signed(folder,release,public=True)
    if not re.fullmatch('[a-f0-9]{64}',expected_signer) or expected_signer==main['signer_sha256']:raise ValueError('Provide the existing distinct legacy certificate')
    if key.is_symlink() or not key.is_file() or key.stat().st_uid!=os.geteuid() or key.stat().st_mode&0o077:raise ValueError('Legacy signing requires the original private key owned by the builder, mode 600; no replacement is generated')
    if (folder/'legacy').exists():raise ValueError('Legacy APK already exists; refusing replacement')
    env=os.environ.copy();env.setdefault('FIELD_NOTES_LEGACY_STORE_PASSWORD','android');env.setdefault('FIELD_NOTES_LEGACY_KEY_PASSWORD',env['FIELD_NOTES_LEGACY_STORE_PASSWORD'])
    with tempfile.TemporaryDirectory(prefix='.legacy-apk-',dir=folder.parent) as tmp:
        apk=pathlib.Path(tmp)/'signed.apk';sdk=pathlib.Path(os.environ['ANDROID_HOME'])/'build-tools/35.0.0'
        subprocess.run([str(sdk/'apksigner'),'sign','--ks',str(key.resolve()),'--ks-key-alias',alias,'--ks-pass','env:FIELD_NOTES_LEGACY_STORE_PASSWORD','--key-pass','env:FIELD_NOTES_LEGACY_KEY_PASSWORD','--out',str(apk),str((folder/'field-notes.apk').resolve())],env=env,check=True,capture_output=True)
        report,signer,signing=inspect(apk,release)
        if signer!=expected_signer:raise ValueError('Legacy APK key differs from the installed certificate. Restore the original key; pairing and data were not changed')
        digest=hashlib.sha256(apk.read_bytes()).hexdigest();output=pathlib.Path(tmp)/'bundle';output.mkdir()
        shutil.copyfile(apk,output/'field-notes.apk')
        (output/'android.json').write_text(json.dumps({**main,'sha256':digest,'signer_sha256':signer,'signing':signing},indent=2)+'\n')
        (output/'field-notes.apk.sha256').write_text(digest+'  field-notes.apk\n');(output/'field-notes.apk.signer.txt').write_text(report)
        verify_signed(output,release)
        for path in output.iterdir():path.chmod(0o644)
        output.chmod(0o755);output.rename(folder/'legacy')
    verify_signed(folder,release,public=True)
    return verify(folder/'legacy')

def copy_verified(folder,output,release):
    info=verify_signed(folder,release)
    shutil.copytree(folder,output)
    for path in pathlib.Path(output).iterdir():path.chmod(0o644)
    return info

class ReleaseRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,request,fp,code,msg,headers,newurl):
        url=urllib.parse.urlsplit(newurl)
        if url.scheme!='https' or url.hostname not in ('github.com','release-assets.githubusercontent.com') or url.username or url.password:
            raise ValueError('Unexpected public APK release redirect')
        return super().redirect_request(request,fp,code,msg,headers,newurl)

def fetch_public(root,output,opener=None):
    """Fetch the version-pinned public APK, never a new local signing identity."""
    release=json.loads((pathlib.Path(root)/'release.json').read_text())
    if not re.fullmatch(r'\d+\.\d+\.\d+',release['version']):raise ValueError('Invalid release version')
    if not re.fullmatch('[a-f0-9]{64}',release.get('public_android_signer_sha256','')):raise ValueError('Missing pinned public signing certificate')
    output=pathlib.Path(output)
    if output.exists() or output.is_symlink():raise ValueError('APK output already exists; refusing replacement')
    output.parent.mkdir(parents=True,exist_ok=True)
    opener=opener or urllib.request.build_opener(ReleaseRedirect())
    with tempfile.TemporaryDirectory(prefix='.android-release-',dir=output.parent) as tmp:
        stage=pathlib.Path(tmp)/'downloads';stage.mkdir()
        for name in sorted(FILES):
            limit=150*1024*1024 if name=='field-notes.apk' else 64*1024
            request=urllib.request.Request(f"https://github.com/argentumseven/field-notes/releases/download/v{release['version']}/{name}",headers={'User-Agent':'Field-Notes-installer'})
            try:
                with opener.open(request,timeout=60) as response, (stage/name).open('xb') as target:
                    remaining=limit
                    while True:
                        chunk=response.read(min(1024*1024,remaining+1))
                        if not chunk:break
                        remaining-=len(chunk)
                        if remaining<0:raise ValueError('Public APK release asset exceeds its size limit')
                        target.write(chunk)
            except (OSError,urllib.error.URLError) as error:
                raise ValueError(f"Could not download the published v{release['version']} Android release. No replacement signing key was created; retry after checking connectivity and release availability.") from error
        info=verify_signed(stage,release,public=True)
        for path in stage.iterdir():path.chmod(0o644)
        stage.chmod(0o755);stage.rename(output)
        return info

def package(apk,output,root):
    apk=pathlib.Path(apk).resolve();output=pathlib.Path(output);release=json.loads((root/'release.json').read_text())
    report,signer,signing=inspect(apk,release)
    source=subprocess.run(['git','rev-parse','HEAD'],cwd=root,capture_output=True,text=True)
    dirty=subprocess.run(['git','status','--porcelain'],cwd=root,capture_output=True,text=True)
    output.mkdir(mode=0o755,parents=True,exist_ok=False);shutil.copyfile(apk,output/'field-notes.apk')
    with apk.open('rb') as file:digest=hashlib.file_digest(file,'sha256').hexdigest()
    info=dict(version=release['version'],version_code=release['android_version_code'],architecture='arm64-v8a',signing=signing,sha256=digest,signer_sha256=signer,source_commit=source.stdout.strip() if source.returncode==0 else None,source_dirty=bool(dirty.stdout.strip()) or dirty.returncode!=0)
    (output/'android.json').write_text(json.dumps(info,indent=2)+'\n');(output/'field-notes.apk.sha256').write_text(digest+'  field-notes.apk\n');(output/'field-notes.apk.signer.txt').write_text(report)
    for path in output.iterdir():path.chmod(0o644)
    verify(output,release['version']);return info

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);source=parser.add_mutually_exclusive_group(required=True);source.add_argument('--apk');source.add_argument('--downloads',help='Preserve the source metadata of an already packaged APK');source.add_argument('--public',action='store_true',help='Download the matching GitHub release and verify its pinned public certificate');parser.add_argument('--output',required=True);parser.add_argument('--legacy-key');parser.add_argument('--legacy-signer');parser.add_argument('--legacy-alias',default='androiddebugkey');args=parser.parse_args()
    root=pathlib.Path(__file__).resolve().parents[1]
    try:
        if bool(args.legacy_key)!=bool(args.legacy_signer):raise ValueError('Provide both --legacy-key and --legacy-signer')
        info=fetch_public(root,args.output) if args.public else copy_verified(args.downloads,args.output,json.loads((root/'release.json').read_text())) if args.downloads else package(args.apk,args.output,root)
        if args.legacy_key:add_legacy(args.output,json.loads((root/'release.json').read_text()),args.legacy_key,args.legacy_signer,args.legacy_alias)
        print(json.dumps(info,indent=2))
    except (ValueError,OSError,KeyError,subprocess.SubprocessError) as error:raise SystemExit('APK packaging stopped: '+str(error))
