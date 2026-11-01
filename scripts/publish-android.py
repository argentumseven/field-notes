#!/usr/bin/env python3
"""Publish fixed, verified Android assets through a draft; preview by default."""
import argparse,json,pathlib,subprocess,tempfile
from android_artifact import FILES,verify

def publish(folder,tag,apply=False,runner=subprocess.run):
    folder=pathlib.Path(folder).resolve();info=verify(folder)
    if tag!='v'+info['version']:raise ValueError('Tag must match the APK version')
    result=dict(tag=tag,source_commit=info['source_commit'],source_dirty=info['source_dirty'],assets=sorted(FILES),signing=info['signing'],prerelease=True)
    if not apply:return result
    if info['source_dirty'] or not info['source_commit']:raise ValueError('Publish only an APK built from a clean tagged source checkout')
    root=pathlib.Path(__file__).resolve().parents[1]
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
            run([*base,'create',tag,*repo,'--verify-tag','--target',info['source_commit'],'--draft','--prerelease','--title','Field Notes '+info['version'],'--notes-file',str(notes)])
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
        if existing['isDraft']:run([*base,'edit',tag,*repo,'--draft=false','--prerelease'])
    return {**result,'published':True}

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--directory',required=True);parser.add_argument('--tag',required=True);parser.add_argument('--apply',action='store_true');args=parser.parse_args()
    try:print(json.dumps(publish(args.directory,args.tag,args.apply),indent=2))
    except (ValueError,OSError,subprocess.SubprocessError) as error:raise SystemExit('Android publication stopped: '+str(error))
