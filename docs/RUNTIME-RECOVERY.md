# Recover a stopped container runtime

A missing pinned Docker image prevents startup even when old container logs show
that HTTPS previously worked. Exit 137 only says the app was killed; it does not
by itself establish an out-of-memory failure. Pulling Git source does not restore
Docker images. Inspect the first failed Docker operation rather than repeatedly
restarting the service or resetting users/pairings.

If the original offline archive is available, verify its checksum and that its
`images.json` IDs match `/etc/field-notes/deployment.json` before importing it.
That restores the exact existing images without an upgrade.

When the saved bundle is different, use the explicit recovery path from the
current source checkout on the Debian/Ubuntu host. With an existing verified
bundle under `dist/server/linux-x86_64`, run as root:

```bash
cd /usr/local/src/field-notes
git pull --ff-only origin main
python3 scripts/upgrade-server.py --apply --bundle "$PWD/dist/server/linux-x86_64" --recover-runtime
```

Use `--plan` instead of `--apply` to inspect it without changing the installation.
Recovery accepts a newer compatible schema-2/protocol-1 bundle with the original
APK signer. An explicitly requested same-version repair additionally requires
the identical server executable and unchanged Android version code. It never
substitutes a random image found under a mutable tag. Downgrades are refused.

If no usable bundle exists, `--apply --build-from-source --recover-runtime`
builds one with the original development APK key; it refuses to generate a
replacement key. Operator-signed installs need a matching prebuilt bundle.

The existing database, master key, initial account/APK metadata and complete
service inventory must already exist. Recovery can finish an installation left
in the `installing` phase by an earlier readiness failure, but it cannot adopt an
uninitialized directory. It validates ownership, image archive/checksums,
signer, network settings and resource scope before stopping services.

After stopping the owned stack, recovery retains the encrypted database/WAL
files in a root-private `pre-recovery-state` directory. It then makes an encrypted
logical backup using the verified target image with no network, no capabilities
and the existing nonroot UID. This avoids needing the missing old image to make
the backup. The original database, password hashes, CA, hub/phone keys, peers,
ports and overlay settings remain in place. New image IDs are committed with the
matching programs/settings; readiness must succeed before a partial installation
is marked complete. The current recovery supervisor/helpers are installed too.

Backups and checkpoints are under `/etc/field-notes/upgrade-<id>` (or the recorded
custom configuration directory). A failed start restores prior programs/settings
and retains those backups. It does not restore old database bytes over changes
accepted after startup. If the old images were already missing, rollback alone
cannot make that previous runtime available.

Image cleanup follows inventoried immutable IDs. A moved tag cannot authorize
deleting an unrelated image; aliases owned by another user and containers using
an image are retained. No global Docker prune or daemon restart is performed.

Automated checks cover compatible/newer and guarded same-version recovery,
partial-state completion, a retained database snapshot, use of the target backup
image, signer/downgrade/empty-directory refusal, failed readiness, retained writes
and primary failure diagnostics. Privileged Debian/systemd recovery remains a
separate host check; temporary-tree tests do not establish that by themselves.
