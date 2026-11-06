// Actual app capture, seeded by an isolated Rust household; dedicated AVD only.
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { mkdtemp, mkdir, readFile, writeFile, rm } from "node:fs/promises";
import { join, resolve } from "node:path";
import { tmpdir } from "node:os";
import assert from "node:assert/strict";
import { historyFixture } from "../tests/history-fixture.mjs";
const run = promisify(execFile),
  root = process.env.FIELD_NOTES_TOOLS || "/workspace/.cache/field-notes";
const adb = join(root, "android-sdk/platform-tools/adb"),
  serial = process.env.FIELD_NOTES_EMULATOR || "emulator-5554";
const call = async (args, timeout = 180000) =>
  (
    await run(adb, ["-s", serial, ...args], {
      timeout,
      maxBuffer: 6 * 1024 * 1024,
    })
  ).stdout;
if (
  !serial.startsWith("emulator-") ||
  (await call(["shell", "getprop", "ro.boot.qemu.avd_name"])).trim() !==
    "field-notes-test"
)
  throw Error("Dedicated field-notes-test AVD required.");
const temporary = await mkdtemp(join(tmpdir(), "field-notes-history-capture-"));
const downloads = resolve(
  process.env.FIELD_NOTES_DOWNLOADS || "dist/server/linux-x86_64/downloads",
);
const f = await historyFixture({ downloads });
try {
  const get = async (path, base = f.f.admin) =>
    (await f.f.request(path, { token: f.alex, base })).data;
  const history = await f.f.request("/api/history", {
    token: f.alex,
    body: { epoch: f.snapshot.epoch, generation: f.snapshot.generation },
  });
  assert.equal(history.status, 200);
  const recovery = async(action,extra={})=>f.f.request('/api/recovery',{token:f.alex,body:{action,epoch:f.snapshot.epoch,generation:f.snapshot.generation,...extra}});
  const versions=(await recovery('versions',{target:f.bananas})).data;
  const saved=versions.entries.at(-1);
  const plan=(await recovery('preview',{source:saved.id})).data;
  const before=await get('/api/snapshot');
  const result=await recovery('apply',{preview_id:plan.preview_id,operation_id:crypto.randomUUID(),confirmation:'RESTORE ITEM'});
  assert.equal(result.status,200);
  const after=await get('/api/snapshot');
  const seed = {
    snapshot: before,
    recovery_versions: versions, recovery_preview:plan, recovery_result:result.data, recovery_after:after,
    history: history.data,
    people: (await get("/api/people")).people,
    head: await get("/api/head"),
    apk_update: await get("/api/device/update", f.f.device),
  };
  assert.equal(
    seed.apk_update.available,
    true,
    "Supply a genuine verified APK download bundle for the synthetic server.",
  );
  const file = join(temporary, "history-fixture.json");
  await writeFile(file, JSON.stringify(seed), { mode: 0o600 });
  await mkdir("dist/android-test-results", { recursive: true });
  await mkdir("docs/screenshots/android", { recursive: true });
  await call(["shell", "input", "keyevent", "KEYCODE_WAKEUP"]);
  await call(["shell", "wm", "dismiss-keyguard"]);
  await call(["shell", "cmd", "statusbar", "collapse"]);
  await call([
    "shell",
    "settings",
    "put",
    "secure",
    "show_ime_with_hard_keyboard",
    "1",
  ]);
  await call(["shell", "cmd", "alarm", "set-timezone", "America/New_York"]);
  await call(["install", "-r", "dist/android-emulator/FieldNotes-debug.apk"]);
  await call([
    "install",
    "-r",
    "dist/android-emulator/FieldNotes-debug-androidTest.apk",
  ]);
  await call(["shell", "pm", "clear", "org.fieldnotes.app"]);
  await call([
    "shell",
    "pm",
    "grant",
    "org.fieldnotes.app",
    "android.permission.POST_NOTIFICATIONS",
  ]);
  await call([
    "shell",
    "appops",
    "set",
    "org.fieldnotes.app",
    "SCHEDULE_EXACT_ALARM",
    "allow",
  ]);
  await call([
    "push",
    file,
    "/data/local/tmp/field-notes-history-fixture.json",
  ]);
  await call(["shell", "run-as", "org.fieldnotes.app", "mkdir", "-p", "files"]);
  await call([
    "shell",
    "run-as",
    "org.fieldnotes.app",
    "cp",
    "/data/local/tmp/field-notes-history-fixture.json",
    "files/history-fixture.json",
  ]);
  for (const pkg of ["org.fieldnotes.app", "org.fieldnotes.app.test"])
    await call(
      ["shell", "cmd", "package", "compile", "-m", "speed", pkg],
      360000,
    );
  const output = await call(
    [
      "shell",
      "am",
      "instrument",
      "-w",
      "-e",
      "class",
      "org.fieldnotes.app.HistoryDeviceTest",
      "-e",
      "fieldNotesHistoryGallery",
      "synthetic-only",
      "org.fieldnotes.app.test/androidx.test.runner.AndroidJUnitRunner",
    ],
    600000,
  );
  await writeFile("dist/android-test-results/HistoryDeviceTest.txt", output);
  if (!/OK \(1 test\)/.test(output) || output.includes("FAILURES!!!")) {
    const failure = await run(
      adb,
      [
        "-s",
        serial,
        "exec-out",
        "run-as",
        "org.fieldnotes.app",
        "cat",
        "files/history-gallery/failure.png",
      ],
      { encoding: "buffer", maxBuffer: 8 * 1024 * 1024 },
    ).catch(() => null);
    if (failure?.stdout?.length)
      await writeFile(
        "dist/android-test-results/history-failure.png",
        failure.stdout,
      );
    throw Error(output);
  }
  for (const name of [
    "59-history",
    "60-history-details",
    "61-history-offline",
    "62-history-light",
    "75-native-item-versions", "76-native-restore-preview", "77-native-restored-item",
    "63-history-keyboard",
  ]) {
    const { stdout } = await run(
      adb,
      [
        "-s",
        serial,
        "exec-out",
        "run-as",
        "org.fieldnotes.app",
        "cat",
        "files/history-gallery/" + name + ".png",
      ],
      { encoding: "buffer", maxBuffer: 8 * 1024 * 1024 },
    );
    assert.ok(
      stdout
        .subarray(0, 8)
        .equals(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10])),
    );
    await writeFile("docs/screenshots/android/" + name + ".png", stdout);
  }
  console.log(
    "Actual native History, field differences, Keystore-backed offline cache and real keyboard captured.",
  );
} finally {
  await call(["shell", "pm", "clear", "org.fieldnotes.app"]).catch(() => {});
  await call([
    "shell",
    "rm",
    "-f",
    "/data/local/tmp/field-notes-history-fixture.json",
  ]).catch(() => {});
  await f.f.close();
  await rm(temporary, { recursive: true, force: true });
}
