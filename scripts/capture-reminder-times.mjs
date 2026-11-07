// Guarded native custom-reminder amount/unit and persistence gallery.
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
const temporary = await mkdtemp(
    join(tmpdir(), "field-notes-contexts-capture-"),
  ),
  s = await projectsFixture();
try {
  const home = await s.create("list", {
    title: "Household work",
    category: "home",
    items_are_tasks: true,
  });
  const homeItem = await s.create("task", {
    title: "Repair the chair",
    project_id: home.id,
  });
  const custom = await s.create("list", {
    title: "Anything list",
    template: "custom",
    category: "home",
    fields: ["notes"],
  });
  const customItem = await s.create("task", {
    title: "Unrestricted example",
    project_id: custom.id,
  });
  await s.send(
    "task.update",
    s.bananas.id,
    { category: "work" },
    s.bananas.revision,
  );
  const snapshot = (await s.f.request("/api/snapshot", { token: s.alex })).data;
  const wanted = new Set([
    s.grocery.id,
    s.bananas.id,
    home.id,
    homeItem.id,
    custom.id,
    customItem.id,
  ]);
  snapshot.records = snapshot.records.filter((r) => wanted.has(r.id));
  const seed = {
    snapshot,
    people: s.people,
    grocery_item: s.bananas.id,
    home_item: homeItem.id,
    custom_item: customItem.id,
    head: (await s.f.request("/api/head", { token: s.alex })).data,
    apk_update: (
      await s.f.request("/api/device/update", {
        token: s.alex,
        base: s.f.device,
      })
    ).data,
  };
  const file = join(temporary, "item-context-fixture.json");
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
    "/data/local/tmp/field-notes-item-context-fixture.json",
  ]);
  await call(["shell", "run-as", "org.fieldnotes.app", "mkdir", "-p", "files"]);
  await call([
    "shell",
    "run-as",
    "org.fieldnotes.app",
    "cp",
    "/data/local/tmp/field-notes-item-context-fixture.json",
    "files/item-context-fixture.json",
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
      "org.fieldnotes.app.ReminderTimingDeviceTest",
      "-e",
      "fieldNotesContextGallery",
      "synthetic-only",
      "org.fieldnotes.app.test/androidx.test.runner.AndroidJUnitRunner",
    ],
    600000,
  );
  await writeFile(
    "dist/android-test-results/ReminderTimingDeviceTest.txt",
    output,
  );
  const passed =
    /OK \(1 test\)/.test(output) && !output.includes("FAILURES!!!");
  const names = passed
    ? ["103-native-reminder-time-units", "104-native-month-year-reminders"]
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
        `files/context-gallery/${name}.png`,
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
          : "dist/android-test-results/item-context-failure.png",
        image.stdout,
      );
  }
  if (!passed) throw Error(output);
  console.log(
    "Native month/year reminder controls saved distinct rules through the durable queue into the encrypted vault on the synthetic AVD.",
  );
} finally {
  await call([
    "shell",
    "rm",
    "-f",
    "/data/local/tmp/field-notes-item-context-fixture.json",
  ]).catch(() => {});
  await s.f.close();
  await rm(temporary, { recursive: true, force: true });
}
