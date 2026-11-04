#!/usr/bin/env python3
"""Publish fixed, verified Android assets through a draft; preview by default."""
import argparse,json,os,pathlib,subprocess,tempfile
from android_artifact import FILES,verify

def public_identity(info,root):
    release=json.loads((root/'release.json').read_text())
    if info['signer_sha256']!=release.get('public_android_signer_sha256') or info['version']!=release['version'] or info['version_code']!=release['android_version_code'] or info['signing']!='release':
        raise ValueError('Public APK signer/version differs from the pinned release; no alternate signing identity may be published')

def queue(folder,tag,apply=False,runner=subprocess.run):
    """Push a dedicated CI payload branch; keep binaries off main and keys local."""
    folder=pathlib.Path(folder).resolve();info=verify(folder)
    if tag!='v'+info['version']:raise ValueError('Tag must match the APK version')
    result=dict(tag=tag,branch='release-assets/'+tag,assets=sorted(FILES),source_commit=info['source_commit'])
    if not apply:return result
    if info['source_dirty'] or not info['source_commit']:raise ValueError('Queue only a clean tagged build')
    root=pathlib.Path(__file__).resolve().parents[1]
    public_identity(info,root)
    def run(args,**kw):return runner(args,cwd=root,check=True,capture_output=True,text=True,**kw)
    for ref in ('HEAD',tag+'^{}'):
        if run(['git','rev-parse',ref]).stdout.strip()!=info['source_commit']:raise ValueError('Tag and APK source differ')
    if run(['git','status','--porcelain']).stdout.strip():raise ValueError('Source checkout has uncommitted changes')
    run(['git','merge-base','--is-ancestor',info['source_commit'],'refs/remotes/origin/main'])
    ref='refs/heads/'+result['branch']
    if run(['git','ls-remote','--refs','origin',ref]).stdout.strip():raise ValueError('Release payload branch already exists; retry its existing workflow instead of overwriting it')
    with tempfile.TemporaryDirectory(prefix='field-notes-release-index-') as tmp:
        env={**os.environ,'GIT_INDEX_FILE':str(pathlib.Path(tmp)/'index')}
        run(['git','read-tree',info['source_commit']],env=env)
        for name in sorted(FILES):
            blob=run(['git','hash-object','-w',str(folder/name)]).stdout.strip()
            run(['git','update-index','--add','--cacheinfo','100644,'+blob+',release-assets/'+name],env=env)
        tree=run(['git','write-tree'],env=env).stdout.strip()
        commit=run(['git','commit-tree',tree,'-p',info['source_commit']],input='Verified Android release assets for '+tag+'\n').stdout.strip()
        run(['git','push','origin',commit+':'+ref])
    return {**result,'queued':True,'payload_commit':commit}

def publish(folder,tag,apply=False,runner=subprocess.run,latest=False):
    folder=pathlib.Path(folder).resolve();info=verify(folder)
    if tag!='v'+info['version']:raise ValueError('Tag must match the APK version')
    result=dict(tag=tag,source_commit=info['source_commit'],source_dirty=info['source_dirty'],assets=sorted(FILES),signing=info['signing'],prerelease=not latest)
    if not apply:return result
    if info['source_dirty'] or not info['source_commit']:raise ValueError('Publish only an APK built from a clean tagged source checkout')
    root=pathlib.Path(__file__).resolve().parents[1]
    public_identity(info,root)
    def run(args,**kw):return runner(args,check=True,capture_output=True,text=True,**kw)
    if run(['git','rev-parse','HEAD'],cwd=root).stdout.strip()!=info['source_commit'] or run(['git','rev-parse',tag+'^{}'],cwd=root).stdout.strip()!=info['source_commit']:
        raise ValueError('APK source and checked-out tag must be identical')
    if run(['git','status','--porcelain'],cwd=root).stdout.strip():raise ValueError('Source checkout has uncommitted changes')
    run(['git','merge-base','--is-ancestor',info['source_commit'],'refs/remotes/origin/main'],cwd=root)
    base=['gh','release'];repo=['--repo','argentumseven/field-notes']
    try:existing=json.loads(run([*base,'view',tag,*repo,'--json','isDraft,targetCommitish,assets']).stdout)
    except subprocess.CalledProcessError as error:
        if 'release not found' not in (error.stderr or '').lower():raise
        existing=None
    with tempfile.TemporaryDirectory(prefix='field-notes-release-') as temporary:
        work=pathlib.Path(temporary);notes=work/'notes.md'
        notes.write_text(f"Field Notes {info['version']} for ARM64 Android/GrapheneOS.\n\nDownload field-notes.apk, verify its SHA-256 checksum and signing certificate. Preserve the same signer for in-place updates.\n\nThis is a development release. Install the matching server first. No Google services or system VPN slot are required.\n\nSource: {info['source_commit']}\n")
        if existing is None:
            run([*base,'create',tag,*repo,'--verify-tag','--target',info['source_commit'],'--draft',*(['--prerelease'] if not latest else []),'--title','Field Notes '+info['version'],'--notes-file',str(notes)])
            existing={'isDraft':True,'targetCommitish':info['source_commit'],'assets':[]}
        if existing['targetCommitish']!=info['source_commit']:raise ValueError('Existing release points to different source; refusing replacement')
        present={asset['name'] for asset in existing['assets']}
        if present-FILES:raise ValueError('Existing release contains unrelated assets; review it manually')
        if present:
            saved=work/'existing';saved.mkdir()
            run([*base,'download',tag,*repo,'--dir',str(saved)])
            for name in present:
                if (saved/name).read_bytes()!=(folder/name).read_bytes():raise ValueError('Existing asset differs; no overwrite is allowed')
        missing=FILES-present
        if missing:
            if not existing['isDraft']:raise ValueError('Published release is incomplete; no silent mutation')
            run([*base,'upload',tag,*repo,*[str(folder/name) for name in sorted(missing)]])
        downloaded=work/'verified';downloaded.mkdir()
        run([*base,'download',tag,*repo,'--dir',str(downloaded)])
        if verify(downloaded)!=info:raise ValueError('Remote artifact metadata differs')
        for name in FILES:
            if (downloaded/name).read_bytes()!=(folder/name).read_bytes():raise ValueError('Remote asset differs')
        if existing['isDraft'] or latest:run([*base,'edit',tag,*repo,'--draft=false',*(['--latest','--prerelease=false'] if latest else ['--prerelease'])])
    return {**result,'published':True}

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--directory',required=True);parser.add_argument('--tag',required=True);parser.add_argument('--apply',action='store_true');parser.add_argument('--queue',action='store_true',help='Use the verified prebuilt GitHub Actions publication path; --apply pushes its payload branch');parser.add_argument('--latest',action='store_true',help='Publish as a normal latest release for the stable download URL; release notes still identify development builds');args=parser.parse_args()
    try:print(json.dumps(queue(args.directory,args.tag,args.apply) if args.queue else publish(args.directory,args.tag,args.apply,latest=args.latest),indent=2))
    except (ValueError,OSError,subprocess.SubprocessError) as error:raise SystemExit('Android publication stopped: '+str(error))
