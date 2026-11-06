# Live updates while shopping

With Field Notes 0.9.1 or newer open on the phone, the app immediately checks its
paired server and repeats a small authenticated head check every **three seconds**.
It downloads content when the server sequence changes or local work is queued.
The currently open list updates without tapping Sync, including additions and
amendments made through the web manager. Expected delay on a healthy connection
is the polling interval plus the request time; minutes of unnoticed lag are not
normal foreground behavior. This uses the existing private tunnel, without
Google services or a public push relay.

Returning to the app starts a new check immediately. A default-network change
reopens only its userspace transport using the same phone key, PSK, pinned server
and session. Android's current VPN and lockdown policy still governs the traffic.
No routes, VPN slots or pairing material are replaced. If both background modes
are off while the app is closed, the transport is invalidated without sending
traffic; the next foreground check reconnects it.

Foreground updates are automatic even if an older release saved Auto refresh as
off. **Pause connection** remains the explicit way to work offline. Background
updates and scheduled catch-up retain their separate preferences and Android's
scheduling limits; their older fifteen-second/five-minute backoff is not the
foreground shopping loop.

Read the status above the list or in its editor:

| Status | Meaning |
| --- | --- |
| Up to date; checked just now | The latest server head matches the cached content |
| New changes found; downloading | A newer server state is known and is being retrieved |
| Synchronizing / queued | Local operations still need confirmation |
| Local drafts; not uploaded | Text was kept on the phone but has not been submitted |
| Changes need review | A rejected/conflicting operation is retained locally |
| Updates delayed / Offline | Freshness cannot be confirmed; cached content remains usable |

Successful head checks update an in-memory timestamp. They do not rewrite the
encrypted workspace or regenerate every widget each time. A stale connection
cannot continue to show Up to date after twenty seconds without confirmation.
The status cannot guarantee connectivity during a request in flight, but it
distinguishes that work from a confirmed current list.

On the web, visible pages also check every three seconds and on return to the tab.
A focused grocery search no longer pauses updates: its text, cursor and page
position survive. An open editor keeps your input and shows **New changes waiting**
until you finish editing. It does not replace a form while you type.

Automatic and manual refresh keep the current page position. The sidebar,
calendar timeline and open detail panel keep their separate scroll positions.
Management builds replacement content before swapping it in, including its APK
metadata, so background checks do not briefly collapse the page. If content has
become shorter, the browser can only retain a position within the remaining page.

If an expected item is missing, check **Store**, **Show**, and the search text;
**Clear filters** removes those view restrictions. If the phone is offline, check
its private connection and Android's network policy. An older 0.5/0.6 app can still
perform core sync, but keeps its older refresh behavior and editor-close bug.
Upgrade that phone in place; upgrading the server alone cannot change its app code.

Physical GrapheneOS roaming, profile forwarding and background delivery still
need device checks. [Offline/reconnection](OFFLINE.md) ·
[Updates without re-pairing](UPDATES.md) · [Android delivery](ANDROID-DELIVERY.md)
