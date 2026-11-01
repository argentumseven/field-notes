#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."
source scripts/dev-env.sh
[[ $(uname -s) == Linux ]] || { echo 'Container builds require Linux.' >&2; exit 1; }
# The explicit local socket cannot be redirected by a saved context or env.
docker_local=(env -u DOCKER_HOST -u DOCKER_CONTEXT -u DOCKER_TLS -u DOCKER_TLS_VERIFY -u DOCKER_CERT_PATH docker --host=unix:///var/run/docker.sock)
"${docker_local[@]}" info --format '{{.ServerVersion}}' >/dev/null
export BUILDX_CONFIG="${BUILDX_CONFIG:-$FIELD_NOTES_TOOLS/docker-buildx}"
mkdir -p "$BUILDX_CONFIG"
version=$(python3 -c 'import json; print(json.load(open("release.json"))["version"])')
arch=$(uname -m)
cargo build --locked --release -j "${FIELD_NOTES_JOBS:-4}"
mkdir -p dist/containers
stage=$(mktemp -d dist/containers/.build.XXXXXX)
trap 'rm -rf -- "$stage"' EXIT
# Include exactly the executable, its actual ELF dependencies and their notices.
python3 - "$stage" <<'PY'
import pathlib,re,shutil,subprocess,sys
root=pathlib.Path(sys.argv[1])/'rootfs'
binary=pathlib.Path('target/release/field-notes-server')
target=root/'usr/local/bin/field-notes-server';target.parent.mkdir(parents=True);shutil.copy2(binary,target)
output=subprocess.check_output(['ldd',str(binary)],text=True)
for line in output.splitlines():
    match=re.search(r'(?:=>\s+)?(/[^\s]+)',line)
    if match:
        source=pathlib.Path(match[1]);destination=root/str(source).lstrip('/')
        destination.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(source,destination)
notices=root/'usr/share/doc/field-notes-runtime';notices.mkdir(parents=True)
for package in ['libc6','libgcc-s1']:
    source=pathlib.Path('/usr/share/doc')/package/'copyright'
    if source.exists():shutil.copy2(source,notices/(package+'-copyright'))
for entry in [root,*root.rglob('*')]:
    if entry.is_dir():entry.chmod(0o755)
    elif entry.is_file():entry.chmod(0o755 if entry==target or 'ld-linux' in entry.name else 0o644)
PY
cp deploy/containers/app.Dockerfile "$stage/Dockerfile"
"${docker_local[@]}" build --network=default --tag "field-notes-app:$version" "$stage"
# BuildKit CA secret is transient; registry credentials/proxy settings stay intact.
"${docker_local[@]}" build --secret id=build_ca,src=/etc/ssl/certs/ca-certificates.crt --file deploy/containers/gateway.Dockerfile --tag "field-notes-gateway:$version" deploy/containers
"${docker_local[@]}" run --rm --network=none --read-only --cap-drop=ALL --security-opt=no-new-privileges:true "field-notes-app:$version" help
"${docker_local[@]}" image inspect "field-notes-app:$version" "field-notes-gateway:$version" | python3 -c 'import json,sys; rows=json.load(sys.stdin); version=sys.argv[1]; assert len(rows)==2; result={r["Config"]["Labels"]["org.fieldnotes.component"]:{"id":r["Id"],"tag":"field-notes-"+r["Config"]["Labels"]["org.fieldnotes.component"]+":"+version} for r in rows}; assert set(result)=={"app","gateway"}; assert all(result[r["Config"]["Labels"]["org.fieldnotes.component"]]["tag"] in r["RepoTags"] for r in rows); print(json.dumps(result,indent=2))' "$version" > dist/containers/images.json
artifact="dist/containers/field-notes-$version-linux-$arch-images.tar.gz"
"${docker_local[@]}" save "field-notes-app:$version" "field-notes-gateway:$version" | gzip -1 > "$artifact"
sha256sum "$artifact" > "$artifact.sha256"
echo "Built app and gateway images: $artifact"
