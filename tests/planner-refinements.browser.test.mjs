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
  if (process.env.FIELD_NOTES_CAPTURE_PLANNER !== "synthetic-only") return;
  await mkdir("docs/screenshots/web", { recursive: true });
  await page.screenshot({
    path: `docs/screenshots/web/${name}.png`,
    fullPage: true,
  });
}

test("annual repeats and custom years have useful future previews and keep the existing wire format", async () => {
  const s = await scene();
  try {
    const { page } = s;
    await page.getByRole("button", { name: "New event", exact: true }).click();
    await page
      .getByLabel("Event title", { exact: true })
      .fill("Annual household review");
    await page.getByLabel("Starts", { exact: true }).fill("2026-11-06");
    await page.getByLabel("Start time", { exact: true }).fill("10:00");
    await page.getByLabel("Repeat", { exact: true }).selectOption("yearly");
    assert.match(
      await page.locator(".repeat-editor").innerText(),
      /Every year[\s\S]*2027[\s\S]*2030/,
    );
    assert.equal(
      await page.getByLabel("Repeat every", { exact: true }).count(),
      0,
      "presets do not expose redundant interval controls",
    );
    await page.getByLabel("Repeat", { exact: true }).selectOption("custom");
    assert.equal(
      await page.getByLabel("Repeat", { exact: true }).inputValue(),
      "custom",
    );
    await page
      .getByLabel("Repeat every", { exact: true })
      .selectOption("yearly");
    await page.getByLabel("Every", { exact: true }).fill("2");
    await page.getByLabel("Every", { exact: true }).blur();
    assert.match(
      await page.locator(".repeat-editor").innerText(),
      /Every 2 years[\s\S]*2028[\s\S]*2034/,
    );
    await shot(page, "66-yearly-repeat");
    const reply = page.waitForResponse(
      (r) => r.url().endsWith("/api/commands") && r.status() === 200,
    );
    await page.getByRole("button", { name: "Save event", exact: true }).click();
    const event = (await (await reply).json()).record;
    assert.equal(event.data.recurrence.frequency, "monthly");
    assert.equal(event.data.recurrence.interval, 24);
    assert.equal(event.data.ends_at, null);
  } finally {
    await s.close();
  }
});

test("household aliases stay intact and rolling-list reuse preserves changed items and contents", async () => {
  const s = await scene();
  try {
    const { page, send } = s;
    const list = randomUUID(),
      item = randomUUID(),
      other = randomUUID();
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
        item,
        {
          title: "Bananas",
          notes: "Keep these notes",
          quantity: "2",
          store: "Costco",
          project_id: list,
        },
      ],
      [
        "task.create",
        other,
        { title: "Milk", store: "Market", project_id: list },
      ],
      [
        "task.create",
        randomUUID(),
        { title: "Repair shelf", category: "honey_do" },
      ],
      ["task.create", randomUUID(), { title: "Paint gate", category: "home" }],
    ])
      assert.equal((await send(action, id, data)).status, 200);
    assert.equal((await send("task.complete", item, {}, 1)).status, 200);
    assert.equal((await send("task.complete", other, {}, 1)).status, 200);
    await page
      .getByRole("button", { name: "Refresh now", exact: true })
      .click();
    await page.getByRole("button", { name: "Tasks", exact: true }).click();
    await page.getByRole("button", { name: "Filters ▾", exact: true }).click();
    await page.getByLabel("Category", { exact: true }).selectOption("home");
    assert.match(await page.locator("#task-view").innerText(), /Repair shelf/);
    assert.match(await page.locator("#task-view").innerText(), /Paint gate/);
    assert.equal(
      await page
        .getByLabel("Category", { exact: true })
        .locator('option[value="honey_do"]')
        .count(),
      0,
    );
    await page.getByRole("button", { name: "Lists", exact: true }).click();
    await page
      .getByRole("button", {
        name: "Open list: Costco Shopping List",
        exact: true,
      })
      .click();
    await page.getByLabel("Store", { exact: true }).selectOption("Costco");
    await page
      .getByRole("button", { name: "Reuse checked (1)", exact: true })
      .click();
    assert.equal(
      (
        await send(
          "task.update",
          item,
          { notes: "Sophia changed this while the confirmation was open" },
          2,
        )
      ).status,
      200,
    );
    await page
      .getByRole("button", { name: "Uncheck and reuse", exact: true })
      .click();
    await page
      .getByText("1 changed items kept for review.", { exact: false })
      .waitFor();
    let snapshot = (await s.f.request("/api/snapshot", { token: s.token }))
      .data;
    assert.equal(
      snapshot.records.find((r) => r.id === item).data.status,
      "done",
    );
    await page
      .getByRole("button", { name: "Reuse checked (1)", exact: true })
      .click();
    await shot(page, "67-reuse-list-preview");
    await page
      .getByRole("button", { name: "Uncheck and reuse", exact: true })
      .click();
    await page
      .getByRole("button", { name: "Reuse checked (0)", exact: true })
      .waitFor();
    snapshot = (await s.f.request("/api/snapshot", { token: s.token })).data;
    const restored = snapshot.records.find((r) => r.id === item);
    assert.equal(restored.data.status, "todo");
    assert.equal(restored.data.quantity, "2");
    assert.equal(restored.data.store, "Costco");
    assert.match(restored.data.notes, /Sophia changed/);
    assert.equal(
      snapshot.records.find((r) => r.id === other).data.status,
      "done",
    );
    assert.equal(snapshot.records.filter((r) => r.id === item).length, 1);
    assert.equal(
      snapshot.records.find((r) => r.data.title === "Repair shelf").data
        .category,
      "honey_do",
    );
    const radii = await page
      .locator(".sidebar button, .toolbar > button, .toolbar select")
      .evaluateAll((nodes) =>
        nodes.map((n) => getComputedStyle(n).borderTopLeftRadius),
      );
    assert(radii.length > 5);
    assert(
      radii.every((r) => r === "8px"),
      JSON.stringify(radii),
    );
    await shot(page, "68-reused-groceries");
  } finally {
    await s.close();
  }
});
