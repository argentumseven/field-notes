# Meals and meal prep

Open **Meals → Plan dinner** to choose a date, dinner time and name. **Plan meal
prep** schedules a separate batch-cooking or prep session. Both use the ordinary
calendar event editor, including optional end times, notes and repeat patterns:
daily, alternate days/weeks, selected weekdays, monthly or chosen dates.

On **Details**, **Use recipe** copies a saved recipe's ingredients and instructions
into the event. The recipe itself stays unchanged; changing it later does not
silently rewrite an already planned dinner. Notes can hold servings, substitutions,
defrosting instructions or anything else needed for this occurrence or series.

On **Reminders**, add up to eight named prep reminders. For example, for dinner
at 6 PM: “Start the oven” 45 minutes before, “Start the rice” 25 minutes before,
and “Pack leftovers” 45 minutes after. Each reminder keeps its own recipients,
sound and importance. The name appears in the notification when notification
details are enabled. Repeating dinners derive each prep reminder from that
occurrence's start, including daylight-saving changes.

Meals always remain available in their own section. **Show meals** in the regular
calendar is off by default and saved per phone/browser. It applies to Day, Week
and Month (also Agenda and calendar widgets on Android). Showing meals uses the
same calendar cells and timeline layout as other events, without a floating meal
panel. Hiding meals never disables reminders or deletes their events.

Use **Clone** beside Delete on an event to choose one or several copy dates. The
original local time is the default and can be changed for each copy. Overnight/
all-day length, details, meal type and reminder rules are kept. Each copy is a one-off event; the original repeating series and its
skip dates remain unchanged. Repeat on a copy can be configured independently.
Copies in daylight-saving gaps are refused so a different time/date can be chosen.
Android queues the entire copy plan durably before syncing. Browser retries keep
the same operation IDs and payloads, including after a lost response.

## Updating safely

Meal planning and after-start controls require server and app 0.11.9. Existing
before-start and saved clock-time reminders are not converted. The server, schema,
identities, pairing, signing certificate and receipt storage retain the same
upgrade contracts. Older apps see meal events as ordinary readable calendar
entries; hiding them is available in updated clients. Compatible reminder alerts
continue to be projected in the older format. An older editor cannot erase newer
after-start rules; it must update before replacing that event's reminder settings.
Export/import, backups and history retain meal labels and the complete timing.
