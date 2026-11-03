import { test } from "node:test";
import assert from "node:assert/strict";
import { chromium } from "playwright";
import { mkdir } from "node:fs/promises";
import { fixture, password } from "./support.mjs";
test("an accepted password without a retained session shows an error and keeps the username", async () => {
  const f = await fixture({ enrollment: true });
  let browser;
  try {
    browser = await chromium.launch({
      executablePath: process.env.CHROMIUM_PATH || "/usr/bin/chromium",
      headless: true,
      args: ["--no-sandbox"],
    });
    const page = await browser.newPage();
    await page.route("**/api/session", (route) =>
      route.fulfill({
        status: 401,
        contentType: "application/json",
        body: JSON.stringify({ error: "Sign in required" }),
      }),
    );
    await page.goto(f.admin);
    await page.getByLabel("User name", { exact: true }).fill("owner");
    await page.getByLabel("Password", { exact: true }).fill(password);
    await page.getByRole("button", { name: "Sign in", exact: true }).click();
    await page
      .getByRole("alert")
      .filter({
        hasText:
          "Your password was accepted, but the browser session could not be confirmed.",
      })
      .waitFor();
    assert.equal(
      await page.getByLabel("User name", { exact: true }).inputValue(),
      "owner",
    );
    assert.equal(
      await page.getByLabel("Password", { exact: true }).inputValue(),
      "",
      "accepted secret is cleared",
    );
    await page.unroute("**/api/session");
    await page.getByLabel("Password", { exact: true }).fill(password);
    await page.getByRole("button", { name: "Sign in", exact: true }).click();
    await page
      .getByRole("heading", { name: "Calendar", exact: true })
      .waitFor();
  } finally {
    await browser?.close();
    await f.close();
  }
});
test("APK action stays visible on sign-in when metadata fails or the bundle is absent", async () => {
  const f = await fixture({ downloads: null });
  let browser;
  try {
    browser = await chromium.launch({
      executablePath: process.env.CHROMIUM_PATH || "/usr/bin/chromium",
      headless: true,
      args: ["--no-sandbox"],
    });
    const page = await browser.newPage();
    await page.route("**/downloads/android", (route) =>
      route.fulfill({ status: 503, body: "Unavailable" }),
    );
    await page.goto(f.admin);
    await page
      .getByText(
        "Download details could not be loaded. Retry this page or use the direct APK button.",
      )
      .waitFor();
    const link = page.getByRole("link", { name: "Download APK", exact: true });
    assert.equal(await link.getAttribute("href"), "/downloads/field-notes.apk");
    assert(await link.isVisible());
    await page.unroute("**/downloads/android");
    await page.reload();
    await page
      .getByText(
        "The server's APK is missing or failed verification. Ask the administrator to restore the matching app bundle.",
      )
      .waitFor();
    assert(await link.isVisible());
  } finally {
    await browser?.close();
    await f.close();
  }
});
test("web workflow: sign in, project, task details, comment, board, admin and mobile layout", async () => {
  const f = await fixture({ enrollment: true });
  let browser;
  const setupOwner = await f.login();
  await f.request("/api/reauth", { token: setupOwner, body: { password } });
  await f.request("/api/admin", {
    token: setupOwner,
    body: { action: "user.create", name: "Sam", role: "member", password },
  });
  try {
    browser = await chromium.launch({
      executablePath: process.env.CHROMIUM_PATH || "/usr/bin/chromium",
      headless: true,
      args: ["--no-sandbox"],
    });
    const page = await browser.newPage({
      viewport: { width: 1440, height: 1050 },
      colorScheme: "dark",
    });
    const errors = [];
    const external = [];
    page.on("pageerror", (e) => errors.push(e.message));
    page.on("request", (r) => {
      if (!r.url().startsWith(f.admin) && !r.url().startsWith("blob:"))
        external.push(r.url());
    });
    await page.goto(f.admin);
    await page.getByLabel("User name", { exact: true }).fill("owner");
    await page.getByLabel("Password", { exact: true }).fill(password);
    await page.getByRole("button", { name: "Sign in", exact: true }).click();
    await page.getByRole("button", { name: "Projects", exact: true }).click();
    await page
      .getByRole("button", { name: "New project", exact: true })
      .click();
    await page
      .getByLabel("Project name", { exact: true })
      .fill("Home & garden");
    await page.locator("#dialog-submit").click();
    await page.getByRole("button", { name: "Focus", exact: true }).click();
    const createTask = async (title) => {
      await page.getByRole("button", { name: "New task", exact: true }).click();
      await page.getByLabel("Task title", { exact: true }).fill(title);
      await page
        .getByRole("button", { name: "Create task", exact: true })
        .click();
      await page
        .getByRole("button", { name: "Open task: " + title, exact: true })
        .waitFor();
    };
    await createTask("Plan the kitchen shelves");
    await page
      .getByRole("button", {
        name: "Open task: Plan the kitchen shelves",
        exact: true,
      })
      .click();
    await page
      .locator(".detail")
      .getByLabel("Notes", { exact: true })
      .fill("Measure the alcove before buying timber.");
    await page
      .locator(".detail")
      .getByLabel("Priority", { exact: true })
      .selectOption("1");
    await page
      .locator(".detail")
      .getByLabel("Due date", { exact: true })
      .fill("2026-11-04");
    await page
      .getByRole("button", { name: "Clear due date", exact: true })
      .click();
    await page
      .getByRole("button", { name: "Save details", exact: true })
      .click();
    await page.getByText("Details saved.", { exact: true }).waitFor();
    await page
      .getByLabel("Add a comment", { exact: true })
      .fill("I can measure this on Saturday.");
    await page
      .getByRole("button", { name: "Post comment", exact: true })
      .click();
    await page
      .getByText("I can measure this on Saturday.", { exact: true })
      .waitFor();
    await page.getByRole("button", { name: "Start task", exact: true }).click();
    await page
      .getByText("Currently claimed by owner", { exact: true })
      .waitFor();
    await page.getByRole("button", { name: "Close", exact: true }).click();
    for (const title of [
      "Order herb seedlings",
      "Book a boiler service",
      "Choose a reading lamp",
    ]) {
      await createTask(title);
    }
    await mkdir("dist/screenshots", { recursive: true });
    await page.screenshot({
      path: "dist/screenshots/workspace.png",
      fullPage: true,
    });
    await page.getByRole("button", { name: "Tasks", exact: true }).click();
    await page.getByRole("button", { name: "Board", exact: true }).click();
    await page
      .getByRole("heading", { name: "In progress", exact: true })
      .waitFor();
    await page.getByRole("button", { name: "Lists", exact: true }).click();
    await page
      .locator("main")
      .getByRole("button", { name: "New list", exact: true })
      .click();
    await page.getByLabel("List name", { exact: true }).fill("Groceries");
    await page.locator("#dialog-submit").click();
    await page
      .getByRole("button", { name: /Groceries.*unchecked items/ })
      .waitFor();
    await page.getByRole("button", { name: "Notes", exact: true }).click();
    await page
      .locator("main")
      .getByRole("button", { name: "New note", exact: true })
      .click();
    await page
      .getByLabel("Note title", { exact: true })
      .fill("Household reference");
    await page
      .locator("#dialog-body")
      .getByLabel("Content", { exact: true })
      .fill("Useful information without a task.");
    await page.locator("#dialog-submit").click();
    await page
      .locator(".note-row")
      .filter({ hasText: "Household reference" })
      .click();
    await page
      .getByRole("button", { name: "Save note", exact: true })
      .waitFor();
    await page
      .locator(".detail")
      .getByLabel("Content", { exact: true })
      .fill("Edited reference, kept separately.");
    await page.getByRole("button", { name: "Save note", exact: true }).click();
    await page.getByText("Note saved.", { exact: true }).waitFor();
    await page.getByRole("button", { name: "Close", exact: true }).click();
    await page.getByText("Task views", { exact: true }).click();
    await page.getByRole("button", { name: "Inbox", exact: true }).click();
    await page
      .getByRole("button", {
        name: "Open task: Plan the kitchen shelves",
        exact: true,
      })
      .click();
    await page
      .getByLabel("Keep in", { exact: true })
      .selectOption({ label: "Groceries · list" });
    await page
      .getByRole("button", { name: "Save details", exact: true })
      .click();
    await page.getByRole("button", { name: "Close", exact: true }).click();
    await page
      .getByRole("button", {
        name: "Open task: Plan the kitchen shelves",
        exact: true,
      })
      .waitFor({ state: "hidden" });
    await page.getByRole("button", { name: "Tasks", exact: true }).click();
    await page.getByRole("button", { name: "List", exact: true }).click();
    await page
      .getByRole("button", {
        name: "Open task: Choose a reading lamp",
        exact: true,
      })
      .click();
    await page
      .getByLabel("Assigned to", { exact: true })
      .selectOption({ label: "Sam" });
    await page
      .getByRole("button", { name: "Save details", exact: true })
      .click();
    await page.getByText("Details saved.", { exact: true }).waitFor();
    await page
      .getByText("Assigned to Sam. Reassign it to yourself before starting.", {
        exact: true,
      })
      .waitFor();
    await page
      .getByRole("button", { name: "Start task", exact: true })
      .waitFor({ state: "hidden" });
    await page.getByRole("button", { name: "Close", exact: true }).click();
    await page
      .getByRole("button", { name: "Assignments", exact: true })
      .click();
    await page
      .locator(".assignment-group")
      .filter({ has: page.getByRole("heading", { name: "Sam", exact: true }) })
      .getByRole("button", {
        name: "Open task: Choose a reading lamp",
        exact: true,
      })
      .waitFor();
    await page.getByRole("button", { name: "My tasks", exact: true }).click();
    await page
      .getByRole("button", {
        name: "Open task: Plan the kitchen shelves",
        exact: true,
      })
      .waitFor();
    await page
      .getByRole("button", {
        name: "Open task: Choose a reading lamp",
        exact: true,
      })
      .waitFor({ state: "hidden" });
    await page.getByText("Preferences", { exact: true }).click();
    const baselines = await page.evaluate(() =>
      ["Tasks", "Management", "Change theme"].map((label) => {
        const b = [...document.querySelectorAll(".sidebar button")].find(
          (n) => n.textContent === label,
        );
        const s = getComputedStyle(b);
        return (
          b.getBoundingClientRect().left +
          parseFloat(s.paddingLeft) +
          parseFloat(s.borderLeftWidth)
        );
      }),
    );
    assert(
      Math.max(...baselines) - Math.min(...baselines) < 1,
      "sidebar destination labels align",
    );
    await page.getByRole("button", { name: "Calendar", exact: true }).click();
    await page.getByRole("button", { name: "New event", exact: true }).click();
    await page
      .getByLabel("Event title", { exact: true })
      .fill("Household planning");
    await page.getByLabel("All day", { exact: true }).check();
    await page.getByRole("button", { name: "Save event", exact: true }).click();
    await page
      .getByRole("button", { name: "Household planning", exact: true })
      .waitFor();
    await page.getByRole("button", { name: "Management" }).click();
    await page.getByRole("heading", { name: "People", exact: true }).waitFor();
    await page
      .getByRole("heading", { name: "Devices & sessions", exact: true })
      .waitFor();
    await page.screenshot({
      path: "dist/screenshots/admin.png",
      fullPage: true,
    });
    await page.getByRole("button", { name: /Tasks/ }).click();
    await page.setViewportSize({ width: 390, height: 844 });
    await page.screenshot({
      path: "dist/screenshots/mobile.png",
      fullPage: true,
    });
    await page
      .getByLabel("Task view", { exact: true })
      .selectOption("unassigned");
    await page
      .getByRole("heading", { name: "Unassigned", exact: true })
      .waitFor();
    assert.equal(
      await page.evaluate(
        () => document.documentElement.scrollWidth > innerWidth,
      ),
      false,
      "mobile layout should not overflow",
    );
    assert.deepEqual(errors, []);
    assert.deepEqual(external, []);
  } finally {
    await browser?.close();
    await f.close();
  }
});

test("calendar is home and ten AM saves and reopens as ten AM in the local zone", async () => {
  const f = await fixture({ enrollment: true });
  let browser;
  try {
    browser = await chromium.launch({
      executablePath: process.env.CHROMIUM_PATH || "/usr/bin/chromium",
      headless: true,
      args: ["--no-sandbox"],
    });
    const page = await browser.newPage({
      viewport: { width: 1440, height: 1050 },
      locale: "en-US",
      timezoneId: "America/New_York",
    });
    await page.goto(f.admin);
    await page.getByLabel("User name", { exact: true }).fill("owner");
    await page.getByLabel("Password", { exact: true }).fill(password);
    await page.getByRole("button", { name: "Sign in", exact: true }).click();
    await page
      .getByRole("heading", { name: "Calendar", exact: true })
      .waitFor();
    await page.getByRole("button", { name: "Month", exact: true }).click();
    await page.getByRole("button", { name: "New event", exact: true }).click();
    await page
      .getByLabel("Event title", { exact: true })
      .fill("Morning check-in");
    await page.getByLabel("All day", { exact: true }).uncheck();
    const day = await page.getByLabel("Starts", { exact: true }).inputValue();
    await page.getByLabel("Start time", { exact: true }).fill("10:00");
    await page.getByLabel("Set an end time", { exact: true }).check();
    await page.getByLabel("End time", { exact: true }).fill("11:00");
    const issued = page.waitForResponse(
      (r) => r.url().endsWith("/api/commands") && r.status() === 200,
    );
    await page.getByRole("button", { name: "Save event", exact: true }).click();
    const saved = (await (await issued).json()).record;
    assert.equal(
      await page.evaluate(
        (value) => new Date(value * 1000).getHours(),
        saved.data.starts_at,
      ),
      10,
    );
    assert.equal(
      await page.evaluate(
        (value) => FieldPlanning.localDay(new Date(value * 1000)),
        saved.data.starts_at,
      ),
      day,
    );
    await page
      .locator(".agenda-row")
      .filter({ hasText: "Morning check-in" })
      .click();
    assert.equal(
      await page.getByLabel("Start time", { exact: true }).inputValue(),
      "10:00",
    );
    assert.equal(
      await page.getByLabel("End time", { exact: true }).inputValue(),
      "11:00",
    );
    assert.equal(
      await page
        .locator("#dialog")
        .innerText()
        .then((s) => /ISO|offset|exclusive/.test(s)),
      false,
    );
    await mkdir("docs/screenshots/web", { recursive: true });
    await page.screenshot({
      path: "dist/screenshots/local-event-time.png",
      fullPage: true,
    });
    await page.getByLabel("All day", { exact: true }).check();
    assert.equal(
      await page.getByLabel("Start time", { exact: true }).isVisible(),
      false,
    );
    await page.getByLabel("First day", { exact: true }).fill(day);
    await page.getByLabel("Last day · included", { exact: true }).fill(day);
    await page.getByRole("button", { name: "Save event", exact: true }).click();
    await page
      .locator(".agenda-row")
      .filter({ hasText: "Morning check-in" })
      .getByText("All day", { exact: true })
      .waitFor();
    await page
      .locator(".agenda-row")
      .filter({ hasText: "Morning check-in" })
      .click();
    assert.equal(
      await page
        .getByLabel("For deletion, type DELETE", { exact: true })
        .isVisible(),
      false,
    );
    await page
      .getByRole("button", { name: "Delete event…", exact: true })
      .click();
    await page
      .getByLabel("For deletion, type DELETE", { exact: true })
      .fill("DELETE");
    await page
      .getByRole("button", { name: "Delete event", exact: true })
      .click();
    await page
      .locator(".agenda-row")
      .filter({ hasText: "Morning check-in" })
      .waitFor({ state: "hidden" });
  } finally {
    await browser?.close();
    await f.close();
  }
});
