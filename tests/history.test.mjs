import { test } from "node:test";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { execFileSync } from "node:child_process";
import { readFile, writeFile } from "node:fs/promises";
import { join } from "node:path";
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

async function household(f) {
  const owner = await f.login();
  await f.request("/api/reauth", { token: owner, body: { password } });
  for (const [name, role] of [
    ["Sophia", "member"],
    ["Reader", "viewer"],
  ])
    assert.equal(
      (
        await f.request("/api/admin", {
          token: owner,
          body: { action: "user.create", name, role, password },
        })
      ).status,
      200,
    );
  const sophia = await f.login("Sophia"),
    reader = await f.login("Reader");
  const snapshot = (await f.request("/api/snapshot", { token: owner })).data;
  const send = (
    token,
    action,
    target,
    data = {},
    revision = null,
    operation_id = randomUUID(),
  ) =>
    f.request("/api/commands", {
      token,
      body: {
        operation_id,
        epoch: snapshot.epoch,
        generation: snapshot.generation,
        action,
        target,
        expected_revision: revision,
        data,
      },
    });
  const history = (extra = {}, token = reader) =>
    f.request("/api/history", {
      token,
      body: {
        epoch: snapshot.epoch,
        generation: snapshot.generation,
        ...extra,
      },
    });
  return { owner, sophia, reader, snapshot, send, history };
}
test("history preserves real actors, before/after changes, list context and deletion across restart without replay duplicates", async () => {
  const f = await fixture();
  try {
    const h = await household(f),
      list = randomUUID(),
      task = randomUUID();
    assert.equal(
      (
        await h.send(h.owner, "list.create", list, {
          title: "Costco Shopping List",
          template: "groceries",
        })
      ).status,
      200,
    );
    const op = randomUUID(),
      added = await h.send(
        h.sophia,
        "task.create",
        task,
        {
          title: "Bananas",
          notes: "Green for later",
          project_id: list,
          store: "Costco",
        },
        null,
        op,
      );
    assert.equal(added.status, 200);
    assert.deepEqual(
      (
        await h.send(
          h.sophia,
          "task.create",
          task,
          {
            title: "Bananas",
            notes: "Green for later",
            project_id: list,
            store: "Costco",
          },
          null,
          op,
        )
      ).data,
      added.data,
    );
    assert.equal(
      (
        await h.send(
          h.owner,
          "task.update",
          task,
          { notes: "Yellow for tomorrow" },
          99,
        )
      ).status,
      409,
    );
    assert.equal(
      (
        await h.send(
          h.owner,
          "task.update",
          task,
          { notes: "Yellow for tomorrow" },
          1,
        )
      ).status,
      200,
    );
    assert.equal(
      (await h.send(h.sophia, "task.complete", task, {}, 2)).status,
      200,
    );
    assert.equal(
      (await h.send(h.sophia, "task.delete", task, {}, 3)).status,
      200,
    );
    const response = await h.history();
    assert.equal(response.status, 200);
    const trail = response.data.entries.filter((e) => e.target === task);
    assert.deepEqual(
      trail.map((e) => e.action),
      ["task.delete", "task.complete", "task.update", "task.create"],
    );
    assert.equal(trail[0].title, "Bananas");
    assert.equal(trail[0].parent_title, "Costco Shopping List");
    assert.equal(trail[0].item_type, "grocery_item");
    assert.equal(trail[0].actor_name, "Sophia");
    const note = trail[2].changes.find((c) => c.field === "notes");
    assert.equal(note.before, "Green for later");
    assert.equal(note.after, "Yellow for tomorrow");
    assert.ok(trail.every((e) => e.at > 0 && e.details === "recorded"));
    assert.equal(
      (await h.history({ action: "removed" })).data.entries.length,
      1,
    );
    assert.equal(
      (await h.history({ query: "green for later" })).data.entries.length,
      2,
    );
    assert.equal((await h.history({ target: list })).data.entries.length, 5);
    const sophiaId = (
      await f.request("/api/people", { token: h.owner })
    ).data.people.find((p) => p.name === "Sophia").id;
    assert.equal((await h.history({ actor: sophiaId })).data.entries.length, 3);
    assert.equal((await h.history({ kind: "event" })).data.entries.length, 0);
    await f.stop();
    await f.start();
    assert.deepEqual((await h.history()).data.entries, response.data.entries);
    assert.equal(
      (
        await f.request("/api/history", {
          body: { epoch: h.snapshot.epoch, generation: h.snapshot.generation },
        })
      ).status,
      401,
    );
    assert.equal(
      (
        await h.send(h.reader, "task.create", randomUUID(), {
          title: "Cannot edit audit",
        })
      ).status,
      403,
    );
    const bytes = await readFile(join(f.data, "field-notes.sqlite3"));
    for (const text of ["Bananas", "Green for later", "Yellow for tomorrow"])
      assert.equal(
        bytes.includes(Buffer.from(text)),
        false,
        "history text must be encrypted at rest",
      );
  } finally {
    await f.close();
  }
});
test("history pagination holds a fixed server watermark while new changes arrive and validates every filter", async () => {
  const f = await fixture();
  try {
    const h = await household(f);
    for (let i = 0; i < 8; i++)
      assert.equal(
        (
          await h.send(h.owner, "note.create", randomUUID(), {
            title: "Trail " + i,
            body: "Household reference",
          })
        ).status,
        200,
      );
    const first = (await h.history({ limit: 3 })).data;
    assert.equal(first.entries.length, 3);
    assert.equal(first.has_more, true);
    const late = randomUUID();
    await h.send(h.sophia, "note.create", late, {
      title: "Added after paging began",
      body: "Newest",
    });
    const second = (
      await h.history({
        limit: 3,
        anchor: first.anchor,
        before: first.next_before,
      })
    ).data;
    const third = (
      await h.history({
        limit: 3,
        anchor: first.anchor,
        before: second.next_before,
      })
    ).data;
    const entries = [...first.entries, ...second.entries, ...third.entries];
    assert.equal(new Set(entries.map((e) => e.sequence)).size, 8);
    assert.equal(third.has_more, false);
    assert.equal(
      entries.some((e) => e.target === late),
      false,
    );
    assert.equal((await h.history()).data.entries[0].target, late);
    for (const bad of [
      { limit: 51 },
      { limit: -1 },
      { actor: "not-a-uuid" },
      { kind: "password" },
      { action: "user.password.reset" },
      { from: 10, to: 9 },
      { anchor: first.anchor, before: first.anchor + 2 },
      { query: "x".repeat(201) },
      { epoch: "another-server" },
      { generation: 999 },
      { extra: "not permitted" },
    ])
      assert.ok(
        [400, 409].includes((await h.history(bad)).status),
        JSON.stringify(bad),
      );
    const now = Math.floor(Date.now() / 1000);
    assert.equal((await h.history({ from: now + 60 })).data.entries.length, 0);
  } finally {
    await f.close();
  }
});
test("deleting a task records its removed comments and attachments without retaining image bytes", async () => {
  const f = await fixture();
  try {
    const h = await household(f),
      task = randomUUID(),
      comment = randomUUID(),
      image = randomUUID();
    await h.send(h.sophia, "task.create", task, { title: "Repair shelf" });
    await h.send(h.sophia, "comment.create", comment, {
      task_id: task,
      body: "Bring the small screwdriver",
    });
    const imageBytes = png().toString("base64");
    const upload = await h.send(h.sophia, "image.create", image, {
      task_id: task,
      name: "Shelf corner",
      bytes: imageBytes,
    });
    assert.equal(upload.status, 200, JSON.stringify(upload.data));
    assert.equal(
      (await h.send(h.owner, "task.delete", task, {}, 1)).status,
      200,
    );
    const trail = (await h.history({ action: "removed" })).data.entries;
    assert.deepEqual(
      new Set(trail.map((e) => e.target)),
      new Set([task, comment, image]),
    );
    assert.equal(
      trail.find((e) => e.target === comment).parent_title,
      "Repair shelf",
    );
    assert.equal(
      trail
        .find((e) => e.target === comment)
        .changes.find((c) => c.field === "body").before,
      "Bring the small screwdriver",
    );
    assert.equal(
      (await f.request("/api/images/" + image, { token: h.owner })).status,
      404,
    );
    const encoded = JSON.stringify((await h.history()).data);
    assert.equal(encoded.includes(imageBytes), false);
    assert.equal(encoded.includes("claim_secret"), false);
    assert.equal(encoded.includes("preshared_key"), false);
    assert.equal(encoded.includes(password), false);
  } finally {
    await f.close();
  }
});
test("history failure rolls the content mutation back; legacy metadata remains honest; clearing fences and removes old details", async () => {
  const f = await fixture();
  try {
    const h = await household(f),
      note = randomUUID();
    await f.stop();
    execFileSync("python3", [
      "-c",
      "import sqlite3,sys; db=sqlite3.connect(sys.argv[1]); db.execute(\"CREATE TRIGGER fail_history BEFORE INSERT ON history_details BEGIN SELECT RAISE(ABORT,'fixture'); END\"); db.commit()",
      join(f.data, "field-notes.sqlite3"),
    ]);
    await f.start();
    assert.equal(
      (
        await h.send(h.owner, "note.create", note, {
          title: "Must not partly commit",
        })
      ).status,
      500,
    );
    assert.equal(
      (await f.request("/api/snapshot", { token: h.owner })).data.records
        .length,
      0,
    );
    assert.equal((await h.history()).data.entries.length, 0);
    await f.stop();
    execFileSync("python3", [
      "-c",
      "import sqlite3,sys; db=sqlite3.connect(sys.argv[1]); db.execute('DROP TRIGGER fail_history'); db.commit()",
      join(f.data, "field-notes.sqlite3"),
    ]);
    await f.start();
    await h.send(h.owner, "note.create", note, {
      title: "Retained note",
      body: "Private history body",
    });
    await f.stop();
    execFileSync("python3", [
      "-c",
      "import sqlite3,sys; db=sqlite3.connect(sys.argv[1]); db.execute('DELETE FROM history_details'); db.commit()",
      join(f.data, "field-notes.sqlite3"),
    ]);
    await f.start();
    const legacy = (await h.history()).data.entries[0];
    assert.equal(legacy.details, "unavailable");
    assert.deepEqual(legacy.changes, []);
    assert.equal(legacy.title, "Retained note");
    await h.send(
      h.owner,
      "note.update",
      note,
      { body: "New private version" },
      1,
    );
    await f.request("/api/reauth", { token: h.owner, body: { password } });
    assert.equal(
      (
        await f.request("/api/admin", {
          token: h.owner,
          body: {
            action: "workspace.clear",
            confirmation: "CLEAR WORKSPACE",
            generation: h.snapshot.generation,
          },
        })
      ).status,
      200,
    );
    assert.equal((await h.history()).status, 409);
    const current = (await f.request("/api/snapshot", { token: h.owner })).data;
    const cleared = await f.request("/api/history", {
      token: h.owner,
      body: { epoch: current.epoch, generation: current.generation },
    });
    assert.equal(cleared.data.entries.length, 1);
    assert.equal(cleared.data.entries[0].action, "workspace.clear");
    assert.equal(
      JSON.stringify(cleared.data).includes("Private history body"),
      false,
    );
    const detailCount = execFileSync(
      "python3",
      [
        "-c",
        "import sqlite3,sys; print(sqlite3.connect(sys.argv[1]).execute('SELECT COUNT(*) FROM history_details').fetchone()[0])",
        join(f.data, "field-notes.sqlite3"),
      ],
      { encoding: "utf8" },
    ).trim();
    assert.equal(detailCount, "1");
  } finally {
    await f.close();
  }
});
test("history excerpts include edits beyond the first page of a note and encrypted backups preserve the trail", async () => {
  const f = await fixture();
  try {
    const h = await household(f),
      note = randomUUID(),
      prefix = "A".repeat(10_000);
    await h.send(h.sophia, "note.create", note, {
      title: "Long reference",
      body: prefix + "OLD-END",
    });
    await h.send(h.owner, "note.update", note, { body: prefix + "NEW-END" }, 1);
    const original = (await h.history()).data;
    const change = original.entries[0].changes.find((c) => c.field === "body");
    assert.equal(change.before_truncated, true);
    assert.equal(change.after_truncated, true);
    assert.ok(change.before.includes("OLD-END"));
    assert.ok(change.after.includes("NEW-END"));
    await f.request("/api/reauth", { token: h.owner, body: { password } });
    const backup = await f.request("/api/admin/backup", { token: h.owner });
    assert.equal(backup.status, 200);
    const file = join(f.root, "history.backup");
    await writeFile(file, backup.data, { mode: 0o600 });
    await f.stop();
    const destination = join(f.root, "restored");
    f.restore(file, destination);
    await f.start(destination);
    const owner = await f.login(),
      snapshot = (await f.request("/api/snapshot", { token: owner })).data;
    assert.notEqual(snapshot.epoch, h.snapshot.epoch);
    const history = await f.request("/api/history", {
      token: owner,
      body: { epoch: snapshot.epoch, generation: snapshot.generation },
    });
    assert.deepEqual(history.data.entries, original.entries);
    assert.equal(
      (
        await f.request("/api/history", {
          token: owner,
          body: { epoch: h.snapshot.epoch, generation: h.snapshot.generation },
        })
      ).status,
      409,
    );
  } finally {
    await f.close();
  }
});
test("bounded search advances its cursor across empty pages without skipping an older match", async () => {
  const f = await fixture();
  try {
    const h = await household(f);
    await h.send(h.owner, "note.create", randomUUID(), {
      title: "Needle before the legacy gap",
    });
    await f.stop();
    execFileSync("python3", [
      "-c",
      "import sqlite3,sys,time,uuid; db=sqlite3.connect(sys.argv[1]); db.executemany('INSERT INTO events(generation,object_id,action,actor,at) VALUES(?,?,?,?,?)',[(1,str(uuid.uuid4()),'note.create',sys.argv[2],int(time.time())) for _ in range(505)]); db.commit()",
      join(f.data, "field-notes.sqlite3"),
      h.snapshot.actor.user_id,
    ]);
    await f.start();
    const first = (await h.history({ query: "Needle before" })).data;
    assert.equal(first.entries.length, 0);
    assert.equal(first.has_more, true);
    assert.equal(first.scanned, 500);
    const second = (
      await h.history({
        query: "Needle before",
        anchor: first.anchor,
        before: first.next_before,
      })
    ).data;
    assert.equal(second.entries.length, 1);
    assert.equal(second.entries[0].title, "Needle before the legacy gap");
    assert.equal(second.has_more, false);
  } finally {
    await f.close();
  }
});

test("item history paginates whole items once, groups child activity, and preserves the detailed audit trail", async () => {
  const f = await fixture();
  try {
    const h = await household(f), a = randomUUID(), b = randomUUID(), c = randomUUID();
    for (const [id,title] of [[a,"Pantry"],[b,"Weekend route"],[c,"Packing"]])
      assert.equal((await h.send(h.owner,"task.create",id,{title,notes:"Whole item notes"})).status,200);
    for (let revision=1; revision<=8; revision++)
      assert.equal((await h.send(h.owner,"task.update",a,{quantity:String(revision)},revision)).status,200);
    const comment=randomUUID();
    assert.equal((await h.send(h.sophia,"comment.create",comment,{task_id:a,body:"Bring the reusable bags"})).status,200);
    const response=await h.history({view:"items",limit:1});assert.equal(response.status,200,JSON.stringify(response.data));
    const first=response.data;
    for(const filters of [{kind:"task"},{action:"changed"}]) {
      const filtered=await h.history({view:"items",...filters});assert.equal(filtered.status,200,JSON.stringify(filtered.data));assert.equal(filtered.data.entries[0].actor_name,"Sophia");
    }
    const added=(await h.history({view:"items",action:"added"})).data.entries;
    assert.equal(added.length,3);assert.equal(added.every(e=>e.action==="task.create"),true);
    assert.equal(first.entries.length,1);assert.equal(first.entries[0].target,a);
    assert.equal(first.entries[0].title,"Pantry");assert.equal(first.entries[0].kind,"task");assert.equal(first.entries[0].action,"task.update");
    // A newer edit cannot change a previously selected pagination watermark.
    assert.equal((await h.send(h.owner,"task.update",a,{notes:"Later edit"},9)).status,200);
    let page=first, entries=[...page.entries];
    while(page.has_more){const r=await h.history({view:"items",limit:1,anchor:first.anchor,before:page.next_before});assert.equal(r.status,200);page=r.data;entries.push(...page.entries);}
    assert.deepEqual(entries.map(e=>e.target),[a,c,b]);
    const raw=(await h.history({target:a})).data.entries;
    assert.equal(raw.filter(e=>e.action==="task.update").length,9);
    assert.equal(raw.filter(e=>e.action==="comment.create").length,1);
    const person=(await h.history({view:"items",actor:(await f.request("/api/people",{token:h.owner})).data.people.find(p=>p.name==="Sophia").id})).data.entries;
    assert.equal(person.length,1);assert.equal(person[0].target,a);assert.equal(person[0].actor_name,"Sophia");
    assert.equal((await h.history({view:"attributes"})).status,400);
  } finally {await f.close();}
});
