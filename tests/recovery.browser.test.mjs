import { test } from "node:test";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { mkdir } from "node:fs/promises";
import { chromium } from "playwright";
import { fixture, password } from "./support.mjs";

async function scene() {
  const f = await fixture();
  let browser;
  try {
    const token = await f.login(),
      basis = (await f.request("/api/snapshot", { token })).data;
    const send = (action, target, data, expected_revision = null) =>
      f.request("/api/commands", {
        token,
        body: {
          operation_id: randomUUID(),
          epoch: basis.epoch,
          generation: basis.generation,
          action,
          target,
          data,
          expected_revision,
        },
      });
    browser = await chromium.launch({
      executablePath: process.env.CHROMIUM_PATH || "/usr/bin/chromium",
      headless: true,
      args: ["--no-sandbox"],
    });
    const page = await browser.newPage({
      viewport: { width: 1440, height: 1080 },
      timezoneId: "America/New_York",
      locale: "en-US",
      colorScheme: "dark",
    });
    await page.clock.setFixedTime(new Date("2026-11-06T14:00:00Z"));
    await page.goto(f.admin);
    await page.getByLabel("User name", { exact: true }).fill("owner");
    await page.getByLabel("Password", { exact: true }).fill(password);
    await page.getByRole("button", { name: "Sign in", exact: true }).click();
    await page
      .getByRole("heading", { name: "Calendar", exact: true })
      .waitFor();
    return {
      f,
      page,
      token,
      send,
      async close() {
        await browser.close();
        await f.close();
      },
    };
  } catch (error) {
    await browser?.close();
    await f.close();
    throw error;
  }
}
async function shot(page, name) {
  if (process.env.FIELD_NOTES_CAPTURE_RECOVERY !== "synthetic-only") return;
  await mkdir("docs/screenshots/web", { recursive: true });
  await page.screenshot({
    path: `docs/screenshots/web/${name}.png`,
    fullPage: !(await page.locator("dialog[open]").count()),
  });
}

async function restoreConfirmation(
  page,
  preview,
  phrase,
  title = "Restore item version",
) {
  await preview
    .getByRole("button", {
      name: "Continue to restore confirmation",
      exact: true,
    })
    .click();
  const dialog = page
    .locator(".recovery-dialog")
    .filter({ has: page.getByRole("heading", { name: title, exact: true }) });
  const submit = dialog.getByRole("button", { name: title, exact: true });
  assert.equal(await submit.isDisabled(), true);
  const typed = dialog.getByLabel(`Type ${phrase} to confirm`, { exact: true });
  await typed.fill("RESTORE");
  await dialog.getByRole("checkbox").check();
  await dialog
    .getByLabel("Your account password", { exact: true })
    .fill(password);
  assert.equal(await submit.isDisabled(), true);
  await typed.fill(phrase);
  // Enter in a text field must not activate the final destructive action.
  await typed.press("Enter");
  assert.equal(await dialog.isVisible(), true);
  assert.equal(await submit.isEnabled(), true);
  return { dialog, submit };
}

test("web restores deleted and previous states with readable previews and keeps roll forward available", async () => {
  const s = await scene();
  try {
    const { page, send } = s;
    const task = randomUUID();
    assert.equal(
      (
        await send("task.create", task, {
          title: "Refill pantry",
          notes: "Original household plan",
        })
      ).status,
      200,
    );
    assert.equal(
      (
        await send(
          "task.update",
          task,
          { title: "Refill pantry", notes: "Revised household plan" },
          1,
        )
      ).status,
      200,
    );
    assert.equal((await send("task.delete", task, {}, 2)).status, 200);
    await page.getByRole("button", { name: "History", exact: true }).click();
    const entry = page
      .locator(".history-entry")
      .filter({
        has: page.getByRole("heading", { name: "Refill pantry", exact: true }),
      })
      .first();
    await entry
      .getByRole("button", { name: "Item versions", exact: true })
      .click();
    const versions = page.locator(".recovery-dialog").first();
    await versions.getByRole("heading", { name: /#1/ }).waitFor();
    await shot(page, "69-item-versions");
    await versions
      .locator(".history-entry")
      .filter({ has: page.getByRole("heading", { name: /#1/ }) })
      .getByRole("button", { name: "Preview restore", exact: true })
      .click();
    const preview = page
      .locator(".recovery-dialog")
      .filter({ has: page.getByRole("heading", { name: /Restore preview/ }) });
    assert.match(await preview.innerText(), /Original household plan/);
    assert.equal(
      await page
        .locator(".history-view > .history-timeline > .history-entry")
        .count(),
      1,
    );
    await preview
      .getByRole("article", { name: "Selected item state" })
      .waitFor();
    assert.equal(
      await preview.locator(".recovery-comparison").getAttribute("open"),
      null,
    );
    await preview
      .getByRole("button", { name: "Current state", exact: true })
      .click();
    assert.match(
      await preview
        .getByRole("article", { name: "Current item state" })
        .innerText(),
      /not present/,
    );
    await preview
      .getByRole("button", { name: "Selected state #1", exact: true })
      .click();
    assert.match(
      await preview
        .getByRole("article", { name: "Selected item state" })
        .innerText(),
      /Original household plan/,
    );

    assert.equal(
      await preview
        .locator(".recovery-body")
        .evaluate((n) => getComputedStyle(n).overflowY),
      "auto",
    );
    await shot(page, "70-item-restore-preview");
    const confirmation = await restoreConfirmation(
      page,
      preview,
      "RESTORE ITEM #1",
    );
    await confirmation.submit.click();
    await preview.waitFor({ state: "detached" });
    let snap = (await s.f.request("/api/snapshot", { token: s.token })).data;
    assert.equal(
      snap.records.find((r) => r.id === task).data.notes,
      "Original household plan",
    );
    await versions.getByRole("button", { name: "Close", exact: true }).click();
    await page
      .getByRole("button", { name: "Content checkpoints", exact: true })
      .click();
    let checkpoints = page.locator(".recovery-dialog").last();
    await checkpoints
      .getByRole("button", { name: "Save content checkpoint", exact: true })
      .click();
    await page
      .getByLabel("Checkpoint name", { exact: true })
      .fill("Before holiday planning");
    await page.locator("#dialog-submit").click();
    await page
      .locator(".recovery-dialog")
      .last()
      .getByRole("heading", { name: /#1 · Before holiday planning/ })
      .waitFor();
    await shot(page, "71-content-checkpoints");
    checkpoints = page.locator(".recovery-dialog").last();
    await checkpoints
      .getByRole("button", { name: "Preview restore", exact: true })
      .click();
    const cp = page
      .locator(".recovery-dialog")
      .filter({ has: page.getByRole("heading", { name: /Restore preview/ }) });
    await cp.waitFor();
    assert.match(
      await cp.innerText(),
      /Accounts, passwords, paired devices and security settings stay current/,
    );
    await cp.getByRole("button", { name: "Close", exact: true }).click();
    await checkpoints
      .getByRole("button", { name: "Close", exact: true })
      .click();
    await page.getByRole("button", { name: "Management", exact: true }).click();
    await page
      .getByRole("heading", { name: "History & recovery storage", exact: true })
      .waitFor();
    await shot(page, "72-history-retention");
    await page
      .getByRole("button", { name: "Retention settings", exact: true })
      .click();
    await page.getByLabel("Password", { exact: true }).fill(password);
    await page.locator("#dialog-submit").click();
    await page.getByLabel("Versions per item", { exact: true }).fill("12");
    await page.locator("#dialog-submit").click();
    await page.getByText(/12 per item/).waitFor();
  } finally {
    await s.close();
  }
});

test("a lost restore reply has a visible review path and status lookup does not repeat the mutation", async () => {
  const s = await scene();
  try {
    const { page, send } = s,
      id = randomUUID();
    await send("note.create", id, {
      title: "Household reference",
      body: "Before",
    });
    await send(
      "note.update",
      id,
      { title: "Household reference", body: "After" },
      1,
    );
    await page.getByRole("button", { name: "History", exact: true }).click();
    await page
      .locator(".history-entry")
      .first()
      .getByRole("button", { name: "Item versions", exact: true })
      .click();
    await page
      .locator(".recovery-dialog")
      .last()
      .getByRole("heading", { name: /#1/ })
      .waitFor();
    await page
      .locator(".recovery-dialog")
      .last()
      .locator(".history-entry")
      .filter({ has: page.getByRole("heading", { name: /#1/ }) })
      .getByRole("button", { name: "Preview restore", exact: true })
      .click();
    let dropped = false;
    await page.route("**/api/recovery", async (route) => {
      if (!dropped && route.request().postDataJSON().action === "apply") {
        dropped = true;
        assert.equal((await route.fetch()).status(), 200);
        await route.abort("failed");
      } else await route.continue();
    });
    const preview = page
      .locator(".recovery-dialog")
      .filter({ has: page.getByRole("heading", { name: /Restore preview/ }) });
    const confirmation = await restoreConfirmation(
      page,
      preview,
      "RESTORE ITEM #1",
    );
    await confirmation.submit.click();
    await confirmation.dialog
      .getByRole("alert")
      .filter({ hasText: "Result unconfirmed" })
      .waitFor();
    await confirmation.dialog
      .getByRole("button", { name: "Close", exact: true })
      .click();
    await preview.getByRole("button", { name: "Close", exact: true }).click();
    await page
      .locator(".recovery-dialog")
      .last()
      .getByRole("button", { name: "Close", exact: true })
      .click();
    await page.reload();
    await page.getByRole("button", { name: "History", exact: true }).click();
    await page
      .getByRole("heading", {
        name: "Recovery result needs review",
        exact: true,
      })
      .waitFor();
    const lookup = page.waitForResponse((r) =>
      r.url().endsWith("/api/recovery"),
    );
    await page
      .getByRole("button", { name: "Check recovery result", exact: true })
      .click();
    const result = await (await lookup).json();
    assert.equal(result.known, true, JSON.stringify(result));
    await page
      .getByRole("heading", {
        name: "Recovery result needs review",
        exact: true,
      })
      .waitFor({ state: "detached" });
    const snap = (await s.f.request("/api/snapshot", { token: s.token })).data;
    assert.equal(snap.records.find((r) => r.id === id).revision, 3);
    assert.equal(snap.records.find((r) => r.id === id).data.body, "Before");
  } finally {
    await s.close();
  }
});

test("retention-only changes close stale recovery dialogs through normal background refresh", async () => {
  const s = await scene();
  try {
    const { page, send } = s,
      id = randomUUID();
    await send("note.create", id, { title: "Reference", body: "Before" });
    await send("note.update", id, { body: "After" }, 1);
    await page.getByRole("button", { name: "History", exact: true }).click();
    await page
      .locator(".history-entry")
      .first()
      .getByRole("button", { name: "Item versions", exact: true })
      .click();
    const versions = page.locator(".recovery-dialog").last();
    await versions.getByRole("heading", { name: /#1/ }).waitFor();
    await versions
      .locator(".history-entry")
      .filter({ has: page.getByRole("heading", { name: /#1/ }) })
      .getByRole("button", { name: "Preview restore", exact: true })
      .click();
    await page.getByRole("heading", { name: /Restore preview/ }).waitFor();
    const basis = (await s.f.request("/api/snapshot", { token: s.token })).data;
    const status = await s.f.request("/api/recovery", {
      token: s.token,
      body: {
        action: "status",
        epoch: basis.epoch,
        generation: basis.generation,
      },
    });
    assert.equal(
      (await s.f.request("/api/reauth", { token: s.token, body: { password } }))
        .status,
      200,
    );
    assert.equal(
      (
        await s.f.request("/api/admin", {
          token: s.token,
          body: {
            action: "recovery.purge",
            scope: "versions",
            revision: status.data.history_revision,
            confirmation: "PURGE HISTORY",
          },
        })
      ).status,
      200,
    );
    await page.waitForFunction(
      () => !document.querySelector("dialog.recovery-dialog"),
      null,
      { timeout: 25000 },
    );
    assert.equal(
      (
        await s.f.request("/api/snapshot", { token: s.token })
      ).data.records.find((r) => r.id === id).data.body,
      "After",
    );
  } finally {
    await s.close();
  }
});

test("checkpoint restore and individual deletion require deliberate confirmation; cancellation keeps content", async () => {
  const s = await scene();
  try {
    const { page } = s,
      task = randomUUID(),
      later = randomUUID();
    await s.send("task.create", task, {
      title: "Original task",
      notes: "Before",
    });
    let basis = (await s.f.request("/api/snapshot", { token: s.token })).data;
    const rec = (action, extra = {}) =>
      s.f.request("/api/recovery", {
        token: s.token,
        body: {
          action,
          epoch: basis.epoch,
          generation: basis.generation,
          ...extra,
        },
      });
    const status = (await rec("status")).data;
    const saved = await rec("checkpoint.create", {
      operation_id: randomUUID(),
      name: "Careful restore",
      history_revision: status.history_revision,
    });
    assert.equal(saved.status, 200);
    await s.send("task.update", task, { notes: "After" }, 1);
    await s.send("note.create", later, {
      title: "Later note",
      body: "Removed by restore",
    });
    await page.getByRole("button", { name: "History", exact: true }).click();
    await page
      .getByRole("button", { name: "Content checkpoints", exact: true })
      .click();
    let list = page.locator(".recovery-dialog").last();
    await list
      .getByRole("button", { name: "Delete checkpoint", exact: true })
      .click();
    let deletion = page.locator(".recovery-dialog").filter({
      has: page.getByRole("heading", {
        name: "Delete content checkpoint",
        exact: true,
      }),
    });
    assert.equal(
      await deletion
        .getByRole("button", { name: "Delete content checkpoint", exact: true })
        .isDisabled(),
      true,
    );
    await deletion.getByRole("button", { name: "Close", exact: true }).click();
    assert.equal((await rec("checkpoints")).data.entries.length, 1);
    await list
      .getByRole("button", { name: "Preview restore", exact: true })
      .click();
    const preview = page.locator(".recovery-dialog").last();
    assert.match(await preview.innerText(), /1 changed · 1 removed/);
    let confirmation = await restoreConfirmation(
      page,
      preview,
      "RESTORE CHECKPOINT #1",
      "Restore content checkpoint",
    );
    await confirmation.dialog
      .getByLabel("Your account password", { exact: true })
      .fill("wrong-test-password");
    const failed = page.waitForResponse((r) => r.url().endsWith("/api/reauth"));
    await confirmation.submit.click();
    assert.equal((await failed).status(), 401);
    await confirmation.dialog
      .getByRole("alert")
      .filter({ hasText: /password|credentials/i })
      .waitFor();
    assert.equal(
      (
        await s.f.request("/api/snapshot", { token: s.token })
      ).data.records.find((r) => r.id === task).data.notes,
      "After",
    );
    await confirmation.dialog
      .getByLabel("Your account password", { exact: true })
      .fill(password);
    await confirmation.submit.click();
    await preview.waitFor({ state: "detached" });
    basis = (await s.f.request("/api/snapshot", { token: s.token })).data;
    assert.equal(basis.records.find((r) => r.id === task).data.notes, "Before");
    assert.equal(
      basis.records.some((r) => r.id === later),
      false,
    );
    assert.equal(basis.generation, 2);
    const before = basis.records;
    await page.setViewportSize({ width: 390, height: 844 });
    await page
      .getByRole("button", { name: "Content checkpoints", exact: true })
      .click();
    list = page.locator(".recovery-dialog").last();
    await list
      .locator(".history-entry")
      .filter({
        has: page.getByRole("heading", {
          name: "#1 · Careful restore",
          exact: true,
        }),
      })
      .getByRole("button", { name: "Delete checkpoint", exact: true })
      .click();
    deletion = page.locator(".recovery-dialog").filter({
      has: page.getByRole("heading", {
        name: "Delete content checkpoint",
        exact: true,
      }),
    });
    await deletion
      .getByLabel("Type DELETE CHECKPOINT #1 to confirm", { exact: true })
      .fill("DELETE CHECKPOINT #1");
    await deletion.getByRole("checkbox").check();
    await deletion
      .getByLabel("Your account password", { exact: true })
      .fill(password);
    await deletion
      .getByRole("button", { name: "Delete content checkpoint", exact: true })
      .click();
    await deletion.waitFor({ state: "detached" });
    await page
      .locator(".recovery-dialog")
      .last()
      .getByRole("heading", { name: /#2 · Before restoring/ })
      .waitFor();
    const remaining = (await rec("checkpoints")).data.entries;
    assert.deepEqual(
      remaining.map((e) => e.index),
      [2],
    );
    assert.deepEqual(
      (await s.f.request("/api/snapshot", { token: s.token })).data.records,
      before,
    );
  } finally {
    await s.close();
  }
});
