use super::*;

impl Store {
    pub fn command(&mut self, token: &str, c: Command) -> Result<Value> {
        valid_id(&c.operation_id)?;
        valid_id(&c.target)?;
        // This store is held under the process mutex for the complete transaction.
        let tx = rusqlite::Transaction::new_unchecked(
            &self.db,
            rusqlite::TransactionBehavior::Immediate,
        )?;
        let result = self.command_inner(token, &c)?;
        tx.commit()?;
        Ok(result)
    }
    fn command_inner(&self, token: &str, c: &Command) -> Result<Value> {
        let actor = self.actor(token)?;
        if actor.role == "viewer" {
            return fail(403, "Read-only role");
        }
        let (epoch, generation, _) = self.meta()?;
        if c.epoch != epoch || c.generation != generation {
            return fail(
                409,
                "Workspace changed; preserve drafts and reconcile a fresh snapshot",
            );
        }
        let encoded = serde_json::to_vec(c).map_err(|_| Fault(400, "Invalid command".into()))?;
        let digest = crypto::digest(&encoded);
        let existing: Option<(String, String, String, Vec<u8>)> = self
            .db
            .query_row(
                "SELECT user_id,session_id,digest,result FROM operations WHERE id=?",
                [&c.operation_id],
                |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?)),
            )
            .optional()?;
        if let Some((uid, sid, dig, bytes)) = existing {
            let deleted: bool = self.db.query_row(
                "SELECT EXISTS(SELECT 1 FROM objects WHERE id=? AND deleted=1)",
                [&c.target],
                |r| r.get(0),
            )?;
            if deleted && !c.action.ends_with(".delete") {
                return fail(
                    410,
                    "Item was deleted; cached operation result is no longer available",
                );
            }
            if uid != actor.user_id || sid != actor.session_id || dig != digest {
                return fail(409, "Operation ID already used for another request");
            }
            let raw = self
                .vault
                .open(&format!("operation:{}", c.operation_id), &bytes)?;
            return serde_json::from_slice(&raw)
                .map_err(|_| Fault(500, "Invalid operation result".into()));
        }
        let receipts: i64 = self.db.query_row(
            "SELECT COALESCE(SUM(length(result)),0) FROM operations",
            [],
            |r| r.get(0),
        )?;
        if receipts > 32 * 1024 * 1024 {
            return fail(
                413,
                "Receipt capacity reached; owner export/clear is required before new edits",
            );
        }
        let count: i64 = self
            .db
            .query_row("SELECT COUNT(*) FROM objects", [], |r| r.get(0))?;
        if count >= 10000 && c.action.ends_with(".create") {
            return fail(
                413,
                "Workspace object limit reached; archive/export and clear old data",
            );
        }
        let mut parent = None;
        let mut record = if c.action.ends_with(".create") {
            let exists: bool = self.db.query_row(
                "SELECT EXISTS(SELECT 1 FROM objects WHERE id=?)",
                [&c.target],
                |r| r.get(0),
            )?;
            if exists {
                return fail(409, "Item ID already exists");
            }
            Record {
                id: c.target.clone(),
                kind: c.action.trim_end_matches(".create").into(),
                revision: 0,
                data: json!({}),
            }
        } else {
            let r = self.record(&c.target)?;
            if c.expected_revision != Some(r.revision) {
                return fail(409, "Revision conflict; your draft was not applied");
            }
            r
        };
        match c.action.as_str() {
            "project.create" | "project.update" | "list.create" | "list.update" | "note.create"
            | "note.update" => {
                if record.kind != c.action.split('.').next().unwrap_or("") {
                    return fail(400, "Object kind mismatch");
                }
                Self::organized_record(&c.data, &mut record, &actor)?;
            }
            "task.create" => {
                fields(
                    &c.data,
                    &[
                        "title",
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
                    ],
                )?;
                let pid = self.container_ref(c.data.get("project_id").unwrap_or(&json!("")))?;
                parent = pid.clone();
                let pid = pid.unwrap_or_default();
                let notes = c.data.get("notes").and_then(Value::as_str).unwrap_or("");
                if notes.len() > 16384 {
                    return fail(400, "Notes too long");
                }
                let quadrant = c
                    .data
                    .get("quadrant")
                    .and_then(Value::as_str)
                    .unwrap_or("do");
                Self::quadrant(quadrant)?;
                let due = c.data.get("due").and_then(Value::as_str).unwrap_or("");
                Self::due(due)?;
                record.data = json!({"title":text(&c.data,"title",240)?,"notes":notes,"project_id":pid,"quadrant":quadrant,"due":due,"status":"todo","claimant":null,"created_by":actor.user_id,"updated_at":now()});
                record.data["created_at"] = json!(now());
                Self::planning(&c.data, &mut record)?;
                self.assignment(&c.data, &mut record)?;
            }
            "task.update" => {
                Self::task(&record)?;
                fields(
                    &c.data,
                    &[
                        "title",
                        "project_id",
                        "notes",
                        "quadrant",
                        "due",
                        "priority",
                        "planned_for",
                        "estimate_minutes",
                        "assignee",
                        "pinned",
                        "blocked",
                    ],
                )?;
                if let Some(value) = c.data.get("project_id") {
                    let container = self.container_ref(value)?;
                    self.db.execute(
                        "UPDATE objects SET parent=? WHERE id=?",
                        params![container, c.target],
                    )?;
                    record.data["project_id"] = json!(container.unwrap_or_default());
                }
                Self::planning(&c.data, &mut record)?;
                self.assignment(&c.data, &mut record)?;
                if c.data.get("title").is_some() {
                    record.data["title"] = json!(text(&c.data, "title", 240)?);
                }
                if let Some(v) = c.data.get("notes") {
                    let s = v.as_str().ok_or(Fault(400, "Notes must be text".into()))?;
                    if s.len() > 16384 {
                        return fail(400, "Notes too long");
                    }
                    record.data["notes"] = json!(s);
                }
                if let Some(v) = c.data.get("quadrant") {
                    Self::quadrant(v.as_str().unwrap_or(""))?;
                    record.data["quadrant"] = v.clone();
                }
                if let Some(v) = c.data.get("due") {
                    Self::due(v.as_str().unwrap_or("invalid"))?;
                    record.data["due"] = v.clone();
                }
            }
            "task.start" | "task.release" | "task.complete" | "task.reopen" => {
                fields(&c.data, &[])?;
                Self::task(&record)?;
                if c.action == "task.start" && record.data["blocked"] == true {
                    return fail(409, "Unblock the task before starting it");
                }
                let claimant = record.data["claimant"].as_str();
                if claimant.is_some_and(|u| u != actor.user_id) {
                    return fail(
                        409,
                        "Task is claimed by someone else; ask an administrator to override",
                    );
                }
                match c.action.as_str() {
                    "task.start" => {
                        if record.data["assignee"]
                            .as_str()
                            .is_some_and(|id| id != actor.user_id)
                        {
                            return fail(409, "This task is assigned to another person; reassign it before starting");
                        }
                        if record.data["status"] != "todo" {
                            return fail(409, "Only an available task can be started");
                        }
                        record.data["status"] = json!("in_progress");
                        record.data["claimant"] = json!(actor.user_id);
                    }
                    "task.release" => {
                        if record.data["status"] != "in_progress" {
                            return fail(409, "Task is not in progress");
                        }
                        record.data["status"] = json!("todo");
                        record.data["claimant"] = Value::Null;
                    }
                    "task.complete" => {
                        if record.data["status"] == "done" {
                            return fail(409, "Task is already complete");
                        }
                        record.data["status"] = json!("done");
                        record.data["claimant"] = Value::Null;
                        record.data["completed_by"] = json!(actor.user_id);
                        record.data["completed_at"] = json!(now());
                    }
                    _ => {
                        if record.data["status"] != "done" {
                            return fail(409, "Only completed tasks can be reopened");
                        }
                        record.data["completed_by"] = Value::Null;
                        record.data["completed_at"] = Value::Null;
                        record.data["status"] = json!("todo");
                        record.data["claimant"] = Value::Null;
                    }
                }
            }
            "task.override" => {
                if !actor.admin() || !actor.elevated {
                    return fail(403, "Administrator and recent reauthentication required");
                }
                Self::task(&record)?;
                fields(&c.data, &["reason"])?;
                let reason = text(&c.data, "reason", 500)?;
                record.data["status"] = json!("todo");
                record.data["claimant"] = Value::Null;
                self.audit(
                    &actor,
                    "task.override",
                    &c.target,
                    &json!({"reason":reason}),
                )?;
            }
            "comment.create" => {
                fields(&c.data, &["task_id", "body"])?;
                let tid = text(&c.data, "task_id", 36)?;
                Self::task(&self.record(&tid)?)?;
                parent = Some(tid.clone());
                record.data = json!({"task_id":tid,"body":text(&c.data,"body",8192)?,"author":actor.name,"author_id":actor.user_id,"created_at":now()});
            }
            "image.create" => {
                fields(&c.data, &["task_id", "name", "bytes"])?;
                let tid = text(&c.data, "task_id", 36)?;
                Self::task(&self.record(&tid)?)?;
                parent = Some(tid.clone());
                let bytes = STANDARD
                    .decode(text(&c.data, "bytes", 6_000_000)?)
                    .map_err(|_| Fault(400, "Invalid image encoding".into()))?;
                if bytes.len() > 4 * 1024 * 1024 {
                    return fail(413, "Image must be smaller than 4 MiB");
                }
                let mut reader = image::ImageReader::new(Cursor::new(&bytes))
                    .with_guessed_format()
                    .map_err(|_| Fault(400, "Invalid image".into()))?;
                if !matches!(
                    reader.format(),
                    Some(
                        image::ImageFormat::Png
                            | image::ImageFormat::Jpeg
                            | image::ImageFormat::WebP
                    )
                ) {
                    return fail(400, "Use PNG, JPEG, or WebP");
                }
                let mut limits = image::Limits::default();
                limits.max_image_width = Some(4096);
                limits.max_image_height = Some(4096);
                limits.max_alloc = Some(64 * 1024 * 1024);
                reader.limits(limits);
                let image = reader
                    .decode()
                    .map_err(|_| Fault(400, "Image is invalid or exceeds decode limits".into()))?;
                let mut normalized = Cursor::new(Vec::new());
                image
                    .write_to(&mut normalized, image::ImageFormat::Png)
                    .map_err(|_| Fault(400, "Image normalization failed".into()))?;
                if normalized.get_ref().len() > 8 * 1024 * 1024 {
                    return fail(413, "Normalized image exceeds 8 MiB");
                }
                let used: i64 = self.db.query_row(
                    "SELECT COALESCE(SUM(length(bytes)),0) FROM blobs",
                    [],
                    |r| r.get(0),
                )?;
                if used + normalized.get_ref().len() as i64 > 128 * 1024 * 1024 {
                    return fail(413, "Image storage quota reached");
                }
                let encrypted = self
                    .vault
                    .seal(&format!("blob:{}", record.id), normalized.get_ref())?;
                self.db.execute(
                    "INSERT INTO blobs VALUES(?,?)",
                    params![record.id, encrypted],
                )?;
                record.data = json!({"task_id":tid,"name":text(&c.data,"name",180)?,"author":actor.name,"author_id":actor.user_id,"width":image.width(),"height":image.height(),"size":normalized.get_ref().len(),"created_at":now()});
            }
            "event.create" | "event.update" => {
                if record.kind != "event" {
                    return fail(400, "Expected a calendar event");
                }
                Self::calendar_event(&c.data, &mut record, &actor)?;
            }
            "project.delete" | "list.delete" | "note.delete" | "task.delete" | "comment.delete"
            | "image.delete" | "event.delete" => {
                let kind = c.action.trim_end_matches(".delete");
                if record.kind != kind {
                    return fail(400, "Object kind mismatch");
                }
                fields(&c.data, &[])?;
                if ["project", "list"].contains(&kind) {
                    let count: i64 = self.db.query_row(
                        "SELECT COUNT(*) FROM objects WHERE parent=? AND deleted=0",
                        [&c.target],
                        |r| r.get(0),
                    )?;
                    if count > 0 {
                        return fail(
                            409,
                            "Move or delete the tasks before deleting this collection",
                        );
                    }
                }
                if ["comment", "image", "event"].contains(&kind)
                    && !actor.admin()
                    && record.data["author_id"] != actor.user_id
                {
                    return fail(403, "Only the author or administrator may remove this item");
                }
                if kind == "task"
                    && record.data["claimant"]
                        .as_str()
                        .is_some_and(|u| u != actor.user_id)
                    && !actor.admin()
                {
                    return fail(403, "Cannot delete another person's claimed task");
                }
                self.db.execute(
                    "DELETE FROM blobs WHERE id IN (SELECT id FROM objects WHERE id=? OR parent=?)",
                    params![c.target, c.target],
                )?;
                self.db.execute(
                    "UPDATE objects SET deleted=1,revision=revision+1 WHERE id=? OR parent=?",
                    params![c.target, c.target],
                )?;
                record.revision += 1;
                record.data = json!({"deleted":true});
            }
            _ => return fail(400, "Unsupported command"),
        }
        if !c.action.ends_with(".delete") {
            record.data["updated_by"] = json!(actor.user_id);
            record.revision += 1;
            if record.kind == "task" {
                record.data["updated_at"] = json!(now());
            }
            self.save(&record, parent.as_deref())?;
        }
        self.db.execute(
            "INSERT INTO events(generation,object_id,action,actor,at) VALUES(?,?,?,?,?)",
            params![generation, c.target, c.action, actor.user_id, now()],
        )?;
        let result = json!({"record":record,"sequence":self.db.last_insert_rowid(),"operation_id":c.operation_id});
        let bytes = self.vault.seal(
            &format!("operation:{}", c.operation_id),
            &serde_json::to_vec(&result).map_err(|_| Fault(500, "Serialization".into()))?,
        )?;
        self.db.execute(
            "INSERT INTO operations VALUES(?,?,?,?,?)",
            params![
                c.operation_id,
                actor.user_id,
                actor.session_id,
                digest,
                bytes
            ],
        )?;
        Ok(result)
    }
    fn quadrant(s: &str) -> Result<()> {
        if !["do", "decide", "delegate", "defer"].contains(&s) {
            return fail(400, "Invalid quadrant");
        }
        Ok(())
    }
    pub(super) fn due(s: &str) -> Result<()> {
        if s.is_empty() {
            return Ok(());
        }
        if s.len() != 10
            || !s.bytes().enumerate().all(|(i, b)| {
                if i == 4 || i == 7 {
                    b == b'-'
                } else {
                    b.is_ascii_digit()
                }
            })
        {
            return fail(400, "Due date must use YYYY-MM-DD");
        }
        let y: u32 = s[0..4].parse().unwrap_or(0);
        let m: usize = s[5..7].parse().unwrap_or(0);
        let d: u32 = s[8..10].parse().unwrap_or(0);
        let leap = y.is_multiple_of(4) && (!y.is_multiple_of(100) || y.is_multiple_of(400));
        let days = [
            0,
            31,
            if leap { 29 } else { 28 },
            31,
            30,
            31,
            30,
            31,
            31,
            30,
            31,
            30,
            31,
        ];
        if y < 1970 || !(1..=12).contains(&m) || d == 0 || d > days[m] {
            return fail(400, "Invalid calendar date");
        }
        Ok(())
    }
    fn task(r: &Record) -> Result<()> {
        if r.kind != "task" {
            return fail(400, "Expected a task");
        }
        Ok(())
    }
    pub fn image(&self, oid: &str) -> Result<Vec<u8>> {
        if self.record(oid)?.kind != "image" {
            return fail(404, "Image not found");
        }
        let data: Vec<u8> =
            self.db
                .query_row("SELECT bytes FROM blobs WHERE id=?", [oid], |r| r.get(0))?;
        Ok(self.vault.open(&format!("blob:{oid}"), &data)?)
    }
}
