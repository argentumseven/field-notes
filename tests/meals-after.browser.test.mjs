import { test } from "node:test";
import assert from "node:assert/strict";
import { chromium } from "playwright";
import { fixture, password } from "./support.mjs";

test("meals repeat, named reminders run after start, copies keep details and optional calendar visibility persists", async () => {
  const f = await fixture();
  let browser;
  try {
    browser = await chromium.launch({
      executablePath: process.env.CHROMIUM_PATH || "/usr/bin/chromium",
      headless: true,
      args: ["--no-sandbox"],
    });
    const page = await browser.newPage({
        viewport: { width: 390, height: 800 },
        timezoneId: "UTC",
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
    await page.evaluate(() => {
      state.page = "meals";
      state.day = "2026-11-15";
      render();
    });
    await page.getByRole("button", { name: "Plan food", exact: true }).click();
    await page.getByLabel("Event title", { exact: true }).fill("Sunday roast");
    assert.equal(
      await page.getByLabel("Plan as", { exact: true }).inputValue(),
      "dinner",
    );
    await page.getByLabel("Repeat", { exact: true }).selectOption("weekly");
    await page.getByRole("tab", { name: "Reminders", exact: true }).click();
    const timing = page.getByLabel("Reminder 1", { exact: true });
    assert.equal(await timing.locator('option[value="day-time"]').count(), 0);
    await timing.selectOption("after");
    await page.getByLabel("Amount after", { exact: true }).fill("45");
    await page
      .getByLabel("Reminder label · optional", { exact: true })
      .fill("Pack leftovers");
    await page.getByRole("button", { name: "Save event", exact: true }).click();
    await page.locator("#dialog").waitFor({ state: "hidden" });
    const token = await f.login();
    const read = async () => (await f.request("/api/snapshot", { token })).data;
    let meal = (await read()).records.find(
      (r) => r.data.title === "Sunday roast",
    );
    assert.equal(meal.data.meal_kind, "dinner");
    assert.equal(meal.data.recurrence.frequency, "weekly");
    assert.equal(
      meal.data.reminder_after_rules.alerts[0].after_start.amount,
      45,
    );
    await page.evaluate((id) => {
      void eventEditor(byId(id)).catch((e) => {
        if (!(e instanceof DialogCanceled)) throw e;
      });
    }, meal.id);
    await page.getByRole("button", { name: "Clone…", exact: true }).click();
    await page.getByRole("button", { name: "Add date", exact: true }).click();
    await page.getByLabel("Copy time 1", { exact: true }).fill("19:00");
    assert(
      await page
        .locator("#dialog-body")
        .evaluate((n) => n.scrollWidth <= n.clientWidth + 1),
      "clone fields stay inside the narrow dialog",
    );
    if (process.env.FIELD_NOTES_CAPTURE_MEALS === "synthetic-only")
      await page
        .locator("#dialog")
        .screenshot({ path: "work/0119-web-clone.png" });
    let lostReply = false;
    await page.route("**/api/commands", async (route) => {
      const command = route.request().postDataJSON();
      if (command.action === "event.create" && !lostReply) {
        lostReply = true;
        await route.fetch();
        await route.abort("failed");
      } else await route.continue();
    });
    await page
      .getByRole("button", { name: "Create copies", exact: true })
      .click();
    await page.locator("#dialog").waitFor({ state: "hidden" });
    const copies = (await read()).records.filter(
      (r) => r.data.title === "Sunday roast",
    );
    assert.equal(copies.length, 3);
    assert(
      copies.some(
        (r) => new Date(r.data.starts_at * 1000).getUTCHours() === 19,
      ),
    );
    assert(
      lostReply,
      "clone replay was tested after a committed reply was lost",
    );
    assert.equal(copies.filter((r) => r.data.recurrence).length, 1);
    assert(
      copies.every(
        (r) => r.data.reminder_after_rules.alerts[0].label === "Pack leftovers",
      ),
    );
    await page.evaluate(() => {
      state.page = "calendar";
      state.day = "2026-11-15";
      state.month = new Date("2026-11-01T12:00Z");
      state.calendarView = "day";
      render();
    });
    assert.equal(
      await page.locator('[data-event-id="' + meal.id + '"]').count(),
      0,
    );
    await page.getByText("Calendar options", { exact: true }).click();
    await page.getByLabel("Show meals", { exact: true }).check();
    assert.equal(
      await page.evaluate(
        (id) => calendarItems(state.day).some((r) => r.record.id === id),
        meal.id,
      ),
      true,
    );
    for (const mode of ["Week", "Month"]) {
      await page.getByRole("button", { name: mode, exact: true }).click();
      assert.equal(
        await page.evaluate(
          (id) => calendarItems("2026-11-15").some((r) => r.record.id === id),
          meal.id,
        ),
        true,
      );
    }
    await page.reload();
    await page
      .getByRole("heading", { name: "Calendar", exact: true })
      .waitFor();
    assert.equal(
      await page.evaluate(() => readSetting("field-notes-calendar-meals")),
      "on",
    );
    assert.deepEqual(errors, []);
  } finally {
    await browser?.close();
    await f.close();
  }
});
