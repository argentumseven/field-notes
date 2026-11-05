// Integration tests only on our disposable AVD; never clear a physical phone.
import { execFile, spawn } from "node:child_process";
import { promisify } from "node:util";
import { readFile, writeFile, mkdir } from "node:fs/promises";
import { resolve, join } from "node:path";
import { setTimeout as delay } from "node:timers/promises";
import { fixture } from "../tests/support.mjs";
import assert from "node:assert/strict";
import vm from "node:vm";
import { webcrypto } from "node:crypto";
import * as fflate from "fflate";
const run = promisify(execFile);
const nativeOnly = process.argv.includes("--native-only");
const uiOnly = process.argv.includes("--ui-only");
let portableFixture = null;
const selectedUi = process.argv
  .find((a) => a.startsWith("--ui-class="))
  ?.slice("--ui-class=".length)
  .split(",");
const tools = process.env.FIELD_NOTES_TOOLS || "/workspace/.cache/field-notes";
const adb = join(tools, "android-sdk/platform-tools/adb");
const serial = process.env.FIELD_NOTES_EMULATOR || "emulator-5554";
if (!serial.startsWith("emulator-"))
  throw Error(
    "Only a disposable emulator is supported by this destructive test fixture.",
  );
async function call(args, timeout = 120000) {
  return (
    await run(adb, ["-s", serial, ...args], {
      timeout,
      maxBuffer: 4 * 1024 * 1024,
    })
  ).stdout;
}
const avd =
  (await call(["shell", "getprop", "ro.boot.qemu.avd_name"])).trim() ||
  (await call(["shell", "getprop", "ro.kernel.qemu.avd_name"])).trim();
if (avd !== "field-notes-test")
  throw Error(
    "Use the dedicated field-notes-test AVD. Existing user devices are not cleared.",
  );
// The AVD presents a hardware keyboard; explicitly exercise the real soft IME.
await call([
  "shell",
  "settings",
  "put",
  "secure",
  "show_ime_with_hard_keyboard",
  "1",
]);
await call(["shell", "input", "keyevent", "KEYCODE_WAKEUP"]);
await call(["shell", "wm", "dismiss-keyguard"]);
const network = await call(["shell", "ip", "-4", "addr"]);
if (!/\binet 10\.0\.2\.(15|16)\//.test(network))
  throw Error(
    "Connect the dedicated AVD to its emulated AndroidWifi network before native tests; no host route is assigned.",
  );
await mkdir("dist/android-test-results", { recursive: true });
await call(["install", "-r", "dist/android-emulator/FieldNotes-debug.apk"]);
await call([
  "install",
  "-r",
  "dist/android-emulator/FieldNotes-debug-androidTest.apk",
]);
await call(["shell", "pm", "clear", "org.fieldnotes.app"]);
// AOT compilation avoids software-emulation cold-start timeouts. The APK,
// Android checks, and test assertions are unchanged.
for (const pkg of ["org.fieldnotes.app", "org.fieldnotes.app.test"]) {
  const out = await call(
    ["shell", "cmd", "package", "compile", "-m", "speed", pkg],
    360000,
  );
  if (!out.includes("Success"))
    throw Error("Emulator AOT preparation failed: " + out);
}
async function instrument(classes, extra = [], report) {
  const output = await call(
    [
      "shell",
      "am",
      "instrument",
      "-w",
      "-e",
      "class",
      classes,
      ...extra,
      "org.fieldnotes.app.test/androidx.test.runner.AndroidJUnitRunner",
    ],
    360000,
  );
  if (report)
    await writeFile(join("dist/android-test-results", report), output);
  if (
    !/OK \(\d+ tests?\)/.test(output) ||
    output.includes("FAILURES!!!") ||
    output.includes("INSTRUMENTATION_FAILED")
  )
    throw Error(output);
  return output;
}
if (!nativeOnly) {
  console.log("Running Android Keystore and first-launch UI tests.");
  const reports = [];
  const names = [
    "VaultDeviceTest",
    "InterfaceDeviceTest",
    "EnrollmentDeviceTest",
    "EditorDeviceTest",
    "WidgetDeviceTest",
    "UpdateDeviceTest",
    "ArchiveDeviceTest",
  ];
  if (selectedUi?.some((name) => !names.includes(name)))
    throw Error("Unknown UI test class");
  for (const name of selectedUi || names) {
    console.log("Running " + name + " on the disposable AVD.");
    reports.push(
      await instrument("org.fieldnotes.app." + name, [], name + ".txt"),
    );
    if (name === "ArchiveDeviceTest") {
      const { stdout } = await run(
        adb,
        [
          "-s",
          serial,
          "exec-out",
          "run-as",
          "org.fieldnotes.app",
          "cat",
          "files/portable-interop.fieldnotes",
        ],
        { encoding: "buffer", maxBuffer: 1024 * 1024 },
      );
      await writeFile(
        "dist/android-test-results/portable-interop.fieldnotes",
        stdout,
      );
      const context = {
        window: {},
        crypto: webcrypto,
        TextEncoder,
        TextDecoder,
        Uint8Array,
        DataView,
        fflate,
        setTimeout,
      };
      vm.runInNewContext(
        await readFile("apps/admin/archive.js", "utf8"),
        context,
      );
      const codec = context.window.FieldArchive;
      const zip = await codec.decrypt(
        new Uint8Array(stdout),
        "A portable café 🌙 with punctuation!",
      );
      try {
        portableFixture = await codec.unpack(zip);
      } finally {
        zip.fill(0);
      }
      assert.equal(portableFixture.manifest.records.length, 2);
      console.log(
        "Actual Android document export decrypted and verified with WebCrypto, including Unicode passphrase and its offline image.",
      );
    }
  }
  await writeFile("dist/android-test-results/vault-ui.txt", reports.join("\n"));
}
if (uiOnly) {
  console.log("Selected Android UI checks completed.");
  process.exit(0);
}
const f = await fixture();
let hub;
try {
  const configFile = join(f.root, "public-connection.json"),
    peerFile = join(f.root, "peer-public-key"),
    ackFile = join(f.root, "installed-peer");
  hub = spawn(
    resolve("dist/testhub"),
    [
      "--backend",
      f.device,
      "--peer-file",
      peerFile,
      "--config-file",
      configFile,
      "--peer-ack-file",
      ackFile,
    ],
    { stdio: ["ignore", "ignore", "pipe"] },
  );
  let config;
  for (let n = 0; n < 100; n++) {
    try {
      config = await readFile(configFile);
      break;
    } catch {}
    await delay(50);
  }
  if (!config) throw Error("Test hub did not become ready.");
  let done = false;
  const monitor = (async () => {
    let acknowledged = "";
    while (!done) {
      try {
        const key = (
          await call(
            [
              "shell",
              "run-as",
              "org.fieldnotes.app",
              "cat",
              "files/native-test-public.txt",
            ],
            15000,
          )
        ).trim();
        if (/^[A-Za-z0-9+/]{43}=$/.test(key) && key !== acknowledged) {
          await writeFile(peerFile, key, { mode: 0o600 });
          for (let n = 0; n < 100 && !done; n++) {
            try {
              if ((await readFile(ackFile, "utf8")) === key) break;
            } catch {}
            await delay(50);
          }
          if ((await readFile(ackFile, "utf8")) !== key)
            throw Error("Test hub did not acknowledge the peer.");
          await new Promise((resolve, reject) => {
            const child = spawn(
              adb,
              [
                "-s",
                serial,
                "shell",
                "-T",
                "run-as org.fieldnotes.app sh -c 'cat > files/native-test-peer-ready.txt'",
              ],
              { stdio: ["pipe", "ignore", "pipe"], timeout: 15000 },
            );
            let error = "";
            child.stderr.on("data", (b) => (error += b));
            child.on("error", reject);
            child.on("close", (code) =>
              code === 0 ? resolve() : reject(Error(error)),
            );
            child.stdin.end(key);
          });
          acknowledged = key;
          console.log("Disposable hub acknowledged the installed test peer.");
        }
      } catch {}
      await delay(150);
    }
  })();
  let second;
  try {
    console.log(
      "Running real Android JNI → WireGuard → TLS → Rust integration.",
    );
    second = await instrument(
      "org.fieldnotes.app.NativeDeviceTest",
      ["-e", "fieldNotesConfig", config.toString("base64")],
      "native.txt",
    );
  } finally {
    done = true;
    await monitor;
  }
  await writeFile("dist/android-test-results/native.txt", second);
  if (portableFixture) {
    const token = await f.login();
    for (let pass = 0; pass < 2; pass++) {
      const prepared = await f.request("/api/transfer/prepare", {
        token,
        body: { manifest: portableFixture.manifest },
      });
      assert.equal(prepared.status, 200, JSON.stringify(prepared.data));
      const plan = prepared.data;
      assert.equal(plan.new_items, pass === 0 ? 2 : 0);
      assert.equal(plan.retained_changes, pass === 0 ? 1 : 0);
      for (const id of plan.needed_media) {
        const bytes = portableFixture.files["media/" + id + ".bin"];
        for (
          let index = 0;
          index < Math.ceil(bytes.length / plan.chunk_size);
          index++
        ) {
          const result = await f.request("/api/transfer/image", {
            token,
            body: {
              plan_id: plan.plan_id,
              image_id: id,
              index,
              bytes: Buffer.from(
                bytes.subarray(
                  index * plan.chunk_size,
                  (index + 1) * plan.chunk_size,
                ),
              ).toString("base64"),
            },
          });
          assert.equal(result.status, 200, JSON.stringify(result.data));
        }
      }
      const committed = await f.request("/api/transfer/commit", {
        token,
        body: { plan_id: plan.plan_id },
      });
      assert.equal(committed.status, 200, JSON.stringify(committed.data));
    }
    assert.equal(
      (await f.request("/api/snapshot", { token })).data.records.length,
      4,
    );
    console.log(
      "Actual Android content file imported twice into Rust with its offline image and retained command; no duplicate items or spurious image conflicts.",
    );
  }
  console.log(
    nativeOnly
      ? "Android emulator: 2 native/manifest integration tests passed."
      : "Selected Android UI checks and 2 real native/manifest integration tests passed.",
  );
} finally {
  hub?.kill("SIGTERM");
  await f.close();
}
