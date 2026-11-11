// List schemas, field choices and rolling checklists. No retailer requests.
const listFieldLabels = {
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
};
const listTemplates = {
  general: "General list",
  tasks: "Task list",
  groceries: "Groceries",
  shopping: "Online shopping",
  custom: "Custom list",
};
const defaultListFields = (type) =>
  type === "tasks"
    ? [
        "notes",
        "assignee",
        "due",
        "planned_for",
        "priority",
        "estimate_minutes",
        "images",
      ]
    : type === "groceries"
      ? ["quantity", "store", "notes"]
      : type === "shopping"
        ? ["quantity", "link_url", "images"]
        : type === "custom"
          ? []
          : ["notes"];
const fieldsOf = (list) => [
  ...new Set([
    ...(list.data.fields || defaultListFields(list.data.template || "general")),
  ]),
];
async function newCollection(kind = "list", defaults = {}) {
  return collectionEditor(kind, null, defaults);
}
async function editCollection(record) {
  return collectionEditor(record.kind, record);
}
async function collectionEditor(kind, record, defaults = {}) {
  const d = record?.data || defaults,
    isList = kind === "list",
    chosen =
      d.fields ||
      defaultListFields(d.items_are_tasks ? "tasks" : d.template || "general");
  const fields = [
    {
      key: "title",
      label: isList ? "List name" : "Project name",
      value: d.title,
      max: 160,
    },
    {
      key: "notes",
      label: "Description",
      type: "textarea",
      value: d.notes,
      required: false,
      max: 16384,
    },
    { ...categoryField(record), value: d.category || "general" },
    ...(!isList
      ? [
          {
            key: "assignee",
            label: "Project coordinator",
            options: assignmentOptions(),
            value: d.assignee || "",
            required: false,
          },
          {
            key: "due",
            label: "Project target date · optional",
            type: "date",
            value: d.due || "",
            required: false,
          },
          {
            key: "status",
            label: "Project state",
            options: [
              ["active", "Active"],
              ["on_hold", "On hold"],
              ["archived", "Finished"],
            ],
            value: d.status || "active",
          },
        ]
      : [
          {
            key: "project_id",
            label: "Group under a project · optional",
            options: [
              ["", "Standalone list"],
              ...records("project").map((p) => [p.id, p.data.title]),
            ],
            value: d.project_id || "",
            required: false,
          },
          {
            key: "items_are_tasks",
            label:
              "Items are work tasks · include in Tasks and project progress",
            type: "checkbox",
            value: !!d.items_are_tasks,
          },
        ]),
    ...(isList
      ? [
          {
            key: "template",
            label: "List type",
            options: Object.entries(listTemplates),
            value: d.items_are_tasks ? "tasks" : d.template || "general",
          },
          ...Object.entries(listFieldLabels).map(([key, label]) => ({
            key: "field_" + key,
            label,
            type: "checkbox",
            value: chosen.includes(key),
          })),
          {
            key: "perpetual",
            label: "Rolling list · keep using it",
            type: "checkbox",
            value: d.perpetual !== false,
          },
          {
            key: "notify_additions",
            label: "Notify when items are added",
            type: "checkbox",
            value: d.notify_additions !== false,
          },
          {
            key: "item_reminders",
            label: "Scheduled item reminders",
            type: "checkbox",
            value: d.item_reminders !== false,
          },
        ]
      : []),
    ...(record
      ? [
          {
            key: "action",
            label: "Action",
            options: [
              ["save", "Save changes"],
              ["delete", "Delete empty " + kind],
            ],
          },
          {
            key: "confirmation",
            label: "For deletion, type DELETE",
            required: false,
          },
        ]
      : []),
  ];
  await formDialog(
    record ? "Edit " + kind : "New " + kind,
    isList
      ? "Choose the fields you need. Field choices change what you see, not existing values or reminders."
      : "An outcome with its own tasks and context.",
    fields,
    async (v) => {
      if (v.action === "delete") {
        if (v.confirmation !== "DELETE")
          throw Error("Type DELETE to remove this " + kind + ".");
        await mutate(kind + ".delete", record.id, {}, record.revision);
        state.project = null;
        state.page = isList ? "lists" : "projects";
        render();
        return;
      }
      const data = {
        title: v.title,
        category: v.category,
        notes: v.notes,
        ...(isList
          ? {
              template: v.template === "tasks" ? "general" : v.template,
              project_id: v.project_id || "",
              items_are_tasks: !!v.items_are_tasks,
              fields: Object.keys(listFieldLabels).filter(
                (key) => v["field_" + key],
              ),
              perpetual: v.perpetual,
              notify_additions: v.notify_additions,
              item_reminders:
                v.template === "groceries"
                  ? (record?.data.item_reminders ?? false)
                  : v.item_reminders,
            }
          : { status: v.status, due: v.due || "", assignee: v.assignee || "" }),
      };
      await mutate(
        kind + (record ? ".update" : ".create"),
        record?.id || uuid(),
        data,
        record?.revision ?? null,
      );
      if (!record) {
        state.page =
          isList && data.project_id ? "tasks" : isList ? "lists" : "projects";
        state.project = isList ? data.project_id || null : null;
        state.projectGroup = "";
        render();
      }
    },
    record ? "Save changes" : "Create " + kind,
    (nodes) => {
      if (isList) {
        const group = el("details", "field-options"),
          summary = el("summary");
        group.append(summary);
        nodes.template.after(group);
        const labels = Object.keys(listFieldLabels).map((key) => [
          key,
          nodes["field_" + key].closest("label"),
        ]);
        for (const [, label] of labels) group.append(label);
        const update = () => {
          summary.textContent =
            "Customize fields · " +
            Object.keys(listFieldLabels).filter(
              (key) => nodes["field_" + key].checked,
            ).length +
            " selected";
        };
        const arrange = () => {
          nodes.item_reminders.closest("label").hidden =
            nodes.template.value === "groceries";
          const price = nodes.field_price.closest("label");
          if (nodes.template.value === "shopping") group.before(price);
          else group.append(price);
          group.open = nodes.template.value === "custom";
          update();
        };
        for (const key of Object.keys(listFieldLabels))
          nodes["field_" + key].onchange = update;
        nodes.template.onchange = () => {
          if (
            !record &&
            nodes.category.value === "general" &&
            nodes.template.value === "groceries"
          )
            nodes.category.value = "food";
          nodes.items_are_tasks.checked = nodes.template.value === "tasks";
          const selected = defaultListFields(nodes.template.value);
          for (const key of Object.keys(listFieldLabels))
            nodes["field_" + key].checked = selected.includes(key);
          arrange();
        };
        arrange();
      }
      if (record) {
        for (const key of ["action", "confirmation"])
          nodes[key].hidden = nodes[key].previousElementSibling.hidden = true;
        const remove = button(
          "Delete " + kind + "…",
          () => {
            const deleting = nodes.action.value !== "delete";
            nodes.action.value = deleting ? "delete" : "save";
            nodes.confirmation.hidden =
              nodes.confirmation.previousElementSibling.hidden = !deleting;
            $("#dialog-submit").textContent = deleting
              ? "Delete " + kind
              : "Save changes";
            remove.textContent = deleting
              ? "Keep " + kind
              : "Delete " + kind + "…";
            if (deleting) nodes.confirmation.focus();
          },
          "danger",
        );
        remove.disabled = state.snapshot.records.some(
          (t) => t.id !== record.id && t.data.project_id === record.id,
        );
        $("#dialog-body").append(remove);
      }
    },
  );
}

function renderCollections(root) {
  const kinds =
    state.page === "lists"
      ? ["list"]
      : state.page === "projects"
        ? ["project"]
        : ["list", "project"];
  state.libraryViews ||= {};
  const view = (state.libraryViews[state.page] ||= {
    query: "",
    template: "*",
    category: "*",
    sort: "title",
    status: "current",
  });
  const tools = el("div", "toolbar"),
    filters = el("div", "view-filters"),
    library = el("section", "collection-library");
  if (mayWrite())
    for (const kind of kinds)
      tools.append(button("New " + kind, () => newCollection(kind), "primary"));
  root.append(tools);
  const search = input("Search names and descriptions", view.query, "search");
  filters.append(addField(el("div"), search).parentElement);
  search.node.oninput = () => {
    view.query = search.node.value;
    draw();
  };
  const choice = (label, key, options) => {
    const n = select(label, options, view[key]),
      box = el("label");
    box.append(n.label, n.node);
    n.node.onchange = () => {
      view[key] = n.node.value;
      draw();
    };
    filters.append(box);
  };
  if (kinds.includes("list"))
    choice("List type", "template", [
      ["*", "All types"],
      ...Object.entries(listTemplates),
    ]);
  choice("Category", "category", [
    ["*", "All categories"],
    ...allCategoryChoices(),
  ]);
  if (kinds.includes("project"))
    choice("Project state", "status", [
      ["current", "Current projects"],
      ["active", "Active"],
      ["on_hold", "On hold"],
      ["archived", "Finished"],
      ["all", "All projects"],
    ]);
  choice("Sort by", "sort", [
    ["title", "Name A–Z"],
    ["updated", "Recently changed"],
  ]);
  root.append(filters, library);
  function draw() {
    return preserveViewport(drawItems);
  }
  function drawItems() {
    library.replaceChildren();
    const group = containers()
      .filter(
        (c) =>
          kinds.includes(c.kind) &&
          (c.kind !== "project" ||
            view.status === "all" ||
            (view.status === "current"
              ? (c.data.status || "active") !== "archived"
              : (c.data.status || "active") === view.status)) &&
          (view.template === "*" ||
            c.kind !== "list" ||
            (c.data.items_are_tasks
              ? "tasks"
              : c.data.template || "general") === view.template) &&
          (view.category === "*" ||
            categoryKey(c.data.category) === categoryKey(view.category)) &&
          (c.data.title + " " + c.data.notes)
            .toLocaleLowerCase()
            .includes(view.query.toLocaleLowerCase()),
      )
      .sort((a, b) =>
        view.sort === "updated"
          ? (b.kind === "project"
              ? FieldProjects.graph().rollup(b, FieldPlanning.localDay())
                  .activity
              : b.data.last_activity_at || b.data.updated_at || 0) -
            (a.kind === "project"
              ? FieldProjects.graph().rollup(a, FieldPlanning.localDay())
                  .activity
              : a.data.last_activity_at || a.data.updated_at || 0)
          : a.data.title.localeCompare(b.data.title),
      );
    if (!group.length)
      library.append(
        el(
          "p",
          "empty",
          "No collections match. Change a filter or create a list/project.",
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
      row.setAttribute("aria-label", "Open " + c.kind + ": " + c.data.title);
      row.append(
        typeBadges(c),
        el("strong", "", c.data.title),
        el(
          "span",
          "item-sub",
          records("task").filter(
            (t) => t.data.project_id === c.id && t.data.status !== "done",
          ).length + (c.kind === "list" ? " unchecked items" : " open tasks"),
        ),
        el("span", "muted", c.data.notes || ""),
      );
      if (c.kind === "project") {
        const stats = FieldProjects.graph().rollup(c, FieldPlanning.localDay());
        row.append(
          el(
            "span",
            "project-library-progress",
            `${stats.done}/${stats.total} tasks · ${FieldProjects.stateLabel(c, stats)} · ${stats.overdue} overdue`,
          ),
        );
      } else if (c.data.project_id)
        row.append(
          el(
            "span",
            "item-sub",
            "In " + (byId(c.data.project_id)?.data.title || "Project"),
          ),
        );
      library.append(row);
    }
  }
  draw();
}
function safeShoppingLink(value) {
  try {
    const url = new URL(value);
    return ["http:", "https:"].includes(url.protocol) &&
      !url.username &&
      !url.password
      ? url.href
      : null;
  } catch {
    return null;
  }
}
function renderChecklist(root, list) {
  state.listViews ||= {};
  const view = (state.listViews[list.id] ||= {
    query: "",
    store: "",
    sort: "title",
    status: "all",
    itemCategory: "all",
  });
  const fields = fieldsOf(list),
    all = records("task").filter((t) => t.data.project_id === list.id);
  if (!fields.includes("store")) view.store = "";
  view.itemCategory ??= "all";
  if (
    !["title", "added", "updated", "item_category", ...fields].includes(
      view.sort,
    )
  )
    view.sort = "title";
  const stores = [...new Set(all.map((t) => (t.data.store || "").trim()))].sort(
    (a, b) => a.localeCompare(b),
  );
  const toolbar = el("div", "toolbar"),
    filters = el("div", "view-filters"),
    results = el("section", "checklist-items");
  const search = input("Search this list", view.query, "search");
  search.node.dataset.liveRefresh = "list-search";
  search.node.placeholder = "Search titles, notes, stores and links";
  filters.append(addField(el("div"), search).parentElement);
  const choose = (label, options, value, changed) => {
    const box = el("label"),
      node = select(label, options, value);
    box.append(node.label, node.node);
    node.node.onchange = () => {
      changed(node.node.value);
      draw();
    };
    filters.append(box);
    return node;
  };
  search.node.oninput = () => {
    view.query = search.node.value;
    draw();
  };
  if (fields.includes("store"))
    choose(
      "Store",
      [
        ["*", "All stores"],
        ...stores.map((store) => [store || "__none", store || "No store yet"]),
      ],
      view.store || "*",
      (value) => (view.store = value === "*" ? "" : value),
    );
  choose(
    "Show",
    [
      ["all", "All items"],
      ["open", "Unchecked"],
      ["done", "Checked"],
    ],
    view.status,
    (value) => (view.status = value),
  );
  const sorts = [
    ["title", "Name A–Z"],
    ["added", "Recently added"],
    ["updated", "Recently changed"],
    ["item_category", "Item category"],
    ...fields
      .filter((f) => !["notes", "images", "link_url", "store"].includes(f))
      .map((f) => [f, listFieldLabels[f]]),
  ];
  choose("Sort by", sorts, view.sort, (value) => (view.sort = value));
  choose(
    "Item category",
    itemCategoryFilters(all, view.itemCategory),
    view.itemCategory,
    (v) => (view.itemCategory = v),
  );
  const reset = button("Clear filters", () => {
    view.query = "";
    view.store = "";
    view.status = "all";
    view.itemCategory = "all";
    render();
  });
  filters.append(reset);
  const clear = button("Delete checked", () => changeChecked(false), "danger");
  const reuse = button("Reuse checked", () => changeChecked(true));
  clear.disabled = true;
  if (mayWrite())
    toolbar.append(
      button(
        "Add item",
        () =>
          editListItem(list, null, view.store === "__none" ? "" : view.store),
        "primary",
      ),
      button("List settings", () => editCollection(list)),
      clear,
      ...(list.data.perpetual !== false ? [reuse] : []),
    );
  toolbar.append(
    button("List history", () => {
      state.historyTarget = list.id;
      state.historyTitle = list.data.title;
      state.page = "history";
      state.selected = null;
      render();
    }),
  );
  const project = FieldProjects.graph().project(list),
    finished =
      !!list.data.items_are_tasks && project?.data.status === "archived";
  if (finished) {
    for (const b of toolbar.querySelectorAll("button"))
      if (
        b.textContent === "Add item" ||
        b.textContent.startsWith("Reuse checked")
      )
        b.disabled = true;
    root.append(
      el(
        "p",
        "",
        "Project finished. Reopen it before adding or reopening work.",
      ),
    );
  }
  root.append(typeBadges(list), toolbar, filters);
  if (
    list.data.notify_additions === false ||
    (list.data.template !== "groceries" && list.data.item_reminders === false)
  )
    root.append(
      el(
        "p",
        "muted",
        [
          list.data.notify_additions === false
            ? "Item-addition alerts muted"
            : "",
          list.data.template !== "groceries" &&
          list.data.item_reminders === false
            ? "Scheduled item reminders muted"
            : "",
        ]
          .filter(Boolean)
          .join(" · "),
      ),
    );
  root.append(results);
  let visible = [],
    matched = [];
  const matching = (item) => {
    const d = item.data;
    return (
      itemCategoryMatches(d.item_category, view.itemCategory) &&
      (!view.store ||
        (d.store || "").trim() ===
          (view.store === "__none" ? "" : view.store)) &&
      (!view.query ||
        [
          d.title,
          d.notes,
          d.store,
          d.quantity,
          d.link_url,
          d.price,
          d.item_category,
        ]
          .filter(Boolean)
          .join(" ")
          .toLocaleLowerCase()
          .includes(view.query.toLocaleLowerCase()))
    );
  };
  const compare = (a, b) => {
    const d = a.data,
      e = b.data;
    let order = 0;
    if (view.sort === "added" || view.sort === "updated")
      order =
        (e[view.sort === "added" ? "created_at" : "last_activity_at"] ||
          e.updated_at ||
          0) -
        (d[view.sort === "added" ? "created_at" : "last_activity_at"] ||
          d.updated_at ||
          0);
    else if (view.sort === "price")
      order =
        (d.currency || "USD").localeCompare(e.currency || "USD") ||
        Number(d.price || Infinity) - Number(e.price || Infinity);
    else if (["priority", "estimate_minutes"].includes(view.sort))
      order = Number(d[view.sort] || 0) - Number(e[view.sort] || 0);
    else
      order = String(d[view.sort] || "~").localeCompare(
        String(e[view.sort] || "~"),
        undefined,
        { numeric: true, sensitivity: "base" },
      );
    return order || d.title.localeCompare(e.title) || a.id.localeCompare(b.id);
  };
  function draw() {
    return preserveViewport(drawItems);
  }
  function drawItems() {
    reset.disabled =
      !view.query &&
      !view.store &&
      view.status === "all" &&
      view.itemCategory === "all";
    results.replaceChildren();
    matched = all.filter(matching);
    visible = matched
      .filter(
        (item) =>
          view.status === "all" ||
          (item.data.status === "done") === (view.status === "done"),
      )
      .sort((a, b) =>
        fields.includes("store") && !view.store
          ? String(a.data.store || "~").localeCompare(
              String(b.data.store || "~"),
            ) || compare(a, b)
          : compare(a, b),
      );
    const checked = matched.filter((t) => t.data.status === "done");
    clear.textContent = `Delete checked (${checked.length})`;
    clear.disabled = !checked.length;
    reuse.textContent = `Reuse checked (${checked.length})`;
    reuse.disabled = finished || !checked.length;
    clear.title =
      "Delete checked items matching this search, store and item category, including hidden checked rows.";
    results.append(
      el(
        "p",
        "muted",
        `${visible.length} of ${all.length} items · ${visible.filter((t) => t.data.status !== "done").length} unchecked`,
      ),
    );
    for (const done of [false, true]) {
      const rows = visible.filter((t) => (t.data.status === "done") === done);
      if (!rows.length) continue;
      const section = done
        ? el("details", "checked-items")
        : el("div", "open-items");
      if (done) {
        section.append(el("summary", "", rows.length + " checked items"));
        section.open = view.status === "done";
      }
      let lastStore = null;
      for (const item of rows) {
        const d = item.data,
          store = (d.store || "").trim();
        if (fields.includes("store") && !view.store && lastStore !== store) {
          const heading = el("div", "store-heading");
          heading.append(
            el("h3", "", store || "No store yet"),
            el(
              "span",
              "count",
              String(
                rows.filter((r) => (r.data.store || "").trim() === store)
                  .length,
              ),
            ),
          );
          section.append(heading);
          lastStore = store;
        }
        const row = el("article", "checklist-item"),
          check = el("input");
        check.type = "checkbox";
        check.checked = done;
        check.setAttribute(
          "aria-label",
          (done ? "Uncheck " : "Check ") + d.title,
        );
        check.disabled =
          !mayWrite() ||
          (finished && done) ||
          (!!d.claimant && d.claimant !== state.actor.user_id);
        check.onchange = () =>
          mutate(
            done ? "task.reopen" : "task.complete",
            item.id,
            {},
            item.revision,
          ).catch((e) => notice(e.message));
        const detail = button(
          "",
          () => editListItem(list, item),
          "checklist-open",
        );
        detail.setAttribute("aria-label", "Open item: " + d.title);
        detail.append(typeBadges(item), el("strong", "", d.title));
        const attrs = [];
        if (fields.includes("quantity") && d.quantity) attrs.push(d.quantity);
        if (fields.includes("store") && store) attrs.push(store);
        if (fields.includes("price") && d.price)
          attrs.push((d.currency || "USD") + " " + d.price);
        if (fields.includes("link_url") && safeShoppingLink(d.link_url))
          attrs.push(new URL(d.link_url).hostname);
        if (attrs.length)
          detail.append(el("span", "list-attributes", attrs.join(" · ")));
        if (fields.includes("notes") && d.notes)
          detail.append(el("span", "muted", d.notes));
        detail.append(historyNode(item));
        const actions = el("div", "checklist-actions");
        if (mayWrite()) {
          const remove = button(
            "Delete",
            () => deleteListItem(list, item),
            "danger",
          );
          remove.setAttribute("aria-label", "Delete item: " + d.title);
          actions.append(remove);
        }
        row.append(check, detail);
        if (fields.includes("images")) {
          const image = records("image").find(
            (i) => i.data.task_id === item.id,
          );
          if (image) {
            const picture = el("img", "list-thumbnail");
            picture.alt = "Attached preview for " + d.title;
            picture.loading = "lazy";
            picture.src = "/api/images/" + encodeURIComponent(image.id);
            actions.append(picture);
          }
        }
        if (actions.childNodes.length) row.append(actions);
        section.append(row);
      }
      results.append(section);
    }
    if (!visible.length)
      results.append(
        el(
          "p",
          "empty",
          !all.length
            ? "Your list is ready. Add an item to get started."
            : !matched.length
              ? "No items match this search or store. Use Clear filters to return to the list."
              : view.status === "open"
                ? "Everything in this view is checked. Show checked items or use Delete checked to clean up."
                : "No checked items in this view. Choose Unchecked to continue.",
        ),
      );
  }
  async function changeChecked(reusing) {
    const checked = matched.filter((t) => t.data.status === "done");
    if (!checked.length) return;
    const basis = {
      epoch: state.snapshot.epoch,
      generation: state.snapshot.generation,
    };
    const preview =
      checked
        .slice(0, 5)
        .map((item) => item.data.title)
        .join(", ") +
      (checked.length > 5 ? ` and ${checked.length - 5} more` : "");
    await confirmDialog(
      (reusing ? "Reuse " : "Delete ") +
        checked.length +
        ` checked item${checked.length === 1 ? "" : "s"}?`,
      (reusing
        ? "Uncheck the completed items matching this view to use the list again. Their notes, quantities, stores, images and history are kept. Changed items stay for review."
        : "Delete the checked items matching this search, store and item category, including hidden checked rows. Unchecked or changed items stay in place. Saved versions remain available in History while retained.") +
        " Items: " +
        preview,
      reusing ? "Uncheck and reuse" : "Delete checked",
    );
    let cleared = 0;
    const failures = [];
    state.loading = true;
    try {
      for (const item of checked) {
        try {
          await sendCommand({
            operation_id: uuid(),
            ...basis,
            action: reusing ? "task.reopen" : "task.delete",
            target: item.id,
            expected_revision: item.revision,
            data: {},
          });
          cleared++;
        } catch (e) {
          failures.push(e.message);
          if (!state.actor) break;
        }
      }
      if (state.actor) {
        await refresh();
        render();
      }
      notice(
        cleared +
          (reusing ? " unchecked for reuse" : " deleted") +
          (failures.length
            ? " · " +
              failures.length +
              ` item${failures.length === 1 ? " needs" : "s need"} review. Refresh before trying again.`
            : ""),
      );
    } finally {
      state.loading = false;
    }
  }
  draw();
}
async function deleteListItem(list, item) {
  const basis = {
    epoch: state.snapshot.epoch,
    generation: state.snapshot.generation,
  };
  await confirmDialog(
    "Delete " + item.data.title + "?",
    "Removes this item, its comments and attached images from active content. Other items stay as they are. Saved versions can be restored from History while retained.",
    "Delete item",
  );
  await mutate("task.delete", item.id, {}, item.revision, basis);
  notice(
    item.data.title +
      " deleted. Retained versions are available in List history.",
  );
}
async function editListItem(list, record, initialStore = "") {
  const d = record?.data || {
      store: initialStore,
      category: list.data.category || "general",
    },
    schema = fieldsOf(list),
    context = itemContext(d, list),
    fields = [
      { key: "title", label: "Item name", value: d.title, max: 240 },
      ...(!context.scoped
        ? [{ ...categoryField({ data: d }), value: d.category || "general" }]
        : []),
    ];
  if (record)
    fields.push({
      key: "project_id",
      label: "Project or list",
      required: false,
      value: d.project_id || "",
      options: [
        ["", "No project or list (Inbox)"],
        ...containers().map((c) => [c.id, FieldProjects.graph().path(c.id)]),
      ],
    });
  for (const key of schema) {
    if (key === "images") continue;
    if (key === "assignee") {
      fields.push({
        key,
        label: "Assigned to",
        options: assignmentOptions(),
        value: d.assignee || "",
        required: false,
      });
      continue;
    }
    if (key === "priority") {
      fields.push({
        key,
        label: "Priority",
        options: [
          ["1", "Urgent"],
          ["2", "High"],
          ["3", "Normal"],
          ["4", "Low"],
        ],
        value: String(d.priority || 3),
      });
      continue;
    }
    fields.push({
      key,
      label: listFieldLabels[key],
      value: d[key] ?? "",
      type:
        key === "notes"
          ? "textarea"
          : ["due", "planned_for"].includes(key)
            ? "date"
            : key === "price"
              ? "text"
              : key === "estimate_minutes"
                ? "number"
                : "text",
      required: false,
      max: key === "notes" ? 16384 : key === "link_url" ? 2048 : 80,
    });
    if (key === "price")
      fields.push({
        key: "currency",
        label: "Currency",
        value: d.currency || "USD",
        max: 3,
      });
  }
  let reminders = FieldReminders.rulesFor(record) || {
      alerts: d.due
        ? [{ id: record.id, minutes_before: 0, ...FieldReminders.profile }]
        : [],
      repeat: null,
    },
    reminderDirty = false;
  let reminderOptions = FieldReminders.optionsFor(record, "task");
  let classification;
  await formDialog(
    record ? (mayWrite() ? "Edit item" : "View item") : "New item",
    list.data.title,
    fields,
    async (v) => {
      const data = { ...v, item_category: classification.value };
      for (const key of ["priority", "estimate_minutes"])
        if (key in data) data[key] = Number(data[key] || 0);
      if (data.currency) data.currency = data.currency.toUpperCase();
      if (reminderDirty) {
        FieldReminders.validate(reminders);
        data.reminders = reminders;
      }
      FieldReminders.validateOptions(reminderOptions);
      data.reminder_options = reminderOptions;
      await mutate(
        record ? "task.update" : "task.create",
        record?.id || uuid(),
        { ...data, ...(!record ? { project_id: list.id } : {}) },
        record?.revision ?? null,
      );
    },
    record ? "Save item" : "Add item",
    (nodes) => {
      const body = $("#dialog-body");
      classification = itemCategoryEditor(
        body,
        () => context,
        d.item_category || "",
        () => {},
        mayWrite(),
      );
      nodes.title.after(classification.root);
      if (record && mayWrite())
        body.append(
          button(
            "Delete item…",
            () => {
              state.dismissDialog?.();
              return deleteListItem(list, record);
            },
            "danger",
          ),
        );
      if (mayWrite() && list.data.template !== "groceries")
        FieldReminders.controls(
          body,
          reminders,
          () => d.due || d.planned_for || FieldPlanning.localDay(),
          (v) => {
            reminders = v;
            reminderDirty = true;
          },
          {
            value: reminderOptions,
            kind: "task",
            changed: (v) => {
              reminderOptions = v;
            },
          },
        );
      if (safeShoppingLink(d.link_url))
        body.append(
          button("Open link in browser", () =>
            window.open(
              safeShoppingLink(d.link_url),
              "_blank",
              "noopener,noreferrer",
            ),
          ),
        );
      if (schema.includes("images")) {
        if (!record)
          body.append(
            el(
              "p",
              "muted",
              "Create this item to attach an image. Previews use only your attached images.",
            ),
          );
        else {
          for (const image of records("image").filter(
            (i) => i.data.task_id === record.id,
          )) {
            const img = el("img", "item-attachment");
            img.alt = image.data.name;
            img.src = "/api/images/" + encodeURIComponent(image.id);
            body.append(img);
          }
          if (mayWrite()) {
            const file = addField(
              body,
              input("Attach image (PNG, JPEG, WebP · up to 4 MiB)", "", "file"),
            );
            file.accept = "image/png,image/jpeg,image/webp";
            file.onchange = async () => {
              const selected = file.files[0];
              if (!selected) return;
              if (selected.size > 4 * 1024 * 1024) {
                notice("Choose an image up to 4 MiB.");
                return;
              }
              try {
                const bytes = await new Promise((resolve, reject) => {
                  const reader = new FileReader();
                  reader.onload = () => resolve(reader.result.split(",")[1]);
                  reader.onerror = reject;
                  reader.readAsDataURL(selected);
                });
                await mutate(
                  "image.create",
                  uuid(),
                  {
                    task_id: record.id,
                    parent_incarnation: record.data.recovery_incarnation || 0,
                    name: selected.name,
                    bytes,
                  },
                  null,
                );
                notice(
                  "Image attached. Location metadata removed. Reopen the item to view it.",
                );
              } catch (e) {
                notice(e.message);
              }
            };
          }
        }
      }
      if (record) {
        body.append(historyNode(record));
        const comments = records("comment").filter(
          (c) => c.data.task_id === record.id,
        );
        for (const c of comments)
          body.append(
            el("p", "item-comment", c.data.author + " · " + c.data.body),
          );
        if (mayWrite()) {
          const text = addField(body, input("Add a comment", "", "textarea"));
          body.append(
            button("Post comment", async () => {
              if (!text.value.trim()) return;
              await mutate(
                "comment.create",
                uuid(),
                {
                  task_id: record.id,
                  parent_incarnation: record.data.recovery_incarnation || 0,
                  body: text.value,
                },
                null,
              );
              text.value = "";
              notice("Comment posted.");
            }),
          );
        }
      }
    },
    !mayWrite(),
  );
}
