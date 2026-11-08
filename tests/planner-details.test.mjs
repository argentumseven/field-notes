import { test } from "node:test";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { readFile, writeFile } from "node:fs/promises";
import { runInNewContext } from "node:vm";
import { fixture } from "./support.mjs";
const base = { importance: "normal", sound: "bell", volume: 75, vibrate: true };
const alert = (
  day = { days_before: 0, time: "09:00", zone: "America/New_York" },
) => ({ id: randomUUID(), minutes_before: 0, ...base, day_time: day });
const rules = (alerts) => ({ timing_version: 2, alerts, repeat: null });
async function scene(options) {
  const f = await fixture(options),
    token = await f.login(),
    basis = (await f.request("/api/snapshot", { token })).data;
  return {
    f,
    token,
    basis,
    snapshot: async () => (await f.request("/api/snapshot", { token })).data,
    send: (action, target, data = {}, revision = null) =>
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
      }),
    transfer: (action, body) =>
      f.request("/api/transfer/" + action, {
        token,
        ...(body ? { body } : {}),
      }),
  };
}
test("day/time reminder projection fences older editors while preserving unrelated edits", async () => {
  const s = await scene();
  try {
    const id = randomUUID(),
      a = alert(),
      old = { ...base, id: randomUUID(), minutes_before: 10 };
    let result = await s.send("event.create", id, {
      title: "All-day trip",
      all_day: true,
      start_date: "2028-03-31",
      end_date: "2028-04-01",
      reminders: rules([a, old]),
    });
    assert.equal(result.status, 200, JSON.stringify(result.data));
    let saved = (await s.snapshot()).records.find((r) => r.id === id);
    assert.deepEqual(saved.data.reminders, { alerts: [old], repeat: null });
    assert.deepEqual(saved.data.reminder_schedule_rules, rules([a, old]));
    assert(!saved.data.reminder_timing_rules.alerts.some((r) => r.day_time));
    const seq = (await s.snapshot()).sequence;
    result = await s.send(
      "event.update",
      id,
      {
        title: "Must not apply",
        reminders: { timing_version: 1, alerts: [], repeat: null },
      },
      saved.revision,
    );
    assert.equal(result.status, 409);
    assert.equal((await s.snapshot()).sequence, seq);
    result = await s.send(
      "event.update",
      id,
      { notes: "Older phone can still edit notes" },
      saved.revision,
    );
    assert.equal(result.status, 200);
    saved = (await s.snapshot()).records.find((r) => r.id === id);
    assert.deepEqual(saved.data.reminder_schedule_rules, rules([a, old]));
    result = await s.send(
      "event.update",
      id,
      { reminders: rules([]) },
      saved.revision,
    );
    assert.equal(result.status, 200);
    assert.equal(
      (await s.snapshot()).records.find((r) => r.id === id).data.reminders
        .alerts.length,
      0,
    );
  } finally {
    await s.f.close();
  }
});
test("invalid day/time and sound combinations cannot partially mutate a record", async () => {
  const s = await scene();
  try {
    const id = randomUUID();
    const item = await s.send("event.create", id, {
      title: "Original",
      all_day: false,
      starts_at: 1920272400,
    });
    assert.equal(item.status, 200);
    const revision = item.data.record.revision,
      seq = (await s.snapshot()).sequence;
    for (const day of [
      { days_before: -1, time: "09:00", zone: "UTC" },
      { days_before: 3661, time: "09:00", zone: "UTC" },
      { days_before: 0, time: "9:00", zone: "UTC" },
      { days_before: 0, time: "24:00", zone: "UTC" },
      { days_before: 0, time: "09:00", zone: "bad" },
    ])
      assert.equal(
        (
          await s.send(
            "event.update",
            id,
            { title: "Invalid", reminders: rules([alert(day)]) },
            revision,
          )
        ).status,
        400,
      );
    const a = alert(),
      sound = {
        tone: "bell",
        duration_seconds: 5,
        alarm: true,
        unmute: true,
        alarm_volume: 100,
        notification_unmute: true,
        notification_volume: 100,
      };
    assert.equal(
      (
        await s.send(
          "event.update",
          id,
          {
            title: "Invalid",
            reminders: rules([a]),
            reminder_options: {
              playback_version: 2,
              audience: { mode: "everyone", people: [] },
              playback: { [a.id]: sound },
            },
          },
          revision,
        )
      ).status,
      400,
    );
    assert.equal((await s.snapshot()).sequence, seq);
  } finally {
    await s.f.close();
  }
});
test("new audio survives old clients echoing options and rejects destructive legacy changes", async () => {
  const s = await scene();
  try {
    const id = randomUUID(),
      a = alert(),
      options = {
        playback_version: 2,
        audience: { mode: "everyone", people: [] },
        playback: {
          [a.id]: {
            tone: "orbit",
            duration_seconds: 0,
            alarm: false,
            unmute: false,
            alarm_volume: 100,
            notification_unmute: true,
            notification_volume: 75,
          },
        },
      };
    const created = await s.send("event.create", id, {
      title: "Reminder",
      all_day: false,
      starts_at: 1920272400,
      reminders: rules([a]),
      reminder_options: options,
    });
    assert.equal(created.status, 200, JSON.stringify(created.data));
    const old = structuredClone(options);
    delete old.playback_version;
    delete old.playback[a.id].notification_unmute;
    delete old.playback[a.id].notification_volume;
    const echo = await s.send(
      "event.update",
      id,
      { title: "Compatible edit", reminder_options: old },
      created.data.record.revision,
    );
    assert.equal(echo.status, 200);
    assert.deepEqual(
      (await s.snapshot()).records.find((r) => r.id === id).data
        .reminder_options,
      options,
    );
    old.playback[a.id].tone = "bell";
    assert.equal(
      (
        await s.send(
          "event.update",
          id,
          { title: "Rejected", reminder_options: old },
          echo.data.record.revision,
        )
      ).status,
      409,
    );
    assert.equal(
      (await s.snapshot()).records.find((r) => r.id === id).data.title,
      "Compatible edit",
    );
  } finally {
    await s.f.close();
  }
});
test("recipe and appointment details survive partial old-client edits and a server restart", async () => {
  const s = await scene();
  try {
    const note = randomUUID(),
      event = randomUUID();
    let n = await s.send("note.create", note, {
      title: "Soup",
      body: "Family recipe",
      note_type: "recipe",
      ingredients: "Lentils",
      instructions: "Simmer gently",
    });
    assert.equal(n.status, 200, JSON.stringify(n.data));
    n = await s.send(
      "note.update",
      note,
      { body: "Older phone comment" },
      n.data.record.revision,
    );
    assert.equal(n.status, 200);
    let e = await s.send("event.create", event, {
      title: "Dentist",
      all_day: false,
      starts_at: 1920272400,
      location: "123 Main Street",
    });
    assert.equal(e.status, 200);
    e = await s.send(
      "event.update",
      event,
      { notes: "Bring paperwork" },
      e.data.record.revision,
    );
    assert.equal(e.status, 200);
    await s.f.stop();
    await s.f.start();
    const records = (await s.snapshot()).records;
    assert.equal(
      records.find((r) => r.id === note).data.instructions,
      "Simmer gently",
    );
    assert.equal(records.find((r) => r.id === note).data.note_type, "recipe");
    assert.equal(
      records.find((r) => r.id === event).data.location,
      "123 Main Street",
    );
  } finally {
    await s.f.close();
  }
});
test("recipes, locations and new reminders export and import without duplicates", async () => {
  const s = await scene(),
    target = await scene();
  try {
    const a = alert();
    assert.equal(
      (
        await s.send("note.create", randomUUID(), {
          title: "Soup",
          note_type: "recipe",
          ingredients: "Lentils",
          instructions: "Simmer",
        })
      ).status,
      200,
    );
    assert.equal(
      (
        await s.send("event.create", randomUUID(), {
          title: "Dentist",
          all_day: false,
          starts_at: 1920272400,
          location: "123 Main",
          reminders: rules([a]),
        })
      ).status,
      200,
    );
    const manifest = (await s.transfer("export")).data;
    for (let n = 0; n < 2; n++) {
      const exported = structuredClone(manifest);
      if (n) exported.records = (await s.snapshot()).records;
      const p = await target.transfer("prepare", { manifest: exported });
      assert.equal(p.status, 200, JSON.stringify(p.data));
      if (n) assert.equal(p.data.new_items, 0);
      assert.equal(
        (await target.transfer("commit", { plan_id: p.data.plan_id })).status,
        200,
      );
    }
    const records = (await target.snapshot()).records;
    assert.equal(records.length, 2);
    assert.equal(
      records.find((r) => r.kind === "note").data.instructions,
      "Simmer",
    );
    assert.equal(
      records.find((r) => r.kind === "event").data.reminder_schedule_rules
        .alerts[0].day_time.time,
      "09:00",
    );
  } finally {
    await s.f.close();
    await target.f.close();
  }
});
test("clock diagnostics require authentication and reject stale or changed host samples", async () => {
  const s = await scene({ enrollment: true });
  try {
    assert.equal((await s.f.request("/api/head")).status, 401);
    let h = (await s.f.request("/api/head", { token: s.token })).data;
    assert(Math.abs(h.server_time_ms - Date.now()) < 10000);
    assert.equal(h.clock_health.status, "unknown");
    const boot = (
        await readFile("/proc/sys/kernel/random/boot_id", "utf8")
      ).trim(),
      uptime = Number((await readFile("/proc/uptime", "utf8")).split(" ")[0]);
    const report = {
      version: 1,
      boot_id: boot,
      captured_at: Date.now() / 1000,
      captured_uptime: uptime,
      status: "synchronized",
      source: "chrony",
      offset_seconds: 0.001,
    };
    const path = s.f.peerStatus.replace("peer-status.json", "clock.json");
    await writeFile(path, JSON.stringify(report));
    h = (await s.f.request("/api/head", { token: s.token })).data;
    assert.equal(h.clock_health.status, "synchronized");
    assert(!h.clock_health.boot_id);
    report.captured_at -= 60;
    await writeFile(path, JSON.stringify(report));
    assert.equal(
      (await s.f.request("/api/head", { token: s.token })).data.clock_health
        .status,
      "clock_jump",
    );
    report.boot_id = "previous-boot";
    await writeFile(path, JSON.stringify(report));
    assert.equal(
      (await s.f.request("/api/head", { token: s.token })).data.clock_health
        .status,
      "stale",
    );
  } finally {
    await s.f.close();
  }
});
test("offline holiday calendar covers observed dates and clock comparison accounts for response delay", async () => {
  const sandbox = { localStorage: { getItem: () => "on" } };
  for (const file of ["holidays", "clock-health"])
    runInNewContext(
      (await readFile("apps/admin/" + file + ".js", "utf8")) +
        "\nglobalThis." +
        (file === "holidays" ? "holidays=FieldHolidays" : "clock=FieldClock") +
        ";",
      sandbox,
    );
  assert(
    sandbox.holidays
      .year(2021)
      .get("2021-12-31")
      .includes("New Year’s Day (observed)"),
  );
  assert(
    sandbox.holidays.year(2026).get("2026-11-26").includes("Thanksgiving Day"),
  );
  assert.equal(
    sandbox.clock.warning(
      { server_time_ms: 1001000, clock_health: { status: "synchronized" } },
      1000000,
      1002000,
      2000,
    ),
    "",
  );
  assert.match(
    sandbox.clock.warning({ server_time_ms: 1100000 }, 1000000, 1002000, 2000),
    /differ/,
  );
});
