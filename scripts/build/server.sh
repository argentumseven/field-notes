#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."
source scripts/dev-env.sh
python3 tools/sync-item-contexts.py --check
python3 tools/sync-brand-icons.py --check
[[ $(uname -s) == Linux ]] || { echo 'Server builds require Linux.' >&2; exit 1; }
arch=$(uname -m)
version=$(python3 -c 'import json; print(json.load(open("release.json"))["version"])')
mkdir -p dist/server
stage=$(mktemp -d dist/server/.stage.XXXXXX)
trap 'rm -rf -- "$stage"' EXIT
legacy_args=()
if [[ -n ${FIELD_NOTES_ANDROID_LEGACY_KEYSTORE:-} || -n ${FIELD_NOTES_ANDROID_LEGACY_SIGNER:-} ]]; then
  [[ -n ${FIELD_NOTES_ANDROID_LEGACY_KEYSTORE:-} && -n ${FIELD_NOTES_ANDROID_LEGACY_SIGNER:-} ]] || { echo 'Both original legacy key and certificate are required; no replacement is generated.' >&2; exit 1; }
  legacy_args=(--legacy-key "$FIELD_NOTES_ANDROID_LEGACY_KEYSTORE" --legacy-signer "$FIELD_NOTES_ANDROID_LEGACY_SIGNER" --legacy-alias "${FIELD_NOTES_ANDROID_LEGACY_ALIAS:-androiddebugkey}")
fi
if [[ -n ${FIELD_NOTES_ANDROID_APK:-} && -n ${FIELD_NOTES_ANDROID_DOWNLOADS:-} ]]; then
  echo 'Choose FIELD_NOTES_ANDROID_APK or FIELD_NOTES_ANDROID_DOWNLOADS, not both.' >&2; exit 1
elif [[ -n ${FIELD_NOTES_ANDROID_DOWNLOADS:-} ]]; then
  python3 -B scripts/android_artifact.py --downloads "$FIELD_NOTES_ANDROID_DOWNLOADS" --output "$stage/downloads" "${legacy_args[@]}"
elif [[ -n ${FIELD_NOTES_ANDROID_APK:-} ]]; then
  python3 -B scripts/android_artifact.py --apk "$FIELD_NOTES_ANDROID_APK" --output "$stage/downloads" "${legacy_args[@]}"
else
  python3 -B scripts/android_artifact.py --public --output "$stage/downloads" "${legacy_args[@]}"
fi
cargo build --locked --release -j "${FIELD_NOTES_JOBS:-4}"
bash scripts/build/containers.sh
install -m 755 target/release/field-notes-server "$stage/field-notes-server"
mkdir -p "$stage/scripts" "$stage/deploy" "$stage/docs"
cp scripts/install-server.sh scripts/install-server.py scripts/uninstall-server.sh scripts/uninstall-server.py scripts/server_installation.py scripts/container_deployment.py scripts/container-runtime.py scripts/peer-agent.py scripts/tls.py "$stage/scripts/"
cp deploy/field-notes.service "$stage/deploy/"
cp README.md release.json "$stage/"
cp dist/containers/images.json "$stage/images.json"
cp "dist/containers/field-notes-$version-linux-$arch-images.tar.gz" "$stage/images.tar.gz"
cp -R docs/. "$stage/docs/"
cp scripts/android_artifact.py "$stage/scripts/"
cp scripts/upgrade-server.py scripts/upgrade-server.sh "$stage/scripts/"
cp scripts/terminal_input.py scripts/reset-password.py scripts/clock_health.py "$stage/scripts/"
(cd "$stage" && sha256sum images.tar.gz > images.tar.gz.sha256 && sha256sum field-notes-server images.json images.tar.gz > SHA256SUMS)
"$stage/field-notes-server" help
artifact="dist/server/field-notes-$version-linux-$arch.tar.gz"
tar -czf "$artifact" -C "$stage" .
(cd dist/server && sha256sum "$(basename "$artifact")" > "$(basename "$artifact").sha256")
package="dist/server/linux-$arch"
rm -rf -- "$package"
mv "$stage" "$package"
trap - EXIT
printf 'Built %s\n' "$artifact"
