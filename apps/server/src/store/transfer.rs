//! Additive portable imports. Preview/staging is encrypted, commit is one SQLite
//! transaction, and source identities plus exact content matches prevent dupes.
use super::*;
use std::collections::{BTreeSet, HashMap};
mod recovery;
const CHUNK: usize = 128 * 1024;
const MAX_MANIFEST: usize = 24 * 1024 * 1024;
#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Person {
    id: String,
    name: String,
}
#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Media {
    id: String,
    sha256: String,
    size: usize,
}
#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Retained {
    id: String,
    title: String,
    payload: Value,
    #[serde(default)]
    media_id: Option<String>,
}
#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Manifest {
    format: String,
    version: u32,
    source: String,
    generation: i64,
    created_at: i64,
    records: Vec<Record>,
    people: Vec<Person>,
    media: Vec<Media>,
    #[serde(default)]
    retained: Vec<Retained>,
}
struct Entry {
    source: Record,
    target: Option<String>,
    data: Value,
    new: bool,
    recover: bool,
}
struct Plan {
    entries: Vec<Entry>,
    mapping: BTreeMap<String, String>,
    needed: BTreeSet<String>,
    new: usize,
    skipped: usize,
    recovered: usize,
}
struct Job {
    actor: Actor,
    manifest: Manifest,
    mapping: BTreeMap<String, String>,
    epoch: String,
    generation: i64,
    sequence: i64,
    expires: i64,
}

fn allowed(kind: &str) -> &'static [&'static str] {
    match kind {
        "project" => &["title", "notes", "category"],
        "list" => &[
            "title",
            "notes",
            "category",
            "template",
            "fields",
            "perpetual",
            "notify_additions",
            "item_reminders",
        ],
        "note" => &[
            "title",
            "body",
            "pinned",
            "category",
            "on_radar",
            "remind_at",
            "reminders",
            "reminder_options",
        ],
        "event" => &[
            "title",
            "notes",
            "category",
            "all_day",
            "start_date",
            "end_date",
            "starts_at",
            "ends_at",
            "recurrence",
            "reminders",
            "reminder_options",
        ],
        "task" => &[
            "title",
            "category",
            "notes",
            "project_id",
            "quadrant",
            "due",
            "priority",
            "planned_for",
            "estimate_minutes",
            "assignee",
            "pinned",
            "blocked",
            "quantity",
            "store",
            "link_url",
            "price",
            "currency",
            "reminders",
            "reminder_options",
        ],
        "comment" => &["task_id", "body"],
        "image" => &["task_id", "name"],
        _ => &[],
    }
}
fn content(
    record: &Record,
    parent: Option<&str>,
    people: &BTreeMap<String, String>,
    fallback: &str,
) -> Value {
    let mut data: serde_json::Map<String, Value> = record
        .data
        .as_object()
        .unwrap()
        .iter()
        .filter(|(k, _)| allowed(&record.kind).contains(&k.as_str()))
        .map(|(k, v)| (k.clone(), v.clone()))
        .collect();
    if let Some(parent) =
        parent.filter(|_| matches!(record.kind.as_str(), "task" | "comment" | "image"))
    {
        data.insert(
            if record.kind == "task" {
                "project_id"
            } else {
                "task_id"
            }
            .into(),
            json!(parent),
        );
    }
    if record.kind == "task" && data.get("project_id").is_some_and(Value::is_null) {
        data.insert("project_id".into(), json!(""));
    }
    if let Some(id) = data
        .get("assignee")
        .and_then(Value::as_str)
        .filter(|s| !s.is_empty())
        .map(str::to_owned)
    {
        data.insert(
            "assignee".into(),
            json!(people.get(&id).map(String::as_str).unwrap_or(fallback)),
        );
    }
    if let Some(rules) = record.data.get("reminder_rules") {
        data.insert("reminders".into(), rules.clone());
    }
    // Preserve legacy assigned-task delivery, rather than turning an imported
    // personal task into a new Everyone reminder. An unassigned "assigned or
    // creator" reminder must keep its mapped original creator, not the importer.
    let assignee = data
        .get("assignee")
        .and_then(Value::as_str)
        .filter(|s| !s.is_empty());
    if record.kind == "task" && !data.contains_key("reminder_options") && assignee.is_some() {
        data.insert(
            "reminder_options".into(),
            json!({"audience":{"mode":"assigned","people":[]},"playback":{}}),
        );
    }
    if matches!(record.kind.as_str(), "task" | "event" | "note")
        && data
            .get("reminder_options")
            .and_then(|v| v.get("audience"))
            .and_then(|v| v.get("mode"))
            .and_then(Value::as_str)
            == Some("assigned")
        && data
            .get("assignee")
            .and_then(Value::as_str)
            .is_none_or(str::is_empty)
    {
        let creator = record
            .data
            .get("created_by")
            .or_else(|| record.data.get("author_id"))
            .and_then(Value::as_str)
            .unwrap_or(fallback);
        let target = people.get(creator).map(String::as_str).unwrap_or(fallback);
        data.get_mut("reminder_options").unwrap()["audience"] =
            json!({"mode":"people","people":[target]});
    }
    if let Some(ids) = data
        .get_mut("reminder_options")
        .and_then(|v| v.get_mut("audience"))
        .and_then(|v| v.get_mut("people"))
        .and_then(Value::as_array_mut)
    {
        let mut seen = BTreeSet::new();
        *ids = ids
            .iter()
            .filter_map(|id| id.as_str())
            .map(|id| people.get(id).map(String::as_str).unwrap_or(fallback))
            .filter(|id| seen.insert((*id).to_owned()))
            .map(|id| json!(id))
            .collect();
    }
    Value::Object(data)
}
fn content_types(record: &Record) -> Result<()> {
    for (key, value) in record.data.as_object().unwrap() {
        let valid = match key.as_str() {
            "title" | "notes" | "body" | "quantity" | "store" | "link_url" | "price"
            | "currency" | "due" | "planned_for" | "quadrant" | "category" | "template"
            | "start_date" | "end_date" | "name" => value.is_string(),
            "pinned" | "blocked" | "all_day" | "perpetual" | "notify_additions"
            | "item_reminders" | "on_radar" => value.is_boolean(),
            "priority" | "estimate_minutes" => value.as_i64().is_some(),
            "starts_at" | "ends_at" | "remind_at" => value.is_null() || value.as_i64().is_some(),
            "assignee" | "project_id" | "task_id" => value.is_null() || value.is_string(),
            "fields" => value
                .as_array()
                .is_some_and(|values| values.iter().all(Value::is_string)),
            "recurrence" | "reminder_rules" | "reminder_options" => {
                value.is_null() || value.is_object()
            }
            "reminders" => value.is_array() || value.is_object() || value.is_null(),
            _ => true,
        };
        if !valid {
            return fail(400,"An archive item has an unsupported field type; its original file and current data are unchanged");
        }
    }
    Ok(())
}
fn semantic(record: &Record, data: &Value, media: Option<&str>) -> String {
    let mut d = data.clone();
    // Defaults describe content, without treating timestamps or signing identities
    // as content. Exact matches only: similar titles with different text are kept.
    let defaults = match record.kind.as_str() {
        "task" => {
            json!({"notes":"","project_id":"","quadrant":"do","due":"","priority":3,"planned_for":"","estimate_minutes":0,"assignee":null,"pinned":false,"blocked":false,"quantity":"","store":"","link_url":"","price":"","currency":"USD","category":"general"})
        }
        "list" => {
            json!({"notes":"","category":"general","template":"general","perpetual":true,"notify_additions":true,"item_reminders":true})
        }
        "project" => json!({"notes":"","category":"general"}),
        "note" => {
            json!({"body":"","pinned":false,"category":"general","on_radar":false,"remind_at":0})
        }
        _ => json!({"notes":"","category":"general","recurrence":null}),
    };
    for (k, v) in defaults.as_object().unwrap() {
        if d.get(k).is_none() {
            d[k] = v.clone();
        }
    }
    if record.kind == "task" {
        if d["project_id"].as_str().is_none_or(str::is_empty) {
            d["project_id"] = json!("");
        }
        if d["assignee"].as_str().is_some_and(str::is_empty) {
            d["assignee"] = Value::Null;
        }
        // Working claims are live server state. Completion is portable content;
        // an open task remains the same content when claimed or released.
        d["status"] = json!(if record.data["status"] == "done" {
            "done"
        } else {
            "todo"
        });
    }
    if record.kind == "image" {
        d["media_sha256"] = json!(media);
    }
    if record.kind == "comment" {
        d["original_author"] = record.data["imported_from"]
            .get("author")
            .cloned()
            .unwrap_or_else(|| {
                record
                    .data
                    .get("created_by_name")
                    .cloned()
                    .unwrap_or(Value::Null)
            });
    }
    if d.get("reminders").is_none() {
        d["reminders"] = Value::Null;
    }
    if d.get("reminder_options").is_none() {
        d["reminder_options"] = json!({"audience":{"mode":"everyone","people":[]},"playback":{}});
    }
    crypto::digest(&serde_json::to_vec(&json!({"kind":record.kind,"data":d})).unwrap())
}
impl Store {
    pub(super) fn transfer_schema(&self) -> Result<()> {
        self.db.execute_batch("CREATE TABLE IF NOT EXISTS transfer_receipts(id TEXT PRIMARY KEY,user_id TEXT NOT NULL,session_id TEXT NOT NULL,epoch TEXT NOT NULL,generation INTEGER NOT NULL,data BLOB NOT NULL);
 CREATE TABLE IF NOT EXISTS transfer_maps(generation INTEGER NOT NULL,source TEXT NOT NULL,source_generation INTEGER NOT NULL,source_id TEXT NOT NULL,target TEXT,PRIMARY KEY(generation,source,source_generation,source_id));
   CREATE TABLE IF NOT EXISTS transfer_plans(id TEXT PRIMARY KEY,user_id TEXT NOT NULL,session_id TEXT NOT NULL,epoch TEXT NOT NULL,generation INTEGER NOT NULL,sequence INTEGER NOT NULL,expires INTEGER NOT NULL,data BLOB NOT NULL);
   CREATE TABLE IF NOT EXISTS transfer_parts(plan_id TEXT NOT NULL REFERENCES transfer_plans(id) ON DELETE CASCADE,image_id TEXT NOT NULL,chunk INTEGER NOT NULL,data BLOB NOT NULL,PRIMARY KEY(plan_id,image_id,chunk));
   CREATE TABLE IF NOT EXISTS transfer_recovered(id TEXT PRIMARY KEY,user_id TEXT NOT NULL,source TEXT NOT NULL,source_id TEXT NOT NULL,data BLOB NOT NULL,media BLOB);
   CREATE TABLE IF NOT EXISTS transfer_restored(generation INTEGER NOT NULL,recovery_id TEXT NOT NULL,target TEXT NOT NULL,PRIMARY KEY(generation,recovery_id));
   CREATE INDEX IF NOT EXISTS transfer_recovered_source ON transfer_recovered(source,source_id);")?;
        Ok(())
    }
    fn archive_manifest(value: &Value) -> Result<Manifest> {
        if serde_json::to_vec(value)
            .map_err(|_| Fault(400, "Invalid archive".into()))?
            .len()
            > MAX_MANIFEST
        {
            return fail(
                413,
                "Archive manifest exceeds 24 MiB; export a smaller selection",
            );
        }
        let m: Manifest = serde_json::from_value(value.clone())
            .map_err(|_| Fault(400, "Unsupported or malformed Field Notes archive".into()))?;
        if m.format != "org.fieldnotes.archive"
            || m.version != 1
            || m.generation < 1
            || m.records.len() > 10000
            || m.people.len() > 64
            || m.retained.len() > 2000
            || m.media.len() > 10000
        {
            return fail(400, "Unsupported archive version or capacity");
        }
        valid_id(&m.source)?;
        let mut ids = BTreeSet::new();
        for r in &m.records {
            valid_id(&r.id)?;
            if !ids.insert(&r.id)
                || allowed(&r.kind).is_empty()
                || !r.data.is_object()
                || r.data["deleted"] == true
            {
                return fail(400, "Duplicate, deleted or unsupported archive item");
            }
            content_types(r)?;
            for key in r.data.as_object().unwrap().keys() {
                if !allowed(&r.kind).contains(&key.as_str())
                    && !matches!(
                        key.as_str(),
                        "created_at"
                            | "created_by"
                            | "created_by_name"
                            | "created_sequence"
                            | "updated_at"
                            | "updated_by"
                            | "updated_by_name"
                            | "last_activity_at"
                            | "last_activity_by"
                            | "last_activity_by_name"
                            | "author"
                            | "author_id"
                            | "status"
                            | "claimant"
                            | "completed_by"
                            | "completed_at"
                            | "width"
                            | "height"
                            | "size"
                            | "original_sha256"
                            | "reminder_rules"
                            | "imported_from"
                            | "recovery_incarnation"
                    )
                {
                    return fail(400,"An archive item contains newer fields; update Field Notes before importing it");
                }
            }
        }
        let mut people = BTreeSet::new();
        for p in &m.people {
            valid_id(&p.id)?;
            if !people.insert(&p.id) || p.name.len() > 240 {
                return fail(400, "Invalid archive people directory");
            }
        }
        let mut media = BTreeSet::new();
        let mut bytes = 0usize;
        for image in &m.media {
            valid_id(&image.id)?;
            bytes = bytes.saturating_add(image.size);
            if !media.insert(&image.id)
                || image.size == 0
                || image.size > 8 * 1024 * 1024
                || image.sha256.len() != 64
                || !image
                    .sha256
                    .bytes()
                    .all(|c| c.is_ascii_digit() || (b'a'..=b'f').contains(&c))
            {
                return fail(400, "Invalid archive image descriptor");
            }
        }
        if bytes > 192 * 1024 * 1024 {
            return fail(413, "Archive images exceed 192 MiB");
        }
        let mut recovered = BTreeSet::new();
        for r in &m.retained {
            if r.id.is_empty()
                || r.id.len() > 200
                || !recovered.insert(&r.id)
                || r.title.len() > 240
                || r.media_id.as_ref().is_some_and(|id| !media.contains(id))
            {
                return fail(400, "Invalid retained archive change");
            }
        }
        if m.records
            .iter()
            .any(|r| r.kind == "image" && !media.contains(&r.id))
        {
            return fail(400, "An archive image is missing its bytes descriptor");
        }
        if media.iter().any(|id| {
            !m.records.iter().any(|r| r.kind == "image" && &r.id == *id)
                && !m.retained.iter().any(|r| r.media_id.as_ref() == Some(*id))
        }) {
            return fail(400, "Unreferenced archive image");
        }
        Ok(m)
    }
    pub fn transfer_export(&self, token: &str) -> Result<Value> {
        self.transfer_schema()?;
        let actor = self.actor(token)?;
        let snapshot = self.snapshot(actor.clone())?;
        let mut records = Vec::new();
        let mut media = Vec::new();
        for projected in snapshot.records {
            let mut record = self.record(&projected.id)?;
            record
                .data
                .as_object_mut()
                .ok_or(Fault(500, "Invalid export record".into()))?
                .remove("recovery_incarnation");
            if record.kind == "image" {
                let bytes = self.image(&record.id)?;
                media.push(Media {
                    id: record.id.clone(),
                    size: bytes.len(),
                    sha256: crypto::digest(&bytes),
                });
            }
            records.push(record);
        }
        let people = self.people(token)?["people"]
            .as_array()
            .unwrap()
            .iter()
            .map(|p| Person {
                id: p["id"].as_str().unwrap().into(),
                name: p["name"].as_str().unwrap().into(),
            })
            .collect();
        let mut retained = Vec::new();
        let mut statement = self.db.prepare(
            "SELECT id,data,media FROM transfer_recovered WHERE user_id=? OR ? ORDER BY rowid",
        )?;
        let rows = statement.query_map(params![actor.user_id, actor.admin()], |r| {
            Ok((
                r.get::<_, String>(0)?,
                r.get::<_, Vec<u8>>(1)?,
                r.get::<_, Option<Vec<u8>>>(2)?,
            ))
        })?;
        for row in rows {
            let (id, data, blob) = row?;
            let mut item: Retained =
                serde_json::from_slice(&self.vault.open(&format!("recovered:{id}"), &data)?)
                    .map_err(|_| Fault(500, "Recovered archive data is invalid".into()))?;
            item.id = id.clone();
            if let Some(blob) = blob {
                let bytes = self.vault.open(&format!("recovered-image:{id}"), &blob)?;
                media.push(Media {
                    id: id.clone(),
                    size: bytes.len(),
                    sha256: crypto::digest(&bytes),
                });
                item.media_id = Some(id);
            }
            retained.push(item);
        }
        serde_json::to_value(Manifest {
            format: "org.fieldnotes.archive".into(),
            version: 1,
            source: snapshot.epoch,
            generation: snapshot.generation,
            created_at: now(),
            records,
            people,
            media,
            retained,
        })
        .map_err(|_| Fault(500, "Archive serialization failed".into()))
    }
    pub fn transfer_recovered_media(&self, token: &str, id: &str) -> Result<Vec<u8>> {
        self.transfer_schema()?;
        let actor = self.actor(token)?;
        valid_id(id)?;
        let blob: Option<Vec<u8>> = self
            .db
            .query_row(
                "SELECT media FROM transfer_recovered WHERE id=? AND (user_id=? OR ?)",
                params![id, actor.user_id, actor.admin()],
                |r| r.get(0),
            )
            .optional()?
            .flatten();
        self.vault
            .open(
                &format!("recovered-image:{id}"),
                &blob.ok_or(Fault(404, "Recovered image unavailable".into()))?,
            )
            .map_err(Into::into)
    }
    fn recovered_exists(&self, source: &str, item: &Retained) -> Result<bool> {
        let mut statement = self
            .db
            .prepare("SELECT id,data FROM transfer_recovered WHERE source=? AND source_id=?")?;
        let rows = statement.query_map(params![source, item.id], |r| {
            Ok((r.get::<_, String>(0)?, r.get::<_, Vec<u8>>(1)?))
        })?;
        for row in rows {
            let (id, bytes) = row?;
            let prior: Retained =
                serde_json::from_slice(&self.vault.open(&format!("recovered:{id}"), &bytes)?)
                    .map_err(|_| Fault(500, "Recovered change is invalid".into()))?;
            if prior.payload == item.payload && prior.media_id == item.media_id {
                return Ok(true);
            }
        }
        Ok(false)
    }
    fn recovery(record: &Record, manifest: &Manifest) -> Retained {
        Retained {
            id: format!("record:{}", record.id),
            title: record.data["title"]
                .as_str()
                .or_else(|| record.data["name"].as_str())
                .unwrap_or("Recovered item")
                .chars()
                .take(240)
                .collect(),
            payload: json!({"record":record,"media_sha256":manifest.media.iter().find(|m|m.id==record.id).map(|m|&m.sha256)}),
            media_id: (record.kind == "image").then(|| record.id.clone()),
        }
    }
    fn make_plan(
        &self,
        actor: &Actor,
        m: &Manifest,
        requested: &BTreeMap<String, String>,
    ) -> Result<Plan> {
        let (epoch, generation, _) = self.meta()?;
        let active: Vec<(String, String)> = self
            .db
            .prepare("SELECT id,name FROM users WHERE active=1 AND role!='viewer'")?
            .query_map([], |r| Ok((r.get(0)?, r.get(1)?)))?
            .collect::<std::result::Result<_, _>>()?;
        let mut mapping: BTreeMap<String, String> = active
            .iter()
            .map(|(id, _)| (id.clone(), id.clone()))
            .collect();
        for p in &m.people {
            let found = requested
                .get(&p.id)
                .cloned()
                .or_else(|| {
                    active
                        .iter()
                        .find(|(id, _)| id == &p.id)
                        .map(|(id, _)| id.clone())
                })
                .or_else(|| {
                    active
                        .iter()
                        .find(|(_, name)| name.eq_ignore_ascii_case(&p.name))
                        .map(|(id, _)| id.clone())
                })
                .unwrap_or_else(|| actor.user_id.clone());
            if !active.iter().any(|(id, _)| id == &found) {
                return fail(400, "Map archived people to active household members");
            }
            mapping.insert(p.id.clone(), found);
        }
        let identity: BTreeMap<_, _> = active
            .iter()
            .map(|(id, _)| (id.clone(), id.clone()))
            .collect();
        let current = self.snapshot(actor.clone())?.records;
        let mut index = HashMap::new();
        for r in &current {
            let raw = self.record(&r.id)?;
            let data = content(&raw, None, &identity, &actor.user_id);
            let hash = if raw.kind == "image" {
                Some(crypto::digest(&self.image(&raw.id)?))
            } else {
                None
            };
            index
                .entry(semantic(&raw, &data, hash.as_deref()))
                .or_insert_with(|| raw.id.clone());
            // Upload normalization changes PNG encoding, not the imported
            // content. Bind the original digest when the image was created;
            // only this trusted target metadata supplies the alternate match.
            if raw.kind == "image" {
                if let Some(original) = raw.data["original_sha256"].as_str() {
                    index
                        .entry(semantic(&raw, &data, Some(original)))
                        .or_insert_with(|| raw.id.clone());
                }
            }
        }
        let mut ordered = m.records.clone();
        ordered.sort_by_key(|r| match r.kind.as_str() {
            "project" | "list" => 0,
            "note" | "event" => 1,
            "task" => 2,
            _ => 3,
        });
        let mut references: BTreeMap<String, Option<String>> = BTreeMap::new();
        let mut entries = Vec::new();
        let mut needed = BTreeSet::new();
        let (mut new, mut skipped, mut recovered) = (0, 0, 0);
        let origin = format!("{}:{}", m.source, m.generation);
        for source in ordered {
            let key = if source.kind == "task" {
                "project_id"
            } else if matches!(source.kind.as_str(), "comment" | "image") {
                "task_id"
            } else {
                ""
            };
            let original_parent = source.data[key].as_str().unwrap_or("");
            let parent = if original_parent.is_empty() {
                Some(String::new())
            } else if let Some(value) = references.get(original_parent) {
                value.clone()
            } else {
                return fail(
                    400,
                    "An archive item refers to a missing parent; export its list/project too",
                );
            };
            let data = content(&source, parent.as_deref(), &mapping, &actor.user_id);
            let hash = m
                .media
                .iter()
                .find(|v| v.id == source.id)
                .map(|v| v.sha256.as_str());
            let comparable = semantic(&source, &data, hash);
            let mapped:Option<Option<String>>=self.db.query_row("SELECT target FROM transfer_maps WHERE generation=? AND source=? AND source_generation=? AND source_id=?",params![generation,m.source,m.generation,source.id],|r|r.get(0)).optional()?;
            let linked = if let Some(target) = mapped {
                Some(target)
            } else if m.source == epoch
                && m.generation == generation
                && self.db.query_row(
                    "SELECT EXISTS(SELECT 1 FROM objects WHERE id=?)",
                    [&source.id],
                    |r| r.get::<_, bool>(0),
                )?
            {
                Some(Some(source.id.clone()))
            } else {
                None
            };
            let (mut target, mut create, mut recover) = (None, false, false);
            if let Some(linked) = linked {
                target = linked;
                skipped += 1;
                let existing = target
                    .as_ref()
                    .and_then(|id| current.iter().find(|r| &r.id == id));
                recover = existing.is_none_or(|r| {
                    let canonical = content(r, None, &identity, &actor.user_id);
                    let old = if r.kind == "image" {
                        self.image(&r.id).ok().map(|b| crypto::digest(&b))
                    } else {
                        None
                    };
                    let matches = semantic(r, &canonical, old.as_deref()) == comparable
                        || r.kind == "image"
                            && r.data["original_sha256"].as_str().is_some_and(|original| {
                                semantic(r, &canonical, Some(original)) == comparable
                            });
                    r.kind != source.kind || !matches
                });
                if existing.is_none() {
                    target = None;
                }
            } else if let Some(id) = index.get(&comparable) {
                target = Some(id.clone());
                skipped += 1;
            } else if parent.is_none() {
                recover = true;
                skipped += 1;
            } else {
                let id = id();
                index.insert(comparable, id.clone());
                target = Some(id);
                create = true;
                new += 1;
            }
            references.insert(source.id.clone(), target.clone());
            if recover && !self.recovered_exists(&origin, &Self::recovery(&source, m))? {
                recovered += 1;
            }
            if source.kind == "image" && (create || recover) {
                needed.insert(source.id.clone());
            }
            entries.push(Entry {
                source,
                target,
                data,
                new: create,
                recover,
            });
        }
        for item in &m.retained {
            if !self.recovered_exists(&origin, item)? {
                recovered += 1;
            }
            if let Some(id) = &item.media_id {
                needed.insert(id.clone());
            }
        }
        Ok(Plan {
            entries,
            mapping,
            needed,
            new,
            skipped,
            recovered,
        })
    }
    fn plan_report(&self, id: &str, m: &Manifest, plan: &Plan, expires: i64) -> Result<Value> {
        let people = m
            .people
            .iter()
            .map(|p| json!({"id":p.id,"name":p.name,"target":plan.mapping.get(&p.id)}))
            .collect::<Vec<_>>();
        let mut uploaded = BTreeMap::new();
        for image in &plan.needed {
            let chunks:Vec<i64>=self.db.prepare("SELECT chunk FROM transfer_parts WHERE plan_id=? AND image_id=? ORDER BY chunk")?.query_map(params![id,image],|r|r.get(0))?.collect::<std::result::Result<_,_>>()?;
            uploaded.insert(image, chunks);
        }
        Ok(
            json!({"plan_id":id,"new_items":plan.new,"already_present":plan.skipped,"retained_changes":plan.recovered,"needed_media":plan.needed,"uploaded_chunks":uploaded,"people":people,"expires":expires,"chunk_size":CHUNK}),
        )
    }
    pub fn transfer_prepare(&mut self, token: &str, input: &Value) -> Result<Value> {
        self.transfer_schema()?;
        let actor = self.actor(token)?;
        if actor.role == "viewer" {
            return fail(403, "Read-only household members cannot import data");
        }
        fields(input, &["manifest", "mapping"])?;
        let m = Self::archive_manifest(&input["manifest"])?;
        let requested: BTreeMap<String, String> =
            serde_json::from_value(input.get("mapping").cloned().unwrap_or(json!({})))
                .map_err(|_| Fault(400, "Invalid people mapping".into()))?;
        self.db
            .execute("DELETE FROM transfer_plans WHERE expires<?", [now()])?;
        let count: i64 = self
            .db
            .query_row("SELECT COUNT(*) FROM transfer_plans", [], |r| r.get(0))?;
        if count >= 4 {
            return fail(
                429,
                "Finish or cancel an earlier import before preparing another",
            );
        }
        let plan = self.make_plan(&actor, &m, &requested)?;
        self.validate_plan(token, &m, &plan)?;
        let (epoch, generation, sequence) = self.meta()?;
        let id = id();
        let expires = now() + 1200;
        let bytes = self.vault.seal(
            &format!("import-plan:{id}"),
            &serde_json::to_vec(&json!({"manifest":m,"mapping":plan.mapping})).unwrap(),
        )?;
        self.db.execute(
            "INSERT INTO transfer_plans VALUES(?,?,?,?,?,?,?,?)",
            params![
                id,
                actor.user_id,
                actor.session_id,
                epoch,
                generation,
                sequence,
                expires,
                bytes
            ],
        )?;
        self.plan_report(&id, &m, &plan, expires)
    }
    fn load_import(&self, token: &str, id: &str) -> Result<Job> {
        self.transfer_schema()?;
        valid_id(id)?;
        let actor = self.actor(token)?;
        if actor.role == "viewer" {
            return fail(403, "Read-only household members cannot import data");
        }
        let row:Option<(String,i64,i64,i64,Vec<u8>)>=self.db.query_row("SELECT epoch,generation,sequence,expires,data FROM transfer_plans WHERE id=? AND user_id=? AND session_id=?",params![id,actor.user_id,actor.session_id],|r|Ok((r.get(0)?,r.get(1)?,r.get(2)?,r.get(3)?,r.get(4)?))).optional()?;
        let (epoch, generation, sequence, expires, bytes) = row.ok_or(Fault(
            404,
            "Import preview expired or belongs to another session. Choose the archive again".into(),
        ))?;
        if expires < now() {
            return fail(
                410,
                "Import preview expired; preview the file again. Existing data was not changed",
            );
        }
        let (current, generation_now, _) = self.meta()?;
        if current != epoch || generation_now != generation {
            return fail(
                409,
                "Workspace was cleared or restored. Preview this archive again before importing",
            );
        }
        let value: Value =
            serde_json::from_slice(&self.vault.open(&format!("import-plan:{id}"), &bytes)?)
                .map_err(|_| Fault(500, "Import preview is invalid".into()))?;
        Ok(Job {
            actor,
            manifest: Self::archive_manifest(&value["manifest"])?,
            mapping: serde_json::from_value(value["mapping"].clone())
                .map_err(|_| Fault(500, "Import mapping is invalid".into()))?,
            epoch,
            generation,
            sequence,
            expires,
        })
    }
    pub fn transfer_review(&mut self, token: &str, input: &Value) -> Result<Value> {
        fields(input, &["plan_id", "mapping"])?;
        let id = text(input, "plan_id", 36)?;
        let job = self.load_import(token, &id)?;
        let requested = serde_json::from_value(
            input
                .get("mapping")
                .cloned()
                .unwrap_or(serde_json::to_value(job.mapping).unwrap()),
        )
        .map_err(|_| Fault(400, "Invalid people mapping".into()))?;
        let plan = self.make_plan(&job.actor, &job.manifest, &requested)?;
        self.validate_plan(token, &job.manifest, &plan)?;
        let (_, _, sequence) = self.meta()?;
        let data = self.vault.seal(
            &format!("import-plan:{id}"),
            &serde_json::to_vec(&json!({"manifest":job.manifest,"mapping":plan.mapping})).unwrap(),
        )?;
        self.db.execute(
            "UPDATE transfer_plans SET sequence=?,data=? WHERE id=?",
            params![sequence, data, id],
        )?;
        self.plan_report(&id, &job.manifest, &plan, job.expires)
    }
    pub fn transfer_image(&mut self, token: &str, input: &Value) -> Result<Value> {
        fields(input, &["plan_id", "image_id", "index", "bytes"])?;
        let plan = text(input, "plan_id", 36)?;
        let id = text(input, "image_id", 36)?;
        let job = self.load_import(token, &plan)?;
        let descriptor = job
            .manifest
            .media
            .iter()
            .find(|m| m.id == id)
            .ok_or(Fault(400, "Image is not part of this archive".into()))?;
        let index = input["index"]
            .as_u64()
            .ok_or(Fault(400, "Invalid image chunk".into()))? as usize;
        let offset = index
            .checked_mul(CHUNK)
            .filter(|n| *n < descriptor.size)
            .ok_or(Fault(400, "Invalid image chunk".into()))?;
        let encoded = input["bytes"]
            .as_str()
            .ok_or(Fault(400, "Missing image chunk".into()))?;
        if encoded.len() > CHUNK * 4 / 3 + 8 {
            return fail(413, "Image chunk exceeds its bound");
        }
        let bytes = STANDARD
            .decode(encoded)
            .map_err(|_| Fault(400, "Invalid image chunk encoding".into()))?;
        if bytes.len() != CHUNK.min(descriptor.size - offset) {
            return fail(400, "Image chunk has the wrong length");
        }
        let aad = format!("import-part:{plan}:{id}:{index}");
        let old: Option<Vec<u8>> = self
            .db
            .query_row(
                "SELECT data FROM transfer_parts WHERE plan_id=? AND image_id=? AND chunk=?",
                params![plan, id, index],
                |r| r.get(0),
            )
            .optional()?;
        if let Some(old) = old {
            if self.vault.open(&aad, &old)? != bytes {
                return fail(
                    409,
                    "A different image chunk already exists. Choose the original archive again",
                );
            }
        } else {
            let used: i64 = self.db.query_row(
                "SELECT COALESCE(SUM(length(data)),0) FROM transfer_parts",
                [],
                |r| r.get(0),
            )?;
            if used + bytes.len() as i64 > 256 * 1024 * 1024 {
                return fail(
                    413,
                    "Import staging is full. Cancel an earlier import or retry after it expires",
                );
            }
            let encrypted = self.vault.seal(&aad, &bytes)?;
            self.db.execute(
                "INSERT INTO transfer_parts VALUES(?,?,?,?)",
                params![plan, id, index, encrypted],
            )?;
        }
        Ok(json!({"accepted":true,"image_id":id,"index":index}))
    }
    fn import_image(&self, plan: &str, image: &Media) -> Result<Vec<u8>> {
        let mut bytes = Vec::with_capacity(image.size);
        for index in 0..image.size.div_ceil(CHUNK) {
            let part: Option<Vec<u8>> = self
                .db
                .query_row(
                    "SELECT data FROM transfer_parts WHERE plan_id=? AND image_id=? AND chunk=?",
                    params![plan, image.id, index],
                    |r| r.get(0),
                )
                .optional()?;
            let part = part.ok_or(Fault(
                409,
                "An archive image is incomplete. Retry the upload before importing".into(),
            ))?;
            bytes.extend(
                self.vault
                    .open(&format!("import-part:{plan}:{}:{index}", image.id), &part)?,
            );
        }
        if bytes.len() != image.size || crypto::digest(&bytes) != image.sha256 {
            return fail(
                400,
                "Archive image checksum failed. No planner data was imported",
            );
        }
        Ok(bytes)
    }
    fn store_recovered(
        &self,
        actor: &Actor,
        origin: &str,
        item: &Retained,
        bytes: Option<&[u8]>,
    ) -> Result<()> {
        if self.recovered_exists(origin, item)? {
            return Ok(());
        }
        let normalized = bytes
            .map(|b| media::normalize(b, 8 * 1024 * 1024))
            .transpose()?;
        let bytes = normalized.as_ref().map(|image| image.bytes.as_slice());
        let used:i64=self.db.query_row("SELECT COALESCE(SUM(length(data)+COALESCE(length(media),0)),0) FROM transfer_recovered",[],|r|r.get(0))?;
        let clear = serde_json::to_vec(item).unwrap();
        let text_used: i64 = self.db.query_row(
            "SELECT COALESCE(SUM(length(data)),0) FROM transfer_recovered",
            [],
            |r| r.get(0),
        )?;
        if text_used + clear.len() as i64 + 64 > 8 * 1024 * 1024 {
            return fail(413,"Recovered draft text is full; export and remove reviewed copies before importing more");
        }
        if used + clear.len() as i64 + bytes.map_or(0, |b| b.len()) as i64 > 64 * 1024 * 1024 {
            return fail(
                413,
                "Recovered-change storage is full; export and review it before importing more",
            );
        }
        let id = id();
        let data = self.vault.seal(&format!("recovered:{id}"), &clear)?;
        let media = bytes
            .map(|b| self.vault.seal(&format!("recovered-image:{id}"), b))
            .transpose()?;
        self.db.execute(
            "INSERT INTO transfer_recovered VALUES(?,?,?,?,?,?)",
            params![id, actor.user_id, origin, item.id, data, media],
        )?;
        Ok(())
    }
    fn execute_import(
        &self,
        token: &str,
        m: &Manifest,
        plan: &Plan,
        staging: Option<&str>,
    ) -> Result<()> {
        let actor = self.actor(token)?;
        let (epoch, generation, _) = self.meta()?;
        let origin = format!("{}:{}", m.source, m.generation);
        let mut tiny = Cursor::new(Vec::new());
        image::DynamicImage::new_rgba8(1, 1)
            .write_to(&mut tiny, image::ImageFormat::Png)
            .map_err(|_| Fault(500, "Image validator unavailable".into()))?;
        for entry in &plan.entries {
            let image = if entry.source.kind == "image" && (entry.new || entry.recover) {
                Some(if let Some(staging) = staging {
                    self.import_image(
                        staging,
                        m.media.iter().find(|v| v.id == entry.source.id).unwrap(),
                    )?
                } else {
                    tiny.get_ref().clone()
                })
            } else {
                None
            };
            if entry.new {
                let target = entry.target.as_ref().unwrap();
                let mut data = entry.data.clone();
                if let Some(bytes) = &image {
                    data["bytes"] = json!(STANDARD.encode(bytes));
                }
                let command = Command {
                    operation_id: id(),
                    epoch: epoch.clone(),
                    generation,
                    action: format!("{}.create", entry.source.kind),
                    target: target.clone(),
                    expected_revision: None,
                    data,
                };
                self.command_inner(token, &command, true)?;
                if entry.source.kind == "task" && entry.source.data["status"] == "done" {
                    let command = Command {
                        operation_id: id(),
                        epoch: epoch.clone(),
                        generation,
                        action: "task.complete".into(),
                        target: target.clone(),
                        expected_revision: Some(1),
                        data: json!({}),
                    };
                    self.command_inner(token, &command, true)?;
                }
                let mut record = self.record(target)?;
                record.data["imported_from"] = json!({"source":m.source,"id":entry.source.id,"author":entry.source.data.get("created_by_name").or_else(||entry.source.data.get("author")),"created_at":entry.source.data.get("created_at"),"status":entry.source.data.get("status")});
                self.save(&record, None)?;
            }
            if staging.is_some() {
                self.db.execute(
                    "INSERT OR IGNORE INTO transfer_maps VALUES(?,?,?,?,?)",
                    params![
                        generation,
                        m.source,
                        m.generation,
                        entry.source.id,
                        entry.target
                    ],
                )?;
                if entry.recover {
                    self.store_recovered(
                        &actor,
                        &origin,
                        &Self::recovery(&entry.source, m),
                        image.as_deref(),
                    )?;
                }
            }
        }
        if let Some(staging) = staging {
            for item in &m.retained {
                let bytes = item
                    .media_id
                    .as_ref()
                    .map(|id| {
                        self.import_image(staging, m.media.iter().find(|m| &m.id == id).unwrap())
                    })
                    .transpose()?;
                self.store_recovered(&actor, &origin, item, bytes.as_deref())?;
            }
        }
        Ok(())
    }
    fn validate_plan(&self, token: &str, m: &Manifest, plan: &Plan) -> Result<()> {
        self.db.execute_batch("SAVEPOINT import_validation")?;
        let result = self.execute_import(token, m, plan, None);
        self.db
            .execute_batch("ROLLBACK TO import_validation; RELEASE import_validation")?;
        result
    }
    pub fn transfer_commit(&mut self, token: &str, input: &Value) -> Result<Value> {
        self.transfer_schema()?;
        fields(input, &["plan_id"])?;
        let id = text(input, "plan_id", 36)?;
        let actor = self.actor(token)?;
        let receipt:Option<(String,i64,Vec<u8>)>=self.db.query_row("SELECT epoch,generation,data FROM transfer_receipts WHERE id=? AND user_id=? AND session_id=?",params![id,actor.user_id,actor.session_id],|r|Ok((r.get(0)?,r.get(1)?,r.get(2)?))).optional()?;
        if let Some((epoch, generation, bytes)) = receipt {
            let (current, current_generation, _) = self.meta()?;
            if current != epoch || generation != current_generation {
                return fail(409,"This import completed before the workspace was cleared or restored. Review the current data before importing again");
            }
            return serde_json::from_slice(
                &self.vault.open(&format!("import-receipt:{id}"), &bytes)?,
            )
            .map_err(|_| Fault(500, "Import receipt needs review".into()));
        }
        let job = self.load_import(token, &id)?;
        let tx = rusqlite::Transaction::new_unchecked(
            &self.db,
            rusqlite::TransactionBehavior::Immediate,
        )?;
        let (epoch, generation, sequence) = self.meta()?;
        if epoch != job.epoch || generation != job.generation || sequence != job.sequence {
            return fail(409,"The workspace changed after preview. Review the import again; uploaded images and existing data are kept");
        }
        let plan = self.make_plan(&job.actor, &job.manifest, &job.mapping)?;
        self.execute_import(token, &job.manifest, &plan, Some(&id))?;
        let report = self.plan_report(&id, &job.manifest, &plan, job.expires)?;
        let result = json!({"imported":plan.new,"already_present":plan.skipped,"retained_changes":plan.recovered,"report":report});
        let bytes = self.vault.seal(
            &format!("import-receipt:{id}"),
            &serde_json::to_vec(&result).unwrap(),
        )?;
        self.db.execute(
            "INSERT INTO transfer_receipts VALUES(?,?,?,?,?,?)",
            params![
                id,
                actor.user_id,
                actor.session_id,
                epoch,
                generation,
                bytes
            ],
        )?;
        self.db
            .execute("DELETE FROM transfer_plans WHERE id=?", [id])?;
        tx.commit()?;
        Ok(result)
    }
    pub fn transfer_cancel(&mut self, token: &str, input: &Value) -> Result<Value> {
        self.transfer_schema()?;
        fields(input, &["plan_id"])?;
        let id = text(input, "plan_id", 36)?;
        let actor = self.actor(token)?;
        self.db.execute(
            "DELETE FROM transfer_plans WHERE id=? AND user_id=? AND session_id=?",
            params![id, actor.user_id, actor.session_id],
        )?;
        Ok(json!({"canceled":true}))
    }
    pub fn transfer_recovered(&self, token: &str) -> Result<Value> {
        self.transfer_schema()?;
        let actor = self.actor(token)?;
        let mut statement = self.db.prepare(
            "SELECT id,data FROM transfer_recovered WHERE user_id=? OR ? ORDER BY rowid DESC",
        )?;
        let rows = statement.query_map(params![actor.user_id, actor.admin()], |r| {
            Ok((r.get::<_, String>(0)?, r.get::<_, Vec<u8>>(1)?))
        })?;
        let mut items = Vec::new();
        for row in rows {
            let (id, data) = row?;
            let item: Retained =
                serde_json::from_slice(&self.vault.open(&format!("recovered:{id}"), &data)?)
                    .map_err(|_| Fault(500, "Recovered data is invalid".into()))?;
            items.push(json!({"id":id,"title":item.title,"payload":item.payload,"has_image":item.media_id.is_some()}));
        }
        Ok(json!({"items":items}))
    }
}
