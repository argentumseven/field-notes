import { test } from "node:test";
import assert from "node:assert/strict";
import { chromium } from "playwright";
import { mkdir } from "node:fs/promises";
import { fixture, password } from "./support.mjs";

test("custom reminder time uses amount and friendly units and round-trips month/year rules", async () => {
  const f = await fixture();
  let browser;
  try {
    browser = await chromium.launch({
      executablePath: process.env.CHROMIUM_PATH || "/usr/bin/chromium",
      headless: true,
      args: ["--no-sandbox"],
    });
    const page = await browser.newPage({
        viewport: { width: 1440, height: 1080 },
        timezoneId: "America/New_York",
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
    await page.getByRole("button", { name: "New event", exact: true }).click();
    await page
      .getByLabel("Event title", { exact: true })
      .fill("Annual household review");
    await page.getByLabel("Starts", { exact: true }).fill("2028-03-31");
    await page.getByLabel("Start time", { exact: true }).fill("10:00");
    await page.locator(".reminder-editor>summary").click();
    await page.getByLabel("Reminder 1", { exact: true }).selectOption("custom");
    assert.deepEqual(
      await page
        .getByLabel("Time unit", { exact: true })
        .locator("option")
        .allTextContents(),
      ["Minutes", "Hours", "Days", "Weeks", "Months", "Years"],
    );
    await page.getByLabel("Amount before", { exact: true }).fill("1");
    await page.getByLabel("Time unit", { exact: true }).selectOption("months");
    assert.equal(
      await page.getByLabel("Minutes before", { exact: true }).count(),
      0,
    );
    await page
      .getByRole("button", { name: "Add reminder", exact: true })
      .click();
    await page.getByLabel("Reminder 2", { exact: true }).selectOption("custom");
    const second = page.locator(".reminder-row").nth(1);
    await second.getByLabel("Amount before", { exact: true }).fill("2");
    await second.getByLabel("Time unit", { exact: true }).selectOption("years");
    if (process.env.FIELD_NOTES_CAPTURE_TIMING === "synthetic-only") {
      await mkdir("docs/screenshots/web", { recursive: true });
      await page.screenshot({
        path: "docs/screenshots/web/105-custom-reminder-units.png",
        fullPage: true,
      });
    }
    await page.getByRole("button", { name: "Save event", exact: true }).click();
    await page.getByRole("dialog").waitFor({ state: "hidden" });
    const token = await f.login();
    const snapshot = (await f.request("/api/snapshot", { token })).data;
    const event = snapshot.records.find(
      (r) => r.data.title === "Annual household review",
    );
    assert.equal(
      event.data.reminder_timing_rules.alerts[0].lead_time.unit,
      "months",
    );
    assert.equal(
      event.data.reminder_timing_rules.alerts[1].lead_time.unit,
      "years",
    );
    await page.evaluate((id) => {
      void eventEditor(byId(id));
    }, event.id);
    await page.locator(".reminder-editor>summary").click();
    assert.equal(
      await page
        .locator(".reminder-row")
        .nth(0)
        .getByLabel("Amount before", { exact: true })
        .inputValue(),
      "1",
    );
    assert.equal(
      await second.getByLabel("Time unit", { exact: true }).inputValue(),
      "years",
    );
    assert.deepEqual(errors, []);
  } finally {
    await browser?.close();
    await f.close();
  }
});
