# ADR 0002: Android delivery with Kotlin Multiplatform and Compose

The user requested replacing Flutter, shipping Android now, and deferring iOS.
Kotlin Multiplatform and Compose replace the uncompiled Flutter client. The Rust
server, LAN web UI, wire protocol, and tested Go WireGuard stack are retained.

`apps/mobile/src/commonMain` contains the models, durable outbox, reconciliation,
reminder policy, and Compose UI. `androidMain` supplies Android Keystore/AES-GCM,
atomic files, JNI, image picking, device-credential UI locking, and WorkManager.
`commonTest` runs as JVM tests without a phone. Android instrumented tests exercise
Keystore, the actual JNI/WireGuard/TLS path, manifest, and first-launch UI.

This code structure permits future Compose iOS UI and shared sync logic, with
Keychain/storage, networking bridge, and notification adapters to implement on
Apple platforms. No iOS target or IPA is claimed in this delivery. Native desktop
packages are also deferred; the LAN web interface continues to work in Linux or
Windows browsers.

All required SDK/Maven destinations already belong to the package-manager
network preset. The active build does not need Flutter or storage.googleapis.com.
Superseded Flutter sources/build helpers were preserved outside the checkout in
`/workspace/.cache/field-notes/retired-flutter-source.tar.gz` before removal.

Android background delivery now uses a default-on, user-visible special-use
foreground service through the app-contained tunnel, with head polling and
bounded offline backoff. WorkManager catch-up is a fallback. AlarmManager posts
cached due/calendar reminders independently of the network with encrypted
receipts. No FCM/Play services or OS VPN slot is used. See
[Android delivery](../ANDROID-DELIVERY.md) for the battery, exact-alarm, sound and
GrapheneOS profile limits. iOS background delivery remains deferred.
