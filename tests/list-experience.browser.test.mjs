import { test } from "node:test";
import assert from "node:assert/strict";
import { chromium } from "playwright";
import { mkdir } from "node:fs/promises";
import { projectsFixture } from "./projects-fixture.mjs";
import { password } from "./support.mjs";

async function scene() {
  const s = await projectsFixture();
  let browser;
  try {
    const apples = await s.create("task", {
      title: "Apples",
      store: "Costco",
      project_id: s.grocery.id,
    });
    const milk = await s.create("task", {
      title: "Milk",
      store: "Market",
      project_id: s.grocery.id,
    });
    await s.send("task.complete", apples.id, {}, 1);
    await s.send("task.complete", milk.id, {}, 1);
    browser = await chromium.launch({
      executablePath: process.env.CHROMIUM_PATH || "/usr/bin/chromium",
      headless: true,
      args: ["--no-sandbox"],
    });
    const page = await browser.newPage({
      viewport: { width: 1440, height: 1080 },
      colorScheme: "dark",
      timezoneId: "America/New_York",
    });
    const errors = [];
    page.on("pageerror", (e) => errors.push(e.message));
    await page.goto(s.f.admin);
    await page.getByLabel("User name", { exact: true }).fill("Alex");
    await page.getByLabel("Password", { exact: true }).fill(password);
    await page.getByRole("button", { name: "Sign in", exact: true }).click();
    await page
      .getByRole("heading", { name: "Calendar", exact: true })
      .waitFor();
    await page.getByRole("button", { name: "Lists", exact: true }).click();
    await page
      .getByRole("button", {
        name: "Open list: Costco Shopping List",
        exact: true,
      })
      .click();
    return {
      ...s,
      page,
      apples,
      milk,
      errors,
      async close() {
        await browser.close();
        await s.f.close();
      },
    };
  } catch (e) {
    await browser?.close();
    await s.f.close();
    throw e;
  }
}
async function shot(page, name) {
  if (process.env.FIELD_NOTES_CAPTURE_LISTS !== "synthetic-only") return;
  await mkdir("docs/screenshots/web", { recursive: true });
  await page.screenshot({
    path: `docs/screenshots/web/${name}.png`,
    fullPage: true,
  });
}

async function scrollPosition(page) {
  return page.evaluate(() => ({
    page: window.scrollY,
    side: document.querySelector(".sidebar").scrollTop,
    calendar: document.querySelector(".calendar-scroller")?.scrollTop,
  }));
}
function samePosition(actual, expected) {
  for (const key of Object.keys(expected))
    if (expected[key] !== undefined)
      assert(
        Math.abs(actual[key] - expected[key]) <= 1,
        `${key} moved from ${expected[key]} to ${actual[key]}`,
      );
}

test("automatic and manual refresh keep a long list and its sidebar at the same scroll position", async () => {
  const s = await scene();
  try {
    const { page } = s;
    await page.setViewportSize({ width: 1280, height: 700 });
    for (let i = 1; i <= 28; i++)
      await s.create("task", {
        title: "Pantry item " + String(i).padStart(2, "0"),
        project_id: s.grocery.id,
        store: "Costco",
        notes: "Check the cupboard before buying.",
      });
    await page
      .getByRole("button", { name: "Open item: Pantry item 28", exact: true })
      .waitFor({ timeout: 12000 });
    await page.evaluate(() => {
      window.scrollTo(0, 1150);
      document.querySelector(".sidebar").scrollTop = 180;
    });
    const position = await scrollPosition(page);
    assert(position.page > 1000, "the fixture really is scrolled down");
    assert(position.side > 0, "the independent sidebar really is scrolled");
    await s.create("task", {
      title: "Zucchini",
      project_id: s.grocery.id,
      store: "Costco",
    });
    await page
      .getByRole("button", { name: "Open item: Zucchini", exact: true })
      .waitFor({ timeout: 12000 });
    samePosition(await scrollPosition(page), position);
    // Click in the DOM so the test itself does not scroll the top toolbar into view.
    await Promise.all([
      page.waitForResponse((r) => r.url().endsWith("/api/people")),
      page.evaluate(() => {
        window.beforeRefreshList = document.querySelector(".checklist-items");
        [...document.querySelectorAll("button")]
          .find((n) => n.textContent === "Refresh now")
          .click();
      }),
    ]);
    await page.waitForFunction(
      () =>
        window.beforeRefreshList !== document.querySelector(".checklist-items"),
    );
    samePosition(await scrollPosition(page), position);
    assert.deepEqual(s.errors, []);
  } finally {
    await s.close();
  }
});

test("calendar refresh preserves its timeline and management polling preserves the page", async () => {
  const s = await scene();
  try {
    const { page } = s;
    await page.setViewportSize({ width: 1280, height: 700 });
    await page.getByRole("button", { name: "Calendar", exact: true }).click();
    await page.getByRole("button", { name: "Week", exact: true }).click();
    await page.locator(".calendar-scroller").evaluate((n) => {
      n.scrollTop = 790;
    });
    await page.evaluate(() => window.scrollTo(0, 130));
    const position = await scrollPosition(page);
    assert(position.calendar > 700);
    const event = await s.create("event", {
      title: "Delivery window",
      all_day: false,
      starts_at: Math.floor(Date.now() / 1000),
      ends_at: null,
    });
    await page.waitForFunction(
      (id) => state.snapshot.records.some((r) => r.id === id),
      event.id,
      { timeout: 12000 },
    );
    samePosition(await scrollPosition(page), position);
    await page.getByText("Management", { exact: true }).click();
    await page
      .getByRole("heading", { name: "Administrative activity", exact: true })
      .waitFor();
    await page.evaluate(() => window.scrollTo(0, 850));
    const managementPosition = await scrollPosition(page);
    assert(managementPosition.page > 500);
    await Promise.all([
      page.waitForResponse((r) => r.url().endsWith("/api/admin")),
      page.evaluate(() => pollLiveChanges()),
    ]);
    samePosition(await scrollPosition(page), managementPosition);
    assert.deepEqual(s.errors, []);
  } finally {
    await s.close();
  }
});

test("unchecked groceries have individual deletion with confirmation, safe retry and a discoverable history", async () => {
  const s = await scene();
  try {
    const { page } = s;
    await page
      .getByRole("button", { name: "Delete item: Bananas", exact: true })
      .click();
    await page.getByRole("button", { name: "Cancel", exact: true }).click();
    assert.equal(
      (await s.f.request("/api/snapshot", { token: s.alex })).data.records.some(
        (r) => r.id === s.bananas.id,
      ),
      true,
    );
    await page
      .getByRole("button", { name: "Open item: Bananas", exact: true })
      .click();
    await page
      .getByRole("button", { name: "Delete item…", exact: true })
      .click();
    await page
      .getByRole("heading", { name: "Delete Bananas?", exact: true })
      .waitFor();
    assert.equal(
      await page.getByRole("textbox").count(),
      0,
      "phone-style confirmation needs no uppercase phrase",
    );
    await shot(page, "91-delete-grocery-confirmation");
    const ids = [];
    let lost = false;
    await page.route("**/api/commands", async (route) => {
      const data = route.request().postDataJSON();
      if (data.action === "task.delete" && data.target === s.bananas.id) {
        ids.push(data.operation_id);
        if (!lost) {
          lost = true;
          const committed = await route.fetch();
          assert.equal(committed.status(), 200);
          await route.abort("failed");
          return;
        }
      }
      await route.continue();
    });
    await page
      .getByRole("button", { name: "Delete item", exact: true })
      .click();
    await page.locator("#dialog").waitFor({ state: "hidden" });
    await page
      .getByRole("button", { name: "Open item: Bananas", exact: true })
      .waitFor({ state: "detached" });
    assert.equal(ids.length, 2);
    assert.equal(ids[0], ids[1]);
    assert.equal(
      (await s.f.request("/api/snapshot", { token: s.alex })).data.records.some(
        (r) => r.id === s.bananas.id,
      ),
      false,
    );
    await page
      .getByRole("button", { name: "List history", exact: true })
      .click();
    await page
      .locator(".history-timeline")
      .getByRole("heading", { name: "Bananas", exact: true })
      .waitFor();
    assert.deepEqual(s.errors, []);
  } finally {
    await s.close();
  }
});

test("delete checked includes hidden checked rows but keeps other stores, unchecked items and a concurrently changed item", async () => {
  const s = await scene();
  try {
    const { page } = s;
    await page.getByLabel("Store", { exact: true }).selectOption("Costco");
    await page.getByLabel("Show", { exact: true }).selectOption("open");
    assert.equal(
      await page
        .getByRole("button", { name: "Open item: Apples", exact: true })
        .count(),
      0,
    );
    await page
      .getByRole("button", { name: "Delete checked (1)", exact: true })
      .waitFor();
    await shot(page, "90-grocery-cleanup");
    await page
      .getByRole("button", { name: "Delete checked (1)", exact: true })
      .click();
    assert.match(await page.locator("#dialog-body").innerText(), /Apples/);
    await s.send(
      "task.update",
      s.apples.id,
      { quantity: "A different amount" },
      2,
      s.sophia,
    );
    await page
      .getByRole("button", { name: "Delete checked", exact: true })
      .click();
    await page.getByText("1 item needs review.", { exact: false }).waitFor();
    let snapshot = (await s.f.request("/api/snapshot", { token: s.alex })).data;
    assert(snapshot.records.some((r) => r.id === s.apples.id));
    assert(snapshot.records.some((r) => r.id === s.milk.id));
    assert(snapshot.records.some((r) => r.id === s.bananas.id));
    await page
      .getByRole("button", { name: "Delete checked (1)", exact: true })
      .click();
    await page
      .getByRole("button", { name: "Delete checked", exact: true })
      .click();
    await page
      .getByRole("button", { name: "Delete checked (0)", exact: true })
      .waitFor();
    snapshot = (await s.f.request("/api/snapshot", { token: s.alex })).data;
    assert(!snapshot.records.some((r) => r.id === s.apples.id));
    assert(snapshot.records.some((r) => r.id === s.milk.id));
    assert(snapshot.records.some((r) => r.id === s.bananas.id));
    assert.deepEqual(s.errors, []);
  } finally {
    await s.close();
  }
});

test("open grocery lists refresh during search without losing focus and show pending changes while an editor is open", async () => {
  const s = await scene();
  try {
    const { page } = s;
    const search = page.getByLabel("Search this list", { exact: true });
    await search.fill("Ba");
    await search.focus();
    await s.create(
      "task",
      { title: "Basil", store: "Costco", project_id: s.grocery.id },
      s.sophia,
    );
    await page
      .getByRole("button", { name: "Open item: Basil", exact: true })
      .waitFor({ timeout: 12000 });
    assert.equal(await search.inputValue(), "Ba");
    assert.equal(
      await search.evaluate((n) => document.activeElement === n),
      true,
    );
    assert.equal(await search.evaluate((n) => n.selectionStart), 2);
    await s.send(
      "task.update",
      s.bananas.id,
      { notes: "Please choose the smaller bunches" },
      1,
      s.sophia,
    );
    await page
      .locator(".checklist-items")
      .getByText("Please choose the smaller bunches", { exact: true })
      .waitFor({ timeout: 12000 });
    await shot(page, "92-live-groceries");
    await page
      .getByRole("button", { name: "Open item: Bananas", exact: true })
      .click();
    await page
      .getByLabel("Notes", { exact: true })
      .fill("Keep my unfinished note");
    await s.send("task.update", s.bananas.id, { quantity: "8" }, 2, s.sophia);
    await page
      .locator("#sync")
      .getByText("New changes waiting · finish editing to refresh", {
        exact: true,
      })
      .waitFor({ timeout: 12000 });
    assert.equal(
      await page.getByLabel("Notes", { exact: true }).inputValue(),
      "Keep my unfinished note",
    );
    await page.getByRole("button", { name: "Cancel", exact: true }).click();
    await page
      .locator("#sync")
      .getByText("Up to date · checked just now", { exact: true })
      .waitFor({ timeout: 12000 });
    assert.deepEqual(s.errors, []);
  } finally {
    await s.close();
  }
});
