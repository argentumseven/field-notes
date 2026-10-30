#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."
source scripts/dev-env.sh
[[ $(uname -s) == Linux ]] || { echo 'Server builds require Linux.' >&2; exit 1; }
cargo build --locked --release -j "${FIELD_NOTES_JOBS:-4}"
arch=$(uname -m)
version=$(python3 -c 'import json; print(json.load(open("release.json"))["version"])')
mkdir -p dist/server
stage=$(mktemp -d dist/server/.stage.XXXXXX)
trap 'rm -rf -- "$stage"' EXIT
install -m 755 target/release/field-notes-server "$stage/field-notes-server"
mkdir -p "$stage/scripts" "$stage/deploy" "$stage/docs"
cp scripts/install-server.sh scripts/install-server.py scripts/peer-agent.py scripts/tls.py "$stage/scripts/"
cp deploy/field-notes.service "$stage/deploy/"
cp README.md release.json "$stage/"
cp -R docs/. "$stage/docs/"
(cd "$stage" && sha256sum field-notes-server > SHA256SUMS)
"$stage/field-notes-server" help
artifact="dist/server/field-notes-$version-linux-$arch.tar.gz"
tar -czf "$artifact" -C "$stage" .
sha256sum "$artifact" > "$artifact.sha256"
package="dist/server/linux-$arch"
rm -rf -- "$package"
mv "$stage" "$package"
trap - EXIT
printf 'Built %s\n' "$artifact"
