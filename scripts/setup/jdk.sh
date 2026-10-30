#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."
source scripts/dev-env.sh
if [[ -x "$FIELD_NOTES_TOOLS/jdk/bin/javac" ]]; then exit 0; fi
mkdir -p "$FIELD_NOTES_TOOLS"
url='https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jdk_x64_linux_hotspot_21.0.12.1_1.tar.gz'
curl -fsSL "$url" -o "$FIELD_NOTES_TOOLS/jdk.tar.gz"
curl -fsSL "$url.sha256.txt" -o "$FIELD_NOTES_TOOLS/jdk.sha256"
python3 - "$FIELD_NOTES_TOOLS" <<'PY'
from pathlib import Path
import hashlib,sys
p=Path(sys.argv[1])
with (p/'jdk.tar.gz').open('rb') as f:assert hashlib.file_digest(f,'sha256').hexdigest()==(p/'jdk.sha256').read_text().split()[0], 'Official JDK checksum mismatch'
PY
mkdir -p "$FIELD_NOTES_TOOLS/jdk"
tar -xf "$FIELD_NOTES_TOOLS/jdk.tar.gz" -C "$FIELD_NOTES_TOOLS/jdk" --strip-components=1
"$FIELD_NOTES_TOOLS/jdk/bin/javac" -version
