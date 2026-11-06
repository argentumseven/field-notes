# List workflow review

The review followed a grocery trip: find the right store, read a shared list,
receive amendments, add/check/remove items, then clean up and recover mistakes.

| Finding | 0.9.1 outcome |
| --- | --- |
| Item editors exposed checking but no individual removal | Visible Delete on web/phone rows and Delete item in their editors |
| Bulk cleanup was hard to discover and the web hid its count with Unchecked selected | Persistent Delete checked count, including hidden checked rows, with frozen search/store scope |
| Deletion could race an amendment or a lost response | Revision/workspace fences, retained phone conflicts and same-operation web retries |
| Removal looked permanent without a nearby recovery route | List history access; retained versions and explicit retention explanation |
| Focused web search suspended incoming data indefinitely | Live search preserves focus/cursor while displaying changes |
| Web refresh sent a long page back to the top | Same-view redraws preserve the page and nested scroll positions; management builds updates off-screen |
| A previously enabled connection could still look current during a delay | Last-check status, pending-update state, queued/draft distinctions and stale warning |
| Phone refresh depended on an old saved preference and a fifteen-second loop | Immediate/resuming foreground loop every three seconds; default-network reconnection with the same identity |
| List controls and empty views obscured the next action | Visible Store, grouped options, Clear filters, and checked/unchecked-specific empty messages |

Remaining useful improvements, in priority order:

1. **A direct Undo after removal.** History already restores retained full states;
   a shorter route needs the same revision/restore checks and an honest offline
   path. It must not overwrite a partner's intervening changes.
2. **Remember the store and view across navigation and restarts.** A shopping trip
   should not require choosing the same store again after switching screens.
3. **Quick quantity editing and multi-item capture.** Updating “2 bunches” or
   pasting several grocery lines should not require repeated full editor visits.
4. **Multi-select move/reclassify.** Move selected items to another store or list
   with a reviewable scope and preserved identities, rather than deleting/recreating.
5. **Duplicate suggestions for ordinary additions.** Offer an existing item or
   Add anyway; identical names at different stores can be intentional. Do not
   silently merge free-form quantities. Portable imports already prevent duplicates.

These remaining items are review findings, not claims of shipped controls.
