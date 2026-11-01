#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."
source scripts/dev-env.sh
mkdir -p "$FIELD_NOTES_TOOLS" "$ANDROID_HOME/cmdline-tools" "$FIELD_NOTES_TOOLS/java-home"
if [[ ! -x "$ANDROID_HOME/cmdline-tools/19.0/bin/sdkmanager" ]]; then
  curl -fsSL https://dl.google.com/android/repository/repository2-1.xml -o "$FIELD_NOTES_TOOLS/android-repository.xml"
  python3 - "$FIELD_NOTES_TOOLS" <<'PY'
import pathlib,sys,xml.etree.ElementTree as E
p=pathlib.Path(sys.argv[1]);root=E.parse(p/'android-repository.xml').getroot()
for node in root.iter('complete'):
    if node.findtext('url')=='commandlinetools-linux-13114758_latest.zip':
        checksum=node.find('checksum')
        (p/'android-download.txt').write_text(checksum.attrib.get('type','sha1')+'\n'+checksum.text+'\n')
        break
else: raise SystemExit('Pinned SDK tools missing from official manifest; update the pin after reviewing upstream.')
PY
  curl -fsSL https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip -o "$FIELD_NOTES_TOOLS/android-tools.zip"
  python3 - "$FIELD_NOTES_TOOLS" <<'PY'
import hashlib,pathlib,sys,zipfile,tempfile,shutil
p=pathlib.Path(sys.argv[1]);algorithm,expected=(p/'android-download.txt').read_text().splitlines()
assert algorithm in ('sha1','sha256'), 'Unsupported official checksum algorithm'
with (p/'android-tools.zip').open('rb') as f: actual=hashlib.file_digest(f,algorithm).hexdigest()
assert actual==expected, 'Android SDK checksum mismatch'
with tempfile.TemporaryDirectory(dir=p) as temp:
    zipfile.ZipFile(p/'android-tools.zip').extractall(temp)
    shutil.move(str(pathlib.Path(temp)/'cmdline-tools'),str(p/'android-sdk/cmdline-tools/19.0'))
PY
  chmod +x "$ANDROID_HOME/cmdline-tools/19.0/bin/"*
fi
args=("--sdk_root=$ANDROID_HOME")
mapfile -t proxy_parts < <(python3 - <<'PY'
import os,urllib.parse
u=urllib.parse.urlsplit(os.environ.get('HTTPS_PROXY',''))
if u.hostname: print(u.hostname);print(u.port or 80)
PY
)
if [[ ${#proxy_parts[@]} == 2 ]]; then args+=(--proxy=http "--proxy_host=${proxy_parts[0]}" "--proxy_port=${proxy_parts[1]}"); fi
export SDKMANAGER_OPTS="-Duser.home=$FIELD_NOTES_TOOLS/java-home"
if [[ -n "${FIELD_NOTES_JAVA_TRUSTSTORE:-}" ]]; then
  [[ -r "$FIELD_NOTES_JAVA_TRUSTSTORE" ]] || { echo "Unreadable Java trust store: $FIELD_NOTES_JAVA_TRUSTSTORE" >&2; exit 1; }
  SDKMANAGER_OPTS+=" -Djavax.net.ssl.trustStore=$FIELD_NOTES_JAVA_TRUSTSTORE"
fi
"$ANDROID_HOME/cmdline-tools/19.0/bin/sdkmanager" "${args[@]}" 'platforms;android-36' 'build-tools;35.0.0' 'platform-tools' 'ndk;28.2.13676358' < <(printf 'y\n%.0s' {1..30})
