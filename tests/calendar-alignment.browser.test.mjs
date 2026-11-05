import { test } from "node:test";
import assert from "node:assert/strict";
import { chromium } from "playwright";
import { mkdir } from "node:fs/promises";
import { randomUUID } from "node:crypto";
import { fixture, password } from "./support.mjs";

test("Sunday-first headings, all-day cells and hours share the scrollbar gutter", async () => {
  const f = await fixture();
  let browser;
  try {
    const token = await f.login(),
      snapshot = (await f.request("/api/snapshot", { token })).data;
    for (const [title, at, end] of [
      ["Household check-in", "2026-11-03T15:00:00Z", "2026-11-03T16:00:00Z"],
      ["Kitchen delivery", "2026-11-05T20:00:00Z", null],
      ["Dinner prep", "2026-11-04T22:30:00Z", "2026-11-04T23:15:00Z"],
    ]) {
      const response = await f.request("/api/commands", {
        token,
        body: {
          operation_id: randomUUID(),
          epoch: snapshot.epoch,
          generation: snapshot.generation,
          action: "event.create",
          target: randomUUID(),
          expected_revision: null,
          data: {
            title,
            all_day: false,
            starts_at: Date.parse(at) / 1000,
            ends_at: end ? Date.parse(end) / 1000 : null,
            category: "home",
          },
        },
      });
      assert.equal(response.status, 200);
    }
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
    });
    await page.clock.setFixedTime(new Date("2026-11-05T14:00:00Z"));
    await page.goto(f.admin);
    await page.getByLabel("User name", { exact: true }).fill("owner");
    await page.getByLabel("Password", { exact: true }).fill(password);
    await page.getByRole("button", { name: "Sign in", exact: true }).click();
    await page
      .getByRole("heading", { name: "Calendar", exact: true })
      .waitFor();
    await page.getByRole("button", { name: "Week", exact: true }).click();
    assert.match(
      await page.locator(".calendar-day-headers").innerText(),
      /Sun\s+1\s+Mon\s+2/,
    );
    for (const width of [12, 28]) {
      await page.evaluate((width) => {
        const sheet = document.styleSheets[0];
        sheet.insertRule(
          `.calendar-scroller{scrollbar-color:auto !important;scrollbar-width:auto !important}`,
          sheet.cssRules.length,
        );
        sheet.insertRule(
          `.calendar-scroller::-webkit-scrollbar{width:${width}px !important}`,
          sheet.cssRules.length,
        );
      }, width);
      await page.locator(".calendar-scroller").evaluate((node) => {
        node.style.overflow = "hidden";
        void node.offsetWidth;
        node.style.overflow = "auto";
      });
      for (const scrollTop of [0, 430, 850]) {
        await page
          .locator(".calendar-scroller")
          .evaluate((node, top) => (node.scrollTop = top), scrollTop);
        await page.evaluate(() => new Promise(requestAnimationFrame));
        const bounds = await page.evaluate(() => {
          const rect = (node) => ({
            x: node.getBoundingClientRect().x,
            width: node.getBoundingClientRect().width,
          });
          return {
            heads: [...document.querySelector(".calendar-day-headers").children]
              .slice(1)
              .map(rect),
            floating: [
              ...document.querySelector(".calendar-floating-row").children,
            ]
              .slice(1)
              .map(rect),
            columns: [
              ...document.querySelectorAll(".calendar-time-column"),
            ].map(rect),
            gutter: ((n) => n.offsetWidth - n.clientWidth)(
              document.querySelector(".calendar-scroller"),
            ),
          };
        });
        assert.ok(
          bounds.gutter >= width,
          `wide scrollbar is present (${bounds.gutter})`,
        );
        for (let i = 0; i < 7; i++)
          for (const cell of [bounds.heads[i], bounds.floating[i]]) {
            assert.ok(
              Math.abs(cell.x - bounds.columns[i].x) < 1,
              `day ${i}, scrollbar ${width}, scroll ${scrollTop}: x differs`,
            );
            assert.ok(
              Math.abs(cell.width - bounds.columns[i].width) < 1,
              `day ${i}: width differs`,
            );
          }
      }
    }
    await page
      .locator(".calendar-scroller")
      .evaluate((node) => (node.scrollTop = 440));
    await mkdir("docs/screenshots/web", { recursive: true });
    await page.screenshot({
      path: "docs/screenshots/web/50-sunday-week-aligned.png",
      fullPage: true,
    });
    await page.getByRole("button", { name: "Month", exact: true }).click();
    assert.match(
      await page.locator(".calendar-weekday").first().innerText(),
      /^Sun/,
    );
    await page.screenshot({
      path: "docs/screenshots/web/51-sunday-month.png",
      fullPage: true,
    });
  } finally {
    await browser?.close();
    await f.close();
  }
});
