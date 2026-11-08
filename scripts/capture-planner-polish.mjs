// Guarded day/time, alarm, recipe, real-keyboard and encrypted-persistence gallery.
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { mkdtemp, mkdir, writeFile, readFile, rm } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";
import {createHash} from "node:crypto";
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
  const plannerEvent = await s.create("event", {
    title: "Annual family trip",
    all_day: true,
    start_date: "2028-03-31",
    end_date: "2028-04-01",
    location: "123 Main Street",
    reminders: {
      timing_version: 2,
      alerts: [
        {
          id: crypto.randomUUID(),
          minutes_before: 0,
          day_time: { days_before: 0, time: "10:30", zone: "America/New_York" },
          importance: "normal",
          sound: "chime",
          volume: 75,
          vibrate: true,
        },
      ],
      repeat: null,
    },
  });
  const snapshot = (await s.f.request("/api/snapshot", { token: s.alex })).data;
  const wanted = new Set([
    plannerEvent.id,
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
    planner_event: plannerEvent.id,
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
  await call([
    "shell",
    "settings",
    "put",
    "secure",
    "show_ime_with_hard_keyboard",
    "1",
  ]);
  await call(["shell", "cmd", "statusbar", "collapse"]);
  await call(["shell", "cmd", "alarm", "set-timezone", "America/New_York"]);
  const changedPackages=[];
  for(const [pkg,name] of [["org.fieldnotes.app","FieldNotes-debug.apk"],["org.fieldnotes.app.test","FieldNotes-debug-androidTest.apk"]]){
    const file="dist/android-emulator/"+name,expected=createHash("sha256").update(await readFile(file)).digest("hex");
    const installed=(await call(["shell","pm","path",pkg]).catch(()=>"")).trim().split("\n").find(v=>v.startsWith("package:"))?.slice(8);
    const digest=installed?(await call(["shell","sha256sum",installed]).catch(()=>"")).split(/\s+/)[0]:null;
    if(digest!==expected){await call(["install","-r",file]);changedPackages.push(pkg);}
  }
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
  for (const pkg of changedPackages)
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
      "org.fieldnotes.app.NativePlannerPolishDeviceTest",
      "-e",
      "fieldNotesContextGallery",
      "synthetic-only",
      "org.fieldnotes.app.test/androidx.test.runner.AndroidJUnitRunner",
    ],
    600000,
  );
  await writeFile(
    "dist/android-test-results/NativePlannerPolishDeviceTest.txt",
    output,
  );
  const passed =
    /OK \(1 test\)/.test(output) && !output.includes("FAILURES!!!");
  const names = passed
    ? [
        "111-native-all-day-reminder",
        "112-native-five-second-alarm",
        "113-native-recipe-keyboard",
        "115-native-recipe-editor",
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
    "Native day/time, alarm, recipe and real-keyboard checks passed with the durable queue and encrypted vault on the synthetic AVD.",
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
