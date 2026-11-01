#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
source scripts/dev-env.sh
mkdir -p "$FIELD_NOTES_TOOLS/java-home" "$FIELD_NOTES_TOOLS/android-user"
export ANDROID_USER_HOME="$FIELD_NOTES_TOOLS/android-user"
args=("-Duser.home=$FIELD_NOTES_TOOLS/java-home")
# Use the pinned JDK default CA store unless an explicit valid override is supplied.
if [[ -n "${FIELD_NOTES_JAVA_TRUSTSTORE:-}" ]]; then
  [[ -r "$FIELD_NOTES_JAVA_TRUSTSTORE" ]] || { echo "Unreadable Java trust store: $FIELD_NOTES_JAVA_TRUSTSTORE" >&2; exit 1; }
  args+=("-Djavax.net.ssl.trustStore=$FIELD_NOTES_JAVA_TRUSTSTORE")
fi
mapfile -t proxy_parts < <(python3 - <<'PY'
import os,urllib.parse
u=urllib.parse.urlsplit(os.environ.get('HTTPS_PROXY',''))
if u.hostname:print(u.hostname);print(u.port or 80)
PY
)
if [[ ${#proxy_parts[@]} == 2 ]]; then args+=("-Dhttps.proxyHost=${proxy_parts[0]}" "-Dhttps.proxyPort=${proxy_parts[1]}" "-Dhttp.proxyHost=${proxy_parts[0]}" "-Dhttp.proxyPort=${proxy_parts[1]}"); fi
cd apps/mobile
exec ./gradlew "${args[@]}" --no-daemon "$@"
