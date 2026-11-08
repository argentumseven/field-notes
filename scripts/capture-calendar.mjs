// Real calendar editor and launcher capture, scoped to the disposable AVD.
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { mkdir, writeFile, readFile } from "node:fs/promises";
import { createHash } from "node:crypto";
import { join } from "node:path";
const run = promisify(execFile),
  root = process.env.FIELD_NOTES_TOOLS || "/workspace/.cache/field-notes";
const adb = join(root, "android-sdk/platform-tools/adb"),
  serial = process.env.FIELD_NOTES_EMULATOR || "emulator-5554";
const only = process.argv
  .find((value) => value.startsWith("--only="))
  ?.slice(7);
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
const originalScreenTimeout = (
  await call(["shell", "settings", "get", "system", "screen_off_timeout"])
).trim();
await mkdir("dist/android-test-results", { recursive: true });
await mkdir("docs/screenshots/android", { recursive: true });
try {
  await call([
    "shell",
    "settings",
    "put",
    "system",
    "screen_off_timeout",
    "1800000",
  ]);
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
  await call(["shell", "cmd", "alarm", "set-timezone", "America/New_York"]);
  const changedPackages = [];
  for (const [pkg, file] of [
    ["org.fieldnotes.app", "dist/android-emulator/FieldNotes-debug.apk"],
    [
      "org.fieldnotes.app.test",
      "dist/android-emulator/FieldNotes-debug-androidTest.apk",
    ],
  ]) {
    const expected = createHash("sha256")
      .update(await readFile(file))
      .digest("hex");
    const installed = (await call(["shell", "pm", "path", pkg]).catch(() => ""))
      .trim()
      .split("\n")
      .find((v) => v.startsWith("package:"))
      ?.slice(8);
    const digest = installed
      ? (await call(["shell", "sha256sum", installed]).catch(() => "")).split(
          /\s+/,
        )[0]
      : null;
    if (digest !== expected) {
      await call(["install", "-r", file]);
      changedPackages.push(pkg);
    }
  }

  await call(["shell", "pm", "clear", "org.fieldnotes.app"]);
  await call(["shell", "pm", "clear", "com.android.launcher3"]);
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
  const packages = changedPackages;
  for (const pkg of packages)
    await call(
      ["shell", "cmd", "package", "compile", "-m", "speed", pkg],
      360000,
    );
  const cases = [
    [
      "WidgetSyncDeviceTest",
      1,
      ["-e", "fieldNotesCalendarGallery", "synthetic-only"],
    ],
    [
      "AlarmExperienceDeviceTest",
      4,
      ["-e", "fieldNotesCalendarGallery", "synthetic-only"],
    ],
    ["InvitationQrImageDeviceTest", 1, []],
    [
      "ReminderDeliveryDeviceTest",
      1,
      ["-e", "fieldNotesCalendarGallery", "synthetic-only"],
    ],
    [
      "CalendarExperienceDeviceTest",
      1,
      ["-e", "fieldNotesCalendarGallery", "synthetic-only"],
    ],
    [
      "CalendarWidgetHostDeviceTest",
      2,
      ["-e", "fieldNotesCalendarGallery", "synthetic-only"],
    ],
  ];
  if (only && !cases.some(([name]) => name === only))
    throw Error("Unknown scoped calendar check.");
  for (const [name, count, extra] of cases.filter(
    ([name]) => !only || name === only,
  )) {
    const output = await call(
      [
        "shell",
        "am",
        "instrument",
        "-w",
        "-r",
        "-e",
        "class",
        "org.fieldnotes.app." + name,
        ...extra,
        "org.fieldnotes.app.test/androidx.test.runner.AndroidJUnitRunner",
      ],
      600000,
    );
    await writeFile("dist/android-test-results/" + name + ".txt", output);
    if (!output.includes(`OK (${count} test${count === 1 ? "" : "s"})`))
      throw Error(output);
    console.log(name + " passed");
    const captures =
      name === "CalendarExperienceDeviceTest"
        ? [
            "30-calendar-home",
            "31-local-event",
            "33-time-picker",
            "40-reminder-rules",
            "41-reminder-sound",
            "44-long-alarm-controls",
            "45-independent-visibility",
            "50-sunday-week",
            "51-sunday-month",
            "52-repeat-event",
          ]
        : name === "CalendarWidgetHostDeviceTest"
          ? [
              "32-home-calendar",
              "42-home-week",
              "73-calendar-widget-day-link",
              "74-week-widget-day-link",
            ]
          : name === "ReminderDeliveryDeviceTest"
            ? ["43-detailed-notifications"]
            : [];
    for (const capture of captures) {
      const { stdout } = await run(
        adb,
        [
          "-s",
          serial,
          "exec-out",
          "run-as",
          "org.fieldnotes.app",
          "cat",
          "files/calendar-gallery/" + capture + ".png",
        ],
        { encoding: "buffer", maxBuffer: 8 * 1024 * 1024 },
      );
      await writeFile("docs/screenshots/android/" + capture + ".png", stdout);
    }
  }
  console.log(
    only
      ? only + " runtime verification completed."
      : "Actual calendar, notification and launcher gallery verified and captured.",
  );
} catch (error) {
  for (const name of ["host-failure", "pin-confirmation"]) {
    const { stdout } = await run(
      adb,
      [
        "-s",
        serial,
        "exec-out",
        "run-as",
        "org.fieldnotes.app",
        "cat",
        "files/calendar-gallery/" + name + ".png",
      ],
      { encoding: "buffer", maxBuffer: 8 * 1024 * 1024 },
    ).catch(() => ({ stdout: null }));
    if (stdout?.length)
      await writeFile(
        "dist/android-test-results/calendar-" + name + ".png",
        stdout,
      );
  }
  const { stdout } = await run(
    adb,
    ["-s", serial, "exec-out", "screencap", "-p"],
    { encoding: "buffer", maxBuffer: 8 * 1024 * 1024 },
  ).catch(() => ({ stdout: null }));
  if (stdout)
    await writeFile("dist/android-test-results/calendar-failure.png", stdout);
  throw error;
} finally {
  if (/^\d+$/.test(originalScreenTimeout))
    await call([
      "shell",
      "settings",
      "put",
      "system",
      "screen_off_timeout",
      originalScreenTimeout,
    ]).catch(() => {});
  await call(["shell", "pm", "clear", "org.fieldnotes.app"]).catch(() => {});
}
