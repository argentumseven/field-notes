# ADR 0001: native shared client and independent LAN admin surface

**Historical; superseded by [ADR 0002](0002-android-kotlin-multiplatform.md).**
The paragraphs below record the earlier decision and build blocker, not the current implementation.

The native Android/Linux/Windows/iOS source uses Flutter with a Go C ABI transport
and encrypted local files whose wrapping keys live in the platform keyring. The
local file format is versioned, atomic and intentionally separate from the server
SQLite schema. This first cache/outbox does not need a second embedded SQL engine.

The LAN web/admin interface is a small, bundled HTML/CSS/JavaScript application
embedded into the Rust binary. It can be built and exercised without Flutter,
does not load third-party runtime assets, and does not expose the native tunnel.
This is an explicit refinement of the original shared-Flutter-web proposal.

The cloud policy currently blocks storage.googleapis.com, required for Flutter's
official SDK/engine artifacts. The network addition is saved in the environment
draft. Native client source and build scripts are not equivalent to a verified
APK; record that blocker until a real build and installation check completes.
