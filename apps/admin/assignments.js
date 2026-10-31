// Responsibility is independent of the exclusive active-work claim.
function assignmentOptions(current = "") {
  const options = [
    ["", "Unassigned"],
    ...(state.people || [])
      .filter((p) => p.can_assign)
      .map((p) => [p.id, p.name]),
  ];
  if (current && !options.some(([id]) => id === current))
    options.push([current, userName(current) + " · unavailable"]);
  return options;
}
function renderAssignments(main) {
  const tools = el("div", "toolbar"),
    search = el("input");
  search.type = "search";
  search.placeholder = "Find assigned work";
  search.setAttribute("aria-label", "Find assigned work");
  search.value = state.query;
  tools.append(search);
  if (mayWrite())
    tools.append(button("New task", newStandaloneTask, "primary"));
  main.append(
    tools,
    el(
      "p",
      "muted",
      "Assigned means responsible. Working means an accepted exclusive claim. Everyone in this workspace shares access.",
    ),
  );
  const groups = el("div", "assignment-groups");
  main.append(groups);
  search.oninput = () => {
    state.query = search.value;
    draw();
  };
  function draw() {
    groups.replaceChildren();
    const tasks = visibleTasks()
      .filter((t) => t.data.status !== "done")
      .sort(
        (a, b) =>
          (a.data.priority || 3) - (b.data.priority || 3) ||
          a.id.localeCompare(b.id),
      );
    const ids = [
      "",
      ...new Set([
        ...(state.people || []).filter((p) => p.can_assign).map((p) => p.id),
        ...tasks.map((t) => t.data.assignee).filter(Boolean),
      ]),
    ];
    for (const id of ids) {
      const rows = tasks.filter((t) => (t.data.assignee || "") === id),
        section = el("section", "assignment-group");
      const heading = el("div", "assignment-heading");
      heading.append(
        el("h2", "", id ? userName(id) : "Unassigned"),
        el("span", "count", String(rows.length)),
      );
      section.append(heading);
      if (!rows.length) section.append(el("p", "muted", "No open tasks."));
      for (const task of rows) section.append(taskCard(task));
      groups.append(section);
    }
  }
  draw();
  if (state.selected) renderDetail();
}
