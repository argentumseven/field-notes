// LAN management views. Authorization and mutations remain server enforced.
async function renderAdmin() {
  const root = $(".admin-grid");
  if (!root) return;
  const data = await api("/admin");
  state.users = data.users;
  root.replaceChildren();
  const people = el("section", "panel"),
    ph = el("div", "admin-title");
  ph.append(
    el("h2", "", "People"),
    button("+ Add person", async () => {
      await reauthenticate();
      await formDialog(
        "Add a household member",
        "Share the initial password securely. Roles are enforced by the server.",
        [
          { key: "name", label: "Name", max: 64 },
          {
            key: "password",
            label: "Initial password (12+ characters)",
            type: "password",
          },
          {
            key: "role",
            label: "Role",
            options:
              state.actor.role === "owner"
                ? [
                    ["member", "Member"],
                    ["viewer", "Viewer"],
                    ["admin", "Administrator"],
                  ]
                : [
                    ["member", "Member"],
                    ["viewer", "Viewer"],
                  ],
          },
        ],
        (v) => api("/admin", { action: "user.create", ...v }),
        "Create user",
      );
      await renderAdmin();
    }),
  );
  people.append(ph);
  for (const u of data.users) {
    const row = el("div", "item-row"),
      t = el("div");
    t.append(
      el("strong", "", u.name),
      el("div", "item-sub", u.role + (u.active ? "" : " · disabled")),
    );
    row.append(t);
    if (state.actor.role === "owner" && u.active) {
      row.append(
        button("Change role", async () => {
          await reauthenticate();
          await formDialog(
            "Change role for " + u.name,
            "Changing a role revokes all of this person’s sessions.",
            [
              {
                key: "role",
                label: "New role",
                options: [
                  ["viewer", "Viewer"],
                  ["member", "Member"],
                  ["admin", "Administrator"],
                  ["owner", "Owner"],
                ],
                value: u.role,
              },
            ],
            (v) => api("/admin", { action: "user.role", target: u.id, ...v }),
            "Change role",
          );
          await renderAdmin();
        }),
      );
      if (u.id !== state.actor.user_id)
        row.append(
          button(
            "Disable",
            async () => {
              await reauthenticate();
              await confirmDialog(
                "Disable " + u.name + "?",
                "All sessions will be revoked. Existing task content is retained.",
                "Disable user",
              );
              await api("/admin", { action: "user.disable", target: u.id });
              await renderAdmin();
            },
            "danger",
          ),
        );
    }
    people.append(row);
  }
  const devices = el("section", "panel wide"),
    dh = el("div", "admin-title");
  dh.append(
    el("h2", "", "Devices"),
    button(
      "Create invitation",
      async () => {
        await reauthenticate();
        let invite;
        await formDialog(
          "Enroll a phone",
          "Choose the account. The phone cannot choose its role.",
          [
            {
              key: "user_id",
              label: "Account",
              options: data.users
                .filter(
                  (u) =>
                    u.active &&
                    (state.actor.role === "owner" ||
                      !["owner", "admin"].includes(u.role)),
                )
                .map((u) => [u.id, u.name + " · " + u.role]),
            },
          ],
          async (v) => {
            invite = await api("/enrollment/invitations", v);
          },
          "Create invitation",
        );
        const waiting = formDialog(
          "Scan on home Wi-Fi",
          invite.user +
            " · " +
            invite.role +
            " · expires " +
            new Date(invite.expires * 1000).toLocaleTimeString() +
            ". Then enter the code shown on that phone in its Approve dialog.",
          [],
          async () => {},
          "Done",
        );
        const blob = URL.createObjectURL(
            new Blob([invite.qr_svg], { type: "image/svg+xml" }),
          ),
          picture = el("img", "invite-qr");
        picture.alt = "Private single-use enrollment invitation";
        picture.src = blob;
        $("#dialog-body").append(
          picture,
          button(
            "Copy invitation",
            async () => {
              await navigator.clipboard.writeText(invite.code);
              notice("Invitation copied. Treat it as a temporary secret.");
            },
            "quiet",
          ),
        );
        try {
          await waiting;
        } finally {
          URL.revokeObjectURL(blob);
        }
        await renderAdmin();
      },
      "primary",
    ),
  );
  devices.append(dh);
  const enroll = await api("/enrollment/devices");
  devices.append(
    el(
      "p",
      "muted",
      enroll.configured
        ? "Enrollment: scan → confirm phone code → install peer → prove tunnel access. One phone, one key, one /32 address."
        : "Host networking is not configured. Run the Linux installer before enrolling phones.",
    ),
  );
  if (enroll.configured)
    devices.append(
      el(
        "p",
        "item-sub",
        "Host peer agent: " + (enroll.provisioner_status || "waiting"),
      ),
    );
  if (enroll.provisioner_error)
    devices.append(el("p", "error", enroll.provisioner_error));
  for (const d of enroll.devices) {
    const row = el("div", "item-row"),
      label = el("div");
    label.append(
      el("strong", "", d.name),
      el(
        "div",
        "item-sub",
        d.user +
          " · " +
          d.state.replaceAll("_", " ") +
          (d.address ? " · " + d.address : ""),
      ),
    );
    row.append(label);
    if (d.state === "pending_approval")
      row.append(
        button(
          "Approve",
          async () => {
            await reauthenticate();
            await formDialog(
              "Approve " + d.name,
              "Read the eight-character code directly from your phone. This authorizes its tunnel; the host must finish provisioning before it becomes usable.",
              [{ key: "verification", label: "Code shown on phone", max: 9 }],
              (v) => api("/enrollment/devices/" + d.id + "/approve", v),
              "Approve phone",
            );
            await renderAdmin();
          },
          "primary",
        ),
      );
    if (!["revoked", "expired"].includes(d.state))
      row.append(
        button(
          d.state === "revoking" ? "Removal pending" : "Revoke device",
          async () => {
            await reauthenticate();
            await confirmDialog(
              "Revoke " + d.name + "?",
              "Content access stops immediately. Tunnel removal is only complete once the host acknowledges it.",
              "Revoke device",
            );
            await api("/enrollment/devices/" + d.id + "/revoke", {});
            await renderAdmin();
          },
          "danger",
        ),
      );
    devices.append(row);
  }
  if (!enroll.devices.length)
    devices.append(
      el(
        "p",
        "muted",
        "No enrolled devices. First create an account, then its invitation.",
      ),
    );
  root.append(devices, people);
  const sessions = el("section", "panel");
  sessions.append(el("h2", "", "Devices & sessions"));
  for (const s of data.sessions) {
    const row = el("div", "item-row"),
      t = el("div");
    t.append(
      el("strong", "", s.device || "Unnamed device"),
      el(
        "div",
        "item-sub",
        s.user +
          " · signed in " +
          new Date(s.created * 1000).toLocaleDateString(),
      ),
    );
    row.append(
      t,
      button(
        "Revoke",
        async () => {
          await reauthenticate();
          await api("/admin", { action: "session.revoke", target: s.id });
          await renderAdmin();
        },
        "danger",
      ),
    );
    sessions.append(row);
  }
  root.append(sessions);
  const recovery = el("section", "panel");
  recovery.append(
    el("h2", "", "Recovery & maintenance"),
    el(
      "p",
      "muted",
      "Backups are encrypted with your server recovery key. Keep a protected copy of that key separately.",
    ),
    button("Download encrypted backup", async () => {
      await reauthenticate();
      const r = await fetch("/api/admin/backup", {
        credentials: "same-origin",
      });
      if (!r.ok) throw Error((await r.json()).error);
      const u = URL.createObjectURL(await r.blob()),
        a = el("a");
      a.href = u;
      a.download = "field-notes.backup";
      a.click();
      setTimeout(() => URL.revokeObjectURL(u), 1000);
    }),
  );
  recovery.append(
    el(
      "p",
      "item-sub",
      "Restoration is a host-side operation: stop services, restore into a new directory, and reconnect clients. The web service cannot execute shell commands.",
    ),
  );
  if (state.actor.role === "owner")
    recovery.append(
      button(
        "Clear workspace…",
        async () => {
          await reauthenticate();
          await formDialog(
            "Clear the entire workspace",
            "This removes all projects, tasks, comments, and images from active data. Old offline changes will be rejected. Users and existing backups remain.",
            [{ key: "confirmation", label: "Type CLEAR WORKSPACE" }],
            async (v) => {
              await api("/admin", {
                action: "workspace.clear",
                generation: state.snapshot.generation,
                ...v,
              });
              state.drafts.clear();
              await refresh();
            },
            "Clear workspace",
          );
          render();
        },
        "danger",
      ),
    );
  root.append(recovery);
  const health = el("section", "panel");
  health.append(
    el("h2", "", "Server"),
    el("p", "", "Field Notes " + data.version),
    el(
      "div",
      "item-sub",
      "Workspace generation " + data.generation + " · event " + data.sequence,
    ),
    el(
      "p",
      "item-sub",
      "Admin interface: management LAN only. Phone transport: dedicated WireGuard device API. No Google push, analytics, or external page assets.",
    ),
  );
  root.append(health);
  const audit = el("section", "panel wide");
  audit.append(el("h2", "", "Administrative activity"));
  if (!data.audit.length)
    audit.append(el("p", "muted", "No administrative changes yet."));
  for (const item of data.audit) {
    const row = el("div", "item-row");
    row.append(
      el("span", "", item.action),
      el(
        "span",
        "item-sub",
        userName(item.actor) +
          " · " +
          new Date(item.at * 1000).toLocaleString(),
      ),
    );
    audit.append(row);
  }
  root.append(audit);
}
