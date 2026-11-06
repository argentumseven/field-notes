# Get the Android app

[**Download the latest public APK**](https://github.com/argentumseven/field-notes/releases/latest/download/field-notes.apk) · [Release/checksums](https://github.com/argentumseven/field-notes/releases/latest)

Standard server installs, server downloads and the GitHub release use the
**same APK and the same stable public signing certificate**. Source installation
downloads the exact release matching `release.json`, verifies the actual APK
signature/package/code/ABI against the pinned certificate, and preserves all
four published files byte for byte. It never creates a local Android signing key.
The signing private key stays on the protected release builder, outside server
bundles. If the release is unavailable or verification fails, the build stops;
there is no fallback to a different signer or to an arbitrary latest version.

Older custom/development installations can have a different signing key. Their
upgrades preserve that original signer. Android cannot install the public APK
over another signer without the original private key and a supported signing
lineage. Do not uninstall, clear storage or reset pairing to work around this.

The Linux server bundle includes its matching signed ARM64 Android APK. On your
PC, open the trusted management URL. **Download APK**
appears on Sign in and in Management, with checksum and signing-certificate
links. Download and verify on the PC, copy the APK/checksum to the phone (for
example over USB), then allow installation from its file manager and
[enroll the phone](ENROLLMENT.md). The phone needs no management access or LAN
browser CA installation. No SDK, developer mode,
Google account, store or public service is required on the installed server or phone.

The public APK uses the release variant and the pinned public certificate. A
local development build, when explicitly requested, is written to
`dist/android/field-notes-0.8.0-android-arm64-debug.apk`, with `.sha256` and
`.signer.txt` alongside. This is a signed **development** build. Keep the same
signing key for in-place updates; changing signers normally requires uninstall
and loses phone-local keys and unsynchronized edits. Never uninstall over retained
work just to resolve a signature mismatch. Existing installations use
**Settings & widgets → App updates** through their paired server starting with
0.3.2. For an older app, install the updated server's APK over it using the same signer, without
uninstalling or clearing storage; see
[UPDATES.md](UPDATES.md) for in-place upgrades and stable signing.

## Local download endpoints

If the web service has not started, copy
`/opt/field-notes/downloads/field-notes.apk` directly from the Linux server.
The APK does not require the web interface to be working in order to sideload it.
Pairing needs a healthy server. See [startup recovery](TROUBLESHOOTING.md).

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
the running server containers do not fetch APKs, contact GitHub, or acquire an
outbound exception. The host installer/builder fetches the public release before
bundling; the isolated runtime serves only the verified local files.
Trust the management CA through the fingerprint procedure in [RUNNING.md](RUNNING.md).
Download on the trusted administrator computer and copy the APK/checksum to the
phone. Keep the PC-only management source restriction.
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
APK packaging checkout and whether it was dirty. Rebundling a public APK retains
its original metadata rather than claiming it was built from the server's newer
checkout; that field alone is not source
attestation. No private signing key or password enters the download directory.

## Builder and permanent releases

The release workflow can publish APK/checksum/signer files as GitHub Release
assets. A permanent public download is available only after a release has
actually been published; the paired server always provides its own matching APK.

For the standard public APK, build the server after preparing verification tools:

```sh
bash scripts/setup/cloud.sh --server
bash scripts/build/server.sh
# Explicit custom build, with the original local signer retained:
bash scripts/build/android.sh debug
FIELD_NOTES_ANDROID_APK=dist/android/field-notes-0.8.0-android-arm64-debug.apk \
  bash scripts/build/server.sh
```

The builder needs Android build-tools 35.0.0 and Java for independent APK signature
and manifest verification. `--server` prepares these without an NDK, Gradle build
or signing key. A prebuilt bundle needs neither on its target host. For offline
rebuilding, set `FIELD_NOTES_ANDROID_DOWNLOADS` to a directory containing the four
previously obtained files; they are signature-verified again and copied without
changing their metadata. Custom APK/directory overrides are explicit and mutually
exclusive. A custom signer is never published as the public release certificate.
The server tarball includes both offline images, APK/checksum/signer/metadata,
installer, uninstaller and documentation.

The optional operator-key build workflow is started manually for an existing
`v<version>` source tag. It requires these repository Actions secrets, supplied
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

The optional operator-key workflow needs repository secret configuration and a
successful hosted run. The prebuilt publication path below does not upload a key. The local server download is
independent of that publishing step. Current executed evidence is in
[VALIDATION.md](VALIDATION.md).

## Publish an already compiled public APK

The public release certificate is pinned in `release.json`. Build and package
from a clean source tag, then queue the verified payload through GitHub Actions:

```bash
python3 -B scripts/android_artifact.py --apk dist/android/field-notes-0.8.0-android-arm64-release.apk --output dist/android-release
python3 -B scripts/publish-android.py --directory dist/android-release --tag v0.8.0 --queue --apply
```

The existing tag must already be pushed and point to this clean checkout on
`main`. Queueing creates a single `release-assets/v<version>` branch over that
source with the four public download files; it never changes the working tree
or overwrites an existing payload branch. Binaries stay off `main`. The workflow
checks the parent/source tag, verifies the actual APK signature, package/version
and architecture, and matches the pinned public certificate before publication.
No private key is uploaded. The job publishes through a draft, downloads and
compares all assets, and marks the verified release as latest.

`--latest` makes a normal GitHub Release so `/releases/latest/download/field-notes.apk`
resolves. The release notes still identify development builds; this label is not
a security-audit or production-readiness claim.

## Original-signature compatibility APK

When a household already has custom-signed phones, source upgrade fetches and
verifies the public APK, then signs a **separate** compatibility copy with the
original protected key. Public assets stay byte-identical to GitHub. The key
never enters either download folder or server bundle. New phones use the main
public download; existing custom phones use the clearly labeled compatibility
button. The optional files are:

```text
/downloads/legacy/field-notes.apk
/downloads/legacy/field-notes.apk.sha256
/downloads/legacy/field-notes.apk.signer.txt
```

Phones from 0.6 report their installed certificate in authenticated metadata
requests, so their paired server chooses the matching public or compatibility
APK. Older metadata clients receive the compatibility copy when present; an
older public-signed phone on a mixed installation may need one manual install
of the matching public 0.6 APK. Neither case requires uninstalling or re-pairing.
If the original custom key is missing, Android cannot accept a replacement
certificate; preserve the app and its data while recovering that key.
Compatibility APKs remain household-local and are refused by public publishing.
