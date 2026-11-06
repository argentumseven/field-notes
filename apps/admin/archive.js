// Portable, passphrase-encrypted files. Content archives contain no sessions,
// passwords, CA/WireGuard keys or APK signing material. All requests stay local.
window.FieldArchive = (() => {
  const encoder = new TextEncoder(),
    decoder = new TextDecoder("utf-8", { fatal: true }),
    magic = encoder.encode("FNARCHIVE1\n");
  const MAX_FILE = 256 * 1024 * 1024,
    MAX_MANIFEST = 24 * 1024 * 1024,
    MAX_MEDIA = 192 * 1024 * 1024,
    CHUNK = 128 * 1024;
  const uuid = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;
  const merge = (...arrays) => {
    const result = new Uint8Array(arrays.reduce((n, a) => n + a.length, 0));
    let offset = 0;
    for (const a of arrays) {
      result.set(a, offset);
      offset += a.length;
    }
    return result;
  };
  const digest = async (bytes) =>
    Array.from(
      new Uint8Array(await crypto.subtle.digest("SHA-256", bytes)),
      (v) => v.toString(16).padStart(2, "0"),
    ).join("");
  async function key(passphrase, salt) {
    const bytes = encoder.encode(passphrase);
    if (bytes.length < 12 || bytes.length > 256)
      throw Error(
        "Use a passphrase from 12 to 256 UTF-8 bytes. Spaces and punctuation are kept exactly.",
      );
    try {
      const material = await crypto.subtle.importKey(
        "raw",
        bytes,
        "PBKDF2",
        false,
        ["deriveKey"],
      );
      return await crypto.subtle.deriveKey(
        { name: "PBKDF2", salt, iterations: 600000, hash: "SHA-256" },
        material,
        { name: "AES-GCM", length: 256 },
        false,
        ["encrypt", "decrypt"],
      );
    } finally {
      bytes.fill(0);
    }
  }
  // Independent, authenticated 64 KiB frames keep Android memory bounded. The
  // authenticated empty final frame prevents accepting a truncated valid prefix.
  const BLOCK = 65536,
    u32 = (n) => {
      const a = new Uint8Array(4);
      new DataView(a.buffer).setUint32(0, n);
      return a;
    };
  async function encrypt(zip, passphrase) {
    if (zip.length > MAX_MANIFEST + MAX_MEDIA)
      throw Error("This export exceeds the portable size limit.");
    const salt = crypto.getRandomValues(new Uint8Array(16)),
      prefix = crypto.getRandomValues(new Uint8Array(8)),
      header = merge(magic, salt, prefix),
      secret = await key(passphrase, salt),
      parts = [header];
    let counter = 0;
    for (let offset = 0; ; offset += BLOCK) {
      const bytes = zip.subarray(offset, Math.min(offset + BLOCK, zip.length)),
        number = u32(counter++),
        length = u32(bytes.length + 16),
        nonce = merge(prefix, number),
        aad = merge(header, number, length);
      parts.push(
        length,
        new Uint8Array(
          await crypto.subtle.encrypt(
            { name: "AES-GCM", iv: nonce, additionalData: aad, tagLength: 128 },
            secret,
            bytes,
          ),
        ),
      );
      if (!bytes.length) break;
    }
    return merge(...parts);
  }
  async function decrypt(file, passphrase) {
    const headerSize = magic.length + 24;
    if (
      file.length > MAX_FILE ||
      file.length < headerSize + 20 ||
      !magic.every((v, i) => file[i] === v)
    )
      throw Error("Choose an encrypted Field Notes .fieldnotes file.");
    const header = file.subarray(0, headerSize),
      salt = header.subarray(magic.length, magic.length + 16),
      prefix = header.subarray(magic.length + 16),
      parts = [];
    let offset = headerSize,
      counter = 0,
      total = 0;
    try {
      const secret = await key(passphrase, salt);
      for (;;) {
        if (offset + 4 > file.length) throw Error();
        const length = file.subarray(offset, offset + 4),
          size = new DataView(
            file.buffer,
            file.byteOffset + offset,
            4,
          ).getUint32(0);
        offset += 4;
        if (size < 16 || size > BLOCK + 16 || offset + size > file.length)
          throw Error();
        const number = u32(counter++),
          bytes = new Uint8Array(
            await crypto.subtle.decrypt(
              {
                name: "AES-GCM",
                iv: merge(prefix, number),
                additionalData: merge(header, number, length),
                tagLength: 128,
              },
              secret,
              file.subarray(offset, offset + size),
            ),
          );
        offset += size;
        if (!bytes.length) {
          if (offset !== file.length) throw Error();
          return merge(...parts);
        }
        total += bytes.length;
        if (total > MAX_MANIFEST + MAX_MEDIA) throw Error();
        parts.push(bytes);
      }
    } catch {
      throw Error(
        "The passphrase is incorrect or this file is damaged. No planner data was imported.",
      );
    } finally {
      for (const bytes of parts) bytes.fill(0);
    }
  }
  function validate(manifest) {
    if (
      manifest?.format !== "org.fieldnotes.archive" ||
      ![1,2].includes(manifest.version) ||
      !uuid.test(manifest.source) ||
      !Number.isInteger(manifest.generation) ||
      manifest.generation < 1 ||
      !Array.isArray(manifest.records) ||
      manifest.records.length > 10000 ||
      !Array.isArray(manifest.people) ||
      manifest.people.length > 64 ||
      !Array.isArray(manifest.media) ||
      !Array.isArray(manifest.retained || [])
    )
      throw Error("Unsupported Field Notes archive version or structure.");
    const ids = new Set();
    for (const r of manifest.records) {
      if (
        !uuid.test(r.id) ||
        ids.has(r.id) ||
        ![
          "project",
          "list",
          "task",
          "note",
          "event",
          "comment",
          "image",
        ].includes(r.kind) ||
        !r.data ||
        typeof r.data !== "object"
      )
        throw Error("The archive contains duplicate or unsupported items.");
      ids.add(r.id);
    }
    const media = new Set();
    let total = 0;
    for (const m of manifest.media) {
      if (
        !uuid.test(m.id) ||
        media.has(m.id) ||
        !Number.isInteger(m.size) ||
        m.size < 1 ||
        m.size > 8 * 1024 * 1024 ||
        !/^([a-f0-9]{64})$/.test(m.sha256)
      )
        throw Error("An archive image descriptor is invalid.");
      media.add(m.id);
      total += m.size;
    }
    if (total > MAX_MEDIA)
      throw Error("Archive images exceed the supported size.");
    for (const r of manifest.records)
      if (r.kind === "image" && !media.has(r.id))
        throw Error("An archive attachment is missing.");
  }
  async function unpack(zip, progress = () => {}) {
    const files = Object.create(null),
      names = new Set();
    let total = 0;
    const reader = new fflate.Unzip((file) => {
      if (
        names.has(file.name) ||
        names.size >= 10001 ||
        (file.name !== "manifest.json" &&
          !/^media\/[a-f0-9-]{36}\.bin$/.test(file.name))
      )
        throw Error("The archive contains an unexpected or duplicate file.");
      names.add(file.name);
      const limit =
        file.name === "manifest.json" ? MAX_MANIFEST : 8 * 1024 * 1024;
      if (file.originalSize !== undefined && file.originalSize > limit)
        throw Error("An archive entry exceeds its size bound.");
      let size = 0;
      const chunks = [];
      file.ondata = (error, data, final) => {
        if (error) throw Error("Archive compression is damaged.");
        size += data.length;
        total += data.length;
        if (size > limit || total > MAX_MANIFEST + MAX_MEDIA)
          throw Error("Archive expands beyond its safe size bound.");
        chunks.push(data);
        if (final) files[file.name] = merge(...chunks);
      };
      file.start();
    });
    reader.register(fflate.UnzipInflate);
    for (let offset = 0; offset < zip.length; offset += 8192) {
      reader.push(
        zip.subarray(offset, offset + 8192),
        offset + 8192 >= zip.length,
      );
      if (offset % (1024 * 1024) === 0) {
        progress(offset / zip.length);
        await new Promise((resolve) => setTimeout(resolve, 0));
      }
    }
    if (!files["manifest.json"])
      throw Error("The archive manifest is missing.");
    let manifest;
    try {
      manifest = JSON.parse(decoder.decode(files["manifest.json"]));
    } catch {
      throw Error("The archive manifest is damaged.");
    }
    validate(manifest);
    const allowed = new Set([
      "manifest.json",
      ...manifest.media.map((m) => "media/" + m.id + ".bin"),
    ]);
    if (names.size !== allowed.size || [...names].some((n) => !allowed.has(n)))
      throw Error("The archive file set differs from its manifest.");
    for (const m of manifest.media) {
      const bytes = files["media/" + m.id + ".bin"];
      if (
        !bytes ||
        bytes.length !== m.size ||
        (await digest(bytes)) !== m.sha256
      )
        throw Error("An archive image is incomplete or has a bad checksum.");
    }
    return { manifest, files };
  }
  function download(bytes, name, type = "application/octet-stream") {
    const url = URL.createObjectURL(new Blob([bytes], { type })),
      link = document.createElement("a");
    link.href = url;
    link.download = name;
    document.body.append(link);
    link.click();
    link.remove();
    setTimeout(() => URL.revokeObjectURL(url), 30000);
  }
  function browserDrafts(manifest) {
    for (const [id, d] of state.drafts) {
      manifest.retained.push({
        id: "browser-draft:" + id + ":" + d.baseRevision,
        title: d.title || "Unsubmitted browser draft",
        payload: {
          record: {
            id,
            kind: d.kind || "task",
            revision: d.baseRevision || 0,
            data: Object.fromEntries(
              Object.entries(d).filter(
                ([k]) => !["baseRevision", "kind", "followup"].includes(k),
              ),
            ),
          },
          reason: "Unsubmitted browser draft",
        },
        media_id: null,
      });
    }
    return manifest;
  }
  let leave = null;
  function render(root) {
    const writable = mayWrite();
    let active = true,
      busy = false,
      opened = null,
      preview = null,
      mapping = {},
      reviewed = false;
    const cleanup = () => {
      if (opened) {
        for (const bytes of Object.values(opened.files)) bytes.fill(0);
        opened = null;
      }
      preview = null;
    };
    leave = () => {
      active = false;
      const plan = preview?.plan_id;
      cleanup();
      if (plan) api("/transfer/cancel", { plan_id: plan }).catch(() => {});
      leave = null;
    };
    const status = el(
      "p",
      "transfer-status",
      "Content exports are encrypted. Keep the passphrase so you can open the file later.",
    );
    status.setAttribute("role", "status");
    const columns = el("div", "transfer-columns"),
      exportBox = el("section", "transfer-card"),
      importBox = el("section", "transfer-card");
    root.append(status, columns);
    columns.append(exportBox, importBox);
    exportBox.append(
      el("h2", "", "Export an encrypted copy"),
      el(
        "p",
        "muted",
        "Lists, tasks, notes, events, comments, images and recovered drafts. Pairing keys and accounts stay on this installation.",
      ),
    );
    const passField = input("Export passphrase", "", "password"),
      confirmField = input("Confirm export passphrase", "", "password"),
      pass = passField.node,
      confirm = confirmField.node;
    pass.autocomplete = confirm.autocomplete = "new-password";
    addField(exportBox, passField);
    addField(exportBox, confirmField);
    const exportButton = button(
      "Download encrypted export",
      async () => {
        if (busy) return;
        if (pass.value !== confirm.value)
          throw Error("The export passphrases do not match.");
        const password = pass.value;
        busy = true;
        state.loading = true;
        exportButton.disabled = true;
        status.textContent = "Collecting planner data and attachments…";
        let files;
        try {
          const manifest = browserDrafts(await api("/transfer/export"));
          validate(manifest);
          files = {};
          let done = 0;
          for (const m of manifest.media) {
            const main = manifest.records.some(
                (r) => r.kind === "image" && r.id === m.id,
              ),
              response = await fetch(
                main ? "/api/images/" + m.id : "/api/transfer/media/" + m.id,
                { credentials: "same-origin" },
              );
            if (!response.ok)
              throw Error(
                "An attachment could not be exported. Retry before treating this as a complete backup.",
              );
            const bytes = new Uint8Array(await response.arrayBuffer());
            if (bytes.length !== m.size || (await digest(bytes)) !== m.sha256)
              throw Error(
                "An attachment changed during export. Retry the export.",
              );
            files["media/" + m.id + ".bin"] = bytes;
            status.textContent = `Collected ${++done} of ${manifest.media.length} images…`;
          }
          files["manifest.json"] = encoder.encode(JSON.stringify(manifest));
          if (files["manifest.json"].length > MAX_MANIFEST)
            throw Error(
              "The manifest is larger than the portable limit. Export or resolve recovered drafts before creating this copy.",
            );
          status.textContent = "Encrypting your export…";
          const zip = fflate.zipSync(files, { level: 0 }),
            encrypted = await encrypt(zip, password);
          zip.fill(0);
          if (active) {
            download(
              encrypted,
              "field-notes-" + FieldPlanning.localDay() + ".fieldnotes",
            );
            status.textContent = `Encrypted export downloaded · ${manifest.records.length} items, ${manifest.media.length} images, ${manifest.retained.length} retained changes.`;
            pass.value = confirm.value = "";
          }
        } finally {
          if (files) for (const bytes of Object.values(files)) bytes.fill(0);
          busy = false;
          state.loading = false;
          exportButton.disabled = false;
        }
      },
      "primary",
    );
    exportBox.append(exportButton);
    importBox.append(
      el("h2", "", "Import with a preview"),
      el(
        "p",
        "muted",
        "Existing items are kept. Matching items are skipped; different versions are saved for review instead of overwriting current work.",
      ),
    );
    const fileField = input("Choose a Field Notes file", "", "file"),
      passwordField = input("Archive passphrase", "", "password"),
      file = fileField.node,
      password = passwordField.node;
    file.accept = ".fieldnotes,application/octet-stream";
    password.autocomplete = "current-password";
    addField(importBox, fileField);
    addField(importBox, passwordField);
    const summary = el("section", "import-preview"),
      controls = el("div", "toolbar");
    importBox.append(controls, summary);
    async function action(work) {
      if (busy) return;
      busy = true;
      state.loading = true;
      for (const b of controls.querySelectorAll("button")) b.disabled = true;
      try {
        await work();
      } catch (error) {
        status.textContent = error.message;
        throw error;
      } finally {
        busy = false;
        state.loading = false;
        drawPreview();
      }
    }
    const unlock = button(
      "Unlock and preview",
      () =>
        action(async () => {
          if (!file.files?.[0]) throw Error("Choose a Field Notes file first.");
          if (file.files[0].size > MAX_FILE)
            throw Error("This file exceeds 256 MiB.");
          cleanup();
          status.textContent = "Unlocking archive…";
          const encrypted = new Uint8Array(await file.files[0].arrayBuffer()),
            zip = await decrypt(encrypted, password.value);
          password.value = "";
          encrypted.fill(0);
          try {
            opened = await unpack(
              zip,
              (p) =>
                (status.textContent = `Verifying archive · ${Math.round(p * 100)}%`),
            );
          } finally {
            zip.fill(0);
          }
          status.textContent = "Checking existing items and people…";
          preview = await api("/transfer/prepare", {
            manifest: opened.manifest,
          });
          mapping = Object.fromEntries(
            preview.people.map((p) => [p.id, p.target]),
          );
          reviewed = true;
          status.textContent =
            "Preview ready. Nothing has been added to the workspace.";
        }),
      "primary",
    );
    const review = button("Review again", () =>
      action(async () => {
        preview = await api("/transfer/review", {
          plan_id: preview.plan_id,
          mapping,
        });
        reviewed = true;
        status.textContent =
          "Preview refreshed. Existing data is still unchanged.";
      }),
    );
    const commit = button(
      "Import these items",
      () =>
        action(async () => {
          if (!opened || !preview || !reviewed)
            throw Error("Review this archive before importing.");
          let done = 0,
            total = 0;
          for (const id of preview.needed_media)
            total += Math.ceil(
              opened.manifest.media.find((m) => m.id === id).size / CHUNK,
            );
          for (const id of preview.needed_media) {
            const bytes = opened.files["media/" + id + ".bin"],
              already = preview.uploaded_chunks[id] || [];
            for (
              let index = 0;
              index < Math.ceil(bytes.length / CHUNK);
              index++
            ) {
              if (!already.includes(index)) {
                const chunk = bytes.subarray(
                  index * CHUNK,
                  (index + 1) * CHUNK,
                );
                let binary = "";
                for (let i = 0; i < chunk.length; i += 8192)
                  binary += String.fromCharCode(...chunk.subarray(i, i + 8192));
                await api("/transfer/image", {
                  plan_id: preview.plan_id,
                  image_id: id,
                  index,
                  bytes: btoa(binary),
                });
              }
              status.textContent = `Preparing images · ${++done} of ${total} chunks`;
            }
          }
          status.textContent = "Importing the reviewed items…";
          let result;
          try {
            result = await api("/transfer/commit", {
              plan_id: preview.plan_id,
            });
          } catch (error) {
            if (!error.status)
              result = await api("/transfer/commit", {
                plan_id: preview.plan_id,
              });
            else {
              reviewed = false;
              throw error;
            }
          }
          cleanup();
          await refresh();
          status.textContent = `Import complete · ${result.imported} added, ${result.already_present} already present, ${result.retained_changes} retained for review.`;
          await recovered();
        }),
      "primary",
    );
    const cancel = button("Cancel preview", () =>
      action(async () => {
        if (preview)
          await api("/transfer/cancel", { plan_id: preview.plan_id });
        cleanup();
        status.textContent = "Import canceled. Existing planner data was kept.";
      }),
    );
    controls.append(unlock, review, commit, cancel);
    function drawPreview() {
      summary.replaceChildren();
      unlock.disabled = busy || !writable;
      review.hidden = commit.hidden = cancel.hidden = !preview;
      review.disabled = cancel.disabled = busy;
      commit.disabled = busy || !reviewed;
      if (!preview) return;
      summary.append(
        el("h3", "", "Review the import"),
        el(
          "p",
          "",
          `${preview.new_items} new items · ${preview.already_present} already present · ${preview.retained_changes} saved alternatives / retained changes`,
        ),
      );
      for (const person of preview.people) {
        const box = el("label"),
          node = select(
            "Archived " + person.name,
            (state.people || [])
              .filter((p) => p.can_assign)
              .map((p) => [p.id, p.name]),
            mapping[person.id],
          );
        box.append(node.label, node.node);
        node.node.onchange = () => {
          mapping[person.id] = node.node.value;
          reviewed = false;
          commit.disabled = true;
          status.textContent =
            "People mapping changed. Choose Review again before importing.";
        };
        summary.append(box);
      }
      summary.append(
        el(
          "p",
          "muted",
          "No current items, users, credentials or pairings will be overwritten.",
        ),
      );
    }
    const recoveredBox = el("section", "transfer-card");
    root.append(recoveredBox);
    async function recovered() {
      try {
        const result = await api("/transfer/recovered");
        recoveredBox.replaceChildren(
          el("h2", "", "Recovered drafts and alternatives"),
          el(
            "p",
            "muted",
            "If a task’s former list or project is missing, its recovered copy goes to Inbox. Identical current items are reused.",
          ),
        );
        if (!result.items.length)
          recoveredBox.append(
            el(
              "p",
              "muted",
              "Imported conflicts and offline drafts appear here, ready for review.",
            ),
          );
        for (const item of result.items) {
          const details = el("details"),
            payload = item.payload,
            record = payload.record || {},
            command = payload.command || {},
            kind = record.kind || command.action?.split(".")[0] || "note",
            data = { ...record.data, ...command.data };
          details.append(el("summary", "", item.title || "Recovered change"));
          if (payload.reason) details.append(el("p", "muted", payload.reason));
          for (const key of [
            "title",
            "body",
            "notes",
            "quantity",
            "store",
            "link_url",
            "price",
          ])
            if (data[key])
              details.append(
                el(
                  "p",
                  "recovered-content",
                  ["title", "body", "notes"].includes(key)
                    ? String(data[key])
                    : key.replaceAll("_", " ") + ": " + data[key],
                ),
              );
          let parent = null;
          if (["image", "comment"].includes(kind)) {
            const field = select(
              "Attach to task",
              [
                ["", "Choose a task"],
                ...records("task").map((t) => [t.id, t.data.title]),
              ],
              byId(data.task_id) ? data.task_id : "",
            );
            parent = field.node;
            addField(details, field);
          }
          if (item.has_image)
            details.append(
              el(
                "p",
                "muted",
                "Its attached image is retained and included in encrypted exports.",
              ),
            );
          const restore = button("Restore as a separate item", async () => {
            if (parent && !parent.value)
              throw Error(
                "Choose a task for this attachment or comment first.",
              );
            restore.disabled = true;
            try {
              const result = await api("/transfer/restore", {
                id: item.id,
                parent_id: parent?.value || null,
              });
              await refresh();
              status.textContent = result.already_present
                ? "This recovered change has already been restored. No duplicate was added."
                : "Restored as a separate item. Existing work was kept.";
              restore.textContent = "Restored";
            } finally {
              restore.disabled = false;
            }
          });
          restore.disabled = !writable;
          const remove = button(
            "Remove recovered copy",
            async () => {
              await formDialog(
                "Remove recovered copy?",
                "Current planner items stay. Keep an encrypted export if you still need this alternative; existing backups are unchanged.",
                [],
                async () => {
                  await api("/transfer/discard", { id: item.id });
                  await recovered();
                  status.textContent =
                    "Recovered copy removed. Current planner items were kept.";
                },
                "Remove copy",
              );
            },
            "danger",
          );
          remove.disabled = !writable;
          details.append(restore, remove);
          const technical = el("details"),
            body = el(
              "pre",
              "recovered-content",
              JSON.stringify(payload, null, 2),
            );
          technical.append(el("summary", "", "Full retained details"), body);
          details.append(technical);
          recoveredBox.append(details);
        }
      } catch (error) {
        recoveredBox.append(el("p", "error", error.message));
      }
    }
    drawPreview();
    recovered();
  }
  return {
    render,
    isOpen: () => !!leave,
    leave: () => leave?.(),
    encrypt,
    decrypt,
    unpack,
    validate,
    digest,
    magic,
    MAX_FILE,
  };
})();
