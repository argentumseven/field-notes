// Progressive disclosure: common fields stay visible; each option has one home.
function editorDetails(parent, label, nodes) {
  const d = el("details", "editor-details");
  d.append(el("summary", "", label));
  for (const n of nodes) if (n) d.append(n);
  parent.append(d);
  return d;
}
function editorTabs(parent, sections) {
  const bar = el("div", "editor-tabs");
  bar.setAttribute("role", "tablist");
  bar.setAttribute("aria-label", "Event settings");
  const entries = sections.map(([name, nodes], i) => {
    const panel = el("section", "editor-panel"),
      tab = button(name, () => choose(i));
    const id = "event-section-" + uuid();
    panel.id = id;
    tab.id = id + "-tab";
    tab.setAttribute("role", "tab");
    tab.setAttribute("aria-controls", id);
    panel.setAttribute("role", "tabpanel");
    panel.setAttribute("aria-labelledby", tab.id);
    for (const n of nodes) if (n) panel.append(n);
    parent.append(panel);
    bar.append(tab);
    panel.addEventListener("invalid", () => choose(i), true);
    return { panel, tab };
  });
  function choose(i) {
    entries.forEach((v, n) => {
      v.panel.hidden = n !== i;
      v.tab.setAttribute("aria-selected", String(n === i));
      v.tab.tabIndex = n === i ? 0 : -1;
    });
  }
  bar.onkeydown = (e) => {
    const i = entries.findIndex((v) => v.tab === document.activeElement);
    let n;
    if (e.key === "ArrowRight") n = (i + 1) % entries.length;
    if (e.key === "ArrowLeft") n = (i + entries.length - 1) % entries.length;
    if (e.key === "Home") n = 0;
    if (e.key === "End") n = entries.length - 1;
    if (n !== undefined) {
      e.preventDefault();
      choose(n);
      entries[n].tab.focus();
    }
  };
  parent.insertBefore(bar, entries[0].panel);
  choose(0);
  return { choose, entries };
}
function editorFieldNodes(node) {
  if (node.type === "checkbox") return [node.closest("label")];
  return [node.previousElementSibling, node];
}
async function copyPlannerText(value) {
  if (navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(value);
      notice("Location copied.");
      return;
    } catch {}
  }
  const previous = document.activeElement,
    node = el("textarea");
  node.value = value;
  (document.querySelector("dialog[open]") || document.body).append(node);
  node.select();
  const ok = document.execCommand("copy");
  node.remove();
  previous?.focus({ preventScroll: true });
  if (!ok)
    throw Error("Copy is unavailable. Select the location text to copy it.");
  notice("Location copied.");
}
