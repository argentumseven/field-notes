import { test } from "node:test";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";
import vm from "node:vm";
import { fixture } from "./support.mjs";
const ctx = {};
vm.runInNewContext(readFileSync("apps/admin/cooking.js", "utf8"), ctx);
const cooking = ctx.FieldCooking;
function plan() {
  const a = randomUUID(),
    b = randomUUID(),
    c = randomUUID();
  return {
    version: 1,
    items: [
      {
        id: randomUUID(),
        name: "Anything I am cooking",
        notes: "User-defined",
        stages: [
          {
            id: a,
            name: "Long cook",
            duration_minutes: 600,
            offset_minutes: 0,
            wait_minutes: 0,
            after: [],
            check_every_minutes: 60,
            notes: "Check temperature",
            notify_start: true,
            notify_end: true,
          },
          {
            id: b,
            name: "Rest",
            duration_minutes: 60,
            offset_minutes: 0,
            wait_minutes: 15,
            after: [a],
            check_every_minutes: 0,
            notes: "",
            notify_start: true,
            notify_end: true,
          },
        ],
      },
      {
        id: randomUUID(),
        name: "Another item",
        notes: "",
        stages: [
          {
            id: c,
            name: "Finish together",
            duration_minutes: 30,
            offset_minutes: 0,
            wait_minutes: 0,
            after: [b],
            check_every_minutes: 0,
            notes: "",
            notify_start: true,
            notify_end: true,
          },
        ],
      },
    ],
    progress: [],
    profile: cooking.profile(),
  };
}
test("backward dependencies and actual progress drive an overnight plan, with loop rejection", () => {
  const p = plan(),
    serve = 1794765600;
  let times = cooking.schedule(p, serve);
  assert.equal(times[0].start, serve - 705 * 60);
  assert.equal(times[1].start, times[0].end + 900);
  assert.equal(times.at(-1).end, serve);
  p.progress.push({
    occurrence: serve,
    stage_id: times[0].stage.id,
    state: "running",
    at: times[0].start + 1800,
  });
  times = cooking.schedule(p, serve);
  assert.equal(times.at(-1).end, serve + 1800);
  assert.equal(
    cooking.schedule(p, serve + 7 * 86400).at(-1).end,
    serve + 7 * 86400,
    "progress stays with its date",
  );
  p.items[0].stages[0].after = [p.items[1].stages[0].id];
  assert.throws(() => cooking.schedule(p, serve), /loop/);
});
test("cooking plan persistence, revision fences, occurrence progress, old writers and portable files", async () => {
  const f = await fixture();
  try {
    const token = await f.login(),
      basis = (await f.request("/api/snapshot", { token })).data,
      id = randomUUID(),
      p = plan(),
      serve = 1794765600;
    const command = (
      action,
      data,
      revision = null,
      operation_id = randomUUID(),
    ) => ({
      operation_id,
      epoch: basis.epoch,
      generation: basis.generation,
      target: id,
      action,
      expected_revision: revision,
      data,
    });
    const send = (c) => f.request("/api/commands", { token, body: c });
    const row = async () =>
      (await f.request("/api/snapshot", { token })).data.records.find(
        (r) => r.id === id,
      );
    assert.equal(
      (
        await send(
          command("event.create", {
            title: "Free-form dinner",
            meal_kind: "dinner",
            all_day: false,
            starts_at: serve,
            ends_at: null,
            notes: "",
            recurrence: {
              frequency: "weekly",
              interval: 1,
              weekdays: [7],
              dates: [],
              excluded: [],
              until: "",
              zone: "UTC",
            },
            meal_plan: p,
          }),
        )
      ).status,
      200,
    );
    let r = await row();
    assert.deepEqual(r.data.meal_plan, JSON.parse(JSON.stringify(p)));
    const stage = p.items[0].stages[0].id,
      c = command(
        "event.meal_stage",
        {
          occurrence: serve,
          stage_id: stage,
          state: "running",
          at: serve - 660 * 60,
        },
        r.revision,
      );
    assert.equal((await send(c)).status, 200);
    assert.equal((await send(c)).status, 200, "replay");
    r = await row();
    assert.equal(r.data.meal_plan.progress.length, 1);
    assert.equal(
      (
        await send(
          command(
            "event.meal_stage",
            {
              occurrence: serve + 86400,
              stage_id: stage,
              state: "done",
              at: serve,
            },
            r.revision,
          ),
        )
      ).status,
      409,
      "made-up recurring date",
    );
    assert.equal(
      (
        await send(
          command(
            "event.update",
            { starts_at: serve + 3600, recurrence: r.data.recurrence },
            r.revision,
          ),
        )
      ).status,
      409,
      "old writer cannot move hidden plan",
    );
    assert.equal(
      (
        await send(
          command(
            "event.update",
            { meal_plan: p, notes: "Plan edits preserve actual progress" },
            r.revision,
          ),
        )
      ).status,
      200,
    );
    r = await row();
    assert.equal(r.data.meal_plan.progress.length, 1);
    for (const invalid of [
      5,
      "bad",
      [],
      { version: 1, items: null, profile: p.profile, progress: [] },
    ])
      assert.equal(
        (
          await send(
            command("event.update", { meal_plan: invalid }, r.revision),
          )
        ).status,
        400,
      );
    const cyclic = structuredClone(p);
    cyclic.items[0].stages[0].after = [cyclic.items[1].stages[0].id];
    assert.equal(
      (await send(command("event.update", { meal_plan: cyclic }, r.revision)))
        .status,
      400,
    );
    assert.equal(
      (
        await send(
          command(
            "event.meal_stage",
            {
              occurrence: serve + 7 * 86400,
              stage_id: stage,
              state: "done",
              at: serve + 7 * 86400,
            },
            r.revision,
          ),
        )
      ).status,
      200,
    );
    r = await row();
    assert.equal(r.data.meal_plan.progress.length, 2);
    await f.stop();
    await f.start();
    assert.deepEqual((await row()).data.meal_plan, r.data.meal_plan);
    await f.request("/api/reauth", {
      token,
      body: { password: "household-test-password-only" },
    });
    const exported = await f.request("/api/transfer/export", { token });
    assert.equal(exported.status, 200);
    assert(JSON.stringify(exported.data).includes("meal_plan"));
    const target = await fixture();
    try {
      const recipient = await target.login();
      for (const manifest of [exported.data, exported.data]) {
        const preview = await target.request("/api/transfer/prepare", {
          token: recipient,
          body: { manifest },
        });
        assert.equal(preview.status, 200, JSON.stringify(preview.data));
        const commit = await target.request("/api/transfer/commit", {
          token: recipient,
          body: { plan_id: preview.data.plan_id },
        });
        assert.equal(commit.status, 200, JSON.stringify(commit.data));
      }
      const imported = (
        await target.request("/api/snapshot", { token: recipient })
      ).data.records.filter((r) => r.kind === "event");
      assert.equal(imported.length, 1);
      assert.deepEqual(imported[0].data.meal_plan, r.data.meal_plan);
    } finally {
      await target.close();
    }
  } finally {
    await f.close();
  }
});

test("moving ready times/dates preserves actual clocks; repeat edits cannot orphan live preparation", async () => {
  const f = await fixture();
  try {
    const token = await f.login(),
      basis = (await f.request("/api/snapshot", { token })).data,
      id = randomUUID(),
      p = plan(),
      serve = 1794765600;
    const weekly = {
      frequency: "weekly",
      interval: 1,
      weekdays: [7],
      dates: [],
      excluded: [],
      until: "",
      zone: "UTC",
    };
    const send = (action, data, revision = null) =>
      f.request("/api/commands", {
        token,
        body: {
          operation_id: randomUUID(),
          epoch: basis.epoch,
          generation: basis.generation,
          action,
          target: id,
          expected_revision: revision,
          data,
        },
      });
    const row = async () =>
      (await f.request("/api/snapshot", { token })).data.records.find(
        (r) => r.id === id,
      );
    assert.equal(
      (
        await send("event.create", {
          title: "Birthday cake or anything else",
          meal_kind: "dinner",
          all_day: false,
          starts_at: serve,
          ends_at: null,
          notes: "",
          recurrence: weekly,
          meal_plan: p,
        })
      ).status,
      200,
    );
    let r = await row();
    assert.equal((await send('event.update',{notes:'Old client note edit',starts_at:serve,ends_at:0,all_day:false,recurrence:weekly,meal_kind:'dinner'},r.revision)).status,200,'old clients may echo no end as zero while preserving hidden preparation');
    r=await row();assert.equal(r.data.ends_at,null);assert.deepEqual(r.data.meal_plan,JSON.parse(JSON.stringify(p)));

    const stage = p.items[0].stages[0].id;
    for (const occurrence of [serve, serve + 7 * 86400]) {
      assert.equal(
        (
          await send(
            "event.meal_stage",
            {
              occurrence,
              stage_id: stage,
              state: "running",
              at: occurrence - 600 * 60,
            },
            r.revision,
          )
        ).status,
        200,
      );
      r = await row();
    }
    const actual = r.data.meal_plan.progress.map((p) => p.at);
    assert.equal(
      (
        await send(
          "event.update",
          { starts_at: serve + 3600, recurrence: weekly, meal_plan: p },
          r.revision,
        )
      ).status,
      200,
    );
    r = await row();
    assert.deepEqual(
      r.data.meal_plan.progress.map((p) => p.occurrence),
      [serve + 3600, serve + 7 * 86400 + 3600],
    );
    assert.deepEqual(
      r.data.meal_plan.progress.map((p) => p.at),
      actual,
    );
    const before = JSON.stringify(r);
    assert.equal(
      (
        await send(
          "event.update",
          { recurrence: { ...weekly, interval: 0 }, meal_plan: p },
          r.revision,
        )
      ).status,
      400,
      "validate repeat rules before mapping progress",
    );
    assert.equal(
      (
        await send(
          "event.update",
          {
            recurrence: {
              ...weekly,
              frequency: "dates",
              dates: ["2026-12-25"],
            },
            meal_plan: p,
          },
          r.revision,
        )
      ).status,
      409,
      "live later occurrence cannot disappear",
    );
    assert.equal(JSON.stringify(await row()), before);
    assert.equal(
      (
        await send(
          "event.update",
          {
            starts_at: serve + 7 * 86400 + 3600,
            recurrence: weekly,
            meal_plan: p,
          },
          r.revision,
        )
      ).status,
      200,
    );
    r = await row();
    assert.deepEqual(
      r.data.meal_plan.progress.map((p) => p.occurrence),
      [serve + 7 * 86400 + 3600, serve + 14 * 86400 + 3600],
    );
    assert.deepEqual(
      r.data.meal_plan.progress.map((p) => p.at),
      actual,
    );
    assert.equal(
      (
        await send(
          "event.update",
          { recurrence: null, meal_plan: p },
          r.revision,
        )
      ).status,
      409,
    );
    await f.stop();
    await f.start();
    assert.deepEqual((await row()).data.meal_plan, r.data.meal_plan);
  } finally {
    await f.close();
  }
});
