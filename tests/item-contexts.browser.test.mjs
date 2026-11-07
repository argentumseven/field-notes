import { test } from "node:test";
import assert from "node:assert/strict";
import { chromium } from "playwright";
import { mkdir, readFile } from "node:fs/promises";
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
        colorScheme: "dark",
      }),
      errors = [];
    page.on("pageerror", (e) => errors.push(e.message));
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
      close: async () => {
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
async function openList(page, title) {
  await page.getByRole("button", { name: "Lists", exact: true }).click();
  await page
    .getByRole("button", { name: "Open list: " + title, exact: true })
    .click();
}
async function shot(page, name) {
  if (process.env.FIELD_NOTES_CAPTURE_CONTEXTS !== "synthetic-only") return;
  await mkdir("docs/screenshots/web", { recursive: true });
  await page.screenshot({
    path: "docs/screenshots/web/" + name + ".png",
    fullPage: true,
  });
}

test("grocery, shopping and all tagged task-list areas expose only their relevant item choices", async () => {
  const s = await scene();
  try {
    const profiles = JSON.parse(
      await readFile("shared/item-contexts.json", "utf8"),
    );
    for (const key of [
      "groceries",
      "shopping",
      "home",
      "food",
      "errands",
      "work",
      "personal",
    ]) {
      const title = "Context example · " + key,
        profile = profiles[key];
      const list = await s.create("list", {
        title,
        template: ["groceries", "shopping"].includes(key) ? key : "general",
        category: ["groceries", "shopping"].includes(key) ? "general" : key,
        items_are_tasks: !["groceries", "shopping"].includes(key),
      });
      await openList(s.page, title);
      await s.page
        .getByRole("button", { name: "Add item", exact: true })
        .click();
      const dialog = s.page.getByRole("dialog");
      await dialog
        .getByLabel("Item name", { exact: true })
        .fill("Useful " + key + " item");
      assert.equal(
        await dialog.getByLabel("Category", { exact: true }).count(),
        0,
      );
      const picker = dialog.getByLabel(profile.label, { exact: true });
      assert.deepEqual(await picker.locator("option").allTextContents(), [
        "Uncategorized",
        ...profile.choices,
      ]);
      await picker.selectOption(profile.choices[0]);
      if (key === "groceries") await shot(s.page, "97-grocery-category");
      if (key === "home") await shot(s.page, "98-household-category");
      await dialog
        .getByRole("button", { name: "Add item", exact: true })
        .click();
      await dialog.waitFor({ state: "hidden" });
      await s.page
        .getByRole("button", {
          name: "Open item: Useful " + key + " item",
          exact: true,
        })
        .waitFor();
      const snapshot = (await s.f.request("/api/snapshot", { token: s.alex }))
        .data;
      assert.equal(
        snapshot.records.find(
          (r) => r.kind === "task" && r.data.project_id === list.id,
        ).data.item_category,
        profile.choices[0],
      );
    }
    assert.deepEqual(s.errors, []);
  } finally {
    await s.close();
  }
});

test("custom and untagged lists allow arbitrary labels and keep saved categories after retyping", async () => {
  const s = await scene();
  try {
    const list = await s.create("list", {
      title: "Ideas or anything",
      template: "custom",
      category: "home",
      project_id: s.project.id,
      fields: ["notes", "quantity"],
    });
    await openList(s.page, list.data.title);
    await s.page.getByRole("button", { name: "Add item", exact: true }).click();
    let dialog = s.page.getByRole("dialog");
    await dialog
      .getByLabel("Item name", { exact: true })
      .fill("An unrestricted item");
    await dialog
      .getByLabel("Item category · optional", { exact: true })
      .fill("Someday / custom idea");
    await dialog.getByLabel("Category", { exact: true }).selectOption("work");
    await shot(s.page, "99-unrestricted-item-category");
    await dialog.getByRole("button", { name: "Add item", exact: true }).click();
    await dialog.waitFor({ state: "hidden" });
    await s.page
      .getByRole("button", {
        name: "Open item: An unrestricted item",
        exact: true,
      })
      .waitFor();
    await s.send(
      "list.update",
      list.id,
      { template: "groceries" },
      list.revision,
    );
    await s.page.waitForFunction(
      (id) =>
        state.snapshot.records.find((r) => r.id === id)?.data.template ===
        "groceries",
      list.id,
      { timeout: 12000 },
    );
    await s.page
      .getByRole("button", {
        name: "Open item: An unrestricted item",
        exact: true,
      })
      .click();
    dialog = s.page.getByRole("dialog");
    const picker = dialog.getByLabel("Grocery category", { exact: true });
    assert.equal(await picker.inputValue(), "Someday / custom idea");
    assert(
      (await picker.locator("option").allTextContents()).includes(
        "Someday / custom idea · saved label",
      ),
    );
    await dialog.getByLabel("Quantity", { exact: true }).fill("2");
    await dialog
      .getByRole("button", { name: "Save item", exact: true })
      .click();
    await dialog.waitFor({ state: "hidden" });
    const item = (
      await s.f.request("/api/snapshot", { token: s.alex })
    ).data.records.find((r) => r.data.title === "An unrestricted item");
    assert.equal(item.data.item_category, "Someday / custom idea");
    assert.equal(item.data.category, "work");
    assert.equal(item.data.quantity, "2");
    const general = await s.create("list", {
      title: "No domain",
      category: "general",
      project_id: s.project.id,
    });
    await openList(s.page, general.data.title);
    await s.page.getByRole("button", { name: "Add item", exact: true }).click();
    await s.page
      .getByLabel("Item category · optional", { exact: true })
      .fill("No imposed vocabulary");
    await s.page.getByRole("button", { name: "Cancel", exact: true }).click();
    assert.deepEqual(s.errors, []);
  } finally {
    await s.close();
  }
});

test("standalone task area changes actually save and category filters scope bulk cleanup", async () => {
  const s = await scene();
  try {
    const task = await s.create("task", {
      title: "A flexible project task",
      project_id: s.project.id,
      category: "home",
    });
    await s.page.getByRole("button", { name: "Tasks", exact: true }).click();
    await s.page.locator('[data-task-id="' + task.id + '"]').click();
    const detail = s.page.getByRole("dialog", {
      name: "Task details",
      exact: true,
    });
    await detail.getByLabel("Category", { exact: true }).selectOption("work");
    await detail
      .getByLabel("Work category", { exact: true })
      .selectOption("Research");
    await detail
      .getByRole("button", { name: "Save details", exact: true })
      .click();
    await s.page.waitForFunction(
      (id) =>
        state.snapshot.records.find((r) => r.id === id)?.data.item_category ===
        "Research",
      task.id,
    );
    const saved = (
      await s.f.request("/api/snapshot", { token: s.alex })
    ).data.records.find((r) => r.id === task.id);
    assert.equal(saved.data.category, "work");
    assert.equal(saved.data.item_category, "Research");
    await detail.getByRole("button", { name: "Close", exact: true }).click();
    const produce = await s.create("task", {
      title: "Apples",
      project_id: s.grocery.id,
      store: "Costco",
      item_category: "Produce",
    });
    const dairy = await s.create("task", {
      title: "Milk",
      project_id: s.grocery.id,
      store: "Costco",
      item_category: "Dairy & eggs",
    });
    await s.send("task.complete", produce.id, {}, 1);
    await s.send("task.complete", dairy.id, {}, 1);
    await openList(s.page, s.grocery.data.title);
    await s.page
      .getByLabel("Item category", { exact: true })
      .selectOption("value:Produce");
    await s.page.getByLabel("Show", { exact: true }).selectOption("open");
    await s.page
      .getByRole("button", { name: "Delete checked (1)", exact: true })
      .click();
    await s.page
      .getByRole("dialog")
      .getByRole("button", { name: "Delete checked", exact: true })
      .click();
    await s.page
      .getByRole("button", { name: "Delete checked (0)", exact: true })
      .waitFor();
    const rows = (await s.f.request("/api/snapshot", { token: s.alex })).data
      .records;
    assert(!rows.some((r) => r.id === produce.id));
    assert(rows.some((r) => r.id === dairy.id));
    assert(rows.some((r) => r.id === s.bananas.id));
    assert.deepEqual(s.errors, []);
  } finally {
    await s.close();
  }
});
