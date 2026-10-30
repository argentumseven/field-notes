#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."
source scripts/dev-env.sh
version=go1.26.9
if [[ -x "$FIELD_NOTES_TOOLS/go/bin/go" ]] && [[ "$("$FIELD_NOTES_TOOLS/go/bin/go" version)" == *"$version "* ]]; then exit 0; fi
mkdir -p "$FIELD_NOTES_TOOLS"
curl -fsSL 'https://go.dev/dl/?mode=json&include=all' -o "$FIELD_NOTES_TOOLS/go-releases.json"
python3 - "$FIELD_NOTES_TOOLS" "$version" <<'PY'
import json,pathlib,sys
p=pathlib.Path(sys.argv[1]);version=sys.argv[2]
f=next(f for r in json.loads((p/'go-releases.json').read_text()) if r['version']==version for f in r['files'] if f['os']=='linux' and f['arch']=='amd64' and f['kind']=='archive')
(p/'go-download.txt').write_text(f['filename']+'\n'+f['sha256']+'\n')
PY
mapfile -t artifact < "$FIELD_NOTES_TOOLS/go-download.txt"
curl -fsSL "https://go.dev/dl/${artifact[0]}" -o "$FIELD_NOTES_TOOLS/go.tar.gz"
printf '%s  %s\n' "${artifact[1]}" "$FIELD_NOTES_TOOLS/go.tar.gz" | sha256sum -c -
tar -xzf "$FIELD_NOTES_TOOLS/go.tar.gz" -C "$FIELD_NOTES_TOOLS"
"$FIELD_NOTES_TOOLS/go/bin/go" version
