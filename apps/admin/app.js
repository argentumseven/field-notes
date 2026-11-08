function readSetting(key) {
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}
("use strict");
// User-controlled strings are assigned through textContent, never HTML.
const $ = (s) => document.querySelector(s);
try {
  document.documentElement.dataset.theme =
    localStorage.getItem("field-notes-theme") ||
    (matchMedia("(prefers-color-scheme: light)").matches ? "light" : "dark");
} catch {}
const state = {
  actor: null,
  people: [],
  csrf: "",
  snapshot: null,
  project: null,
  view: "list",
  query: "",
  page: "calendar",
  autoRefresh: readSetting("field-notes-auto-refresh") !== "off",
  refreshPending: false,
  lastCheckedAt: 0,
  month: new Date(new Date().getFullYear(), new Date().getMonth(), 1),
  day: FieldPlanning.localDay(),
  selected: null,
  drafts: new Map(),
  online: true,
  images: [],
  loading: false,
};
const uuid = () => crypto.randomUUID();
function el(tag, cls = "", text = "") {
  const n = document.createElement(tag);
  if (cls) n.className = cls;
  if (text) n.textContent = text;
  return n;
}
class DialogCanceled extends Error {
  constructor() {
    super("This view changed. Reopen it and review again.");
  }
}
function button(text, fn, cls = "") {
  const n = el("button", cls, text);
  n.type = "button";
  if (text === "◐") n.setAttribute("aria-label", "Change theme");
  n.addEventListener("click", async () => {
    n.disabled = true;
    try {
      await fn();
    } catch (e) {
      if (!(e instanceof DialogCanceled)) notice(e.message);
    } finally {
      n.disabled = false;
    }
  });
  return n;
}
function input(label, value = "", type = "text") {
  const l = el("label", "", label),
    n = el(type === "textarea" ? "textarea" : "input");
  n.value = value;
  if (type !== "textarea") n.type = type;
  const ident = uuid();
  n.id = ident;
  l.htmlFor = ident;
  return { label: l, node: n };
}
function select(label, options, value) {
  const f = input(label);
  const n = el("select");
  n.id = f.node.id;
  for (const [v, t] of options) {
    const o = el("option", "", t);
    o.value = v;
    n.append(o);
  }
  n.value = value;
  return { label: f.label, node: n };
}
function addField(parent, f) {
  parent.append(f.label, f.node);
  return f.node;
}
function notice(message) {
  const n = $("#notice");
  n.textContent = message;
  n.classList.add("visible");
  clearTimeout(notice.timer);
  notice.timer = setTimeout(() => n.classList.remove("visible"), 6500);
}
async function api(path, body, method, live = () => true) {
  const h = { Accept: "application/json", "X-Field-Protocol": "1" };
  if (body !== undefined) {
    h["Content-Type"] = "application/json";
    h["X-Field-CSRF"] = state.csrf;
  }
  const clockStart = Date.now(),
    clockTick = performance.now();
  let r;
  try {
    r = await fetch("/api" + path, {
      method: method || (body !== undefined ? "POST" : "GET"),
      headers: h,
      body: body !== undefined ? JSON.stringify(body) : undefined,
      credentials: "same-origin",
    });
  } catch (e) {
    if (!live()) throw new DialogCanceled();
    state.online = false;
    updateSync();
    throw Error("Server unavailable. Your open draft is kept in this tab.");
  }
  const data = await r.json();
  if (!live()) throw new DialogCanceled();
  if (!r.ok) {
    if (r.status === 401 && state.actor && path !== "/reauth") {
      const username = state.actor.name;
      state.actor = null;
      state.csrf = "";
      state.snapshot = null;
      state.people = [];
      closeDetail();
      renderLogin({ username, message: "Your session ended. Sign in again." });
    }
    if (r.status === 403 && /reauthentication/i.test(data.error || ""))
      state.elevatedUntil = 0;
    const e = Error(data.error || "Request failed");
    e.status = r.status;
    throw e;
  }
  state.online = true;
  if (path === "/head")
    FieldClock.accept(
      data,
      clockStart,
      Date.now(),
      performance.now() - clockTick,
    );
  return data;
}
function records(kind) {
  return (state.snapshot?.records || []).filter((r) => r.kind === kind);
}
function byId(id) {
  return state.snapshot?.records.find((r) => r.id === id);
}
function projectName(id) {
  return FieldProjects.graph().path(id);
}
function userName(id) {
  return (
    state.people?.find((u) => u.id === id)?.name ||
    state.users?.find((u) => u.id === id)?.name ||
    (id === state.actor?.user_id
      ? state.actor.name
      : "Another household member")
  );
}
function mayWrite() {
  return state.actor?.role !== "viewer";
}
function isAdmin() {
  return ["owner", "admin"].includes(state.actor?.role);
}
async function refresh(expectedActor) {
  const snapshot = await api("/snapshot");
  const directory = await api("/people");
  if (expectedActor && snapshot.actor.user_id !== expectedActor.user_id)
    throw Error(
      "The browser session belongs to another account. Sign in again.",
    );
  state.snapshot = snapshot;
  state.people = directory.people;
  state.directoryRevision = directory.revision;
  state.actor = state.snapshot.actor;
  state.refreshPending = false;
  state.lastCheckedAt = Date.now();
  updateSync();
}
function updateSync() {
  const n = $("#sync");
  if (n) {
    const delayed =
      !state.lastCheckedAt || Date.now() - state.lastCheckedAt > 20000;
    n.className =
      "sync" +
      (!state.online || state.refreshPending || !state.autoRefresh || delayed
        ? " offline"
        : "");
    n.replaceChildren(
      el("span", "sync-dot"),
      el(
        "span",
        "",
        !state.online
          ? "Offline · drafts kept in this tab"
          : !state.autoRefresh
            ? "Automatic refresh paused"
            : state.refreshPending
              ? "New changes waiting · finish editing to refresh"
              : delayed
                ? "Updates delayed · checking connection"
                : state.drafts.size
                  ? "Unsent edits in this tab · server checked"
                  : "Up to date · checked just now",
      ),
    );
  }
}
function brand() {
  const n = el("div", "brand");
  const icon = el("img", "brand-mark");
  icon.src = "/field-notes-icon.svg";
  icon.alt = "";
  icon.width = icon.height = 32;
  n.append(icon, el("span", "", "Field Notes"));
  return n;
}
function renderLogin({ username = "", message = "" } = {}) {
  FieldRecovery.changed();
  FieldHistory.leave();
  state.dismissDialog?.();
  state.elevatedUntil = 0;
  $("#app").replaceChildren();
  const wrap = el("div", "login"),
    form = el("form", "login-card");
  form.append(
    brand(),
    el("h1", "", "Sign in"),
    el("p", "muted", "Manage your private planner on the home LAN."),
  );
  const name = addField(form, input("User name", username)),
    password = addField(form, input("Password", "", "password"));
  name.autocomplete = "username";
  password.autocomplete = "current-password";
  name.required = true;
  password.required = true;
  const submit = el("button", "primary", "Sign in"),
    error = el("p", "error", message);
  error.setAttribute("role", "alert");
  form.append(
    submit,
    error,
    el("div", "login-foot", "Self-hosted. No analytics. No cloud account."),
  );
  form.addEventListener("submit", async (e) => {
    e.preventDefault();
    submit.disabled = true;
    error.textContent = "";
    let passwordAccepted = false;
    try {
      const result = await api("/login", {
        name: name.value,
        password: password.value,
        device: "LAN web browser",
      });
      passwordAccepted = true;
      password.value = "";
      const session = await api("/session");
      if (
        session.actor.user_id !== result.actor.user_id ||
        session.csrf !== result.csrf
      )
        throw Error(
          "The browser did not retain the new session. Allow cookies for this HTTPS address, then sign in again.",
        );
      state.csrf = result.csrf;
      await refresh(result.actor);
      render();
    } catch (e) {
      state.csrf = "";
      error.textContent =
        passwordAccepted && e.status === 401
          ? "Your password was accepted, but the browser session could not be confirmed. Allow cookies for this HTTPS address, then sign in again."
          : e.message;
    } finally {
      submit.disabled = false;
    }
  });
  wrap.append(form);
  appendAndroidDownload(form);
  $("#app").append(wrap);
}
async function appendAndroidDownload(parent, cls = "") {
  const section = el("section", cls),
    status = el("p", "muted", "Download the Android app from this server."),
    link = el("a", "download-link primary", "Download APK");
  link.href = "/downloads/field-notes.apk";
  link.download = "field-notes.apk";
  section.append(el("h2", "", "Android app"), status, link);
  parent.append(section);
  try {
    const reply = await fetch("/downloads/android", { credentials: "omit" });
    if (!reply.ok) throw Error("Download details unavailable");
    const result = await reply.json();
    if (!result.available) {
      status.textContent =
        result.error ||
        "The server's APK is missing or failed verification. Ask the administrator to restore a verified client bundle; core notes remain available.";
      link.removeAttribute("href");
      link.setAttribute("role", "link");
      link.setAttribute("aria-disabled", "true");
      return;
    }
    const data = result.artifact;
    if (result.legacy_artifact) {
      const compatibility = el("details"),
        heading = el("summary", "", "Updating an older locally signed phone?");
      compatibility.append(
        heading,
        el(
          "p",
          "muted",
          "Its paired updater selects the compatibility APK automatically. For manual updates, keep the existing app and install this matching-signer copy over it.",
        ),
      );
      const legacy = el("a", "download-link", "Download compatibility APK");
      legacy.href = "/downloads/legacy/field-notes.apk";
      legacy.download = "field-notes-compatibility.apk";
      compatibility.append(legacy);
      section.append(compatibility);
    }
    status.textContent =
      `Version ${data.version} · ARM64 · ${data.signing} signing` +
      (result.matching_server_release === false
        ? " · Different server/client versions: core compatibility is negotiated. Install the matching client bundle for all new features."
        : "");
    for (const [label, file] of [
      ["SHA-256 checksum", "field-notes.apk.sha256"],
      ["Signing certificate", "field-notes.apk.signer.txt"],
    ]) {
      const link = el(
        "a",
        "download-link" + (file === "field-notes.apk" ? " primary" : ""),
        label,
      );
      link.href = "/downloads/" + file;
      link.download = file;
      section.append(link);
    }
    const details = el("details"),
      summary = el("summary", "", "Verify your download");
    details.append(
      summary,
      el("p", "item-sub", "APK SHA-256: " + data.sha256),
      el("p", "item-sub", "Signer SHA-256: " + data.signer_sha256),
    );
    section.append(details);
  } catch {
    status.textContent =
      "Download details could not be loaded. Retry this page or use the direct APK button.";
  }
}
function signedOut() {
  FieldArchive.leave();
  state.drafts.clear();
  state.actor = null;
  state.snapshot = null;
  state.people = [];
  state.users = [];
  state.csrf = "";
  state.elevatedUntil = 0;
  closeDetail();
  renderLogin();
}
function visibleTasks() {
  return records("task")
    .filter((t) => state.project || isPlannerTask(t))
    .filter(
      (t) =>
        (!state.project ||
          (byId(state.project)?.kind === "project"
            ? FieldProjects.graph()
                .members(state.project)
                .some((r) => r.id === t.id) &&
              (!state.projectGroup ||
                t.data.project_id ===
                  (state.projectGroup === "__direct"
                    ? state.project
                    : state.projectGroup))
            : t.data.project_id === state.project)) &&
        (t.data.title + " " + t.data.notes + " " + (t.data.item_category || ""))
          .toLowerCase()
          .includes(state.query.toLowerCase()),
    );
}
// Replacing a tall subtree briefly clamps the document's scroll position. Keep
// the viewport through a synchronous redraw, after its full height is restored.
function preserveViewport(draw) {
  const left = window.scrollX,
    top = window.scrollY;
  const areas = [...document.querySelectorAll("[data-scroll-key]")].map(
    (n) => ({
      key: n.dataset.scrollKey,
      left: n.scrollLeft,
      top: n.scrollTop,
    }),
  );
  try {
    return draw();
  } finally {
    for (const area of areas) {
      const node = [...document.querySelectorAll("[data-scroll-key]")].find(
        (n) => n.dataset.scrollKey === area.key,
      );
      node?.scrollTo({ left: area.left, top: area.top, behavior: "instant" });
    }
    window.scrollTo({ left, top, behavior: "instant" });
  }
}
function render() {
  const viewKey = JSON.stringify([
    state.actor?.user_id,
    state.page,
    state.project,
    state.view,
  ]);
  const sameView = $(".shell")?.dataset.viewKey === viewKey;
  if (sameView && state.page === "admin") {
    updateSync();
    void renderAdmin().catch((e) => notice(e.message));
    return;
  }
  const draw = () => renderWorkspace(viewKey);
  return sameView ? preserveViewport(draw) : draw();
}
function renderWorkspace(viewKey) {
  FieldRecovery.changed();
  if (state.actor && state.page === "history" && FieldHistory.isOpen()) {
    updateSync();
    FieldHistory.changed();
    return;
  }
  FieldHistory.leave();
  if (state.actor && state.page === "archive" && FieldArchive.isOpen()) {
    updateSync();
    return;
  }
  FieldArchive.leave();
  if (!state.actor) {
    renderLogin();
    return;
  }
  const previousSide = $(".sidebar"),
    sidebarScrollTop = previousSide?.scrollTop ?? 0,
    preferencesOpen =
      previousSide?.querySelector(".preferences-nav")?.open ?? false;
  for (const u of state.images) URL.revokeObjectURL(u);
  state.images = [];
  const shell = el("div", "shell"),
    side = el("aside", "sidebar"),
    main = el("main");
  shell.dataset.viewKey = viewKey;
  side.dataset.scrollKey = "sidebar";
  side.append(brand());
  const workspaceNav = el("div", "sidebar-scroll");
  side.append(workspaceNav);
  const selectedCollection = containers().find((c) => c.id === state.project);
  function destination(key, label) {
    return button(
      label,
      () => {
        state.page = key;
        state.project = null;
        state.projectGroup = "";
        state.historyTarget = null;
        state.historyTitle = null;
        state.query = "";
        closeDetail();
        render();
      },
      (state.page === key && !state.project) ||
        (key === "lists" && selectedCollection?.kind === "list") ||
        (key === "projects" && selectedCollection?.kind === "project")
        ? "active"
        : "",
    );
  }
  for (const [title, items, defaultOpen] of [
    [
      "Plan",
      [
        ["calendar", "Calendar"],
        ["tasks", "Tasks"],
        ["focus", "Focus"],
        ["later", "Later & reminders"],
      ],
      true,
    ],
    [
      "Library",
      [
        ["lists", "Lists"],
        ["projects", "Projects"],
        ["notes", "Notes"],
        ["recipes", "Recipes"],
        ["history", "History"],
      ],
      true,
    ],
    [
      "Task views",
      [
        ["inbox", "Inbox"],
        ["mine", "My tasks"],
        ["assignments", "Assignments"],
        ["unassigned", "Unassigned"],
        ["undated", "Unscheduled"],
        ["waiting", "Waiting"],
        ["completed", "Completed"],
      ],
      false,
    ],
  ]) {
    const group = el("details", "nav-group"),
      summary = el("summary", "", title),
      nav = el("nav", "nav");
    nav.setAttribute("aria-label", title);
    const saved = readSetting("field-notes-nav-" + title);
    group.open =
      items.some(([key]) => state.page === key && !state.project) ||
      (saved === null ? defaultOpen : saved === "open");
    group.ontoggle = () => {
      try {
        localStorage.setItem(
          "field-notes-nav-" + title,
          group.open ? "open" : "closed",
        );
      } catch {}
    };
    for (const [key, label] of items) nav.append(destination(key, label));
    group.append(summary, nav);
    workspaceNav.append(group);
  }
  const bottom = el("div", "sidebar-bottom");
  if (isAdmin())
    bottom.append(
      button(
        "Management",
        () => {
          closeDetail();
          state.page = "admin";
          render();
        },
        state.page === "admin" ? "active" : "",
      ),
    );
  const settingsNav = el("details", "nav-group preferences-nav");
  settingsNav.open = preferencesOpen;
  settingsNav.append(el("summary", "", "Preferences"));
  settingsNav.append(
    button("Change password", changeOwnPassword, "quiet"),
    button(
      "Change theme",
      () => {
        const t =
          document.documentElement.dataset.theme === "dark" ? "light" : "dark";
        document.documentElement.dataset.theme = t;
        try {
          localStorage.setItem("field-notes-theme", t);
        } catch {}
      },
      "quiet",
    ),
  );
  bottom.append(
    button(
      "Export & import",
      () => {
        closeDetail();
        state.page = "archive";
        state.project = null;
        render();
      },
      state.page === "archive" ? "active" : "",
    ),
  );
  bottom.append(settingsNav);
  if (state.drafts.size)
    bottom.append(
      button("Retained drafts (" + state.drafts.size + ")", () =>
        formDialog(
          "Retained drafts",
          "Copies in this tab. Copy them before closing the page.",
          [
            {
              key: "drafts",
              label: "Draft contents",
              type: "textarea",
              value: JSON.stringify([...state.drafts.values()], null, 2),
              required: false,
            },
          ],
          async () => {},
          "Close",
        ),
      ),
    );
  bottom.append(
    el("div", "person", state.actor.name + " · " + state.actor.role),
  );
  const out = button(
    "Sign out",
    async () => {
      if (state.drafts.size)
        await confirmDialog(
          "Leave open drafts?",
          "Copy your text before signing out.",
          "Sign out",
        );
      await api("/logout", {});
      signedOut();
    },
    "quiet",
  );
  out.id = "logout";
  bottom.append(out);
  const syncChoice = el("label", "auto-refresh-choice"),
    auto = el("input");
  auto.type = "checkbox";
  auto.checked = state.autoRefresh;
  auto.onchange = () => {
    state.autoRefresh = auto.checked;
    try {
      localStorage.setItem(
        "field-notes-auto-refresh",
        auto.checked ? "on" : "off",
      );
    } catch {}
  };
  syncChoice.append(auto, document.createTextNode("Auto refresh"));
  side.append(bottom);
  const top = el("header", "topline"),
    titles = el("div");
  titles.append(
    el(
      "h1",
      "",
      state.project
        ? projectName(state.project)
        : {
            inbox: "Inbox",
            notes: "Notes",
            recipes: "Recipes",
            later: "Later & reminders",
            collections: "Collections",
            waiting: "Waiting",
            completed: "Completed",
            undated: "Unscheduled",
            focus: "Focus",
            tasks: "Tasks",
            assignments: "Assignments",
            mine: "My tasks",
            unassigned: "Unassigned",
            calendar: "Calendar",
            lists: "Lists",
            projects: "Projects",
            admin: "Management",
            archive: "Export & import",
            history: "History",
          }[state.page],
    ),
    el(
      "p",
      "subtitle",
      {
        inbox: "Capture tasks before choosing where they belong.",
        notes: "References and ideas, without a task attached.",
        recipes: "Ingredients and cooking instructions, kept together.",
        collections: "Lists for ongoing things. Projects for outcomes.",
        waiting: "Blocked tasks and work claimed by someone else.",
        completed: "Finished tasks, kept for reference.",
        undated: "Open work without a planned day or deadline.",
        focus: "Your next steps, ranked by priority, time and effort.",
        tasks: "Every task. Dates are optional.",
        assignments:
          "Responsibility across your household, with active work shown separately.",
        mine: "Open work assigned to you, plus tasks you are working on.",
        unassigned: "Open tasks without a responsible person yet.",
        calendar: "Your calendar, on your server.",
        admin: "People, enrolled devices and server maintenance.",
        archive:
          "Encrypted copies, duplicate-safe imports and retained alternatives.",
        history:
          "Who added, changed or removed something, with server timestamps.",
      }[state.page],
    ),
  );
  const sync = el("div", "sync");
  sync.id = "sync";
  top.append(
    titles,
    sync,
    syncChoice,
    button("Refresh now", async () => {
      await refresh();
      if (state.page === "history") await FieldHistory.refresh();
      render();
    }),
  );
  main.append(top);
  const clockNotice = el("p", "clock-warning", state.clockWarning || "");
  clockNotice.id = "clock-warning";
  clockNotice.hidden = !state.clockWarning;
  main.append(clockNotice);

  shell.append(side, main);
  $("#app").replaceChildren(shell);
  side.scrollTop = sidebarScrollTop;
  updateSync();
  if (state.page === "history") {
    FieldHistory.render(main);
    return;
  }
  if (state.page === "admin") {
    main.append(el("div", "admin-grid"));
    renderAdmin().catch((e) => notice(e.message));
    return;
  }
  if (state.page === "archive") {
    FieldArchive.render(main);
    return;
  }
  if (state.page === "assignments") {
    renderAssignments(main);
    return;
  }
  if (state.page === "calendar") {
    renderCalendar(main);
    if (state.selected) renderDetail();
    return;
  }
  if (["notes", "later", "recipes"].includes(state.page)) {
    renderNotes(main);
    if (state.selected) renderDetail();
    return;
  }
  if (["collections", "lists", "projects"].includes(state.page)) {
    renderCollections(main);
    return;
  }
  if (state.project) {
    const c = byId(state.project);
    if (c)
      main.append(
        button(
          c.kind === "list" && c.data.project_id
            ? "‹ " + (byId(c.data.project_id)?.data.title || "Project")
            : c.kind === "list"
              ? "‹ All lists"
              : "‹ All projects",
          () => {
            state.project =
              c.kind === "list" ? c.data.project_id || null : null;
            state.projectGroup = "";
            state.page = state.project
              ? "tasks"
              : c.kind === "list"
                ? "lists"
                : "projects";
            state.query = "";
            render();
          },
        ),
      );
    if (c?.kind === "list") {
      renderChecklist(main, c);
      return;
    }
    if (c) {
      const context = el("div", "collection-context");
      context.append(
        el("span", "badge", c.kind === "list" ? "LIST" : "PROJECT"),
      );
      if (c.data.notes) context.append(el("p", "muted", c.data.notes));
      if (mayWrite())
        context.append(
          button("Edit " + c.kind, () => editCollection(c), "quiet"),
        );
      if (c.kind === "project") renderProjectContext(main, c);
      else main.append(context);
    }
  }
  const toolbar = el("div", "toolbar"),
    search = el("input");
  search.type = "search";
  search.placeholder = "Search tasks";
  search.setAttribute("aria-label", "Find a task");
  search.value = state.query;
  search.oninput = () => {
    state.query = search.value;
    renderTasks();
  };
  toolbar.append(search);
  const taskView = el("select", "task-view-select");
  taskView.setAttribute("aria-label", "Task view");
  for (const [key, label] of [
    ["focus", "Focus"],
    ["tasks", "All tasks"],
    ["mine", "My tasks"],
    ["unassigned", "Unassigned"],
    ["undated", "Unscheduled"],
    ["waiting", "Waiting"],
    ["completed", "Completed"],
  ]) {
    const option = el("option", "", label);
    option.value = key;
    taskView.append(option);
  }
  taskView.value = state.page;
  taskView.onchange = () => {
    state.page = taskView.value;
    closeDetail();
    render();
  };
  toolbar.append(taskView);
  if (state.page === "tasks") {
    const seg = el("div", "segmented");
    for (const [v, l] of [
      ["list", "List"],
      ["board", "Board"],
    ])
      seg.append(
        button(
          l,
          () => {
            state.view = v;
            render();
          },
          state.view === v ? "active" : "",
        ),
      );
    toolbar.append(seg);
  }
  main.append(toolbar);
  if (
    mayWrite() &&
    byId(state.project)?.data.status !== "archived" &&
    FieldProjects.graph().workProject({ data: { project_id: state.project } })
      ?.data.status !== "archived"
  )
    toolbar.append(button("New task", newStandaloneTask, "primary"));
  const tasks = el("div");
  tasks.id = "task-view";
  main.append(tasks);
  renderTasks();
  if (state.selected) renderDetail();
}
function renderTasks() {
  return preserveViewport(drawTasks);
}
function drawTasks() {
  const root = $("#task-view");
  if (!root) return;
  root.replaceChildren();
  state.taskFilters ||= {
    category: "*",
    person: "*",
    status: "all",
    priority: "0",
    sort: "smart",
    expanded: false,
  };
  const filters = state.taskFilters,
    bar = el("div", "view-filters");
  const choose = (label, key, options) => {
    const wrap = el("label"),
      node = select(label, options, filters[key]);
    node.node.id = "task-filter-" + key;
    node.label.htmlFor = node.node.id;
    wrap.append(node.label, node.node);
    node.node.onchange = () => {
      filters[key] = node.node.value;
      renderTasks();
      document.getElementById(node.node.id)?.focus();
    };
    bar.append(wrap);
  };
  bar.append(
    button(filters.expanded ? "Hide filters ▴" : "Filters ▾", () => {
      filters.expanded = !filters.expanded;
      renderTasks();
    }),
  );
  choose("Sort by", "sort", [
    ["smart", state.page === "focus" ? "Focus score" : "View order"],
    ["priority", "Priority"],
    ["title", "Name A–Z"],
    ["updated", "Recently changed"],
    ["due", "Due date"],
    ["planned_for", "Planned day"],
    ["estimate_minutes", "Effort"],
  ]);
  if (filters.expanded) {
    choose("Category", "category", [
      ["*", "All categories"],
      ...Object.entries(recordCategories),
    ]);
    choose("Person", "person", [
      ["*", "Everyone"],
      ["__none", "Unassigned"],
      ...state.people.map((p) => [p.id, p.name]),
    ]);
    choose("Status", "status", [
      ["all", "All statuses"],
      ["open", "Open"],
      ["todo", "To do"],
      ["in_progress", "In progress"],
      ["done", "Done"],
    ]);
    choose("Priority", "priority", [
      ["0", "Any priority"],
      ["1", "Urgent"],
      ["2", "High"],
      ["3", "Normal"],
      ["4", "Low"],
    ]);
    bar.append(
      button("Clear filters", () => {
        state.taskFilters = {
          category: "*",
          person: "*",
          status: "all",
          priority: "0",
          sort: "smart",
          expanded: true,
        };
        renderTasks();
      }),
    );
  }
  root.append(bar);
  let tasks = visibleTasks().filter(
    (t) =>
      (filters.category === "*" ||
        categoryKey(t.data.category) === categoryKey(filters.category)) &&
      (filters.person === "*" ||
        (t.data.assignee || "") ===
          (filters.person === "__none" ? "" : filters.person)) &&
      (filters.status === "all" ||
        (filters.status === "open"
          ? t.data.status !== "done"
          : t.data.status === filters.status)) &&
      (filters.priority === "0" ||
        Number(t.data.priority || 3) === Number(filters.priority)),
  );

  if (state.page === "inbox")
    tasks = tasks.filter((t) => !t.data.project_id && t.data.status !== "done");
  if (state.page === "mine")
    tasks = tasks.filter(
      (t) =>
        t.data.status !== "done" &&
        (t.data.assignee === state.actor.user_id ||
          t.data.claimant === state.actor.user_id),
    );
  if (state.page === "unassigned")
    tasks = tasks.filter((t) => t.data.status !== "done" && !t.data.assignee);
  if (state.page === "waiting")
    tasks = tasks.filter(
      (t) =>
        t.data.status !== "done" &&
        (t.data.blocked ||
          FieldProjects.graph().held(t) ||
          (t.data.claimant && t.data.claimant !== state.actor.user_id)),
    );
  if (state.page === "completed")
    tasks = tasks.filter((t) => t.data.status === "done");
  if (state.page === "undated")
    tasks = tasks.filter(
      (t) => t.data.status !== "done" && !t.data.due && !t.data.planned_for,
    );
  if (state.page === "focus")
    tasks = tasks
      .filter(
        (t) =>
          !FieldProjects.graph().held(t) &&
          FieldPlanning.rankTask(
            t,
            FieldPlanning.localDay(),
            Math.floor(Date.now() / 1000),
            state.actor.user_id,
          ).eligible,
      )
      .sort(
        (a, b) =>
          Number(!!b.data.pinned) - Number(!!a.data.pinned) ||
          FieldPlanning.rankTask(
            b,
            FieldPlanning.localDay(),
            Date.now() / 1000,
            state.actor.user_id,
          ).score -
            FieldPlanning.rankTask(
              a,
              FieldPlanning.localDay(),
              Date.now() / 1000,
              state.actor.user_id,
            ).score ||
          (a.data.created_at || Number.MAX_SAFE_INTEGER) -
            (b.data.created_at || Number.MAX_SAFE_INTEGER) ||
          a.id.localeCompare(b.id),
      );
  else
    tasks.sort(
      (a, b) =>
        Number(a.data.status === "done") - Number(b.data.status === "done") ||
        (a.data.priority || 3) - (b.data.priority || 3) ||
        (a.data.created_at || 0) - (b.data.created_at || 0) ||
        a.id.localeCompare(b.id),
    );
  if (filters.sort !== "smart")
    tasks.sort((a, b) =>
      filters.sort === "title"
        ? a.data.title.localeCompare(b.data.title)
        : filters.sort === "updated"
          ? (b.data.last_activity_at || b.data.updated_at || 0) -
            (a.data.last_activity_at || a.data.updated_at || 0)
          : filters.sort === "priority"
            ? (a.data.priority || 3) - (b.data.priority || 3)
            : filters.sort === "estimate_minutes"
              ? (a.data.estimate_minutes || Infinity) -
                (b.data.estimate_minutes || Infinity)
              : String(a.data[filters.sort] || "9999-12-31").localeCompare(
                  String(b.data[filters.sort] || "9999-12-31"),
                ),
    );
  root.append(
    el(
      "p",
      "muted",
      tasks.length + " tasks · checklist items stay inside their lists",
    ),
  );
  if (!tasks.length) {
    root.append(
      el(
        "div",
        "empty",
        state.page === "focus"
          ? "Nothing to focus on here. Blocked, completed, future-planned and other people’s claimed work remains in Tasks."
          : state.page === "inbox"
            ? "Inbox is clear. Capture a task here, then move it into a list or project whenever you need."
            : "No tasks match this view. Try another view or capture a task.",
      ),
    );
    return;
  }
  if (state.view === "board" && state.page === "tasks") {
    root.className = "board";
    for (const [key, label] of [
      ["todo", "To do"],
      ["in_progress", "In progress"],
      ["done", "Done"],
    ]) {
      const col = el("section", "column");
      col.append(el("h2", "", label));
      for (const t of tasks.filter((t) => t.data.status === key))
        col.append(taskCard(t));
      root.append(col);
    }
  } else {
    root.className = "list";
    for (const t of tasks) root.append(taskCard(t));
  }
}
function recordHistory(record) {
  const d = record.data,
    by = (id) =>
      !id
        ? "unavailable"
        : (state.people || []).find((p) => p.id === id)?.name ||
          (id === state.actor.user_id ? state.actor.name : "Former member");
  const stamp = (seconds) =>
    seconds
      ? new Date(seconds * 1000).toLocaleString([], {
          year: "numeric",
          month: "short",
          day: "numeric",
          hour: "numeric",
          minute: "2-digit",
        })
      : "time unavailable";
  const creator = d.created_by || d.author_id;
  return `Created by ${d.created_by_name || by(creator)} · ${stamp(d.created_at)}\nUpdated by ${d.last_activity_by_name || d.updated_by_name || by(d.last_activity_by || d.updated_by || creator)} · ${stamp(d.last_activity_at || d.updated_at || d.created_at)}`;
}
function historyNode(record) {
  return el("p", "record-history", recordHistory(record));
}
function taskCard(t) {
  const b = button(
    "",
    () => {
      state.selected = t.id;
      renderDetail();
    },
    "task " + t.data.status,
  );
  b.setAttribute("aria-label", "Open task: " + t.data.title);
  b.dataset.taskId = t.id;
  b.append(el("span", "task-status " + t.data.status));
  const content = el("div", "task-main");
  content.append(typeBadges(t));
  content.append(el("div", "task-title", t.data.title));
  const meta = el("div", "task-meta");
  for (const text of [
    projectName(t.data.project_id),
    ["Urgent", "High", "Normal", "Low"][(t.data.priority || 3) - 1],
    t.data.due ? "Due " + t.data.due : "",
    t.data.planned_for ? "Planned " + t.data.planned_for : "",
    t.data.estimate_minutes ? t.data.estimate_minutes + " min" : "",
    t.data.blocked ? "Blocked" : "",
    t.data.pinned ? "Pinned" : "",
    t.data.assignee ? "Assigned to " + userName(t.data.assignee) : "",
    t.data.claimant ? userName(t.data.claimant) + " is working" : "",
  ])
    if (text) meta.append(el("span", "", text));
  content.append(meta, historyNode(t));
  b.append(content);
  if (state.page === "focus")
    b.append(
      el(
        "span",
        "score",
        String(
          FieldPlanning.rankTask(
            t,
            FieldPlanning.localDay(),
            Date.now() / 1000,
            state.actor.user_id,
          ).score,
        ),
      ),
    );
  return b;
}
async function sendCommand(command) {
  try {
    return await api("/commands", command);
  } catch (e) {
    if (e.status) throw e;
    return api("/commands", command);
  }
}
async function mutate(action, target, data, revision, basis = null) {
  const c = {
    operation_id: uuid(),
    epoch: basis?.epoch ?? state.snapshot.epoch,
    generation: basis?.generation ?? state.snapshot.generation,
    action,
    target,
    expected_revision: revision,
    data,
  };
  // Retry uses this SAME envelope. Never generate a fresh ID after an ambiguous response.
  const controls = [
    ...document.querySelectorAll(
      "#app button, #app input, #app textarea, #app select, .detail button, .detail input, .detail textarea, .detail select",
    ),
  ].map((n) => [n, n.disabled]);
  for (const [n] of controls) n.disabled = true;
  let result;
  try {
    result = await sendCommand(c);
    await refresh();
    render();
    return result;
  } finally {
    for (const [n, disabled] of controls)
      if (n.isConnected) n.disabled = disabled;
  }
}
async function newProject() {
  return newCollection("project");
}
function closeDetail() {
  const previous = state.selected;
  state.selected = null;
  $(".detail")?.remove();
  $(".detail-backdrop")?.remove();
  for (const u of state.images) URL.revokeObjectURL(u);
  state.images = [];
  if (previous)
    document
      .querySelector('[data-task-id="' + previous + '"]')
      ?.focus({ preventScroll: true });
}
function renderDetail() {
  $(".detail")?.remove();
  $(".detail-backdrop")?.remove();
  const task = byId(state.selected);
  if (!task) {
    closeDetail();
    return;
  }
  if (task.kind === "note") {
    renderNoteDetail(task);
    return;
  }
  const backdrop = el("div", "detail-backdrop");
  backdrop.addEventListener("click", closeDetail);
  const detail = el("aside", "detail");
  detail.dataset.scrollKey = "task-detail:" + task.id;
  detail.setAttribute("aria-label", "Task details");
  detail.setAttribute("role", "dialog");
  detail.setAttribute("aria-modal", "true");
  detail.tabIndex = -1;
  const header = el("div", "detail-header");
  header.append(
    el(
      "span",
      "eyebrow",
      projectName(task.data.project_id) + " · revision " + task.revision,
    ),
    button("Close", closeDetail, "quiet"),
  );
  detail.append(header);
  const draft = state.drafts.get(task.id) || {
    ...task.data,
    baseRevision: task.revision,
  };
  const title = addField(detail, input("Task title", draft.title));
  title.className = "title-input";
  title.maxLength = 240;
  const notes = addField(detail, input("Notes", draft.notes, "textarea"));
  const category = addField(
    detail,
    select(
      "Category",
      categoryChoices(draft.category),
      draft.category || "general",
    ),
  );
  notes.placeholder = "A few details, links, or a little more context…";
  notes.maxLength = 16384;
  const due = addField(detail, input("Due date", draft.due || "", "date"));
  const assignee = addField(
    detail,
    select(
      "Assigned to",
      assignmentOptions(draft.assignee || ""),
      draft.assignee || "",
    ),
  );
  assignee.disabled = !mayWrite() || task.data.status === "in_progress";
  if (task.data.status === "in_progress")
    detail.append(
      el("p", "muted", "Release the task before changing its assignment."),
    );

  const priority = addField(
      detail,
      select(
        "Priority",
        [
          [1, "Urgent"],
          [2, "High"],
          [3, "Normal"],
          [4, "Low"],
        ],
        draft.priority || 3,
      ),
    ),
    planned = addField(
      detail,
      input("Planned day · optional", draft.planned_for || "", "date"),
    ),
    effort = addField(
      detail,
      input(
        "Estimate · minutes, optional",
        draft.estimate_minutes || "",
        "number",
      ),
    );
  effort.min = "0";
  effort.max = "480";
  due.required = false;
  planned.required = false;
  detail.append(
    button(
      "Clear due date",
      () => {
        due.value = "";
        dirty();
      },
      "quiet",
    ),
    button(
      "Clear planned day",
      () => {
        planned.value = "";
        dirty();
      },
      "quiet",
    ),
  );
  const pinned = addField(
      detail,
      select(
        "Focus pin",
        [
          ["false", "Unpinned"],
          ["true", "Pinned"],
        ],
        String(!!draft.pinned),
      ),
    ),
    blocked = addField(
      detail,
      select(
        "Availability",
        [
          ["false", "Ready"],
          ["true", "Blocked"],
        ],
        String(!!draft.blocked),
      ),
    );
  const rank = FieldPlanning.rankTask(
    task,
    FieldPlanning.localDay(),
    Date.now() / 1000,
    state.actor.user_id,
  );
  detail.append(
    el(
      "p",
      "rank-explanation",
      "Saved Focus score " +
        rank.score +
        (rank.eligible ? "" : " · excluded") +
        " — " +
        rank.reasons.join(" · "),
    ),
  );
  const origin = FieldProjects.graph().project(task);
  if (origin)
    detail.append(
      button("Open project · " + origin.data.title, () => {
        if (state.project !== origin.id) {
          state.projectGroup = "";
          state.query = "";
        }
        state.page = "tasks";
        state.project = origin.id;
        closeDetail();
        render();
      }),
    );
  const container = addField(
    detail,
    select(
      "Keep in",
      [
        ["", "Inbox"],
        ...containers().map((c) => [c.id, c.data.title + " · " + c.kind]),
      ],
      draft.project_id || "",
    ),
  );
  container.disabled = !mayWrite();
  container.addEventListener("input", () => dirty());
  const dirty = () =>
    state.drafts.set(task.id, {
      ...draft,
      title: title.value,
      category: category.value,
      item_category: classification.value,
      notes: notes.value,
      assignee: assignee.value || null,
      due: due.value,
      project_id: container.value,
      priority: Number(priority.value),
      planned_for: planned.value,
      estimate_minutes: Number(effort.value) || 0,
      pinned: pinned.value === "true",
      blocked: blocked.value === "true",
    });
  const classification = itemCategoryEditor(
    detail,
    () => {
      const parent = byId(container.value),
        context = itemContext({ category: category.value }, parent);
      const scoped = parent?.kind === "list" && context.scoped;
      category.hidden = scoped;
      detail.querySelector('label[for="' + category.id + '"]').hidden = scoped;
      return context;
    },
    draft.item_category || "",
    dirty,
    mayWrite(),
  );
  category.after(classification.root);
  category.addEventListener("change", () => classification.refresh());
  container.addEventListener("change", () => classification.refresh());
  for (const n of [
    title,
    category,
    notes,
    due,
    priority,
    planned,
    effort,
    pinned,
    blocked,
  ]) {
    n.disabled = !mayWrite();
    n.addEventListener("input", dirty);
  }
  assignee.addEventListener("input", dirty);
  if (draft.baseRevision !== task.revision) {
    const c = el("div", "conflict");
    c.append(
      el("strong", "", "This task changed on another device."),
      el(
        "p",
        "",
        "Your draft is kept. Compare it with the server version before applying it.",
      ),
    );
    const compare = el("details"),
      summary = el("summary", "", "Current server text");
    compare.append(
      summary,
      el(
        "pre",
        "",
        task.data.title +
          "\nAssigned to: " +
          (task.data.assignee ? userName(task.data.assignee) : "Unassigned") +
          "\nKeep in: " +
          projectName(task.data.project_id) +
          "\n\n" +
          task.data.notes,
      ),
    );
    c.append(
      compare,
      button(
        "Use server version",
        () => {
          state.drafts.delete(task.id);
          renderDetail();
        },
        "quiet",
      ),
      button(
        "Keep draft for a new save",
        () => {
          dirty();
          state.drafts.get(task.id).baseRevision = task.revision;
          renderDetail();
        },
        "quiet",
      ),
    );
    detail.append(c);
  }
  if (mayWrite())
    detail.append(
      button(
        "Save details",
        async () => {
          dirty();
          const d = state.drafts.get(task.id);
          try {
            await mutate(
              "task.update",
              task.id,
              {
                title: d.title,
                project_id: d.project_id,
                notes: d.notes,
                category: d.category,
                item_category: d.item_category,
                assignee: d.assignee,
                due: d.due,
                priority: d.priority,
                planned_for: d.planned_for,
                estimate_minutes: d.estimate_minutes,
                pinned: d.pinned,
                blocked: d.blocked,
              },
              d.baseRevision,
            );
            state.drafts.delete(task.id);
            renderDetail();
            notice("Details saved.");
          } catch (e) {
            if (e.status === 409) {
              await refresh();
              render();
            }
            throw e;
          }
        },
        "primary",
      ),
    );
  detail.append(
    el(
      "p",
      "item-sub",
      task.data.claimant
        ? "Currently claimed by " + userName(task.data.claimant)
        : task.data.status === "done"
          ? "Completed"
          : "Available to start",
    ),
  );
  if (
    task.data.assignee &&
    task.data.assignee !== state.actor.user_id &&
    task.data.status === "todo"
  )
    detail.append(
      el(
        "p",
        "muted",
        "Assigned to " +
          userName(task.data.assignee) +
          ". Reassign it to yourself before starting.",
      ),
    );
  if (task.data.status === "done" && task.data.completed_by)
    detail.append(
      el(
        "p",
        "item-sub",
        "Completed by " +
          userName(task.data.completed_by) +
          " · " +
          new Date(task.data.completed_at * 1000).toLocaleString(),
      ),
    );
  detail.append(historyNode(task));
  const actions = el("div", "detail-actions");
  if (mayWrite() && canScheduleReminders(task))
    actions.append(
      button("Reminders & sound", () => FieldReminders.edit(task)),
    );
  if (mayWrite()) {
    if (
      task.data.status === "todo" &&
      !task.data.blocked &&
      !FieldProjects.graph().held(task) &&
      (!task.data.assignee || task.data.assignee === state.actor.user_id)
    )
      actions.append(
        button(
          "Start task",
          () => mutate("task.start", task.id, {}, task.revision),
          "primary",
        ),
      );
    if (task.data.status === "in_progress")
      actions.append(
        button("Release task", () =>
          mutate("task.release", task.id, {}, task.revision),
        ),
      );
    if (task.data.status !== "done")
      actions.append(
        button("Mark complete", () =>
          mutate("task.complete", task.id, {}, task.revision),
        ),
      );
    else if (
      FieldProjects.graph().workProject(task)?.data.status !== "archived"
    )
      actions.append(
        button("Reopen", () =>
          mutate("task.reopen", task.id, {}, task.revision),
        ),
      );
    if (isAdmin() && task.data.claimant)
      actions.append(
        button("Override claim", async () => {
          await reauthenticate();
          await formDialog(
            "Release this claim",
            "This administrator action is recorded in the audit log.",
            [{ key: "reason", label: "Reason" }],
            (v) => mutate("task.override", task.id, v, task.revision),
            "Release claim",
          );
        }),
      );
    actions.append(
      button(
        "Delete",
        async () => {
          await confirmDialog(
            "Delete this task?",
            "Its comments and images will be removed from active data. Existing backups are unchanged.",
            "Delete task",
          );
          await mutate("task.delete", task.id, {}, task.revision);
          state.drafts.delete(task.id);
          closeDetail();
        },
        "danger",
      ),
    );
  }
  detail.append(actions);
  detail.append(el("h3", "", "Images"));
  const images = el("div", "image-grid");
  for (const im of records("image").filter((i) => i.data.task_id === task.id)) {
    const figure = el("figure"),
      picture = el("img");
    picture.alt = im.data.name;
    picture.loading = "lazy";
    figure.append(picture, el("figcaption", "", im.data.name));
    if (mayWrite() && (isAdmin() || im.data.author_id === state.actor.user_id))
      figure.append(
        button(
          "Remove",
          () => mutate("image.delete", im.id, {}, im.revision),
          "quiet danger",
        ),
      );
    images.append(figure);
    fetch("/api/images/" + im.id, { credentials: "same-origin" })
      .then((r) => {
        if (!r.ok) throw Error("Unavailable");
        return r.blob();
      })
      .then((b) => {
        const u = URL.createObjectURL(b);
        if (picture.isConnected) {
          picture.src = u;
          state.images.push(u);
        } else URL.revokeObjectURL(u);
      })
      .catch(() => {
        picture.alt = "Image unavailable";
      });
  }
  detail.append(images);
  if (mayWrite()) {
    const f = addField(
      detail,
      input("Attach image (PNG, JPEG, WebP · up to 4 MiB)", "", "file"),
    );
    f.accept = "image/png,image/jpeg,image/webp";
    f.addEventListener("change", async () => {
      const file = f.files[0];
      if (!file) return;
      if (file.size > 4 * 1024 * 1024) {
        notice("Choose an image smaller than 4 MiB.");
        return;
      }
      f.disabled = true;
      try {
        const bytes = await new Promise((resolve, reject) => {
          const reader = new FileReader();
          reader.onload = () => resolve(reader.result.split(",")[1]);
          reader.onerror = reject;
          reader.readAsDataURL(file);
        });
        await mutate(
          "image.create",
          uuid(),
          {
            task_id: task.id,
            parent_incarnation: task.data.recovery_incarnation || 0,
            name: file.name,
            bytes,
          },
          null,
        );
        notice("Image attached. Location metadata removed.");
      } catch (e) {
        notice(e.message);
      } finally {
        f.disabled = false;
      }
    });
  }
  detail.append(el("h3", "", "Conversation"));
  for (const c of records("comment").filter(
    (c) => c.data.task_id === task.id,
  )) {
    const item = el("div", "comment"),
      meta = el("div", "comment-meta");
    meta.append(
      el("strong", "", c.data.author),
      el("span", "", new Date(c.data.created_at * 1000).toLocaleString()),
    );
    item.append(meta, el("p", "", c.data.body));
    if (mayWrite() && (isAdmin() || c.data.author_id === state.actor.user_id))
      item.append(
        button(
          "Remove",
          () => mutate("comment.delete", c.id, {}, c.revision),
          "quiet danger",
        ),
      );
    detail.append(item);
  }
  if (mayWrite()) {
    const form = el("form", "comment comment-form"),
      comment = addField(form, input("Add a comment", "", "textarea"));
    comment.maxLength = 8192;
    comment.required = true;
    form.append(el("button", "", "Post comment"));
    form.addEventListener("submit", async (e) => {
      e.preventDefault();
      try {
        await mutate(
          "comment.create",
          uuid(),
          {
            task_id: task.id,
            parent_incarnation: task.data.recovery_incarnation || 0,
            body: comment.value,
          },
          null,
        );
      } catch (e) {
        notice(e.message);
      }
    });
    detail.append(form);
  }
  document.body.append(backdrop, detail);
  detail.focus({ preventScroll: true });
}
async function formDialog(
  title,
  description,
  fields,
  onSubmit,
  submitText = "Continue",
  configure = () => {},
  readOnly = false,
) {
  const dialog = $("#dialog"),
    body = $("#dialog-body"),
    form = $("#dialog-form");
  body.replaceChildren(el("h2", "", title), el("p", "muted", description));
  const nodes = {};
  for (const f of fields) {
    if (f.type === "checkbox") {
      const label = el("label", "dialog-choice"),
        node = el("input");
      node.type = "checkbox";
      node.checked = f.value === true;
      label.append(node, document.createTextNode(f.label));
      body.append(label);
      nodes[f.key] = node;
      continue;
    }
    nodes[f.key] = addField(
      body,
      f.options
        ? select(f.label, f.options, f.value || f.options[0][0])
        : input(f.label, f.value || "", f.type || "text"),
    );
    nodes[f.key].required = f.required !== false;
    if (f.max) nodes[f.key].maxLength = f.max;
    if (f.min) nodes[f.key].minLength = f.min;
    if (f.autocomplete) nodes[f.key].autocomplete = f.autocomplete;
  }
  configure(nodes);
  for (const node of Object.values(nodes)) node.disabled = readOnly;
  $("#dialog-error").textContent = "";
  $("#dialog-submit").textContent = submitText;
  $("#dialog-submit").hidden = readOnly;
  $("#dialog-cancel").textContent = readOnly ? "Close" : "Cancel";
  dialog.showModal();
  return new Promise((resolve, reject) => {
    let done = false,
      submitting = false;
    const finish = (ok) => {
      if (done) return;
      done = true;
      form.onsubmit = null;
      dialog.oncancel = null;
      dialog.onclose = null;
      if (state.dismissDialog === dismiss) state.dismissDialog = null;
      for (const node of Object.values(nodes))
        if (node.type === "password") node.value = "";
      for (const image of body.querySelectorAll(".invite-qr")) image.remove();
      for (const secret of body.querySelectorAll(".invitation-material"))
        secret.remove();
      dialog.close();
      if (ok) resolve();
      else reject(new DialogCanceled("Action canceled."));
    };
    const dismiss = () => finish(false);
    state.dismissDialog = dismiss;
    $("#dialog-cancel").onclick = () => {
      if (!submitting) finish(false);
    };
    dialog.oncancel = (e) => {
      e.preventDefault();
      if (!submitting) finish(false);
    };
    form.onsubmit = async (e) => {
      e.preventDefault();
      if (readOnly) return;
      if (submitting) return;
      submitting = true;
      const submit = $("#dialog-submit");
      submit.disabled = true;
      $("#dialog-cancel").disabled = true;
      try {
        await onSubmit(
          Object.fromEntries(
            Object.entries(nodes).map(([k, n]) => [
              k,
              n.type === "checkbox" ? n.checked : n.value,
            ]),
          ),
        );
        finish(true);
      } catch (e) {
        if (!done) $("#dialog-error").textContent = e.message;
      } finally {
        submitting = false;
        submit.disabled = false;
        $("#dialog-cancel").disabled = false;
      }
    };
  });
}
async function confirmDialog(title, description, submitText) {
  return formDialog(title, description, [], async () => {}, submitText);
}
async function reauthenticate() {
  if (performance.now() < (state.elevatedUntil || 0)) return;
  await formDialog(
    "Confirm it’s you",
    "Enter your password to authorize administrative changes for five minutes.",
    [{ key: "password", label: "Password", type: "password" }],
    async (v) => {
      await api("/reauth", v);
      state.elevatedUntil = performance.now() + 4 * 60 * 1000;
    },
    "Verify",
  );
}
window.addEventListener("keydown", (e) => {
  if ($("#dialog").open) return;
  if (e.key === "Escape") closeDetail();
  const detail = $(".detail");
  if (e.key === "Tab" && detail) {
    const controls = [
      ...detail.querySelectorAll("button,input,textarea,select,summary"),
    ].filter((n) => !n.disabled && n.offsetParent !== null);
    if (!controls.length) return;
    const first = controls[0],
      last = controls.at(-1);
    if (
      e.shiftKey &&
      (document.activeElement === first || document.activeElement === detail)
    ) {
      e.preventDefault();
      last.focus();
    } else if (
      !e.shiftKey &&
      (document.activeElement === last ||
        !detail.contains(document.activeElement))
    ) {
      e.preventDefault();
      first.focus();
    }
  }
});
window.addEventListener("beforeunload", (e) => {
  if (state.drafts.size) {
    e.preventDefault();
    e.returnValue = "";
  }
});
async function pollLiveChanges() {
  if (!state.actor || state.loading || document.hidden) return;
  state.loading = true;
  try {
    if (state.page === "admin") {
      const focused = document.activeElement;
      if (
        !focused ||
        !["INPUT", "TEXTAREA", "SELECT"].includes(focused.tagName)
      )
        await renderAdmin();
      return;
    }
    updateCalendarClock();
    if (!state.autoRefresh) return;
    const head = await api("/head");
    state.lastCheckedAt = Date.now();
    if (
      head.epoch !== state.snapshot.epoch ||
      head.generation !== state.snapshot.generation ||
      head.sequence !== state.snapshot.sequence ||
      head.history_revision !== state.snapshot.history_revision ||
      head.directory_revision !== state.directoryRevision
    ) {
      state.refreshPending = true;
      const focused = document.activeElement;
      const typing =
        focused && ["INPUT", "TEXTAREA", "SELECT"].includes(focused.tagName);
      const listSearch = focused?.dataset.liveRefresh === "list-search";
      if ($("#dialog").open || (typing && !listSearch)) {
        updateSync();
        return;
      }
      const caret = listSearch
        ? [focused.selectionStart, focused.selectionEnd]
        : null;
      await refresh();
      render();
      if (caret) {
        const next = document.querySelector(
          '[data-live-refresh="list-search"]',
        );
        if (next) {
          next.focus({ preventScroll: true });
          next.setSelectionRange(caret[0], caret[1]);
        }
      }
    } else {
      state.refreshPending = false;
      updateSync();
      const minute = Math.floor(Date.now() / 60000);
      if (
        state.rankMinute !== minute &&
        !state.selected &&
        !["INPUT", "TEXTAREA", "SELECT"].includes(
          document.activeElement?.tagName,
        )
      ) {
        state.rankMinute = minute;
        renderTasks();
      }
    }
  } catch (e) {
    updateSync();
  } finally {
    state.loading = false;
  }
}
setInterval(pollLiveChanges, 3000);
document.addEventListener("visibilitychange", () => {
  if (!document.hidden) void pollLiveChanges();
});
window.addEventListener("focus", () => {
  void pollLiveChanges();
});
(async () => {
  try {
    const s = await api("/session");
    state.csrf = s.csrf;
    await refresh(s.actor);
    render();
  } catch {
    renderLogin();
  }
})();

function updateAvailableViewport() {
  document.documentElement.style.setProperty(
    "--available-height",
    Math.round(window.visualViewport?.height || innerHeight) + "px",
  );
}
updateAvailableViewport();
window.visualViewport?.addEventListener("resize", updateAvailableViewport);
