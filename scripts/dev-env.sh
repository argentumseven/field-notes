#!/usr/bin/env bash
# Source this file; tools and caches remain outside the checkout.
export FIELD_NOTES_TOOLS="${FIELD_NOTES_TOOLS:-/workspace/.cache/field-notes}"
export CARGO_HOME="$FIELD_NOTES_TOOLS/cargo"
export RUSTUP_HOME="$FIELD_NOTES_TOOLS/rustup"
export ANDROID_HOME="$FIELD_NOTES_TOOLS/android-sdk"
export GRADLE_USER_HOME="$FIELD_NOTES_TOOLS/gradle"
export PATH="$CARGO_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

export PATH="$FIELD_NOTES_TOOLS/go/bin:$PATH"
export GOPATH="$FIELD_NOTES_TOOLS/gopath"
export GOCACHE="$FIELD_NOTES_TOOLS/go-cache"

if [[ -x "$FIELD_NOTES_TOOLS/jdk/bin/javac" ]]; then
  export JAVA_HOME="$FIELD_NOTES_TOOLS/jdk"
  export PATH="$JAVA_HOME/bin:$PATH"
fi
