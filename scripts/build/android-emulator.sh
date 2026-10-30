#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."
source scripts/dev-env.sh
compiler="$ANDROID_HOME/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin/x86_64-linux-android26-clang"
[[ -x $compiler ]] || { echo 'Run bash scripts/setup/cloud.sh --android first.' >&2; exit 1; }
mkdir -p dist/native/android-x86_64 apps/mobile/src/androidMain/jniLibs/x86_64
(cd native && CGO_ENABLED=1 GOOS=android GOARCH=amd64 CC="$compiler" go build -trimpath -buildmode=c-shared -o ../dist/native/android-x86_64/libfieldnotes.so ./bridge)
"$compiler" -Wall -Wextra -Werror -shared -fPIC -Wl,-z,max-page-size=16384 -Idist/native/android-x86_64 native/android/bridge.c -Ldist/native/android-x86_64 -lfieldnotes -o dist/native/android-x86_64/libfieldnotes_jni.so
cp dist/native/android-x86_64/*.so apps/mobile/src/androidMain/jniLibs/x86_64/
(cd native && go build -trimpath -o ../dist/testhub ./cmd/testhub)
bash scripts/android.sh -PfieldNotesAbis=x86_64 assembleDebug assembleDebugAndroidTest
echo 'Built the x86_64 emulator APK and instrumentation APK. The arm64 distribution APK is unchanged.'
