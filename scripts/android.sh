#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
source scripts/dev-env.sh
mkdir -p "$FIELD_NOTES_TOOLS/java-home" "$FIELD_NOTES_TOOLS/android-user"
export ANDROID_USER_HOME="$FIELD_NOTES_TOOLS/android-user"
args=("-Duser.home=$FIELD_NOTES_TOOLS/java-home" "-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts")
mapfile -t proxy_parts < <(python3 - <<'PY'
import os,urllib.parse
u=urllib.parse.urlsplit(os.environ.get('HTTPS_PROXY',''))
if u.hostname:print(u.hostname);print(u.port or 80)
PY
)
if [[ ${#proxy_parts[@]} == 2 ]]; then args+=("-Dhttps.proxyHost=${proxy_parts[0]}" "-Dhttps.proxyPort=${proxy_parts[1]}" "-Dhttp.proxyHost=${proxy_parts[0]}" "-Dhttp.proxyPort=${proxy_parts[1]}"); fi
cd apps/mobile
exec ./gradlew "${args[@]}" --no-daemon "$@"
