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
const categoryChoices = (value) =>
  Object.entries(recordCategories).map(([key, label]) => [
    key === "home" && value === "honey_do" ? value : key,
    label,
  ]);
function categoryField(record) {
  return {
    key: "category",
    label: "Category",
    options: categoryChoices(record?.data.category),
    value: record?.data.category || "general",
  };
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
  const category =
    record.data.category || listForTask(record)?.data.category || "general";
  if (category !== "general")
    group.append(
      el(
        "span",
        "type-badge category-" + category,
        recordCategories[categoryKey(category)] || "General",
      ),
    );
  return group;
}
