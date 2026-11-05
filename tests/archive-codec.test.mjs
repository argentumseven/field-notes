import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { webcrypto, randomUUID } from "node:crypto";
import vm from "node:vm";
import * as fflate from "fflate";
const scope = {
  window: {},
  crypto: webcrypto,
  TextEncoder,
  TextDecoder,
  Uint8Array,
  DataView,
  fflate,
  setTimeout,
};
vm.runInNewContext(readFileSync("apps/admin/archive.js", "utf8"), scope);
const codec = scope.window.FieldArchive,
  pass = "A portable café 🌙 with punctuation!";
test("portable framed encryption round-trips Unicode and multiple frames; rejects damage and valid-prefix truncation", async () => {
  const bytes = new Uint8Array(180000).map((_, i) => i % 251),
    encrypted = await codec.encrypt(bytes, pass);
  assert.deepEqual(
    Buffer.from(await codec.decrypt(encrypted, pass)),
    Buffer.from(bytes),
  );
  await assert.rejects(
    codec.decrypt(encrypted, "a wrong password!"),
    /incorrect|damaged/,
  );
  for (const mutate of [
    (x) => x.subarray(0, x.length - 20),
    (x) => {
      x[100] ^= 1;
      return x;
    },
    (x) => {
      x[35] ^= 1;
      return x;
    },
    (x) => new Uint8Array([...x, 0]),
  ])
    await assert.rejects(
      codec.decrypt(mutate(encrypted.slice()), pass),
      /incorrect|damaged/,
    );
  const swapped = encrypted.slice(),
    size = 65556;
  swapped.set(encrypted.subarray(35 + size, 35 + 2 * size), 35);
  swapped.set(encrypted.subarray(35, 35 + size), 35 + size);
  await assert.rejects(codec.decrypt(swapped, pass), /incorrect|damaged/);
});
test("portable unpack verifies complete image hashes, file set and decompression bounds", async () => {
  const id = randomUUID(),
    bytes = new TextEncoder().encode(
      "not imported until server image validation",
    ),
    manifest = {
      format: "org.fieldnotes.archive",
      version: 1,
      source: randomUUID(),
      generation: 1,
      created_at: 1,
      people: [],
      records: [],
      retained: [],
      media: [{ id, size: bytes.length, sha256: await codec.digest(bytes) }],
    };
  const files = {
    "manifest.json": new TextEncoder().encode(JSON.stringify(manifest)),
    ["media/" + id + ".bin"]: bytes,
  };
  assert.equal(
    (await codec.unpack(fflate.zipSync(files))).manifest.source,
    manifest.source,
  );
  await assert.rejects(
    codec.unpack(
      fflate.zipSync({
        ...files,
        ["media/" + id + ".bin"]: new Uint8Array([1]),
      }),
    ),
    /incomplete|checksum/,
  );
  await assert.rejects(
    codec.unpack(
      fflate.zipSync({ ...files, "../unexpected": new Uint8Array([1]) }),
    ),
    /unexpected/,
  );
  await assert.rejects(
    codec.unpack(
      fflate.zipSync(
        {
          ...files,
          ["media/" + id + ".bin"]: new Uint8Array(8 * 1024 * 1024 + 1),
        },
        { level: 9 },
      ),
    ),
    /size|bound/,
  );
});
