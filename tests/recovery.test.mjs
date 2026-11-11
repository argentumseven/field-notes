import { test } from "node:test";
import assert from "node:assert/strict";
import { randomUUID, randomBytes } from "node:crypto";
import { execFileSync } from "node:child_process";
import { readFile, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { deflateSync } from "node:zlib";
import { fixture, password } from "./support.mjs";

function png(width = 1, height = 1, noise = false) {
  const crc = (b) => {
    let c = 0xffffffff;
    for (const byte of b) {
      c ^= byte;
      for (let n = 0; n < 8; n++) c = (c >>> 1) ^ (0xedb88320 & -(c & 1));
    }
    return (c ^ 0xffffffff) >>> 0;
  };
  const chunk = (name, data) => {
    const bytes = Buffer.concat([Buffer.from(name), data]),
      head = Buffer.alloc(4),
      tail = Buffer.alloc(4);
    head.writeUInt32BE(data.length);
    tail.writeUInt32BE(crc(bytes));
    return Buffer.concat([head, bytes, tail]);
  };
  const head = Buffer.alloc(13);
  head.writeUInt32BE(width);
  head.writeUInt32BE(height, 4);
  head[8] = 8;
  head[9] = 2;
  const raw = Buffer.alloc((width * 3 + 1) * height);
  for (let row = 0; row < height; row++)
    (noise ? randomBytes(width * 3) : Buffer.alloc(width * 3, 125)).copy(
      raw,
      row * (width * 3 + 1) + 1,
    );
  return Buffer.concat([
    Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]),
    chunk("IHDR", head),
    chunk("IDAT", deflateSync(raw)),
    chunk("IEND", Buffer.alloc(0)),
  ]);
}
async function scene(elevated = true) {
  const f = await fixture();
  const token = await f.login();
  if (elevated)
    assert.equal(
      (await f.request("/api/reauth", { token, body: { password } })).status,
      200,
    );
  let basis = (await f.request("/api/snapshot", { token })).data;
  const send = (action, target, data = {}, revision = null, extra = {}) =>
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
        ...extra,
      },
    });
  const recover = (action, data = {}, auth = token) =>
    f.request("/api/recovery", {
      token: auth,
      body: {
        action,
        epoch: basis.epoch,
        generation: basis.generation,
        ...data,
      },
    });
  const snapshot = async () => {
    basis = (await f.request("/api/snapshot", { token })).data;
    return basis;
  };
  const status = async () => {
    const result = await recover("status");
    assert.equal(result.status, 200, JSON.stringify(result.data));
    return result.data;
  };
  const versions = async (target) => {
    const result = await recover("versions", { target, limit: 50 });
    assert.equal(result.status, 200, JSON.stringify(result.data));
    return result.data.entries;
  };
  const preview = async (source) => {
    const result = await recover("preview", { source });
    assert.equal(result.status, 200, JSON.stringify(result.data));
    return result.data;
  };
  const applyBody = (preview) => ({
    action: "apply",
    epoch: preview.epoch,
    generation: preview.generation,
    preview_id: preview.preview_id,
    operation_id: randomUUID(),
    confirmation: preview.confirmation_phrase,
    reviewed: true,
  });
  const apply = (body) => f.request("/api/recovery", { token, body });
  const reauth = () => f.request("/api/reauth", { token, body: { password } });
  const admin = (body) => f.request("/api/admin", { token, body });
  const checkpoint = async (name) => {
    const state = await status();
    const body = {
      action: "checkpoint.create",
      epoch: basis.epoch,
      generation: basis.generation,
      name,
      operation_id: randomUUID(),
      history_revision: state.history_revision,
    };
    const result = await f.request("/api/recovery", { token, body });
    assert.equal(result.status, 200, JSON.stringify(result.data));
    return { ...result.data, body };
  };
  const sql = (code) =>
    execFileSync(
      "python3",
      [
        "-c",
        "import sqlite3,sys\ndb=sqlite3.connect(sys.argv[1])\ndb.execute('PRAGMA foreign_keys=ON')\n" +
          code +
          "\ndb.commit()",
        join(f.data, "field-notes.sqlite3"),
      ],
      { encoding: "utf8" },
    );
  return {
    f,
    token,
    send,
    recover,
    snapshot,
    status,
    versions,
    preview,
    applyBody,
    apply,
    reauth,
    admin,
    checkpoint,
    sql,
    close: () => f.close(),
  };
}

test("complete indexed versions restore backward and forward without truncation or stale child writes", async () => {
  const s = await scene();
  try {
    const id = randomUUID(),
      old = "Original full notes · " + "a".repeat(6000),
      next = "New full notes · " + "b".repeat(6000);
    assert.equal(
      (await s.send("task.create", id, { title: "Annual review", notes: old }))
        .status,
      200,
    );
    assert.equal(
      (await s.send("task.update", id, { notes: next }, 1)).status,
      200,
    );
    const versions = await s.versions(id);
    assert.deepEqual(
      versions.map((v) => v.index),
      [2, 1],
    );
    const preview = await s.preview(versions[1].id);
    assert.equal(preview.source_index, 1);
    assert.equal(preview.checkpoint, false);
    assert(
      preview.changes[0].changes.find((f) => f.field === "notes").truncated,
    );
    const request = s.applyBody(preview),
      first = await s.apply(request);
    assert.equal(first.status, 200, JSON.stringify(first.data));
    let task = (await s.snapshot()).records.find((r) => r.id === id);
    assert.equal(task.data.notes, old);
    assert.equal(task.revision, 3);
    assert.equal(task.data.recovery_incarnation, 1);
    assert.deepEqual(
      (await s.apply(request)).data,
      first.data,
      "receipt retries cannot create another revision",
    );
    assert.equal(
      (await s.send("task.update", id, { notes: "stale update" }, 2)).status,
      409,
    );
    assert.equal(
      (
        await s.send("comment.create", randomUUID(), {
          task_id: id,
          body: "queued before restore",
        })
      ).status,
      409,
    );
    const forward = s.applyBody(await s.preview(versions[0].id));
    assert.equal((await s.apply(forward)).status, 200);
    task = (await s.snapshot()).records.find((r) => r.id === id);
    assert.equal(task.data.notes, next);
    assert.equal(task.revision, 4);
    assert.equal(task.data.recovery_incarnation, 2);
    assert.deepEqual(
      (await s.versions(id)).map((v) => v.index),
      [4, 3, 2, 1],
    );
    assert.equal(
      (
        await s.send("comment.create", randomUUID(), {
          task_id: id,
          body: "Reviewed after restore",
          parent_incarnation: 2,
        })
      ).status,
      200,
    );
    const exported = await s.f.request("/api/transfer/export", {
      token: s.token,
    });
    assert.equal(exported.status, 200);
    assert.equal(
      exported.data.records.find((r) => r.id === id).data.recovery_incarnation,
      undefined,
      "transport fences are not portable content",
    );
    const prepared = await s.f.request("/api/transfer/prepare", {
      token: s.token,
      body: { manifest: exported.data },
    });
    assert.equal(prepared.status, 200, JSON.stringify(prepared.data));
    assert.equal(prepared.data.new_items, 0);
    const data = await readFile(join(s.f.data, "field-notes.sqlite3"));
    assert(!data.includes(Buffer.from("Original full notes")));
    assert(!data.includes(Buffer.from("New full notes")));
  } finally {
    await s.close();
  }
});

test("deleted tasks recover their images, comments and missing collection with their original IDs", async () => {
  const s = await scene();
  try {
    const list = randomUUID(),
      task = randomUUID(),
      image = randomUUID(),
      comment = randomUUID();
    assert.equal(
      (await s.send("list.create", list, { title: "Household repairs" }))
        .status,
      200,
    );
    assert.equal(
      (
        await s.send("task.create", task, {
          title: "Repair shelf",
          notes: "Keep the original measurements",
          project_id: list,
        })
      ).status,
      200,
    );
    assert.equal(
      (
        await s.send("image.create", image, {
          task_id: task,
          name: "Shelf photo",
          bytes: png().toString("base64"),
        })
      ).status,
      200,
    );
    assert.equal(
      (
        await s.send("comment.create", comment, {
          task_id: task,
          body: "Use the shorter screws",
        })
      ).status,
      200,
    );
    const original = (
      await s.f.request("/api/images/" + image, { token: s.token })
    ).data;
    const old = (await s.versions(task))[0];
    assert.equal(
      (await s.send("list.update", list, { title: "Later household label" }, 1))
        .status,
      200,
    );
    assert.equal((await s.send("task.delete", task, {}, 1)).status, 200);
    assert.equal((await s.send("list.delete", list, {}, 2)).status, 200);
    assert.equal(
      (await s.f.request("/api/images/" + image, { token: s.token })).status,
      404,
    );
    const preview = await s.preview(old.id);
    assert.equal(preview.restored, 4);
    assert.equal(
      preview.item_state.saved.fields.find((f) => f.field === "project_id")
        .value,
      "Household repairs",
    );
    const restored = await s.apply(s.applyBody(preview));
    assert.equal(restored.status, 200, JSON.stringify(restored.data));
    const snapshot = await s.snapshot();
    for (const id of [list, task, image, comment])
      assert(
        snapshot.records.some((r) => r.id === id),
        id,
      );
    assert.deepEqual(
      (await s.f.request("/api/images/" + image, { token: s.token })).data,
      original,
    );
    assert.equal(
      snapshot.records.find((r) => r.id === task).data.notes,
      "Keep the original measurements",
    );
    assert.equal(
      snapshot.records.find((r) => r.id === comment).data.body,
      "Use the shorter screws",
    );
    assert.equal(
      s
        .sql(
          "print(db.execute('SELECT COUNT(*) FROM recovery_blobs').fetchone()[0])",
        )
        .trim(),
      "1",
      "versions share one retained image",
    );
    assert.equal(
      (
        await s.send("image.create", randomUUID(), {
          task_id: task,
          name: "stale photo",
          bytes: png().toString("base64"),
        })
      ).status,
      409,
    );
  } finally {
    await s.close();
  }
});

test("whole-content checkpoints roll backward and forward, retain authentication and fence old queues", async () => {
  const s = await scene(false);
  try {
    const task = randomUUID(),
      later = randomUUID();
    assert.equal(
      (
        await s.send("task.create", task, {
          title: "First state",
          notes: "At checkpoint",
        })
      ).status,
      200,
    );
    const originalEpoch = (await s.snapshot()).epoch,
      key = await readFile(s.f.key);
    const saved = await s.checkpoint("Before the trip");
    const { body, ...expectedSaved } = saved;
    assert.deepEqual(
      (await s.f.request("/api/recovery", { token: s.token, body })).data,
      expectedSaved,
      "checkpoint retries reuse the exact saved checkpoint",
    );
    assert.equal(
      (
        await s.send(
          "task.update",
          task,
          { notes: "Changed after checkpoint" },
          1,
        )
      ).status,
      200,
    );
    assert.equal(
      (
        await s.send("note.create", later, {
          title: "Later idea",
          body: "Keep this for roll-forward",
        })
      ).status,
      200,
    );
    const preview = await s.preview(saved.checkpoint_id),
      request = s.applyBody(preview);
    assert.equal(preview.checkpoint, true);
    assert.equal(preview.reauth_required, true);
    assert.equal(preview.fences_offline_writes, true);
    assert.equal((await s.apply(request)).status, 403);
    assert.equal((await s.reauth()).status, 200);
    const applied = await s.apply(request);
    assert.equal(applied.status, 200, JSON.stringify(applied.data));
    assert.equal(applied.data.generation, 2);
    assert(applied.data.before_checkpoint);
    assert.deepEqual(
      (await s.apply(request)).data,
      applied.data,
      "lost replies remain idempotent after generation advances",
    );
    let snapshot = await s.snapshot();
    assert.equal(snapshot.epoch, originalEpoch);
    assert.equal(
      snapshot.records.find((r) => r.id === task).data.notes,
      "At checkpoint",
    );
    assert(!snapshot.records.some((r) => r.id === later));
    assert.deepEqual(await readFile(s.f.key), key);
    assert.equal(
      (
        await s.send(
          "task.create",
          randomUUID(),
          { title: "Stale offline create" },
          null,
          { generation: 1 },
        )
      ).status,
      409,
    );
    const forward = await s.apply(
      s.applyBody(await s.preview(applied.data.before_checkpoint)),
    );
    assert.equal(forward.status, 200, JSON.stringify(forward.data));
    snapshot = await s.snapshot();
    assert.equal(snapshot.generation, 3);
    assert.equal(snapshot.epoch, originalEpoch);
    assert.equal(
      snapshot.records.find((r) => r.id === task).data.notes,
      "Changed after checkpoint",
    );
    assert(snapshot.records.some((r) => r.id === later));
    assert.equal(
      (await s.f.request("/api/people", { token: s.token })).status,
      200,
      "the original authenticated phone session remains valid",
    );
    const history = await s.f.request("/api/history", {
      token: s.token,
      body: { epoch: snapshot.epoch, generation: snapshot.generation },
    });
    assert.equal(history.status, 200);
    assert(
      history.data.entries.some((e) => e.action === "task.create"),
      "checkpoint restores retain the prior audit trail",
    );
  } finally {
    await s.close();
  }
});

test("preview races, session changes and expired previews never overwrite newer work", async () => {
  const s = await scene();
  try {
    const task = randomUUID();
    await s.send("task.create", task, { title: "Shared work", notes: "First" });
    await s.send("task.update", task, { notes: "Second" }, 1);
    const old = (await s.versions(task)).at(-1);
    let preview = await s.preview(old.id);
    assert.equal(
      (
        await s.send("comment.create", randomUUID(), {
          task_id: task,
          body: "Added while preview was open",
        })
      ).status,
      200,
    );
    assert.equal(
      (await s.apply(s.applyBody(preview))).status,
      409,
      "child edits conflict even though the task content revision did not change",
    );
    assert.equal(
      (await s.snapshot()).records.find((r) => r.id === task).data.notes,
      "Second",
    );
    preview = await s.preview(old.id);
    const otherSession = await s.f.login();
    assert.equal(
      (
        await s.f.request("/api/recovery", {
          token: otherSession,
          body: s.applyBody(preview),
        })
      ).status,
      403,
    );
    const rosterPreview = await s.preview(old.id);
    await s.reauth();
    assert.equal(
      (
        await s.admin({
          action: "user.create",
          name: "New household member",
          role: "member",
          password,
        })
      ).status,
      200,
    );
    assert.equal(
      (await s.apply(s.applyBody(rosterPreview))).status,
      409,
      "people changes invalidate the approved preview",
    );
    s.sql("db.execute('UPDATE recovery_previews SET expires=0')");
    assert.equal((await s.apply(s.applyBody(preview))).status, 410);
    assert.equal(
      (await s.snapshot()).records.find((r) => r.id === task).data.notes,
      "Second",
    );
  } finally {
    await s.close();
  }
});

test("a storage failure rolls back content, recovery indices and restore identities together", async () => {
  const s = await scene();
  try {
    const task = randomUUID();
    await s.send("task.create", task, { title: "Atomic change", notes: "One" });
    await s.send("task.update", task, { notes: "Two" }, 1);
    const versions = await s.versions(task),
      request = s.applyBody(await s.preview(versions.at(-1).id));
    s.sql(
      "db.execute(\"CREATE TRIGGER block_restore BEFORE UPDATE OF data ON objects BEGIN SELECT RAISE(ABORT,'private failure fixture'); END\")",
    );
    const failed = await s.apply(request);
    assert.equal(failed.status, 500);
    assert(!JSON.stringify(failed.data).includes("private failure"));
    const current = (await s.snapshot()).records.find((r) => r.id === task);
    assert.equal(current.data.notes, "Two");
    assert.equal(current.revision, 2);
    assert.equal(current.data.recovery_incarnation, undefined);
    assert.deepEqual(
      (await s.versions(task)).map((v) => v.index),
      [2, 1],
    );
    assert.equal(
      s
        .sql(
          "print(db.execute('SELECT COUNT(*) FROM recovery_receipts').fetchone()[0])",
        )
        .trim(),
      "0",
    );
    s.sql("db.execute('DROP TRIGGER block_restore')");
    assert.equal(
      (await s.apply(request)).status,
      200,
      "retrying the same request after rollback applies exactly once",
    );
    assert.equal(
      (await s.snapshot()).records.find((r) => r.id === task).revision,
      3,
    );
  } finally {
    await s.close();
  }
});

test("retention purges retained images only when their final snapshot reference is gone and never reuses version indices", async () => {
  const s = await scene();
  try {
    const task = randomUUID(),
      image = randomUUID();
    await s.send("task.create", task, {
      title: "Retained photo",
      notes: "First",
    });
    await s.send("image.create", image, {
      task_id: task,
      name: "Reference photo",
      bytes: png().toString("base64"),
    });
    await s.send("task.update", task, { notes: "Second" }, 1);
    const highest = (await s.versions(task))[0].index;
    const saved = await s.checkpoint("Keep the photograph");
    await s.reauth();
    let status = await s.status();
    assert(status.usage.image_bytes > 0);
    assert.equal(
      (
        await s.admin({
          action: "recovery.purge",
          scope: "versions",
          revision: status.history_revision,
          confirmation: "PURGE HISTORY",
        })
      ).status,
      200,
    );
    status = await s.status();
    assert(
      status.usage.image_bytes > 0,
      "a checkpoint still refers to the retained image",
    );
    const delayed = await s.f.request("/api/recovery", {
      token: s.token,
      body: saved.body,
    });
    assert.equal(
      delayed.status,
      409,
      "an old checkpoint request cannot become a duplicate after its receipt was purged",
    );
    assert.equal(
      (
        await s.admin({
          action: "recovery.purge",
          scope: "checkpoints",
          revision: status.history_revision,
          confirmation: "PURGE HISTORY",
        })
      ).status,
      200,
    );
    status = await s.status();
    assert.equal(status.usage.image_bytes, 0);
    assert.equal(
      (await s.f.request("/api/images/" + image, { token: s.token })).status,
      200,
      "current images survive recovery purging",
    );
    await s.send("task.update", task, { notes: "Third" }, 2);
    const versions = await s.versions(task);
    assert(versions.every((v) => v.index > highest));
    status = await s.status();
    assert.equal(
      (
        await s.admin({
          action: "recovery.policy",
          revision: status.history_revision,
          policy: { ...status.policy, versions_per_item: 2 },
        })
      ).status,
      200,
    );
    for (let revision = 3; revision < 7; revision++)
      assert.equal(
        (
          await s.send(
            "task.update",
            task,
            { notes: "Revision " + revision },
            revision,
          )
        ).status,
        200,
      );
    assert.equal((await s.versions(task)).length, 2);
  } finally {
    await s.close();
  }
});

test("workspace clearing invalidates old versions and checkpoints while purging history keeps sync sequence increasing", async () => {
  const s = await scene();
  try {
    const task = randomUUID();
    await s.send("task.create", task, { title: "Never resurrect this" });
    const version = (await s.versions(task))[0],
      checkpoint = await s.checkpoint("Private old state");
    const preview = await s.preview(version.id),
      old = await s.snapshot();
    await s.reauth();
    let status = await s.status();
    assert.equal(
      (
        await s.admin({
          action: "recovery.purge",
          scope: "history",
          revision: status.history_revision,
          confirmation: "PURGE HISTORY",
        })
      ).status,
      200,
    );
    const afterPurge = await s.snapshot();
    assert(afterPurge.sequence > old.sequence);
    assert.equal(afterPurge.generation, old.generation);
    const history = await s.f.request("/api/history", {
      token: s.token,
      body: { epoch: old.epoch, generation: old.generation },
    });
    assert.equal(history.status, 200);
    assert(!history.data.entries.some((e) => e.action === "task.create"));
    assert(
      [409, 410].includes((await s.apply(s.applyBody(preview))).status),
      "purging invalidates an open restore preview",
    );
    assert.equal(
      (
        await s.admin({
          action: "workspace.clear",
          confirmation: "CLEAR WORKSPACE",
          generation: old.generation,
        })
      ).status,
      200,
    );
    const cleared = await s.snapshot();
    assert.equal(cleared.records.length, 0);
    assert(cleared.generation > old.generation);
    status = await s.status();
    assert.equal(status.usage.versions, 0);
    assert.equal(status.usage.checkpoints, 0);
    assert.equal(
      (await s.recover("preview", { source: version.id })).status,
      410,
    );
    assert.equal(
      (await s.recover("preview", { source: checkpoint.checkpoint_id })).status,
      410,
    );
    assert.equal((await s.apply(s.applyBody(preview))).status, 409);
  } finally {
    await s.close();
  }
});

test("read-only and author permissions apply to recovery and recovery settings", async () => {
  const s = await scene();
  try {
    await s.reauth();
    for (const [name, role] of [
      ["Sophia", "member"],
      ["ReadOnly", "viewer"],
    ])
      assert.equal(
        (await s.admin({ action: "user.create", name, role, password })).status,
        200,
      );
    const member = await s.f.login("Sophia"),
      viewer = await s.f.login("ReadOnly");
    const event = randomUUID();
    await s.send("event.create", event, {
      title: "Owner appointment",
      starts_at: 1_800_000_000,
      all_day: false,
    });
    const version = (await s.versions(event))[0];
    assert.equal(
      (await s.recover("versions", { target: event }, viewer)).status,
      200,
    );
    assert.equal(
      (await s.recover("preview", { source: version.id }, viewer)).status,
      403,
    );
    assert.equal(
      (await s.recover("preview", { source: version.id }, member)).status,
      403,
    );
    const status = await s.status();
    assert.equal(
      (
        await s.recover(
          "checkpoint.create",
          {
            name: "Not an admin",
            operation_id: randomUUID(),
            history_revision: status.history_revision,
          },
          member,
        )
      ).status,
      403,
    );
    assert.equal(
      (
        await s.f.request("/api/admin", {
          token: member,
          body: {
            action: "recovery.policy",
            policy: status.policy,
            revision: status.history_revision,
          },
        })
      ).status,
      403,
    );
    assert.equal(
      (
        await s.admin({
          action: "recovery.policy",
          revision: status.history_revision,
          policy: { ...status.policy, days: -1 },
        })
      ).status,
      400,
    );
    assert.deepEqual((await s.status()).policy, status.policy);
  } finally {
    await s.close();
  }
});

test("age-out removes expired versions and restart preserves usable encrypted versions", async () => {
  const s = await scene();
  try {
    const task = randomUUID();
    await s.send("task.create", task, {
      title: "Long-lived history",
      notes: "Old",
    });
    await s.send("task.update", task, { notes: "Current" }, 1);
    const versions = await s.versions(task);
    s.sql(
      "db.execute('UPDATE recovery_snapshots SET at=1 WHERE version_index=1')",
    );
    await s.status();
    assert.deepEqual(
      (await s.versions(task)).map((v) => v.index),
      [2],
    );
    assert.equal(
      (await s.recover("preview", { source: versions.at(-1).id })).status,
      410,
    );
    await s.f.stop();
    await s.f.start();
    assert.deepEqual(
      (await s.versions(task)).map((v) => v.index),
      [2],
    );
    assert.equal((await s.preview(versions[0].id)).change_count, 0);
  } finally {
    await s.close();
  }
});

test("recovery receipt lookup survives a renewed session and never discloses another person’s result", async () => {
  const s = await scene();
  try {
    const id = randomUUID();
    await s.send("note.create", id, { title: "Reference", body: "Original" });
    await s.send("note.update", id, { body: "Changed" }, 1);
    const plan = await s.preview((await s.versions(id)).at(-1).id),
      body = s.applyBody(plan);
    assert.equal((await s.apply(body)).status, 200);
    const again = await s.f.login();
    let result = await s.recover(
      "result",
      { operation_id: body.operation_id },
      again,
    );
    assert.equal(result.data.known, true);
    assert.equal(result.data.result.changed, 1);
    assert.equal(
      (await s.f.request("/api/recovery", { token: again, body })).status,
      409,
      "a renewed session may read its own result but cannot reuse an old mutation envelope",
    );
    assert.equal(
      (await s.recover("result", { operation_id: randomUUID() })).data.known,
      false,
    );
  } finally {
    await s.close();
  }
});

test("encrypted backup and fresh-directory restore retain version indices, checkpoints and image recovery", async () => {
  const s = await scene();
  try {
    const id = randomUUID();
    await s.send("task.create", id, { title: "Saved task", notes: "Before" });
    const image = randomUUID();
    assert.equal(
      (
        await s.send("image.create", image, {
          task_id: id,
          name: "Attached.png",
          bytes: png().toString("base64"),
        })
      ).status,
      200,
    );
    const cp = await s.checkpoint("Household saved");
    await s.send("task.update", id, { notes: "After" }, 1);
    const original = await s.versions(id);
    await s.reauth();
    const backup = await s.f.request("/api/admin/backup", { token: s.token });
    assert.equal(backup.status, 200);
    const file = join(s.f.root, "recovery.backup");
    await writeFile(file, backup.data, { mode: 0o600 });
    await s.f.stop();
    const restored = join(s.f.root, "restored");
    s.f.restore(file, restored);
    await s.f.start(restored);
    const auth = await s.f.login(),
      basis = (await s.f.request("/api/snapshot", { token: auth })).data;
    assert.notEqual(basis.epoch, original.epoch);
    const rec = (action, extra = {}) =>
      s.f.request("/api/recovery", {
        token: auth,
        body: {
          action,
          epoch: basis.epoch,
          generation: basis.generation,
          ...extra,
        },
      });
    assert.deepEqual(
      (await rec("versions", { target: id })).data.entries,
      original,
    );
    const checkpoints = (await rec("checkpoints")).data.entries;
    assert.ok(checkpoints.some((c) => c.id === cp.checkpoint_id));
    await s.f.request("/api/reauth", { token: auth, body: { password } });
    const preview = (await rec("preview", { source: original.at(-1).id })).data;
    assert.equal((await rec("apply", { ...s.applyBody(preview) })).status, 200);
  } finally {
    await s.close();
  }
});

test("corrupt retained ciphertext fails visibly and leaves current content, indices and identities unchanged", async () => {
  const s = await scene();
  try {
    const id = randomUUID();
    await s.send("note.create", id, { title: "Reference", body: "Before" });
    await s.send("note.update", id, { body: "After" }, 1);
    const old = (await s.versions(id)).at(-1);
    const before = await s.snapshot();
    s.sql(
      `db.execute("UPDATE recovery_snapshots SET data=? WHERE id=?",(b'corrupt', '${old.id}'))`,
    );
    const rejected = await s.recover("preview", { source: old.id });
    assert.equal(rejected.status, 500);
    const listed = await s.versions(id);
    assert.equal(listed.find((v) => v.id === old.id).can_restore, false);
    assert.match(
      listed.find((v) => v.id === old.id).problem,
      /integrity check/,
    );
    assert.equal(listed[0].can_restore, true);
    assert.deepEqual((await s.snapshot()).records, before.records);
    assert.equal(
      Number(
        s
          .sql(
            "print(db.execute('SELECT COUNT(*) FROM recovery_snapshots WHERE target=?',('" +
              id +
              "',)).fetchone()[0])",
          )
          .trim(),
      ),
      2,
    );
  } finally {
    await s.close();
  }
});

test("recovery capacity exhaustion rolls the attachment transaction back instead of losing the previous state", async () => {
  const s = await scene();
  try {
    const id = randomUUID();
    await s.send("task.create", id, { title: "Images" });
    await s.reauth();
    const state = await s.status();
    assert.equal(
      (
        await s.admin({
          action: "recovery.policy",
          revision: state.history_revision,
          policy: { ...state.policy, storage_mib: 16 },
        })
      ).status,
      200,
    );
    let accepted = 0,
      rejected = false;
    for (let n = 0; n < 8; n++) {
      const before = await s.snapshot();
      const result = await s.send("image.create", randomUUID(), {
        task_id: id,
        name: `Image ${n}`,
        bytes: png(1024, 1024, true).toString("base64"),
      });
      if (result.status === 413) {
        assert.match(result.data.error, /Recovery storage/);
        assert.deepEqual((await s.snapshot()).records, before.records);
        rejected = true;
        break;
      }
      assert.equal(result.status, 200, JSON.stringify(result.data));
      accepted++;
    }
    assert.ok(accepted > 0);
    assert.equal(rejected, true);
    assert.ok((await s.status()).usage.recovery_bytes <= 16 * 1024 * 1024);
  } finally {
    await s.close();
  }
});

test("competing restore previews allow one winner and parallel replay returns one committed revision", async () => {
  const s = await scene();
  try {
    const id = randomUUID();
    await s.send("note.create", id, { title: "Reference", body: "Original" });
    await s.send("note.update", id, { body: "Changed" }, 1);
    const versions = await s.versions(id),
      a = await s.preview(versions.at(-1).id),
      b = await s.preview(versions.at(-1).id);
    const results = await Promise.all([
      s.apply(s.applyBody(a)),
      s.apply(s.applyBody(b)),
    ]);
    assert.deepEqual(results.map((r) => r.status).sort(), [200, 409]);
    let current = (await s.snapshot()).records.find((r) => r.id === id);
    assert.equal(current.revision, 3);
    const forward = await s.preview(versions[0].id),
      body = s.applyBody(forward);
    const replay = await Promise.all([s.apply(body), s.apply(body)]);
    assert.ok(replay.every((r) => r.status === 200));
    assert.deepEqual(replay[0].data, replay[1].data);
    current = (await s.snapshot()).records.find((r) => r.id === id);
    assert.equal(current.revision, 4);
    assert.equal(current.data.body, "Changed");
  } finally {
    await s.close();
  }
});

test("restoring an attachment revives its missing task with a new child-write fence", async () => {
  const s = await scene();
  try {
    const task = randomUUID(),
      image = randomUUID();
    await s.send("task.create", task, { title: "Task with photo" });
    await s.send("image.create", image, {
      task_id: task,
      name: "Photo",
      bytes: png().toString("base64"),
    });
    const version = (await s.versions(image))[0];
    await s.send("task.delete", task, {}, 1);
    const plan = await s.preview(version.id);
    assert.equal(plan.restored, 2);
    const restored = await s.apply(s.applyBody(plan));
    assert.equal(restored.status, 200, JSON.stringify(restored.data));
    const current = (await s.snapshot()).records.find((r) => r.id === task);
    assert.equal(current.data.recovery_incarnation, 1);
    assert.equal(
      (
        await s.send("comment.create", randomUUID(), {
          task_id: task,
          body: "Stale draft",
        })
      ).status,
      409,
    );
    assert.equal(
      (
        await s.send("comment.create", randomUUID(), {
          task_id: task,
          body: "Reviewed after restore",
          parent_incarnation: 1,
        })
      ).status,
      200,
    );
  } finally {
    await s.close();
  }
});

test("saved image bytes can repair a damaged live attachment even when its metadata is unchanged", async () => {
  const s = await scene();
  try {
    const task = randomUUID(),
      image = randomUUID();
    await s.send("task.create", task, { title: "Photo reference" });
    await s.send("image.create", image, {
      task_id: task,
      name: "Attached photo",
      bytes: png().toString("base64"),
    });
    const original = (
        await s.f.request("/api/images/" + image, { token: s.token })
      ).data,
      version = (await s.versions(image))[0],
      old = await s.preview(version.id);
    s.sql(
      `db.execute("UPDATE blobs SET bytes=? WHERE id=?",(b'corrupt', '${image}'))`,
    );
    assert.equal(
      (await s.apply(s.applyBody(old))).status,
      409,
      "a changed live image invalidates an earlier preview",
    );
    const plan = await s.preview(version.id);
    assert.equal(plan.changed, 1);
    assert.ok(plan.changes[0].changes.some((c) => c.field === "image_content"));
    const applied = await s.apply(s.applyBody(plan));
    assert.equal(applied.status, 200, JSON.stringify(applied.data));
    assert.deepEqual(
      (await s.f.request("/api/images/" + image, { token: s.token })).data,
      original,
    );
  } finally {
    await s.close();
  }
});

test("item previews present complete selected/current states including unchanged fields and child content", async () => {
  const s = await scene();
  try {
    const task = randomUUID(),
      comment = randomUUID();
    assert.equal(
      (
        await s.send("task.create", task, {
          title: "Household plan",
          notes: "Unchanged reference notes",
          priority: 2,
          store: "Local market",
        })
      ).status,
      200,
    );
    assert.equal(
      (
        await s.send("comment.create", comment, {
          task_id: task,
          body: "Keep this comment with the item",
        })
      ).status,
      200,
    );
    const saved = (await s.versions(task))[0];
    assert.equal(
      (await s.send("task.update", task, { priority: 1 }, 1)).status,
      200,
    );
    const plan = await s.preview(saved.id),
      old = plan.item_state.saved,
      current = plan.item_state.current;
    const field = (state, key) =>
      state.fields.find((f) => f.field === key)?.value;
    assert.equal(old.title, "Household plan");
    assert.equal(field(old, "priority"), 2);
    assert.equal(field(current, "priority"), 1);
    assert.equal(field(old, "notes"), "Unchanged reference notes");
    assert.equal(field(current, "notes"), "Unchanged reference notes");
    assert.equal(field(old, "store"), "Local market");
    assert.equal(old.comments, 1);
    assert.equal(old.children[0].body, "Keep this comment with the item");
    assert.equal(
      old.fields.some((f) => f.field === "recovery_incarnation"),
      false,
    );
    assert.equal((await s.apply(s.applyBody(plan))).status, 200);
    const after = await s.snapshot();
    assert.equal(after.records.find((r) => r.id === task).data.priority, 2);
    assert.equal(
      after.records.find((r) => r.id === comment).data.body,
      "Keep this comment with the item",
    );
  } finally {
    await s.f.close();
  }
});

test("every restore requires password proof, a source-specific phrase and an explicit reviewed acknowledgement", async () => {
  const s = await scene(false);
  try {
    const id = randomUUID();
    await s.send("note.create", id, {
      title: "Restore carefully",
      body: "Before",
    });
    await s.send("note.update", id, { body: "After" }, 1);
    const plan = await s.preview((await s.versions(id)).at(-1).id);
    assert.equal(plan.reauth_required, true);
    assert.equal(plan.confirmation_phrase, "RESTORE ITEM #1");
    const body = s.applyBody(plan);
    assert.equal((await s.apply(body)).status, 403);
    await s.reauth();
    for (const invalid of [
      { ...body, confirmation: "RESTORE ITEM" },
      { ...body, confirmation: "RESTORE ITEM #2" },
      { ...body, reviewed: false },
      { ...body, reviewed: undefined },
    ])
      assert.equal((await s.apply(invalid)).status, 400);
    assert.equal(
      (await s.snapshot()).records.find((r) => r.id === id).data.body,
      "After",
    );
    assert.equal((await s.apply(body)).status, 200);
    assert.equal(
      (await s.snapshot()).records.find((r) => r.id === id).data.body,
      "Before",
    );
  } finally {
    await s.close();
  }
});

test("individual checkpoint deletion is scoped, revision-fenced, replay-safe and retains shared and live images", async () => {
  const s = await scene();
  try {
    const task = randomUUID(),
      image = randomUUID();
    await s.send("task.create", task, { title: "Image remains live" });
    await s.send("image.create", image, {
      task_id: task,
      name: "Live.png",
      bytes: png().toString("base64"),
    });
    const a = await s.checkpoint("First copy"),
      b = await s.checkpoint("Second copy");
    const page = (await s.recover("checkpoints")).data;
    assert(page.entries.every((e) => e.can_delete));
    const makeBody = (entry, revision = page.history_revision) => ({
      action: "checkpoint.delete",
      epoch: page.epoch,
      generation: page.generation,
      source: entry.id,
      history_revision: revision,
      operation_id: randomUUID(),
      confirmation: `DELETE CHECKPOINT #${entry.index}`,
    });
    const first = page.entries.find((e) => e.id === a.checkpoint_id);
    const second = page.entries.find((e) => e.id === b.checkpoint_id);
    const preview = await s.preview(second.id);
    const staleCreate = { ...a.body, operation_id: randomUUID() };
    let body = makeBody(first);
    assert.equal((await s.apply({ ...body, history_revision: 0 })).status, 409);
    assert.equal(
      (await s.apply({ ...body, confirmation: "DELETE CHECKPOINT #999" }))
        .status,
      400,
    );
    const version = (await s.versions(task))[0];
    assert.equal((await s.apply({ ...body, source: version.id })).status, 410);
    await s.admin({
      action: "user.create",
      name: "member",
      role: "member",
      password,
    });
    const member = await s.f.login("member");
    await s.f.request("/api/reauth", { token: member, body: { password } });
    assert(
      (await s.recover("checkpoints", {}, member)).data.entries.every(
        (e) => !e.can_delete,
      ),
    );
    assert.equal(
      (await s.f.request("/api/recovery", { token: member, body })).status,
      403,
    );
    const unelevated = await s.f.login();
    assert.equal(
      (await s.f.request("/api/recovery", { token: unelevated, body })).status,
      403,
    );
    const before = await s.snapshot();
    const result = await s.apply(body);
    assert.equal(result.status, 200, JSON.stringify(result.data));
    assert.deepEqual((await s.apply(body)).data, result.data);
    assert.equal(
      (await s.recover("result", { operation_id: body.operation_id })).data
        .known,
      true,
    );
    assert.equal((await s.apply(s.applyBody(preview))).status, 409);
    assert.equal((await s.apply(staleCreate)).status, 409);
    assert.deepEqual((await s.snapshot()).records, before.records);
    assert.equal(
      s
        .sql(
          "print(db.execute('SELECT COUNT(*) FROM recovery_blobs').fetchone()[0])",
        )
        .trim(),
      "1",
    );
    // Remove other recovery references first; deleting the final checkpoint must
    // free retained bytes without touching the separate live attachment store.
    let status = await s.status();
    await s.admin({
      action: "recovery.purge",
      scope: "versions",
      revision: status.history_revision,
      confirmation: "PURGE HISTORY",
    });
    status = await s.status();
    body = makeBody(second, status.history_revision);
    assert.equal((await s.apply(body)).status, 200);
    assert.equal(
      s
        .sql(
          "print(db.execute('SELECT COUNT(*) FROM recovery_blobs').fetchone()[0])",
        )
        .trim(),
      "0",
    );
    assert.equal(
      s
        .sql("print(db.execute('SELECT COUNT(*) FROM blobs').fetchone()[0])")
        .trim(),
      "1",
    );
    assert.deepEqual((await s.snapshot()).records, before.records);
    assert.equal((await s.recover("checkpoints")).data.entries.length, 0);
    const next = await s.checkpoint("Next copy");
    assert.equal((await s.recover("checkpoints")).data.entries[0].index, 3);
    assert.notEqual(next.checkpoint_id, second.id);
  } finally {
    await s.close();
  }
});

test("damaged checkpoints remain individually deletable without reading their encrypted contents", async () => {
  const s = await scene();
  try {
    const cp = await s.checkpoint("Damaged test copy");
    s.sql(
      `db.execute("UPDATE recovery_snapshots SET data=x'00' WHERE id=?", ('${cp.checkpoint_id}',))`,
    );
    const page = (await s.recover("checkpoints")).data,
      entry = page.entries[0];
    assert.equal(entry.can_restore, false);
    assert.equal(entry.can_delete, true);
    const result = await s.recover("checkpoint.delete", {
      source: entry.id,
      history_revision: page.history_revision,
      operation_id: randomUUID(),
      confirmation: `DELETE CHECKPOINT #${entry.index}`,
    });
    assert.equal(result.status, 200, JSON.stringify(result.data));
    assert.equal((await s.recover("checkpoints")).data.entries.length, 0);
  } finally {
    await s.close();
  }
});

test("parallel checkpoint deletes commit once and restart preserves the receipt and revision fence", async () => {
  const s = await scene();
  try {
    await s.checkpoint("Concurrent delete");
    const page = (await s.recover("checkpoints")).data,
      entry = page.entries[0];
    const body = {
      action: "checkpoint.delete",
      epoch: page.epoch,
      generation: page.generation,
      source: entry.id,
      history_revision: page.history_revision,
      operation_id: randomUUID(),
      confirmation: `DELETE CHECKPOINT #${entry.index}`,
    };
    const [a, b] = await Promise.all([s.apply(body), s.apply(body)]);
    assert.equal(a.status, 200);
    assert.equal(b.status, 200);
    assert.deepEqual(b.data, a.data);
    assert.equal(
      (await s.status()).history_revision,
      page.history_revision + 1,
    );
    assert.equal(
      (await s.apply({ ...body, operation_id: randomUUID() })).status,
      409,
    );
    await s.f.stop();
    await s.f.start();
    assert.deepEqual((await s.apply(body)).data, a.data);
    assert.equal((await s.recover("checkpoints")).data.entries.length, 0);
  } finally {
    await s.close();
  }
});

test("a failed checkpoint-delete audit rolls back the copy, image references and revision before an exact retry", async () => {
  const s = await scene();
  try {
    const task = randomUUID();
    await s.send("task.create", task, { title: "Atomic delete" });
    await s.send("image.create", randomUUID(), {
      task_id: task,
      name: "Retained.png",
      bytes: png().toString("base64"),
    });
    await s.checkpoint("Keep if deletion fails");
    const page = (await s.recover("checkpoints")).data,
      entry = page.entries[0];
    const body = {
      action: "checkpoint.delete",
      epoch: page.epoch,
      generation: page.generation,
      source: entry.id,
      history_revision: page.history_revision,
      operation_id: randomUUID(),
      confirmation: `DELETE CHECKPOINT #${entry.index}`,
    };
    const usage = (await s.status()).usage;
    s.sql(
      "db.execute(\"CREATE TRIGGER fail_checkpoint_delete BEFORE INSERT ON audit WHEN NEW.action='checkpoint.delete' BEGIN SELECT RAISE(ABORT, 'injected test failure'); END\")",
    );
    assert.equal((await s.apply(body)).status, 500);
    assert.deepEqual(
      (await s.recover("checkpoints")).data.entries,
      page.entries,
    );
    assert.equal((await s.status()).history_revision, page.history_revision);
    assert.equal((await s.status()).usage.image_bytes, usage.image_bytes);
    assert.equal(
      (await s.recover("result", { operation_id: body.operation_id })).data
        .known,
      false,
    );
    s.sql("db.execute('DROP TRIGGER fail_checkpoint_delete')");
    assert.equal((await s.apply(body)).status, 200);
  } finally {
    await s.close();
  }
});


test("Trash lists deleted records without changing current content or recovery copies", async () => {
  const s = await scene();
  try {
    const project = await s.send("project.create", randomUUID(), {title: "Discarded draft"});
    assert.equal(project.status, 200, JSON.stringify(project.data));
    const record = project.data.record;
    const removed = await s.send("project.delete", record.id, {}, record.revision);
    assert.equal(removed.status, 200, JSON.stringify(removed.data));
    const before = await s.snapshot();
    assert.equal(before.records.some(r => r.id === record.id), false);
    const response = await s.recover("trash", {limit: 50});
    assert.equal(response.status, 200, JSON.stringify(response.data));
    const entry = response.data.entries.find(row => row.id === record.id);
    assert(entry, "deleted project should appear in Trash");
    assert.equal(entry.kind, "project");
    assert.equal(entry.title, "Discarded draft");
    assert.equal(typeof entry.deleted_at, "number");
    assert.equal(entry.recoverable, true);
    assert.equal((await s.versions(record.id)).length > 0, true);
    const again = await s.recover("trash", {limit: 50});
    assert.equal(again.status, 200);
    assert.deepEqual(again.data.entries, response.data.entries);
    const after = await s.snapshot();
    assert.deepEqual(after.records, before.records);
  } finally { await s.f.close(); }
});

test("Trash rejects malformed pagination and never restores content implicitly", async () => {
  const s = await scene();
  try {
    const project = (await s.send("project.create", randomUUID(), {title: "Safe recovery"})).data.record;
    assert.equal((await s.send("project.delete", project.id, {}, project.revision)).status, 200);
    assert.equal((await s.recover("trash", {limit: 0})).status, 400);
    assert.equal((await s.recover("trash", {limit: 51})).status, 400);
    assert.equal((await s.recover("trash", {before: "not-a-uuid"})).status, 400);
    assert.equal((await s.recover("trash", {unexpected: true})).status, 400);
    const page = await s.recover("trash", {limit: 1});
    assert.equal(page.status, 200, JSON.stringify(page.data));
    assert.equal(page.data.entries.length, 1);
    assert.equal(page.data.entries[0].id, project.id);
    assert.equal((await s.snapshot()).records.some(r => r.id === project.id), false);
  } finally { await s.f.close(); }
});
