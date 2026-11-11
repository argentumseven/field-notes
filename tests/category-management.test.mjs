import { test } from "node:test";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { fixture, password } from "./support.mjs";
import { execFileSync } from "node:child_process";
import { join } from "node:path";

async function scene() {
  const f = await fixture();
  const token = await f.login();
  const auth = await f.request("/api/reauth", { token, body: { password } });
  assert.equal(auth.status, 200);
  const initial = (await f.request("/api/snapshot", { token })).data;
  const send = (action, target, data = {}, revision = null) =>
    f.request("/api/commands", {
      token,
      body: {
        operation_id: randomUUID(),
        epoch: initial.epoch,
        generation: initial.generation,
        action,
        target,
        expected_revision: revision,
        data,
      },
    });
  const admin = (action, data = {}) =>
    f.request("/api/admin", { token, body: { action, ...data } });
  const snapshot = async () =>
    (await f.request("/api/snapshot", { token })).data;
  const ok = async (...args) => {
    const r = await send(...args);
    assert.equal(r.status, 200, JSON.stringify(r.data));
    return r.data.record;
  };
  return { f, token, send, admin, snapshot, ok };
}

test("Custom category rename, archive, merge and delete preserve all assigned records", async () => {
  const s = await scene();
  try {
    assert.deepEqual((await s.snapshot()).categories, []);
    const id = randomUUID(),
      key = "custom:" + id;
    assert.equal(
      (
        await s.admin("category.create", {
          target: id,
          name: "Garden projects",
        })
      ).status,
      200,
    );
    let cat = (await s.snapshot()).categories.find((c) => c.id === id);
    assert.equal(cat.name, "Garden projects");
    const p = await s.ok("project.create", randomUUID(), {
      title: "Restore soil",
      category: key,
    });
    const t = await s.ok("task.create", randomUUID(), {
      title: "Pick up mulch",
      notes: "Do not lose this",
      category: key,
      project_id: p.id,
    });
    const note = await s.ok("note.create", randomUUID(), {
      title: "Measurements",
      body: "Six bags",
      category: key,
    });
    assert.equal(
      (
        await s.admin("category.rename", {
          target: id,
          name: "Landscaping",
          revision: cat.revision,
        })
      ).status,
      200,
    );
    cat = (await s.snapshot()).categories.find((c) => c.id === id);
    assert.equal(cat.name, "Landscaping");
    assert.equal(
      (await s.snapshot()).records.find((r) => r.id === t.id).data.category,
      key,
    );
    assert.equal(
      (
        await s.admin("category.archive", {
          target: id,
          revision: cat.revision,
        })
      ).status,
      200,
    );
    cat = (await s.snapshot()).categories.find((c) => c.id === id);
    assert.equal(cat.archived, true);
    assert.equal(
      (
        await s.send("task.create", randomUUID(), {
          title: "Cannot choose archived",
          category: key,
        })
      ).status,
      409,
    );
    // Existing assignment remains editable and retains its category.
    const changed = await s.ok(
      "task.update",
      t.id,
      { notes: "Important measurements" },
      t.revision,
    );
    assert.equal(changed.data.category, key);
    assert.equal(
      (
        await s.send(
          "task.update",
          t.id,
          { category: "general" },
          changed.revision,
        )
      ).status,
      409,
      "old client cannot silently replace custom classification",
    );
    const contents = [p, changed, note].map((r) => ({
      id: r.id,
      revision: r.revision,
    }));
    assert.equal(
      (
        await s.admin("category.merge", {
          target: id,
          revision: cat.revision,
          into: "home",
          contents,
          confirmation: "MERGE CATEGORY",
        })
      ).status,
      200,
    );
    const rows = (await s.snapshot()).records;
    for (const r of [p, changed, note])
      assert.equal(rows.find((v) => v.id === r.id).data.category, "home");
    assert.equal(
      rows.find((v) => v.id === t.id).data.notes,
      "Important measurements",
    );
    assert.equal(rows.find((v) => v.id === note.id).data.body, "Six bags");
    cat = (await s.snapshot()).categories.find((c) => c.id === id);
    assert.equal(
      (
        await s.admin("category.delete", {
          target: id,
          revision: cat.revision,
          confirmation: "DELETE CATEGORY",
        })
      ).status,
      200,
    );
    assert.equal(
      (await s.snapshot()).categories.find((c) => c.id === id),
      undefined,
    );
  } finally {
    await s.f.close();
  }
});

test("Category merge is revision fenced and cannot delete a category with assigned work", async () => {
  const s = await scene();
  try {
    const id = randomUUID(),
      key = "custom:" + id;
    assert.equal(
      (await s.admin("category.create", { target: id, name: "Assignments" }))
        .status,
      200,
    );
    const category = (await s.snapshot()).categories.find((c) => c.id === id);
    const task = await s.ok("task.create", randomUUID(), {
      title: "Keep",
      category: key,
    });
    assert.equal(
      (
        await s.admin("category.delete", {
          target: id,
          revision: category.revision,
          confirmation: "DELETE CATEGORY",
        })
      ).status,
      409,
    );
    assert.equal(
      (
        await s.admin("category.merge", {
          target: id,
          revision: category.revision,
          into: "work",
          contents: [],
          confirmation: "MERGE CATEGORY",
        })
      ).status,
      409,
    );
    const updated = await s.ok(
      "task.update",
      task.id,
      { notes: "Newer edit" },
      task.revision,
    );
    assert.equal(
      (
        await s.admin("category.merge", {
          target: id,
          revision: category.revision,
          into: "work",
          contents: [{ id: task.id, revision: task.revision }],
          confirmation: "MERGE CATEGORY",
        })
      ).status,
      409,
    );
    assert.equal(
      (await s.snapshot()).records.find((r) => r.id === task.id).data.notes,
      "Newer edit",
    );
    assert.equal(
      (
        await s.admin("category.merge", {
          target: id,
          revision: category.revision,
          into: "work",
          contents: [{ id: updated.id, revision: updated.revision }],
          confirmation: "MERGE CATEGORY",
        })
      ).status,
      200,
    );
    assert.equal(
      (await s.snapshot()).records.find((r) => r.id === task.id).data.category,
      "work",
    );
  } finally {
    await s.f.close();
  }
});

test("List unbundle releases work tasks without deleting the list, comments or work history", async () => {
  const s = await scene();
  try {
    const list = await s.ok("list.create", randomUUID(), {
      title: "Build checklist",
      items_are_tasks: true,
    });
    const task = await s.ok("task.create", randomUUID(), {
      title: "Measure wall",
      notes: "Preserve measurements",
      project_id: list.id,
    });
    const comment = await s.ok("comment.create", randomUUID(), {
      task_id: task.id,
      body: "Remember studs",
    });
    const contents = [task, comment].map((r) => ({
      id: r.id,
      revision: r.revision,
    }));
    assert.equal(
      (
        await s.send(
          "list.unbundle",
          list.id,
          { contents: "not an array" },
          list.revision,
        )
      ).status,
      400,
    );
    assert.equal(
      (
        await s.send(
          "list.unbundle",
          list.id,
          {
            contents: [
              { id: task.id, revision: 0 },
              { id: comment.id, revision: comment.revision },
            ],
          },
          list.revision,
        )
      ).status,
      409,
    );
    assert.equal(
      (await s.send("list.unbundle", list.id, { contents }, list.revision))
        .status,
      200,
    );
    const rows = (await s.snapshot()).records;
    assert.equal(
      rows.find((r) => r.id === list.id).data.title,
      "Build checklist",
    );
    assert.equal(rows.find((r) => r.id === task.id).data.project_id, "");
    assert.equal(
      rows.find((r) => r.id === task.id).data.notes,
      "Preserve measurements",
    );
    assert.equal(
      rows.find((r) => r.id === comment.id).data.body,
      "Remember studs",
    );
    assert.equal(
      (await s.send("list.unbundle", list.id, { contents }, list.revision + 1))
        .status,
      409,
    );
    const ref = await s.ok("list.create", randomUUID(), {
      title: "Shopping",
      items_are_tasks: false,
    });
    assert.equal(
      (await s.send("list.unbundle", ref.id, { contents: [] }, ref.revision))
        .status,
      409,
      "reference lists must preserve their grouping",
    );
  } finally {
    await s.f.close();
  }
});

test("Converting a work list to reference mode cannot strand an in-progress task", async () => {
  const s = await scene();
  try {
    const list = await s.ok("list.create", randomUUID(), {
      title: "Queue",
      items_are_tasks: true,
    });
    const task = await s.ok("task.create", randomUUID(), {
      title: "Task",
      project_id: list.id,
    });
    assert.equal(
      (await s.send("task.start", task.id, {}, task.revision)).status,
      200,
    );
    assert.equal(
      (
        await s.send(
          "list.update",
          list.id,
          { items_are_tasks: false },
          list.revision,
        )
      ).status,
      409,
    );
    assert.equal(
      (await s.send("task.release", task.id, {}, task.revision + 1)).status,
      200,
    );
    assert.equal(
      (
        await s.send(
          "list.update",
          list.id,
          { items_are_tasks: false },
          list.revision,
        )
      ).status,
      200,
    );
    const rows = (await s.snapshot()).records;
    assert.equal(rows.find((r) => r.id === task.id).data.project_id, list.id);
    assert.equal(
      rows.find((r) => r.id === list.id).data.items_are_tasks,
      false,
    );
  } finally {
    await s.f.close();
  }
});

test("Custom category on calendar events cannot be silently replaced by an older editor", async () => {
  const s = await scene();
  try {
    const id = randomUUID(),
      key = "custom:" + id;
    assert.equal(
      (
        await s.admin("category.create", {
          target: id,
          name: "Maintenance calendar",
        })
      ).status,
      200,
    );
    const event = await s.ok("event.create", randomUUID(), {
      title: "Service visit",
      category: key,
      all_day: true,
      start_date: "2026-12-10",
      end_date: "2026-12-11",
    });
    assert.equal(
      (
        await s.send(
          "event.update",
          event.id,
          { category: "general" },
          event.revision,
        )
      ).status,
      409,
    );
    assert.equal(
      (await s.snapshot()).records.find((r) => r.id === event.id).data.category,
      key,
    );
    const changed = await s.ok(
      "event.update",
      event.id,
      { category: "general", category_override: true },
      event.revision,
    );
    assert.equal(changed.data.category, "general");
    assert.equal(
      changed.data.category_override,
      undefined,
      "editor-only override must never become persistent content",
    );
  } finally {
    await s.f.close();
  }
});

test("Calendar rejects newly assigned inactive categories while preserving existing archived assignments", async () => {
  const s = await scene();
  try {
    const id = randomUUID(),
      key = "custom:" + id;
    assert.equal(
      (await s.admin("category.create", { target: id, name: "Past calendar" }))
        .status,
      200,
    );
    const fields = {
      title: "Visit",
      all_day: true,
      start_date: "2026-12-10",
      end_date: "2026-12-11",
    };
    const existing = await s.ok("event.create", randomUUID(), {
      ...fields,
      category: key,
    });
    const other = await s.ok("event.create", randomUUID(), fields);
    assert.equal(
      (await s.admin("category.archive", { target: id, revision: 1 })).status,
      200,
    );
    for (const extra of [{}, { category_override: true }]) {
      assert.equal(
        (
          await s.send(
            "event.update",
            other.id,
            { category: key, ...extra },
            other.revision,
          )
        ).status,
        409,
      );
      assert.equal(
        (
          await s.send("event.create", randomUUID(), {
            ...fields,
            category: key,
            ...extra,
          })
        ).status,
        409,
      );
    }
    const kept = await s.ok(
      "event.update",
      existing.id,
      { title: "Updated visit", category: key },
      existing.revision,
    );
    assert.equal(kept.data.category, key);
    assert.equal(
      (
        await s.send(
          "event.update",
          existing.id,
          { category_override: false },
          kept.revision,
        )
      ).status,
      400,
    );
    assert.equal(
      (await s.snapshot()).records.find((r) => r.id === other.id).data.category,
      "general",
    );
    await s.ok(
      "event.update",
      existing.id,
      { category: "general", category_override: true },
      kept.revision,
    );
    assert.equal(
      (
        await s.admin("category.delete", {
          target: id,
          revision: 2,
          confirmation: "DELETE CATEGORY",
        })
      ).status,
      200,
    );
    assert.equal(
      (
        await s.send(
          "event.update",
          other.id,
          { category: key },
          other.revision,
        )
      ).status,
      409,
    );
  } finally {
    await s.f.close();
  }
});

async function categoryArchive(s) {
  const active = randomUUID(),
    archived = randomUUID(),
    deleted = randomUUID();
  for (const [id, name] of [
    [active, "Garden"],
    [archived, "Past work"],
    [deleted, "Removed category"],
  ])
    assert.equal(
      (await s.admin("category.create", { target: id, name })).status,
      200,
    );
  const project = await s.ok("project.create", randomUUID(), {
    title: "Garden project",
    category: "custom:" + active,
  });
  const list = await s.ok("list.create", randomUUID(), {
    title: "Garden list",
    project_id: project.id,
    category: "custom:" + active,
    items_are_tasks: true,
  });
  await s.ok("task.create", randomUUID(), {
    title: "Archived task",
    notes: "Preserve notes",
    project_id: list.id,
    category: "custom:" + archived,
  });
  await s.ok("note.create", randomUUID(), {
    title: "Garden note",
    body: "Preserve body",
    category: "custom:" + active,
  });
  await s.ok("event.create", randomUUID(), {
    title: "Past event",
    all_day: true,
    start_date: "2026-12-10",
    end_date: "2026-12-11",
    category: "custom:" + archived,
  });
  assert.equal(
    (await s.admin("category.archive", { target: archived, revision: 1 }))
      .status,
    200,
  );
  assert.equal(
    (
      await s.admin("category.delete", {
        target: deleted,
        revision: 1,
        confirmation: "DELETE CATEGORY",
      })
    ).status,
    200,
  );
  const exported = await s.f.request("/api/transfer/export", {
    token: s.token,
  });
  assert.equal(exported.status, 200);
  assert.equal(exported.data.version, 3);
  assert.equal(exported.data.categories.length, 3);
  return { manifest: exported.data, active, archived, deleted };
}
const transfer = (s, action, body, token = s.token) =>
  s.f.request("/api/transfer/" + action, { token, ...(body ? { body } : {}) });

test("Portable custom categories round-trip atomically with preview, password proof, replay and restart", async () => {
  const source = await scene(),
    target = await scene();
  try {
    const { manifest, active, archived, deleted } =
      await categoryArchive(source);
    const token = await target.f.login(); // A new session has no recent password proof.
    let r = await transfer(target, "prepare", { manifest }, token);
    assert.equal(r.status, 200, JSON.stringify(r.data));
    const plan = r.data;
    assert.equal(plan.new_categories, 3);
    assert.deepEqual(
      (await target.snapshot()).categories,
      [],
      "preview rolls category definitions back",
    );
    assert.equal(
      (await transfer(target, "commit", { plan_id: plan.plan_id }, token))
        .status,
      403,
    );
    assert.deepEqual((await target.snapshot()).categories, []);
    assert.equal(
      (await target.f.request("/api/reauth", { token, body: { password } }))
        .status,
      200,
    );
    r = await transfer(target, "commit", { plan_id: plan.plan_id }, token);
    assert.equal(r.status, 200, JSON.stringify(r.data));
    assert.equal(r.data.imported, 5);
    assert.deepEqual(
      (await transfer(target, "commit", { plan_id: plan.plan_id }, token)).data,
      r.data,
    );
    let snap = await target.snapshot();
    assert.equal(snap.categories.find((c) => c.id === active).name, "Garden");
    assert.equal(snap.categories.find((c) => c.id === archived).archived, true);
    assert.equal(
      snap.categories.find((c) => c.id === deleted),
      undefined,
    );
    assert.equal(
      snap.records.find((c) => c.kind === "task").data.category,
      "custom:" + archived,
    );
    assert.equal(
      snap.records.find((c) => c.kind === "task").data.notes,
      "Preserve notes",
    );
    // Re-importing stale metadata cannot rename, reopen or resurrect destination definitions.
    assert.equal(
      (
        await target.admin("category.rename", {
          target: active,
          revision: 1,
          name: "Local garden",
        })
      ).status,
      200,
    );
    assert.equal(
      (await target.admin("category.archive", { target: active, revision: 2 }))
        .status,
      200,
    );
    r = await transfer(target, "prepare", { manifest });
    assert.equal(r.status, 200, JSON.stringify(r.data));
    assert.equal(r.data.new_categories, 0);
    assert.equal(r.data.new_items, 0);
    assert.equal(
      (await transfer(target, "commit", { plan_id: r.data.plan_id })).status,
      200,
    );
    await target.f.stop();
    await target.f.start();
    snap = await target.snapshot();
    assert.equal(
      snap.categories.find((c) => c.id === active).name,
      "Local garden",
    );
    assert.equal(snap.categories.find((c) => c.id === active).archived, true);
    assert.equal(snap.records.length, 5);
    const task = snap.records.find((c) => c.kind === "task");
    assert.equal(
      (
        await target.send(
          "task.update",
          task.id,
          { notes: "Still editable" },
          task.revision,
        )
      ).status,
      200,
    );
  } finally {
    await source.f.close();
    await target.f.close();
  }
});

test("Category imports preserve authorization, reject malformed definitions and roll back failed commits", async () => {
  const source = await scene(),
    target = await scene();
  try {
    const { manifest, active } = await categoryArchive(source);
    assert.equal(
      (
        await target.admin("user.create", {
          name: "Member",
          password,
          role: "member",
        })
      ).status,
      200,
    );
    const member = await target.f.login("Member");
    assert.equal(
      (await transfer(target, "prepare", { manifest }, member)).status,
      403,
    );
    for (const categories of [
      [...manifest.categories, manifest.categories[0]],
      [{ ...manifest.categories[0], name: "line\nbreak" }],
      [{ ...manifest.categories[0], id: "invalid" }],
      [{ ...manifest.categories[0], archived: "false" }],
    ])
      assert.equal(
        (
          await transfer(target, "prepare", {
            manifest: { ...manifest, categories },
          })
        ).status,
        400,
      );
    const collision = randomUUID();
    assert.equal(
      (
        await target.admin("category.create", {
          target: collision,
          name: "Garden",
        })
      ).status,
      200,
    );
    assert.equal((await transfer(target, "prepare", { manifest })).status, 409);
    assert.equal((await target.snapshot()).categories.length, 1);
    assert.equal(
      (
        await target.admin("category.delete", {
          target: collision,
          revision: 1,
          confirmation: "DELETE CATEGORY",
        })
      ).status,
      200,
    );
    const oversized = {
      ...manifest,
      categories: Array.from({ length: 65 }, (_, i) => ({
        id: randomUUID(),
        name: "Category " + i,
        archived: false,
        deleted: false,
      })),
    };
    assert.equal(
      (await transfer(target, "prepare", { manifest: oversized })).status,
      413,
    );
    assert.deepEqual((await target.snapshot()).categories, []);
    let r = await transfer(target, "prepare", { manifest });
    assert.equal(r.status, 200, JSON.stringify(r.data));
    const plan = r.data.plan_id;
    const sql = (code) =>
      execFileSync("python3", [
        "-c",
        "import sqlite3,sys\ndb=sqlite3.connect(sys.argv[1])\ndb.executescript(sys.argv[2])\ndb.commit()",
        join(target.f.data, "field-notes.sqlite3"),
        code,
      ]);
    sql(
      "CREATE TRIGGER reject_import BEFORE INSERT ON objects BEGIN SELECT RAISE(ABORT,'injected failure'); END;",
    );
    assert.equal(
      (await transfer(target, "commit", { plan_id: plan })).status,
      500,
    );
    assert.deepEqual((await target.snapshot()).categories, []);
    assert.deepEqual((await target.snapshot()).records, []);
    sql("DROP TRIGGER reject_import;");
    assert.equal(
      (await transfer(target, "commit", { plan_id: plan })).status,
      200,
    );
    assert.equal(
      (await target.snapshot()).categories.find((c) => c.id === active).name,
      "Garden",
    );
    // Members can import content once definitions already exist; they cannot mutate them.
    r = await transfer(target, "prepare", { manifest }, member);
    assert.equal(r.status, 200, JSON.stringify(r.data));
    assert.equal(r.data.new_categories, 0);
    assert.equal(
      (await transfer(target, "commit", { plan_id: r.data.plan_id }, member))
        .status,
      200,
    );
  } finally {
    await source.f.close();
    await target.f.close();
  }
});
