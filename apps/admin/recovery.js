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
    const disabledBefore = new WeakMap();
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
        if (busy === value) return;
        busy = value;
        node.setAttribute("aria-busy", String(value));
        for (const b of node.querySelectorAll("button")) {
          if (value) disabledBefore.set(b, b.disabled);
          b.disabled = value || (disabledBefore.get(b) ?? false);
        }
        if (!value) node.dispatchEvent(new Event("recovery-idle"));
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
  function confirmMutation({
    title,
    description,
    phrase,
    reviewLabel,
    live,
    action,
    completed,
  }) {
    const d = modal(title);
    d.body.append(el("p", "recovery-warning", description));
    const confirmation = addField(
      d.body,
      input(`Type ${phrase} to confirm`, ""),
    );
    confirmation.autocomplete = "off";
    confirmation.spellcheck = false;
    const reviewed = el("input");
    reviewed.type = "checkbox";
    const label = el("label", "dialog-choice");
    label.append(reviewed, document.createTextNode(reviewLabel));
    d.body.append(label);
    const password = addField(
      d.body,
      input("Your account password", "", "password"),
    );
    password.autocomplete = "current-password";
    const apply = button(
      title,
      () =>
        d.run(async () => {
          if (!live())
            throw Error(
              "Workspace or recovery history changed. Close and review it again.",
            );
          if (
            confirmation.value !== phrase ||
            !reviewed.checked ||
            !password.value
          )
            throw Error(
              "Type the exact phrase, acknowledge the changes and enter your password.",
            );
          const secret = password.value;
          password.value = "";
          await api("/reauth", { password: secret });
          if (!live()) throw Error("Workspace changed. Review a new preview.");
          const result = await action(confirmation.value, reviewed.checked);
          d.setBusy(false);
          d.close();
          await completed(result);
        }),
      "danger",
    );
    const validate = () => {
      apply.disabled =
        !phrase ||
        confirmation.value !== phrase ||
        !reviewed.checked ||
        !password.value ||
        !!pending;
    };
    for (const node of [confirmation, reviewed, password])
      node.addEventListener("input", validate);
    d.node.addEventListener("recovery-idle", validate);
    d.node.addEventListener("close", () => {
      password.value = "";
    });
    d.actions.prepend(apply);
    validate();
    // This is deliberately a button, not a form: Enter in a text field cannot restore.
    d.actions.lastElementChild.focus();
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
  function stateValue(field, data) {
    if (field === "recurrence" && data?.frequency)
      return [
        FieldRecurrence.label(data),
        data.zone,
        data.until
          ? "Through " + FieldHistory.value({ field: "until" }, data.until)
          : "",
        data.dates?.length
          ? "Dates: " +
            data.dates
              .map((d) => FieldHistory.value({ field: "dates" }, d))
              .join(", ")
          : "",
        data.excluded?.length
          ? "Skipped: " +
            data.excluded
              .map((d) => FieldHistory.value({ field: "excluded" }, d))
              .join(", ")
          : "",
      ]
        .filter(Boolean)
        .join(" · ");
    if (Array.isArray(data) && ["weekdays", "days"].includes(field))
      return FieldHistory.value({ field }, data);
    if (Array.isArray(data))
      return data.map((v) => stateValue(field, v)).join("; ");
    if (data && typeof data === "object")
      return Object.entries(data)
        .filter(([key]) => key !== "id")
        .map(
          ([key, value]) =>
            key.replaceAll("_", " ") + ": " + stateValue(key, value),
        )
        .join(" · ");
    return FieldHistory.value({ field }, data);
  }
  function itemState(parent, state, label) {
    const card = el("article", "recovery-item-state");
    card.setAttribute("aria-label", label);
    if (!state) {
      card.append(el("p", "", "Item is not present in this state."));
      parent.append(card);
      return;
    }
    card.append(el("h3", "", state.title));
    const fields = el("dl", "recovery-state-fields");
    const settings = el("details", "recovery-state-settings");
    settings.append(el("summary", "", "Scheduling, reminders & list settings"));
    const advanced = el("dl", "recovery-state-fields");
    for (const field of state.fields) {
      if (["title", "name"].includes(field.field)) continue;
      if (["body", "notes"].includes(field.field)) {
        card.append(
          el(
            "p",
            "recovery-state-content",
            stateValue(field.field, field.value),
          ),
        );
        continue;
      }
      const list = [
        "recurrence",
        "reminders",
        "reminder_options",
        "fields",
        "notify_additions",
        "item_reminders",
        "perpetual",
        "template",
      ].includes(field.field)
        ? advanced
        : fields;
      if (list === fields && (field.value === false || field.value === 0))
        continue;
      list.append(
        el("dt", "", field.label),
        el("dd", "", stateValue(field.field, field.value)),
      );
    }
    card.append(fields);
    if (advanced.children.length) {
      settings.append(advanced);
      card.append(settings);
    }
    if (state.comments || state.images) {
      card.append(
        el(
          "h4",
          "",
          `${state.comments} comments · ${state.images} attachments`,
        ),
      );
      for (const child of state.children) {
        const row = el("section", "recovery-state-child");
        row.append(
          el(
            "strong",
            "",
            child.kind === "comment" ? child.author || "Comment" : child.title,
          ),
        );
        if (child.body)
          row.append(el("p", "recovery-state-content", child.body));
        card.append(row);
      }
      if (state.children_limited)
        card.append(
          el(
            "p",
            "muted",
            "First 20 comments and attachments shown. The complete state is restored together.",
          ),
        );
    }
    parent.append(card);
  }
  async function preview(source, title, hierarchyMode = "") {
    const live = scope();
    const plan = await read("preview", { source, ...(hierarchyMode ? { hierarchy_mode: hierarchyMode } : {}) }, live);
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
    if (plan.hierarchy) {
      const h = plan.hierarchy;
      d.body.append(
        el("p", "recovery-warning",
          "Hierarchy recovery: " + h.linked_existing + " existing linked records kept under their original IDs · " +
          h.missing + " deleted linked records · " +
          (h.mode === "respawn"
            ? h.respawned + " missing records will be restored under their original IDs, without overwriting live descendants."
            : "Deleted linked records stay deleted. Choose the separate restore-missing option to recover their contents.")
        ),
      );
    }
    if (plan.checkpoint)
      d.body.append(
        el(
          "p",
          "recovery-warning",
          "This replaces household content with the checkpoint. Accounts, passwords, paired devices and security settings stay current. Unsynchronized edits from the earlier content state are retained for review instead of being uploaded automatically.",
        ),
      );
    if (plan.item_state) {
      const choices = el("div", "history-actions"),
        content = el("div");
      const selected = button(`Selected state #${plan.source_index}`, () =>
          show(true),
        ),
        current = button("Current state", () => show(false));
      function show(saved) {
        selected.setAttribute("aria-pressed", String(saved));
        current.setAttribute("aria-pressed", String(!saved));
        content.replaceChildren();
        itemState(
          content,
          saved ? plan.item_state.saved : plan.item_state.current,
          saved ? "Selected item state" : "Current item state",
        );
      }
      choices.append(selected, current);
      d.body.append(choices, content);
      show(true);
      d.body.append(
        el(
          "p",
          "muted",
          "Restores the selected item. Existing linked records keep their current content; only explicitly selected missing contents are respawned using their original IDs. Individual attributes are not restored separately.",
        ),
      );
    }
    const comparison = el("details", "recovery-comparison");
    comparison.append(el("summary", "", "Compare with current state"));
    for (const change of plan.changes) {
      const card = el("article", "history-entry");
      card.append(
        el(
          "h3",
          "",
          `${change.action === "remove" ? "Remove" : change.action === "restore" ? "Restore" : "Change"} · ${change.title}`,
        ),
      );
      const details = el("details");
      details.append(el("summary", "", "Field comparison"));
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
        details.append(row);
      }
      if (change.total_fields > change.changes.length)
        details.append(
          el(
            "p",
            "muted",
            `${change.changes.length} of ${change.total_fields} changed fields shown.`,
          ),
        );
      card.append(details);
      (plan.item_state ? comparison : d.body).append(card);
    }
    if (plan.item_state && plan.change_count) d.body.append(comparison);
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
    d.body.prepend(
      el("p", "", `Selected: #${plan.source_index} · ${plan.title}`),
    );
    d.body.append(
      el(
        "p",
        "muted",
        `Preview expires ${moment(plan.expires_at)}. Changes after preview require a fresh review.`,
      ),
    );
    if (!plan.confirmation_phrase)
      d.body.append(
        el(
          "p",
          "error",
          "Update the paired server to use the required restore confirmation safeguards.",
        ),
      );
    const apply = button(
      "Continue to restore confirmation",
      () => {
        if (!live()) {
          d.error.textContent =
            "Workspace changed. Close and review a new preview.";
          return;
        }
        confirmMutation({
          title: plan.checkpoint
            ? "Restore content checkpoint"
            : "Restore item version",
          description: `Restore #${plan.source_index} · ${plan.title}: ${plan.restored} restored, ${plan.changed} changed, ${plan.removed} removed. ${plan.checkpoint ? "This replaces household content and affects everyone." : plan.hierarchy ? "Existing linked records remain unchanged; " + (plan.hierarchy.mode === "respawn" ? "missing linked records will be restored with their existing IDs." : "missing linked records stay deleted.") : "This replaces the complete item state, including its comments and attachments."} Current state is saved first. Working claims are cleared.`,
          phrase: plan.confirmation_phrase,
          reviewLabel:
            "I reviewed the selected state and restore impact, including removals.",
          live,
          action: (confirmation, reviewed) =>
            send({
              action: "apply",
              epoch: plan.epoch,
              generation: plan.generation,
              preview_id: plan.preview_id,
              operation_id: uuid(),
              confirmation,
              reviewed,
            }),
          completed: async (result) => {
            d.close();
            await finish(result);
          },
        });
      },
      "primary",
    );
    apply.disabled =
      !!pending || !plan.change_count || !plan.confirmation_phrase;
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
          ? "Each version is one complete item state. Task versions include their comments and attachments. Project and list versions restore the collection’s own details; their current tasks stay as they are. Use a content checkpoint to restore all household content together. Later states stay available."
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
            `${item.actor_name} · ${moment(item.at)}${target ? " · Complete item state" : " · " + item.records + " items · " + item.images + " images"}`,
          ),
        );
        if (item.problem) row.append(el("p", "error", item.problem));
        if (item.can_restore) {
          const isCollection = target && ["project", "list"].includes(item.kind) && !item.deleted;
          row.append(button(isCollection ? "Preview restore · reuse existing links" : "Preview restore", () =>
            preview(item.id, target ? title : item.title, isCollection ? "bindings" : ""),
          ));
          if (isCollection && isAdmin())
            row.append(button("Preview restore + missing contents", () =>
              preview(item.id, target ? title : item.title, "respawn"),
            ));
        }
        if (!target && item.can_delete)
          row.append(
            button(
              "Delete checkpoint",
              () =>
                confirmMutation({
                  title: "Delete content checkpoint",
                  description: `Permanently delete #${item.index} · ${item.title}, saved by ${item.actor_name} on ${moment(item.at)}? This recovery copy cannot be restored after deletion. Current household content and other retained states are kept.`,
                  phrase: `DELETE CHECKPOINT #${item.index}`,
                  reviewLabel:
                    "I understand this permanently removes this saved checkpoint.",
                  live,
                  action: (confirmation) =>
                    send({
                      action: "checkpoint.delete",
                      ...basis(),
                      source: item.id,
                      operation_id: uuid(),
                      history_revision: page.history_revision,
                      confirmation,
                    }),
                  completed: async (result) => {
                    d.close();
                    await finish(result);
                    notice(
                      "Content checkpoint deleted. Current content is unchanged.",
                    );
                    await versions(null);
                  },
                }),
              "danger",
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
  async function trash() {
    const live = scope(), d = modal("Trash · deleted items");
    const rows = el("div", "recovery-versions");
    d.body.append(
      el("p", "muted", "Deleted content is absent from active views. Recoverability depends on retained item versions and storage limits; Trash is not a guaranteed 30-day holding period. Old deleted items may have no recoverable version. Encrypted backups are separate."),
      rows,
    );
    let before = null;
    const more = button("Load more deleted items", () => d.run(load));
    async function load() {
      const page = await read("trash", before ? { before } : {}, live);
      if (!before && !page.entries.length) {
        rows.append(el("p", "empty", "No deleted items are recorded."));
      }
      for (const item of page.entries) {
        const row = el("article", "history-entry");
        row.append(
          el("h3", "", item.title),
          el("p", "muted", item.kind + " · " +
            (item.deleted_at ? "Deleted " + moment(item.deleted_at) : "Deletion date unavailable")),
        );
        if (item.recoverable) {
          row.append(button("Review saved versions", () => {
            d.close();
            return versions(item.id, item.title);
          }));
        } else {
          row.append(el("p", "muted", "No retained item versions are available. An earlier checkpoint or independent backup may still contain this item."));
        }
        rows.append(row);
      }
      before = page.more ? page.next_before : null;
      more.hidden = !page.more;
    }
    d.actions.prepend(more);
    await d.run(load);
  }
  function tools(parent) {
    pendingCard(parent);
    parent.append(
      button("Trash · deleted items", trash),
      button("Content checkpoints", () => versions(null)),
    );
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
      el("p", "muted", `Saved version metadata: ${size(u.version_metadata_bytes || 0)} · checkpoint metadata: ${size(u.checkpoint_metadata_bytes || 0)} · shared recovery images: ${size(u.image_bytes)}. Deleted items recorded: ${u.trash_records || 0} (${u.trash_recoverable_records || 0} with saved versions). Metadata and shared images are separate, without double-counting image bytes. Database allocated ${size(u.database_allocated_bytes)}; reusable pages ${size(u.database_reusable_bytes)}.`),
      el("p", "muted", "Finished projects remain live records indefinitely until deliberately deleted. Version age, count and size limits are ceilings, not a guaranteed recovery period. External encrypted backups must be managed separately."),
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
            `This permanently removes the selected recovery copies. Current retained counts: ${u.versions} item versions, ${u.checkpoints} checkpoints and ${size(u.history_bytes)} of change details. Review which category you are purging. Current content, accounts, pairings and security audit remain; existing backups are unaffected. Reclaimed database space can be reused without shrinking the file.`,
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
              {
                key: "confirmation",
                label: "Type PURGE HISTORY",
              },
              {
                key: "reviewed",
                type: "checkbox",
                label: "I understand the selected recovery copies will be permanently removed from this server. Existing backups are not affected.",
              },
            ],
            async (v) => {
              if (v.confirmation !== "PURGE HISTORY" || !v.reviewed)
                throw Error("Review the impact and type PURGE HISTORY to continue.");
              await api("/admin", {
                action: "recovery.purge",
                scope: v.scope,
                revision: data.history_revision,
                confirmation: v.confirmation,
              });
            },
            "Purge selected history",
          );
          await renderAdmin();
        },
        "danger",
      ),
    );
    panel.append(
      button("Trash · deleted items", trash),
      button("Content checkpoints", () => versions(null)),
    );
    parent.append(panel);
  }
  function changed() {
    for (const node of document.querySelectorAll("dialog.recovery-dialog"))
      if (!node.recoveryScope()) {
        node.close();
        node.remove();
      }
  }
  return { tools, versions, trash, pendingCard, management, changed };
})();
