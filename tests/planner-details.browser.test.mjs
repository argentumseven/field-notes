import { test } from "node:test";
import assert from "node:assert/strict";
import { chromium } from "playwright";
import { mkdir } from "node:fs/promises";
import { fixture, password } from "./support.mjs";
async function scene() {
  const f = await fixture();
  const browser = await chromium.launch({
    executablePath: process.env.CHROMIUM_PATH || "/usr/bin/chromium",
    headless: true,
    args: ["--no-sandbox"],
  });
  const page = await browser.newPage({
      viewport: { width: 1440, height: 1080 },
      timezoneId: "America/New_York",
      colorScheme: "dark",
    }),
    errors = [];
  page.on("pageerror", (e) => errors.push(e.message));
  await page.goto(f.admin);
  await page.getByLabel("User name", { exact: true }).fill("owner");
  await page.getByLabel("Password", { exact: true }).fill(password);
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await page.getByRole("heading", { name: "Calendar", exact: true }).waitFor();
  return { f, browser, page, errors };
}
async function shot(page, name) {
  if (process.env.FIELD_NOTES_CAPTURE_PLANNER !== "synthetic-only") return;
  await mkdir("docs/screenshots/web", { recursive: true });
  await page.mouse.move(0, 0);
  await page.waitForTimeout(150);
  await page.screenshot({
    path: "docs/screenshots/web/" + name + ".png",
    fullPage: true,
  });
}
test("event sections keep explicit all-day times, one sound mode and copyable location", async () => {
  const s = await scene();
  const { page } = s;
  try {
    await page.getByRole("button", { name: "New event", exact: true }).click();
    await page
      .getByLabel("Event title", { exact: true })
      .fill("Annual family trip");
    await page.getByLabel("Starts", { exact: true }).fill("2028-03-31");
    await page.getByLabel("All day", { exact: true }).check();
    await page.getByRole("tab", { name: "Reminders", exact: true }).click();
    assert.equal(
      await page.getByLabel("Reminder 1", { exact: true }).inputValue(),
      "day-time",
    );
    await page.getByLabel("At time", { exact: true }).fill("10:30");
    await page.getByLabel("Day unit", { exact: true }).selectOption("weeks");
    await page.getByLabel("How far before", { exact: true }).fill("1");
    await page.locator(".reminder-row details>summary").first().click();
    await page
      .getByLabel("Reminder 1 delivery mode", { exact: true })
      .selectOption("alarm");
    assert.equal(
      await page
        .getByLabel("Reminder 1 alarm duration", { exact: true })
        .inputValue(),
      "5",
    );
    const sound=page.getByLabel("Reminder 1 sound", {exact:true});
    assert.deepEqual(await sound.locator("option").evaluateAll(nodes=>nodes.map(n=>n.value)),["beacon","orbit","sunrise","siren"]);
    assert.equal(await sound.inputValue(),"beacon");
    const alarmAudio=await page.request.get(new URL("/sounds/v1/alarm_beacon_75.wav",s.f.admin).href);
    const notificationAudio=await page.request.get(new URL("/sounds/v1/reminder_beacon_75.wav",s.f.admin).href);
    assert.equal(alarmAudio.status(),200);assert.equal(notificationAudio.status(),200);
    const alarmBytes=await alarmAudio.body(), notificationBytes=await notificationAudio.body();
    assert(alarmBytes.length>notificationBytes.length*3);assert.notDeepEqual(alarmBytes,notificationBytes);
    await page.getByLabel("Reminder 1 delivery mode",{exact:true}).selectOption("notification");
    assert.deepEqual(await sound.locator("option").evaluateAll(nodes=>nodes.map(n=>n.value)),["chime","bell","pulse","marimba"]);
    assert.equal(await sound.inputValue(),"chime");
    await page.getByLabel("Reminder 1 delivery mode",{exact:true}).selectOption("alarm");
    await page.getByLabel("Reminder 1 importance",{exact:true}).selectOption("quiet");
    await page.getByLabel("Reminder 1 importance",{exact:true}).selectOption("normal");
    assert.equal(await page.getByLabel("Reminder 1 delivery mode",{exact:true}).inputValue(),"notification");
    assert.equal(await page.getByLabel("Reminder 1 alarm duration",{exact:true}).isVisible(),false);
    await page.getByLabel("Reminder 1 delivery mode",{exact:true}).selectOption("alarm");
    await shot(page, "110-all-day-reminder");
    await page
      .getByLabel("Reminder 1 alarm duration", { exact: true })
      .scrollIntoViewIfNeeded();
    await shot(page, "116-alarm-volume-controls");
    await page.getByRole("tab", { name: "Details", exact: true }).click();
    await page
      .getByLabel("Location · optional", { exact: true })
      .fill("123 Main Street");
    await page
      .getByLabel("Notes", { exact: true })
      .fill("Meet at the entrance.");
    await page.evaluate(() => {
      Object.defineProperty(navigator, "clipboard", {
        configurable: true,
        value: undefined,
      });
      window.copyCalls = 0;
      document.execCommand = () => {
        window.copyCalls++;
        return true;
      };
    });
    await page
      .getByRole("button", { name: "Copy location", exact: true })
      .click();
    assert.equal(await page.evaluate(() => window.copyCalls), 1);
    assert.equal(
      await page
        .getByRole("tab", { name: "Details", exact: true })
        .getAttribute("aria-selected"),
      "true",
    );
    await page.locator("#notice:not(.visible)").waitFor();
    await shot(page, "111-event-details");
    await page.getByRole("button", { name: "Save event", exact: true }).click();
    await page.getByRole("dialog").waitFor({ state: "hidden" });
    const token = await s.f.login(),
      snap = (await s.f.request("/api/snapshot", { token })).data,
      item = snap.records.find((r) => r.data.title === "Annual family trip");
    assert(item);
    assert.equal(item.data.location, "123 Main Street");
    assert.equal(
      item.data.reminder_schedule_rules.alerts[0].day_time.days_before,
      7,
    );
    assert.equal(
      item.data.reminder_options.playback[
        item.data.reminder_schedule_rules.alerts[0].id
      ].duration_seconds,
      5,
    );
    if (process.env.FIELD_NOTES_CAPTURE_PLANNER === "synthetic-only") {
      await page.evaluate(async () => {
        const start = FieldPlanning.scheduledInstant(state.day, "10:00");
        await mutate(
          "event.create",
          uuid(),
          {
            title: "Household check-in",
            all_day: false,
            starts_at: start,
            ends_at: start + 3600,
            location: "Kitchen table",
          },
          null,
        );
        await mutate(
          "event.create",
          uuid(),
          {
            title: "Pickup",
            all_day: false,
            starts_at: start + 5 * 3600,
            ends_at: null,
            location: "Library entrance",
            category: "errands",
          },
          null,
        );
        await mutate(
          "task.create",
          uuid(),
          {
            title: "Plan weekend meals",
            planned_for: state.day,
            category: "food",
            priority: 3,
          },
          null,
        );
        await refresh();
        render();
      });
      await shot(page, "119-calendar-at-glance");
    }
    await page.getByRole("button", { name: "Month", exact: true }).click();
    await page.locator(".calendar-options>summary").click();
    await page.getByText("Show US federal holidays", { exact: true }).check();
    assert.equal(await page.locator("button.calendar-day.outside").count(), 0);
    const dates = await page
      .locator("button.calendar-day>strong")
      .allTextContents();
    assert.equal(dates[0], "1");
    assert(dates.every((n) => Number(n) >= 1 && Number(n) <= 31));
    await shot(page, "112-calendar-holidays");
    await page.evaluate(
      () => (document.documentElement.dataset.theme = "light"),
    );
    await page.waitForTimeout(200); // Let the real theme color transition finish before the gallery capture.
    await shot(page, "117-calendar-light");
    assert.deepEqual(s.errors, []);
  } finally {
    await s.browser.close();
    await s.f.close();
  }
});
test("recipes search full ingredients and instructions and preserve optional sections", async () => {
  const s = await scene();
  const { page } = s;
  try {
    await page.getByRole("button", { name: "Recipes", exact: true }).click();
    await page.getByRole("button", { name: "New recipe", exact: true }).click();
    await page.getByLabel("Recipe name", { exact: true }).fill("Lentil soup");
    await page
      .getByLabel("Ingredients", { exact: true })
      .fill("Lentils, carrots, cumin");
    await page
      .getByLabel("Cooking instructions", { exact: true })
      .fill("Simmer gently for 25 minutes.");
    assert.equal(
      await page
        .getByText("Keep in Later & reminders", { exact: true })
        .isVisible(),
      false,
    );
    await shot(page, "113-recipe-editor");
    await page
      .getByRole("button", { name: "Create recipe", exact: true })
      .click();
    await page.getByRole("dialog").waitFor({ state: "hidden" });
    await page
      .getByRole("searchbox", { name: "Find a note" })
      .fill("simmer gently");
    await page.locator(".note-row").first().waitFor();
    assert.match(await page.locator(".notes-list").innerText(), /Lentil soup/);
    await shot(page, "114-recipes");
    await page.locator(".note-row").first().click();
    await page
      .locator(".detail")
      .getByLabel("Cooking instructions", { exact: true })
      .fill("Simmer gently, then season.");
    await page
      .getByRole("button", { name: "Save recipe", exact: true })
      .click();
    await page.getByRole("button", { name: "Close", exact: true }).click();
    const token = await s.f.login(),
      snap = (await s.f.request("/api/snapshot", { token })).data,
      item = snap.records.find((r) => r.data.title === "Lentil soup");
    assert.equal(item.data.note_type, "recipe");
    assert.equal(item.data.instructions, "Simmer gently, then season.");
    await page.getByRole("button", { name: "Management", exact: true }).click();
    await page
      .getByRole("heading", { name: "Server time", exact: true })
      .waitFor();
    await shot(page, "115-server-time");
    assert.deepEqual(s.errors, []);
  } finally {
    await s.browser.close();
    await s.f.close();
  }
});
