import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdir } from "node:fs/promises";
import { chromium } from "playwright";
import { projectsFixture } from "./projects-fixture.mjs";
import { password } from "./support.mjs";

async function scene() {
  const s = await projectsFixture();
  let browser;
  try {
    browser = await chromium.launch({
      executablePath: process.env.CHROMIUM_PATH || "/usr/bin/chromium",
      headless: true,
      args: ["--no-sandbox"],
    });
    const page = await browser.newPage({
      viewport: { width: 1440, height: 1080 },
      timezoneId: "America/New_York",
      colorScheme: "dark",
      locale: "en-US",
    });
    const errors = [];
    page.on("pageerror", (e) => errors.push(e.message));
    await page.clock.setFixedTime(new Date("2026-11-06T14:00:00Z"));
    await page.goto(s.f.admin);
    await page.getByLabel("User name", { exact: true }).fill("Alex");
    await page.getByLabel("Password", { exact: true }).fill(password);
    await page.getByRole("button", { name: "Sign in", exact: true }).click();
    await page
      .getByRole("heading", { name: "Calendar", exact: true })
      .waitFor();
    return {
      ...s,
      page,
      errors,
      async close() {
        await browser.close();
        await s.f.close();
      },
    };
  } catch (error) {
    await browser?.close();
    await s.f.close();
    throw error;
  }
}
async function shot(page, name) {
  if (process.env.FIELD_NOTES_CAPTURE_PROJECTS !== "synthetic-only") return;
  await mkdir("docs/screenshots/web", { recursive: true });
  await page.screenshot({
    path: `docs/screenshots/web/${name}.png`,
    fullPage: true,
  });
}

test("project work rolls up across task lists, keeps reference items separate and navigates back to its context", async () => {
  const s = await scene();
  try {
    const { page } = s;
    await page.getByRole("button", { name: "Tasks", exact: true }).click();
    await page
      .getByRole("button", {
        name: "Open task: Choose replacement fittings",
        exact: true,
      })
      .waitFor();
    assert.equal(
      await page
        .getByRole("button", { name: "Open task: Bananas", exact: true })
        .count(),
      0,
    );
    await page.getByRole("button", { name: "Projects", exact: true }).click();
    await page
      .getByRole("button", {
        name: "Open project: Bathroom repairs",
        exact: true,
      })
      .click();
    const overview = page.getByRole("region", { name: "Project overview" });
    assert.match(await overview.innerText(), /1 of 5 tasks complete/);
    assert.match(
      await overview.innerText(),
      /1 ready · 1 in progress · 1 waiting · 1 planned later/,
    );
    await page
      .getByRole("button", { name: "Finish project", exact: true })
      .isDisabled()
      .then((x) => assert(x));
    assert.equal(
      await page
        .getByRole("button", { name: "Put on hold", exact: true })
        .isDisabled(),
      true,
    );
    await shot(page, "83-project-workspace");
    await page
      .getByLabel("Work group", { exact: true })
      .selectOption(s.work.id);
    assert.equal(
      await page
        .getByRole("button", {
          name: "Open task: Book the plumber",
          exact: true,
        })
        .count(),
      0,
    );
    assert.match(
      await overview.innerText(),
      /1 of 5 tasks complete/,
      "filters never change the project denominator",
    );
    await page.getByLabel("Find a task", { exact: true }).fill("replacement");
    await page
      .getByRole("button", {
        name: "Open task: Choose replacement fittings",
        exact: true,
      })
      .click();
    await page
      .getByRole("button", {
        name: "Open project · Bathroom repairs",
        exact: true,
      })
      .click();
    assert.equal(
      await page.getByLabel("Work group", { exact: true }).inputValue(),
      s.work.id,
    );
    await page.getByLabel("Find a task", { exact: true }).fill("");
    await page.getByRole("button", { name: "New task", exact: true }).click();
    assert.equal(
      await page.getByLabel("Keep in", { exact: true }).inputValue(),
      s.work.id,
    );
    assert.equal(
      await page.getByLabel("Category", { exact: true }).last().inputValue(),
      "home",
    );
    await page.getByRole("button", { name: "Cancel", exact: true }).click();
    await page.locator(".project-links>summary").click();
    await page
      .getByRole("button", {
        name: "Parts & receipts · Reference list",
        exact: true,
      })
      .click();
    await page
      .getByRole("button", { name: "‹ Bathroom repairs", exact: true })
      .click();
    await page
      .getByRole("button", { name: "Project history", exact: true })
      .click();
    await page
      .getByRole("button", { name: "All household history", exact: true })
      .waitFor();
    assert.doesNotMatch(
      await page.locator(".history-timeline").innerText(),
      /Bananas/,
    );
    await shot(page, "84-project-history");
    assert.deepEqual(s.errors, []);
  } finally {
    await s.close();
  }
});

test("the shared recipient picker keeps multi-select open, saves across all reminder kinds and rejects an empty choice", async () => {
  const s = await scene();
  try {
    const { page } = s;
    const rules = {
      alerts: [
        {
          id: crypto.randomUUID(),
          minutes_before: 0,
          importance: "normal",
          sound: "chime",
          volume: 75,
          vibrate: true,
        },
      ],
      repeat: null,
    };
    const records = [
      s.standalone,
      await s.create("event", {
        title: "Household review",
        starts_at: 1793977200,
        all_day: false,
        reminders: rules,
      }),
      await s.create("note", {
        title: "Remember the survey",
        body: "Bring the papers",
        on_radar: true,
        remind_at: 1793977200,
        reminders: rules,
      }),
    ];
    await page.evaluate(async () => {
      await refresh();
      render();
    });
    for (const record of records) {
      await page.evaluate((id) => {
        void FieldReminders.edit(byId(id)).catch(() => {});
      }, record.id);
      await page.locator(".reminder-editor>summary").click();
      const who = page.getByLabel("Who receives reminders", { exact: true });
      await who.selectOption("people");
      assert.equal(
        await who.inputValue(),
        "people",
        "the mode must not collapse to Only me",
      );
      const alex = page
          .locator(".reminder-recipients")
          .getByRole("checkbox", { name: "Alex", exact: true }),
        sophia = page
          .locator(".reminder-recipients")
          .getByRole("checkbox", { name: "Sophia", exact: true });
      await sophia.check();
      await alex.uncheck();
      await sophia.uncheck();
      await page
        .getByRole("button", { name: "Save reminders", exact: true })
        .click();
      await page
        .getByText("Choose who receives reminders.", { exact: true })
        .waitFor();
      await alex.check();
      await sophia.check();
      if (record.kind === "task")
        await page
          .getByRole("button", { name: "Add reminder", exact: true })
          .click();
      await page
        .getByRole("button", { name: "Save reminders", exact: true })
        .click();
      await page.locator("#dialog").waitFor({ state: "hidden" });
      for (const token of [s.alex, s.sophia, s.owner]) {
        const saved = (
          await s.f.request("/api/snapshot", { token })
        ).data.records.find((r) => r.id === record.id);
        assert.deepEqual(
          saved.data.reminder_options.audience.people.sort(),
          [s.person("Alex"), s.person("Sophia")].sort(),
        );
        assert.equal(
          saved.data.reminders.alerts.length,
          token === s.owner ? 0 : 1,
          "only selected people receive projected rules",
        );
      }
      await page.evaluate((id) => {
        void FieldReminders.edit(byId(id)).catch(() => {});
      }, record.id);
      await page.locator(".reminder-editor>summary").click();
      assert.equal(await who.inputValue(), "people");
      assert.equal(
        await page
          .locator(".reminder-recipients")
          .getByRole("checkbox", { name: "Sophia", exact: true })
          .isChecked(),
        true,
      );
      await shot(page, "85-reminder-recipients");
      await page.getByRole("button", { name: "Cancel", exact: true }).click();
    }
    assert.deepEqual(s.errors, []);
  } finally {
    await s.close();
  }
});

test("grocery items have no reminder or recipient editor while list addition alerts stay available", async () => {
  const s = await scene();
  try {
    const { page } = s;
    await page.getByRole("button", { name: "Lists", exact: true }).click();
    await page
      .getByRole("button", {
        name: "Open list: Costco Shopping List",
        exact: true,
      })
      .click();
    await page
      .getByRole("button", { name: "Open item: Bananas", exact: true })
      .click();
    assert.equal(await page.locator("#dialog .reminder-editor").count(), 0);
    assert.equal(
      await page.getByLabel("Who receives reminders", { exact: true }).count(),
      0,
    );
    await shot(page, "86-grocery-item");
    await page.getByRole("button", { name: "Cancel", exact: true }).click();
    await page
      .getByRole("button", { name: "List settings", exact: true })
      .click();
    assert.equal(
      await page
        .getByLabel("Scheduled item reminders", { exact: true })
        .isVisible(),
      false,
    );
    assert.equal(
      await page
        .getByLabel("Notify when items are added", { exact: true })
        .isVisible(),
      true,
    );
    await page.getByRole("button", { name: "Cancel", exact: true }).click();
    assert.deepEqual(s.errors, []);
  } finally {
    await s.close();
  }
});
