// Server-recorded content history. Never stores household content in browser
// storage and never imports client-supplied events into the authoritative trail.
const FieldHistory = (() => {
  let view = null;
  const types = {
    task: "Task",
    list: "List",
    project: "Project",
    note: "Note",
    event: "Calendar event",
    comment: "Comment",
    image: "Attachment",
    attachment: "Attachment",
    grocery_item: "Grocery item",
    shopping_item: "Shopping item",
    list_item: "List item",
    workspace: "Workspace",
  };
  const group = (entry) =>
    entry.action.endsWith(".create")
      ? "added"
      : entry.action.endsWith(".delete") || entry.action === "workspace.clear"
        ? "removed"
        : "changed";
  const verb = (entry) => {
    const action =
      {
        create: "Added",
        delete: "Removed",
        complete: "Completed",
        reopen: "Reopened",
        start: "Started",
        release: "Released",
        override: "Overrode working claim",
        clear: "Cleared",
      }[entry.action.split(".")[1]] || "Changed";
    return entry.source === "import"
      ? entry.action.endsWith(".create")
        ? "Imported"
        : "Imported · " + action
      : action;
  };
  const moment = (at) =>
    new Intl.DateTimeFormat(undefined, {
      dateStyle: "medium",
      timeStyle: "short",
    }).format(new Date(at * 1000));
  function value(change, data) {
    if (data === null || data === undefined || data === "") return "Not set";
    const leaf = change.field.split(".").at(-1);
    if (Array.isArray(data))
      return data.length
        ? data
            .map((v) =>
              ["weekdays", "days"].includes(leaf)
                ? [
                    "",
                    "Monday",
                    "Tuesday",
                    "Wednesday",
                    "Thursday",
                    "Friday",
                    "Saturday",
                    "Sunday",
                  ][v] || String(v)
                : value(change, v),
            )
            .join(", ")
        : "None";
    if (typeof data === "boolean") return data ? "Yes" : "No";
    if (typeof data === "number") {
      if (["starts_at", "ends_at", "remind_at", "completed_at"].includes(leaf))
        return data > 0 ? moment(data) : "Not set";
      if (["volume", "alarm_volume"].includes(leaf)) return data + "%";
      if (leaf === "duration_seconds")
        return data ? data + " seconds" : "Standard length";
      if (["minutes_before", "lead_minutes"].includes(leaf))
        return data ? data + " minutes before" : "At start";
      if (leaf === "every_minutes") return "Every " + data + " minutes";
      if (leaf === "priority" && data >= 1 && data <= 4)
        return ["", "Urgent", "High", "Normal", "Low"][data];
    }
    if (typeof data !== "string") return String(data);
    if (leaf === "fields")
      return (
        {
          quantity: "Quantity",
          store: "Store",
          notes: "Notes",
          link_url: "Web link",
          price: "Price",
          images: "Attached images",
          assignee: "Assigned to",
          due: "Due date",
          planned_for: "Planned day",
          priority: "Priority",
          estimate_minutes: "Effort",
        }[data] || data
      );
    if (/^\d{4}-\d{2}-\d{2}$/.test(data))
      return new Intl.DateTimeFormat(undefined, { dateStyle: "medium" }).format(
        new Date(data + "T12:00:00"),
      );
    if (/^\d{2}:\d{2}$/.test(data))
      return new Intl.DateTimeFormat(undefined, {
        hour: "numeric",
        minute: "2-digit",
      }).format(new Date("2000-01-01T" + data));
    if (
      [
        "status",
        "frequency",
        "mode",
        "importance",
        "sound",
        "tone",
        "template",
        "category",
      ].includes(leaf)
    )
      return (
        {
          todo: "To do",
          in_progress: "In progress",
          done: "Done",
          everyone: "Everyone",
          assigned: "Assigned person or creator",
          people: "Selected people",
          food: "Food & cooking",
          honey_do: "Honey-do",
        }[data] ||
        data.replaceAll("_", " ").replace(/^./, (s) => s.toUpperCase())
      );
    return data;
  }
  function sameScope(v) {
    return (
      view === v &&
      v.root.isConnected &&
      state.actor?.user_id === v.actor &&
      state.snapshot?.epoch === v.epoch &&
      state.snapshot?.generation === v.generation
    );
  }
  function leave() {
    if (!view) return;
    view.ticket++;
    clearTimeout(view.timer);
    view = null;
  }
  function requestBody(v, more) {
    const body = { epoch: v.epoch, generation: v.generation, ...v.filters };
    delete body.from;
    delete body.to;
    if (v.filters.from)
      body.from = new Date(v.filters.from + "T00:00:00").getTime() / 1000;
    if (v.filters.to) {
      const day = new Date(v.filters.to + "T00:00:00");
      day.setDate(day.getDate() + 1);
      body.to = day.getTime() / 1000;
    }
    if (more && v.page) {
      body.anchor = v.page.anchor;
      body.before = v.page.next_before;
    }
    return body;
  }
  async function load(v, more = false) {
    if (!sameScope(v)) return;
    const ticket = ++v.ticket;
    v.loading = true;
    v.problem = "";
    status(v);
    try {
      const page = await api(
        "/history",
        requestBody(v, more),
        undefined,
        () => sameScope(v) && ticket === v.ticket,
      );
      if (!sameScope(v) || ticket !== v.ticket) return;
      if (
        page.epoch !== v.epoch ||
        page.generation !== v.generation ||
        page.actor_id !== v.actor ||
        !Array.isArray(page.entries) ||
        page.entries.length > 50
      )
        throw Error(
          "History belongs to another workspace or account. Refresh this page.",
        );
      v.entries =
        more && v.entries.length + page.entries.length <= 300
          ? [
              ...new Map(
                [...v.entries, ...page.entries].map((e) => [e.sequence, e]),
              ).values(),
            ]
          : page.entries;
      v.page = page;
      v.older = more;
      v.fetched = Date.now();
      for (const e of page.entries)
        if (![...v.person.node.options].some((o) => o.value === e.actor_id)) {
          const option = el("option", "", e.actor_name);
          option.value = e.actor_id;
          v.person.node.append(option);
        }
      rows(v);
    } catch (error) {
      if (!sameScope(v) || ticket !== v.ticket) return;
      v.problem =
        error.status === 404
          ? "Update the server to view History. Your notes and saved work are kept."
          : error.message;
    } finally {
      if (sameScope(v) && ticket === v.ticket) {
        v.loading = false;
        status(v);
      }
    }
  }
  function status(v) {
    v.error.textContent = v.problem;
    v.error.hidden = !v.problem;
    v.status.textContent = v.loading
      ? "Loading history…"
      : v.problem
        ? "History could not refresh. Previously loaded changes remain in this tab."
        : v.entries.length +
          (v.entries.length === 1 ? " change shown" : " changes shown") +
          " · Newest first";
    v.root.setAttribute("aria-busy", String(v.loading));
    v.refresh.disabled = v.loading;
    v.more.hidden = !v.page?.has_more;
    v.more.disabled = v.loading;
    v.more.textContent = v.entries.length
      ? "Load older changes"
      : "Search older changes";
    v.newer.hidden = !(
      v.older ||
      (v.page && state.snapshot.sequence > v.page.anchor)
    );
    v.newer.textContent =
      v.page && state.snapshot.sequence > v.page.anchor
        ? "New changes · Show latest"
        : "Back to latest changes";
    for (const [key, node] of v.actions)
      node.setAttribute("aria-pressed", String(v.filters.action === key));
  }
  function schedule(v) {
    clearTimeout(v.timer);
    v.ticket++;
    v.loading = false;
    v.page = null;
    v.entries = [];
    v.older = false;
    rows(v);
    status(v);
    v.timer = setTimeout(() => load(v), 300);
  }
  function rows(v) {
    v.renderedSequence = state.snapshot?.sequence;
    v.timeline.replaceChildren();
    let day = "";
    if (!v.entries.length)
      v.timeline.append(
        el(
          "p",
          "muted history-empty",
          v.page?.has_more
            ? "No match in this part of the trail. Search older changes below."
            : Object.values(v.filters).some(Boolean)
              ? "No changes match these filters."
              : "No changes yet. Added, changed and removed items will appear here.",
        ),
      );
    for (const entry of v.entries) {
      const date = new Intl.DateTimeFormat(undefined, {
        dateStyle: "full",
      }).format(new Date(entry.at * 1000));
      if (date !== day) {
        v.timeline.append(el("h2", "history-day", date));
        day = date;
      }
      const card = el("article", "history-entry history-" + group(entry));
      card.dataset.sequence = entry.sequence;
      const header = el("div", "history-entry-header");
      header.append(
        el("span", "history-action", verb(entry)),
        el(
          "span",
          "muted",
          types[entry.item_type] || types[entry.kind] || "Item",
        ),
      );
      card.append(header, el("h3", "history-title", entry.title));
      if (entry.parent_title)
        card.append(el("p", "history-context", "In " + entry.parent_title));
      const who = el("p", "history-byline");
      const at = el("time", "", moment(entry.at));
      at.dateTime = new Date(entry.at * 1000).toISOString();
      who.append(
        el("strong", "", entry.actor_name),
        document.createTextNode(" · "),
        at,
      );
      card.append(who);
      const current = byId(entry.target),
        target = ["comment", "image"].includes(current?.kind)
          ? byId(current.data.task_id)
          : current;
      if (target)
        card.append(
          button(
            "Open item",
            () => {
              if (["list", "project"].includes(target.kind)) {
                state.page = "tasks";
                state.project = target.id;
                state.selected = null;
                render();
              } else openCalendarRecord(target);
            },
            "history-open",
          ),
        );
      if (entry.details !== "recorded")
        card.append(
          el(
            "p",
            "muted history-context",
            "Earlier change · field details were not retained. The item name is its last known title.",
          ),
        );
      else if (!entry.changes.length)
        card.append(el("p", "muted", "Saved without changing content fields."));
      if (entry.changes.length) {
        const detail = el("details", "history-changes"),
          summary = el(
            "summary",
            "",
            "Show changes (" + entry.total_fields + ")",
          );
        detail.open = v.expanded.has(entry.sequence);
        detail.ontoggle = () => {
          if (detail.open) v.expanded.add(entry.sequence);
          else v.expanded.delete(entry.sequence);
        };
        detail.append(summary);
        for (const change of entry.changes) {
          const field = el("section", "history-field");
          field.append(el("h4", "", change.label));
          const pair = el("div", "history-values");
          for (const [label, data, cls] of [
            ["Previous", change.before, "previous"],
            ["Now", change.after, "now"],
          ]) {
            const half = el("div", "history-value " + cls);
            half.append(
              el("span", "history-value-label", label),
              el("div", "history-value-text", value(change, data)),
            );
            pair.append(half);
          }
          field.append(pair);
          if (change.before_truncated || change.after_truncated)
            field.append(
              el(
                "p",
                "muted",
                "Excerpt around the edit; full content is not retained in this history entry.",
              ),
            );
          detail.append(field);
        }
        if (entry.total_fields > entry.changes.length)
          detail.append(
            el(
              "p",
              "muted",
              "Showing " +
                entry.changes.length +
                " of " +
                entry.total_fields +
                " changed fields; this large entry is summarized.",
            ),
          );
        card.append(detail);
      }
      v.timeline.append(card);
    }
  }
  function renderHistory(parent) {
    leave();
    const root = el("section", "history-view");
    root.setAttribute("aria-label", "Household change history");
    parent.append(root);
    const v = (view = {
      root,
      actor: state.actor.user_id,
      epoch: state.snapshot.epoch,
      generation: state.snapshot.generation,
      ticket: 0,
      filters: { query: "", actor: "", kind: "", action: "", from: "", to: "" },
      entries: [],
      page: null,
      loading: false,
      older: false,
      problem: "",
      expanded: new Set(),
    });
    const tools = el("div", "history-tools");
    v.search = input("Search titles, people and change details", "", "search");
    v.search.node.maxLength = 100;
    addField(tools, v.search);
    v.search.node.oninput = () => {
      v.filters.query = v.search.node.value;
      schedule(v);
    };
    v.actions = [];
    const actions = el("div", "history-actions");
    actions.setAttribute("aria-label", "History actions");
    for (const [key, label] of [
      ["", "All changes"],
      ["added", "Added"],
      ["changed", "Changed"],
      ["removed", "Removed"],
    ]) {
      const node = button(label, () => {
        v.filters.action = key;
        schedule(v);
      });
      v.actions.push([key, node]);
      actions.append(node);
    }
    tools.append(actions);
    const filters = el("details", "history-filter-disclosure"),
      heading = el("summary", "", "Filters");
    filters.append(heading);
    const fields = el("div", "history-filters");
    v.person = select(
      "Person",
      [["", "Everyone"], ...state.people.map((p) => [p.id, p.name])],
      "",
    );
    v.kind = select(
      "Item type",
      [
        ["", "All types"],
        ["task", "Tasks & list items"],
        ["list", "Lists"],
        ["project", "Projects"],
        ["note", "Notes"],
        ["event", "Calendar events"],
        ["comment", "Comments"],
        ["image", "Attachments"],
        ["workspace", "Workspace"],
      ],
      "",
    );
    v.from = input("From day", "", "date");
    v.to = input("Through day", "", "date");
    for (const [key, field] of [
      ["actor", v.person],
      ["kind", v.kind],
      ["from", v.from],
      ["to", v.to],
    ]) {
      const wrap = el("div");
      addField(wrap, field);
      fields.append(wrap);
      field.node.onchange = () => {
        v.filters[key] = field.node.value;
        schedule(v);
      };
    }
    fields.append(
      button("Clear filters", () => {
        for (const key of Object.keys(v.filters)) v.filters[key] = "";
        for (const field of [v.search, v.person, v.kind, v.from, v.to])
          field.node.value = "";
        schedule(v);
      }),
    );
    filters.append(fields);
    tools.append(filters);
    const bar = el("div", "history-toolbar");
    v.status = el("p", "muted");
    v.status.setAttribute("role", "status");
    v.refresh = button("Refresh history", () => load(v));
    v.newer = button("New changes · Show latest", () => load(v), "primary");
    bar.append(v.status, v.newer, v.refresh);
    root.append(tools, bar);
    v.error = el("p", "error");
    v.error.setAttribute("role", "alert");
    root.append(v.error);
    v.timeline = el("div", "history-timeline");
    root.append(v.timeline);
    v.more = button("Load older changes", () => load(v, true));
    root.append(v.more);
    const about = el("details", "history-about");
    about.append(
      el("summary", "", "About this history"),
      el(
        "p",
        "muted",
        "Server timestamps determine order. Unsynchronized edits appear after the server accepts them. Detailed changes are kept for the newest 10,000 events, up to 64 MB; earlier actions and timestamps remain. Clearing the workspace removes prior content details. This trail is read-only. Account, device and password activity stays in Management. Server backups include history; portable content imports record new changes rather than replacing this trail.",
      ),
    );
    root.append(about);
    load(v);
  }
  function changed() {
    const v = view;
    if (!v) return;
    if (!sameScope(v)) {
      leave();
      render();
      return;
    }
    if (
      v.page &&
      state.snapshot.sequence > v.page.anchor &&
      state.autoRefresh &&
      !v.older &&
      !v.loading &&
      !v.root.querySelector("details[open].history-changes")
    )
      load(v);
    else {
      if (v.renderedSequence !== state.snapshot.sequence) rows(v);
      status(v);
    }
  }
  return {
    render: renderHistory,
    leave,
    isOpen: () => !!view?.root.isConnected,
    changed,
    refresh: () => view && load(view),
    value,
  };
})();
