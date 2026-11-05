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
  return result;
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
    state.calendarView ||
    readSetting("field-notes-calendar-view") ||
    (innerWidth < 760 ? "day" : "week");
  state.calendarView = view;
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
        state.calendarView = mode;
        try {
          localStorage.setItem("field-notes-calendar-view", mode);
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
    toolbar.append(button("New event", () => eventEditor(), "primary"));
  root.append(toolbar);
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
  for (let i = 0; i < 42; i++) {
    const n = i - first + 1;
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
async function eventEditor(record, selectedTime) {
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
      : new Date(state.day + "T" + (selectedTime || "09:00") + ":00"),
    initialEnd = d.ends_at
      ? new Date(d.ends_at * 1000)
      : new Date(initialStart.getTime() + 3600000);
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
    "Times are local · " +
      Intl.DateTimeFormat().resolvedOptions().timeZone.replaceAll("_", " "),
    [
      { key: "title", label: "Event title", value: d.title, max: 240 },
      categoryField(record),
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
        value: d.start_date || FieldPlanning.localDay(initialStart),
      },
      {
        key: "start_time",
        label: "Start time",
        type: "time",
        value: FieldPlanning.localTime(initialStart),
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
          : FieldPlanning.localDay(initialEnd),
      },
      {
        key: "end_time",
        label: "End time",
        type: "time",
        value: FieldPlanning.localTime(initialEnd),
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
          : FieldPlanning.scheduledInstant(
              v.start_date,
              v.start_time,
              d.starts_at,
            ),
        end =
          all || !v.has_end
            ? 0
            : FieldPlanning.scheduledInstant(v.end_date, v.end_time, d.ends_at);
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
          title: v.title,
          category: v.category,
          recurrence,
          notes: v.notes,
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
          : "Starts";
        nodes.end_date.previousElementSibling.textContent = nodes.all_day
          .checked
          ? "Last day · included"
          : "Ends";
      };
      nodes.all_day.onchange = update;
      nodes.has_end.onchange = update;
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
        $("#dialog-body").append(remove);
      }
      FieldRecurrence.controls(
        $("#dialog-body"),
        recurrence,
        () => nodes.start_date.value || state.day,
        (value) => {
          recurrence = value;
        },
      );
      FieldReminders.controls(
        $("#dialog-body"),
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
