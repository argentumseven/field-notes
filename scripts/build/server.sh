#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."
source scripts/dev-env.sh
[[ $(uname -s) == Linux ]] || { echo 'Server builds require Linux.' >&2; exit 1; }
cargo build --locked --release -j "${FIELD_NOTES_JOBS:-4}"
arch=$(uname -m)
mkdir -p "dist/server/linux-$arch"
install -m 755 target/release/field-notes-server "dist/server/linux-$arch/field-notes-server"
cp deploy/Caddyfile.example deploy/field-notes.service "dist/server/linux-$arch/"
(cd "dist/server/linux-$arch" && sha256sum field-notes-server > SHA256SUMS)
"dist/server/linux-$arch/field-notes-server" help

package="dist/server/linux-$arch"
mkdir -p "$package/scripts" "$package/deploy" "$package/docs"
cp scripts/install-server.sh scripts/install-server.py scripts/peer.py "$package/scripts/"
cp deploy/Caddyfile.example deploy/field-notes.service "$package/deploy/"
cp README.md release.json "$package/"
cp -R docs/. "$package/docs/"
tar -czf "dist/server/field-notes-0.1.0-linux-$arch.tar.gz" -C "$package" .
sha256sum "dist/server/field-notes-0.1.0-linux-$arch.tar.gz" > "dist/server/field-notes-0.1.0-linux-$arch.tar.gz.sha256"
