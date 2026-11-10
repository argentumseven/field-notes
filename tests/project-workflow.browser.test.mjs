import { test } from "node:test";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { chromium } from "playwright";
import { fixture, password } from "./support.mjs";

test("create in a project, add an existing task, move and unbundle without losing content", async () => {
  const f = await fixture();
  let browser;
  try {
    const token = await f.login();
    const meta = (await f.request("/api/snapshot", { token })).data;
    const send = (action, target, data, expected_revision = null) =>
      f.request("/api/commands", {
        token,
        body: {
          operation_id: randomUUID(),
          epoch: meta.epoch,
          generation: meta.generation,
          action,
          target,
          data,
          expected_revision,
        },
      });
    const a = randomUUID(),
      b = randomUUID(),
      task = randomUUID();
    assert.equal(
      (await send("project.create", a, { title: "Kitchen refresh" })).status,
      200,
    );
    assert.equal(
      (await send("project.create", b, { title: "Garden refresh" })).status,
      200,
    );
    assert.equal(
      (
        await send("task.create", task, {
          title: "Measure the shelves",
          notes: "Keep these measurements",
          due: "2027-02-01",
        })
      ).status,
      200,
    );
    const comment = randomUUID();
    assert.equal(
      (
        await send("comment.create", comment, {
          task_id: task,
          body: "Use the long tape",
        })
      ).status,
      200,
    );
    browser = await chromium.launch({
      executablePath: process.env.CHROMIUM_PATH || "/usr/bin/chromium",
      headless: true,
      args: ["--no-sandbox"],
    });
    const page = await browser.newPage();
    const errors = [];
    page.on("pageerror", (e) => errors.push(e.message));
    await page.goto(f.admin);
    await page.getByLabel("User name", { exact: true }).fill("owner");
    await page.getByLabel("Password", { exact: true }).fill(password);
    await page.getByRole("button", { name: "Sign in", exact: true }).click();
    async function openProject(title) {
      await page.getByRole("button", { name: "Projects", exact: true }).click();
      await page.locator(".collection-row").filter({ hasText: title }).click();
    }
    async function saved() {
      const snap = (await f.request("/api/snapshot", { token })).data;
      return snap.records.find((r) => r.id === task);
    }
    async function moveExisting() {
      await page
        .getByRole("button", { name: "Add existing task", exact: true })
        .click();
      await page.getByLabel("Task to move", { exact: true }).selectOption(task);
      await page
        .getByRole("button", { name: "Move task", exact: true })
        .click();
      await page.locator("#dialog").waitFor({ state: "hidden" });
    }
    await page.getByRole("button", { name: "Tasks", exact: true }).click();
    await page.getByRole("button", { name: "New task", exact: true }).click();
    assert.equal(
      await page.getByLabel("Project or list", { exact: true }).inputValue(),
      "",
    );
    await page.locator("#dialog-cancel").click();
    await openProject("Kitchen refresh");
    await page.getByRole("button", { name: "New task", exact: true }).click();
    assert.equal(
      await page.getByLabel("Project or list", { exact: true }).inputValue(),
      a,
    );
    await page
      .getByLabel("Task title", { exact: true })
      .fill("Choose shelf brackets");
    await page
      .getByRole("button", { name: "Create task", exact: true })
      .click();
    await page
      .getByRole("button", {
        name: "Open task: Choose shelf brackets",
        exact: true,
      })
      .waitFor();
    await page.getByRole("button", { name: "Close", exact: true }).click();
    await moveExisting();
    assert.equal((await saved()).data.project_id, a);
    await openProject("Garden refresh");
    await moveExisting();
    assert.equal((await saved()).data.project_id, b);
    await page
      .getByRole("button", {
        name: "Open task: Measure the shelves",
        exact: true,
      })
      .click();
    await page.getByLabel("Project or list", { exact: true }).selectOption("");
    await page
      .getByRole("button", { name: "Save details", exact: true })
      .click();
    await page.getByText("Details saved.", { exact: true }).waitFor();
    assert.equal((await saved()).data.project_id, "");
    assert.equal((await saved()).data.notes, "Keep these measurements");
    assert.equal((await saved()).data.due, "2027-02-01");
    const snap = (await f.request("/api/snapshot", { token })).data;
    assert.equal(
      snap.records.find((r) => r.id === comment).data.body,
      "Use the long tape",
    );
    await page.getByRole("button", { name: "Close", exact: true }).click();

    // The picker must retain its captured revision if the task changes elsewhere.
    await page
      .getByRole("button", { name: "Add existing task", exact: true })
      .click();
    await page.getByLabel("Task to move", { exact: true }).selectOption(task);
    const latest = await saved();
    assert.equal(
      (
        await send(
          "task.update",
          task,
          { notes: "Changed on another device" },
          latest.revision,
        )
      ).status,
      200,
    );
    await page.getByRole("button", { name: "Move task", exact: true }).click();
    await page
      .locator("#dialog-error")
      .filter({ hasText: /changed|revision|conflict/i })
      .waitFor();
    assert.equal((await saved()).data.project_id, "");
    assert.equal((await saved()).data.notes, "Changed on another device");
    assert.deepEqual(errors, []);
  } finally {
    await browser?.close();
    await f.close();
  }
});
