# Get the Android app

The Linux server bundle includes its matching signed ARM64 Android APK. On home
Wi-Fi, open your trusted management URL in the phone browser. **Download APK**
appears on Sign in and in Management, with checksum and signing-certificate
links. Download, verify, temporarily allow installation from that browser/file
manager, install, then [enroll the phone](ENROLLMENT.md). No SDK, developer mode,
Google account, store or public service is required on the installed server or phone.

The standalone build is also available at
`dist/android/field-notes-0.3.3-android-arm64-debug.apk`, with `.sha256` and
`.signer.txt` alongside. This is a signed **development** build. Keep the same
signing key for in-place updates; changing signers normally requires uninstall
and loses phone-local keys and unsynchronized edits. Never uninstall over retained
work just to resolve a signature mismatch. Existing installations use
**Settings & widgets → App updates** through their paired server starting with
0.3.2. For an older app, install 0.3.3 over it using the same signer, without
uninstalling or clearing storage; see
[UPDATES.md](UPDATES.md) for in-place upgrades and stable signing.

## Local download endpoints

Replace the example management address/port with the installer output:

```text
https://192.168.1.10:9443/downloads/field-notes.apk
https://192.168.1.10:9443/downloads/field-notes.apk.sha256
https://192.168.1.10:9443/downloads/field-notes.apk.signer.txt
https://192.168.1.10:9443/downloads/android
```

These are software downloads on the source-restricted **LAN listener**. They do
not require a household session and expose no household data. The public `/downloads` paths are absent from the private API. Authenticated,
source-bound in-app updates use separate metadata/chunk routes on the same
private notes endpoint. Downloads remain local;
the server does not fetch APKs, contact GitHub, or acquire an outbound exception.
Trust the management CA through the fingerprint procedure in [RUNNING.md](RUNNING.md).
A phone browser needs that trust for a LAN download too; alternatively download
on the trusted administrator computer and copy the APK/checksum to the phone.
QR enrollment inside the app needs no system-wide CA installation.
Do not disable certificate verification or expose the manager to the Internet.

The installer verifies the matching version, manifest/checksum/report metadata
and fixed file set, copies it to root-owned `/opt/field-notes/downloads`, and
mounts that folder read-only into the nonroot app. The builder additionally
runs `apksigner verify` and checks the actual package, version code and ARM64 ABI.
Startup streams a digest check before offering an APK; a missing/mismatched
bundle disables downloads while leaving the planner running. Four concurrent
bounded, streamed responses are permitted; arbitrary paths are not served.
The scoped server uninstaller removes these inventoried program files.

To check a downloaded file in its download directory:

```sh
sha256sum -c field-notes.apk.sha256
# Optional on a computer with Android SDK build-tools:
apksigner verify --print-certs field-notes.apk
```

Compare the signer SHA-256 to your previously trusted installation or operator
record. A checksum downloaded from the same server detects transfer errors but
does not independently establish publisher identity. `android.json` records the
packaging checkout and whether it was dirty; that field alone is not source
attestation. No private signing key or password enters the download directory.

## Builder and permanent releases

Bungee's current APK delivery uses permanent GitHub Releases with APK/checksum
assets built from verified source tags. Field Notes includes an analogous
[workflow](../.github/workflows/publish-android.yml) and adds the LAN download
above so a household server can furnish its exact APK without Internet access.
No Bungee code or assets were copied.

Build Android before the server. An operator release can replace the default
development APK in a bundle:

```sh
bash scripts/build/android.sh debug
bash scripts/build/server.sh
# After a separately configured stable-key release build:
FIELD_NOTES_ANDROID_APK=dist/android/field-notes-0.3.3-android-arm64-release.apk \
  bash scripts/build/server.sh
```

The builder needs Android build-tools 35.0.0. It is not needed on the target host.
The server tarball includes both offline images, APK/checksum/signer/metadata,
installer, uninstaller and documentation.

The release workflow runs on an existing `v<version>` source tag (or manual
selection of that tag). It requires these repository Actions secrets, supplied
by the operator outside chat and source control:

- `FIELD_NOTES_KEYSTORE_BASE64`: the stable release keystore encoded as Base64.
- `FIELD_NOTES_KEY_ALIAS`, `FIELD_NOTES_STORE_PASSWORD`, `FIELD_NOTES_KEY_PASSWORD`.

Back up that key securely. The workflow refuses to publish a new ephemeral
signer, verifies the actual build and keeps only public artifacts. The private
key is a temporary restricted file removed after signing. Build permissions are
read-only; a separate publishing job gets repository-content write permission.
Actions are pinned to specific commits.

`scripts/publish-android.py` previews by default. Its explicit `--apply` requires
a clean checkout matching the existing tag and artifact source. It creates a
**draft prerelease**, uploads the four fixed public assets, downloads and compares
them, then publishes. Interrupted drafts can resume; differing or unrelated
assets are never silently overwritten. Published releases are not mutated to
repair mismatches. An Actions artifact alone is not a permanent release.

This workflow needs repository secret configuration and a successful hosted run
before a GitHub Release download can be claimed. The local server download is
independent of that publishing step. Current executed evidence is in
[VALIDATION.md](VALIDATION.md).
