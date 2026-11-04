import { test } from "node:test";
import assert from "node:assert/strict";
import { chromium } from "playwright";
import { randomUUID } from "node:crypto";
import { mkdir } from "node:fs/promises";
import { fixture, password } from "./support.mjs";
async function open(f) {
  const browser = await chromium.launch({
    executablePath: process.env.CHROMIUM_PATH || "/usr/bin/chromium",
    headless: true,
    args: ["--no-sandbox"],
  });
  const page = await browser.newPage({
    viewport: { width: 1440, height: 1050 },
    timezoneId: "America/New_York",
    locale: "en-US",
    colorScheme: "dark",
  });
  await page.clock.setFixedTime(new Date("2026-11-03T14:00:00Z"));
  await page.goto(f.admin);
  await page.getByLabel("User name", { exact: true }).fill("owner");
  await page.getByLabel("Password", { exact: true }).fill(password);
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await page.getByRole("heading", { name: "Calendar", exact: true }).waitFor();
  return { browser, page };
}
async function shot(page, name) {
  await mkdir("docs/screenshots/web", { recursive: true });
  await page
    .locator("#notice.visible")
    .waitFor({ state: "hidden", timeout: 10000 });
  await page.mouse.move(0, 0);
  await page.screenshot({
    path: "docs/screenshots/web/" + name + ".png",
    fullPage: true,
  });
}

test("calendar uses the highlighted day, local ten AM, optional ends and per-reminder sound controls", async () => {
  const f = await fixture({ enrollment: true });
  let browser, page;
  try {
    ({ browser, page } = await open(f));
    const errors = [];
    page.on("pageerror", (e) => errors.push(e.message));
    await page.getByRole("button", { name: "Month", exact: true }).click();
    await page.getByRole("button", { name: /^2026-11-04 ·/ }).click();
    await page.getByRole("button", { name: "New event", exact: true }).click();
    assert.equal(
      await page.getByLabel("Starts", { exact: true }).inputValue(),
      "2026-11-04",
    );
    assert.equal(
      await page.getByLabel("Set an end time", { exact: true }).isChecked(),
      false,
    );
    assert.equal(
      await page.getByLabel("End time", { exact: true }).isVisible(),
      false,
    );
    await page
      .getByLabel("Event title", { exact: true })
      .fill("Weekly check-in");
    await page.getByLabel("Start time", { exact: true }).fill("10:00");
    await page.locator(".reminder-editor>summary").click();
    await page
      .getByRole("button", { name: "Add reminder", exact: true })
      .click();
    await page.getByLabel("Reminder 2", { exact: true }).selectOption("0");
    await page
      .locator(".reminder-row")
      .nth(1)
      .locator("details>summary")
      .click();
    await page
      .getByLabel("Reminder 2 importance", { exact: true })
      .selectOption("urgent");
    await page
      .getByLabel("Reminder 2 sound", { exact: true })
      .selectOption("bell");
    await page
      .getByLabel("Reminder 2 sound level", { exact: true })
      .selectOption("100");
    await page.getByLabel("Who receives reminders",{exact:true}).selectOption("self");
    // Changing audience rerenders the controls; expand the second sound panel.
    await page.locator(".reminder-row").nth(1).locator("details>summary").click();
    await page.getByLabel("Reminder 2 sound",{exact:true}).selectOption("sunrise");
    await page.getByLabel("Reminder 2 · play as an alarm",{exact:true}).check();
    await page.getByLabel("Reminder 2 alarm duration",{exact:true}).selectOption("30");
    assert.match(await page.locator(".reminder-row").nth(1).locator("details>summary").textContent(),/Alarm.*Sunrise.*30 seconds/);
    await shot(page,"42-personal-alarm-controls");
    await page.getByLabel("Who receives reminders",{exact:true}).scrollIntoViewIfNeeded();
    await shot(page,"43-reminder-recipients");
    await page
      .getByLabel("Repeat during a time window", { exact: true })
      .check();
    assert.equal(
      await page.getByLabel("First reminder day", { exact: true }).inputValue(),
      "2026-11-04",
    );
    await page
      .getByLabel("Repeat days", { exact: true })
      .selectOption("weekly");
    await page.getByRole("button", { name: "Fri", exact: true }).click();
    await page.getByLabel("Repeat every · minutes", { exact: true }).fill("30");
    await page.getByLabel("Stop at", { exact: true }).fill("17:00");
    const sent = page.waitForResponse(
      (r) => r.url().endsWith("/api/commands") && r.status() === 200,
    );
    await page.getByRole("button", { name: "Save event", exact: true }).click();
    const saved = (await (await sent).json()).record;
    assert.equal(saved.data.ends_at, null);
    assert.equal(
      saved.data.starts_at,
      Date.parse("2026-11-04T15:00:00Z") / 1000,
    );
    assert.equal(saved.data.reminders.alerts.length, 2);
    assert.equal(saved.data.reminders.alerts[1].importance, "urgent");
    assert.equal(saved.data.reminders.alerts[1].volume, 100);
    assert.equal(saved.data.reminder_options.audience.mode,"people");
    const playback=saved.data.reminder_options.playback[saved.data.reminders.alerts[1].id];
    assert.equal(playback.tone,"sunrise");assert.equal(playback.duration_seconds,30);assert.equal(playback.alarm,true);assert.equal(playback.unmute,true);
    assert.deepEqual(saved.data.reminders.repeat.weekdays, [3, 5]);
    await page.getByRole("button", { name: "Day", exact: true }).click();
    const point = page
      .locator(".calendar-time-event.point")
      .filter({ hasText: "Weekly check-in" });
    await point.waitFor();
    assert.match(await point.getAttribute("title"), /10:00 AM.*Start only/);
    await shot(page, "35-calendar-day");
    await point.click();
    await page.getByLabel("Set an end time", { exact: true }).check();
    await page.getByLabel("End time", { exact: true }).fill("12:00");
    await page.getByRole("button", { name: "Save event", exact: true }).click();
    await page
      .locator(".calendar-time-event:not(.point)")
      .filter({ hasText: "Weekly check-in" })
      .waitFor();
    await shot(page, "36-calendar-blocks");
    await page
      .locator(".calendar-time-event:not(.point)")
      .filter({ hasText: "Weekly check-in" })
      .click();
    await shot(page, "34-local-event-time");
    await page.getByRole("button", { name: "Cancel", exact: true }).click();
    assert.deepEqual(errors, []);
  } catch (e) {
    if (page) {
      await mkdir("dist/screenshots", { recursive: true });
      await page
        .screenshot({
          path: "dist/screenshots/planner-calendar-failure.png",
          fullPage: true,
        })
        .catch(() => {});
    }
    throw e;
  } finally {
    await browser?.close();
    await f.close();
  }
});

test("typed rolling lists use attached previews only, show authorship and clear just checked items", async () => {
  const f = await fixture({ enrollment: true });
  let browser, page;
  try {
    ({ browser, page } = await open(f));
    const external = [],
      errors = [];
    page.on("request", (r) => {
      if (
        !r.url().startsWith(f.admin) &&
        !r.url().startsWith("blob:") &&
        !r.url().startsWith("data:")
      )
        external.push(r.url());
    });
    page.on("pageerror", (e) => errors.push(e.message));
    assert.equal(await page.locator(".sidebar .create-menu").count(), 0);
    assert.equal(
      await page
        .getByRole("button", { name: "Unscheduled", exact: true })
        .isVisible(),
      false,
    );
    await page.getByRole("button", { name: "Lists", exact: true }).click();
    await page.getByRole("button", { name: "New list", exact: true }).click();
    await page.getByLabel("List name", { exact: true }).fill("Saved links");
    await page
      .getByLabel("List type", { exact: true })
      .selectOption("groceries");
    assert.equal(
      await page.getByLabel("Quantity", { exact: true }).isChecked(),
      true,
    );
    assert.equal(
      await page.getByLabel("Price", { exact: true }).isChecked(),
      false,
    );
    await page
      .getByLabel("List type", { exact: true })
      .selectOption("shopping");
    assert.equal(
      await page.getByLabel("Web link", { exact: true }).isChecked(),
      true,
    );
    assert.equal(
      await page.getByLabel("Price", { exact: true }).isChecked(),
      false,
    );
    await page.getByLabel("Price", { exact: true }).check();
    await shot(page, "37-list-fields");
    const issued = page.waitForResponse(
      (r) => r.url().endsWith("/api/commands") && r.status() === 200,
    );
    await page
      .getByRole("button", { name: "Create list", exact: true })
      .click();
    const list = (await (await issued).json()).record;
    await page
      .locator(".collection-row")
      .filter({ hasText: "Saved links" })
      .click();
    await page.getByRole("button", { name: "Add item", exact: true }).click();
    await page.getByLabel("Item name", { exact: true }).fill("Reading lamp");
    await page.getByLabel("Quantity", { exact: true }).fill("1");
    await page
      .getByLabel("Web link", { exact: true })
      .fill("https://example.invalid/lamp");
    await page.getByLabel("Price", { exact: true }).fill("34.95");
    const added = page.waitForResponse(
      (r) => r.url().endsWith("/api/commands") && r.status() === 200,
    );
    await page
      .getByRole("button", { name: "Add item", exact: true })
      .last()
      .click();
    const item = (await (await added).json()).record;
    const owner = await f.login();
    const snapshot = (await f.request("/api/snapshot", { token: owner })).data;
    const send = (action, target, data = {}, revision = null) =>
      f.request("/api/commands", {
        token: owner,
        body: {
          operation_id: randomUUID(),
          epoch: snapshot.epoch,
          generation: snapshot.generation,
          action,
          target,
          data,
          expected_revision: revision,
        },
      });
    const picture = await browser.newPage({
      viewport: { width: 320, height: 240 },
    });
    await picture.setContent(
      '<body style="margin:0;padding:35px;background:#eef2ff;color:#26396b;font:18px system-ui"><h2>Reading lamp</h2><p>Attached reference image</p><p>USD 34.95</p></body>',
    );
    const image = await picture.screenshot();
    await picture.close();
    assert.equal(
      (
        await send("image.create", randomUUID(), {
          task_id: item.id,
          name: "reference.png",
          bytes: image.toString("base64"),
        })
      ).status,
      200,
    );
    assert.equal(
      (
        await send("task.create", randomUUID(), {
          project_id: list.id,
          title: "Keep this unchecked",
        })
      ).status,
      200,
    );
    await page
      .getByRole("button", { name: "Refresh now", exact: true })
      .click();
    await page.locator(".list-thumbnail").waitFor();
    assert(
      (await page.locator(".list-thumbnail").getAttribute("src")).startsWith(
        "/api/images/",
      ),
    );
    await page
      .locator(".checklist-item")
      .filter({ hasText: "Reading lamp" })
      .getByText(/Created by owner/)
      .waitFor();
    await shot(page, "38-shopping-list");
    await page
      .getByRole("checkbox", { name: "Check Reading lamp", exact: true })
      .check();
    await page
      .getByRole("button", { name: "Clear checked (1)", exact: true })
      .click();
    await page
      .getByRole("button", { name: "Clear checked", exact: true })
      .click();
    await page
      .getByText("Reading lamp", { exact: true })
      .waitFor({ state: "hidden" });
    await page.getByText("Keep this unchecked", { exact: true }).waitFor();
    await page.getByRole("button", { name: "Notes", exact: true }).click();
    await page.getByRole("button", { name: "New note", exact: true }).click();
    await page
      .getByLabel("Note title", { exact: true })
      .fill("One complete note");
    await page
      .getByLabel("Content", { exact: true })
      .fill("Created with its title and content together.");
    await page
      .getByRole("button", { name: "Create note", exact: true })
      .click();
    await page.getByText("One complete note", { exact: true }).waitFor();
    assert.equal(await page.locator(".detail").count(), 0);
    assert.deepEqual(external, []);
    assert.deepEqual(errors, []);
  } catch (e) {
    if (page) {
      await mkdir("dist/screenshots", { recursive: true });
      await page
        .screenshot({
          path: "dist/screenshots/planner-lists-failure.png",
          fullPage: true,
        })
        .catch(() => {});
    }
    throw e;
  } finally {
    await browser?.close();
    await f.close();
  }
});
