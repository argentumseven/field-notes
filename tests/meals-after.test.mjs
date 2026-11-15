import { test } from "node:test";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { fixture, password } from "./support.mjs";
const profile = {
  importance: "normal",
  sound: "chime",
  volume: 75,
  vibrate: true,
};
test("meal schedules and labelled before/after reminders survive restart, old writers and portable transfer", async () => {
  const f = await fixture();
  try {
    const token = await f.login(),
      basis = (await f.request("/api/snapshot", { token })).data;
    const send = (data, revision = null, target = id) =>
      f.request("/api/commands", {
        token,
        body: {
          operation_id: randomUUID(),
          epoch: basis.epoch,
          generation: basis.generation,
          action: revision === null ? "event.create" : "event.update",
          target,
          expected_revision: revision,
          data,
        },
      });
    const snapshot = async () =>
      (await f.request("/api/snapshot", { token })).data;
    const id = randomUUID(),
      rules = {
        timing_version: 4,
        alerts: [
          {
            id: randomUUID(),
            minutes_before: 30,
            label: "Start the rice",
            ...profile,
          },
          {
            id: randomUUID(),
            minutes_before: 0,
            after_start: { amount: 45, unit: "minutes", zone: "UTC" },
            label: "Pack leftovers",
            ...profile,
          },
        ],
        repeat: null,
      };
    assert.equal(
      (
        await send({
          title: "Roast dinner",
          meal_kind: "dinner",
          notes: "Chicken and vegetables",
          starts_at: 1794765600,
          ends_at: null,
          all_day: false,
          reminders: rules,
          recurrence: {
            frequency: "weekly",
            interval: 2,
            weekdays: [7],
            dates: [],
            until: "",
            excluded: [],
            zone: "UTC",
          },
        })
      ).status,
      200,
    );
    let item = (await snapshot()).records.find((r) => r.id === id);
    assert.deepEqual(item.data.reminder_after_rules, rules);
    for (const field of [
      "reminders",
      "reminder_timing_rules",
      "reminder_schedule_rules",
    ]) {
      assert.equal(item.data[field].alerts.length, 1);
      assert(!item.data[field].alerts[0].label);
      assert(!item.data[field].alerts[0].after_start);
    }
    const before = (await snapshot()).sequence;
    const blocked = await send(
      {
        notes: "Must not replace",
        reminders: { alerts: [], repeat: null, timing_version: 3 },
      },
      item.revision,
    );
    assert.equal(blocked.status, 409);
    assert.equal((await snapshot()).sequence, before);
    assert.equal(
      (await send({ notes: "Extra salad" }, item.revision)).status,
      200,
    );
    item = (await snapshot()).records.find((r) => r.id === id);
    assert.equal(item.data.meal_kind, "dinner");
    assert.deepEqual(item.data.reminder_after_rules, rules);
    for (const broken of [
      {
        ...rules,
        alerts: [
          {
            ...rules.alerts[1],
            after_start: { amount: -1, unit: "minutes", zone: "UTC" },
          },
        ],
      },
      { ...rules, timing_version: 3 },
      {
        ...rules,
        alerts: [
          {
            ...rules.alerts[1],
            lead_time: { amount: 1, unit: "hours", zone: "UTC" },
          },
        ],
      },
    ])
      assert.equal(
        (
          await send(
            { reminders: broken, recurrence: item.data.recurrence },
            item.revision,
          )
        ).status,
        broken.timing_version === 3 ? 409 : 400,
      );
    assert.equal(
      (await send({ meal_kind: "unrecognised" }, item.revision)).status,
      400,
    );
    await f.stop();
    await f.start();
    item = (await snapshot()).records.find((r) => r.id === id);
    assert.deepEqual(item.data.reminder_after_rules, rules);
    await f.request("/api/reauth", { token, body: { password } });
    const exported = await f.request("/api/transfer/export", { token });
    assert.equal(exported.status, 200);
    const text = JSON.stringify(exported.data);
    assert(text.includes("Pack leftovers"));
    assert(text.includes("dinner"));
    assert(text.includes("after_start"));
    const target = await fixture();
    try {
      const recipient = await target.login();
      for (const manifest of [
        exported.data,
        { ...exported.data, records: (await snapshot()).records },
        exported.data,
      ]) {
        const preview = await target.request("/api/transfer/prepare", {
          token: recipient,
          body: { manifest },
        });
        assert.equal(preview.status, 200, JSON.stringify(preview.data));
        const result = await target.request("/api/transfer/commit", {
          token: recipient,
          body: { plan_id: preview.data.plan_id },
        });
        assert.equal(result.status, 200, JSON.stringify(result.data));
      }
      const imported = (
        await target.request("/api/snapshot", { token: recipient })
      ).data.records.filter((r) => r.kind === "event");
      assert.equal(imported.length, 1);
      assert.equal(imported[0].data.meal_kind, "dinner");
      assert.deepEqual(imported[0].data.reminder_after_rules, rules);
    } finally {
      await target.close();
    }
  } finally {
    await f.close();
  }
});

test("regular events omit empty meal metadata and old archives deduplicate unchanged content", async () => {
  const f = await fixture(),
    target = await fixture();
  try {
    const token = await f.login(),
      basis = (await f.request("/api/snapshot", { token })).data;
    const created = await f.request("/api/commands", {
      token,
      body: {
        operation_id: randomUUID(),
        epoch: basis.epoch,
        generation: basis.generation,
        action: "event.create",
        target: randomUUID(),
        expected_revision: null,
        data: {
          title: "Ordinary appointment",
          meal_kind: "",
          starts_at: 1794765600,
          ends_at: null,
          all_day: false,
        },
      },
    });
    assert.equal(created.status, 200);
    assert.equal(created.data.record.data.meal_kind, undefined);
    const exported = (await f.request("/api/transfer/export", { token })).data,
      other = structuredClone(exported);
    other.records[0].data.meal_kind = "";
    const auth = await target.login();
    for (const manifest of [exported, other, exported]) {
      const plan = await target.request("/api/transfer/prepare", {
        token: auth,
        body: { manifest },
      });
      assert.equal(plan.status, 200, JSON.stringify(plan.data));
      const applied = await target.request("/api/transfer/commit", {
        token: auth,
        body: { plan_id: plan.data.plan_id },
      });
      assert.equal(applied.status, 200);
    }
    assert.equal(
      (
        await target.request("/api/snapshot", { token: auth })
      ).data.records.filter((r) => r.kind === "event").length,
      1,
    );
  } finally {
    await f.close();
    await target.close();
  }
});
