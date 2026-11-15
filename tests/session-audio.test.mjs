import { test } from "node:test";
import assert from "node:assert/strict";
import { DatabaseSync } from "node:sqlite";
import { join } from "node:path";
import { randomUUID, createHash } from "node:crypto";
import { fixture, password } from "./support.mjs";
test("browser sessions lock after inactivity, background reads cannot extend them, phone tokens retain their lifetime", async () => {
  const f = await fixture();
  try {
    assert.equal((await f.request("/api/snapshot")).status, 401);
    const phone = await f.login();
    const login = await f.request("/api/login", {
      origin: f.admin,
      body: { name: "owner", password, device: "Browser" },
    });
    assert.equal(login.status, 200);
    assert(!login.headers.get("set-cookie").includes("Max-Age"));
    const cookie = login.headers.get("set-cookie").split(";")[0],
      token = cookie.split("=")[1],
      id = createHash("sha256").update(token).digest("hex");
    const db = new DatabaseSync(join(f.data, "field-notes.sqlite3"));
    try {
      const session = () =>
        db.prepare("SELECT * FROM sessions WHERE id=?").get(id);
      const original = session();
      assert(original.expires - original.created <= 1801);
      assert.equal(
        (await f.request("/api/snapshot", { headers: { cookie } })).status,
        200,
      );
      assert.equal(session().expires, original.expires);
      db.prepare("UPDATE sessions SET expires=? WHERE id=?").run(
        Math.floor(Date.now() / 1000) + 20,
        id,
      );
      assert.equal(
        (
          await f.request("/api/head", {
            headers: { cookie, "x-field-activity": "1" },
          })
        ).status,
        200,
      );
      assert(session().expires > Math.floor(Date.now() / 1000) + 1700);
      db.prepare("UPDATE sessions SET created=? WHERE id=?").run(
        Math.floor(Date.now() / 1000) - 43201,
        id,
      );
      assert.equal(
        (
          await f.request("/api/head", {
            headers: { cookie, "x-field-activity": "1" },
          })
        ).status,
        401,
      );
      assert.equal(
        (await f.request("/api/head", { token: phone, base: f.device })).status,
        200,
      );
      assert.equal(
        (
          await f.request("/api/head", {
            headers: { cookie: "field_session=" + phone },
          })
        ).status,
        401,
        "legacy cookie requires one login",
      );
    } finally {
      db.close();
    }
  } finally {
    await f.close();
  }
});
test("boosted dismissible audio projects safely to old clients, rejects destructive old writes, and persists canonical data", async () => {
  const f = await fixture();
  try {
    const token = await f.login(),
      basis = (await f.request("/api/snapshot", { token })).data,
      id = randomUUID(),
      rid = randomUUID(),
      options = {
        playback_version: 3,
        audience: { mode: "everyone", people: [] },
        playback: {
          [rid]: {
            tone: "klaxon",
            duration_seconds: 0,
            alarm: true,
            unmute: false,
            alarm_volume: 100,
            gain_percent: 200,
            until_dismissed: true,
          },
        },
      };
    const send = (data, revision = null) =>
      f.request("/api/commands", {
        token,
        body: {
          operation_id: randomUUID(),
          epoch: basis.epoch,
          generation: basis.generation,
          target: id,
          action: revision === null ? "event.create" : "event.update",
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
        await send({
          title: "Alarm",
          all_day: false,
          starts_at: 1794765600,
          ends_at: null,
          notes: "",
          reminder_options: options,
        })
      ).status,
      200,
    );
    let r = await row();
    assert.deepEqual(r.data.reminder_audio_options, options);
    assert.equal(r.data.reminder_options.playback_version, 2);
    assert.equal(r.data.reminder_options.playback[rid].tone, "beacon");
    assert.equal(r.data.reminder_options.playback[rid].duration_seconds, 5);
    assert(!("gain_percent" in r.data.reminder_options.playback[rid]));
    assert.equal(
      (
        await send(
          { notes: "Old app notes", reminder_options: r.data.reminder_options },
          r.revision,
        )
      ).status,
      200,
    );
    r = await row();
    const old = structuredClone(r.data.reminder_options);
    old.playback[rid].duration_seconds = 10;
    assert.equal(
      (await send({ reminder_options: old }, r.revision)).status,
      409,
    );
    assert.equal(
      (
        await send(
          {
            reminder_options: {
              ...options,
              playback: {
                [rid]: { ...options.playback[rid], gain_percent: 201 },
              },
            },
          },
          r.revision,
        )
      ).status,
      400,
    );
    for (const name of ["alarm_klaxon_200", "reminder_signal_150"]) {
      const preview = await f.request("/sounds/v2/" + name + ".flac");
      assert.equal(preview.status, 200);
      assert.equal(preview.data.subarray(0, 4).toString(), "fLaC");
      assert.match(preview.headers.get("content-type"), /audio\/flac/);
    }
    await f.stop();
    await f.start();
    assert.deepEqual((await row()).data.reminder_audio_options, options);
    await f.request("/api/reauth", { token, body: { password } });
    const exported = await f.request("/api/transfer/export", { token });
    assert.equal(exported.status, 200);
    const target = await fixture();
    try {
      const recipient = await target.login();
      for (const manifest of [exported.data, exported.data]) {
        const prepared = await target.request("/api/transfer/prepare", {
          token: recipient,
          body: { manifest },
        });
        assert.equal(prepared.status, 200, JSON.stringify(prepared.data));
        const committed = await target.request("/api/transfer/commit", {
          token: recipient,
          body: { plan_id: prepared.data.plan_id },
        });
        assert.equal(committed.status, 200, JSON.stringify(committed.data));
      }
      const imported = (
        await target.request("/api/snapshot", { token: recipient })
      ).data.records.filter((r) => r.kind === "event");
      assert.equal(imported.length, 1);
      assert.deepEqual(imported[0].data.reminder_audio_options, options);
    } finally {
      await target.close();
    }
  } finally {
    await f.close();
  }
});
