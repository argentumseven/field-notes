"""Build/verify fixed public APK downloads. Signing keys never enter the bundle."""
import argparse,hashlib,json,os,pathlib,re,shutil,subprocess

FILES={'field-notes.apk','field-notes.apk.sha256','field-notes.apk.signer.txt','android.json'}

def verify(folder,version=None):
    folder=pathlib.Path(folder)
    if folder.is_symlink() or not folder.is_dir() or {p.name for p in folder.iterdir()}!=FILES:raise ValueError('Incomplete Android download bundle')
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
    return info

def package(apk,output,root):
    apk=pathlib.Path(apk).resolve();output=pathlib.Path(output);release=json.loads((root/'release.json').read_text())
    sdk=pathlib.Path(os.environ['ANDROID_HOME'])/'build-tools/35.0.0'
    report=subprocess.check_output([str(sdk/'apksigner'),'verify','--print-certs',str(apk)],text=True)
    badging=subprocess.check_output([str(sdk/'aapt'),'dump','badging',str(apk)],text=True)
    expected=f"package: name='org.fieldnotes.app' versionCode='{release['android_version_code']}' versionName='{release['version']}'"
    if not badging.startswith(expected) or re.findall(r"^native-code: (.+)$",badging,re.M)!=["'arm64-v8a'"]:raise ValueError('Provide the matching signed arm64 Field Notes APK')
    signer=re.findall(r'^Signer #1 certificate SHA-256 digest: ([a-fA-F0-9]{64})$',report,re.M)
    if len(signer)!=1:raise ValueError('Expected one verified APK signer')
    source=subprocess.run(['git','rev-parse','HEAD'],cwd=root,capture_output=True,text=True)
    dirty=subprocess.run(['git','status','--porcelain'],cwd=root,capture_output=True,text=True)
    output.mkdir(mode=0o755,parents=True,exist_ok=False);shutil.copyfile(apk,output/'field-notes.apk')
    with apk.open('rb') as file:digest=hashlib.file_digest(file,'sha256').hexdigest()
    info=dict(version=release['version'],version_code=release['android_version_code'],architecture='arm64-v8a',signing='development' if 'application-debuggable' in badging else 'release',sha256=digest,signer_sha256=signer[0].lower(),source_commit=source.stdout.strip() if source.returncode==0 else None,source_dirty=bool(dirty.stdout.strip()) or dirty.returncode!=0)
    (output/'android.json').write_text(json.dumps(info,indent=2)+'\n');(output/'field-notes.apk.sha256').write_text(digest+'  field-notes.apk\n');(output/'field-notes.apk.signer.txt').write_text(report)
    for path in output.iterdir():path.chmod(0o644)
    verify(output,release['version']);return info

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--apk',required=True);parser.add_argument('--output',required=True);args=parser.parse_args()
    try:print(json.dumps(package(args.apk,args.output,pathlib.Path(__file__).resolve().parents[1]),indent=2))
    except (ValueError,OSError,KeyError,subprocess.SubprocessError) as error:raise SystemExit('APK packaging stopped: '+str(error))
