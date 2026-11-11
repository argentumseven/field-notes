import { test } from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { runInNewContext } from "node:vm";
import { randomUUID } from "node:crypto";
import { fixture, password } from "./support.mjs";

const profiles = JSON.parse(
  await readFile("shared/item-contexts.json", "utf8"),
);
const cases = JSON.parse(
  await readFile("tests/fixtures/item-contexts.json", "utf8"),
);
test("web item contexts use the shared vocabulary and respect explicit unrestricted boundaries", async () => {
  const sandbox = {};
  runInNewContext(
    (await readFile("apps/admin/item-context-data.js", "utf8")) +
      "\n" +
      (await readFile("apps/admin/record-types.js", "utf8")) +
      "\nglobalThis.policy={itemContext,itemCategoryMatches,itemCategoryField};",
    sandbox,
  );
  for (const c of cases) {
    const actual = sandbox.policy.itemContext(c.data, c.parent);
    assert.equal(actual.key, c.expected, c.name);
    assert.deepEqual(
      Array.from(actual.choices),
      profiles[c.expected].choices,
      c.name,
    );
  }
  assert(sandbox.policy.itemCategoryMatches("*", "value:*"));
  assert(!sandbox.policy.itemCategoryMatches("Other", "value:*"));
  assert(sandbox.policy.itemCategoryMatches("", "none"));
  const legacy = sandbox.policy.itemCategoryField(
    sandbox.policy.itemContext({}, cases[0].parent),
    "A label from before",
  );
  assert(
    legacy.options.some(
      ([v, label]) =>
        v === "A label from before" && label.includes("saved label"),
    ),
  );
});

async function client() {
  const f = await fixture(),
    token = await f.login();
  const basis = (await f.request("/api/snapshot", { token })).data;
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
  const create = async (kind, data) => {
    const r = await send(kind + ".create", randomUUID(), data);
    assert.equal(r.status, 200, JSON.stringify(r.data));
    return r.data.record;
  };
  return {
    f,
    token,
    basis,
    send,
    create,
    snapshot: async () => (await f.request("/api/snapshot", { token })).data,
    call: (action, body) =>
      f.request("/api/transfer/" + action, {
        token,
        ...(body === undefined ? {} : { body }),
      }),
  };
}

test("specific list purposes accept their own choices and reject new unrelated choices atomically", async () => {
  const s = await client();
  try {
    for (const [key, profile] of Object.entries(profiles)) {
      const list = await s.create("list", {
        title: profile.label,
        template: ["groceries", "shopping"].includes(key) ? key : "general",
        category: ["general", "groceries", "shopping"].includes(key)
          ? "general"
          : key,
      });
      const item = await s.create("task", {
        title: "Example item",
        project_id: list.id,
        item_category: profile.choices[0] || "Anything at all",
      });
      assert.equal(
        item.data.item_category,
        profile.choices[0] || "Anything at all",
      );
      if (!profile.choices.length) continue;
      const before = await s.snapshot();
      const result = await s.send(
        "task.update",
        item.id,
        {
          title: "Must not replace the saved title",
          item_category: "Unrelated new category",
        },
        item.revision,
      );
      assert.equal(result.status, 400);
      const after = await s.snapshot();
      assert.equal(after.sequence, before.sequence);
      assert.equal(
        after.records.find((r) => r.id === item.id).data.title,
        "Example item",
      );
    }
  } finally {
    await s.f.close();
  }
});

test("general, custom and action lists remain free; moves, type changes and old clients preserve labels", async () => {
  const s = await client();
  try {
    const project = await s.create("project", {
      title: "Household project",
      category: "home",
    });
    for (const extra of [
      { template: "general", category: "general" },
      { template: "custom", category: "food" },
      { template: "general", category: "action" },
    ]) {
      const list = await s.create("list", {
        title: "Anything list",
        project_id: project.id,
        ...extra,
      });
      let item = await s.create("task", {
        title: "Untyped example",
        project_id: list.id,
        item_category: "Equipment / arbitrary label",
      });
      assert.equal(item.data.item_category, "Equipment / arbitrary label");
      const groceries = await s.create("list", {
        title: "Grocery run",
        template: "groceries",
      });
      let r = await s.send(
        "task.update",
        item.id,
        { project_id: groceries.id },
        item.revision,
      );
      assert.equal(r.status, 200);
      item = r.data.record;
      r = await s.send(
        "task.update",
        item.id,
        { notes: "Old client sends no new category field" },
        item.revision,
      );
      assert.equal(r.status, 200);
      item = r.data.record;
      assert.equal(item.data.item_category, "Equipment / arbitrary label");
      r = await s.send(
        "task.update",
        item.id,
        { item_category: item.data.item_category },
        item.revision,
      );
      assert.equal(r.status, 200);
      item = r.data.record;
      r = await s.send(
        "task.update",
        item.id,
        { item_category: "Produce" },
        item.revision,
      );
      assert.equal(r.status, 200);
      item = r.data.record;
      r = await s.send(
        "list.update",
        groceries.id,
        { template: "shopping" },
        groceries.revision,
      );
      assert.equal(r.status, 200);
      r = await s.send(
        "task.update",
        item.id,
        { item_category: "Produce", quantity: "2" },
        item.revision,
      );
      assert.equal(r.status, 200);
      item = r.data.record;
      assert.equal(item.data.item_category, "Produce");
      assert.equal(
        (
          await s.send(
            "task.update",
            item.id,
            { item_category: "Dairy & eggs" },
            item.revision,
          )
        ).status,
        400,
      );
    }
  } finally {
    await s.f.close();
  }
});

test("optional labels are bounded, Unicode-safe and protected against stale parent choices and stale item revisions", async () => {
  const s = await client();
  try {
    const list = await s.create("list", { title: "Work", category: "work" });
    const item = await s.create("task", {
      title: "Plan the change",
      project_id: list.id,
      item_category: "Planning",
    });
    const retag = await s.send(
      "list.update",
      list.id,
      { category: "home" },
      list.revision,
    );
    assert.equal(retag.status, 200);
    assert.equal(
      (
        await s.send(
          "task.update",
          item.id,
          { item_category: "Research" },
          item.revision,
        )
      ).status,
      400,
    );
    let r = await s.send(
      "task.update",
      item.id,
      { item_category: "Repairs" },
      item.revision,
    );
    assert.equal(r.status, 200);
    assert.equal(
      (
        await s.send(
          "task.update",
          item.id,
          { item_category: "Maintenance" },
          item.revision,
        )
      ).status,
      409,
    );
    const general = await s.create("list", { title: "自由" });
    assert.equal(
      (
        await s.create("task", {
          title: "Unicode",
          project_id: general.id,
          item_category: "字".repeat(80),
        })
      ).data.item_category.length,
      80,
    );
    for (const value of [
      null,
      false,
      {},
      "\n",
      "a".repeat(81),
      "字".repeat(81),
    ])
      assert.equal(
        (
          await s.send("task.create", randomUUID(), {
            title: "Reject invalid label",
            project_id: general.id,
            item_category: value,
          })
        ).status,
        400,
      );
    assert.equal(
      (
        await s.send(
          "task.update",
          item.id,
          { item_category: "" },
          r.data.record.revision,
        )
      ).status,
      200,
    );
  } finally {
    await s.f.close();
  }
});

test("portable content and retained whole-item versions preserve labels without creating duplicates", async () => {
  const s = await client(),
    t = await client();
  try {
    let item = await s.create("task", {
      title: "Keep the category",
      item_category: "A completely custom label",
    });
    const second = await s.send(
      "task.update",
      item.id,
      { item_category: "A newer label" },
      item.revision,
    );
    assert.equal(second.status, 200);
    const history = await s.f.request("/api/history", {
      token: s.token,
      body: {
        epoch: s.basis.epoch,
        generation: s.basis.generation,
        limit: 20,
        target: item.id,
      },
    });
    assert.equal(history.status, 200);
    assert(
      history.data.entries.some((e) =>
        e.changes.some(
          (c) => c.field === "item_category" && c.label === "Item category",
        ),
      ),
    );
    const exported = await s.call("export");
    assert.equal(exported.status, 200);
    assert.equal(
      exported.data.records.find((r) => r.id === item.id).data.item_category,
      "A newer label",
    );
    for (let n = 0; n < 2; n++) {
      const plan = await t.call("prepare", { manifest: exported.data });
      assert.equal(plan.status, 200, JSON.stringify(plan.data));
      const commit = await t.call("commit", { plan_id: plan.data.plan_id });
      assert.equal(commit.status, 200, JSON.stringify(commit.data));
    }
    const rows = (await t.snapshot()).records.filter((r) => r.kind === "task");
    assert.equal(rows.length, 1);
    assert.equal(rows[0].data.item_category, "A newer label");
    await s.f.request("/api/reauth", { token: s.token, body: { password } });
    const recover = (body) =>
      s.f.request("/api/recovery", {
        token: s.token,
        body: { epoch: s.basis.epoch, generation: s.basis.generation, ...body },
      });
    const versions = await recover({
      action: "versions",
      target: item.id,
      limit: 50,
    });
    assert.equal(versions.status, 200, JSON.stringify(versions.data));
    const saved = versions.data.entries.find((v) => v.index === 1);
    assert(saved);
    const preview = await recover({ action: "preview", source: saved.id });
    assert.equal(preview.status, 200, JSON.stringify(preview.data));
    assert(
      preview.data.item_state.saved.fields.some(
        (f) =>
          f.field === "item_category" &&
          f.value === "A completely custom label",
      ),
    );
    const restored = await recover({
      action: "apply",
      preview_id: preview.data.preview_id,
      operation_id: randomUUID(),
      confirmation: preview.data.confirmation_phrase,
      reviewed: true,
    });
    assert.equal(restored.status, 200, JSON.stringify(restored.data));
    assert.equal(
      (await s.snapshot()).records.find((r) => r.id === item.id).data
        .item_category,
      "A completely custom label",
    );
  } finally {
    await s.f.close();
    await t.f.close();
  }
});

test("legacy exports and explicit empty categories identify the same item on repeated import", async () => {
  const s = await client(),
    t = await client();
  try {
    const legacy = await s.create("task", {
      title: "Existing untagged item",
      notes: "No forced category",
    });
    const manifest = (await s.call("export")).data;
    assert(
      !(
        "item_category" in manifest.records.find((r) => r.id === legacy.id).data
      ),
    );
    for (let n = 0; n < 2; n++) {
      if (n)
        manifest.records.find((r) => r.id === legacy.id).data.item_category =
          "";
      const plan = await t.call("prepare", { manifest });
      assert.equal(plan.status, 200, JSON.stringify(plan.data));
      if (n) assert.equal(plan.data.new_items, 0);
      assert.equal(
        (await t.call("commit", { plan_id: plan.data.plan_id })).status,
        200,
      );
    }
    assert.equal(
      (await t.snapshot()).records.filter((r) => r.kind === "task").length,
      1,
    );
  } finally {
    await s.f.close();
    await t.f.close();
  }
});
