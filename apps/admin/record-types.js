// Explicit household labels; no remote icons, fonts, or metadata requests.
const recordCategories = {
  general: "General",
  action: "Action",
  food: "Food & cooking",
  honey_do: "Honey-do",
  home: "Home & maintenance",
  errands: "Errands",
  work: "Work",
  personal: "Personal",
};
function categoryField(record) {
  return {
    key: "category",
    label: "Category",
    options: Object.entries(recordCategories),
    value: record?.data.category || "general",
  };
}
function listForTask(record) {
  return records("list").find((list) => list.id === record.data.project_id);
}
function isPlannerTask(record) {
  return record.kind === "task" && !listForTask(record);
}
function recordTypeLabel(record) {
  if (record.kind === "list")
    return listTemplates[record.data.template || "general"] || "List";
  if (record.kind === "task") {
    const list = listForTask(record);
    return list
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
  const category =
    record.data.category || listForTask(record)?.data.category || "general";
  if (category !== "general")
    group.append(
      el(
        "span",
        "type-badge category-" + category,
        recordCategories[category] || "General",
      ),
    );
  return group;
}
