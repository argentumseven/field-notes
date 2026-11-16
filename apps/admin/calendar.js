// Calendar views. Shared state/DOM/API helpers are defined in app.js.
function calendarItems(day) {
  const next = new Date(day + "T00:00:00");
  next.setDate(next.getDate() + 1);
  const start = new Date(day + "T00:00:00").getTime() / 1000,
    end = next.getTime() / 1000;
  const result = records("task")
    .filter((t) => t.data.due === day || t.data.planned_for === day)
    .map((t) => ({
      record: t,
      label: [
        t.data.due === day ? "Due" : "",
        t.data.planned_for === day ? "Planned" : "",
      ]
        .filter(Boolean)
        .join(" · "),
    }))
    .concat(
      records("event")
        .flatMap((e) =>
          FieldRecurrence.instances(e, day, FieldPlanning.shiftDay(day, 1)),
        )
        .filter((e) =>
          e.data.all_day
            ? day >= e.data.start_date && day < e.data.end_date
            : e.data.ends_at
              ? e.data.starts_at < end && e.data.ends_at > start
              : e.data.starts_at >= start && e.data.starts_at < end,
        )
        .map((e) => ({
          record: e,
          label: e.data.all_day
            ? "All day"
            : new Date(e.data.starts_at * 1000).toLocaleTimeString([], {
                hour: "2-digit",
                minute: "2-digit",
              }),
        })),
    );
  for (const note of records("note"))
    if (
      note.data.on_radar &&
      note.data.remind_at >= start &&
      note.data.remind_at < end
    )
      result.push({ record: note, label: "Follow up" });
  for (const record of [...records("task"), ...records("event")]) {
    const label = FieldReminders.windowLabel(record, day);
    if (!label) continue;
    const existing = result.find((entry) => entry.record.id === record.id);
    if (existing) existing.windowLabel = label;
    else result.push({ record, label, windowLabel: label, windowOnly: true });
  }
  return result.filter((entry) =>
    state.page === "meals"
      ? !!entry.record.data.meal_kind
      : !entry.record.data.meal_kind ||
        readSetting("field-notes-calendar-meals") === "on",
  );
}
function calendarTime(at) {
  return new Date(at * 1000).toLocaleTimeString([], {
    hour: "numeric",
    minute: "2-digit",
  });
}
function calendarEventLabel(record) {
  return record.data.all_day
    ? "All day"
    : calendarTime(record.data.starts_at) +
        (record.data.ends_at
          ? " – " + calendarTime(record.data.ends_at)
          : " · Start only");
}
function renderCalendar(root) {
  const view =
    state.page === "meals"
      ? state.mealsView || readSetting("field-notes-meals-view") || "week"
      : state.calendarView ||
        readSetting("field-notes-calendar-view") ||
        (innerWidth < 760 ? "day" : "week");
  if (state.page === "meals") state.mealsView = view;
  else state.calendarView = view;
  const chosen = new Date(state.day + "T12:00:00"),
    weekStart = new Date(chosen);
  weekStart.setDate(weekStart.getDate() - weekStart.getDay());
  const days = Array.from({ length: view === "week" ? 7 : 1 }, (_, i) => {
    const day = new Date(view === "week" ? weekStart : chosen);
    day.setDate(day.getDate() + i);
    return FieldPlanning.localDay(day);
  });
  const toolbar = el("div", "calendar-toolbar"),
    nav = el("div", "calendar-navigation"),
    modes = el("div", "segmented");
  const move = (amount) => {
    if (view === "month") {
      state.month.setMonth(state.month.getMonth() + amount);
      state.day = FieldPlanning.localDay(state.month);
    } else {
      const date = new Date(state.day + "T12:00");
      date.setDate(date.getDate() + amount * (view === "week" ? 7 : 1));
      state.day = FieldPlanning.localDay(date);
      state.month = new Date(date.getFullYear(), date.getMonth(), 1);
    }
    render();
  };
  const title =
    view === "month"
      ? state.month.toLocaleDateString([], { month: "long", year: "numeric" })
      : view === "week"
        ? new Intl.DateTimeFormat([], {
            month: "short",
            day: "numeric",
            year: "numeric",
          }).formatRange(
            weekStart,
            new Date(new Date(weekStart).setDate(weekStart.getDate() + 6)),
          )
        : chosen.toLocaleDateString([], {
            weekday: "long",
            month: "short",
            day: "numeric",
            year: "numeric",
          });
  const previous = button("‹", () => move(-1)),
    next = button("›", () => move(1));
  previous.setAttribute(
    "aria-label",
    "Previous " +
      (view === "month" ? "month" : view === "week" ? "week" : "day"),
  );
  next.setAttribute(
    "aria-label",
    "Next " + (view === "month" ? "month" : view === "week" ? "week" : "day"),
  );
  nav.append(
    previous,
    next,
    el("h2", "", title),
    button("Today", () => {
      const now = new Date();
      state.day = FieldPlanning.localDay();
      state.month = new Date(now.getFullYear(), now.getMonth(), 1);
      render();
    }),
  );
  for (const mode of ["day", "week", "month"]) {
    const b = button(
      mode[0].toUpperCase() + mode.slice(1),
      () => {
        if (state.page === "meals") state.mealsView = mode;
        else state.calendarView = mode;
        try {
          localStorage.setItem(
            state.page === "meals"
              ? "field-notes-meals-view"
              : "field-notes-calendar-view",
            mode,
          );
        } catch {}
        render();
      },
      view === mode ? "selected" : "",
    );
    b.setAttribute("aria-pressed", String(view === mode));
    modes.append(b);
  }
  toolbar.append(nav, modes);
  if (mayWrite())
    toolbar.append(
      button(
        state.page === "meals" ? "Plan food" : "New event",
        () =>
          eventEditor(null, undefined, state.page === "meals" ? "dinner" : ""),
        "primary",
      ),
    );
  if (state.page === "meals" && mayWrite())
    toolbar.append(
      button("Plan prep", () => eventEditor(null, undefined, "prep")),
    );
  const options = el("details", "calendar-options");
  options.append(el("summary", "", "Calendar options"));
  const holidayLabel = el("label", "dialog-choice"),
    holidayChoice = el("input");
  holidayChoice.type = "checkbox";
  holidayChoice.checked = FieldHolidays.enabled();
  holidayLabel.append(
    holidayChoice,
    document.createTextNode("Show US federal holidays"),
  );
  holidayChoice.onchange = () => {
    localStorage.setItem(
      "field-notes-federal-holidays",
      holidayChoice.checked ? "on" : "off",
    );
    render();
  };
  options.append(holidayLabel);
  if (state.page !== "meals") {
    const label = el("label", "dialog-choice"),
      show = el("input");
    show.type = "checkbox";
    show.checked = readSetting("field-notes-calendar-meals") === "on";
    label.append(show, document.createTextNode("Show meals"));
    show.onchange = () => {
      localStorage.setItem(
        "field-notes-calendar-meals",
        show.checked ? "on" : "off",
      );
      render();
    };
    options.append(label);
  } else
    root.append(
      el(
        "p",
        "muted",
        "Plan any food or occasion here. Show meals in Calendar is optional and never changes reminders.",
      ),
    );
  root.append(toolbar, options);
  if (state.page === "meals") {
    const mealDays =
      view === "month"
        ? Array.from(
            {
              length: new Date(
                state.month.getFullYear(),
                state.month.getMonth() + 1,
                0,
              ).getDate(),
            },
            (_, i) =>
              FieldPlanning.localDay(
                new Date(
                  state.month.getFullYear(),
                  state.month.getMonth(),
                  i + 1,
                ),
              ),
          )
        : days;
    renderMealsOverview(root, mealDays);
    return;
  }
  if (view === "month") {
    renderCalendarMonth(root);
    return;
  }
  const frame = el("section", "time-calendar"),
    headers = el("div", "calendar-day-headers");
  headers.style.gridTemplateColumns =
    "58px repeat(" + days.length + ",minmax(0,1fr))";
  const localZone = el("span", "calendar-zone", "Local time");
  localZone.title = Intl.DateTimeFormat()
    .resolvedOptions()
    .timeZone.replaceAll("_", " ");
  headers.append(localZone);
  for (const day of days) {
    const date = new Date(day + "T12:00"),
      head = button(
        "",
        () => {
          state.day = day;
          state.month = new Date(date.getFullYear(), date.getMonth(), 1);
          render();
        },
        "calendar-date-header" +
          (day === state.day ? " selected" : "") +
          (day === FieldPlanning.localDay() ? " today" : ""),
      );
    head.append(
      el("span", "", date.toLocaleDateString([], { weekday: "short" })),
      el("strong", "", String(date.getDate())),
    );
    headers.append(head);
  }
  const floating = el("div", "calendar-floating-row");
  floating.style.gridTemplateColumns = headers.style.gridTemplateColumns;
  floating.append(el("span", "calendar-floating-label", "All day / tasks"));
  for (const day of days) {
    const cell = el("div", "calendar-floating-cell"),
      items = calendarItems(day).filter(
        (i) =>
          i.record.kind === "task" || i.record.data.all_day || i.windowLabel,
      );
    for (const name of FieldHolidays.on(day))
      cell.append(el("span", "holiday-label", name));
    for (const item of items.slice(0, view === "week" ? 2 : 6))
      cell.append(
        button(
          item.record.data.title,
          () => openCalendarRecord(item.record),
          "calendar-floating-item",
        ),
      );
    if (items.length > (view === "week" ? 2 : 6))
      cell.append(
        el(
          "span",
          "muted",
          "+" + (items.length - (view === "week" ? 2 : 6)) + " more",
        ),
      );
    floating.append(cell);
  }
  const scroll = el("div", "calendar-scroller"),
    grid = el("div", "calendar-time-grid");
  grid.style.gridTemplateColumns = headers.style.gridTemplateColumns;
  const selectedStart = new Date(state.day + "T00:00:00").getTime() / 1000,
    selectedEnd =
      new Date(FieldPlanning.shiftDay(state.day, 1) + "T00:00:00").getTime() /
      1000;
  const minutes = view === "day" ? (selectedEnd - selectedStart) / 60 : 1440,
    height = (minutes / 60) * 56;
  grid.style.height = height + "px";
  const axis = el("div", "calendar-hour-axis");
  for (let minute = 0; minute < minutes; minute += 60) {
    const label = el(
      "span",
      "",
      view === "day"
        ? calendarTime(selectedStart + minute * 60)
        : new Date(
            "2000-01-01T" + String(minute / 60).padStart(2, "0") + ":00",
          ).toLocaleTimeString([], { hour: "numeric" }),
    );
    label.style.top = (minute / 60) * 56 + "px";
    axis.append(label);
  }
  grid.append(axis);
  for (const day of days) {
    const column = el(
        "div",
        "calendar-time-column" + (day === state.day ? " selected" : ""),
      ),
      start = new Date(day + "T00:00").getTime() / 1000,
      end =
        new Date(FieldPlanning.shiftDay(day, 1) + "T00:00").getTime() / 1000;
    const length = view === "day" ? (end - start) / 60 : 1440;
    column.style.height = (length / 60) * 56 + "px";
    for (let minute = 0; minute < length; minute += 60) {
      const at =
        view === "day"
          ? new Date((start + minute * 60) * 1000)
          : new Date(day + "T" + String(minute / 60).padStart(2, "0") + ":00");
      const slot = button(
        "",
        () => {
          state.day = day;
          state.month = new Date(day + "T12:00");
          return eventEditor(undefined, FieldPlanning.localTime(at));
        },
        "calendar-hour-slot",
      );
      slot.style.top = (minute / 60) * 56 + "px";
      slot.setAttribute(
        "aria-label",
        "Add event on " +
          day +
          " at " +
          at.toLocaleTimeString([], { hour: "numeric", minute: "2-digit" }),
      );
      slot.disabled = !mayWrite();
      column.append(slot);
    }
    const items = calendarItems(day)
      .filter(
        (i) =>
          i.record.kind === "event" && !i.record.data.all_day && !i.windowOnly,
      )
      .map((i) => i.record);
    const position = (at) => {
      if (view === "day") return (at - start) / 60;
      const local = new Date(at * 1000),
        date = FieldPlanning.localDay(local);
      return date < day
        ? 0
        : date > day
          ? 1440
          : local.getHours() * 60 + local.getMinutes();
    };
    const blocks = items
      .map((record) => {
        const from = Math.max(0, position(record.data.starts_at)),
          raw = record.data.ends_at
            ? Math.min(length, position(record.data.ends_at))
            : null;
        return {
          record,
          from,
          until:
            raw === null
              ? null
              : raw <= from
                ? from + (record.data.ends_at - record.data.starts_at) / 60
                : raw,
        };
      })
      .sort(
        (a, b) => a.from - b.from || (a.until ?? a.from) - (b.until ?? b.from),
      );
    let cluster = [],
      limit = -1;
    const place = () => {
      const ends = [];
      for (const block of cluster) {
        let n = ends.findIndex((end) => end <= block.from);
        if (n < 0) n = ends.length;
        block.column = n;
        ends[n] = block.until ?? block.from + 15;
      }
      for (const block of cluster) {
        const point = block.until === null,
          b = button(
            "",
            () => openCalendarRecord(block.record),
            "calendar-time-event" + (point ? " point" : ""),
          );
        b.style.top = (block.from / 60) * 56 + "px";
        b.style.height =
          Math.max(
            point ? 28 : 20,
            (((block.until ?? block.from) - block.from) / 60) * 56,
          ) + "px";
        b.style.left = `calc(${(block.column / ends.length) * 100}% + 4px)`;
        b.style.width = `calc(${100 / ends.length}% - 8px)`;
        b.title =
          calendarEventLabel(block.record) + "\n" + recordHistory(block.record);
        b.append(
          el("strong", "", (point ? "◦ " : "") + block.record.data.title),
          el("span", "", calendarEventLabel(block.record)),
        );
        column.append(b);
      }
      cluster = [];
      limit = -1;
    };
    for (const block of blocks) {
      if (cluster.length && block.from >= limit) place();
      cluster.push(block);
      limit = Math.max(limit, block.until ?? block.from + 15);
    }
    place();
    if (day === FieldPlanning.localDay()) {
      const now = el("div", "calendar-now-line");
      now.dataset.day = day;
      now.dataset.view = view;
      now.style.top = (position(Date.now() / 1000) / 60) * 56 + "px";
      column.append(now);
    }
    grid.append(column);
  }
  scroll.append(headers, floating, grid);
  frame.append(scroll);
  root.append(frame);
  const key = view + ":" + days[0];
  scroll.dataset.scrollKey = "calendar:" + key;
  state.calendarScroll ??= {};
  scroll.scrollTop = state.calendarScroll[key] ?? 7 * 56;
  scroll.onscroll = () => {
    state.calendarScroll[key] = scroll.scrollTop;
  };
  root.append(
    el(
      "p",
      "calendar-help",
      "Click a time to add an event. Outlined markers have no end time; filled blocks show occupied time.",
    ),
  );
}
function openCalendarRecord(record) {
  if (record.kind === "event" && record.data.meal_kind) {
    state.selected = record.id;
    state.mealOccurrence = record.data.starts_at;
    renderDetail();
    return;
  }
  if (record.kind === "task") {
    state.selected = record.id;
    renderDetail();
  } else if (record.kind === "note") {
    state.selected = record.id;
    renderDetail();
  } else
    return eventEditor(
      records("event").find((e) => e.id === record.id) || record,
    );
}
function renderCalendarMonth(root) {
  const grid = el("div", "calendar-grid");
  for (const d of ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"])
    grid.append(el("div", "calendar-weekday", d));
  const first = state.month.getDay(),
    length = new Date(
      state.month.getFullYear(),
      state.month.getMonth() + 1,
      0,
    ).getDate();
  for (let i = 0; i < Math.ceil((first + length) / 7) * 7; i++) {
    const n = i - first + 1;
    if (n < 1 || n > length) {
      const blank = el("div", "calendar-day outside");
      blank.setAttribute("aria-hidden", "true");
      grid.append(blank);
      continue;
    }
    const day = FieldPlanning.localDay(
        new Date(state.month.getFullYear(), state.month.getMonth(), n),
      ),
      items = calendarItems(day),
      b = button(
        "",
        () => {
          state.day = day;
          render();
        },
        "calendar-day" +
          (n < 1 || n > length ? " outside" : "") +
          (day === state.day ? " selected" : "") +
          (day === FieldPlanning.localDay() ? " today" : ""),
      );
    b.setAttribute("aria-label", day + " · " + items.length + " items");
    b.append(el("strong", "", String(n)));
    for (const name of FieldHolidays.on(day))
      b.append(el("span", "holiday-label", name));
    for (const item of items.slice(0, 2))
      b.append(el("span", "calendar-preview", item.record.data.title));
    if (items.length > 2) b.append(el("span", "", "+" + (items.length - 2)));
    grid.append(b);
  }
  root.append(grid);
  const agenda = el("section", "agenda");
  agenda.append(
    el(
      "h2",
      "",
      new Date(state.day + "T12:00").toLocaleDateString([], {
        weekday: "long",
        month: "short",
        day: "numeric",
      }),
    ),
  );
  for (const item of calendarItems(state.day)) {
    const row = button("", () => openCalendarRecord(item.record), "agenda-row");
    row.append(
      el(
        "span",
        "badge",
        item.record.kind === "event"
          ? calendarEventLabel(item.record)
          : item.label,
      ),
      el("strong", "", item.record.data.title),
      historyNode(item.record),
    );
    agenda.append(row);
  }
  if (!calendarItems(state.day).length)
    agenda.append(
      el(
        "p",
        "muted",
        "No scheduled items. Undated work remains in Tasks and Lists.",
      ),
    );
  root.append(agenda);
}
async function eventEditor(record, selectedTime, mealKind = "") {
  const d = record?.data || {},
    own = !record || isAdmin() || d.author_id === state.actor.user_id;
  if (!mayWrite() || !own) {
    await formDialog(
      d.title,
      d.notes || "Calendar event",
      [],
      async () => {},
      "Close",
    );
    return;
  }
  const initialStart = d.starts_at
      ? new Date(d.starts_at * 1000)
      : new Date(
          state.day +
            "T" +
            (selectedTime ||
              (mealKind === "dinner"
                ? "18:00"
                : mealKind === "prep"
                  ? "16:30"
                  : "09:00")) +
            ":00",
        ),
    initialEnd = d.ends_at
      ? new Date(d.ends_at * 1000)
      : new Date(initialStart.getTime() + 3600000);
  const editZone =
    d.recurrence?.zone || Intl.DateTimeFormat().resolvedOptions().timeZone;
  const editParts = (at) => FieldRecurrence.parts(at, editZone);
  const editDay = (at) => {
    const p = editParts(at);
    return `${p.year}-${p.month}-${p.day}`;
  };
  const editTime = (at) => {
    const p = editParts(at);
    return `${p.hour}:${p.minute}`;
  };
  const editInstant = (day, time, original) => {
    const at = FieldRecurrence.instant(day, time, editZone, original);
    if (at === null)
      throw Error(
        "That time is skipped when the clocks change. Choose another time.",
      );
    return at;
  };
  let reminderSettings = structuredClone(
      FieldReminders.rulesFor(record) || {
        alerts: [
          {
            id: record?.id || uuid(),
            minutes_before: 10,
            ...FieldReminders.profile,
          },
        ],
        repeat: null,
      },
    ),
    remindersDirty = !record || !!d.reminders;
  let reminderOptions = FieldReminders.optionsFor(record, "event"),
    recurrence = d.recurrence ? structuredClone(d.recurrence) : null;
  await formDialog(
    record ? "Edit event" : "New event",
    "Times shown in · " + editZone.replaceAll("_", " "),
    [
      { key: "title", label: "Event title", value: d.title, max: 240 },
      categoryField(record),
      {
        key: "meal_kind",
        required: false,
        label: "Plan as",
        value: d.meal_kind || mealKind,
        options: [
          ["", "Calendar event"],
          ["dinner", "Food or occasion"],
          ["prep", "Prep session"],
        ],
      },
      {
        key: "location",
        label: "Location · optional",
        value: d.location || "",
        required: false,
        max: 2048,
      },
      {
        key: "notes",
        label: "Notes",
        type: "textarea",
        value: d.notes,
        required: false,
        max: 16384,
      },
      {
        key: "all_day",
        label: "All day",
        type: "checkbox",
        value: d.all_day === true,
      },
      {
        key: "start_date",
        label: "Starts",
        type: "date",
        value: d.start_date || editDay(initialStart.getTime() / 1000),
      },
      {
        key: "start_time",
        label: "Start time",
        type: "time",
        value: editTime(initialStart.getTime() / 1000),
      },
      {
        key: "has_end",
        label: "Set an end time",
        type: "checkbox",
        value: !!d.ends_at,
      },
      {
        key: "end_date",
        label: "Ends",
        type: "date",
        value: d.end_date
          ? FieldPlanning.shiftDay(d.end_date, -1)
          : editDay(initialEnd.getTime() / 1000),
      },
      {
        key: "end_time",
        label: "End time",
        type: "time",
        value: editTime(initialEnd.getTime() / 1000),
      },
      ...(record
        ? [
            {
              key: "operation",
              label: "Action",
              options: [
                ["save", "Save event"],
                ["delete", "Delete event"],
              ],
            },
            {
              key: "confirmation",
              label: "For deletion, type DELETE",
              required: false,
            },
          ]
        : []),
    ],
    async (v) => {
      if (v.operation === "delete") {
        if (v.confirmation !== "DELETE")
          throw Error("Type DELETE to remove this event.");
        await mutate("event.delete", record.id, {}, record.revision);
        return;
      }
      const all = v.all_day;
      if (all && v.end_date < v.start_date)
        throw Error("The last day cannot be before the first day.");
      const start = all
          ? 0
          : editInstant(v.start_date, v.start_time, d.starts_at),
        end =
          all || !v.has_end
            ? 0
            : editInstant(v.end_date, v.end_time, d.ends_at);
      if (!all && v.has_end && end <= start)
        throw Error(
          "Choose an end time after the start. For an overnight event, change the end date too.",
        );
      if (recurrence) FieldRecurrence.validate(recurrence);
      FieldReminders.validate(reminderSettings);
      FieldReminders.validateOptions(reminderOptions);
      await mutate(
        record ? "event.update" : "event.create",
        record?.id || uuid(),
        {
          ...(record?.data.meal_plan
            ? { meal_plan: record.data.meal_plan }
            : {}),
          title: v.title,
          meal_kind: v.meal_kind,
          category: v.category,
          ...(record?.data.category?.startsWith("custom:") &&
          v.category !== record.data.category
            ? { category_override: true }
            : {}),
          recurrence,
          notes: v.notes,
          location: v.location,
          all_day: all,
          start_date: all ? v.start_date : "",
          end_date: all ? FieldPlanning.shiftDay(v.end_date, 1) : "",
          starts_at: start,
          ends_at: end,
          ...(remindersDirty ? { reminders: reminderSettings } : {}),
          reminder_options: reminderOptions,
        },
        record?.revision ?? null,
      );
    },
    "Save event",
    (nodes) => {
      const update = () => {
        for (const key of ["start_time", "end_time"]) {
          nodes[key].hidden = nodes.all_day.checked;
          nodes[key].previousElementSibling.hidden = nodes.all_day.checked;
          nodes[key].required = !nodes.all_day.checked;
        }
        nodes.has_end.closest("label").hidden = nodes.all_day.checked;
        for (const key of ["end_date", "end_time"]) {
          const visible = nodes.all_day.checked
            ? key === "end_date"
            : nodes.has_end.checked;
          nodes[key].hidden = nodes[key].previousElementSibling.hidden =
            !visible;
          nodes[key].required = visible;
        }
        nodes.start_date.previousElementSibling.textContent = nodes.all_day
          .checked
          ? "First day"
          : nodes.meal_kind.value
            ? "Ready date"
            : "Starts";
        nodes.start_time.labels[0].textContent = nodes.meal_kind.value
          ? "Ready time"
          : "Start time";
        nodes.end_date.previousElementSibling.textContent = nodes.all_day
          .checked
          ? "Last day · included"
          : "Ends";
      };
      nodes.all_day.onchange = update;
      nodes.has_end.onchange = update;
      nodes.meal_kind.onchange = update;
      update();
      if (record) {
        nodes.operation.hidden = true;
        nodes.operation.previousElementSibling.hidden = true;
        nodes.confirmation.hidden = true;
        nodes.confirmation.previousElementSibling.hidden = true;
        const remove = button(
          "Delete event…",
          () => {
            const deleting = nodes.operation.value !== "delete";
            nodes.operation.value = deleting ? "delete" : "save";
            nodes.confirmation.hidden = !deleting;
            nodes.confirmation.previousElementSibling.hidden = !deleting;
            remove.textContent = deleting ? "Keep event" : "Delete event…";
            $("#dialog-submit").textContent = deleting
              ? "Delete event"
              : "Save event";
            for (const key of [
              "title",
              "notes",
              "all_day",
              "start_date",
              "start_time",
              "end_date",
              "end_time",
            ])
              nodes[key].disabled = deleting;
            if (deleting) nodes.confirmation.focus();
            else update();
          },
          "danger",
        );
        const actions = el("div", "event-editor-actions");
        $("#dialog-body").append(actions);
        actions.append(remove);
        const clone = button("Clone…", () => {
          state.dismissDialog?.();
          return cloneEvent(record);
        });
        remove.before(clone);
      }
      const recurrenceRoot = el("section");
      $("#dialog-body").append(recurrenceRoot);
      FieldRecurrence.controls(
        recurrenceRoot,
        recurrence,
        () => nodes.start_date.value || state.day,
        (value) => {
          recurrence = value;
        },
      );
      const reminderRoot = el("section");
      $("#dialog-body").append(reminderRoot);
      const reminderUi = FieldReminders.controls(
        reminderRoot,
        reminderSettings,
        () => nodes.start_date.value || state.day,
        (v) => {
          reminderSettings = v;
          remindersDirty = true;
        },
        {
          value: reminderOptions,
          kind: "event",
          changed: (v) => {
            reminderOptions = v;
          },
        },
      );
      reminderUi.flat();
      if (d.all_day && !reminderSettings.alerts?.some((r) => r.day_time))
        reminderRoot.prepend(
          el(
            "p",
            "muted",
            "Saved all-day reminders use 9 AM on the first day. Use Before start or After start.",
          ),
        );
      const recipeRoot = el("section");
      const recipes = records("note").filter(
        (r) => r.data.note_type === "recipe",
      );
      if (recipes.length) {
        const choice = addField(
          recipeRoot,
          select(
            "Use recipe",
            [
              ["", "Choose a recipe"],
              ...recipes.map((r) => [r.id, r.data.title]),
            ],
            "",
          ),
        );
        choice.onchange = () => {
          const r = byId(choice.value);
          if (!r) return;
          if (!nodes.title.value) nodes.title.value = r.data.title;
          nodes.notes.value = [
            r.data.body,
            r.data.ingredients,
            r.data.instructions,
          ]
            .filter(Boolean)
            .join("\n\n")
            .slice(0, 16384);
        };
        recipeRoot.append(
          el(
            "p",
            "muted",
            "Copies recipe instructions. Add named reminders for prep steps, before or after dinner.",
          ),
        );
      }
      const details = el("div"),
        copy = button("Copy location", () =>
          copyPlannerText(nodes.location.value),
        );
      copy.disabled = !nodes.location.value;
      nodes.location.addEventListener("input", () => {
        copy.disabled = !nodes.location.value;
      });
      details.append(copy);
      const fieldNodes = (keys) =>
        keys.flatMap((k) => editorFieldNodes(nodes[k]));
      editorTabs($("#dialog-body"), [
        [
          "Schedule",
          [
            ...fieldNodes([
              "meal_kind",
              "all_day",
              "start_date",
              "start_time",
              "has_end",
              "end_date",
              "end_time",
            ]),
            recurrenceRoot,
          ],
        ],
        ["Reminders", [reminderRoot]],
        [
          "Details",
          [
            recipeRoot,
            ...fieldNodes(["location"]),
            details,
            ...fieldNodes(["notes", "category"]),
          ],
        ],
      ]);
      let oldDate = nodes.start_date.value;
      nodes.start_date.onchange = () => {
        const days = Math.max(
          0,
          FieldPlanning.day(nodes.end_date.value) - FieldPlanning.day(oldDate),
        );
        if (nodes.start_date.value) {
          nodes.end_date.value = FieldPlanning.shiftDay(
            nodes.start_date.value,
            days,
          );
          oldDate = nodes.start_date.value;
        }
      };
      const actions = $("#dialog-body .event-editor-actions");
      if (actions) $("#dialog-body").append(actions);
      let previousTime = nodes.start_time.value;
      nodes.start_time.onchange = () => {
        try {
          const duration =
            FieldPlanning.scheduledInstant(
              nodes.end_date.value,
              nodes.end_time.value,
              d.ends_at,
            ) -
            FieldPlanning.scheduledInstant(
              nodes.start_date.value,
              previousTime,
              d.starts_at,
            );
          const start = FieldPlanning.scheduledInstant(
            nodes.start_date.value,
            nodes.start_time.value,
          );
          const end = new Date(
            (start + (duration > 0 ? duration : 3600)) * 1000,
          );
          nodes.end_date.value = FieldPlanning.localDay(end);
          nodes.end_time.value = FieldPlanning.localTime(end);
        } catch {
          /* Save explains incomplete or skipped clock selections. */
        }
        previousTime = nodes.start_time.value;
      };
    },
  );
}

function updateCalendarClock() {
  for (const line of document.querySelectorAll(".calendar-now-line")) {
    if (line.dataset.day !== FieldPlanning.localDay()) {
      line.hidden = true;
      continue;
    }
    const now = new Date(),
      start = new Date(line.dataset.day + "T00:00").getTime() / 1000;
    const minute =
      line.dataset.view === "day"
        ? (Date.now() / 1000 - start) / 60
        : now.getHours() * 60 + now.getMinutes();
    line.style.top = (minute / 60) * 56 + "px";
  }
}

async function cloneEvent(record) {
  const zone =
    record.data.recurrence?.zone ||
    Intl.DateTimeFormat().resolvedOptions().timeZone;
  const original = record.data.all_day
    ? record.data.start_date
    : (() => {
        const p = FieldRecurrence.parts(record.data.starts_at, zone);
        return `${p.year}-${p.month}-${p.day}`;
      })();
  const dates = [],
    times = [];
  const originalClock = record.data.all_day
    ? null
    : FieldRecurrence.parts(record.data.starts_at, zone);
  const defaultTime = originalClock
    ? `${originalClock.hour}:${originalClock.minute}`
    : "";
  let prepared = null;
  await formDialog(
    "Clone event",
    "Choose dates and times for independent copies with the same details. The original series stays unchanged.",
    [],
    async () => {
      if (!prepared) {
        const selected = dates.map((n) => n.value);
        if (
          !selected.length ||
          selected.some((d) => !d) ||
          new Set(selected.map((d, i) => d + times[i]?.value)).size !==
            selected.length
        )
          throw Error("Choose each copy date once.");
        prepared = selected.map((date, i) => {
          const data = eventCopyData(
            record,
            date,
            zone,
            original,
            times[i]?.value,
          );
          if (data.reminders?.interval)
            data.reminders = FieldReminders.forSave(data.reminders, null);
          return {
            operation_id: uuid(),
            epoch: state.snapshot.epoch,
            generation: state.snapshot.generation,
            action: "event.create",
            target: uuid(),
            expected_revision: null,
            data,
          };
        });
      }
      for (const n of [...dates, ...times]) if (n) n.disabled = true;
      for (const command of prepared) await applyCommand(command);
    },
    "Create copies",
    () => {
      const body = $("#dialog-body"),
        list = el("div");
      body.append(list);
      const add = () => {
        const row = el("div", "clone-date"),
          dayGroup = el("div"),
          timeGroup = el("div"),
          n = addField(
            dayGroup,
            input(
              "Copy date " + (dates.length + 1),
              FieldPlanning.shiftDay(dates.at(-1)?.value || original, 1),
              "date",
            ),
          );
        row.append(dayGroup);
        n.required = true;
        dates.push(n);
        const time = record.data.all_day
          ? null
          : addField(
              timeGroup,
              input("Copy time " + dates.length, defaultTime, "time"),
            );
        times.push(time);
        if (time) {
          time.required = true;
          row.append(timeGroup);
        }
        row.append(
          button("Remove", () => {
            if (prepared)
              throw Error(
                "Finish or cancel this saved copy plan before changing its dates.",
              );
            const index = dates.indexOf(n);
            dates.splice(index, 1);
            times.splice(index, 1);
            row.remove();
            dates.forEach(
              (input, i) =>
                (input.labels[0].textContent = "Copy date " + (i + 1)),
            );
            times.forEach((input, i) => {
              if (input) input.labels[0].textContent = "Copy time " + (i + 1);
            });
          }),
        );
        list.append(row);
      };
      add();
      body.append(
        button("Add date", () => {
          if (!prepared && dates.length < 31) add();
        }),
      );
    },
  );
}
function eventCopyData(record, date, zone, original, clock = null) {
  const d = record.data,
    delta = Math.round(
      (new Date(date + "T12:00Z") - new Date(original + "T12:00Z")) / 86400000,
    );
  const data = Object.fromEntries(
    [
      "title",
      "notes",
      "location",
      "meal_kind",
      "meal_plan",
      "category",
      "all_day",
      "start_date",
      "end_date",
      "starts_at",
      "ends_at",
    ]
      .filter((k) => k in d)
      .map((k) => [k, structuredClone(d[k])]),
  );
  data.recurrence = null;
  const startParts = d.all_day
    ? null
    : FieldRecurrence.parts(d.starts_at, zone);
  const shift = clock
    ? (Number(clock.slice(0, 2)) * 60 +
        Number(clock.slice(3, 5)) -
        Number(startParts.hour) * 60 -
        Number(startParts.minute)) *
      60000
    : 0;
  if (d.all_day) {
    data.start_date = date;
    data.end_date = FieldPlanning.shiftDay(d.end_date, delta);
  } else
    for (const key of ["starts_at", "ends_at"])
      if (d[key]) {
        const p = FieldRecurrence.parts(d[key], zone);
        const day = FieldPlanning.shiftDay(
          `${p.year}-${p.month}-${p.day}`,
          delta,
        );
        const changed = new Date(
          new Date(`${day}T${p.hour}:${p.minute}:00Z`).getTime() + shift,
        ).toISOString();
        const at = FieldRecurrence.instant(
          changed.slice(0, 10),
          changed.slice(11, 16),
          zone,
        );
        if (at === null)
          throw Error(
            "A copy time falls in a daylight-saving gap. Choose another date.",
          );
        data[key] = at;
      }
  const rules = FieldReminders.rulesFor(record);
  if (rules) {
    data.reminders = structuredClone(rules);
    if (data.reminders.repeat) {
      const w = data.reminders.repeat;
      w.starts_on = FieldPlanning.shiftDay(w.starts_on, delta);
      if (w.ends_on) w.ends_on = FieldPlanning.shiftDay(w.ends_on, delta);
    }
  }
  data.reminder_options = FieldReminders.optionsFor(record, "event");
  if (data.meal_plan) data.meal_plan.progress = [];
  return data;
}

function renderMealsOverview(root, days) {
  const overview = el("div", "meal-overview");
  for (const day of days) {
    const section = el("section", "meal-day");
    section.append(
      el(
        "h3",
        "",
        new Date(day + "T12:00").toLocaleDateString([], {
          weekday: "long",
          month: "short",
          day: "numeric",
        }),
      ),
    );
    const meals = calendarItems(day).filter((x) => x.record.kind === "event");
    if (!meals.length) section.append(el("p", "muted", "Nothing planned"));
    for (const { record: r } of meals) {
      const b = button("", () => openCalendarRecord(r), "meal-summary");
      b.append(
        el("strong", "", r.data.title),
        el("span", "", calendarEventLabel(r)),
      );
      const plan = r.data.meal_plan;
      if (plan) {
        try {
          const times = FieldCooking.schedule(plan, r.data.starts_at),
            begin = times[0]?.start;
          b.append(
            el(
              "span",
              "muted",
              plan.items.length +
                " items" +
                (begin ? " · Begin " + cookingClock(begin) : ""),
            ),
          );
        } catch (e) {
          b.append(el("span", "muted", "Plan needs review"));
        }
      }
      section.append(b);
    }
    overview.append(section);
  }
  root.append(overview);
}
function cookingClock(at) {
  return new Date(at * 1000).toLocaleString([], {
    weekday: "short",
    month: "short",
    day: "numeric",
    year: "numeric",
    timeZoneName: "short",
    hour: "numeric",
    minute: "2-digit",
  });
}
function renderMealDetail(record) {
  const occurrence = state.mealOccurrence || record.data.starts_at,
    plan = record.data.meal_plan || FieldCooking.empty(),
    detail = el("aside", "detail meal-detail"),
    backdrop = el("div", "detail-backdrop");
  backdrop.onclick = closeDetail;
  detail.dataset.scrollKey = "meal-detail:" + record.id + ":" + occurrence;
  detail.setAttribute("role", "dialog");
  detail.setAttribute("aria-modal", "true");
  detail.setAttribute("aria-label", "Meal workflow");
  const head = el("div", "detail-header");
  head.append(
    el("strong", "", record.data.title),
    button("Close", closeDetail),
  );
  detail.append(head, el("p", "", "Ready " + cookingClock(occurrence)));
  const own =
    mayWrite() && (isAdmin() || record.data.author_id === state.actor.user_id);
  if (own) {
    const actions = el("div", "event-editor-actions");
    if (
      plan.progress.some(
        (p) => p.state === "done" && p.occurrence < Date.now() / 1000 - 86400,
      )
    )
      actions.append(
        button("Clear completed history", async () => {
          if (
            await confirmDialog(
              "Clear older completed history?",
              "Completed phases for meals served more than 24 hours ago will be cleared. Current and future meals and running timers are kept.",
              "Clear history",
            )
          )
            await mutate("event.meal_clear", record.id, {}, record.revision);
        }),
      );
    actions.append(
      button("Edit schedule", () => {
        closeDetail();
        return eventEditor(record);
      }),
      button("Edit preparation", () => editCookingPlan(record)),
    );
    detail.append(actions);
  }
  if (record.data.all_day) {
    detail.append(el("p", "", "Set a ready time to calculate preparation."));
  } else if (!plan.items.length)
    detail.append(
      el(
        "p",
        "",
        "Add your own items and phases. The ready time sets their deadlines; durations and dependencies calculate when to begin.",
      ),
    );
  else {
    try {
      const times = FieldCooking.schedule(plan, occurrence);
      if (times.length)
        detail.append(
          el("p", "", "Get started " + cookingClock(times[0].start)),
        );
      const finish = Math.max(occurrence, ...times.map((t) => t.end));
      if (finish > occurrence)
        detail.append(
          el(
            "p",
            "warning",
            "Current progress puts the meal " +
              Math.ceil((finish - occurrence) / 60) +
              " minutes behind its ready time.",
          ),
        );
      for (const t of times) {
        const row = el("section", "cooking-phase");
        row.append(
          el("h3", "", t.stage.name),
          el(
            "p",
            "muted",
            t.item.name + " · " + (t.progress?.state || "planned"),
          ),
          el(
            "p",
            "",
            t.progress?.state === "done"
              ? "Completed " + cookingClock(t.end)
              : cookingClock(t.start) + " → " + cookingClock(t.end),
          ),
        );
        if (t.stage.after.length)
          row.append(
            el(
              "p",
              "muted",
              "After " +
                t.stage.after
                  .map((id) => times.find((v) => v.stage.id === id)?.stage.name)
                  .join(", "),
            ),
          );
        if (t.stage.notes) row.append(el("p", "", t.stage.notes));
        if (own) {
          const actions = el("div", "cooking-actions");
          const change = async (status) => {
            if (
              status === "running" &&
              t.stage.after.some(
                (id) =>
                  !plan.progress.some(
                    (p) =>
                      p.occurrence === occurrence &&
                      p.stage_id === id &&
                      p.state === "done",
                  ),
              ) &&
              !(await confirmDialog(
                "Start before prerequisites are complete?",
                "Check that the required work is ready before starting this phase.",
                "Start phase",
              ))
            )
              return;
            await mutate(
              "event.meal_stage",
              record.id,
              {
                occurrence,
                stage_id: t.stage.id,
                state: status,
                at: Math.floor(Date.now() / 1000),
              },
              record.revision,
            );
          };
          if (t.progress?.state !== "done")
            actions.append(
              button(t.progress ? "Restart timer" : "Start now", () =>
                change("running"),
              ),
              button("Done", () => change("done")),
            );
          if (t.progress)
            actions.append(
              button("Reset", async () => {
                if (
                  await confirmDialog(
                    "Reset this phase?",
                    "Its timer and completion will be cleared for this date.",
                    "Reset phase",
                  )
                )
                  await change("planned");
              }),
            );
          row.append(actions);
        }
        detail.append(row);
      }
    } catch (e) {
      detail.append(el("p", "error", e.message));
    }
  }
  document.body.append(backdrop, detail);
  detail.tabIndex = -1;
  detail.focus();
}
async function editCookingPlan(record) {
  let plan = structuredClone(record.data.meal_plan || FieldCooking.empty());
  await formDialog(
    "Preparation plan",
    "Enter any item and phase names. Durations work backward from ready time. Dependencies can connect any phases; waiting time follows all prerequisites.",
    [],
    async () => {
      FieldCooking.schedule(plan, record.data.starts_at);
      await mutate(
        "event.update",
        record.id,
        { meal_plan: plan },
        record.revision,
      );
    },
    "Save preparation",
    () => {
      const root = el("div", "cooking-editor");
      $("#dialog-body").append(root);
      const field = (
        parent,
        label,
        value,
        change,
        type = typeof value === "number" ? "number" : "text",
      ) => {
        const wrap = el("div", "cooking-field");
        parent.append(wrap);
        const n = addField(wrap, input(label, value, type));
        n.oninput = () => change(type === "number" ? Number(n.value) : n.value);
        if (type === "number") {
          n.min = 0;
          n.max = 20160;
          n.step = 1;
        }
        return n;
      };
      const redraw = () => {
        root.replaceChildren();
        for (const item of plan.items) {
          const block = el("section", "cooking-item");
          field(block, "Item name", item.name, (v) => (item.name = v));
          field(
            block,
            "Item notes",
            item.notes,
            (v) => (item.notes = v),
            "textarea",
          );
          for (const phase of item.stages) {
            const section = el("fieldset", "cooking-phase");
            section.append(el("legend", "", "Phase"));
            field(section, "Phase name", phase.name, (v) => (phase.name = v));
            const timing = el("div", "cooking-timing");
            field(
              timing,
              "Duration (minutes)",
              phase.duration_minutes,
              (v) => (phase.duration_minutes = v),
            );
            field(
              timing,
              "Finish early (minutes)",
              -phase.offset_minutes,
              (v) => (phase.offset_minutes = -v),
            );
            field(
              timing,
              "Wait after prerequisites (minutes)",
              phase.wait_minutes,
              (v) => (phase.wait_minutes = v),
            );
            field(
              timing,
              "Check every (minutes, 0 = off)",
              phase.check_every_minutes,
              (v) => (phase.check_every_minutes = v),
            );
            section.append(timing);
            field(
              section,
              "Phase notes",
              phase.notes,
              (v) => (phase.notes = v),
              "textarea",
            );
            const deps = el("details");
            deps.append(
              el("summary", "", "Depends on… (" + phase.after.length + ")"),
            );
            for (const other of plan.items
              .flatMap((i) => i.stages.map((s) => ({ item: i, stage: s })))
              .filter((t) => t.stage.id !== phase.id)) {
              const label = el("label", "dialog-choice"),
                check = el("input");
              check.type = "checkbox";
              check.checked = phase.after.includes(other.stage.id);
              label.append(
                check,
                document.createTextNode(
                  other.item.name + " · " + other.stage.name,
                ),
              );
              check.onchange = () => {
                phase.after = phase.after.filter((id) => id !== other.stage.id);
                if (check.checked) phase.after.push(other.stage.id);
              };
              deps.append(label);
            }
            section.append(deps);
            for (const [key, label] of [
              ["notify_start", "Remind me to begin"],
              ["notify_end", "Remind me when finished"],
            ]) {
              const l = el("label", "dialog-choice"),
                c = el("input");
              c.type = "checkbox";
              c.checked = phase[key];
              c.onchange = () => (phase[key] = c.checked);
              l.append(c, document.createTextNode(label));
              section.append(l);
            }
            section.append(
              button(
                "Remove phase",
                () => {
                  item.stages = item.stages.filter((s) => s !== phase);
                  for (const s of plan.items.flatMap((i) => i.stages))
                    s.after = s.after.filter((id) => id !== phase.id);
                  redraw();
                },
                "danger",
              ),
            );
            block.append(section);
          }
          block.append(
            button("Add phase", () => {
              item.stages.push({
                id: uuid(),
                name: "",
                duration_minutes: 30,
                offset_minutes: 0,
                wait_minutes: 0,
                after: item.stages.length ? [item.stages.at(-1).id] : [],
                check_every_minutes: 0,
                notes: "",
                notify_start: true,
                notify_end: true,
              });
              redraw();
            }),
            button(
              "Remove item",
              () => {
                const ids = item.stages.map((s) => s.id);
                plan.items = plan.items.filter((i) => i !== item);
                for (const s of plan.items.flatMap((i) => i.stages))
                  s.after = s.after.filter((id) => !ids.includes(id));
                redraw();
              },
              "danger",
            ),
          );
          root.append(block);
        }
        root.append(
          button("Add item", () => {
            plan.items.push({ id: uuid(), name: "", notes: "", stages: [] });
            redraw();
          }),
        );
        const sounds = el("details");
        sounds.append(el("summary", "", "Cooking reminders"));
        const audioField = (label, choices, value, change) => {
          const wrap = el("div", "cooking-field");
          sounds.append(wrap);
          const n = addField(wrap, select(label, choices, value));
          n.onchange = () => change(n.value);
          return n;
        };
        audioField(
          "Delivery mode",
          [
            ["notification", "Notification"],
            ["alarm", "Alarm"],
          ],
          plan.profile.alarm ? "alarm" : "notification",
          (v) => {
            plan.profile.alarm = v === "alarm";
            plan.profile.untilDismissed = plan.profile.alarm;
            plan.profile.duration = 0;
            plan.profile.unmute = false;
            plan.profile.notificationUnmute = false;
            redraw();
          },
        );
        audioField(
          "Cooking sound",
          Object.entries({
            chime: "Chime",
            bell: "Bell",
            pulse: "Pulse",
            marimba: "Marimba",
            signal: "Signal",
            crystal: "Crystal",
            beacon: "Beacon",
            orbit: "Orbit",
            sunrise: "Sunrise",
            siren: "Siren",
            radar: "Radar",
            klaxon: "Klaxon",
          }),
          plan.profile.sound,
          (v) => (plan.profile.sound = v),
        );
        audioField(
          "Cooking sound level",
          [25, 50, 75, 100, 125, 150, 200].map((n) => [String(n), n + "%"]),
          String((plan.profile.volume * plan.profile.gain) / 100),
          (v) => {
            plan.profile.volume = Math.min(100, +v);
            plan.profile.gain = +v > 100 ? +v : 100;
          },
        );
        if (plan.profile.alarm)
          audioField(
            "Cooking alarm duration",
            [
              ["dismiss", "Until dismissed"],
              ["0", "Play once"],
              ...[5, 10, 15, 30, 60].map((n) => [String(n), n + " seconds"]),
            ],
            plan.profile.untilDismissed
              ? "dismiss"
              : String(plan.profile.duration),
            (v) => {
              plan.profile.untilDismissed = v === "dismiss";
              plan.profile.duration = v === "dismiss" ? 0 : +v;
            },
          );
        sounds.append(
          el(
            "p",
            "muted",
            "Reminders inherit the meal’s recipients. 100% is the standard sound level; 125–200% adds boost. Your phone’s volume and Do Not Disturb settings apply.",
          ),
        );
        root.append(sounds);
      };
      redraw();
    },
  );
}
