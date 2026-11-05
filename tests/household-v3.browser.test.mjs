import { test } from "node:test";
import assert from "node:assert/strict";
import { chromium } from "playwright";
import { randomUUID } from "node:crypto";
import { mkdir } from "node:fs/promises";
import { fixture, password } from "./support.mjs";

test("task filters, grocery store subviews, later notes and irregular event controls work together", async () => {
  const f = await fixture();
  let browser;
  try {
    const token = await f.login(),
      snapshot = (await f.request("/api/snapshot", { token })).data,
      list = randomUUID();
    const send = (action, id, data) =>
      f.request("/api/commands", {
        token,
        body: {
          operation_id: randomUUID(),
          epoch: snapshot.epoch,
          generation: snapshot.generation,
          action,
          target: id,
          expected_revision: null,
          data,
        },
      });
    for (const [action, id, data] of [
      [
        "list.create",
        list,
        {
          title: "Costco Shopping List",
          template: "groceries",
          category: "food",
        },
      ],
      [
        "task.create",
        randomUUID(),
        {
          title: "Bananas",
          notes: "Not too ripe",
          store: "Costco",
          project_id: list,
          category: "food",
        },
      ],
      [
        "task.create",
        randomUUID(),
        { title: "Milk", store: "Market", project_id: list, category: "food" },
      ],
      [
        "task.create",
        randomUUID(),
        {
          title: "Cook dinner",
          notes: "Use the freezer vegetables",
          category: "food",
        },
      ],
      [
        "task.create",
        randomUUID(),
        {
          title: "Repair the shelf",
          notes: "Hardware in the garage",
          category: "honey_do",
        },
      ],
    ])
      assert.equal((await send(action, id, data)).status, 200);
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
    await page.clock.setFixedTime(new Date("2026-11-05T14:00:00Z"));
    await page.goto(f.admin);
    await page.getByLabel("User name", { exact: true }).fill("owner");
    await page.getByLabel("Password", { exact: true }).fill(password);
    await page.getByRole("button", { name: "Sign in", exact: true }).click();
    await page
      .getByRole("heading", { name: "Calendar", exact: true })
      .waitFor();
    await page.getByRole("button", { name: "Tasks", exact: true }).click();
    assert.equal(
      await page.locator(".task-title").filter({ hasText: "Bananas" }).count(),
      0,
    );
    await page.getByRole("button", { name: "Filters ▾", exact: true }).click();
    await page.getByLabel("Category", { exact: true }).selectOption("food");
    assert.match(await page.locator("#task-view").innerText(), /Cook dinner/);
    assert.doesNotMatch(
      await page.locator("#task-view").innerText(),
      /Repair the shelf/,
    );
    await page
      .getByLabel("Find a task", { exact: true })
      .fill("freezer vegetables");
    assert.match(await page.locator("#task-view").innerText(), /Cook dinner/);
    await page.getByLabel("Find a task", { exact: true }).fill("Cook dinner");
    assert.match(await page.locator("#task-view").innerText(), /Cook dinner/);
    await page.getByRole("button", { name: "Lists", exact: true }).click();
    await page
      .getByLabel("Search names and descriptions", { exact: true })
      .fill("Costco");
    await page
      .getByRole("button", {
        name: "Open list: Costco Shopping List",
        exact: true,
      })
      .click();
    await page.getByLabel("Store", { exact: true }).selectOption("Costco");
    assert.match(await page.locator(".checklist-items").innerText(), /Bananas/);
    assert.doesNotMatch(
      await page.locator(".checklist-items").innerText(),
      /Milk/,
    );
    await page
      .getByLabel("Search this list", { exact: true })
      .fill("not too ripe");
    assert.match(await page.locator(".checklist-items").innerText(), /Bananas/);
    await page.getByLabel("Search this list", { exact: true }).fill("");
    await page.getByRole("button", { name: "Add item", exact: true }).click();
    assert.equal(
      await page.getByLabel("Store", { exact: true }).last().inputValue(),
      "Costco",
    );
    await page.getByLabel("Item name", { exact: true }).fill("Coffee");
    await page
      .getByRole("button", { name: "Add item", exact: true })
      .last()
      .click();
    await page.locator("#dialog").waitFor({ state: "hidden" });
    assert.match(await page.locator(".checklist-items").innerText(), /Coffee/);
    await mkdir("docs/screenshots/web", { recursive: true });
    await page.screenshot({
      path: "docs/screenshots/web/53-grocery-stores.png",
      fullPage: true,
    });
    await page
      .getByRole("button", { name: "Later & reminders", exact: true })
      .click();
    await page.getByRole("button", { name: "New note", exact: true }).click();
    await page
      .getByLabel("Note title", { exact: true })
      .fill("Greenhouse someday");
    await page
      .getByLabel("Content", { exact: true })
      .fill("Compare winter heating options");
    await page.getByRole("button", { name: "Tomorrow", exact: true }).click();
    await page.getByLabel("At", { exact: true }).fill("10:00");
    const response = page.waitForResponse(
      (r) => r.url().endsWith("/api/commands") && r.status() === 200,
    );
    await page
      .getByRole("button", { name: "Create reminder note", exact: true })
      .click();
    const note = (await (await response).json()).record;
    assert.equal(
      note.data.remind_at,
      Date.parse("2026-11-06T15:00:00Z") / 1000,
    );
    assert.equal(note.data.reminder_options.audience.mode, "everyone");
    await page
      .getByLabel("Find a note", { exact: true })
      .fill("winter heating");
    assert.match(
      await page.locator(".notes-list").innerText(),
      /Greenhouse someday/,
    );
    await page.screenshot({
      path: "docs/screenshots/web/54-later-reminders.png",
      fullPage: true,
    });
    await page.getByRole("button", { name: "Calendar", exact: true }).click();
    await page.getByRole("button", { name: "New event", exact: true }).click();
    await page
      .getByLabel("Event title", { exact: true })
      .fill("Household check-in");
    await page.getByLabel("Start time", { exact: true }).fill("10:00");
    await page
      .getByLabel("Repeat", { exact: true })
      .selectOption("fortnightly");
    await page.getByRole("button", { name: "Sun", exact: true }).click();
    await page.getByRole("button", { name: "Tue", exact: true }).click();
    await page
      .getByLabel("Last repeat day · optional", { exact: true })
      .fill("2027-01-31");
    await page.getByLabel("Last repeat day · optional", { exact: true }).blur();
    await page.screenshot({
      path: "docs/screenshots/web/55-repeat-schedule.png",
      fullPage: true,
    });
    const eventResponse = page.waitForResponse(
      (r) => r.url().endsWith("/api/commands") && r.status() === 200,
    );
    await page.getByRole("button", { name: "Save event", exact: true }).click();
    const event = (await (await eventResponse).json()).record;
    assert.equal(event.data.recurrence.interval, 2);
    assert(event.data.recurrence.weekdays.includes(7));
    assert(event.data.recurrence.weekdays.includes(2));
    assert.equal(event.data.reminder_options.audience.mode, "everyone");
    assert.equal(event.data.ends_at, null);
    assert.deepEqual(errors, []);
  } finally {
    await browser?.close();
    await f.close();
  }
});
