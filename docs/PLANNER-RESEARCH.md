# Planner iteration: research and decisions

Reviewed 30 October 2026. These are design references, not dependencies or reused
code/assets. Field Notes keeps its own original implementation and visual system.

| Reference inspected | Useful idea | Field Notes decision |
| --- | --- | --- |
| [Planify README and real UI screenshot](https://github.com/alainm23/planify/tree/63570d67b4bb8e14c89bf50f7c9b307ddc8d0cc7) | Compact rows, a useful sidebar, scheduled work and a calendar | Focus / Tasks / Calendar, restrained charcoal/paper, teal, no decorative artwork or giant headings |
| [Super Productivity features](https://github.com/super-productivity/super-productivity/tree/22ad1b6271d92ab9047b5ceac6cffe480eeac792) | Separate planning, schedule, estimates, focused work, boards and Eisenhower views | A planned day is distinct from a deadline; undated priorities are first-class; ranking explains its inputs |
| [Tasks.org widget implementation](https://github.com/tasks/tasks/blob/a50bef9a9e46520d8fdf0d93abe4981875c8fbec/app/src/main/java/org/tasks/widget/TasksWidget.kt) | Native AppWidgetProvider/RemoteViews rather than a Google push or calendar dependency | Native widget reading our encrypted app cache, with explicit privacy settings |
| [Vikunja overview](https://github.com/go-vikunja/vikunja/tree/d4899715e3724448aaa08951f474a575c7d729a0) | A self-hosted task workspace | Keep the server local and household roles enforced centrally; its README alone is not evidence for detailed scheduling behavior |
| [Bungee server TLS implementation](https://github.com/argentumseven/bungee/blob/main/server/src/tls.rs) and README | Direct Rust HTTPS, explicit management restrictions, local CA, per-phone WireGuard, single-use invitations | No Caddy, reverse proxy, load balancer, public bootstrap endpoint, default route, or OS VPN service |

The official Todoist priority-help URL was unavailable under this cloud's network
policy. No claims here rely on reading that page. Upstream art/icons are not used.

## Priority without dates

Both due date and planned day may be empty. Priority is explicit: P1 Urgent,
P2 High, P3 Normal, P4 Low. Calendar dates are never manufactured to make a task
eligible for Focus. Effort is optional, in minutes. A pin overrides ordering;
blocked/completed work and another person's active claim are not suggestions.

The version-1 score is a bounded, inspectable heuristic, not an optimal scheduler:

- Priority contributes 70 / 45 / 25 / 10.
- Due proximity contributes 0 without a due date; 3 within 14 days, 8 within 7,
  16 within 3, 22 tomorrow, 30 today, or 31–50 when overdue.
- Waiting contributes one point per three elapsed days, capped at 18. Editing
  preserves the server-generated creation time.
- A planned day reached contributes 12. Future-planned tasks wait unless already
  due or already started by you; they remain visible in Tasks and Calendar.
- An optional estimate contributes 6 up to 15 minutes, 3 up to 30, 1 up to 60.
  Long or unestimated work is not penalized. This small boost cannot indefinitely
  defeat explicit importance and aging.
- Continuing your own started task contributes 12.

Reasons accompany the score. Ties use immutable creation time and object ID.
Phones can compute it offline using the same rules; it never decides server
ownership. Local calendar day determines date urgency, while elapsed seconds
determine aging, so daylight saving transitions do not add phantom aging days.

## Calendar semantics

Tasks may have no dates, a planned day, a due date, or both. Undated tasks stay
in the backlog and Focus. Timed calendar events store UTC start/end instants;
all-day events store floating dates with an exclusive end. An event ending at
midnight does not appear the next day. No Google account, Calendar provider,
third-party sync, or OS calendar permission is needed.

## Enrollment and the tunnel

Initial pairing takes place on the home LAN. A scoped ten-minute invitation is
shown as QR/text after administrator reauthentication. The phone generates its
own private key. Claiming an invitation requests enrollment; it does not grant
content access. The administrator enters the code displayed directly on the phone before
approval. A bounded host helper then provisions that phone's /32 peer and PSK.
The app service never receives NET_ADMIN or arbitrary shell access.

Activation occurs through the actual WireGuard connection. A device session is
bound to that peer address. Revocation blocks authorization immediately and
reports peer removal separately until the host acknowledges it. The firewall
permits only the overlay notes API address/port; it denies the management LAN,
administration, other peers and Internet forwarding. Direct TLS checks the real
connection address and does not trust forwarded identity headers.

This file records the chosen design during implementation. VALIDATION.md is the
authority for completed checks; screenshots do not establish physical routing.

Assignment is an eligibility filter in ranking version 2. Work assigned to
another person stays in Tasks/Assignments, while an already-accepted own claim
remains visible. The score arithmetic stays bounded; assignment never grants a
claim. Shared Kotlin/JavaScript fixtures now cover 19 cases.
