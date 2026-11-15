import { test } from "node:test";
import assert from "node:assert/strict";
import { chromium } from "playwright";
import { fixture, password } from "./support.mjs";

test("undated task priority and repeat intervals save, reopen and stop without changing the cadence on sound edits", async () => {
  const f = await fixture();
  let browser;
  try {
    browser = await chromium.launch({
      executablePath: process.env.CHROMIUM_PATH || "/usr/bin/chromium",
      headless: true,
      args: ["--no-sandbox"],
    });
    const page = await browser.newPage({
      viewport: { width: 390, height: 760 },
    });
    const errors = [];
    page.on("pageerror", (e) => errors.push(e.message));
    await page.goto(f.admin);
    await page.getByLabel("User name", { exact: true }).fill("owner");
    await page.getByLabel("Password", { exact: true }).fill(password);
    await page.getByRole("button", { name: "Sign in", exact: true }).click();
    await page
      .getByRole("heading", { name: "Calendar", exact: true })
      .waitFor();
    await page.evaluate(() => {
      void newStandaloneTask();
    });
    await page
      .getByLabel("Task title", { exact: true })
      .fill("Check the seedlings");
    await page.getByLabel("Priority", { exact: true }).selectOption("1");
    await page.locator(".reminder-editor > summary").click();
    const addDateReminder = page.getByRole("button", {
      name: "Add reminder",
      exact: true,
    });
    assert.equal(await addDateReminder.isEnabled(), false);
    await page
      .getByLabel("Due date · optional", { exact: true })
      .fill("2026-12-01");
    assert.equal(await addDateReminder.isEnabled(), true);
    await page.getByLabel("Due date · optional", { exact: true }).fill("");
    assert.equal(await addDateReminder.isEnabled(), false);
    await page.getByLabel("Repeat every interval", { exact: true }).check();
    await page.getByLabel("Repeat interval", { exact: true }).fill("3");
    const units = page.getByLabel("Repeat interval unit", { exact: true });
    assert.deepEqual(await units.locator("option").allTextContents(), [
      "Minutes",
      "Hours",
      "Days",
      "Weeks",
      "Months",
      "Years",
    ]);
    await units.selectOption("days");
    await page.locator(".reminder-window details > summary").click();
    await page
      .getByLabel("Repeating nudge importance", { exact: true })
      .selectOption("urgent");
    await page
      .getByRole("button", { name: "Create task", exact: true })
      .click();
    await page.locator("#dialog").waitFor({ state: "hidden" });
    const token = await f.login();
    const read = async () =>
      (await f.request("/api/snapshot", { token })).data.records.find(
        (r) => r.data.title === "Check the seedlings",
      );
    let item = await read();
    assert.equal(item.data.priority, 1);
    assert.equal(item.data.due, "");
    const saved = item.data.reminder_interval_rules.interval;
    assert.equal(saved.amount, 3);
    assert.equal(saved.unit, "days");
    assert.equal(saved.importance, "urgent");
    assert(saved.anchor_at > 0);
    await page.evaluate((id) => {
      void FieldReminders.edit(byId(id));
    }, item.id);
    await page.locator(".reminder-editor > summary").click();
    assert.equal(await units.inputValue(), "days");
    assert.equal(
      await page.getByLabel("Repeat interval", { exact: true }).inputValue(),
      "3",
    );
    await page.locator(".reminder-window details > summary").click();
    assert.equal(
      await page
        .getByLabel("Repeating nudge importance", { exact: true })
        .inputValue(),
      "urgent",
    );
    await page
      .getByLabel("Repeating nudge importance", { exact: true })
      .selectOption("normal");
    await page
      .getByRole("button", { name: "Save reminders", exact: true })
      .click();
    await page.locator("#dialog").waitFor({ state: "hidden" });
    item = await read();
    assert.equal(
      item.data.reminder_interval_rules.interval.anchor_at,
      saved.anchor_at,
    );
    assert.equal(
      item.data.reminder_interval_rules.interval.importance,
      "normal",
    );
    await page.evaluate((id) => {
      void FieldReminders.edit(byId(id));
    }, item.id);
    await page.locator(".reminder-editor > summary").click();
    await page.getByLabel("Repeat every interval", { exact: true }).uncheck();
    await page
      .getByRole("button", { name: "Save reminders", exact: true })
      .click();
    await page.locator("#dialog").waitFor({ state: "hidden" });
    item = await read();
    assert.equal(item.data.reminder_interval_rules, undefined);
    assert(!item.data.reminder_schedule_rules?.interval);
    assert.equal(item.data.reminders.alerts.length, 0);
    assert.deepEqual(errors, []);
  } finally {
    await browser?.close();
    await f.close();
  }
});
