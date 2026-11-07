import { test } from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { runInNewContext } from "node:vm";
import { randomUUID } from "node:crypto";
import { fixture } from "./support.mjs";
const profile = {
  importance: "normal",
  sound: "bell",
  volume: 75,
  vibrate: true,
};
const basic = () => ({ id: randomUUID(), minutes_before: 60, ...profile });
const custom = (unit = "years", amount = 1) => ({
  id: randomUUID(),
  minutes_before: 0,
  ...profile,
  lead_time: { unit, amount, zone: "America/New_York" },
});
const rules = (alerts) => ({ alerts, repeat: null, timing_version: 1 });
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
  return {
    f,
    token,
    basis,
    send,
    snapshot: async () => (await f.request("/api/snapshot", { token })).data,
    create: async (reminders) => {
      const id = randomUUID();
      const r = await send("event.create", id, {
        title: "Annual home check",
        all_day: false,
        starts_at: 1920272400,
        ends_at: null,
        reminders,
      });
      assert.equal(r.status, 200, JSON.stringify(r.data));
      return r.data.record;
    },
    transfer: (action, body) =>
      f.request("/api/transfer/" + action, {
        token,
        ...(body === undefined ? {} : { body }),
      }),
  };
}
test("friendly timing controls preserve legacy durations and encode calendar units explicitly", async () => {
  const sandbox = {};
  runInNewContext(
    (await readFile("apps/admin/reminder-timing.js", "utf8")) +
      "\nglobalThis.timing=FieldReminderTiming;",
    sandbox,
  );
  const t = sandbox.timing;
  assert.deepEqual(Object.keys(t.units), [
    "minutes",
    "hours",
    "days",
    "weeks",
    "months",
    "years",
  ]);
  const r = basic();
  t.apply(r, 2, "hours", "America/New_York");
  assert.equal(r.minutes_before, 120);
  assert(!r.lead_time);
  assert.equal(t.label(r), "2 hours before");
  assert.equal(r.sound, "bell");
  t.apply(r, 1, "months", "America/New_York");
  assert.equal(r.minutes_before, 0);
  assert.equal(r.lead_time.unit, "months");
  assert.equal(t.label(r), "1 month before");
  for (const [unit, maximum] of Object.entries(t.limits)) {
    assert.throws(() => t.validate({ unit, amount: maximum + 1, zone: "UTC" }));
    assert.throws(() => t.validate({ unit, amount: -1, zone: "UTC" }));
  }
  assert.throws(() => t.validate({ unit: "years", amount: 1 }));
  assert.throws(() =>
    t.validate({ unit: "years", amount: 1, zone: "invalid" }),
  );
});
test("legacy projections keep compatible alerts and cannot silently replace calendar reminders", async () => {
  const s = await scene();
  try {
    const legacy = basic(),
      lead = custom(),
      item = await s.create(rules([legacy, lead]));
    let saved = (await s.snapshot()).records.find((r) => r.id === item.id);
    assert.equal(saved.data.reminders.alerts.length, 1);
    assert.deepEqual(saved.data.reminders.alerts[0], legacy);
    assert(!("timing_version" in saved.data.reminders));
    assert.equal(saved.data.reminder_timing_rules.alerts.length, 2);
    const before = (await s.snapshot()).sequence;
    const oldEdit = await s.send(
      "event.update",
      item.id,
      {
        notes: "Must stay unapplied",
        reminders: {
          alerts: [{ ...legacy, minutes_before: 30 }],
          repeat: null,
        },
        reminder_options: saved.data.reminder_options,
      },
      item.revision,
    );
    assert.equal(oldEdit.status, 409);
    assert.equal((await s.snapshot()).sequence, before);
    const core = await s.send(
      "event.update",
      item.id,
      { notes: "A compatible old client can still add this" },
      item.revision,
    );
    assert.equal(core.status, 200);
    saved = (await s.snapshot()).records.find((r) => r.id === item.id);
    assert.equal(saved.data.reminder_timing_rules.alerts.length, 2);
    const clear = await s.send(
      "event.update",
      item.id,
      { reminders: rules([legacy]) },
      core.data.record.revision,
    );
    assert.equal(clear.status, 200, JSON.stringify(clear.data));
    saved = (await s.snapshot()).records.find((r) => r.id === item.id);
    assert(!saved.data.reminder_timing_rules);
    assert.deepEqual(saved.data.reminders, { alerts: [legacy], repeat: null });
  } finally {
    await s.f.close();
  }
});
test("calendar timing survives server and phone-style exports and duplicate-safe imports", async () => {
  const s = await scene(),
    target = await scene();
  try {
    const item = await s.create(
      rules([basic(), custom("months", 2), custom("years", 1)]),
    );
    const exported = (await s.transfer("export")).data;
    assert.equal(
      exported.records.find((r) => r.id === item.id).data.reminders.alerts
        .length,
      3,
    );
    for (let n = 0; n < 2; n++) {
      const manifest = structuredClone(exported);
      if (n)
        manifest.records[0] = (await s.snapshot()).records.find(
          (r) => r.id === item.id,
        );
      const p = await target.transfer("prepare", { manifest });
      assert.equal(p.status, 200, JSON.stringify(p.data));
      if (n) assert.equal(p.data.new_items, 0);
      assert.equal(
        (await target.transfer("commit", { plan_id: p.data.plan_id })).status,
        200,
      );
    }
    const saved = (await target.snapshot()).records;
    assert.equal(saved.length, 1);
    assert.equal(saved[0].data.reminder_timing_rules.alerts.length, 3);
  } finally {
    await s.f.close();
    await target.f.close();
  }
});
test("invalid timing formats and limits roll back the entire command", async () => {
  const s = await scene();
  try {
    const item = await s.create(rules([basic()]));
    const before = (await s.snapshot()).sequence;
    const invalid = [
      { ...custom(), lead_time: { unit: "years", amount: 11, zone: "UTC" } },
      { ...custom(), lead_time: { unit: "months", amount: 121, zone: "UTC" } },
      { ...custom(), lead_time: { unit: "years", amount: 1.2, zone: "UTC" } },
      { ...custom(), lead_time: { unit: "years", amount: 1, zone: "invalid" } },
      { ...custom(), minutes_before: 10 },
      { ...custom(), lead_time: null },
    ];
    for (const lead of invalid)
      assert.equal(
        (
          await s.send(
            "event.update",
            item.id,
            { title: "Must not change", reminders: rules([lead]) },
            item.revision,
          )
        ).status,
        400,
      );
    assert.equal(
      (
        await s.send(
          "event.update",
          item.id,
          { reminders: { alerts: [custom()], repeat: null } },
          item.revision,
        )
      ).status,
      400,
    );
    assert.equal((await s.snapshot()).sequence, before);
    assert.equal(
      (await s.snapshot()).records.find((r) => r.id === item.id).data.title,
      "Annual home check",
    );
  } finally {
    await s.f.close();
  }
});
