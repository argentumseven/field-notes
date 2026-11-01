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
python3 - <<'PY'
import json,os,pathlib,re,shutil,subprocess,tempfile
root=pathlib.Path.cwd();release=json.loads((root/'release.json').read_text());sdk=pathlib.Path(os.environ['ANDROID_HOME'])/'build-tools/35.0.0'
source=root/'apps/mobile/build/outputs/apk';destination=root/'dist/android-emulator';destination.mkdir(parents=True,exist_ok=True)
with tempfile.TemporaryDirectory(dir=destination,prefix='.stage-') as temporary:
 stage=pathlib.Path(temporary)
 for path,name in [('debug/FieldNotes-debug.apk','FieldNotes-debug.apk'),('androidTest/debug/FieldNotes-debug-androidTest.apk','FieldNotes-debug-androidTest.apk')]:
  shutil.copyfile(source/path,stage/name);subprocess.run([str(sdk/'apksigner'),'verify',str(stage/name)],check=True)
 badging=subprocess.check_output([str(sdk/'aapt'),'dump','badging',str(stage/'FieldNotes-debug.apk')],text=True)
 assert badging.startswith(f"package: name='org.fieldnotes.app' versionCode='{release['android_version_code']}' versionName='{release['version']}'")
 assert re.findall(r'^native-code: (.+)$',badging,re.M)==["'x86_64'"], 'An ARM64 build overwrote Gradle outputs; repeat the emulator build'
 for file in stage.iterdir():os.replace(file,destination/file.name)
PY
echo 'Built the x86_64 emulator APK and instrumentation APK. The arm64 distribution APK is unchanged.'
