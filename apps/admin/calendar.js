// Calendar views. Shared state/DOM/API helpers are defined in app.js.
function calendarItems(day) {
  const next = new Date(day + "T00:00:00");
  next.setDate(next.getDate() + 1);
  const start = new Date(day + "T00:00:00").getTime() / 1000,
    end = next.getTime() / 1000;
  return records("task")
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
        .filter((e) =>
          e.data.all_day
            ? day >= e.data.start_date && day < e.data.end_date
            : e.data.starts_at < end && e.data.ends_at > start,
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
}
function renderCalendar(root) {
  const actions = el("div", "toolbar"),
    title = el(
      "h2",
      "",
      state.month.toLocaleDateString([], { month: "long", year: "numeric" }),
    );
  actions.append(
    button("‹", () => {
      state.month.setMonth(state.month.getMonth() - 1);
      render();
    }),
    title,
    button("›", () => {
      state.month.setMonth(state.month.getMonth() + 1);
      render();
    }),
    button("Today", () => {
      const n = new Date();
      state.month = new Date(n.getFullYear(), n.getMonth(), 1);
      state.day = FieldPlanning.localDay();
      render();
    }),
  );
  if (mayWrite())
    actions.append(button("Add event", () => eventEditor(), "primary"));
  root.append(actions);
  const grid = el("div", "calendar-grid");
  for (const d of ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"])
    grid.append(el("div", "calendar-weekday", d));
  const first = (state.month.getDay() + 6) % 7,
    length = new Date(
      state.month.getFullYear(),
      state.month.getMonth() + 1,
      0,
    ).getDate();
  for (let i = 0; i < 42; i++) {
    const n = i - first + 1;
    if (n < 1 || n > length) {
      grid.append(el("div", "calendar-blank"));
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
          (state.day === day ? " selected" : "") +
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
      new Date(state.day + "T12:00:00").toLocaleDateString([], {
        weekday: "long",
        month: "short",
        day: "numeric",
      }),
    ),
    el("p", "muted", Intl.DateTimeFormat().resolvedOptions().timeZone),
  );
  const items = calendarItems(state.day);
  if (!items.length)
    agenda.append(
      el(
        "p",
        "muted",
        "No scheduled items. Undated tasks remain in Focus and All tasks.",
      ),
    );
  for (const item of items) {
    const row = button(
      "",
      () => {
        if (item.record.kind === "task") {
          state.selected = item.record.id;
          renderDetail();
        } else eventEditor(item.record);
      },
      "agenda-row",
    );
    row.append(
      el("span", "badge", item.label),
      el("span", "", item.record.data.title),
    );
    agenda.append(row);
  }
  root.append(agenda);
  const undated = records("task").filter(
    (t) => !t.data.due && !t.data.planned_for && t.data.status !== "done",
  );
  root.append(
    el(
      "p",
      "muted",
      undated.length + " unscheduled tasks · available in Focus and All tasks",
    ),
  );
}
async function eventEditor(record) {
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
  await formDialog(
    record ? "Edit calendar event" : "New calendar event",
    "All-day end is exclusive. Timed events use an explicit ISO offset to avoid daylight-saving ambiguity. Display zone: " +
      Intl.DateTimeFormat().resolvedOptions().timeZone,
    [
      { key: "title", label: "Event title", value: d.title, max: 240 },
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
        label: "Event type",
        options: [
          ["true", "All day"],
          ["false", "Timed (ISO + offset)"],
        ],
        value: d.all_day === false ? "false" : "true",
      },
      {
        key: "start_date",
        label: "Start day",
        type: "date",
        value: d.start_date || state.day,
        required: false,
      },
      {
        key: "end_date",
        label: "End day (exclusive)",
        type: "date",
        value:
          d.end_date ||
          FieldPlanning.localDay(
            new Date(new Date(state.day + "T12:00:00").getTime() + 86400000),
          ),
        required: false,
      },
      {
        key: "starts_at",
        label: "Timed start · e.g. 2026-11-01T09:00:00-05:00",
        value: d.starts_at ? new Date(d.starts_at * 1000).toISOString() : "",
        required: false,
      },
      {
        key: "ends_at",
        label: "Timed end · ISO + offset",
        value: d.ends_at ? new Date(d.ends_at * 1000).toISOString() : "",
        required: false,
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
      const all = v.all_day === "true";
      let start = 0,
        end = 0;
      if (!all) {
        if (
          !/[zZ]|[+-]\d{2}:\d{2}$/.test(v.starts_at) ||
          !/[zZ]|[+-]\d{2}:\d{2}$/.test(v.ends_at)
        )
          throw Error("Timed events need an explicit offset or Z.");
        start = Date.parse(v.starts_at) / 1000;
        end = Date.parse(v.ends_at) / 1000;
        if (!Number.isFinite(start) || !Number.isFinite(end))
          throw Error("Invalid time.");
      }
      await mutate(
        record ? "event.update" : "event.create",
        record?.id || uuid(),
        {
          title: v.title,
          notes: v.notes,
          all_day: all,
          start_date: all ? v.start_date : "",
          end_date: all ? v.end_date : "",
          starts_at: start,
          ends_at: end,
        },
        record?.revision ?? null,
      );
    },
    "Save event",
  );
}
