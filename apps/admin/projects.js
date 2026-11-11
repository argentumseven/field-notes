// Categories describe the work. Projects and lists provide its context.
const FieldProjects = (() => {
  class Graph {
    constructor(rows) {
      this.rows = rows;
      this.byId = new Map(rows.map((r) => [r.id, r]));
      this.lists = rows.filter((r) => r.kind === "list");
      this.tasks = rows.filter((r) => r.kind === "task");
      this.work = new Map();
      for (const task of this.tasks) {
        const project = this.workProject(task);
        if (project) {
          if (!this.work.has(project.id)) this.work.set(project.id, []);
          this.work.get(project.id).push(task);
        }
      }
    }
    project(record) {
      if (!record) return null;
      if (record.kind === "project") return record;
      const parent = this.byId.get(
        record.data.project_id || record.data.task_id,
      );
      if (!parent) return null;
      return parent.kind === "project"
        ? parent
        : ["list", "task"].includes(parent.kind)
          ? this.project(parent)
          : null;
    }
    workProject(task) {
      const parent = this.byId.get(task.data.project_id);
      return parent?.kind === "list" && !parent.data.items_are_tasks
        ? null
        : this.project(task);
    }
    planner(task) {
      return (
        task.kind === "task" &&
        !(
          this.byId.get(task.data.project_id)?.kind === "list" &&
          !this.byId.get(task.data.project_id).data.items_are_tasks
        )
      );
    }
    held(task) {
      const project = this.workProject(task);
      return !!project && (project.data.status || "active") !== "active";
    }
    path(id) {
      const row = this.byId.get(id);
      if (!row) return id ? "Collection unavailable" : "Inbox";
      const p = row.kind === "list" ? this.project(row) : null;
      return (p ? p.data.title + " › " : "") + row.data.title;
    }
    members(id) {
      return this.work.get(id) || [];
    }
    groups(id) {
      return this.lists.filter((r) => r.data.project_id === id);
    }
    rollup(project, today) {
      const rows = this.members(project.id),
        open = rows.filter((r) => r.data.status !== "done");
      const ready = open.filter(
        (r) =>
          r.data.status === "todo" &&
          !r.data.blocked &&
          !this.held(r) &&
          (!r.data.planned_for || r.data.planned_for <= today),
      );
      return {
        total: rows.length,
        done: rows.length - open.length,
        open: open.length,
        working: open.filter((r) => r.data.status === "in_progress").length,
        waiting: open.filter(
          (r) => r.data.status === "todo" && (r.data.blocked || this.held(r)),
        ).length,
        ready: ready.length,
        later: open.filter(
          (r) =>
            r.data.status === "todo" &&
            !r.data.blocked &&
            !this.held(r) &&
            r.data.planned_for > today,
        ).length,
        overdue: open.filter((r) => r.data.due && r.data.due < today).length,
        unassigned: open.filter((r) => !r.data.assignee).length,
        undated: open.filter((r) => !r.data.due && !r.data.planned_for).length,
        remaining_minutes: open.reduce(
          (n, r) => n + Number(r.data.estimate_minutes || 0),
          0,
        ),
        unestimated: open.filter((r) => !r.data.estimate_minutes).length,
        after_target: open.filter(
          (r) => project.data.due && r.data.due > project.data.due,
        ).length,
        activity: Math.max(
          0,
          ...[
            project,
            ...this.tasks.filter((t) => this.project(t)?.id === project.id),
            ...this.groups(project.id),
          ].map((r) => r.data.last_activity_at || r.data.updated_at || 0),
        ),
      };
    }
  }
  let snapshot, cached;
  function graph() {
    if (snapshot !== state.snapshot) {
      snapshot = state.snapshot;
      cached = new Graph(snapshot?.records || []);
    }
    return cached;
  }
  const stateLabel = (p, r) =>
    p.data.status === "archived"
      ? "Finished"
      : p.data.status === "on_hold"
        ? "On hold"
        : !r.total
          ? "Planning"
          : !r.open
            ? "Work complete"
            : r.working
              ? "In progress"
              : r.ready
                ? "Ready"
                : r.waiting
                  ? "Waiting"
                  : "Scheduled";
  return { Graph, graph, stateLabel };
})();
function renderProjectContext(root, project) {
  const graph = FieldProjects.graph(),
    r = graph.rollup(project, FieldPlanning.localDay());
  const section = el("section", "project-summary");
  section.setAttribute("aria-label", "Project overview");
  const line = el("div", "project-heading");
  line.append(
    typeBadges(project),
    el("strong", "", project.data.status === "archived" ? "Finished project · retained" : "Project state: " + FieldProjects.stateLabel(project, r)),
  );
  section.append(line);
  const context = [
    project.data.assignee
      ? "Coordinator: " +
        (state.people.find((p) => p.id === project.data.assignee)?.name ||
          "Unavailable member")
      : "No coordinator",
    project.data.due
      ? "Target: " + FieldHistory.value({ field: "due" }, project.data.due)
      : "No target date",
  ];
  if (project.data.status === "archived" && project.data.finished_at)
    context.push("Finished on " + new Date(project.data.finished_at * 1000).toLocaleDateString());
  section.append(el("p", "muted", context.join(" · ")));
  const progress = el("progress");
  progress.max = r.total || 1;
  progress.value = r.done;
  progress.setAttribute("aria-label", "Project task completion");
  section.append(
    el(
      "strong",
      "",
      r.total ? `${r.done} of ${r.total} tasks complete` : "No work tasks yet",
    ),
    progress,
  );
  section.append(
    el(
      "p",
      "project-counts",
      `${r.ready} ready · ${r.working} in progress · ${r.waiting} waiting · ${r.later} planned later · ${r.overdue} overdue`,
    ),
  );
  if (r.open)
    section.append(
      el(
        "p",
        "muted",
        `${r.unassigned} unassigned · ${r.undated} without a date · ${r.remaining_minutes} known minutes remaining${r.unestimated ? " · " + r.unestimated + " tasks without estimates" : ""}`,
      ),
    );
  if (r.after_target)
    section.append(
      el(
        "p",
        "project-warning",
        `${r.after_target} tasks have due dates after the project target. Review the schedule.`,
      ),
    );
  if (project.data.notes) {
    const goal = el("details");
    goal.append(
      el("summary", "", "Goal & context"),
      el("p", "project-description", project.data.notes),
    );
    section.append(goal);
  }
  const actions = el("div", "toolbar");
  async function change(status) {
    await mutate("project.update", project.id, { status }, project.revision);
  }
  if (mayWrite()) {
    actions.append(button("Project settings / delete", () => editCollection(project)));
    if (project.data.status === "on_hold" || project.data.status === "archived")
      actions.append(
        button("Reopen project", () => change("active"), "primary"),
      );
    else {
      const hold = button("Put on hold", () => change("on_hold"));
      hold.disabled = !!r.working;
      actions.append(hold);
    }
    if (project.data.status !== "archived") {
      const done = button("Finish project", () => change("archived"));
      done.disabled = !!r.open;
      actions.append(done);
    }
    if (project.data.status !== "archived")
      actions.append(
        button("Add existing task", () => addExistingTask(project)),
        button("New task list", () =>
          newCollection("list", {
            project_id: project.id,
            items_are_tasks: true,
            category: project.data.category,
          }),
        ),
        button("New reference list", () =>
          newCollection("list", {
            project_id: project.id,
            category: project.data.category,
          }),
        ),
      );
  }
  actions.append(
    button("Project history", () => {
      state.historyTarget = project.id;
      state.historyTitle = project.data.title;
      state.page = "history";
      state.selected = null;
      render();
    }),
  );
  section.append(actions);
  const groups = graph.groups(project.id),
    work = groups.filter((g) => g.data.items_are_tasks),
    refs = groups.filter((g) => !g.data.items_are_tasks);
  const choose = select(
    "Work group",
    [
      ["", "All project work"],
      ["__direct", "Direct tasks"],
      ...work.map((g) => [g.id, g.data.title]),
    ],
    state.projectGroup || "",
  );
  if (
    state.projectGroup &&
    state.projectGroup !== "__direct" &&
    !work.some((g) => g.id === state.projectGroup)
  )
    state.projectGroup = "";
  choose.node.value = state.projectGroup || "";
  choose.node.onchange = () => {
    state.projectGroup = choose.node.value;
    renderTasks();
  };
  section.append(
    addField(el("div", "project-group-choice"), choose).parentElement,
  );
  if (groups.length) {
    const details = el("details", "project-links");
    details.append(
      el(
        "summary",
        "",
        `Lists in this project · ${work.length} task lists · ${refs.length} reference lists`,
      ),
    );
    for (const list of groups) {
      const b = button(
        list.data.title +
          " · " +
          (list.data.items_are_tasks ? "Task list" : "Reference list"),
        () => {
          state.project = list.id;
          state.projectGroup = "";
          state.page = "tasks";
          state.query = "";
          render();
        },
      );
      details.append(b);
    }
    details.append(
      el(
        "p",
        "muted",
        "Task-list items count toward project progress. Shopping and reference checklists stay separate.",
      ),
    );
    section.append(details);
  }
  root.append(section);
}
