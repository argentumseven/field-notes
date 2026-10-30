#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."
source scripts/dev-env.sh
mode=${1:-debug}
[[ $mode == debug || $mode == release ]] || { echo 'Usage: android.sh [debug|release]' >&2; exit 1; }
[[ -x "$FIELD_NOTES_TOOLS/jdk/bin/javac" ]] || bash scripts/setup/jdk.sh
source scripts/dev-env.sh
bash scripts/build/native.sh android
compiler="$ANDROID_HOME/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang"
"$compiler" -Wall -Wextra -Werror -shared -fPIC -Wl,-z,max-page-size=16384 -Idist/native/android-arm64 native/android/bridge.c -Ldist/native/android-arm64 -lfieldnotes -o dist/native/android-arm64/libfieldnotes_jni.so
mkdir -p apps/mobile/src/androidMain/jniLibs/arm64-v8a
cp dist/native/android-arm64/*.so apps/mobile/src/androidMain/jniLibs/arm64-v8a/
if [[ $mode == release ]]; then
  : "${FIELD_NOTES_KEYSTORE:?Path to the stable operator release signing key is required}"
  : "${FIELD_NOTES_KEY_ALIAS:?Release alias is required}"
  : "${FIELD_NOTES_STORE_PASSWORD:?Supply securely through the local environment}"
  : "${FIELD_NOTES_KEY_PASSWORD:?Supply securely through the local environment}"
fi
variant=${mode^}
bash scripts/android.sh "assemble$variant" "lint$variant" hostTest
mkdir -p dist/android
source_apk="apps/mobile/build/outputs/apk/$mode/FieldNotes-$mode.apk"
[[ -f $source_apk ]] || { echo "Expected artifact missing: $source_apk" >&2; exit 1; }
version=$(python3 -c 'import json; print(json.load(open("release.json"))["version"])')
artifact="dist/android/field-notes-$version-android-arm64-$mode.apk"
install -m 644 "$source_apk" "$artifact"
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --print-certs "$artifact" > "$artifact.signer.txt"
"$ANDROID_HOME/build-tools/35.0.0/zipalign" -c -P 16 4 "$artifact"
sha256sum "$artifact" > "$artifact.sha256"
chmod 644 "$artifact.sha256" "$artifact.signer.txt"
python3 scripts/check-apk.py "$artifact"
echo "Built $artifact ($mode signing)."
