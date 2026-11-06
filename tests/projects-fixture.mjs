import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { fixture, password } from "./support.mjs";

// Synthetic household used by browser checks and the guarded Android gallery.
export async function projectsFixture(options = {}) {
  const f = await fixture(options);
  try {
    const owner = await f.login();
    await f.request("/api/reauth", { token: owner, body: { password } });
    for (const [name, role] of [
      ["Alex", "admin"],
      ["Sophia", "member"],
    ]) {
      assert.equal(
        (
          await f.request("/api/admin", {
            token: owner,
            body: { action: "user.create", name, role, password },
          })
        ).status,
        200,
      );
    }
    const alex = await f.login("Alex"),
      sophia = await f.login("Sophia");
    const basis = (await f.request("/api/snapshot", { token: alex })).data;
    const people = (await f.request("/api/people", { token: alex })).data
      .people;
    const person = (name) => people.find((p) => p.name === name).id;
    async function send(
      action,
      target,
      data = {},
      revision = null,
      token = alex,
    ) {
      const r = await f.request("/api/commands", {
        token,
        body: {
          operation_id: randomUUID(),
          epoch: basis.epoch,
          generation: basis.generation,
          action,
          target,
          data,
          expected_revision: revision,
        },
      });
      assert.equal(r.status, 200, JSON.stringify(r.data));
      return r.data.record;
    }
    const create = (kind, data, token) =>
      send(kind + ".create", randomUUID(), data, null, token);
    const project = await create("project", {
      title: "Bathroom repairs",
      category: "home",
      notes:
        "Stop the leak, replace the worn fittings and leave the room ready to use.",
      due: "2026-11-15",
      assignee: person("Sophia"),
    });
    const work = await create("list", {
      title: "Preparation",
      project_id: project.id,
      items_are_tasks: true,
    });
    const reference = await create("list", {
      title: "Parts & receipts",
      template: "shopping",
      project_id: project.id,
    });
    const measured = await create("task", {
      title: "Measure the vanity",
      project_id: work.id,
      notes: "Measure both corners before ordering.",
    });
    await send("task.complete", measured.id, {}, measured.revision);
    const choice = await create(
      "task",
      {
        title: "Choose replacement fittings",
        project_id: work.id,
        planned_for: "2026-11-07",
        due: "2026-11-12",
        estimate_minutes: 45,
      },
      sophia,
    );
    await create("task", {
      title: "Remove the old sealant",
      project_id: work.id,
      blocked: true,
      notes: "Wait for the replacement parts.",
    });
    await create("task", {
      title: "Book the plumber",
      project_id: project.id,
      due: "2026-11-09",
      assignee: person("Sophia"),
      estimate_minutes: 15,
    });
    let active = await create("task", {
      title: "Check the water supply",
      project_id: project.id,
      estimate_minutes: 20,
    });
    active = await send("task.start", active.id, {}, active.revision);
    await create("task", {
      title: "Mixer tap specifications",
      project_id: reference.id,
      notes: "Keep the receipt and model number here.",
    });
    const grocery = await create("list", {
      title: "Costco Shopping List",
      template: "groceries",
      category: "food",
    });
    const bananas = await create(
      "task",
      {
        title: "Bananas",
        quantity: "6",
        store: "Costco",
        project_id: grocery.id,
      },
      sophia,
    );
    const standalone = await create("task", {
      title: "Fix the hallway light",
      category: "home",
      notes: "A standalone honey-do task; no project needed.",
    });
    return {
      f,
      owner,
      alex,
      sophia,
      basis,
      people,
      person,
      send,
      create,
      project,
      work,
      reference,
      choice,
      active,
      grocery,
      bananas,
      standalone,
      snapshot: (await f.request("/api/snapshot", { token: alex })).data,
    };
  } catch (error) {
    await f.close();
    throw error;
  }
}
