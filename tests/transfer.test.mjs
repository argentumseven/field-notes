import { test } from "node:test";
import assert from "node:assert/strict";
import { randomUUID, createHash } from "node:crypto";
import { deflateSync } from "node:zlib";
import { fixture, password } from "./support.mjs";
function png() {
  const crc = (b) => {
    let c = 0xffffffff;
    for (const byte of b) {
      c ^= byte;
      for (let i = 0; i < 8; i++) c = (c >>> 1) ^ (0xedb88320 & -(c & 1));
    }
    return (c ^ 0xffffffff) >>> 0;
  };
  const chunk = (type, data) => {
    const b = Buffer.concat([Buffer.from(type), data]),
      n = Buffer.alloc(4),
      h = Buffer.alloc(4);
    n.writeUInt32BE(data.length);
    h.writeUInt32BE(crc(b));
    return Buffer.concat([n, b, h]);
  };
  const h = Buffer.alloc(13);
  h.writeUInt32BE(1);
  h.writeUInt32BE(1, 4);
  h[8] = 8;
  h[9] = 2;
  return Buffer.concat([
    Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]),
    chunk("IHDR", h),
    chunk("IDAT", deflateSync(Buffer.from([0, 250, 120, 100]))),
    chunk("IEND", Buffer.alloc(0)),
  ]);
}
async function client(f) {
  const token = await f.login(),
    snapshot = (await f.request("/api/snapshot", { token })).data;
  return {
    token,
    snapshot,
    send: (action, target, data = {}, revision = null) =>
      f.request("/api/commands", {
        token,
        body: {
          operation_id: randomUUID(),
          epoch: snapshot.epoch,
          generation: snapshot.generation,
          action,
          target,
          expected_revision: revision,
          data,
        },
      }),
    call: (action, body) =>
      f.request("/api/transfer/" + action, {
        token,
        ...(body === undefined ? {} : { body }),
      }),
  };
}
async function seed(c) {
  const list = randomUUID(),
    task = randomUUID(),
    note = randomUUID(),
    event = randomUUID(),
    image = randomUUID();
  for (const [action, id, data] of [
    [
      "list.create",
      list,
      {
        title: "Costco Shopping List",
        template: "groceries",
        category: "food",
        notify_additions: false,
        item_reminders: false,
      },
    ],
    [
      "task.create",
      task,
      {
        title: "Bananas",
        project_id: list,
        store: "Costco",
        quantity: "2 bunches",
        notes: "Not too ripe",
        category: "food",
        assignee: c.snapshot.actor.user_id,
      },
    ],
    [
      "note.create",
      note,
      {
        title: "Greenhouse someday",
        body: "Compare winter heating options",
        on_radar: true,
        remind_at: 1794078000,
      },
    ],
    [
      "event.create",
      event,
      {
        title: "Family plan",
        all_day: false,
        starts_at: 1794078000,
        ends_at: null,
        recurrence: {
          frequency: "weekly",
          interval: 2,
          weekdays: [1, 3, 5],
          dates: [],
          until: "",
          excluded: [],
          zone: "America/New_York",
        },
      },
    ],
    [
      "comment.create",
      randomUUID(),
      { task_id: task, body: "Sophia prefers these" },
    ],
    [
      "image.create",
      image,
      { task_id: task, name: "bananas.png", bytes: png().toString("base64") },
    ],
  ]) {
    const r = await c.send(action, id, data);
    assert.equal(r.status, 200, JSON.stringify(r.data));
  }
  assert.equal((await c.send("task.complete", task, {}, 1)).status, 200);
  return { list, task, note, event, image };
}
async function upload(source, target, manifest, plan) {
  for (const id of plan.needed_media) {
    const bytes = (
      await source.request("/api/images/" + id, { token: await source.login() })
    ).data;
    assert(Buffer.isBuffer(bytes));
    assert.equal(
      createHash("sha256").update(bytes).digest("hex"),
      manifest.media.find((m) => m.id === id).sha256,
    );
    for (
      let index = 0;
      index < Math.ceil(bytes.length / plan.chunk_size);
      index++
    ) {
      const r = await target.call("image", {
        plan_id: plan.plan_id,
        image_id: id,
        index,
        bytes: bytes
          .subarray(index * plan.chunk_size, (index + 1) * plan.chunk_size)
          .toString("base64"),
      });
      assert.equal(r.status, 200, JSON.stringify(r.data));
    }
  }
}

test("portable round-trip preserves planner content and images, skips duplicates, and retries a committed receipt safely", async () => {
  const a = await fixture(),
    b = await fixture();
  try {
    const source = await client(a),
      target = await client(b);
    await seed(source);
    const exported = await source.call("export");
    assert.equal(exported.status, 200);
    const manifest = exported.data;
    assert.equal(manifest.records.length, 6);
    assert.equal(manifest.media.length, 1);
    assert(!JSON.stringify(manifest).includes('hash"'));
    let r = await target.call("prepare", { manifest });
    assert.equal(r.status, 200, JSON.stringify(r.data));
    assert.equal(r.data.new_items, 6);
    const plan = r.data;
    assert.equal(
      (await b.request("/api/snapshot", { token: target.token })).data.records
        .length,
      0,
      "preview never commits workspace content",
    );
    await upload(a, target, manifest, plan);
    r = await target.call("commit", { plan_id: plan.plan_id });
    assert.equal(r.status, 200, JSON.stringify(r.data));
    assert.equal(r.data.imported, 6);
    const repeated = await target.call("commit", { plan_id: plan.plan_id });
    assert.equal(repeated.status, 200);
    assert.deepEqual(repeated.data, r.data);
    const saved = (await b.request("/api/snapshot", { token: target.token }))
        .data,
      task = saved.records.find((r) => r.kind === "task"),
      list = saved.records.find((r) => r.kind === "list"),
      note = saved.records.find((r) => r.kind === "note");
    assert.equal(saved.records.length, 6);
    const trail = await b.request("/api/history", {
      token: target.token,
      body: { epoch: saved.epoch, generation: saved.generation },
    });
    assert.equal(trail.status, 200);
    assert.equal(
      trail.data.entries.length,
      7,
      "six imports and their accepted completion; a receipt retry adds no events",
    );
    assert(
      trail.data.entries.every(
        (entry) =>
          entry.source === "import" && entry.actor_id === saved.actor.user_id,
      ),
      "imports cannot claim another actor's authoritative history",
    );
    assert.equal(task.data.status, "done");
    assert.equal(task.data.store, "Costco");
    assert.equal(task.data.assignee, target.snapshot.actor.user_id);
    assert.equal(task.data.project_id, list.id);
    assert.equal(list.data.notify_additions, false);
    assert.equal(note.data.body, "Compare winter heating options");
    assert.equal(note.data.on_radar, true);
    assert.equal(
      saved.records.find((r) => r.kind === "event").data.recurrence.interval,
      2,
    );
    const restoredImage = saved.records.find((r) => r.kind === "image");
    assert.equal(
      createHash("sha256")
        .update(
          (
            await b.request("/api/images/" + restoredImage.id, {
              token: target.token,
            })
          ).data,
        )
        .digest("hex"),
      manifest.media[0].sha256,
    );
    r = await target.call("prepare", { manifest });
    assert.equal(r.status, 200, JSON.stringify(r.data));
    assert.equal(r.data.new_items, 0);
    assert.equal(r.data.already_present, 6);
    assert.equal(r.data.retained_changes, 0, JSON.stringify(r.data));
    await target.call("commit", { plan_id: r.data.plan_id });
    assert.equal(
      (await b.request("/api/snapshot", { token: target.token })).data.records
        .length,
      6,
    );
  } finally {
    await a.close();
    await b.close();
  }
});
test("a stale archive keeps current edits and retains the incoming alternative once", async () => {
  const f = await fixture();
  try {
    const c = await client(f),
      id = randomUUID();
    let r = await c.send("note.create", id, {
      title: "A family idea",
      body: "Original context",
    });
    assert.equal(r.status, 200);
    const manifest = (await c.call("export")).data;
    r = await c.send("note.update", id, { body: "Newer context must stay" }, 1);
    assert.equal(r.status, 200);
    for (let pass = 0; pass < 2; pass++) {
      r = await c.call("prepare", { manifest });
      assert.equal(r.status, 200, JSON.stringify(r.data));
      assert.equal(r.data.new_items, 0);
      assert.equal(r.data.retained_changes, pass === 0 ? 1 : 0);
      r = await c.call("commit", { plan_id: r.data.plan_id });
      assert.equal(r.status, 200, JSON.stringify(r.data));
    }
    const saved = (await f.request("/api/snapshot", { token: c.token })).data
      .records;
    assert.equal(saved.length, 1);
    assert.equal(saved[0].data.body, "Newer context must stay");
    const recovered = (await c.call("recovered")).data.items;
    assert.equal(recovered.length, 1);
    assert.equal(recovered[0].payload.record.data.body, "Original context");
    const discarded = await c.call("discard", { id: recovered[0].id });
    assert.equal(discarded.status, 200);
    assert.equal((await c.call("recovered")).data.items.length, 0);
    assert.equal(
      (await f.request("/api/snapshot", { token: c.token })).data.records[0]
        .data.body,
      "Newer context must stay",
    );
    assert.equal(
      (await c.call("discard", { id: recovered[0].id })).status,
      200,
    );
  } finally {
    await f.close();
  }
});
test("identical content with different archive IDs is reused rather than imported twice", async () => {
  const a = await fixture(),
    b = await fixture();
  try {
    const source = await client(a),
      target = await client(b);
    const id = randomUUID();
    assert.equal(
      (
        await source.send("note.create", id, {
          title: "Shared reference",
          body: "Full text",
          category: "home",
        })
      ).status,
      200,
    );
    const manifest = (await source.call("export")).data;
    let r = await target.call("prepare", { manifest });
    assert.equal(r.status, 200, JSON.stringify(r.data));
    await target.call("commit", { plan_id: r.data.plan_id });
    manifest.source = randomUUID();
    manifest.records[0].id = randomUUID();
    r = await target.call("prepare", { manifest });
    assert.equal(r.status, 200, JSON.stringify(r.data));
    assert.equal(r.data.new_items, 0);
    assert.equal(r.data.already_present, 1);
    await target.call("commit", { plan_id: r.data.plan_id });
    assert.equal(
      (await b.request("/api/snapshot", { token: target.token })).data.records
        .length,
      1,
    );
  } finally {
    await a.close();
    await b.close();
  }
});
test("corrupt images, stale previews and invalid records cannot partially change the workspace", async () => {
  const a = await fixture(),
    b = await fixture();
  try {
    const source = await client(a),
      target = await client(b);
    await seed(source);
    const manifest = (await source.call("export")).data;
    let r = await target.call("prepare", { manifest });
    assert.equal(r.status, 200, JSON.stringify(r.data));
    const plan = r.data;
    r = await target.call("commit", { plan_id: plan.plan_id });
    assert.equal(r.status, 409);
    assert.equal(
      (await b.request("/api/snapshot", { token: target.token })).data.records
        .length,
      0,
    );
    await upload(a, target, manifest, plan);
    assert.equal(
      (
        await target.send("note.create", randomUUID(), {
          title: "Keep this",
          body: "Written during import",
        })
      ).status,
      200,
    );
    r = await target.call("commit", { plan_id: plan.plan_id });
    assert.equal(r.status, 409);
    assert.equal(
      (await b.request("/api/snapshot", { token: target.token })).data.records
        .length,
      1,
    );
    r = await target.call("review", { plan_id: plan.plan_id });
    assert.equal(r.status, 200, JSON.stringify(r.data));
    r = await target.call("commit", { plan_id: plan.plan_id });
    assert.equal(r.status, 200, JSON.stringify(r.data));
    const invalid = structuredClone(manifest);
    invalid.source = randomUUID();
    invalid.records.find((r) => r.kind === "note").data.future_field =
      "unknown";
    r = await target.call("prepare", { manifest: invalid });
    assert.equal(r.status, 400);
    assert.equal(
      (await b.request("/api/snapshot", { token: target.token })).data.records
        .length,
      7,
    );
  } finally {
    await a.close();
    await b.close();
  }
});
test("portable files never import accounts, pairing secrets or cross-session preview ownership", async () => {
  const f = await fixture();
  try {
    const c = await client(f),
      id = randomUUID();
    await c.send("note.create", id, {
      title: "Private household context",
      body: "A reference",
    });
    const manifest = (await c.call("export")).data;
    manifest.source = randomUUID();
    manifest.records[0].id = randomUUID();
    manifest.records[0].data.title = "Another reference";
    let r = await c.call("prepare", { manifest });
    assert.equal(r.status, 200, JSON.stringify(r.data));
    const plan = r.data;
    await f.request("/api/reauth", { token: c.token, body: { password } });
    assert.equal(
      (
        await f.request("/api/admin", {
          token: c.token,
          body: {
            action: "user.create",
            name: "Sophia",
            role: "member",
            password,
          },
        })
      ).status,
      200,
    );
    const other = await f.login("Sophia");
    r = await f.request("/api/transfer/commit", {
      token: other,
      body: { plan_id: plan.plan_id },
    });
    assert.equal(r.status, 404);
    await c.call("cancel", { plan_id: plan.plan_id });
    const malicious = {
      ...manifest,
      users: [
        { name: "admin", role: "owner", password: "should-never-import" },
      ],
    };
    assert.equal(
      (await c.call("prepare", { manifest: malicious })).status,
      400,
    );
  } finally {
    await f.close();
  }
});

test("corrupt retained images roll back content and recovered copies; restoration is idempotent across restart", async () => {
  const f = await fixture();
  try {
    const c = await client(f),
      task = randomUUID();
    assert.equal(
      (await c.send("task.create", task, { title: "Existing task" })).status,
      200,
    );
    const image = randomUUID(),
      note = randomUUID(),
      bytes = png(),
      manifest = {
        format: "org.fieldnotes.archive",
        version: 1,
        source: randomUUID(),
        generation: 1,
        created_at: 1794000000,
        people: [],
        records: [
          {
            id: note,
            kind: "note",
            revision: 0,
            data: { title: "An offline idea", body: "Keep this context" },
          },
        ],
        media: [
          {
            id: image,
            size: bytes.length,
            sha256: createHash("sha256").update(bytes).digest("hex"),
          },
        ],
        retained: [
          {
            id: "offline-image",
            title: "A retained photo",
            payload: {
              record: {
                id: image,
                kind: "image",
                revision: 0,
                data: { task_id: task, name: "photo.png" },
              },
              reason: "Unsynchronized attachment",
            },
            media_id: image,
          },
        ],
      };
    let r = await c.call("prepare", { manifest });
    assert.equal(r.status, 200, JSON.stringify(r.data));
    let plan = r.data;
    const damaged = Buffer.from(bytes);
    damaged[20] ^= 1;
    assert.equal(
      (
        await c.call("image", {
          plan_id: plan.plan_id,
          image_id: image,
          index: 0,
          bytes: damaged.toString("base64"),
        })
      ).status,
      200,
    );
    r = await c.call("commit", { plan_id: plan.plan_id });
    assert.equal(r.status, 400);
    assert.match(r.data.error, /checksum/);
    assert.equal(
      (await f.request("/api/snapshot", { token: c.token })).data.records
        .length,
      1,
    );
    assert.equal((await c.call("recovered")).data.items.length, 0);
    await c.call("cancel", { plan_id: plan.plan_id });
    r = await c.call("prepare", { manifest });
    assert.equal(r.status, 200);
    plan = r.data;
    await c.call("image", {
      plan_id: plan.plan_id,
      image_id: image,
      index: 0,
      bytes: bytes.toString("base64"),
    });
    assert.equal(
      (await c.call("commit", { plan_id: plan.plan_id })).status,
      200,
    );
    const recovered = (await c.call("recovered")).data.items;
    assert.equal(recovered.length, 1);
    assert.equal(recovered[0].has_image, true);
    const restored = await c.call("restore", {
      id: recovered[0].id,
      parent_id: task,
    });
    assert.equal(restored.status, 200, JSON.stringify(restored.data));
    assert.equal(restored.data.restored, true);
    await f.stop();
    await f.start();
    const again = await c.call("restore", {
      id: recovered[0].id,
      parent_id: task,
    });
    assert.equal(again.status, 200);
    assert.equal(again.data.already_present, true);
    assert.equal(again.data.target, restored.data.target);
    assert.equal(
      (await f.request("/api/snapshot", { token: c.token })).data.records
        .length,
      3,
    );
  } finally {
    await f.close();
  }
});

test("deleted originals stay deleted, identical entries within one file do not multiply, and viewer imports are forbidden", async () => {
  const f = await fixture();
  try {
    const c = await client(f),
      id = randomUUID();
    await c.send("note.create", id, {
      title: "Original",
      body: "Retain this if it was removed",
    });
    const manifest = (await c.call("export")).data;
    await c.send("note.delete", id, {}, 1);
    let r = await c.call("prepare", { manifest });
    assert.equal(r.status, 200);
    assert.equal(r.data.new_items, 0);
    assert.equal(r.data.retained_changes, 1);
    await c.call("commit", { plan_id: r.data.plan_id });
    assert.equal(
      (await f.request("/api/snapshot", { token: c.token })).data.records
        .length,
      0,
    );
    const other = structuredClone(manifest);
    other.source = randomUUID();
    other.records[0].id = randomUUID();
    other.records.push({ ...other.records[0], id: randomUUID() });
    r = await c.call("prepare", { manifest: other });
    assert.equal(r.status, 200, JSON.stringify(r.data));
    assert.equal(r.data.new_items, 1);
    assert.equal(r.data.already_present, 1);
    assert.equal(
      (await c.call("commit", { plan_id: r.data.plan_id })).status,
      200,
    );
    assert.equal(
      (await f.request("/api/snapshot", { token: c.token })).data.records
        .length,
      1,
    );
    await f.request("/api/reauth", { token: c.token, body: { password } });
    await f.request("/api/admin", {
      token: c.token,
      body: { action: "user.create", name: "Reader", role: "viewer", password },
    });
    const token = await f.login("Reader");
    assert.equal(
      (await f.request("/api/transfer/prepare", { token, body: { manifest } }))
        .status,
      403,
    );
    assert.equal(
      (await f.request("/api/transfer/export", { token })).status,
      200,
    );
  } finally {
    await f.close();
  }
});

test("offline phone image bytes remain duplicate-safe after server PNG normalization", async () => {
  const f = await fixture();
  try {
    const c = await client(f),
      task = randomUUID(),
      image = randomUUID(),
      bytes = png(),
      manifest = {
        format: "org.fieldnotes.archive",
        version: 1,
        source: randomUUID(),
        generation: 1,
        created_at: 1794000000,
        people: [],
        retained: [],
        records: [
          {
            id: task,
            kind: "task",
            revision: 0,
            data: { title: "Offline phone task" },
          },
          {
            id: image,
            kind: "image",
            revision: 0,
            data: { task_id: task, name: "phone.png" },
          },
        ],
        media: [
          {
            id: image,
            size: bytes.length,
            sha256: createHash("sha256").update(bytes).digest("hex"),
          },
        ],
      };
    let r = await c.call("prepare", { manifest });
    assert.equal(r.status, 200, JSON.stringify(r.data));
    const plan = r.data;
    assert.equal(
      (
        await c.call("image", {
          plan_id: plan.plan_id,
          image_id: image,
          index: 0,
          bytes: bytes.toString("base64"),
        })
      ).status,
      200,
    );
    assert.equal(
      (await c.call("commit", { plan_id: plan.plan_id })).status,
      200,
    );
    r = await c.call("prepare", { manifest });
    assert.equal(r.status, 200);
    assert.equal(r.data.new_items, 0);
    assert.equal(r.data.already_present, 2);
    assert.equal(
      r.data.retained_changes,
      0,
      "normalizing a PNG must not manufacture an incoming conflict",
    );
  } finally {
    await f.close();
  }
});

test("imported open tasks do not resurrect working claims or create claim-only alternatives", async () => {
  const a = await fixture(),
    b = await fixture();
  try {
    const source = await client(a),
      target = await client(b),
      id = randomUUID();
    await source.send("task.create", id, { title: "Shared household work" });
    await source.send("task.start", id, {}, 1);
    const manifest = (await source.call("export")).data;
    for (let pass = 0; pass < 2; pass++) {
      const p = await target.call("prepare", { manifest });
      assert.equal(p.status, 200, JSON.stringify(p.data));
      assert.equal(p.data.new_items, pass === 0 ? 1 : 0);
      assert.equal(p.data.retained_changes, 0);
      assert.equal(
        (await target.call("commit", { plan_id: p.data.plan_id })).status,
        200,
      );
    }
    const rows = (await b.request("/api/snapshot", { token: target.token }))
      .data.records;
    assert.equal(rows.length, 1);
    assert.equal(rows[0].data.status, "todo");
    assert.equal(rows[0].data.claimant, null);
  } finally {
    await a.close();
    await b.close();
  }
});

test("imports keep mapped creator reminders and legacy assigned-task audiences", async () => {
  const source = await fixture(),
    target = await fixture();
  try {
    const from = await client(source),
      into = await client(target);
    assert.equal(
      (
        await target.request("/api/reauth", {
          token: into.token,
          body: { password },
        })
      ).status,
      200,
    );
    assert.equal(
      (
        await target.request("/api/admin", {
          token: into.token,
          body: {
            action: "user.create",
            name: "Sophia",
            role: "member",
            password,
          },
        })
      ).status,
      200,
    );
    const people = (await target.request("/api/people", { token: into.token }))
      .data.people;
    const sophia = people.find((p) => p.name === "Sophia").id;
    const personal = randomUUID(),
      legacy = randomUUID(),
      shared = randomUUID();
    for (const [id, data] of [
      [
        personal,
        {
          title: "Creator-only follow-up",
          reminder_options: {
            audience: { mode: "assigned", people: [] },
            playback: {},
          },
        },
      ],
      [
        legacy,
        {
          title: "Legacy personal task",
          assignee: from.snapshot.actor.user_id,
        },
      ],
      [shared, { title: "New shared task" }],
    ])
      assert.equal((await from.send("task.create", id, data)).status, 200);
    const manifest = (await from.call("export")).data;
    delete manifest.records.find((r) => r.id === legacy).data.reminder_options;
    const first = (await into.call("prepare", { manifest })).data;
    const reviewed = await into.call("review", {
      plan_id: first.plan_id,
      mapping: { [from.snapshot.actor.user_id]: sophia },
    });
    assert.equal(reviewed.status, 200, JSON.stringify(reviewed.data));
    assert.equal(
      (await into.call("commit", { plan_id: first.plan_id })).status,
      200,
    );
    const rows = (await target.request("/api/snapshot", { token: into.token }))
      .data.records;
    const data = (title) => rows.find((r) => r.data.title === title).data;
    assert.deepEqual(data("Creator-only follow-up").reminder_options.audience, {
      mode: "people",
      people: [sophia],
    });
    assert.equal(data("Legacy personal task").assignee, sophia);
    assert.equal(
      data("Legacy personal task").reminder_options.audience.mode,
      "assigned",
    );
    assert.equal(
      data("New shared task").reminder_options.audience.mode,
      "everyone",
    );
    const second = (await into.call("prepare", { manifest })).data;
    const again = (
      await into.call("review", {
        plan_id: second.plan_id,
        mapping: { [from.snapshot.actor.user_id]: sophia },
      })
    ).data;
    assert.equal(again.new_items, 0);
    assert.equal(again.already_present, 3);
    assert.equal(again.retained_changes, 0);
  } finally {
    await source.close();
    await target.close();
  }
});
