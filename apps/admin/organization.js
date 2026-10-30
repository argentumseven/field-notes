// Typed collections and standalone reference notes share the command/revision
// boundary. Inbox is the absence of a container, never an artificial project.
function containers() {
  return [...records("list"), ...records("project")].sort((a, b) =>
    a.data.title.localeCompare(b.data.title),
  );
}
async function newCollection(kind = "list") {
  await formDialog(
    kind === "list" ? "New list" : "New project",
    kind === "list"
      ? "A shared checklist, shopping list or recurring collection."
      : "An outcome with related tasks and a place for context.",
    [
      {
        key: "title",
        label: kind === "list" ? "List name" : "Project name",
        max: 160,
      },
      {
        key: "notes",
        label: "Description",
        type: "textarea",
        required: false,
        max: 16384,
      },
    ],
    (v) => mutate(kind + ".create", uuid(), v, null),
    "Create " + kind,
  );
}
async function editCollection(record) {
  await formDialog(
    "Edit " + record.kind,
    "Tasks must be moved or removed before deleting this collection.",
    [
      { key: "title", label: "Name", value: record.data.title, max: 160 },
      {
        key: "notes",
        label: "Description",
        type: "textarea",
        value: record.data.notes || "",
        required: false,
        max: 16384,
      },
      {
        key: "action",
        label: "Action",
        options: [
          ["save", "Save changes"],
          ["delete", "Delete empty collection"],
        ],
      },
      {
        key: "confirmation",
        label: "For deletion, type DELETE",
        required: false,
      },
    ],
    async (v) => {
      if (v.action === "delete") {
        if (v.confirmation !== "DELETE")
          throw Error("Type DELETE to remove the collection.");
        await mutate(record.kind + ".delete", record.id, {}, record.revision);
        state.project = null;
        state.page = "collections";
        render();
      } else
        await mutate(
          record.kind + ".update",
          record.id,
          { title: v.title, notes: v.notes },
          record.revision,
        );
    },
    "Apply",
  );
}
async function newStandaloneTask() {
  let created;
  await formDialog(
    "New task",
    "Capture first. Organize and add dates whenever you need them.",
    [
      { key: "title", label: "Task title", max: 240 },
      {
        key: "project_id",
        label: "Keep in",
        options: [
          ["", "Inbox"],
          ...containers().map((c) => [c.id, c.data.title + " · " + c.kind]),
        ],
        value: state.project || "",
      },
    ],
    async (v) => {
      created = await mutate("task.create", uuid(), v, null);
    },
    "Create task",
  );
  state.selected = created.record.id;
  renderDetail();
}
async function newNote() {
  let created;
  await formDialog(
    "New note",
    "A shared reference, idea or piece of information that does not need to become a task.",
    [
      { key: "title", label: "Note title", max: 240 },
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
  state.project = null;
  state.selected = created.record.id;
  render();
}
function renderCollections(root) {
  const tools = el("div", "toolbar");
  if (mayWrite())
    tools.append(
      button("New list", () => newCollection("list"), "primary"),
      button("New project", () => newCollection("project")),
    );
  root.append(tools);
  for (const kind of ["list", "project"]) {
    const section = el("section", "collection-library");
    section.append(el("h2", "", kind === "list" ? "Lists" : "Projects"));
    const group = containers().filter((c) => c.kind === kind);
    if (!group.length)
      section.append(
        el(
          "p",
          "muted",
          kind === "list"
            ? "Shopping, routines, packing: collect tasks without turning everything into a project."
            : "Give an outcome its own tasks and context.",
        ),
      );
    for (const c of group) {
      const row = button(
        "",
        () => {
          state.project = c.id;
          state.page = "tasks";
          state.query = "";
          render();
        },
        "collection-row",
      );
      row.append(
        el("strong", "", c.data.title),
        el(
          "span",
          "item-sub",
          records("task").filter(
            (t) => t.data.project_id === c.id && t.data.status !== "done",
          ).length + " open tasks",
        ),
        el("span", "muted", c.data.notes || ""),
      );
      section.append(row);
    }
    root.append(section);
  }
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
