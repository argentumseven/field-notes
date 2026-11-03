import { test } from "node:test";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { fixture, password } from "./support.mjs";
const profile = () => ({
  id: randomUUID(),
  importance: "normal",
  sound: "chime",
  volume: 75,
  vibrate: true,
});
const repeat = () => ({
  ...profile(),
  weekdays: [2],
  starts_on: "2026-11-03",
  ends_on: "",
  from: "09:00",
  until: "18:00",
  every_minutes: 60,
  zone: "America/New_York",
});
async function commands(f) {
  const token = await f.login(),
    basis = (await f.request("/api/snapshot", { token })).data;
  return {
    token,
    send: (action, target, data = {}, revision = null) =>
      f.request("/api/commands", {
        token,
        body: {
          operation_id: randomUUID(),
          epoch: basis.epoch,
          generation: basis.generation,
          action,
          target,
          expected_revision: revision,
          data,
        },
      }),
  };
}

test("rolling list schemas preserve item values and stale checked-item removal cannot delete a reopened item", async () => {
  const f = await fixture();
  try {
    const { send, token } = await commands(f),
      list = randomUUID(),
      item = randomUUID(),
      other = randomUUID();
    const created = await send("list.create", list, {
      title: "Rolling list",
      template: "groceries",
    });
    assert.equal(created.status, 200);
    assert.deepEqual(created.data.record.data.fields, ["quantity", "notes"]);
    assert.equal(created.data.record.data.perpetual, true);
    let r = await send("task.create", item, {
      project_id: list,
      title: "A useful item",
      quantity: "2 boxes",
      link_url: "https://example.invalid/item",
      price: "49.95",
      currency: "USD",
    });
    assert.equal(r.status, 200);
    assert.equal(r.data.record.data.created_by_name, "owner");
    assert.equal(
      (
        await send("task.create", other, {
          project_id: list,
          title: "Unchecked item",
        })
      ).status,
      200,
    );
    assert.equal((await send("task.complete", item, {}, 1)).status, 200);
    assert.equal((await send("task.reopen", item, {}, 2)).status, 200);
    assert.equal((await send("task.delete", item, {}, 2)).status, 409);
    assert.equal(
      (await send("list.update", list, { fields: ["link_url", "images"] }, 1))
        .status,
      200,
    );
    const items = (await f.request("/api/snapshot", { token })).data.records;
    assert.equal(items.find((r) => r.id === item).data.price, "49.95");
    assert.equal(items.find((r) => r.id === other).data.status, "todo");
    for (const fields of [["not-a-field"], ["price", "price"]])
      assert.equal(
        (await send("list.update", list, { fields }, 2)).status,
        400,
      );
    for (const data of [
      { price: "-1" },
      { price: "NaN" },
      { price: "1.999" },
      { currency: "usd" },
      { link_url: "javascript:alert(1)" },
      { link_url: "https://user:secret@example.invalid/" },
    ])
      assert.equal((await send("task.update", item, data, 3)).status, 400);
    const comment = randomUUID();
    assert.equal(
      (
        await send("comment.create", comment, {
          task_id: item,
          body: "More context",
        })
      ).status,
      200,
    );
    const parent = (
      await f.request("/api/snapshot", { token })
    ).data.records.find((r) => r.id === item);
    assert.equal(parent.revision, 3);
    assert.equal(parent.data.last_activity_by_name, "owner");
    assert(parent.data.last_activity_at > 0);
    const project = randomUUID();
    assert.equal(
      (
        await send("project.create", project, {
          title: "Project",
          template: "groceries",
        })
      ).status,
      400,
    );
  } finally {
    await f.close();
  }
});
test("start-only events support multiple independent sound profiles and bounded recurring windows", async () => {
  const f = await fixture();
  try {
    const { send } = await commands(f),
      event = randomUUID(),
      rules = {
        alerts: [
          { ...profile(), minutes_before: 30, importance: "quiet" },
          {
            ...profile(),
            minutes_before: 0,
            importance: "urgent",
            sound: "bell",
            volume: 100,
          },
        ],
        repeat: repeat(),
      };
    const created = await send("event.create", event, {
      title: "A start marker",
      all_day: false,
      starts_at: 1800000000,
      ends_at: null,
      reminders: rules,
    });
    assert.equal(created.status, 200, JSON.stringify(created.data));
    assert.equal(created.data.record.data.ends_at, null);
    assert.deepEqual(created.data.record.data.reminders, rules);
    const update = await send(
      "event.update",
      event,
      { notes: "Edited later" },
      1,
    );
    assert.equal(update.status, 200);
    assert.deepEqual(update.data.record.data.reminders, rules);
    assert.equal(update.data.record.data.updated_by_name, "owner");
    assert.equal(
      (await send("event.update", event, { ends_at: 1799999999 }, 2)).status,
      400,
    );
    const invalid = [
      {
        alerts: Array.from({ length: 9 }, () => ({
          ...profile(),
          minutes_before: 0,
        })),
        repeat: null,
      },
      { alerts: [], repeat: { ...repeat(), every_minutes: 0 } },
      { alerts: [], repeat: { ...repeat(), zone: "Not/AZone" } },
      { alerts: [], repeat: { ...repeat(), weekdays: [1, 1] } },
      {
        alerts: [],
        repeat: {
          ...repeat(),
          from: "00:00",
          until: "00:00",
          every_minutes: 0,
        },
      },
      {
        alerts: [
          { ...profile(), minutes_before: 0, sound: "https://example.invalid" },
        ],
        repeat: null,
      },
    ];
    for (const reminders of invalid)
      assert.equal(
        (await send("event.update", event, { reminders }, 2)).status,
        400,
      );
    assert.equal(
      (
        await send(
          "event.update",
          event,
          {
            reminders: {
              alerts: [],
              repeat: {
                ...repeat(),
                from: "00:00",
                until: "00:00",
                every_minutes: 15,
              },
            },
          },
          2,
        )
      ).status,
      200,
    );
    const task = randomUUID();
    assert.equal(
      (
        await send("task.create", task, {
          title: "Undated routine",
          reminders: { alerts: [], repeat: repeat() },
        })
      ).status,
      200,
    );
  } finally {
    await f.close();
  }
});
