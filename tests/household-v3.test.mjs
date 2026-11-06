import { test } from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { runInNewContext } from "node:vm";
import { randomUUID } from "node:crypto";
import { fixture } from "./support.mjs";

const window = {};
runInNewContext(
  await readFile(
    new URL("../apps/admin/recurrence.js", import.meta.url),
    "utf8",
  ),
  { window },
);
const recurrence = window.FieldRecurrence;
const repeat = (extra = {}) => ({
  frequency: "daily",
  interval: 1,
  weekdays: [],
  dates: [],
  until: "",
  excluded: [],
  zone: "America/New_York",
  ...extra,
});

test("yearly presets and long-range previews agree with native calendar cases", async () => {
  const cases = JSON.parse(
    await readFile(
      new URL("./fixtures/repeat-previews.json", import.meta.url),
      "utf8",
    ),
  );
  for (const item of cases) {
    const rule = repeat(item);
    assert.deepEqual(
      Array.from(recurrence.nextDates(rule, item.first, item.from)),
      item.expected,
      item.name,
    );
  }
  assert.equal(
    recurrence.preset(repeat({ frequency: "monthly", interval: 12 })),
    "yearly",
  );
  assert.equal(
    recurrence.label(repeat({ frequency: "monthly", interval: 12 })),
    "Every year",
  );
  assert.equal(
    recurrence.label(repeat({ frequency: "monthly", interval: 24 })),
    "Every 2 years",
  );
});
const event = (day, extra = {}) => ({
  id: "event",
  kind: "event",
  data: {
    title: "Check-in",
    all_day: false,
    starts_at: recurrence.instant(day, "10:00:00", "America/New_York"),
    ends_at: recurrence.instant(day, "11:00:00", "America/New_York"),
    recurrence: repeat(),
    ...extra,
  },
});

test("recurrence follows wall time across DST and rejects skipped local times", () => {
  assert.equal(
    recurrence.instant("2026-03-07", "10:00:00", "America/New_York"),
    Date.parse("2026-03-07T15:00Z") / 1000,
  );
  assert.equal(
    recurrence.instant("2026-03-08", "10:00:00", "America/New_York"),
    Date.parse("2026-03-08T14:00Z") / 1000,
  );
  assert.equal(
    recurrence.instant("2026-03-08", "02:30:00", "America/New_York"),
    null,
  );
  assert.equal(
    recurrence.instant("2026-11-01", "01:30:00", "America/New_York"),
    Date.parse("2026-11-01T05:30Z") / 1000,
  );
});
test("a repeating series preserves the exact second autumn clock occurrence of its anchor", () => {
  const at = Date.parse("2026-11-01T06:30:00Z") / 1000,
    record = event("2026-11-01", { starts_at: at, ends_at: null });
  assert.equal(
    recurrence.instances(record, "2026-11-01", "2026-11-02")[0].data.starts_at,
    at,
  );
});
test("irregular repeat patterns, alternating weeks and exception dates agree with shared-client rules", () => {
  const rule = repeat({
    frequency: "weekly",
    interval: 2,
    weekdays: [1, 3, 5],
    until: "2026-11-20",
  });
  const dates = [];
  for (let d = "2026-11-02"; d < "2026-11-23"; d = recurrence.shift(d, 1))
    if (recurrence.matches(rule, "2026-11-02", d)) dates.push(d);
  assert.deepEqual(dates, [
    "2026-11-02",
    "2026-11-04",
    "2026-11-06",
    "2026-11-16",
    "2026-11-18",
    "2026-11-20",
  ]);
  const specific = repeat({
    frequency: "dates",
    dates: ["2026-11-05", "2026-11-09", "2026-11-21"],
    excluded: ["2026-11-09"],
  });
  assert.equal(recurrence.matches(specific, "2026-11-05", "2026-11-09"), false);
  assert.equal(recurrence.matches(specific, "2026-11-05", "2026-11-21"), true);
});
test("monthly day 31 skips missing dates and range expansion has a strict bound", () => {
  const record = event("2026-01-31", {
    recurrence: repeat({ frequency: "monthly" }),
  });
  assert.deepEqual(
    Array.from(
      recurrence.instances(record, "2026-01-31", "2026-04-01"),
      (e) => e.data.occurrence_date,
    ),
    ["2026-01-31", "2026-03-31"],
  );
  assert.equal(
    recurrence.instances(record, "2026-01-01", "2030-01-01").length,
    0,
  );
});
async function commands(f) {
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
  };
}
test("new events/tasks/notes default to everyone, while explicit personal choices survive edits", async () => {
  const f = await fixture();
  try {
    const { send, token } = await commands(f);
    for (const [kind, data] of [
      [
        "event",
        {
          title: "Shared check-in",
          all_day: false,
          starts_at: 1793876400,
          ends_at: null,
        },
      ],
      ["task", { title: "Shared task" }],
      [
        "note",
        {
          title: "Maybe a greenhouse",
          body: "Compare winter heating",
          on_radar: true,
          remind_at: 1793876400,
        },
      ],
    ]) {
      const id = randomUUID(),
        created = await send(kind + ".create", id, data);
      assert.equal(created.status, 200, JSON.stringify(created.data));
      assert.equal(
        created.data.record.data.reminder_options.audience.mode,
        "everyone",
      );
      assert.equal(
        created.data.record.data.created_sequence,
        created.data.sequence,
      );
      const person = (await f.request("/api/snapshot", { token })).data.actor
          .user_id,
        options = {
          audience: { mode: "people", people: [person] },
          playback: {},
        };
      let r = await send(
        kind + ".update",
        id,
        { reminder_options: options },
        1,
      );
      assert.equal(r.status, 200, JSON.stringify(r.data));
      r = await send(kind + ".update", id, { title: "Edited title" }, 2);
      assert.equal(r.status, 200);
      assert.deepEqual(r.data.record.data.reminder_options, options);
    }
  } finally {
    await f.close();
  }
});
test("grocery stores, categories and independent list mutes persist without deleting reminder rules", async () => {
  const f = await fixture();
  try {
    const { send } = await commands(f),
      list = randomUUID(),
      item = randomUUID();
    let r = await send("list.create", list, {
      title: "Costco Shopping List",
      template: "groceries",
      category: "food",
      notify_additions: false,
      item_reminders: false,
    });
    assert.equal(r.status, 200, JSON.stringify(r.data));
    assert.ok(r.data.record.data.fields.includes("store"));
    const reminders = {
      alerts: [
        {
          id: randomUUID(),
          minutes_before: 0,
          importance: "normal",
          sound: "chime",
          volume: 75,
          vibrate: true,
        },
      ],
      repeat: null,
    };
    r = await send("task.create", item, {
      title: "Bananas",
      project_id: list,
      store: "Costco",
      quantity: "1 bunch",
      category: "food",
      reminders,
    });
    assert.equal(r.status, 200, JSON.stringify(r.data));
    assert.equal(r.data.record.data.store, "Costco");
    assert.deepEqual(r.data.record.data.reminders, reminders);
    r = await send("list.update", list, { title: "Weekly groceries" }, 1);
    assert.equal(r.status, 200);
    assert.equal(r.data.record.data.notify_additions, false);
    assert.equal(r.data.record.data.item_reminders, false);
    assert.equal(
      (await send("list.update", list, { notify_additions: "false" }, 2))
        .status,
      400,
    );
    assert.equal(
      (await send("task.update", item, { category: "unsupported" }, 1)).status,
      400,
    );
  } finally {
    await f.close();
  }
});
test("one repeating series persists, invalid rules are atomic and old editors cannot overwrite its schedule", async () => {
  const f = await fixture();
  try {
    const { send, token } = await commands(f),
      id = randomUUID(),
      rule = repeat({
        frequency: "weekly",
        interval: 2,
        weekdays: [1, 3, 5],
        excluded: ["2026-11-16"],
      });
    let r = await send("event.create", id, {
      title: "Family plan",
      all_day: false,
      starts_at: 1793631600,
      ends_at: 1793635200,
      recurrence: rule,
    });
    assert.equal(r.status, 200, JSON.stringify(r.data));
    r = await send(
      "event.update",
      id,
      { notes: "Title and notes remain editable by compatible old clients" },
      1,
    );
    assert.equal(r.status, 200);
    assert.deepEqual(r.data.record.data.recurrence, rule);
    assert.equal(
      (await send("event.update", id, { starts_at: 1793631600 + 3600 }, 2))
        .status,
      409,
    );
    for (const invalid of [
      { ...rule, weekdays: [1, 1] },
      { ...rule, interval: 0 },
      { ...rule, zone: "Not/AZone" },
      { ...rule, dates: ["2026-02-30"] },
      { ...rule, excluded: Array(129).fill("2026-11-16") },
    ])
      assert.equal(
        (await send("event.update", id, { recurrence: invalid }, 2)).status,
        400,
      );
    const snapshot = (await f.request("/api/snapshot", { token })).data;
    assert.equal(snapshot.records.filter((r) => r.kind === "event").length, 1);
    assert.deepEqual(
      snapshot.records.find((r) => r.id === id).data.recurrence,
      rule,
    );
  } finally {
    await f.close();
  }
});
