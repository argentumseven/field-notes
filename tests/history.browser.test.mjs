import { test } from "node:test";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { mkdir } from "node:fs/promises";
import { chromium } from "playwright";
import { password } from "./support.mjs";
import { historyFixture } from "./history-fixture.mjs";

async function scene() {
  const { f, owner, alex, sophia, send, list, bananas, removed } =
    await historyFixture();
  try {
    const browser = await chromium.launch({
      executablePath: process.env.CHROMIUM_PATH || "/usr/bin/chromium",
      headless: true,
      args: ["--no-sandbox"],
    });
    const page = await browser.newPage({
      viewport: { width: 1440, height: 1080 },
      locale: "en-US",
      timezoneId: "America/New_York",
      colorScheme: "dark",
    });
    const errors = [];
    page.on("pageerror", (e) => errors.push(e.message));
    await page.goto(f.admin);
    await page.getByLabel("User name", { exact: true }).fill("Alex");
    await page.getByLabel("Password", { exact: true }).fill(password);
    await page.getByRole("button", { name: "Sign in", exact: true }).click();
    await page
      .getByRole("heading", { name: "Calendar", exact: true })
      .waitFor();
    await page.getByRole("button", { name: "History", exact: true }).click();
    await page.locator(".history-entry").first().waitFor();
    return {
      f,
      browser,
      page,
      errors,
      send,
      alex,
      sophia,
      owner,
      list,
      bananas,
      removed,
      async close() {
        await browser.close();
        await f.close();
      },
    };
  } catch (e) {
    await f.close();
    throw e;
  }
}
async function shot(page, name) {
  if (process.env.FIELD_NOTES_CAPTURE_HISTORY !== "synthetic-only") return;
  await mkdir("docs/screenshots/web", { recursive: true });
  await page.screenshot({
    path: "docs/screenshots/web/" + name + ".png",
    fullPage: true,
  });
}
test("web history shows people, added/removed items, real field differences and useful filters", async () => {
  const s = await scene();
  try {
    const { page } = s;
    assert.equal(await page.locator(".history-entry").count(), 3);
    const latest = page.locator(".history-entry").first();
    assert.match(
      await latest.innerText(),
      /Changed[\s\S]*Grocery item[\s\S]*Bananas[\s\S]*Costco Shopping List[\s\S]*Alex/,
    );
    await shot(page, "56-history");
    await page.getByRole("button", { name: "Removed", exact: true }).click();
    await page
      .getByRole("heading", { name: "Tomatoes", exact: true })
      .waitFor();
    assert.equal(await page.locator(".history-entry").count(), 1);
    await page.getByRole("button", { name: "Changed", exact: true }).click();
    await page.getByRole("heading", { name: "Bananas", exact: true }).waitFor();
    await page.locator(".history-changes > summary").click();
    const notes = page.locator(".history-field").filter({
      has: page.getByRole("heading", { name: "Notes", exact: true }),
    });
    assert.match(
      await notes.innerText(),
      /Previous[\s\S]*Two for breakfast[\s\S]*Now[\s\S]*Four for the breakfast smoothies/,
    );
    assert.match(
      await latest.innerText(),
      /Quantity[\s\S]*Previous[\s\S]*2[\s\S]*Now[\s\S]*4/,
    );
    await shot(page, "57-history-details");
    await page
      .getByRole("button", { name: "All changes", exact: true })
      .click();
    await page
      .getByLabel("Search titles, people and change details")
      .fill("Two for breakfast");
    await page.waitForFunction(
      () => document.querySelectorAll(".history-entry").length === 1,
    );
    await page.locator(".history-filter-disclosure > summary").click();
    const sophiaId = (
      await s.f.request("/api/people", { token: s.alex })
    ).data.people.find((p) => p.name === "Sophia").id;
    await page.getByLabel("Person", { exact: true }).selectOption(sophiaId);
    await page.waitForFunction(
      () => document.querySelectorAll(".history-entry").length === 1,
    );
    assert.match(await page.locator(".history-byline").innerText(), /Sophia/);
    await page
      .getByRole("button", { name: "Clear filters", exact: true })
      .click();
    await page.waitForFunction(
      () => document.querySelectorAll(".history-entry").length === 3,
    );
    await page.evaluate(
      () => (document.documentElement.dataset.theme = "light"),
    );
    await shot(page, "58-history-light");
    // History can arrive before the ordinary snapshot. A later passive sync
    // must make the new item openable without resetting the history filters.
    await s.send(s.sophia, "note.create", randomUUID(), {
      title: "Weekend route ideas",
      body: "Choose the woodland loop",
    });
    await page
      .getByRole("button", { name: "Refresh history", exact: true })
      .click();
    await page
      .getByRole("heading", { name: "Weekend route ideas", exact: true })
      .waitFor();
    const newItem = page
      .locator(".history-entry")
      .filter({
        has: page.getByRole("heading", {
          name: "Weekend route ideas",
          exact: true,
        }),
      });
    await newItem
      .getByRole("button", { name: "Open item", exact: true })
      .waitFor({ state: "visible", timeout: 20_000 });
    assert.deepEqual(s.errors, []);
  } finally {
    await s.close();
  }
});
test("web history ignores stale filter responses, keeps focus and retains loaded rows on a connection failure", async () => {
  const s = await scene();
  try {
    const { page } = s;
    let release;
    const stalled = new Promise((resolve) => (release = resolve));
    let seen;
    const requested = new Promise((resolve) => (seen = resolve));
    await page.route("**/api/history", async (route) => {
      const body = route.request().postDataJSON();
      if (body.query === "slow") {
        seen();
        await stalled;
        await route.fulfill({
          status: 401,
          contentType: "application/json",
          body: '{"error":"Old response"}',
        });
      } else await route.continue();
    });
    const search = page.getByLabel("Search titles, people and change details");
    await search.fill("slow");
    await requested;
    await search.fill("Bananas");
    await page.waitForFunction(
      () => document.querySelectorAll(".history-entry").length === 1,
    );
    release();
    await page
      .getByRole("button", { name: "Refresh history", exact: true })
      .waitFor({ state: "visible" });
    assert.equal(await search.inputValue(), "Bananas");
    assert.equal(
      await search.evaluate((n) => document.activeElement === n),
      true,
    );
    await page
      .getByRole("button", { name: "Refresh now", exact: true })
      .click();
    await page.waitForFunction(
      () => document.querySelectorAll(".history-entry").length === 1,
    );
    assert.equal(await search.inputValue(), "Bananas");
    await page.unroute("**/api/history");
    await page.route("**/api/history", (route) => route.abort("failed"));
    await page
      .getByRole("button", { name: "Refresh history", exact: true })
      .click();
    await page
      .getByRole("alert")
      .filter({ hasText: "Server unavailable" })
      .waitFor();
    assert.equal(await page.locator(".history-entry").count(), 1);
    assert.equal(await search.inputValue(), "Bananas");
    assert.deepEqual(s.errors, []);
  } finally {
    await s.close();
  }
});
test("web history escapes supplied text and removes prior content from the view after a workspace clear", async () => {
  const s = await scene();
  try {
    const { page } = s;
    const title = '<img src=x onerror="window.auditXss=true">';
    await s.send(s.sophia, "note.create", randomUUID(), {
      title,
      body: "A literal example, not markup",
    });
    await page
      .getByRole("button", { name: "Refresh history", exact: true })
      .click();
    await page.getByRole("heading", { name: title, exact: true }).waitFor();
    assert.equal(await page.evaluate(() => !!window.auditXss), false);
    await s.f.request("/api/reauth", { token: s.owner, body: { password } });
    const snapshot = (await s.f.request("/api/snapshot", { token: s.owner }))
      .data;
    assert.equal(
      (
        await s.f.request("/api/admin", {
          token: s.owner,
          body: {
            action: "workspace.clear",
            confirmation: "CLEAR WORKSPACE",
            generation: snapshot.generation,
          },
        })
      ).status,
      200,
    );
    await page
      .getByRole("button", { name: "Refresh now", exact: true })
      .click();
    await page
      .getByRole("heading", { name: "Workspace content cleared", exact: true })
      .waitFor();
    assert.equal(await page.locator(".history-entry").count(), 1);
    assert.equal(
      (await page.locator(".history-view").innerText()).includes("Bananas"),
      false,
    );
    assert.deepEqual(s.errors, []);
  } finally {
    await s.close();
  }
});
