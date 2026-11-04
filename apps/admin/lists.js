// List schemas, field choices and rolling checklists. No retailer requests.
const listFieldLabels = {
  quantity: "Quantity",
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
  groceries: "Groceries",
  shopping: "Online shopping",
  custom: "Custom list",
};
const defaultListFields = (type) =>
  type === "groceries"
    ? ["quantity", "notes"]
    : type === "shopping"
      ? ["quantity", "link_url", "images"]
      : type === "custom"
        ? []
        : ["notes"];
const fieldsOf = (list) =>
  list.data.fields || defaultListFields(list.data.template || "general");
async function newCollection(kind = "list") {
  return collectionEditor(kind);
}
async function editCollection(record) {
  return collectionEditor(record.kind, record);
}
async function collectionEditor(kind, record) {
  const d = record?.data || {},
    isList = kind === "list",
    chosen = d.fields || defaultListFields(d.template || "general");
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
    ...(isList
      ? [
          {
            key: "template",
            label: "List type",
            options: Object.entries(listTemplates),
            value: d.template || "general",
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
        notes: v.notes,
        ...(isList
          ? {
              template: v.template,
              fields: Object.keys(listFieldLabels).filter(
                (key) => v["field_" + key],
              ),
              perpetual: v.perpetual,
            }
          : {}),
      };
      await mutate(
        kind + (record ? ".update" : ".create"),
        record?.id || uuid(),
        data,
        record?.revision ?? null,
      );
      if (!record) {
        state.page = isList ? "lists" : "projects";
        state.project = null;
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
          const price = nodes.field_price.closest("label");
          if (nodes.template.value === "shopping") group.before(price);
          else group.append(price);
          group.open = nodes.template.value === "custom";
          update();
        };
        for (const key of Object.keys(listFieldLabels))
          nodes["field_" + key].onchange = update;
        nodes.template.onchange = () => {
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
        remove.disabled = records("task").some(
          (t) => t.data.project_id === record.id,
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
  const tools = el("div", "toolbar");
  if (mayWrite())
    for (const kind of kinds)
      tools.append(button("New " + kind, () => newCollection(kind), "primary"));
  root.append(tools);
  for (const kind of kinds) {
    const library = el("section", "collection-library");
    const group = containers().filter((c) => c.kind === kind);
    if (!group.length)
      library.append(
        el(
          "p",
          "empty",
          kind === "list"
            ? "Build a grocery list, an online shopping list, or a list with your own fields."
            : "Keep an outcome and its related tasks together.",
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
        el(
          "span",
          "eyebrow",
          kind === "list"
            ? listTemplates[c.data.template || "general"] || "List"
            : "Project",
        ),
        el("strong", "", c.data.title),
        el(
          "span",
          "item-sub",
          records("task").filter(
            (t) => t.data.project_id === c.id && t.data.status !== "done",
          ).length + (kind === "list" ? " unchecked items" : " open tasks"),
        ),
        el("span", "muted", c.data.notes || ""),
      );
      library.append(row);
    }
    root.append(library);
  }
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
  const fields = fieldsOf(list),
    items = records("task").filter((t) => t.data.project_id === list.id),
    checked = items.filter((t) => t.data.status === "done");
  const toolbar = el("div", "toolbar");
  if (mayWrite())
    toolbar.append(
      button("Add item", () => editListItem(list), "primary"),
      button("List settings", () => editCollection(list)),
    );
  if (checked.length && mayWrite())
    toolbar.append(
      button("Clear checked (" + checked.length + ")", async () => {
        await confirmDialog(
          "Clear " + checked.length + " checked items?",
          "Only these checked items and their attachments will be removed. Changed items remain for review.",
          "Clear checked",
        );
        const basis = {
          epoch: state.snapshot.epoch,
          generation: state.snapshot.generation,
        };
        let cleared = 0;
        const failures = [];
        state.loading = true;
        try {
          for (const item of checked) {
            try {
              await api("/commands", {
                operation_id: uuid(),
                ...basis,
                action: "task.delete",
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
              " cleared" +
              (failures.length
                ? " · " + failures.length + " changed items kept for review."
                : ""),
          );
        } finally {
          state.loading = false;
        }
      }),
    );
  root.append(toolbar);
  const open = el("div", "checklist-items"),
    done = el("details", "checked-items");
  done.open = true;
  done.append(el("summary", "", checked.length + " checked items"));
  for (const item of items.sort(
    (a, b) =>
      Number(a.data.status === "done") - Number(b.data.status === "done"),
  )) {
    const d = item.data,
      row = el("article", "checklist-item"),
      check = el("input");
    check.type = "checkbox";
    check.checked = d.status === "done";
    check.setAttribute("aria-label", "Check " + d.title);
    check.disabled =
      !mayWrite() || (!!d.claimant && d.claimant !== state.actor.user_id);
    check.onchange = () =>
      mutate(
        check.checked ? "task.complete" : "task.reopen",
        item.id,
        {},
        item.revision,
      ).catch((e) => notice(e.message));
    const detail = button("", () => editListItem(list, item), "checklist-open");
    detail.append(el("strong", "", d.title));
    const attrs = [];
    if (fields.includes("quantity") && d.quantity) attrs.push(d.quantity);
    if (fields.includes("price") && d.price)
      attrs.push((d.currency || "USD") + " " + d.price);
    if (fields.includes("link_url") && safeShoppingLink(d.link_url))
      attrs.push(new URL(d.link_url).hostname);
    if (attrs.length)
      detail.append(el("span", "list-attributes", attrs.join(" · ")));
    if (fields.includes("notes") && d.notes)
      detail.append(el("span", "muted", d.notes));
    detail.append(historyNode(item));
    row.append(check, detail);
    if (fields.includes("images")) {
      const image = records("image").find((i) => i.data.task_id === item.id);
      if (image) {
        const picture = el("img", "list-thumbnail");
        picture.alt = "Attached preview for " + d.title;
        picture.loading = "lazy";
        picture.src = "/api/images/" + encodeURIComponent(image.id);
        row.append(picture);
      }
    }
    (d.status === "done" ? done : open).append(row);
  }
  if (!items.length)
    open.append(
      el("p", "empty", "Your list is ready. Add an item to get started."),
    );
  root.append(open);
  if (checked.length) root.append(done);
}
async function editListItem(list, record) {
  const d = record?.data || {},
    schema = fieldsOf(list),
    fields = [{ key: "title", label: "Item name", value: d.title, max: 240 }];
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
  let reminders = FieldReminders.rulesFor(record) || { alerts: d.due?[{id:record.id,minutes_before:0,...FieldReminders.profile}]:[], repeat: null },
    reminderDirty = false;
  let reminderOptions=FieldReminders.optionsFor(record,"task");
  await formDialog(
    record ? (mayWrite() ? "Edit item" : "View item") : "New item",
    list.data.title,
    fields,
    async (v) => {
      const data = { ...v };
      for (const key of ["priority", "estimate_minutes"])
        if (key in data) data[key] = Number(data[key] || 0);
      if (data.currency) data.currency = data.currency.toUpperCase();
      if (reminderDirty) {
        FieldReminders.validate(reminders);
        data.reminders = reminders;
      }
      FieldReminders.validateOptions(reminderOptions);data.reminder_options=reminderOptions;
      await mutate(
        record ? "task.update" : "task.create",
        record?.id || uuid(),
        { ...data, ...(!record ? { project_id: list.id } : {}) },
        record?.revision ?? null,
      );
    },
    record ? "Save item" : "Add item",
    () => {
      const body = $("#dialog-body");
      if (mayWrite())
        FieldReminders.controls(
          body,
          reminders,
          () => d.due || d.planned_for || FieldPlanning.localDay(),
          (v) => {
            reminders = v;
            reminderDirty = true;
          },
          {value:reminderOptions,kind:"task",changed:v=>{reminderOptions=v;}},
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
                  { task_id: record.id, name: selected.name, bytes },
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
                { task_id: record.id, body: text.value },
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
