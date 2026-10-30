#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
source scripts/dev-env.sh
python3 scripts/check-versions.py
cargo fmt --all -- --check
cargo clippy --locked --all-targets -- -D warnings
cargo test --locked
cargo build --locked -j "${FIELD_NOTES_JOBS:-4}"
for file in apps/admin/*.js; do node --check "$file"; done
npm test
npm run test:browser
python3 -m unittest discover -s tests -p 'test_*.py'
(cd native && files=$(gofmt -l transport bridge); if [[ -n "$files" ]]; then printf "Go formatting required:\n%s\n" "$files"; exit 1; fi; go vet ./...; go test -race ./... -count=1)

if [[ ${1:-} == --android ]]; then bash scripts/build/android.sh debug; fi
