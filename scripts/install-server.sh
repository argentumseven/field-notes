#!/usr/bin/env bash
set -euo pipefail
here="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
if (( $# == 0 )); then
  # One-command, interactive source installation; the Python wizard confirms
  # configuration before any packages, toolchains or installed state are changed.
  exec python3 "$here/install-server.py" --apply --build-from-source --interactive
fi
# Explicit --plan/--apply and advanced flags continue to work as documented.
exec python3 "$here/install-server.py" "$@"
