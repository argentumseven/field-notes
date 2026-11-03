import { test } from "node:test";
import assert from "node:assert/strict";
import { chromium } from "playwright";
import { readdir, writeFile, mkdir } from "node:fs/promises";
import { join, delimiter } from "node:path";
import { execFileSync } from "node:child_process";
import { fixture, password } from "./support.mjs";

test("the displayed invitation decodes to the exact server-issued code using offline ZXing", async () => {
  const f = await fixture({ enrollment: true });
  let browser;
  try {
    const base =
      (process.env.GRADLE_USER_HOME ||
        (process.env.FIELD_NOTES_TOOLS || "/workspace/.cache/field-notes") +
          "/gradle") +
      "/caches/modules-2/files-2.1/com.google.zxing/core/3.5.3";
    let jar;
    for (const hash of await readdir(base))
      for (const file of await readdir(join(base, hash)))
        if (file.endsWith(".jar")) jar = join(base, hash, file);
    assert(
      jar,
      "Build Android dependencies before the scanner round-trip check",
    );
    const classes = join(f.root, "decoder");
    await mkdir(classes);
    execFileSync("javac", [
      "-cp",
      jar,
      "-d",
      classes,
      "tests/qr/DecodeInvitation.java",
    ]);
    browser = await chromium.launch({
      executablePath: process.env.CHROMIUM_PATH || "/usr/bin/chromium",
      headless: true,
      args: ["--no-sandbox"],
    });
    const page = await browser.newPage({
      viewport: { width: 1440, height: 1050 },
    });
    await page.goto(f.admin);
    await page.getByLabel("User name", { exact: true }).fill("owner");
    await page.getByLabel("Password", { exact: true }).fill(password);
    await page.getByRole("button", { name: "Sign in", exact: true }).click();
    await page.getByRole("button", { name: "Management", exact: true }).click();
    await page
      .getByRole("button", { name: "Create invitation", exact: true })
      .click();
    await page.getByLabel("Password", { exact: true }).fill(password);
    await page.getByRole("button", { name: "Verify", exact: true }).click();
    const issued = page.waitForResponse(
      (r) =>
        r.url().endsWith("/api/enrollment/tunnel-invitations") &&
        r.status() === 200,
    );
    await page
      .getByLabel("Phone public key", { exact: true })
      .fill(Buffer.alloc(32, 18).toString("base64"));
    await page.locator("#dialog-submit").click();
    const invitation = await (await issued).json();
    const image = page.locator(".invite-qr");
    await image.waitFor();
    await image.evaluate((img) => img.decode());
    const expected = join(f.root, "expected.txt"),
      picture = join(f.root, "displayed.png");
    await writeFile(expected, invitation.code, { mode: 0o600 });
    assert.match(invitation.qr_png, /^data:image\/png;base64,/);
    for (const width of [1440, 390, 360]) {
      await page.setViewportSize({ width, height: 1050 });
      await page.waitForFunction(() => {
        const i = document.querySelector(".invite-qr");
        return i.getBoundingClientRect().width >= i.naturalWidth / 4;
      });
      await image.screenshot({ path: picture });
      const output = execFileSync(
        "java",
        [
          "-Djava.awt.headless=true",
          "-cp",
          classes + delimiter + jar,
          "DecodeInvitation",
          picture,
          expected,
        ],
        { encoding: "utf8" },
      );
      assert.match(output, /Verified exact invitation decode/);
    }
    await page.evaluate(() =>
      Object.defineProperty(navigator, "clipboard", {
        value: {
          writeText: async () => {
            throw Error("blocked");
          },
        },
      }),
    );
    await page
      .getByRole("button", { name: "Copy invitation", exact: true })
      .click();
    assert.equal(
      await page.getByLabel("Invitation text", { exact: true }).inputValue(),
      invitation.code,
    );
    assert.equal(
      await page
        .getByLabel("Invitation text", { exact: true })
        .evaluate((n) => n.readOnly),
      true,
    );
    await page.getByRole("button", { name: "Done", exact: true }).click();
    assert.equal(await page.locator(".invitation-material").count(), 0);
  } finally {
    await browser?.close();
    await f.close();
  }
});

test("an unreadable QR offers selectable invitation text and removes it on expiry", async () => {
  const f = await fixture({ enrollment: true });
  let browser;
  try {
    browser = await chromium.launch({
      executablePath: process.env.CHROMIUM_PATH || "/usr/bin/chromium",
      headless: true,
      args: ["--no-sandbox"],
    });
    const page = await browser.newPage();
    await page.route("**/api/enrollment/tunnel-invitations", async (route) => {
      const response = await route.fetch(),
        data = await response.json();
      await route.fulfill({
        response,
        json: { ...data, qr_png: "data:image/png;base64,AA" },
      });
    });
    await page.goto(f.admin);
    await page.getByLabel("User name", { exact: true }).fill("owner");
    await page.getByLabel("Password", { exact: true }).fill(password);
    await page.getByRole("button", { name: "Sign in", exact: true }).click();
    await page.getByRole("button", { name: "Management", exact: true }).click();
    await page.clock.install();
    await page
      .getByRole("button", { name: "Create invitation", exact: true })
      .click();
    await page.getByLabel("Password", { exact: true }).fill(password);
    await page.getByRole("button", { name: "Verify", exact: true }).click();
    await page
      .getByLabel("Phone public key", { exact: true })
      .fill(Buffer.alloc(32, 18).toString("base64"));
    await page.locator("#dialog-submit").click();
    await page
      .getByText("The QR image could not be displayed.", { exact: false })
      .waitFor();
    await page
      .getByLabel("Invitation text", { exact: true })
      .waitFor({ state: "visible" });
    assert.match(
      await page.getByLabel("Invitation text", { exact: true }).inputValue(),
      /^fieldnotes:\/\/enroll#/,
    );
    await page.clock.fastForward(601000);
    assert.equal(
      await page.getByLabel("Invitation text", { exact: true }).count(),
      0,
    );
    assert.equal(await page.locator(".invite-qr").count(), 0);
    assert.equal(
      await page
        .getByRole("button", { name: "Copy invitation", exact: true })
        .isDisabled(),
      true,
    );
  } finally {
    await browser?.close();
    await f.close();
  }
});
