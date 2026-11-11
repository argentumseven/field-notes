// Explicit household labels; no remote icons, fonts, or metadata requests.
const recordCategories = {
  general: "General",
  action: "Action",
  food: "Food & cooking",
  home: "Household",
  errands: "Errands",
  work: "Work",
  personal: "Personal",
};
const categoryKey = (value) =>
  value === "honey_do" ? "home" : value || "general";
const customCategoryChoices = (value) => {
  const choices = (state.snapshot?.categories || [])
    .filter((c) => !c.archived || "custom:" + c.id === value)
    .map((c) => ["custom:" + c.id, c.name + (c.archived ? " · archived" : "")]);
  if (value?.startsWith("custom:") && !choices.some(([key]) => key === value))
    choices.push([value, "Unavailable custom category · retained"]);
  return choices;
};
const categoryChoices = (value) => [
  ...Object.entries(recordCategories).map(([key, label]) => [
    key === "home" && value === "honey_do" ? value : key,
    label,
  ]),
  ...customCategoryChoices(value),
];
const allCategoryChoices = () => [
  ...Object.entries(recordCategories),
  ...customCategoryChoices(""),
];
const categoryFilterChoices = () => [
  ...Object.entries(recordCategories),
  ...(state.snapshot?.categories || []).map((c)=>[
    "custom:"+c.id,c.name+(c.archived?" · archived":""),
  ]),
];
function categoryField(record) {
  return {
    key: "category",
    label: "Category",
    options: categoryChoices(record?.data.category),
    value: record?.data.category || "general",
  };
}
function itemContext(data, parent) {
  const key =
    parent?.kind === "list"
      ? parent.data.template === "groceries"
        ? "groceries"
        : parent.data.template === "shopping"
          ? "shopping"
          : !parent.data.template || parent.data.template === "general"
            ? categoryKey(parent.data.category)
            : "general"
      : categoryKey(data.category ?? parent?.data.category);
  const profile = itemContextProfiles[key] || itemContextProfiles.general;
  return {
    ...profile,
    key: itemContextProfiles[key] ? key : "general",
    scoped: profile.choices.length > 0,
  };
}
function itemCategoryField(context, value = "") {
  return context.scoped
    ? {
        key: "item_category",
        label: context.label,
        value,
        options: [
          ["", "Uncategorized"],
          ...context.choices.map((v) => [v, v]),
          ...(value && !context.choices.includes(value)
            ? [[value, value + " · saved label"]]
            : []),
        ],
        required: false,
      }
    : {
        key: "item_category",
        label: "Item category · optional",
        value,
        max: 80,
        required: false,
      };
}
function itemContextHint(context, value = "") {
  return context.scoped
    ? value && !context.choices.includes(value)
      ? "Saved label kept. Choose a category for this context only if you want to reclassify it."
      : context.label +
        " choices follow the list’s purpose or task area. Uncategorized is allowed."
    : "Use any item category, or leave it uncategorized. General and custom lists have no preset restrictions.";
}
function itemCategoryMatches(value, filter) {
  return (
    filter === "all" ||
    (filter === "none" ? !value : filter === "value:" + (value || ""))
  );
}
function itemCategoryFilters(items, selected = "all") {
  const values = items.map((t) => t.data.item_category || "").filter(Boolean);
  if (selected.startsWith("value:")) values.push(selected.slice(6));
  return [
    ["all", "All item categories"],
    ["none", "Uncategorized"],
    ...[...new Set(values)]
      .sort((a, b) => a.localeCompare(b))
      .map((v) => ["value:" + v, v]),
  ];
}
function itemCategoryEditor(
  host,
  readContext,
  value = "",
  changed = () => {},
  enabled = true,
) {
  const root = el("div", "item-category-editor");
  host.append(root);
  const editor = {
    root,
    value,
    refresh() {
      const context = readContext(),
        field = itemCategoryField(context, editor.value);
      const control = context.scoped
        ? select(field.label, field.options, editor.value)
        : input(field.label, editor.value);
      control.node.disabled = !enabled;
      if (!context.scoped) {
        control.node.maxLength = 80;
        control.node.autocomplete = "off";
      }
      control.node.addEventListener("input", () => {
        editor.value = control.node.value;
        changed(editor.value);
      });
      root.replaceChildren(
        control.label,
        control.node,
        el("p", "item-sub", itemContextHint(context, editor.value)),
      );
    },
  };
  editor.refresh();
  return editor;
}
function listForTask(record) {
  return records("list").find((list) => list.id === record.data.project_id);
}
function isPlannerTask(record) {
  return (
    record.kind === "task" &&
    (!listForTask(record) || listForTask(record).data.items_are_tasks === true)
  );
}
function canScheduleReminders(record) {
  return listForTask(record)?.data.template !== "groceries";
}
function recordTypeLabel(record) {
  if (record.kind === "note" && record.data.note_type === "recipe")
    return "Recipe";
  if (record.kind === "list" && record.data.items_are_tasks) return "Task list";
  if (record.kind === "list")
    return listTemplates[record.data.template || "general"] || "List";
  if (record.kind === "task") {
    const list = listForTask(record);
    return list && !list.data.items_are_tasks
      ? list.data.template === "groceries"
        ? "Grocery item"
        : list.data.template === "shopping"
          ? "Shopping item"
          : "List item"
      : "Task";
  }
  return record.kind === "event"
    ? "Calendar event"
    : record.kind === "note" && record.data.on_radar
      ? "Later & reminders"
      : record.kind === "note"
        ? "Note"
        : "Project";
}
function typeBadges(record) {
  const group = el("span", "type-badges");
  group.append(el("span", "type-badge", recordTypeLabel(record)));
  const parent = record.kind === "task" ? byId(record.data.project_id) : null;
  const scoped =
    record.kind === "task" &&
    parent?.kind === "list" &&
    itemContext(record.data, parent).scoped;
  const category =
    record.data.category || listForTask(record)?.data.category || "general";
  if (!scoped && category !== "general")
    group.append(
      el(
        "span",
        "type-badge category-" + category,
        categoryChoices(category).find(([key]) => key === category)?.[1] || "Saved category",
      ),
    );
  if (record.kind === "task" && record.data.item_category)
    group.append(
      el("span", "type-badge item-category", record.data.item_category),
    );
  return group;
}
