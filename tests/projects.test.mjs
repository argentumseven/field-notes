import { test } from "node:test";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { fixture, password } from "./support.mjs";
async function scene() {
  const f = await fixture(),
    token = await f.login(),
    basis = (await f.request("/api/snapshot", { token })).data;
  const send = (action, target, data = {}, revision = null) =>
    f.request("/api/commands", {
      token,
      body: {
        operation_id: randomUUID(),
        epoch: basis.epoch,
        generation: basis.generation,
        action,
        target,
        data,
        expected_revision: revision,
      },
    });
  const ok = async (...args) => {
    const r = await send(...args);
    assert.equal(r.status, 200, JSON.stringify(r.data));
    return r.data.record;
  };
  const snap = async () => (await f.request("/api/snapshot", { token })).data;
  return {
    f,
    token,
    basis,
    send,
    ok,
    snap,
    history: (extra) =>
      f.request("/api/history", {
        token,
        body: { epoch: basis.epoch, generation: basis.generation, ...extra },
      }),
  };
}

test("household category is independent of project/work-list grouping and moves preserve whole tasks", async () => {
  const s = await scene();
  try {
    const p = await s.ok("project.create", randomUUID(), {
        title: "Bathroom repairs",
        category: "home",
        due: "2026-12-20",
      }),
      q = await s.ok("project.create", randomUUID(), {
        title: "Garden",
        category: "food",
      });
    const list = await s.ok("list.create", randomUUID(), {
      title: "Preparation",
      project_id: p.id,
      items_are_tasks: true,
    });
    assert.equal(list.data.category, "home");
    const task = await s.ok("task.create", randomUUID(), {
      title: "Measure the bath",
      notes: "Keep these measurements",
      project_id: list.id,
      due: "2026-12-10",
    });
    assert.equal(task.data.category, "home");
    const own = await s.ok("task.create", randomUUID(), {
      title: "Call the plumber",
      project_id: p.id,
      category: "personal",
    });
    assert.equal(own.data.category, "personal");
    const standalone = await s.ok("task.create", randomUUID(), {
      title: "Fix the tap",
      category: "home",
    });
    assert.equal(standalone.data.project_id, "");
    assert.equal(standalone.data.due, "");
    const comment = await s.ok("comment.create", randomUUID(), {
      task_id: task.id,
      body: "Check both corners",
    });
    await s.ok("list.update", list.id, { project_id: q.id }, list.revision);
    let snapshot = await s.snap();
    const moved = snapshot.records.find((r) => r.id === task.id);
    assert.equal(moved.revision, task.revision);
    assert.equal(moved.data.notes, task.data.notes);
    assert.equal(moved.data.category, "home");
    assert.equal(moved.data.due, "2026-12-10");
    assert(snapshot.records.some((r) => r.id === comment.id));
    for (const root of [p, q]) {
      const h = await s.history({ view: "items", target: root.id });
      assert.equal(h.status, 200);
      assert(h.data.entries.some((e) => e.target === list.id));
    }
    await s.ok("task.update", task.id, { project_id: "" }, task.revision);
    snapshot = await s.snap();
    assert.equal(
      snapshot.records.find((r) => r.id === task.id).data.notes,
      "Keep these measurements",
    );
    assert(snapshot.records.some((r) => r.id === comment.id));
    assert.equal(
      (await s.send("project.delete", q.id, {}, q.revision)).status,
      409,
    );
    assert.equal(
      (
        await s.send(
          "list.update",
          list.id,
          { project_id: list.id },
          list.revision + 1,
        )
      ).status,
      400,
    );
    assert.equal(
      (await s.send("project.update", p.id, { due: 7 }, p.revision)).status,
      400,
    );
  } finally {
    await s.f.close();
  }
});

test("project pause and finish are fenced against direct and grouped work; reference shopping does not inflate completion", async () => {
  const s = await scene();
  try {
    let p = await s.ok("project.create", randomUUID(), {
      title: "Honey-do weekend",
    });
    const group = await s.ok("list.create", randomUUID(), {
        title: "Repairs",
        project_id: p.id,
        items_are_tasks: true,
      }),
      ref = await s.ok("list.create", randomUUID(), {
        title: "Parts shopping",
        project_id: p.id,
        template: "shopping",
      });
    let task = await s.ok("task.create", randomUUID(), {
      title: "Repair shelf",
      project_id: group.id,
    });
    await s.ok("task.create", randomUUID(), {
      title: "Spare screws",
      project_id: ref.id,
    });
    assert.equal(
      (await s.send("project.update", p.id, { status: "archived" }, p.revision))
        .status,
      409,
    );
    assert.equal(
      (await s.snap()).records.find((r) => r.id === p.id).revision,
      p.revision,
    );
    p = await s.ok("project.update", p.id, { status: "on_hold" }, p.revision);
    assert.equal(
      (await s.send("task.start", task.id, {}, task.revision)).status,
      409,
    );
    p = await s.ok("project.update", p.id, { status: "active" }, p.revision);
    task = await s.ok("task.start", task.id, {}, task.revision);
    assert.equal(
      (await s.send("project.update", p.id, { status: "on_hold" }, p.revision))
        .status,
      409,
    );
    task = await s.ok("task.complete", task.id, {}, task.revision);
    p = await s.ok("project.update", p.id, { status: "archived" }, p.revision);
    assert.equal(
      (
        await s.send("task.create", randomUUID(), {
          title: "New work",
          project_id: group.id,
        })
      ).status,
      409,
    );
    assert.equal(
      (await s.send("task.reopen", task.id, {}, task.revision)).status,
      409,
    );
    await s.ok("task.create", randomUUID(), {
      title: "Receipt envelope",
      project_id: ref.id,
    });
    p = await s.ok("project.update", p.id, { status: "active" }, p.revision);
    task = await s.ok("task.reopen", task.id, {}, task.revision);
    assert.equal(task.data.status, "todo");
    assert.equal(
      (await s.snap()).records.find((r) => r.id === ref.id).data.project_id,
      p.id,
    );
    task = await s.ok("task.complete", task.id, {}, task.revision);
    const race = await Promise.all([
      s.send("project.update", p.id, { status: "archived" }, p.revision),
      s.send("task.reopen", task.id, {}, task.revision),
    ]);
    assert.deepEqual(race.map((r) => r.status).sort(), [200, 409]);
    const current = await s.snap(),
      root = current.records.find((r) => r.id === p.id),
      child = current.records.find((r) => r.id === task.id);
    assert(root.data.status !== "archived" || child.data.status === "done");
  } finally {
    await s.f.close();
  }
});

test("nested groups export/import without duplicates, keep task history, and restore missing project/list ancestors", async () => {
  const s = await scene(),
    other = await scene();
  try {
    const p = await s.ok("project.create", randomUUID(), {
        title: "Kitchen project",
        category: "home",
      }),
      list = await s.ok("list.create", randomUUID(), {
        title: "Honey-do tasks",
        project_id: p.id,
        items_are_tasks: true,
      });
    let task = await s.ok("task.create", randomUUID(), {
      title: "Fit handles",
      project_id: list.id,
      notes: "Keep original notes",
    });
    const comment = await s.ok("comment.create", randomUUID(), {
      task_id: task.id,
      body: "Use brass screws",
    });
    task = await s.ok("task.complete", task.id, {}, task.revision);
    await s.ok("project.update", p.id, { status: "archived" }, p.revision);
    const manifest = (
      await s.f.request("/api/transfer/export", { token: s.token })
    ).data;
    assert.equal(manifest.version, 2);
    for (let pass = 0; pass < 2; pass++) {
      const plan = await other.f.request("/api/transfer/prepare", {
        token: other.token,
        body: { manifest },
      });
      assert.equal(plan.status, 200, JSON.stringify(plan.data));
      assert.equal(plan.data.new_items, pass ? 0 : 4);
      const result = await other.f.request("/api/transfer/commit", {
        token: other.token,
        body: { plan_id: plan.data.plan_id },
      });
      assert.equal(result.status, 200, JSON.stringify(result.data));
    }
    const imported = await other.snap();
    assert.equal(imported.records.length, 4);
    const t = imported.records.find((r) => r.data.title === "Fit handles");
    const l = imported.records.find((r) => r.id === t.data.project_id);
    assert.equal(l.data.items_are_tasks, true);
    assert.equal(
      imported.records.find((r) => r.id === l.data.project_id).data.status,
      "archived",
    );
    const r = await other.f.request("/api/recovery", {
      token: other.token,
      body: {
        action: "versions",
        epoch: other.basis.epoch,
        generation: other.basis.generation,
        target: t.id,
      },
    });
    assert.equal(r.data.entries.length, 1, "one final imported item state");
    const rec = async (action, data = {}) =>
      s.f.request("/api/recovery", {
        token: s.token,
        body: {
          action,
          epoch: s.basis.epoch,
          generation: s.basis.generation,
          ...data,
        },
      });
    const versions = (await rec("versions", { target: task.id })).data.entries;
    const source = versions[0];
    await s.ok("task.delete", task.id, {}, task.revision);
    await s.ok("list.delete", list.id, {}, list.revision);
    await s.ok("project.delete", p.id, {}, p.revision + 1);
    const preview = await rec("preview", { source: source.id });
    assert.equal(preview.status, 200, JSON.stringify(preview.data));
    const restored = await rec("apply", {
      preview_id: preview.data.preview_id,
      operation_id: randomUUID(),
      confirmation: "RESTORE ITEM",
    });
    assert.equal(restored.status, 200, JSON.stringify(restored.data));
    const snapshot = await s.snap();
    for (const id of [p.id, list.id, task.id, comment.id])
      assert(snapshot.records.some((r) => r.id === id));
    assert.equal(
      snapshot.records.find((r) => r.id === list.id).data.project_id,
      p.id,
    );
    assert.equal(
      snapshot.records.find((r) => r.id === task.id).data.notes,
      "Keep original notes",
    );
  } finally {
    await s.f.close();
    await other.f.close();
  }
});

test("project rollups distinguish work, references and standalone honey-do groupings", async () => {
  const { readFile } = await import("node:fs/promises"),
    vm = await import("node:vm");
  const fixture = JSON.parse(
    await readFile("tests/fixtures/projects.json", "utf8"),
  );
  const context = vm.createContext({});
  vm.runInContext(await readFile("apps/admin/projects.js", "utf8"), context);
  const Graph = vm.runInContext("FieldProjects.Graph", context);
  const graph = new Graph(fixture.records),
    project = graph.byId.get("p");
  assert.deepEqual(
    JSON.parse(JSON.stringify(graph.rollup(project, fixture.today))),
    fixture.expected,
  );
  assert.deepEqual(
    graph.tasks
      .filter((t) => graph.planner(t))
      .map((t) => t.id)
      .sort(),
    fixture.planner,
  );
  assert.equal(graph.path("w"), "Bathroom repairs › Preparation");
  assert.equal(graph.project(graph.byId.get("shopping")).id, "p");
  assert.equal(graph.workProject(graph.byId.get("shopping")), null);
  const moved = fixture.records.map((r) =>
    r.id === "w" ? { ...r, data: { ...r.data, project_id: "q" } } : r,
  );
  const next = new Graph(moved);
  assert.equal(next.rollup(next.byId.get("p"), fixture.today).total, 2);
  assert.equal(next.rollup(next.byId.get("q"), fixture.today).total, 3);
  assert.equal(next.byId.get("doing").data.category, "home");
});

test("project coordinator and group moves preserve assignments and active claim ownership", async () => {
  const s = await scene();
  try {
    await s.f.request("/api/reauth", { token: s.token, body: { password } });
    for (const [name, role] of [
      ["Sophia", "member"],
      ["Reader", "viewer"],
    ])
      assert.equal(
        (
          await s.f.request("/api/admin", {
            token: s.token,
            body: { action: "user.create", name, role, password },
          })
        ).status,
        200,
      );
    const people = (await s.f.request("/api/people", { token: s.token })).data
        .people,
      sophia = people.find((p) => p.name === "Sophia").id,
      viewer = people.find((p) => p.name === "Reader").id;
    let p = await s.ok("project.create", randomUUID(), {
      title: "Household work",
      assignee: sophia,
    });
    const group = await s.ok("list.create", randomUUID(), {
      title: "Weekend work",
      project_id: p.id,
      items_are_tasks: true,
    });
    let task = await s.ok("task.create", randomUUID(), {
      title: "Replace filter",
      project_id: group.id,
    });
    assert.equal(
      task.data.assignee,
      null,
      "coordination does not silently assign every task",
    );
    const memberToken = await s.f.login("Sophia");
    const sendMember = (action, target, data, revision) =>
      s.f.request("/api/commands", {
        token: memberToken,
        body: {
          operation_id: randomUUID(),
          epoch: s.basis.epoch,
          generation: s.basis.generation,
          action,
          target,
          data,
          expected_revision: revision,
        },
      });
    task = await s.ok("task.start", task.id, {}, task.revision);
    assert.equal(
      (
        await sendMember(
          "task.update",
          task.id,
          { project_id: "" },
          task.revision,
        )
      ).status,
      403,
      "moving must not steal another person’s claim",
    );
    assert.equal(
      (
        await s.send(
          "list.update",
          group.id,
          { items_are_tasks: false },
          group.revision,
        )
      ).status,
      409,
      "active work cannot be hidden as a reference item",
    );
    assert.equal(
      (await s.snap()).records.find((r) => r.id === group.id).revision,
      group.revision,
    );
    assert.equal(
      (await s.send("project.update", p.id, { assignee: viewer }, p.revision))
        .status,
      400,
    );
    const reader = await s.f.login("Reader");
    assert.equal(
      (
        await s.f.request("/api/commands", {
          token: reader,
          body: {
            operation_id: randomUUID(),
            epoch: s.basis.epoch,
            generation: s.basis.generation,
            action: "project.update",
            target: p.id,
            data: { status: "on_hold" },
            expected_revision: p.revision,
          },
        })
      ).status,
      403,
    );
    task = await s.ok("task.release", task.id, {}, task.revision);
    await s.ok(
      "list.update",
      group.id,
      { items_are_tasks: false },
      group.revision,
    );
    assert.equal(
      (await s.snap()).records.find((r) => r.id === task.id).revision,
      task.revision,
      "conversion changes the grouping, not the child state",
    );
    await s.f.request("/api/reauth", { token: s.token, body: { password } });
    assert.equal(
      (
        await s.f.request("/api/admin", {
          token: s.token,
          body: { action: "user.disable", target: sophia },
        })
      ).status,
      200,
    );
    p = await s.ok(
      "project.update",
      p.id,
      { notes: "Coordinator history is kept", assignee: sophia },
      p.revision,
    );
    assert.equal(p.data.assignee, sophia);
  } finally {
    await s.f.close();
  }
});

test("restoring unfinished task state cannot reopen a finished project implicitly", async () => {
  const s = await scene();
  try {
    let p = await s.ok("project.create", randomUUID(), {
        title: "Finished repair",
      }),
      task = await s.ok("task.create", randomUUID(), {
        title: "Fix hinge",
        project_id: p.id,
      });
    const rec = (action, data = {}) =>
      s.f.request("/api/recovery", {
        token: s.token,
        body: {
          action,
          epoch: s.basis.epoch,
          generation: s.basis.generation,
          ...data,
        },
      });
    const todo = (await rec("versions", { target: task.id })).data.entries[0];
    task = await s.ok("task.complete", task.id, {}, task.revision);
    p = await s.ok("project.update", p.id, { status: "archived" }, p.revision);
    const failed = await rec("preview", { source: todo.id });
    assert.equal(failed.status, 409, JSON.stringify(failed.data));
    let snapshot = await s.snap();
    assert.equal(
      snapshot.records.find((r) => r.id === task.id).data.status,
      "done",
    );
    assert.equal(
      snapshot.records.find((r) => r.id === p.id).data.status,
      "archived",
    );
    p = await s.ok("project.update", p.id, { status: "active" }, p.revision);
    const preview = await rec("preview", { source: todo.id });
    assert.equal(preview.status, 200, JSON.stringify(preview.data));
    const applied = await rec("apply", {
      preview_id: preview.data.preview_id,
      operation_id: randomUUID(),
      confirmation: "RESTORE ITEM",
    });
    assert.equal(applied.status, 200, JSON.stringify(applied.data));
    snapshot = await s.snap();
    assert.equal(
      snapshot.records.find((r) => r.id === task.id).data.status,
      "todo",
    );
  } finally {
    await s.f.close();
  }
});

test("version-one archives remain readable and default grouping fields deduplicate on reimport", async () => {
  const s = await scene(),
    other = await scene();
  try {
    await s.ok("project.create", randomUUID(), { title: "Legacy project" });
    await s.ok("list.create", randomUUID(), { title: "Legacy checklist" });
    const manifest = (
      await s.f.request("/api/transfer/export", { token: s.token })
    ).data;
    manifest.version = 1;
    for (const item of manifest.records) {
      if (item.kind === "project")
        for (const key of ["status", "due", "assignee"]) delete item.data[key];
      if (item.kind === "list")
        for (const key of ["project_id", "items_are_tasks"])
          delete item.data[key];
    }
    for (let i = 0; i < 2; i++) {
      const plan = await other.f.request("/api/transfer/prepare", {
        token: other.token,
        body: { manifest },
      });
      assert.equal(plan.status, 200, JSON.stringify(plan.data));
      assert.equal(plan.data.new_items, i ? 0 : 2);
      assert.equal(
        (
          await other.f.request("/api/transfer/commit", {
            token: other.token,
            body: { plan_id: plan.data.plan_id },
          })
        ).status,
        200,
      );
    }
    assert.equal((await other.snap()).records.length, 2);
  } finally {
    await s.f.close();
    await other.f.close();
  }
});
