# Calendar and scheduling

Weeks and month headers start on Sunday on the website, app and launcher widgets.
The web timeline and its sticky headings share one native scrollbar gutter.

Calendar is the home view on Android and the LAN website. Android opens a day
timeline; a desktop browser opens the week. Switch to Month for a larger date
grid. Select a day or an hour slot; **New event** uses that selection. Today
returns to the current day. Tasks appear on their optional planned or due day.
Undated tasks stay in Tasks and Focus; no project or deadline is required.

Filled blocks show occupied time, arranged side by side when events overlap.
An event needs a start time but **does not need an end time**. Leave **Set an end
time** off for a start-only reminder: it appears as an outlined marker, not a
made-up duration. All-day items and repeat-reminder windows have their own area;
windows do not reserve time. See [reminders and sound](REMINDERS.md).

Timed events use the device/browser's local timezone, shown beside the form.
Choose dates with the date picker and times with the clock or numeric time input.
10 AM is stored as the instant corresponding to 10 AM in that zone and reopens
as 10 AM there. UTC timestamps and offsets are not form inputs. Android respects
the system's 12/24-hour setting in its time picker. A nonexistent time during the
spring clock change is rejected with a choice to pick another time. New events
at a repeated autumn time use the first occurrence; saving an existing repeated
time without changing it preserves its exact original instant. Times display in
the viewing device's zone when travelling. All-day events keep their dates.

For an all-day event, First day and Last day are both included. A one-day event
uses the same date for both. For an overnight timed event choose the next date
under Ends. Editing the start date moves the end date by the same number of days.
Android keeps the duration when moving the start time within a day. Invalid
entries leave the form open with the entered values retained.

## Android home-screen calendar

Long-press the home screen → Widgets → **Field Notes · Calendar**, or open
Field Notes → workspace menu → Settings & widgets → **Add Calendar to home
screen**. This is a launcher widget with a real month grid, weekday columns,
today highlight, scheduled-day markers, month arrows, Today, and today's agenda.
The **Today** busy-time strip shows occupied parts of the day and point markers
for start-only events. Day indicators summarize busy time; overlapping events
are counted once. The strip is a compact overview, not a minute-level editor.
It is separate from the existing Upcoming agenda and Focus widgets. Resize it
in the launcher. Its default fits four rows; compact sizes hide agenda text,
while a taller widget reveals it. Tap a date to open that date in the app's calendar.

Choose **Field Notes · Week** or **Add Week to home screen** for seven dated
agenda rows, previous/next/this-week navigation and occupied-time bars for each
day. A point marks a start-only event without reserving time. Tap any day to open
it; edits and sync repaint the actual launcher widget. Resize to fit more text.

**Show schedules in home-screen widgets** defaults on for new installs. Existing
off choices are preserved and **Show schedule** opens the relevant settings.
App credential locking is optional and does not hide widget titles. Device lock
still requests redaction unless **Widget details while the device is locked**
is explicitly enabled. Known invalid sessions always redact. By default widgets
show your reminder recipients; **Include everyone's appointments in widgets**
adds the shared household schedule without subscribing you to their alarms.

The widget renders the encrypted local cache and pending offline changes.
App state changes repaint it automatically, including edits while offline,
successful syncs, sign-out and pause changes. Refresh requests the paired private
API through the existing tunnel. Local date/time/timezone broadcasts and a
midnight alarm update the calendar without a server change; Android also has a
30-minute widget refresh fallback. Android may delay background work and inexact
alarms under Doze; this does not promise instant updates from a suspended or
ended user profile. No Google Calendar permission, Play services, FCM or public
calendar account is used. Normal uninstall removes the provider, its alarms and
private per-widget preferences through Android's package cleanup.

## Repeating events

Use **Repeat** in the event form for every day, every other day, weekdays, selected
weekdays, alternating weeks, monthly or individual dates. Custom intervals, an
optional last day and skipped dates handle irregular schedules. Times follow
the stored timezone across daylight-saving changes; missing month dates and
nonexistent clock-change times are skipped. Start-only repeats need no end.
A repeating event may span at most 31 days. Editing applies to the series; use
skipped dates for exceptions. Phones need 0.6 for recurrence expansion and alerts;
older compatible clients recommend updating and cannot overwrite series schedules
through an editor that does not understand them.

For an idea that only needs to return later, use **Later & reminders** instead
of reserving calendar time. See [Household planning](PLANNER-0.6.md).

Every year and custom Years show upcoming dates beyond the current year. February 29 skips years without that date. Existing recurring events are edited in their saved timezone. Tapping a home-screen calendar day opens that exact day’s timeline, regardless of the previous view. Background updates offer Live, Periodic and Manual; widget status shows cache freshness.

## Calendar options and focused editing — 0.11

Month grids show only dates in the selected month. Empty surrounding cells do
not navigate or display adjacent-month events. Sunday remains the first day.
Enable **Calendar options → US federal holidays** separately on the website or
phone. Holidays are calculated offline, include observed dates and appear in
the native month/week widgets when enabled on that phone; they create no events
or reminders and make no external requests.

Events use Schedule / Reminders / Details sections. Appointments have an optional
location with **Copy location**, so you can paste it into your chosen navigation
app. Field Notes performs no geocoding or map lookup. Start-only events still
require no end time and remain distinct from blocks of occupied time.
