import { test } from "node:test";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { fixture, password } from "./support.mjs";
const profile = {
  importance: "urgent",
  sound: "bell",
  volume: 75,
  vibrate: true,
};
const cycle = (unit = "hours", amount = 2) => ({
  id: randomUUID(),
  amount,
  unit,
  anchor_at: 1794700000,
  zone: "America/New_York",
  ...profile,
});
const rules = (interval) => ({
  alerts: [],
  repeat: null,
  timing_version: 3,
  interval,
});
async function scene() {
  const f = await fixture(),
    token = await f.login(),
    basis = (await f.request("/api/snapshot", { token })).data;
  const send = (
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
    transfer: (action, body) =>
      f.request("/api/transfer/" + action, {
        token,
        ...(body === undefined ? {} : { body }),
      }),
  };
}
test("undated interval reminders preserve urgent importance, replay and restart without exposing incompatible rules", async () => {
  const s = await scene();
  try {
    const id = randomUUID(),
      interval = cycle(),
      operation = randomUUID();
    const data = {
      title: "Nudge without a due date",
      reminders: rules(interval),
    };
    const created = await s.send("task.create", id, data, null, operation);
    assert.equal(created.status, 200, JSON.stringify(created.data));
    assert.deepEqual(
      (await s.send("task.create", id, data, null, operation)).data,
      created.data,
    );
    let item = (await s.snapshot()).records.find((r) => r.id === id);
    assert.equal(item.data.due, "");
    assert.deepEqual(item.data.reminder_interval_rules, rules(interval));
    for (const value of [
      item.data.reminders,
      item.data.reminder_timing_rules,
      item.data.reminder_schedule_rules,
    ]) {
      assert(!("interval" in value));
      assert(!("timing_version" in value));
      assert.equal(value.alerts.length, 0);
    }
    const sequence = (await s.snapshot()).sequence;
    for (const version of [undefined, 1, 2]) {
      const refused = await s.send(
        "task.update",
        id,
        {
          notes: "Must not apply",
          reminders: {
            alerts: [],
            repeat: null,
            ...(version ? { timing_version: version } : {}),
          },
          reminder_options: item.data.reminder_options,
        },
        item.revision,
      );
      assert.equal(refused.status, 409);
      assert.equal((await s.snapshot()).sequence, sequence);
    }
    assert.equal(
      (
        await s.send(
          "task.update",
          id,
          { notes: "Core edits still work", priority: 1 },
          item.revision,
        )
      ).status,
      200,
    );
    await s.f.stop();
    await s.f.start();
    item = (await s.snapshot()).records.find((r) => r.id === id);
    assert.equal(item.data.priority, 1);
    assert.equal(
      item.data.reminder_interval_rules.interval.importance,
      "urgent",
    );
    assert.equal(
      item.data.reminder_interval_rules.interval.anchor_at,
      interval.anchor_at,
    );
    const cleared = await s.send(
      "task.update",
      id,
      {
        reminders: { alerts: [], repeat: null, timing_version: 3 },
        reminder_options: item.data.reminder_options,
      },
      item.revision,
    );
    assert.equal(cleared.status, 200, JSON.stringify(cleared.data));
    assert(
      !(await s.snapshot()).records.find((r) => r.id === id).data
        .reminder_interval_rules,
    );
  } finally {
    await s.f.close();
  }
});
test("interval validation rejects bad amounts, clocks, identities and conflicting windows atomically", async () => {
  const s = await scene();
  try {
    for (const changed of [
      { amount: 0 },
      { amount: -1 },
      { unit: "years", amount: 11 },
      { unit: "seconds" },
      { anchor_at: 0 },
      { zone: "not/a-zone" },
      { amount: 1.5 },
    ]) {
      const response = await s.send("task.create", randomUUID(), {
        title: "Invalid",
        reminders: rules({ ...cycle(), ...changed }),
      });
      assert.equal(response.status, 400, JSON.stringify(response.data));
    }
    const interval = cycle();
    const r = await s.send("task.create", randomUUID(), {
      title: "Duplicate IDs",
      reminders: {
        ...rules(interval),
        alerts: [{ id: interval.id, minutes_before: 0, ...profile }],
      },
    });
    assert.equal(r.status, 400);
    assert.equal((await s.snapshot()).records.length, 0);
    for (const unit of [
      "minutes",
      "hours",
      "days",
      "weeks",
      "months",
      "years",
    ]) {
      const r = await s.send("note.create", randomUUID(), {
        title: unit,
        on_radar: true,
        reminders: rules(cycle(unit, 1)),
      });
      assert.equal(r.status, 200, JSON.stringify(r.data));
    }
  } finally {
    await s.f.close();
  }
});
test("all supported urgency and task priorities persist independently of reminder scheduling", async () => {
  const s = await scene();
  try {
    let item = (
      await s.send("task.create", randomUUID(), {
        title: "Keep my chosen urgency",
        reminders: rules(cycle()),
      })
    ).data.record;
    for (const [priority, importance] of [
      [1, "urgent"],
      [2, "urgent"],
      [4, "quiet"],
      [3, "normal"],
    ]) {
      const saved = await s.send(
        "task.update",
        item.id,
        {
          priority,
          reminders: rules({ ...cycle(), importance }),
          reminder_options: item.data.reminder_options,
        },
        item.revision,
      );
      assert.equal(saved.status, 200, JSON.stringify(saved.data));
      item = saved.data.record;
      assert.equal(item.data.priority, priority);
      assert.equal(
        item.data.reminder_interval_rules.interval.importance,
        importance,
      );
    }
  } finally {
    await s.f.close();
  }
});
test("interval anchors and urgency survive portable server and phone-style imports without duplicates", async () => {
  const source = await scene(),
    target = await scene();
  try {
    const interval = cycle("months", 1);
    await source.send("task.create", randomUUID(), {
      title: "Monthly undated task",
      reminders: rules(interval),
    });
    const exported = (await source.transfer("export")).data;
    const phone = { ...exported, records: (await source.snapshot()).records };
    for (const manifest of [exported, phone, exported]) {
      const preview = await target.transfer("prepare", { manifest });
      assert.equal(preview.status, 200, JSON.stringify(preview.data));
      const applied = await target.transfer("commit", {
        plan_id: preview.data.plan_id,
      });
      assert.equal(applied.status, 200, JSON.stringify(applied.data));
    }
    const tasks = (await target.snapshot()).records.filter(
      (r) => r.kind === "task",
    );
    assert.equal(tasks.length, 1);
    assert.deepEqual(tasks[0].data.reminder_interval_rules.interval, interval);
  } finally {
    await source.f.close();
    await target.f.close();
  }
});
