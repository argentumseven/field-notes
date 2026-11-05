// Typed collections and standalone reference notes share the command/revision
// boundary. Inbox is the absence of a container, never an artificial project.
function containers() {
  return [...records("list"), ...records("project")].sort((a, b) =>
    a.data.title.localeCompare(b.data.title),
  );
}
async function newStandaloneTask() {
  let reminders = { alerts: [], repeat: null },
    reminderDirty = false;
  await formDialog(
    "New task",
    "Capture everything you need in one step. Dates stay optional.",
    [
      { key: "title", label: "Task title", max: 240 },
      categoryField(),
      {
        key: "notes",
        label: "Notes",
        type: "textarea",
        required: false,
        max: 16384,
      },
      {
        key: "project_id",
        label: "Keep in",
        options: [
          ["", "Inbox"],
          ...containers()
            .filter((c) => c.kind === "project")
            .map((c) => [c.id, c.data.title]),
        ],
        value: state.project || "",
        required: false,
      },
      {
        key: "assignee",
        label: "Assigned to",
        options: assignmentOptions(),
        value: "",
        required: false,
      },
      {
        key: "priority",
        label: "Priority",
        options: [
          ["1", "Urgent"],
          ["2", "High"],
          ["3", "Normal"],
          ["4", "Low"],
        ],
        value: "3",
      },
      {
        key: "due",
        label: "Due date · optional",
        type: "date",
        required: false,
      },
      {
        key: "planned_for",
        label: "Planned day · optional",
        type: "date",
        required: false,
      },
    ],
    async (v) => {
      const data = { ...v, priority: Number(v.priority) };
      if (reminderDirty) {
        FieldReminders.validate(reminders);
        data.reminders = reminders;
      }
      await mutate("task.create", uuid(), data, null);
      state.selected = null;
      state.query = "";
      render();
    },
    "Create task",
    (nodes) =>
      FieldReminders.controls(
        $("#dialog-body"),
        reminders,
        () =>
          nodes.due.value ||
          nodes.planned_for.value ||
          FieldPlanning.localDay(),
        (v) => {
          reminders = v;
          reminderDirty = true;
        },
      ),
  );
}

function noteFollowUpControls(parent, initial = {}, changed = () => {}) {
  const section = el("section", "repeat-editor"),
    choice = el("label", "dialog-choice"),
    enabled = el("input");
  enabled.type = "checkbox";
  enabled.checked = !!initial.on_radar;
  choice.append(enabled, document.createTextNode("Keep in Later & reminders"));
  section.append(choice);
  const when = initial.remind_at ? new Date(initial.remind_at * 1000) : null,
    date = input(
      "Remind on · optional",
      initial.followup?.date || (when ? FieldPlanning.localDay(when) : ""),
      "date",
    ),
    time = input(
      "At",
      initial.followup?.time ||
        (when ? FieldPlanning.localTime(when) : "09:00"),
      "time",
    );
  date.node.required = false;
  time.node.required = false;
  if (initial.followup) enabled.checked = initial.followup.onRadar;
  const fields = el("div", "view-filters");
  fields.append(
    addField(el("div"), date).parentElement,
    addField(el("div"), time).parentElement,
  );
  section.append(fields);
  const quick = el("div", "repeat-presets");
  for (const [days, label] of [
    [1, "Tomorrow"],
    [7, "In a week"],
    [null, "Someday · no reminder"],
  ])
    quick.append(
      button(label, () => {
        enabled.checked = true;
        date.node.value =
          days === null
            ? ""
            : FieldPlanning.shiftDay(FieldPlanning.localDay(), days);
        render();
        changed();
      }),
    );
  section.append(
    quick,
    el(
      "p",
      "muted",
      "An idea can stay undated. A reminder brings it back without blocking calendar time. Everyone receives new reminders by default.",
    ),
  );
  const render = () => {
    fields.hidden = quick.hidden = !enabled.checked;
    time.node.disabled = !enabled.checked || !date.node.value;
  };
  enabled.onchange = () => {
    render();
    changed();
  };
  date.node.onchange = () => {
    render();
    changed();
  };
  time.node.onchange = changed;
  parent.append(section);
  render();
  return {
    raw: () => ({
      onRadar: enabled.checked,
      date: date.node.value,
      time: time.node.value,
    }),
    value: () => ({
      on_radar: enabled.checked,
      remind_at:
        enabled.checked && date.node.value
          ? FieldPlanning.scheduledInstant(
              date.node.value,
              time.node.value || "09:00",
              initial.remind_at,
            )
          : 0,
    }),
  };
}
async function newNote() {
  const later = state.page === "later";
  let follow;
  await formDialog(
    later ? "New reminder note" : "New note",
    "Keep the idea and context together. Dates are optional.",
    [
      { key: "title", label: "Note title", max: 240 },
      {
        key: "body",
        label: "Content",
        type: "textarea",
        required: false,
        max: 16384,
      },
      categoryField(),
    ],
    async (v) => {
      await mutate("note.create", uuid(), { ...v, ...follow.value() }, null);
    },
    later ? "Create reminder note" : "Create note",
    () => {
      follow = noteFollowUpControls($("#dialog-body"), { on_radar: later });
    },
  );
  state.page = later ? "later" : "notes";
  state.query = "";
  state.project = null;
  state.selected = null;
  render();
}
function renderNotes(root) {
  state.noteViews ||= {};
  const view = (state.noteViews[state.page] ||= {
    category: "",
    sort: "updated",
    when: "all",
  });
  const tools = el("div", "toolbar"),
    search = el("input");
  search.type = "search";
  search.placeholder = "Search note titles and content";
  search.value = state.query;
  search.setAttribute("aria-label", "Find a note");
  search.oninput = () => {
    state.query = search.value;
    draw();
  };
  tools.append(search);
  if (mayWrite()) tools.append(button("New note", newNote, "primary"));
  root.append(tools);
  const filters = el("div", "view-filters");
  const choice = (label, options, value, change) => {
    const node = select(label, options, value),
      box = el("label");
    box.append(node.label, node.node);
    node.node.onchange = () => {
      change(node.node.value);
      draw();
    };
    filters.append(box);
  };
  choice(
    "Category",
    [["*", "All categories"], ...Object.entries(recordCategories)],
    view.category || "*",
    (value) => (view.category = value === "*" ? "" : value),
  );
  choice(
    "Sort by",
    [
      ["updated", "Recently changed"],
      ["title", "Name A–Z"],
      ["reminder", "Reminder time"],
      ["created", "Recently added"],
    ],
    view.sort,
    (value) => (view.sort = value),
  );
  if (state.page === "later")
    choice(
      "Show",
      [
        ["all", "All ideas & reminders"],
        ["undated", "Undated ideas"],
        ["upcoming", "Upcoming reminders"],
        ["overdue", "Needs follow-up"],
      ],
      view.when,
      (value) => (view.when = value),
    );
  root.append(filters);
  const list = el("div", "notes-list");
  root.append(list);
  function draw() {
    list.replaceChildren();
    const notes = records("note")
      .filter((n) => state.page !== "later" || n.data.on_radar)
      .filter(
        (n) =>
          !view.category || (n.data.category || "general") === view.category,
      )
      .filter(
        (n) =>
          state.page !== "later" ||
          view.when === "all" ||
          (view.when === "undated" && !n.data.remind_at) ||
          (view.when === "upcoming" && n.data.remind_at > Date.now() / 1000) ||
          (view.when === "overdue" &&
            n.data.remind_at > 0 &&
            n.data.remind_at <= Date.now() / 1000),
      )
      .filter((n) =>
        (n.data.title + " " + n.data.body)
          .toLowerCase()
          .includes(state.query.toLowerCase()),
      )
      .sort((a, b) =>
        view.sort === "title"
          ? a.data.title.localeCompare(b.data.title)
          : view.sort === "reminder"
            ? (a.data.remind_at || Infinity) - (b.data.remind_at || Infinity)
            : view.sort === "created"
              ? (b.data.created_at || 0) - (a.data.created_at || 0)
              : Number(!!b.data.pinned) - Number(!!a.data.pinned) ||
                (b.data.last_activity_at || b.data.updated_at || 0) -
                  (a.data.last_activity_at || a.data.updated_at || 0),
      );
    if (!notes.length)
      list.append(
        el(
          "p",
          "empty",
          "Keep references, ideas and household information here. Notes stand on their own.",
        ),
      );
    for (const n of notes) {
      const row = button(
        "",
        () => {
          state.selected = n.id;
          renderDetail();
        },
        "note-row",
      );
      row.dataset.taskId = n.id;
      row.append(
        typeBadges(n),
        el("strong", "", n.data.title),
        el("span", "note-preview", n.data.body.slice(0, 180)),
        el(
          "span",
          "item-sub",
          (n.data.pinned ? "Pinned · " : "") +
            new Date(n.data.updated_at * 1000).toLocaleDateString(),
        ),
      );
      if (n.data.on_radar)
        row.append(
          el(
            "span",
            "badge",
            n.data.remind_at
              ? "Remind " + new Date(n.data.remind_at * 1000).toLocaleString()
              : "Someday · no reminder",
          ),
        );
      row.append(historyNode(n));
      list.append(row);
    }
  }
  draw();
}
function renderNoteDetail(note) {
  const backdrop = el("div", "detail-backdrop");
  backdrop.onclick = closeDetail;
  const detail = el("aside", "detail");
  detail.setAttribute("role", "dialog");
  detail.setAttribute("aria-label", "Note details");
  detail.setAttribute("aria-modal", "true");
  detail.tabIndex = -1;
  const head = el("div", "detail-header");
  head.append(
    el("span", "eyebrow", "Shared note · revision " + note.revision),
    button("Close", closeDetail, "quiet"),
  );
  detail.append(head);
  const draft = state.drafts.get(note.id) || {
    ...note.data,
    kind: "note",
    baseRevision: note.revision,
  };
  const title = addField(detail, input("Note title", draft.title)),
    body = addField(detail, input("Content", draft.body, "textarea")),
    pinned = addField(
      detail,
      select(
        "Pin note",
        [
          ["false", "Unpinned"],
          ["true", "Pinned"],
        ],
        String(!!draft.pinned),
      ),
    );
  const category = addField(
    detail,
    select(
      "Category",
      Object.entries(recordCategories),
      draft.category || "general",
    ),
  );
  const follow = noteFollowUpControls(detail, draft, () => dirty());
  title.maxLength = 240;
  body.maxLength = 16384;
  body.rows = 16;
  const dirty = () =>
    state.drafts.set(note.id, {
      ...draft,
      title: title.value,
      body: body.value,
      category: category.value,
      followup: follow.raw(),
      pinned: pinned.value === "true",
    });
  for (const n of [title, body, pinned, category]) {
    n.disabled = !mayWrite();
    n.oninput = dirty;
  }
  if (draft.baseRevision !== note.revision) {
    const conflict = el("div", "conflict");
    conflict.append(
      el("strong", "", "This note changed elsewhere. Your draft is kept."),
      el("pre", "", note.data.title + "\n\n" + note.data.body),
      button("Use server version", () => {
        state.drafts.delete(note.id);
        renderDetail();
      }),
      button("Keep draft for a new save", () => {
        dirty();
        state.drafts.get(note.id).baseRevision = note.revision;
        renderDetail();
      }),
    );
    detail.append(conflict);
  }
  if (mayWrite()) {
    detail.append(
      button(
        "Save note",
        async () => {
          dirty();
          const d = state.drafts.get(note.id);
          await mutate(
            "note.update",
            note.id,
            {
              title: d.title,
              body: d.body,
              pinned: d.pinned,
              category: d.category,
              ...follow.value(),
            },
            d.baseRevision,
          );
          state.drafts.delete(note.id);
          renderDetail();
          notice("Note saved.");
        },
        "primary",
      ),
      button(
        "Delete note",
        async () => {
          await confirmDialog(
            "Delete this note?",
            "Existing backups are unchanged.",
            "Delete note",
          );
          await mutate("note.delete", note.id, {}, note.revision);
          state.drafts.delete(note.id);
          closeDetail();
        },
        "danger",
      ),
    );
  }
  document.body.append(backdrop, detail);
  detail.focus();
}
