// Guarded native live-update and list-removal exercise on the dedicated AVD.
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { mkdtemp, mkdir, writeFile, rm } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { projectsFixture } from "../tests/projects-fixture.mjs";

const run = promisify(execFile),
  tools = process.env.FIELD_NOTES_TOOLS || "/workspace/.cache/field-notes";
const adb = join(tools, "android-sdk/platform-tools/adb"),
  serial = process.env.FIELD_NOTES_EMULATOR || "emulator-5554";
async function call(args, timeout = 180000) {
  return (
    await run(adb, ["-s", serial, ...args], {
      timeout,
      maxBuffer: 8 * 1024 * 1024,
    })
  ).stdout;
}
if (
  !serial.startsWith("emulator-") ||
  (await call(["shell", "getprop", "ro.boot.qemu.avd_name"])).trim() !==
    "field-notes-test"
)
  throw Error(
    "Dedicated field-notes-test AVD required; user phones are never changed.",
  );
const temporary = await mkdtemp(join(tmpdir(), "field-notes-lists-capture-")),
  s = await projectsFixture();
try {
  const apples = await s.create("task", {
    title: "Apples",
    store: "Costco",
    project_id: s.grocery.id,
  });
  const milk = await s.create("task", {
    title: "Milk",
    store: "Market",
    project_id: s.grocery.id,
  });
  await s.send("task.complete", apples.id, {}, 1);
  await s.send("task.complete", milk.id, {}, 1);
  const get = async () =>
    (await s.f.request("/api/snapshot", { token: s.alex })).data;
  const initial = await get();
  const bread = await s.create(
    "task",
    {
      title: "Bread",
      notes: "Added while you are at the store",
      store: "Costco",
      project_id: s.grocery.id,
    },
    s.sophia,
  );
  await s.send(
    "task.update",
    s.bananas.id,
    { quantity: "8", notes: "Please choose the smaller bunches" },
    1,
    s.sophia,
  );
  const amended = await get();
  const deletedBread = await s.send("task.delete", bread.id, {}, 1),
    afterBread = await get();
  const deletedApples = await s.send("task.delete", apples.id, {}, 2),
    afterApples = await get();
  const seed = {
    initial,
    amended,
    after_bread: afterBread,
    after_apples: afterApples,
    deleted_bread: { record: deletedBread, sequence: afterBread.sequence },
    deleted_apples: { record: deletedApples, sequence: afterApples.sequence },
    bananas: s.bananas.id,
    bread: bread.id,
    apples: apples.id,
    milk: milk.id,
    people: s.people,
    head: (await s.f.request("/api/head", { token: s.alex })).data,
    apk_update: (
      await s.f.request("/api/device/update", {
        token: s.alex,
        base: s.f.device,
      })
    ).data,
  };
  const file = join(temporary, "list-experience-fixture.json");
  await writeFile(file, JSON.stringify(seed), { mode: 0o600 });
  await mkdir("dist/android-test-results", { recursive: true });
  await mkdir("docs/screenshots/android", { recursive: true });
  await call(["shell", "input", "keyevent", "KEYCODE_WAKEUP"]);
  await call(["shell", "wm", "dismiss-keyguard"]);
  await call(["shell", "cmd", "statusbar", "collapse"]);
  await call(["shell", "cmd", "alarm", "set-timezone", "America/New_York"]);
  for (const name of [
    "FieldNotes-debug.apk",
    "FieldNotes-debug-androidTest.apk",
  ])
    await call(["install", "-r", "dist/android-emulator/" + name]);
  // Intentional reset only on the named disposable AVD, before seeding.
  await call(["shell", "pm", "clear", "org.fieldnotes.app"]);
  await call([
    "shell",
    "pm",
    "grant",
    "org.fieldnotes.app",
    "android.permission.POST_NOTIFICATIONS",
  ]);
  await call([
    "push",
    file,
    "/data/local/tmp/field-notes-list-experience-fixture.json",
  ]);
  await call(["shell", "run-as", "org.fieldnotes.app", "mkdir", "-p", "files"]);
  await call([
    "shell",
    "run-as",
    "org.fieldnotes.app",
    "cp",
    "/data/local/tmp/field-notes-list-experience-fixture.json",
    "files/list-experience-fixture.json",
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
      "org.fieldnotes.app.ListExperienceDeviceTest",
      "-e",
      "fieldNotesListGallery",
      "synthetic-only",
      "org.fieldnotes.app.test/androidx.test.runner.AndroidJUnitRunner",
    ],
    600000,
  );
  await writeFile(
    "dist/android-test-results/ListExperienceDeviceTest.txt",
    output,
  );
  const passed =
    /OK \(1 test\)/.test(output) && !output.includes("FAILURES!!!");
  const names = passed
    ? [
        "93-native-list-cleanup",
        "94-native-delete-grocery",
        "95-native-live-groceries",
        "96-native-queued-removal",
      ]
    : ["failure"];
  for (const name of names) {
    const image = await run(
      adb,
      [
        "-s",
        serial,
        "exec-out",
        "run-as",
        "org.fieldnotes.app",
        "cat",
        `files/list-gallery/${name}.png`,
      ],
      { encoding: "buffer", maxBuffer: 8 * 1024 * 1024 },
    ).catch(() => null);
    if (
      passed &&
      !image?.stdout
        ?.subarray(0, 8)
        .equals(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]))
    )
      throw Error("Expected native screenshot was not captured: " + name);
    if (image?.stdout?.length)
      await writeFile(
        passed
          ? `docs/screenshots/android/${name}.png`
          : "dist/android-test-results/list-failure.png",
        image.stdout,
      );
  }
  if (!passed) throw Error(output);
  console.log(
    "Native open-app refresh, real-time amendments, offline individual removal and scoped bulk cleanup passed on the synthetic AVD.",
  );
} finally {
  await call([
    "shell",
    "rm",
    "-f",
    "/data/local/tmp/field-notes-list-experience-fixture.json",
  ]).catch(() => {});
  await s.f.close();
  await rm(temporary, { recursive: true, force: true });
}
