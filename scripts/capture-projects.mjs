// Guarded synthetic project/recipient/grocery UI exercise on the dedicated AVD.
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
    join(tmpdir(), "field-notes-projects-capture-"),
  ),
  s = await projectsFixture();
try {
  const seed = {
    snapshot: s.snapshot,
    people: s.people,
    head: (await s.f.request("/api/head", { token: s.alex })).data,
    apk_update: (
      await s.f.request("/api/device/update", {
        token: s.alex,
        base: s.f.device,
      })
    ).data,
  };
  const file = join(temporary, "projects-fixture.json");
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
    "/data/local/tmp/field-notes-projects-fixture.json",
  ]);
  await call(["shell", "run-as", "org.fieldnotes.app", "mkdir", "-p", "files"]);
  await call([
    "shell",
    "run-as",
    "org.fieldnotes.app",
    "cp",
    "/data/local/tmp/field-notes-projects-fixture.json",
    "files/projects-fixture.json",
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
      "org.fieldnotes.app.ProjectsDeviceTest",
      "-e",
      "fieldNotesProjectsGallery",
      "synthetic-only",
      "org.fieldnotes.app.test/androidx.test.runner.AndroidJUnitRunner",
    ],
    600000,
  );
  await writeFile("dist/android-test-results/ProjectsDeviceTest.txt", output);
  const passed =
    /OK \(1 test\)/.test(output) && !output.includes("FAILURES!!!");
  const names = passed
    ? [
        "87-native-project-workspace",
        "88-native-reminder-recipients",
        "89-native-grocery-item",
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
        `files/projects-gallery/${name}.png`,
      ],
      { encoding: "buffer", maxBuffer: 8 * 1024 * 1024 },
    ).catch(() => null);
    if (image?.stdout?.length)
      await writeFile(
        passed
          ? `docs/screenshots/android/${name}.png`
          : "dist/android-test-results/projects-failure.png",
        image.stdout,
      );
  }
  if (!passed) throw Error(output);
  console.log(
    "Native project navigation preserved search context; recipient chips saved through the outbox into the Keystore-backed cache; grocery reminder controls are absent. Synthetic AVD checks passed.",
  );
} finally {
  await call([
    "shell",
    "rm",
    "-f",
    "/data/local/tmp/field-notes-projects-fixture.json",
  ]).catch(() => {});
  await s.f.close();
  await rm(temporary, { recursive: true, force: true });
}
