import { test } from "node:test";
import assert from "node:assert/strict";
import { chromium } from "playwright";
import { readFile, mkdir } from "node:fs/promises";
import { randomUUID } from "node:crypto";
import { fixture, password } from "./support.mjs";

test("web manager exports an encrypted file, previews duplicates and preserves a newer edit", async () => {
  const f = await fixture();
  let browser;
  try {
    const token = await f.login(),
      snapshot = (await f.request("/api/snapshot", { token })).data;
    const id = randomUUID();
    const send = (action, data, revision = null) =>
      f.request("/api/commands", {
        token,
        body: {
          operation_id: randomUUID(),
          epoch: snapshot.epoch,
          generation: snapshot.generation,
          action,
          target: id,
          expected_revision: revision,
          data,
        },
      });
    assert.equal(
      (
        await send("note.create", {
          title: "Greenhouse someday",
          body: "Compare winter heating options",
          on_radar: true,
          category: "home",
        })
      ).status,
      200,
    );
    browser = await chromium.launch({
      executablePath: process.env.CHROMIUM_PATH || "/usr/bin/chromium",
      headless: true,
      args: ["--no-sandbox"],
    });
    const page = await browser.newPage({
        viewport: { width: 1440, height: 1050 },
        timezoneId: "America/New_York",
        locale: "en-US",
        colorScheme: "dark",
      }),
      errors = [];
    page.on("pageerror", (e) => errors.push(e.message));
    await page.goto(f.admin);
    await page.getByLabel("User name", { exact: true }).fill("owner");
    await page.getByLabel("Password", { exact: true }).fill(password);
    await page.getByRole("button", { name: "Sign in", exact: true }).click();
    await page
      .getByRole("heading", { name: "Calendar", exact: true })
      .waitFor();
    await page
      .getByRole("button", { name: "Export & import", exact: true })
      .click();
    await page
      .getByRole("heading", { name: "Export & import", exact: true })
      .waitFor();
    const pass = "Portable household copy with punctuation!";
    await page.getByLabel("Export passphrase", { exact: true }).fill(pass);
    await page
      .getByLabel("Confirm export passphrase", { exact: true })
      .fill(pass);
    const download = page.waitForEvent("download");
    await page
      .getByRole("button", { name: "Download encrypted export", exact: true })
      .click();
    let received;
    try {
      received = await download;
    } catch (e) {
      console.error(
        "Archive status:",
        await page.locator(".transfer-status").innerText(),
        "notice:",
        await page.locator("#notice").innerText(),
        "JS:",
        errors,
      );
      throw e;
    }
    const file = await received.path();
    assert(file);
    const bytes = await readFile(file);
    assert.equal(bytes.subarray(0, 11).toString(), "FNARCHIVE1\n");
    assert(!bytes.includes(Buffer.from("Greenhouse someday")));
    await page
      .getByLabel("Choose a Field Notes file", { exact: true })
      .setInputFiles(file);
    await page
      .getByLabel("Archive passphrase", { exact: true })
      .fill("an incorrect password!");
    await page
      .getByRole("button", { name: "Unlock and preview", exact: true })
      .click();
    await page
      .getByText(/passphrase is incorrect or this file is damaged/)
      .first()
      .waitFor();
    await page.getByLabel("Archive passphrase", { exact: true }).fill(pass);
    await page
      .getByRole("button", { name: "Unlock and preview", exact: true })
      .click();
    await page
      .getByRole("heading", { name: "Review the import", exact: true })
      .waitFor();
    assert.match(
      await page.locator(".import-preview").innerText(),
      /0 new items · 1 already present/,
    );
    await page
      .getByRole("button", { name: "Import these items", exact: true })
      .click();
    await page
      .locator(".transfer-status")
      .filter({ hasText: "Import complete" })
      .waitFor();
    assert.equal(
      (await f.request("/api/snapshot", { token })).data.records.length,
      1,
    );
    assert.equal(
      (await send("note.update", { body: "Newer heating plan, keep this" }, 1))
        .status,
      200,
    );
    await page.getByLabel("Archive passphrase", { exact: true }).fill(pass);
    await page
      .getByRole("button", { name: "Unlock and preview", exact: true })
      .click();
    await page
      .getByRole("heading", { name: "Review the import", exact: true })
      .waitFor();
    assert.match(
      await page.locator(".import-preview").innerText(),
      /1 saved alternatives/,
    );
    await page
      .getByRole("button", { name: "Import these items", exact: true })
      .click();
    await page
      .locator(".transfer-status")
      .filter({ hasText: "Import complete" })
      .waitFor();
    assert.equal(
      (await f.request("/api/snapshot", { token })).data.records[0].data.body,
      "Newer heating plan, keep this",
    );
    await page.locator(".transfer-card details > summary").first().click();
    await page
      .getByRole("button", { name: "Restore as a separate item", exact: true })
      .click();
    await page
      .locator(".transfer-status")
      .filter({ hasText: "Restored as a separate item" })
      .waitFor();
    assert.equal(
      (await f.request("/api/snapshot", { token })).data.records.length,
      2,
    );
    await page.getByRole("button", { name: "Restored", exact: true }).click();
    await page
      .locator(".transfer-status")
      .filter({ hasText: "No duplicate was added" })
      .waitFor();
    assert.equal(
      (await f.request("/api/snapshot", { token })).data.records.length,
      2,
    );
    await mkdir("docs/screenshots/web", { recursive: true });
    await page.screenshot({
      path: "docs/screenshots/web/52-encrypted-export-import.png",
      fullPage: true,
    });
    assert.deepEqual(errors, []);
  } finally {
    await browser?.close();
    await f.close();
  }
});
