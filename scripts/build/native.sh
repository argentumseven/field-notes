#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."
source scripts/dev-env.sh
target=${1:-linux}
case "$target" in
 linux) mkdir -p dist/native/linux-x64; (cd native && go build -trimpath -buildmode=c-shared -o ../dist/native/linux-x64/libfieldnotes.so ./bridge);;
 android)
  compiler="$ANDROID_HOME/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android23-clang"
  [[ -x $compiler ]] || { echo 'Run bash scripts/setup/android.sh first.' >&2; exit 1; }
  mkdir -p dist/native/android-arm64
  (cd native && CGO_ENABLED=1 GOOS=android GOARCH=arm64 CC="$compiler" go build -trimpath -buildmode=c-shared -o ../dist/native/android-arm64/libfieldnotes.so ./bridge);;
 *) echo 'Supported targets: linux or android. Other native targets are deferred.' >&2; exit 1;;
esac
