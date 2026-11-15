# Meals and meal prep

[Android preparation fields](screenshots/android/121-cooking-plan.png) show the free-form item and phase editor (Compose host capture, 390 × 760).

**Meals** includes meals, snacks, birthday cakes, packed lunches, batch cooking,
and any other food occasion you name. Food and phase names are fillable fields;
there is no required food-type dropdown. **Meals** opens in **Week**, with independent Day and Month choices. Each day
shows a compact meal summary and preparation start time. Open a meal to see its
preparation workflow. **Calendar → Calendar options → Show meals** is optional,
off by default, and remembered per phone/browser. The calendar shows one meal
entry; individual cooking phases stay in the meal workflow. Visibility never
disables reminders.

Choose **Plan food** or **Plan prep** and enter a name, ready time and
optional repeat pattern. For a prep session, its start time is the target time
at which the planned work should be ready. A timed event is required for a
preparation workflow; an all-day meal must first be given a ready time.

Open **Edit preparation** and add any **Item name**, notes, and named **Phases**.
These are free-form fields, with no fixed food types or required templates.
The ready time can mean dinner served, a snack packed, or a cake decorated and
ready for the celebration. Durations and dependencies work the same way for all
of them.
For each phase enter:

- Duration in minutes, including long cooks and overnight work.
- Optional ready-before-ready time, for work that should finish earlier.
- Dependencies on any other phases, including phases on other items.
- Waiting time after all prerequisites finish, if needed.
- A start reminder, finish reminder and optional periodic check interval.
- Notes such as temperatures, quantities or instructions.

New phases follow the previous phase on that item by default. Edit **Depends
on…** to change this, allow parallel work or connect separate items. The plan
works backward from ready time through durations, waits and dependencies to
calculate **Get started**. Dependencies cannot contain a loop, and the full
preparation chain must fit within 28 days. There are up to 24 items, 12 phases
per item and 64 phases per meal.

For example, a 600-minute cook followed by a 15-minute wait, a 60-minute rest,
and a dependent 30-minute finishing phase begins 705 minutes before serving.
The entries and names can describe any preparation process. You decide which
phases need reminders and checks.

**Start now** records the actual start and bases checks and the finish timer on
that time. Actual starts/completions propagate through downstream dependencies;
the serving target stays fixed and lateness is shown explicitly. Starting before
prerequisites are marked complete requires a review. **Done** stops that phase's
remaining cues; **Reset** clears its timer/completion for this date after review.
A forgotten completion does not silently cancel later scheduled reminders: check
the displayed prerequisites before starting the next phase. The app cannot infer
that food is safe or finished from a timer.

Cooking reminders inherit the meal's recipients. Their shared sound settings are
under **Cooking reminder sound** / **Cooking reminders**. Start, check and finish
cues identify the phase and item. Only the latest recent missed periodic check is
caught up, so reconnecting does not replay an entire night of checks. Android
notification permission, precise-alarm access, background and DND settings still
apply. The web editor prepares schedules; local background alerts run on Android.

Repeating meals inherit the plan and serving clock in the series' time zone,
including daylight-saving changes. Durations and waits are elapsed minutes.
Each occurrence keeps separate progress, so completing this Sunday does not
complete next Sunday. **Clear completed history** removes completed progress for
meals served more than 24 hours ago after confirmation, keeping running timers
and current/future dates. Nothing is silently evicted at the progress limit.

**Edit schedule → Details → Use recipe** copies ingredients and instructions
into meal notes. Recipe edits do not silently rewrite scheduled meals. Ordinary
before/after event reminders remain available separately. **Clone** retains the
plan, details and reminder rules but clears cooking progress; each copy is an
independent one-off with its own selected date/time. The source series and skipped
dates remain unchanged.

## Updating safely

The preparation workflow requires the matching updated server and Android app.
Existing events, reminder profiles, pairing, queued changes and receipts remain
intact; there is no storage-schema reset. Older apps can read meal events and
make ordinary notes edits. An older editor cannot move a meal's hidden cooking
plan by changing its schedule; it must update first. New clients retain workflow
edits when an older server does not support them. Export/import, encrypted
backups, history and restores preserve the complete plan and progress.
