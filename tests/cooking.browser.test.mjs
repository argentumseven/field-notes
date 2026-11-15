import { test } from "node:test";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { chromium } from "playwright";
import { fixture, password } from "./support.mjs";
test("weekly meal overview and free-form dependency editor fit narrow screens; event actions have space; idle login locks", async () => {
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
    assert.equal(
      await page
        .getByRole("button", { name: "Week", exact: true })
        .getAttribute("aria-pressed"),
      "true",
    );
    await page.getByRole("button", { name: "Plan food", exact: true }).click();
    await page.getByLabel("Event title", { exact: true }).fill("Birthday cake");
    await page.getByRole("button", { name: "Save event", exact: true }).click();
    await page.locator("#dialog").waitFor({ state: "hidden" });
    await page.getByRole("button", { name: /Birthday cake/ }).click();
    await page
      .getByRole("button", { name: "Edit preparation", exact: true })
      .click();
    await page.getByRole("button", { name: "Add item", exact: true }).click();
    await page.getByLabel("Item name", { exact: true }).fill("Anything I want");
    await page.getByRole("button", { name: "Add phase", exact: true }).click();
    await page.getByLabel("Phase name", { exact: true }).fill("Slowly prepare");
    await page.getByLabel("Duration (minutes)", { exact: true }).fill("600");
    await page.getByRole("button", { name: "Add phase", exact: true }).click();
    await page
      .getByLabel("Phase name", { exact: true })
      .nth(1)
      .fill("Finish up");
    await page
      .getByLabel("Duration (minutes)", { exact: true })
      .nth(1)
      .fill("30");
    assert(
      await page
        .locator("#dialog-body")
        .evaluate((n) => n.scrollWidth <= n.clientWidth + 1),
    );
    await page
      .locator("#dialog")
      .screenshot({ path: "work/0120-cooking-narrow.png" });
    await page
      .getByRole("button", { name: "Save preparation", exact: true })
      .click();
    await page
      .locator("#dialog")
      .waitFor({ state: "hidden", timeout: 5000 })
      .catch(async (e) => {
        throw new Error(
          (await page.locator("#dialog-error").textContent()) +
            " / " +
            JSON.stringify(
              await page.locator("#dialog input:invalid").evaluateAll((ns) =>
                ns.map((n) => ({
                  label: n.previousElementSibling?.textContent,
                  value: n.value,
                  why: n.validationMessage,
                })),
              ),
            ),
        );
      });
    await page
      .getByRole("heading", { name: "Slowly prepare", exact: true })
      .waitFor();
    assert(await page.getByText("Get started", { exact: false }).count());
    await page
      .getByRole("button", { name: "Edit schedule", exact: true })
      .click();
    await page.getByRole("tab", { name: "Schedule", exact: true }).waitFor();
    for (const width of [390, 1245]) {
      await page.setViewportSize({ width, height: 800 });
      const boxes = await page.evaluate(() => {
        const title = document.querySelector("#dialog-body input"),
          actions = document.querySelector(".event-editor-actions"),
          tabs = document.querySelector("[role=tablist]");
        return {
          title: title.getBoundingClientRect().bottom,
          actions: actions.getBoundingClientRect().top,
          tabs: tabs.getBoundingClientRect().top,
          overflow:
            document.querySelector("#dialog-body").scrollWidth >
            document.querySelector("#dialog-body").clientWidth + 1,
        };
      });
      assert(boxes.actions > boxes.title + 20);
      assert(boxes.tabs > boxes.title);
      assert.equal(boxes.overflow, false);
      await page
        .locator("#dialog")
        .screenshot({ path: "work/0120-event-" + width + ".png" });
    }
    await page.getByRole("button", { name: "Cancel", exact: true }).click();
    await page.evaluate(() => {
      state.page = "calendar";
      state.selected = null;
      render();
    });
    assert.equal(
      await page.getByLabel("Show meals", { exact: true }).isVisible(),
      false,
    );
    await page.getByText("Calendar options", { exact: true }).click();
    assert(await page.getByLabel("Show meals", { exact: true }).isVisible());
    await page.getByRole("button", { name: "New event", exact: true }).click();
    await page
      .getByLabel("Event title", { exact: true })
      .fill("Kept through idle lock");
    await page.getByRole("button", { name: "Cancel", exact: true }).click();
    await page.evaluate(() => {
      state.page = "meals";
      state.selected = null;
      render();
    });
    await page.getByRole("button", { name: "Plan food", exact: true }).click();
    await page
      .getByLabel("Event title", { exact: true })
      .fill("After-school snack");
    assert.deepEqual(
      await page
        .getByLabel("Plan as", { exact: true })
        .locator("option")
        .allTextContents(),
      ["Calendar event", "Food or occasion", "Prep session"],
    );
    await page.getByLabel("Ready time", { exact: true }).fill("15:30");
    await page.getByRole("button", { name: "Save event", exact: true }).click();
    await page.locator("#dialog").waitFor({ state: "hidden" });
    await page.getByRole("button", { name: /After-school snack/ }).click();
    await page
      .getByRole("button", { name: "Edit preparation", exact: true })
      .click();
    await page.getByRole("button", { name: "Add item", exact: true }).click();
    await page.getByLabel("Item name", { exact: true }).fill("Anything I pack");
    await page.getByRole("button", { name: "Add phase", exact: true }).click();
    await page.getByLabel("Phase name", { exact: true }).fill("Pack it");
    await page.getByLabel("Duration (minutes)", { exact: true }).fill("0");
    await page.getByLabel("Remind me when finished", { exact: true }).uncheck();
    await page
      .getByRole("button", { name: "Save preparation", exact: true })
      .click();
    await page.locator("#dialog").waitFor({ state: "hidden" });
    const readToken = await f.login();
    const snack = (
      await f.request("/api/snapshot", { token: readToken })
    ).data.records.find((r) => r.data.title === "After-school snack");
    assert.equal(snack.data.meal_plan.items[0].stages[0].duration_minutes, 0);
    assert.equal(snack.data.meal_plan.items[0].name, "Anything I pack");
    await page.getByRole("button", { name: "Close", exact: true }).click();
    await page.evaluate(() => {
      state.page = "calendar";
      state.selected = null;
      render();
    });
    await page.getByRole("button", { name: "New event", exact: true }).click();
    await page
      .getByLabel("Event title", { exact: true })
      .fill("Kept through idle lock");
    await page.clock.install();
    await page.clock.fastForward(31 * 60 * 1000);
    await page.getByRole("button", { name: "Sign in", exact: true }).waitFor();
    await page.getByLabel("User name", { exact: true }).fill("owner");
    await page.getByLabel("Password", { exact: true }).fill(password);
    await page.getByRole("button", { name: "Sign in", exact: true }).click();
    await page.getByLabel("Event title", { exact: true }).waitFor();
    assert.equal(
      await page.getByLabel("Event title", { exact: true }).inputValue(),
      "Kept through idle lock",
    );
    assert.deepEqual(errors, []);
  } finally {
    await browser?.close();
    await f.close();
  }
});
