#!/usr/bin/env python3
"""Stage a same-version app-only maintenance bundle without changing the installation.

Use the original, verified dist/server/linux-<arch> folder as --base. The
replacement app must already exist by immutable Docker ID. No Android build,
signing key, mutable application tag, or container restart is involved.
"""
import argparse
import copy
import gzip
import hashlib
import json
import os
import pathlib
import shutil
import subprocess
import tempfile

DOCKER = ['docker', '--host=unix:///var/run/docker.sock']
PREFIX = pathlib.Path('/etc/field-notes')
APP_BINARY = '/usr/local/bin/field-notes-server'


def digest(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def files(folder):
    if not folder.is_dir() or folder.is_symlink():
        raise ValueError('Download directory missing or redirected')
    result = {}
    for p in folder.rglob('*'):
        if p.is_symlink() or not (p.is_file() or p.is_dir()):
            raise ValueError('Unexpected or redirected download entry')
        if p.is_file():
            result[str(p.relative_to(folder))] = digest(p)
    if not result:
        raise ValueError('No APK artifacts')
    return result


def inspect(image, role):
    output = subprocess.check_output([*DOCKER, 'image', 'inspect', image], text=True)
    rows = json.loads(output)
    if len(rows) != 1 or rows[0]['Id'] != image:
        raise ValueError(role + ': immutable image ID did not match')
    labels = rows[0]['Config'].get('Labels') or {}
    if labels.get('org.fieldnotes.component') != role or labels.get('org.fieldnotes.runtime') != '1':
        raise ValueError(role + ': image is not a verified Field Notes component')


def check_base(base, installed, original):
    if not base.is_dir() or base.is_symlink():
        raise ValueError('Preserved original server bundle missing')
    expected = ['images.tar.gz', 'images.json', 'field-notes-server', 'release.json', 'SHA256SUMS', 'images.tar.gz.sha256']
    for name in expected:
        path = base / name
        if not path.is_file() or path.is_symlink():
            raise ValueError('Original bundle file missing or redirected: ' + name)
    for entry in (base / 'SHA256SUMS').read_text().splitlines():
        fields = entry.split()
        if len(fields) != 2 or fields[1] not in ('field-notes-server', 'images.json', 'images.tar.gz'):
            raise ValueError('Unexpected bundle checksum entry')
        if digest(base / fields[1]) != fields[0]:
            raise ValueError('Original bundle checksum mismatch: ' + fields[1])
    checksum = (base / 'images.tar.gz.sha256').read_text().split()
    if len(checksum) != 2 or checksum[1] != 'images.tar.gz' or checksum[0] != digest(base / 'images.tar.gz'):
        raise ValueError('Original image archive checksum mismatch')
    old = json.loads((base / 'images.json').read_text())
    for role in ('app', 'gateway'):
        if old[role]['id'] != installed['images'][role]:
            raise ValueError('Original bundle image does not match installed ' + role)
    if digest(base / 'field-notes-server') != digest(original / 'field-notes-server'):
        raise ValueError('Preserved bundle server executable differs from installation')
    release = json.loads((base / 'release.json').read_text())
    if release != json.loads((original / 'release.json').read_text()):
        raise ValueError('Original release metadata differs from installation')
    if files(base / 'downloads') != files(original / 'downloads'):
        raise ValueError('Preserved bundle APK/download metadata differs from installation')
    return old


def get_binary(image, output):
    # Inspect the binary from the immutable replacement image itself, not a
    # possibly stale target/release binary from an unrelated build.
    container_id = subprocess.check_output(
        [*DOCKER, 'container', 'create', '--network', 'none', image, 'help'],
        text=True,
    ).strip()
    try:
        subprocess.run([*DOCKER, 'container', 'cp', container_id + ':' + APP_BINARY, str(output)], check=True)
    finally:
        subprocess.run([*DOCKER, 'container', 'rm', container_id], check=True, capture_output=True)


def save_images(ids, target):
    with target.open('wb') as output:
        with gzip.GzipFile(fileobj=output, mode='wb', compresslevel=1, mtime=0) as compressed:
            proc = subprocess.Popen([*DOCKER, 'image', 'save', *ids], stdout=subprocess.PIPE)
            try:
                shutil.copyfileobj(proc.stdout, compressed, 1024 * 1024)
            finally:
                proc.stdout.close()
            if proc.wait() != 0:
                raise ValueError('Docker image export failed')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', required=True, type=pathlib.Path, help='Original untouched dist/server/linux-x86_64 directory')
    parser.add_argument('--app-image', required=True, help='Immutable sha256: ID of the rebuilt app image')
    parser.add_argument('--output', required=True, type=pathlib.Path, help='New, nonexistent maintenance-bundle directory')
    args = parser.parse_args()
    if os.geteuid() != 0:
        raise ValueError('Run as root to inspect the retained installation')
    if not args.app_image.startswith('sha256:') or len(args.app_image) != 71 or any(c not in '0123456789abcdef' for c in args.app_image[7:]):
        raise ValueError('Specify the full immutable sha256 app image ID')
    base = args.base.resolve(strict=True)
    output = args.output.absolute()
    if output.exists() or output.is_symlink():
        raise ValueError('Refusing to overwrite existing output directory')
    installed = json.loads((PREFIX / 'deployment.json').read_text())
    original = pathlib.Path(installed['prefix'])
    if output == base or base in output.parents or original in output.parents:
        raise ValueError('Output cannot be inside the original bundle or installed prefix')
    old = check_base(base, installed, original)
    if args.app_image == installed['images']['app']:
        raise ValueError('Replacement app image is unchanged')
    inspect(args.app_image, 'app')
    inspect(installed['images']['gateway'], 'gateway')
    inspect(installed['images']['app'], 'app')
    output.parent.mkdir(parents=True, exist_ok=True)
    stage = pathlib.Path(tempfile.mkdtemp(prefix='.field-notes-maintenance-', dir=output.parent))
    try:
        shutil.copytree(base, stage, dirs_exist_ok=True)
        get_binary(args.app_image, stage / 'field-notes-server')
        if digest(stage / 'field-notes-server') == digest(original / 'field-notes-server'):
            raise ValueError('Replacement application binary is identical to the old one')
        index = copy.deepcopy(old)
        index['app']['id'] = args.app_image
        (stage / 'images.json').write_text(json.dumps(index, indent=2) + '\n')
        save_images([args.app_image, installed['images']['gateway']], stage / 'images.tar.gz')
        archive_sum = digest(stage / 'images.tar.gz')
        (stage / 'images.tar.gz.sha256').write_text(archive_sum + '  images.tar.gz\n')
        (stage / 'SHA256SUMS').write_text(''.join(digest(stage / name) + '  ' + name + '\n'
                                                  for name in ('field-notes-server', 'images.json', 'images.tar.gz')))
        # Reconfirm no download file changed in the output.
        if files(stage / 'downloads') != files(original / 'downloads'):
            raise ValueError('The installed APK/downloads changed during preparation')
        os.replace(stage, output)
    finally:
        if stage.exists():
            shutil.rmtree(stage)
    print('Prepared:', output)
    print('Old app:', installed['images']['app'])
    print('New app:', args.app_image)
    print('Unchanged gateway:', installed['images']['gateway'])
    print('Unchanged APK/downloads: verified')
    print('No running service or installation record was changed.')


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, subprocess.CalledProcessError) as error:
        raise SystemExit('Maintenance preparation stopped: ' + str(error)) from None
