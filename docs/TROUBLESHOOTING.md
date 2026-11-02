# Server startup and account recovery

Use the existing installation. Reinstalling or deleting its directories is not
a password reset or a startup repair: it can destroy keys and household data.
Run these recovery commands in a root shell.

## Cannot sign in

New installations suggest **admin** as the initial owner account name. Existing
accounts keep their selected names and roles; earlier installations may use
`owner`. Exclamation marks and spaces are accepted literally. Password entry
does not pass through a shell. After repeated failures, login attempts are
limited for five minutes.

Earlier container installers reused the owner-name variable for Docker
resource names. They could create the owner as **`field-notes`** even when
`admin` or another name was entered. This is an installer bug, unrelated to
password punctuation. The current installer preserves the chosen name.

Inspect existing account names without entering a password or stopping services:

```bash
python3 /usr/local/src/field-notes/scripts/reset-password.py --list-users
```

This prints names, roles and active/disabled status only. It reads the database
as the app UID and does not create an installation or reveal password hashes.
Update the checkout with `git pull --ff-only` first if the option is unavailable.
To repair the affected account and use the intended `admin` login:

```bash
cd /usr/local/src/field-notes
git pull --ff-only
python3 scripts/reset-password.py --name field-notes --rename-to admin
```

It prompts twice for a new password and changes the existing account's name
and hash in one transaction. Its account ID, owner role, tasks, assignments,
phone sessions and keys remain. It rejects another user's active or disabled
name instead of taking it over, and never silently enables a disabled account.
A wrong target name is rejected before password entry or service interruption.

An authenticated user can use **Change password** in the website. Owners can
reset another account in **Management → People** after confirming their own
password. For a lost password, use local host-root recovery:

```bash
python3 /opt/field-notes/reset-password.py --name admin
```

For an existing 0.3.3 installation that predates this helper, update the source
checkout and run the helper there; no Android/Rust rebuild is required:

```bash
cd /usr/local/src/field-notes
git pull --ff-only
python3 scripts/reset-password.py --name admin
```

It prompts twice using hidden input, obtains a salted Argon2id hash from the
installed offline app image, briefly stops the app and its timers, and updates
the selected active account transactionally. Its browser sessions are signed
out; phone sessions, pairing keys, tasks, account roles and other users remain.
Previously active services/timers are resumed, including if recovery fails.
The root recovery event goes to the host authentication journal; it is separate
from the encrypted in-app audit. Passwords and hashes are never logged. Existing
source-IP throttles expire normally; wait five minutes if necessary.

Escape or Ctrl-C cancels a prompt. Backspace, Delete, arrows, Home/End and Ctrl-U
edit the input; passwords never echo. No password flags or environment variables
are accepted. Use a real terminal for password entry.

## Sign-in clears the form or works only in a different browser

In 0.3.3, the HTTPS server read only the first Cookie header. HTTP/2 browsers
can split cookies across headers; another application on the same IP could
therefore hide the Field Notes session even after a correct password. The old
UI also discarded the real error when it rebuilt the sign-in form.

A fresh private browser window can avoid the old cookie interaction. Version
0.3.4 fixes cookie parsing and shows session-confirmation failures explicitly.
Pulling Git updates host scripts; the bundled web UI is compiled into the server
image, so apply the [server upgrade](UPDATES.md#upgrade-an-existing-source-installation)
to get that fix. Another password reset is not needed if the same credentials
work in another browser.

The management address must start with **https://**, including the chosen port.
Plain HTTP on a TLS socket previously displayed binary TLS alert bytes. Version
0.3.4 returns a readable HTTPS-required error on that same management port. It
does not serve the application or accept authentication over HTTP.

## Services active but app reports Permission denied

Older 0.3.3 TLS provisioning incorrectly left the issued certificate directory
at `0700` under the service's `077` umask. The separate application user could
not traverse its read-only TLS mount. Adding RAM does not repair this error.
For the standard installation paths, repair just that directory:

```bash
chown root:fieldnotes /etc/field-notes/tls/issued
chmod 0750 /etc/field-notes/tls/issued
systemctl restart field-notes
```

Keep the leaf key at `0640` and the private CA directory/key at `0700`/`0600`.
Do not recursively make keys world-readable or change the data directory owner.
The current TLS helper applies explicit directory modes on both initialization
and renewal, including renewal that keeps the existing certificate.

After a failed install's startup has been repaired, complete its retained
inventory using the current checkout's helper:

```bash
python3 /usr/local/src/field-notes/scripts/container-runtime.py check --complete-installation
```

This requires matching inventory/service files, active services/timers, the
verified APK, matching running version, pinned TLS 1.3 and the management source
restriction. Only after success does it change `installing` to `installed`.
Then sign in from the allowed LAN subnet. It does not rebuild, change networks,
rotate keys, modify household content, or bypass the management allowlist.

## Download the APK directly

Use the management address and port chosen during installation:

```text
https://YOUR_LAN_IP:YOUR_WEB_PORT/downloads/field-notes.apk
```

The same APK is stored at `/opt/field-notes/downloads/field-notes.apk`, with
checksum/signer metadata alongside. It can be copied to the phone when the web
service is unavailable. Keep the same signer for future updates. See
[Android downloads](ANDROID-DOWNLOADS.md).

## Inspect a different startup failure

```bash
systemctl --no-pager --full status field-notes
journalctl -u field-notes -n 40 --no-pager
docker --host=unix:///var/run/docker.sock logs --tail 40 field-notes-app
docker --host=unix:///var/run/docker.sock logs --tail 40 field-notes-gateway
```

An active supervisor alone does not establish readiness. Current failures report
the owned containers' status, exit code and OOM flag. Do not share private keys,
passwords or enrollment codes when reporting errors.
