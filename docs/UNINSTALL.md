# Remove the Linux server

The installed uninstaller discovers its root-owned installation inventory and
previews changes by default. Review the printed directories before applying.
Use the installed wrapper, including with custom installation paths:

```sh
sudo /opt/field-notes/uninstall-server.sh --plan
sudo /opt/field-notes/uninstall-server.sh --apply
```

Normal removal stops/disables the Field Notes services and timers, removes its
owned app/gateway/private bridge and image tags, deletes installed programs and
helper/runtime state, and removes its service account/group. **Planner data,
attachments, master key, TLS CA and tunnel identity remain for recovery**, under
root-only retained directories. The inventory remains so a later purge has an
explicit, verifiable scope. It does not modify Bungee, shared Docker services,
packages, other containers/networks, host forwarding policy or global logs.

Upgraded installations also retain root-private checkpoints with older program
files, settings and encrypted data backups. Normal removal retains these for
recovery; purge removes checkpoints within the recorded configuration directory.
The current uninstaller validates historical and attempted-upgrade image
inventories against this installation before removing their owned image tags.
It refuses foreign or shared resources. After rollback to an older helper, use
the new bundle's uninstaller so this history is inspected.

## Delete all managed data and keys

Purge includes normal removal and irreversibly deletes the recorded data and
configuration directories, including attachments, server keys and backups stored
inside those directories. Export needed data and recovery keys first.

```sh
sudo /opt/field-notes/uninstall-server.sh --apply --purge
# At the prompt, type: DELETE-FIELD-NOTES
```

For an unattended, deliberately destructive purge:

```sh
sudo /opt/field-notes/uninstall-server.sh --apply --purge \
  --confirm-purge DELETE-FIELD-NOTES
```

If programs were already removed normally, use a freshly extracted server bundle
or the checkout and select the retained configuration directory:

```sh
sudo bash scripts/uninstall-server.sh --config /etc/field-notes --plan --purge
sudo bash scripts/uninstall-server.sh --config /etc/field-notes --apply --purge
```

Use your custom configuration path if different. Do not recreate the removed
account or guess data paths; the inventory records the actual paths. After a
complete purge, the bundled script reports absence without changing anything.
Missing legacy inspection tools are reported instead of claiming an inspected
legacy firewall. Unknown named resources require manual review.

## Safety and retries

Removal validates root ownership, inventory identity, installed unit hashes,
container/network ownership and exclusive namespace membership before mutation.
It stops on changed units/drop-ins, foreign resources or reused/shared accounts.
No global Docker prune, forced image removal, firewall flush or recursive
account-home deletion is used. Shared images in use by another container are
retained and removal stops for review. Failures retain the inventory/programs
until resource cleanup succeeds; rerun after resolving the reported condition.

Directory deletion refuses mount points and mounted subtrees. Dedicated mounted
storage needs separate explicit review and cleanup; removing a mount directory
does not erase its backing volume. Retained directories become root-only without
following/chowning child links. Purge removes entries within managed directories,
without following symlinks to other storage. It is logical deletion, not secure
erasure of SSD blocks, snapshots, journals or backups.

Recognized older direct-TLS native installations can be removed from this bundle
without first upgrading. The tool verifies the dedicated WireGuard identity and
unit configuration, stops that interface, and deletes only its nftables table.
Normal removal preserves a private hub configuration under the retained
configuration's `recovery/` directory before removing its auto-start file.
Unrecognized proxy-era/customized installations are refused, not guessed at.

## External cleanup

Remove this server's UDP forwarding rule from the router. Remove its public CA
from administrator trust stores if no longer needed. Uninstall Field Notes in
each Android user profile separately; [ANDROID-DELIVERY.md](ANDROID-DELIVERY.md)
describes local Android cleanup and revocation. Android removes the app-private
partial/verified update cache during normal uninstall. External backups, downloaded
bundles/APKs, browser data, other copies, OS journal history and router settings
are outside server uninstall scope. No application can erase copies on another
device merely by removing the server.
