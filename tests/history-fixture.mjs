import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { fixture, password } from "./support.mjs";

export async function historyFixture(options = {}) {
  const f = await fixture(options);
  try {
    const owner = await f.login();
    await f.request("/api/reauth", { token: owner, body: { password } });
    for (const [name, role] of [
      ["Alex", "admin"],
      ["Sophia", "member"],
    ])
      assert.equal(
        (
          await f.request("/api/admin", {
            token: owner,
            body: { action: "user.create", name, role, password },
          })
        ).status,
        200,
      );
    const alex = await f.login("Alex"),
      sophia = await f.login("Sophia"),
      snapshot = (await f.request("/api/snapshot", { token: alex })).data;
    const send = async (token, action, target, data = {}, revision = null) => {
      const r = await f.request("/api/commands", {
        token,
        body: {
          operation_id: randomUUID(),
          epoch: snapshot.epoch,
          generation: snapshot.generation,
          action,
          target,
          expected_revision: revision,
          data,
        },
      });
      assert.equal(r.status, 200, JSON.stringify(r.data));
      return r.data;
    };
    const list = randomUUID(),
      bananas = randomUUID(),
      removed = randomUUID();
    await send(alex, "list.create", list, {
      title: "Costco Shopping List",
      template: "groceries",
      category: "food",
    });
    await send(sophia, "task.create", bananas, {
      title: "Bananas",
      quantity: "2",
      notes: "Two for breakfast",
      store: "Costco",
      project_id: list,
    });
    await send(sophia, "task.create", removed, {
      title: "Tomatoes",
      quantity: "6",
      project_id: list,
      store: "Market",
    });
    await send(alex, "task.delete", removed, {}, 1);
    await send(
      alex,
      "task.update",
      bananas,
      { quantity: "4", notes: "Four for the breakfast smoothies" },
      1,
    );
    return { f, owner, alex, sophia, snapshot, send, list, bananas, removed };
  } catch (error) {
    await f.close();
    throw error;
  }
}
