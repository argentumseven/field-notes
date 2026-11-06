// Content recovery uses server snapshots, fenced previews and replay-safe operation IDs.
// Browser storage contains only opaque result identifiers, never content or passwords.
const FieldRecovery = (() => {
  let pending = null,
    request = null;
  try {
    pending = JSON.parse(
      sessionStorage.getItem("field-notes-recovery-result") || "null",
    );
  } catch {}
  const basis = () => ({
    epoch: state.snapshot.epoch,
    generation: state.snapshot.generation,
  });
  const moment = (at) => new Date(at * 1000).toLocaleString();
  const size = (bytes) => (bytes / 1024 / 1024).toFixed(1) + " MB";
  function scope() {
    const b = basis(),
      actor = state.actor.user_id,
      revision = state.snapshot.history_revision;
    return () =>
      state.actor?.user_id === actor &&
      state.snapshot?.epoch === b.epoch &&
      state.snapshot?.generation === b.generation &&
      state.snapshot?.history_revision === revision;
  }
  function save(value, body = null) {
    pending = value;
    request = body;
    try {
      if (value)
        sessionStorage.setItem(
          "field-notes-recovery-result",
          JSON.stringify(value),
        );
      else sessionStorage.removeItem("field-notes-recovery-result");
    } catch {}
  }
  async function read(action, fields = {}, live = scope()) {
    return api("/recovery", { action, ...basis(), ...fields }, undefined, live);
  }
  function modal(title) {
    const node = el("dialog", "recovery-dialog"),
      head = el("div", "recovery-heading"),
      body = el("div", "recovery-body"),
      error = el("p", "error"),
      actions = el("div", "dialog-actions");
    node.recoveryScope = scope();
    error.setAttribute("role", "alert");
    head.append(el("h2", "", title));
    let busy = false;
    const close = () => {
      if (!busy) {
        node.close();
        node.remove();
      }
    };
    const cancel = button("Close", close, "quiet");
    actions.append(cancel);
    node.append(head, body, error, actions);
    document.body.append(node);
    node.showModal();
    node.addEventListener("cancel", (e) => {
      e.preventDefault();
      close();
    });
    node.addEventListener("close", () => node.remove());
    return {
      node,
      body,
      error,
      actions,
      close,
      setBusy(value) {
        busy = value;
        node.setAttribute("aria-busy", String(value));
        for (const b of node.querySelectorAll("button")) b.disabled = value;
      },
      async run(fn) {
        this.setBusy(true);
        error.textContent = "";
        try {
          await fn();
        } catch (e) {
          if (!(e instanceof DialogCanceled)) error.textContent = e.message;
        } finally {
          this.setBusy(false);
        }
      },
    };
  }
  async function send(body) {
    if (pending)
      throw Error(
        "Check the previous recovery result before starting another.",
      );
    save(
      {
        actor: state.actor.user_id,
        epoch: body.epoch,
        operation_id: body.operation_id,
      },
      body,
    );
    try {
      return await api("/recovery", body);
    } catch (e) {
      throw Error(
        "Result unconfirmed. Check recovery status in History before trying a different restore. " +
          e.message,
      );
    }
  }
  async function finish(result) {
    if (!result.ok) throw Error("The server did not confirm recovery.");
    save(null);
    try {
      await refresh();
      FieldHistory.leave();
      render();
      notice("Recovery completed. Current content is synchronized.");
    } catch {
      notice(
        "Recovery completed on the server. Refresh to load its current state; open drafts are kept.",
      );
    }
  }
  function pendingCard(parent) {
    if (!pending || pending.actor !== state.actor?.user_id) return;
    const card = el("section", "panel recovery-pending");
    card.setAttribute("role", "status");
    card.append(
      el("h3", "", "Recovery result needs review"),
      el(
        "p",
        "",
        "A request may have reached the server. Check its result before starting another restore. No restore is retried automatically.",
      ),
    );
    const message = el("p", "error");
    card.append(message);
    card.append(
      button("Check recovery result", async () => {
        const result = await api("/recovery", {
          action: "result",
          ...basis(),
          operation_id: pending.operation_id,
        });
        if (result.known) {
          await finish(result.result);
          return;
        }
        message.textContent =
          "No saved result was found. It may not have committed, or its receipt was purged. Refresh and inspect current content before dismissing this status.";
      }),
    );
    if (request)
      card.append(
        button("Retry the same request", async () => {
          await finish(await api("/recovery", request));
        }),
      );
    card.append(
      button("Dismiss after reviewing content", async () => {
        await confirmDialog(
          "Dismiss recovery status?",
          "This does not undo a restore. Inspect current content first; a new restore needs a new preview.",
          "Dismiss status",
        );
        save(null);
        card.remove();
      }),
    );
    parent.append(card);
  }
  async function preview(source, title) {
    const live = scope();
    const plan = await read("preview", { source }, live);
    const d = modal("Restore preview · " + title);
    d.body.append(
      el(
        "p",
        "",
        `${plan.restored} restored · ${plan.changed} changed · ${plan.removed} removed`,
      ),
      el(
        "p",
        "muted",
        "Current state is saved before changes. Restoration creates new versions; newer retained versions remain available to roll forward. Active working claims are cleared.",
      ),
    );
    if (plan.checkpoint)
      d.body.append(
        el(
          "p",
          "recovery-warning",
          "This replaces household content with the checkpoint. Accounts, passwords, paired devices and security settings stay current. Unsynchronized edits from the earlier content state are retained for review instead of being uploaded automatically.",
        ),
      );
    for (const change of plan.changes) {
      const card = el("article", "history-entry");
      card.append(
        el(
          "h3",
          "",
          `${change.action === "remove" ? "Remove" : change.action === "restore" ? "Restore" : "Change"} · ${change.title}`,
        ),
        el("p", "muted", change.kind),
      );
      for (const field of change.changes) {
        const row = el("div", "recovery-difference");
        row.append(
          el("strong", "", field.label || field.field),
          el("p", "", "Current: " + FieldHistory.value(field, field.before)),
          el("p", "", "Saved: " + FieldHistory.value(field, field.after)),
        );
        if (field.truncated)
          row.append(
            el("small", "muted", "Excerpt; the full saved value is restored."),
          );
        card.append(row);
      }
      if (change.total_fields > change.changes.length)
        card.append(
          el(
            "p",
            "muted",
            `${change.changes.length} of ${change.total_fields} changed fields shown.`,
          ),
        );
      d.body.append(card);
    }
    if (plan.preview_limited)
      d.body.append(
        el(
          "p",
          "muted",
          `Showing the first 50 of ${plan.change_count} affected items. The entire checkpoint is applied atomically.`,
        ),
      );
    if (!plan.change_count)
      d.body.append(el("p", "", "Content already matches this saved state."));
    const apply = button(
      plan.checkpoint ? "Restore content checkpoint" : "Restore item version",
      () =>
        d.run(async () => {
          if (!live())
            throw Error("Workspace changed. Close and review a new preview.");
          if (plan.reauth_required) await reauthenticate();
          const result = await send({
            action: "apply",
            epoch: plan.epoch,
            generation: plan.generation,
            preview_id: plan.preview_id,
            operation_id: uuid(),
            confirmation: plan.checkpoint ? "RESTORE CONTENT" : "RESTORE ITEM",
          });
          d.setBusy(false);
          d.close();
          await finish(result);
        }),
      "primary",
    );
    apply.disabled = !!pending;
    d.actions.prepend(apply);
  }
  async function versions(target, title = "Item") {
    const live = scope(),
      d = modal(target ? "Versions · " + title : "Content checkpoints");
    let before = null;
    d.body.append(
      el(
        "p",
        "muted",
        target
          ? "Version indices never go backward. Choose any retained state to preview, then restore it as a new version. Earlier excerpt-only history cannot reconstruct full versions."
          : "Checkpoints include tasks, lists, notes, calendar events, comments and attached images. They exclude accounts and device/security settings.",
      ),
    );
    const rows = el("div", "recovery-versions");
    d.body.append(rows);
    async function load() {
      const page = await read(
        target ? "versions" : "checkpoints",
        { ...(target ? { target } : {}), ...(before ? { before } : {}) },
        live,
      );
      if (!page.entries.length && !before)
        rows.append(
          el(
            "p",
            "",
            "No retained states yet. Versions are captured from changes made after this feature was installed.",
          ),
        );
      for (const item of page.entries) {
        const row = el("article", "history-entry"),
          description =
            item.action === "baseline.before_change"
              ? "Previous state captured"
              : item.action === "checkpoint.before_restore"
                ? "Automatically saved before restore"
                : item.deleted
                  ? "Deleted state"
                  : item.action.startsWith("restore.")
                    ? "Restored state"
                    : item.action === "checkpoint.create"
                      ? "Named checkpoint"
                      : "Saved state";
        row.append(
          el("h3", "", `#${item.index} · ${target ? description : item.title}`),
          el(
            "p",
            "muted",
            `${item.actor_name} · ${moment(item.at)} · ${item.records} records · ${item.images} images`,
          ),
        );
        if (item.problem) row.append(el("p", "error", item.problem));
        if (item.can_restore)
          row.append(
            button("Preview restore", () =>
              preview(item.id, target ? title : item.title),
            ),
          );
        rows.append(row);
      }
      before = page.next_before;
      more.hidden = !page.more;
    }
    d.actions.prepend(
      button("Refresh saved states", () =>
        d.run(async () => {
          before = null;
          rows.replaceChildren();
          await load();
        }),
      ),
    );
    const more = button("Load older versions", () => d.run(load));
    d.actions.prepend(more);
    if (!target && isAdmin())
      d.actions.prepend(
        button(
          "Save content checkpoint",
          async () => {
            if (pending)
              throw Error("Review the pending recovery result first.");
            let result;
            await formDialog(
              "Save content checkpoint",
              "This saves household content, including attached images, under the configured storage limit.",
              [{ key: "name", label: "Checkpoint name", max: 120 }],
              async (v) => {
                const status = await read("status", {}, live);
                result = await send({
                  action: "checkpoint.create",
                  ...basis(),
                  name: v.name,
                  operation_id: uuid(),
                  history_revision: status.history_revision,
                });
              },
              "Save checkpoint",
            );
            d.close();
            await finish(result);
            await versions(null);
          },
          "primary",
        ),
      );
    await d.run(load);
  }
  function tools(parent) {
    pendingCard(parent);
    parent.append(button("Content checkpoints", () => versions(null)));
  }
  function management(parent, data) {
    if (!data) return;
    const panel = el("section", "panel wide"),
      p = data.policy,
      u = data.usage;
    panel.append(
      el("h2", "", "History & recovery storage"),
      el(
        "p",
        "",
        `${u.versions} item versions · ${u.checkpoints} checkpoints · ${size(u.recovery_bytes)} / ${p.storage_mib} MB retained · ${size(u.history_bytes)} change details`,
      ),
      el(
        "p",
        "muted",
        `Automatic versions ${p.enabled ? "on" : "off"} · ${p.days ? p.days + " days" : "no age limit"} · ${p.versions_per_item} per item · ${p.checkpoints} checkpoints. Oldest unprotected states expire when limits are reached. Shared image bytes are stored once. Purging saved history never deletes current content.`,
      ),
    );
    panel.append(
      button("Retention settings", async () => {
        await reauthenticate();
        const fields = [
          {
            key: "enabled",
            label: "Automatically retain item versions",
            type: "checkbox",
            value: p.enabled,
            required: false,
          },
          ...[
            [
              "days",
              "Version/checkpoint age in days (0 = no age limit)",
              0,
              3650,
            ],
            ["storage_mib", "Recovery storage limit (MB)", 16, 2048],
            ["versions_per_item", "Versions per item", 2, 1000],
            ["checkpoints", "Content checkpoints", 1, 100],
            [
              "history_days",
              "Change-trail age in days (0 = no age limit)",
              0,
              3650,
            ],
            ["history_entries", "Change-trail entry limit", 100, 10000],
            ["history_mib", "Change-detail storage (MB)", 1, 64],
          ].map(([key, label, min, max]) => ({
            key,
            label,
            type: "number",
            value: p[key],
            min,
            max,
          })),
        ];
        await formDialog(
          "Retention settings",
          "Reducing a limit immediately removes older retained states. Current content is preserved. If a new edit cannot safely retain its before/after states within the size limit, it is rejected with a visible error.",
          fields,
          async (v) => {
            const policy = { ...v };
            for (const k of Object.keys(policy))
              if (k !== "enabled") policy[k] = Number(policy[k]);
            await api("/admin", {
              action: "recovery.policy",
              revision: data.history_revision,
              policy,
            });
          },
          "Save retention settings",
        );
        await renderAdmin();
      }),
    );
    panel.append(
      button(
        "Purge retained history",
        async () => {
          await reauthenticate();
          await formDialog(
            "Purge retained history",
            "This permanently removes the selected recovery copies. Current content, accounts, pairings and security audit remain. Database free space is reused; its file may not shrink immediately.",
            [
              {
                key: "scope",
                label: "What to purge",
                options: [
                  ["versions", "Item versions"],
                  ["checkpoints", "Content checkpoints"],
                  ["history", "Change trail"],
                  ["all", "All content history and recovery copies"],
                ],
              },
            ],
            async (v) => {
              await api("/admin", {
                action: "recovery.purge",
                scope: v.scope,
                revision: data.history_revision,
                confirmation: "PURGE HISTORY",
              });
            },
            "Purge selected history",
          );
          await renderAdmin();
        },
        "danger",
      ),
    );
    panel.append(button("Content checkpoints", () => versions(null)));
    parent.append(panel);
  }
  function changed() {
    for (const node of document.querySelectorAll("dialog.recovery-dialog"))
      if (!node.recoveryScope()) {
        node.close();
        node.remove();
      }
  }
  return { tools, versions, pendingCard, management, changed };
})();
