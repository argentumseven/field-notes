import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdir } from "node:fs/promises";
import { chromium } from "playwright";
import { fixture, password } from "./support.mjs";

async function scene() {
  const f = await fixture();
  let browser;
  try {
    browser = await chromium.launch({
      executablePath: process.env.CHROMIUM_PATH || "/usr/bin/chromium",
      headless: true,
      ignoreDefaultArgs: ["--hide-scrollbars"],
      args: ["--no-sandbox"],
    });
    const page = await browser.newPage({
      viewport: { width: 1280, height: 800 },
      locale: "en-US",
      timezoneId: "America/New_York",
      colorScheme: "dark",
    });
    await page.goto(f.admin);
    await page.getByLabel("User name", { exact: true }).fill("owner");
    await page.getByLabel("Password", { exact: true }).fill(password);
    await page.getByRole("button", { name: "Sign in", exact: true }).click();
    await page
      .getByRole("heading", { name: "Calendar", exact: true })
      .waitFor();
    return {
      page,
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

async function assertFlow(page) {
  const groups = await page
    .locator(".sidebar .nav-group")
    .evaluateAll((nodes) =>
      nodes.map((node) => {
        const bounds = node.getBoundingClientRect();
        return {
          name: node.querySelector("summary").textContent,
          top: bounds.top,
          bottom: bounds.bottom,
          contentBottom: (node.open
            ? node.lastElementChild
            : node.firstElementChild
          ).getBoundingClientRect().bottom,
        };
      }),
    );
  for (let i = 0; i < groups.length; i++) {
    assert.ok(
      groups[i].contentBottom <= groups[i].bottom + 1,
      `${groups[i].name} contains all of its expanded content`,
    );
    if (i)
      assert.ok(
        groups[i].top >= groups[i - 1].bottom,
        `${groups[i].name} follows the previous expanded section`,
      );
  }
  const footerTop = await page
    .locator(".sidebar-bottom")
    .evaluate((node) => node.getBoundingClientRect().top);
  assert.ok(
    footerTop >= groups[2].bottom,
    "management and preferences flow below Task views",
  );
}

async function assertReachable(locator) {
  await locator.scrollIntoViewIfNeeded();
  assert.ok(
    await locator.evaluate((node) => {
      const r = node.getBoundingClientRect();
      const hit = document.elementFromPoint(
        r.x + r.width / 2,
        r.y + r.height / 2,
      );
      return hit === node || node.contains(hit);
    }),
    `${await locator.innerText()} can be reached without another section covering it`,
  );
}

test("expanded sidebar sections keep their height and scroll together with management", async () => {
  const s = await scene();
  try {
    const { page } = s;
    for (const viewport of [
      { width: 1440, height: 1080 },
      { width: 1280, height: 800 },
      { width: 1024, height: 600 },
      { width: 800, height: 420 },
    ]) {
      await page.setViewportSize(viewport);
      await page.locator(".sidebar .nav-group").evaluateAll((nodes) => {
        for (const node of nodes) node.open = false;
      });
      const collapsedWidth = await page
        .locator(".sidebar-scroll")
        .evaluate((node) => node.getBoundingClientRect().width);
      await page.locator(".sidebar .nav-group").evaluateAll((nodes) => {
        for (const node of nodes) node.open = true;
      });
      assert.equal(
        await page
          .locator(".sidebar-scroll")
          .evaluate((node) => node.getBoundingClientRect().width),
        collapsedWidth,
        "the scrollbar does not change the navigation width on expansion",
      );
      await assertFlow(page);
      const scroll = await page.locator(".sidebar").evaluate((node) => ({
        content: node.scrollHeight,
        viewport: node.clientHeight,
      }));
      assert.ok(
        scroll.content > scroll.viewport,
        "the whole sidebar can scroll",
      );
      const windowTop = await page.evaluate(() => window.scrollY);
      for (const name of [
        "Calendar",
        "History",
        "Completed",
        "Management",
        "Change theme",
        "Sign out",
      ])
        await assertReachable(
          page.locator(".sidebar").getByRole("button", { name, exact: true }),
        );
      assert.equal(
        await page.evaluate(() => window.scrollY),
        windowTop,
        "scrolling navigation does not move the planner",
      );
    }
    await page.setViewportSize({ width: 1280, height: 800 });
    const taskViews = page.locator(".sidebar-scroll > details").filter({
      has: page.getByText("Task views", { exact: true }),
    });
    const summary = taskViews.locator("summary");
    const before = await taskViews.evaluate(
      (node) => node.getBoundingClientRect().height,
    );
    await summary.focus();
    await page.keyboard.press("Space");
    assert.equal(await taskViews.getAttribute("open"), null);
    const collapsed = await taskViews.evaluate(
      (node) => node.getBoundingClientRect().height,
    );
    assert.ok(before - collapsed > 300, "collapsing releases the rows' space");
    // Document coordinates avoid confusing normal scroll anchoring with reflow.
    const collapsedOffset = await page
      .locator(".sidebar-bottom")
      .evaluate((node) => node.offsetTop);
    await page.keyboard.press("Space");
    assert.notEqual(await taskViews.getAttribute("open"), null);
    assert.ok(
      (await page
        .locator(".sidebar-bottom")
        .evaluate((node) => node.offsetTop)) >
        collapsedOffset + 300,
      "expanding pushes the following controls down",
    );
    await page.keyboard.press("Tab");
    assert.equal(await page.locator(":focus").innerText(), "Inbox");
    await assertReachable(page.locator(":focus"));
    const completed = page.getByRole("button", {
      name: "Completed",
      exact: true,
    });
    await assertReachable(completed);
    const scrollTop = await page
      .locator(".sidebar")
      .evaluate((node) => node.scrollTop);
    await completed.click();
    await page
      .getByRole("heading", { name: "Completed", exact: true })
      .waitFor();
    await assertFlow(page);
    for (let pass = 0; pass < 2; pass++) {
      assert.equal(
        await page.locator(".sidebar").evaluate((node) => node.scrollTop),
        scrollTop,
        "navigation and refresh preserve the sidebar's scroll position",
      );
      assert.notEqual(
        await page.locator(".preferences-nav").getAttribute("open"),
        null,
        "refresh keeps Preferences expanded",
      );
      if (!pass) {
        const previousSidebar = await page.locator(".sidebar").elementHandle();
        await page
          .getByRole("button", { name: "Refresh now", exact: true })
          .click();
        await page.waitForFunction(
          (node) => !node.isConnected,
          previousSidebar,
        );
        await previousSidebar.dispose();
      }
    }
    if (process.env.FIELD_NOTES_CAPTURE_SIDEBAR === "synthetic-only") {
      await page.setViewportSize({ width: 1440, height: 1080 });
      await page.locator(".sidebar .nav-group").evaluateAll((nodes) => {
        for (const node of nodes) node.open = true;
      });
      await page.locator(".sidebar").evaluate((node) => (node.scrollTop = 0));
      await mkdir("docs/screenshots/web", { recursive: true });
      await page.screenshot({
        path: "docs/screenshots/web/64-sidebar-expanded.png",
      });
      await page
        .locator(".sidebar")
        .evaluate((node) => (node.scrollTop = node.scrollHeight));
      await page.screenshot({
        path: "docs/screenshots/web/65-sidebar-scrolled.png",
      });
    }
  } finally {
    await s.close();
  }
});

test("narrow web navigation grows in normal page flow without overlapping sections", async () => {
  const s = await scene();
  try {
    const { page } = s;
    for (const width of [390, 600]) {
      await page.setViewportSize({ width, height: 700 });
      await page.locator(".sidebar .nav-group").evaluateAll((nodes) => {
        for (const node of nodes) node.open = true;
      });
      await assertFlow(page);
      for (const name of [
        "Calendar",
        "Completed",
        "Management",
        "Change theme",
      ])
        await assertReachable(
          page.locator(".sidebar").getByRole("button", { name, exact: true }),
        );
      assert.ok(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= innerWidth,
        ),
        "expanded navigation stays within the screen width",
      );
    }
  } finally {
    await s.close();
  }
});
