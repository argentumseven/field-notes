//! Read-only household history. Details are encrypted and committed with the
//! mutation, never supplied by clients or imported as authoritative history.
use super::*;
use rusqlite::types::Value as SqlValue;
use std::collections::BTreeSet;

const DETAIL_ENTRIES: usize = 10_000;
const DETAIL_BYTES: usize = 64 * 1024 * 1024;
const PAGE_SCAN: usize = 500;
const DETAIL_FIELDS: usize = 64;

impl Store {
    pub(super) fn history_record(&self, target: &str) -> Result<Option<Record>> {
        let row: Option<(String, i64, Vec<u8>)> = self
            .db
            .query_row(
                "SELECT kind,revision,data FROM objects WHERE id=?",
                [target],
                |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
            )
            .optional()?;
        row.map(|(kind, revision, bytes)| {
            let data =
                serde_json::from_slice(&self.vault.open(&format!("{kind}:{target}"), &bytes)?)
                    .map_err(|_| Fault(500, "History item could not be read".into()))?;
            Ok(Record {
                id: target.into(),
                kind,
                revision,
                data,
            })
        })
        .transpose()
    }
    pub(super) fn history_title(record: &Record) -> String {
        record
            .data
            .get("title")
            .or_else(|| record.data.get("name"))
            .or_else(|| record.data.get("body"))
            .and_then(Value::as_str)
            .unwrap_or("Item")
            .chars()
            .take(240)
            .collect()
    }
    fn history_context(&self, record: &Record) -> Result<(String, String, String)> {
        let parent = record
            .data
            .get("project_id")
            .or_else(|| record.data.get("task_id"))
            .and_then(Value::as_str)
            .unwrap_or("")
            .to_owned();
        let container = if parent.is_empty() {
            None
        } else {
            self.history_record(&parent)?
        };
        let collection = match container.as_ref() {
            Some(r) if r.kind == "task" => r.data["project_id"].as_str().unwrap_or("").to_owned(),
            Some(r) if matches!(r.kind.as_str(), "list" | "project") => r.id.clone(),
            _ => String::new(),
        };
        let title = container
            .as_ref()
            .map(Self::history_title)
            .unwrap_or_default();
        Ok((parent, collection, title))
    }
    pub(super) fn history_value(&self, field: &str, value: &Value) -> Result<Value> {
        if matches!(field, "assignee" | "claimant" | "completed_by") || field.ends_with(".people") {
            let name = |id: &str| -> Result<Value> {
                if id.is_empty() {
                    return Ok(Value::Null);
                }
                Ok(json!(self
                    .db
                    .query_row("SELECT name FROM users WHERE id=?", [id], |r| r
                        .get::<_, String>(0))
                    .optional()?
                    .unwrap_or_else(|| "Former household member".into())))
            };
            return match value {
                Value::String(id) => name(id),
                Value::Array(ids) => Ok(Value::Array(
                    ids.iter()
                        .map(|id| name(id.as_str().unwrap_or("")))
                        .collect::<Result<_>>()?,
                )),
                _ => Ok(value.clone()),
            };
        }
        if matches!(field, "project_id" | "task_id") {
            return Ok(match value.as_str().filter(|id| !id.is_empty()) {
                Some(id) => self
                    .history_record(id)?
                    .map(|r| json!(Self::history_title(&r)))
                    .unwrap_or(json!("Removed item")),
                None => Value::Null,
            });
        }
        Ok(value.clone())
    }
    pub(super) fn history_capture(
        &self,
        seq: i64,
        actor: &Actor,
        before: Option<&Record>,
        after: Option<&Record>,
        importing: bool,
        reason: Option<&Value>,
    ) -> Result<()> {
        let record = after
            .or(before)
            .ok_or(Fault(500, "Missing history subject".into()))?;
        let (parent, collection, parent_title) = self.history_context(record)?;
        let mut raw = Vec::new();
        // These are content fields only. No session, password, pairing, upload
        // bytes, private keys or internal import receipts can enter this log.
        for field in [
            "title",
            "notes",
            "body",
            "project_id",
            "quadrant",
            "category",
            "template",
            "fields",
            "perpetual",
            "notify_additions",
            "item_reminders",
            "items_are_tasks",
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
            "status",
            "claimant",
            "completed_by",
            "completed_at",
            "all_day",
            "start_date",
            "end_date",
            "starts_at",
            "ends_at",
            "recurrence",
            "on_radar",
            "remind_at",
            "task_id",
            "name",
            "width",
            "height",
        ] {
            history_diff(
                field,
                before.and_then(|r| r.data.get(field)),
                after.and_then(|r| r.data.get(field)),
                &mut raw,
            );
        }
        if let Some(reason) = reason {
            history_diff("reason", None, Some(reason), &mut raw)
        }
        let total = raw.len();
        let mut changes = Vec::new();
        for (field, old, new) in raw.into_iter().take(DETAIL_FIELDS) {
            let old_value = self.history_value(&field, &old)?;
            let new_value = self.history_value(&field, &new)?;
            let (old, old_cut) = history_excerpt(old_value.clone(), &new_value);
            let (new, new_cut) = history_excerpt(new_value, &old_value);
            changes.push(json!({"field":field,"label":history_label(&field),"before":old,"after":new,"before_truncated":old_cut,"after_truncated":new_cut}));
        }
        let item_type = if record.kind == "image" {
            "attachment".to_owned()
        } else if record.kind == "task" && !collection.is_empty() {
            match self
                .history_record(&collection)?
                .as_ref()
                .and_then(|r| r.data["template"].as_str())
            {
                Some("groceries") => "grocery_item".into(),
                Some("shopping") => "shopping_item".into(),
                _ => "list_item".into(),
            }
        } else {
            record.kind.clone()
        };
        let mut payload = json!({"title":Self::history_title(record),"parent_title":parent_title,"kind":record.kind,"item_type":item_type,"actor_name":actor.name,"source":if importing{"import"}else{"edit"},"changes":changes,"total_fields":total,"details":"recorded"});
        let bytes = loop {
            let bytes = serde_json::to_vec(&payload)
                .map_err(|_| Fault(500, "History serialization failed".into()))?;
            if bytes.len() <= 32 * 1024 {
                break bytes;
            }
            payload["changes"]
                .as_array_mut()
                .expect("history changes")
                .pop();
        };
        self.db.execute(
            "INSERT INTO history_details(seq,parent_id,collection_id,data) VALUES(?,?,?,?)",
            params![
                seq,
                parent,
                collection,
                self.vault.seal(&format!("history:{seq}"), &bytes)?
            ],
        )?;
        for item in before.into_iter().chain(after) {
            if let Some(project) = self.project_for_record(item)? {
                self.db.execute(
                    "INSERT OR IGNORE INTO history_projects VALUES(?,?)",
                    params![seq, project],
                )?;
            }
        }
        self.trim_history(DETAIL_ENTRIES, DETAIL_BYTES)
    }
    pub(super) fn trim_history(&self, max_entries: usize, max_bytes: usize) -> Result<()> {
        self.db.execute("DELETE FROM history_details WHERE seq IN (SELECT seq FROM history_details ORDER BY seq DESC LIMIT -1 OFFSET ?)",[max_entries as i64])?;
        let mut used: i64 = self.db.query_row(
            "SELECT COALESCE(SUM(length(data)),0) FROM history_details",
            [],
            |r| r.get(0),
        )?;
        if used > max_bytes as i64 {
            let rows = self
                .db
                .prepare("SELECT seq,length(data) FROM history_details ORDER BY seq")?
                .query_map([], |r| Ok((r.get::<_, i64>(0)?, r.get::<_, i64>(1)?)))?
                .collect::<std::result::Result<Vec<_>, _>>()?;
            let mut cutoff = 0;
            for (seq, size) in rows {
                if used <= max_bytes as i64 {
                    break;
                }
                used -= size;
                cutoff = seq;
            }
            self.db
                .execute("DELETE FROM history_details WHERE seq<=?", [cutoff])?;
        }
        Ok(())
    }
    pub(super) fn history_removed_children(&self, target: &str) -> Result<Vec<Record>> {
        let ids = self
            .db
            .prepare("SELECT id FROM objects WHERE parent=? AND deleted=0")?
            .query_map([target], |r| r.get::<_, String>(0))?
            .collect::<std::result::Result<Vec<_>, _>>()?;
        ids.iter().map(|id| self.record(id)).collect()
    }
    pub(super) fn history_cascade(
        &self,
        generation: i64,
        actor: &Actor,
        records: &[Record],
    ) -> Result<()> {
        for record in records {
            self.db.execute(
                "INSERT INTO events(generation,object_id,action,actor,at) VALUES(?,?,?,?,?)",
                params![
                    generation,
                    record.id,
                    format!("{}.delete", record.kind),
                    actor.user_id,
                    now()
                ],
            )?;
            self.history_capture(
                self.db.last_insert_rowid(),
                actor,
                Some(record),
                None,
                false,
                None,
            )?;
        }
        Ok(())
    }
    pub(super) fn history_workspace_cleared(
        &self,
        seq: i64,
        actor: &Actor,
        counts: &[(String, i64)],
    ) -> Result<()> {
        let changes=counts.iter().map(|(kind,count)|json!({"field":format!("removed_{kind}"),"label":format!("{} removed",history_label(kind)),"before":count,"after":0,"before_truncated":false,"after_truncated":false})).collect::<Vec<_>>();
        let payload = json!({"title":"Workspace content cleared","parent_title":"","kind":"workspace","item_type":"workspace","actor_name":actor.name,"source":"edit","changes":changes,"total_fields":changes.len(),"details":"recorded"});
        let bytes = serde_json::to_vec(&payload)
            .map_err(|_| Fault(500, "History serialization failed".into()))?;
        self.db.execute(
            "INSERT INTO history_details(seq,data) VALUES(?,?)",
            params![seq, self.vault.seal(&format!("history:{seq}"), &bytes)?],
        )?;
        Ok(())
    }
    pub fn history(&self, token: &str, body: Value) -> Result<Value> {
        let actor = self.actor(token)?;
        fields(
            &body,
            &[
                "epoch",
                "generation",
                "view",
                "anchor",
                "before",
                "limit",
                "query",
                "actor",
                "kind",
                "action",
                "target",
                "from",
                "to",
            ],
        )?;
        let (epoch, generation, sequence) = self.meta()?;
        if body["epoch"] != epoch || body["generation"] != generation {
            return fail(
                409,
                "Workspace changed. Refresh History; current data is kept.",
            );
        }
        let number = |key: &str, default: i64, min: i64, max: i64| -> Result<i64> {
            match body.get(key) {
                None => Ok(default),
                Some(v) => v
                    .as_i64()
                    .filter(|n| (min..=max).contains(n))
                    .ok_or(Fault(400, format!("Invalid history {key}"))),
            }
        };
        let anchor = number("anchor", sequence, 0, sequence)?;
        let before = number("before", anchor + 1, 1, anchor + 1)?;
        let limit = number("limit", 30, 1, 50)? as usize;
        let from = number("from", 0, 0, 7_258_118_400)?;
        let to = number("to", 7_258_118_400, 1, 7_258_118_400)?;
        if from >= to {
            return fail(400, "Choose a history date range in order");
        }
        let string = |key: &str, max: usize| -> Result<String> {
            match body.get(key) {
                None => Ok(String::new()),
                Some(Value::String(v)) if v.len() <= max => Ok(v.clone()),
                _ => fail(400, "Invalid history filter"),
            }
        };
        let view = string("view", 10)?;
        if !["", "items"].contains(&view.as_str()) {
            return fail(400, "Choose a history view");
        }
        let items = view == "items";
        let query = string("query", 200)?.trim().to_lowercase();
        let who = string("actor", 36)?;
        if !who.is_empty() {
            valid_id(&who)?
        }
        let target = string("target", 36)?;
        if !target.is_empty() {
            valid_id(&target)?
        }
        let kind = string("kind", 20)?;
        if ![
            "",
            "task",
            "list",
            "project",
            "note",
            "event",
            "comment",
            "image",
            "workspace",
        ]
        .contains(&kind.as_str())
        {
            return fail(400, "Choose a history item type");
        }
        let action = string("action", 20)?;
        if !["", "added", "changed", "removed"].contains(&action.as_str()) {
            return fail(400, "Choose a history action");
        }
        // Rank before applying the page cursor: an older page must never
        // surface another attribute edit of an item already listed. Child
        // activity belongs to the complete task, not a separate history item.
        let subject = "CASE WHEN e.action LIKE 'comment.%' OR e.action LIKE 'image.%' THEN COALESCE(NULLIF(d.parent_id,''),o.parent,e.object_id) ELSE e.object_id END";
        let mut sql=format!("SELECT e.seq,e.object_id,e.action,e.actor,e.at,COALESCE(u.name,'Former household member'),d.data,{subject} AS history_target,ROW_NUMBER() OVER (PARTITION BY {subject} ORDER BY e.seq DESC) AS history_position FROM events e LEFT JOIN users u ON u.id=e.actor LEFT JOIN history_details d ON d.seq=e.seq LEFT JOIN objects o ON o.id=e.object_id LEFT JOIN objects p ON p.id=o.parent WHERE e.generation>=? AND e.seq<=? AND e.at>=? AND e.at<?");
        let mut args: Vec<SqlValue> = vec![
            self.recovery_start_generation()?.into(),
            anchor.into(),
            from.into(),
            to.into(),
        ];
        sql.push_str(" AND e.seq>?");
        args.push(self.recovery_history_floor()?.into());
        let policy = self.recovery_policy()?;
        sql.push_str(" AND e.seq >= COALESCE((SELECT MIN(seq) FROM (SELECT seq FROM events ORDER BY seq DESC LIMIT ?)),0)");
        args.push(policy.history_entries.into());
        if policy.history_days > 0 {
            sql.push_str(" AND e.at>=?");
            args.push((now() - policy.history_days * 86400).into());
        }
        if !who.is_empty() {
            sql.push_str(" AND e.actor=?");
            args.push(who.into());
        }
        if !kind.is_empty() {
            if items && kind == "task" {
                sql.push_str(" AND (e.action LIKE 'task.%' OR e.action LIKE 'comment.%' OR e.action LIKE 'image.%')");
            } else {
                sql.push_str(" AND substr(e.action,1,instr(e.action,'.')-1)=?");
                args.push(kind.into());
            }
        }
        let filter_action = if items {
            "CASE WHEN e.action LIKE 'comment.%' OR e.action LIKE 'image.%' THEN 'task.update' ELSE e.action END"
        } else {
            "e.action"
        };
        match action.as_str() {
            "added" => sql.push_str(&format!(" AND ({filter_action}) LIKE '%.create'")),
            "removed" => sql.push_str(&format!(" AND (({filter_action}) LIKE '%.delete' OR e.action='workspace.clear')")),
            "changed" => sql.push_str(&format!(" AND ({filter_action}) NOT LIKE '%.create' AND ({filter_action}) NOT LIKE '%.delete' AND e.action!='workspace.clear'")),
            _ => {}
        }
        if !target.is_empty() {
            sql.push_str(" AND (e.object_id=? OR d.parent_id=? OR d.collection_id=? OR o.parent=? OR p.parent=? OR EXISTS(SELECT 1 FROM history_projects h WHERE h.seq=e.seq AND h.project_id=?))");
            for _ in 0..6 {
                args.push(target.clone().into());
            }
        }
        if items {
            sql = format!("SELECT * FROM ({sql}) WHERE history_position=1 AND seq<? ORDER BY seq DESC LIMIT 501");
        } else {
            sql.push_str(" AND e.seq<? ORDER BY e.seq DESC LIMIT 501");
        }
        args.push(before.into());
        type Row = (
            i64,
            String,
            String,
            String,
            i64,
            String,
            Option<Vec<u8>>,
            String,
        );
        let rows: Vec<Row> = self
            .db
            .prepare(&sql)?
            .query_map(rusqlite::params_from_iter(args), |r| {
                Ok((
                    r.get(0)?,
                    r.get(1)?,
                    r.get(2)?,
                    r.get(3)?,
                    r.get(4)?,
                    r.get(5)?,
                    r.get(6)?,
                    r.get(7)?,
                ))
            })?
            .collect::<std::result::Result<_, _>>()?;
        let mut entries = Vec::new();
        let mut examined = 0;
        let mut cursor = before;
        for (seq, target, action, uid, at, name, bytes, item_target) in rows.iter().take(PAGE_SCAN)
        {
            if entries.len() >= limit {
                break;
            }
            examined += 1;
            cursor = *seq;
            let mut item = if let Some(bytes) = bytes {
                serde_json::from_slice::<Value>(&self.vault.open(&format!("history:{seq}"), bytes)?)
                    .map_err(|_| Fault(500, "History details could not be read".into()))?
            } else {
                let record = self.history_record(target)?;
                let parent_title = record
                    .as_ref()
                    .map(|r| self.history_context(r).map(|v| v.2))
                    .transpose()?
                    .unwrap_or_default();
                json!({"title":record.as_ref().map(Self::history_title).unwrap_or_else(||if action=="workspace.clear"{"Workspace content cleared".into()}else{"Earlier item".into()}),"parent_title":parent_title,"kind":action.split('.').next().unwrap_or("item"),"item_type":action.split('.').next().unwrap_or("item"),"actor_name":name,"source":"edit","changes":[],"total_fields":0,"details":"unavailable"})
            };
            let mut project_ids = self
                .db
                .prepare("SELECT project_id FROM history_projects WHERE seq=? ORDER BY project_id")?
                .query_map([seq], |r| r.get::<_, String>(0))?
                .collect::<std::result::Result<BTreeSet<_>, _>>()?;
            if let Some(record) = self.history_record(target)? {
                if let Some(project) = self.project_for_record(&record)? {
                    project_ids.insert(project);
                }
            }
            item["project_ids"] = json!(project_ids);
            item["sequence"] = json!(seq);
            item["target"] = json!(target);
            item["action"] = json!(action);
            item["item_target"] = json!(item_target);
            if items && target != item_target {
                let parent = self.history_record(item_target)?;
                item["title"] = parent
                    .as_ref()
                    .map(Self::history_title)
                    .map(Value::String)
                    .unwrap_or_else(|| item["parent_title"].clone());
                item["parent_title"] = json!(parent
                    .as_ref()
                    .map(|r| self.history_context(r).map(|v| v.2))
                    .transpose()?
                    .unwrap_or_default());
                item["activity_kind"] = item["kind"].clone();
                item["kind"] = json!("task");
                let collection = parent
                    .as_ref()
                    .map(|r| self.history_context(r).map(|v| v.1))
                    .transpose()?
                    .unwrap_or_default();
                let template = self
                    .history_record(&collection)?
                    .and_then(|r| r.data["template"].as_str().map(str::to_owned));
                item["item_type"] = json!(match template.as_deref() {
                    Some("groceries") => "grocery_item",
                    Some("shopping") => "shopping_item",
                    _ if !collection.is_empty() => "list_item",
                    _ => "task",
                });
                item["target"] = json!(item_target);
                let deleted = self
                    .db
                    .query_row(
                        "SELECT deleted FROM objects WHERE id=?",
                        [item_target],
                        |r| r.get::<_, bool>(0),
                    )
                    .optional()?
                    .unwrap_or(false);
                item["action"] = json!(if deleted {
                    "task.delete"
                } else {
                    "task.update"
                });
            }
            item["actor_id"] = json!(uid);
            item["at"] = json!(at);
            if query.is_empty()
                || serde_json::to_string(&item)
                    .unwrap_or_default()
                    .to_lowercase()
                    .contains(&query)
            {
                entries.push(item)
            }
        }
        Ok(
            json!({"epoch":epoch,"generation":generation,"anchor":anchor,"entries":entries,"next_before":cursor,"has_more":examined<rows.len(),"scanned":examined,"detail_limit":policy.history_entries,"detail_bytes":policy.history_mib*1024*1024,"actor_id":actor.user_id,"history_revision":self.recovery_revision()?}),
        )
    }
}

pub(super) fn history_diff(
    path: &str,
    old: Option<&Value>,
    new: Option<&Value>,
    out: &mut Vec<(String, Value, Value)>,
) {
    if old == new {
        return;
    }
    let null = Value::Null;
    let a = old.unwrap_or(&null);
    let b = new.unwrap_or(&null);
    if a.is_object() || b.is_object() {
        let keys = a
            .as_object()
            .into_iter()
            .flat_map(|o| o.keys())
            .chain(b.as_object().into_iter().flat_map(|o| o.keys()))
            .collect::<std::collections::BTreeSet<_>>();
        for key in keys {
            if key != "id" {
                history_diff(&format!("{path}.{key}"), a.get(key), b.get(key), out)
            }
        }
    } else if a
        .as_array()
        .into_iter()
        .flatten()
        .chain(b.as_array().into_iter().flatten())
        .any(Value::is_object)
    {
        let len = a
            .as_array()
            .map_or(0, Vec::len)
            .max(b.as_array().map_or(0, Vec::len));
        for index in 0..len {
            history_diff(&format!("{path}.{index}"), a.get(index), b.get(index), out)
        }
    } else {
        out.push((path.into(), a.clone(), b.clone()))
    }
}
pub(super) fn history_excerpt(value: Value, other: &Value) -> (Value, bool) {
    if let Some(text) = value.as_str() {
        if text.len() <= 2000 {
            return (value, false);
        }
        let chars = text.chars().collect::<Vec<_>>();
        let common = other.as_str().map_or(0, |s| {
            chars
                .iter()
                .copied()
                .zip(s.chars())
                .take_while(|(a, b)| a == b)
                .count()
        });
        let start = common
            .saturating_sub(120)
            .min(chars.len().saturating_sub(1));
        let mut excerpt = if start > 0 {
            "…".to_owned()
        } else {
            String::new()
        };
        for ch in chars.iter().skip(start) {
            if excerpt.len() + ch.len_utf8() > 1997 {
                break;
            }
            excerpt.push(*ch)
        }
        if excerpt.chars().count() - usize::from(start > 0) < chars.len() - start {
            excerpt.push('…')
        }
        return (json!(excerpt), true);
    }
    if let Value::Array(items) = &value {
        if items.len() > 32 {
            return (json!(items[..32]), true);
        }
    }
    (value, false)
}
pub(super) fn history_label(path: &str) -> String {
    let leaf = path.rsplit('.').next().unwrap_or(path);
    let label = match leaf {
        "project_id" => "List or project",
        "task_id" => "Task",
        "notes" => "Notes",
        "body" => "Content",
        "title" => "Title",
        "due" => "Due date",
        "planned_for" => "Planned day",
        "assignee" => "Assigned to",
        "claimant" => "Working on it",
        "completed_by" => "Completed by",
        "completed_at" => "Completed at",
        "starts_at" => "Starts",
        "ends_at" => "Ends",
        "start_date" => "Start day",
        "end_date" => "End day",
        "all_day" => "All day",
        "remind_at" => "Follow-up",
        "on_radar" => "Later & reminders",
        "estimate_minutes" => "Estimated minutes",
        "link_url" => "Web link",
        "notify_additions" => "Notify about added items",
        "item_reminders" => "Item reminders",
        "lead_minutes" => "Minutes before",
        "tone" => "Sound",
        "volume" => "Volume",
        "vibrate" => "Vibration",
        "importance" => "Importance",
        "duration_seconds" => "Playback seconds",
        "alarm_volume" => "Alarm volume",
        "unmute" => "Raise alarm volume",
        "alarm" => "Loud alarm",
        "frequency" => "Frequency",
        "interval" => "Repeat every",
        "weekdays" | "days" => "Days of the week",
        "until" => "Repeat through",
        "excluded" => "Skipped dates",
        "dates" => "Chosen dates",
        "zone" => "Time zone",
        "people" => "People",
        "mode" => "Recipients",
        "reason" => "Override reason",
        _ => {
            return leaf
                .replace('_', " ")
                .split_whitespace()
                .map(|w| {
                    let mut c = w.chars();
                    c.next()
                        .map(|f| f.to_uppercase().collect::<String>() + c.as_str())
                        .unwrap_or_default()
                })
                .collect::<Vec<_>>()
                .join(" ")
        }
    };
    if path.starts_with("reminders.alerts.") {
        let n = path
            .split('.')
            .nth(2)
            .and_then(|s| s.parse::<usize>().ok())
            .unwrap_or(0)
            + 1;
        format!("Alert {n} · {label}")
    } else if path.starts_with("reminder_options.playback.") {
        format!("Alarm · {label}")
    } else if path.starts_with("recurrence.") {
        format!("Repeat · {label}")
    } else if path.starts_with("reminders.repeat.") {
        format!("Reminder window · {label}")
    } else {
        label.into()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn retention_bounds_details_without_removing_audit_events() {
        let root = std::env::temp_dir().join(format!("field-notes-history-{}", id()));
        std::fs::create_dir_all(&root).unwrap();
        Vault::create(&root.join("key")).unwrap();
        let store = Store::open(&root.join("data"), &root.join("key")).unwrap();
        for _ in 0..4 {
            store.db.execute("INSERT INTO events(generation,object_id,action,actor,at) VALUES(1,'item','note.create','actor',1)",[]).unwrap();
            let seq = store.db.last_insert_rowid();
            store
                .db
                .execute(
                    "INSERT INTO history_details(seq,data) VALUES(?,?)",
                    params![seq, vec![0_u8; 20]],
                )
                .unwrap();
        }
        store.trim_history(3, 45).unwrap();
        let detail_ids = store
            .db
            .prepare("SELECT seq FROM history_details ORDER BY seq")
            .unwrap()
            .query_map([], |r| r.get::<_, i64>(0))
            .unwrap()
            .collect::<std::result::Result<Vec<_>, _>>()
            .unwrap();
        assert_eq!(detail_ids, vec![3, 4]);
        assert_eq!(
            store
                .db
                .query_row("SELECT COUNT(*) FROM events", [], |r| r.get::<_, i64>(0))
                .unwrap(),
            4
        );
        drop(store);
        std::fs::remove_dir_all(root).unwrap();
    }
}
