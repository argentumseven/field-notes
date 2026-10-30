#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."
source scripts/dev-env.sh
mkdir -p "$FIELD_NOTES_TOOLS"
if [[ ! -x "$CARGO_HOME/bin/rustup" ]]; then
  curl -fsSL https://sh.rustup.rs -o "$FIELD_NOTES_TOOLS/rustup-init.sh"
  sh "$FIELD_NOTES_TOOLS/rustup-init.sh" -y --profile minimal --no-modify-path --default-toolchain 1.90.0
fi
rustup toolchain install 1.90.0 --profile minimal --component rustfmt --component clippy
npm ci --cache "$FIELD_NOTES_TOOLS/npm" --ignore-scripts --no-audit --no-fund
cargo build --locked -j 4
bash scripts/setup/go.sh
(cd native && go mod download && go test ./...)
# Pass --android for the Kotlin/Android toolchain; --native is an alias.
if [[ ${1:-} == --android || ${1:-} == --native ]]; then
  bash scripts/setup/jdk.sh
  bash scripts/setup/android.sh
  bash scripts/build/android.sh debug
fi
