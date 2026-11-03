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
        value: state.page === "mine" ? state.actor.user_id : "",
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

async function newNote() {
  let created;
  await formDialog(
    "New note",
    "A shared reference, idea or piece of information that does not need to become a task.",
    [
      { key: "title", label: "Note title", max: 240 },
      { key: "pinned", label: "Pin note", type: "checkbox", value: false },
      {
        key: "body",
        label: "Content",
        type: "textarea",
        required: false,
        max: 16384,
      },
    ],
    async (v) => {
      created = await mutate("note.create", uuid(), v, null);
    },
    "Create note",
  );
  state.page = "notes";
  state.query = "";
  state.project = null;
  state.selected = null;
  render();
}
function renderNotes(root) {
  const tools = el("div", "toolbar"),
    search = el("input");
  search.type = "search";
  search.placeholder = "Search notes";
  search.value = state.query;
  search.setAttribute("aria-label", "Find a note");
  search.oninput = () => {
    state.query = search.value;
    draw();
  };
  tools.append(search);
  if (mayWrite()) tools.append(button("New note", newNote, "primary"));
  root.append(tools);
  const list = el("div", "notes-list");
  root.append(list);
  function draw() {
    list.replaceChildren();
    const notes = records("note")
      .filter((n) =>
        (n.data.title + " " + n.data.body)
          .toLowerCase()
          .includes(state.query.toLowerCase()),
      )
      .sort(
        (a, b) =>
          Number(!!b.data.pinned) - Number(!!a.data.pinned) ||
          (b.data.updated_at || 0) - (a.data.updated_at || 0) ||
          a.id.localeCompare(b.id),
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
        el("strong", "", n.data.title),
        el("span", "note-preview", n.data.body.slice(0, 180)),
        el(
          "span",
          "item-sub",
          (n.data.pinned ? "Pinned · " : "") +
            new Date(n.data.updated_at * 1000).toLocaleDateString(),
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
  title.maxLength = 240;
  body.maxLength = 16384;
  body.rows = 16;
  const dirty = () =>
    state.drafts.set(note.id, {
      ...draft,
      title: title.value,
      body: body.value,
      pinned: pinned.value === "true",
    });
  for (const n of [title, body, pinned]) {
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
            { title: d.title, body: d.body, pinned: d.pinned },
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
