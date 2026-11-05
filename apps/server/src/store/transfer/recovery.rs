use super::*;

impl Store {
    pub fn transfer_discard(&self, token: &str, input: &Value) -> Result<Value> {
        self.transfer_schema()?;
        fields(input, &["id"])?;
        let id = text(input, "id", 36)?;
        valid_id(&id)?;
        let actor = self.actor(token)?;
        if actor.role == "viewer" {
            return fail(
                403,
                "Read-only household members cannot remove recovered content",
            );
        }
        self.db.execute(
            "DELETE FROM transfer_recovered WHERE id=? AND (user_id=? OR ?)",
            params![id, actor.user_id, actor.admin()],
        )?;
        Ok(json!({"removed":true}))
    }
    /// Explicit recovery creates one separate, validated item. Repeated taps or
    /// lost responses return that same item; existing planner rows are untouched.
    pub fn transfer_restore(&mut self, token: &str, input: &Value) -> Result<Value> {
        self.transfer_schema()?;
        fields(input, &["id", "parent_id"])?;
        let recovery_id = text(input, "id", 36)?;
        valid_id(&recovery_id)?;
        let actor = self.actor(token)?;
        if actor.role == "viewer" {
            return fail(403, "Read-only household members cannot restore content");
        }
        let tx = rusqlite::Transaction::new_unchecked(
            &self.db,
            rusqlite::TransactionBehavior::Immediate,
        )?;
        let (epoch, generation, _) = self.meta()?;
        let row: Option<(String, Vec<u8>, Option<Vec<u8>>)> = self
            .db
            .query_row(
                "SELECT source,data,media FROM transfer_recovered WHERE id=? AND (user_id=? OR ?)",
                params![recovery_id, actor.user_id, actor.admin()],
                |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
            )
            .optional()?;
        let (origin, encrypted, media) = row.ok_or(Fault(
            404,
            "Recovered change is not available to this account".into(),
        ))?;
        if let Some(target) = self
            .db
            .query_row(
                "SELECT target FROM transfer_restored WHERE generation=? AND recovery_id=?",
                params![generation, recovery_id],
                |r| r.get::<_, String>(0),
            )
            .optional()?
        {
            self.record(&target).map_err(|_| {
                Fault(
                    409,
                    "This change was already restored and later removed. It has not been recreated"
                        .into(),
                )
            })?;
            return Ok(json!({"restored":false,"already_present":true,"target":target}));
        }
        let retained: Retained = serde_json::from_slice(
            &self
                .vault
                .open(&format!("recovered:{recovery_id}"), &encrypted)?,
        )
        .map_err(|_| Fault(500, "Recovered data is invalid".into()))?;
        let payload = &retained.payload;
        let command = &payload["command"];
        let kind = payload["record"]["kind"]
            .as_str()
            .or_else(|| {
                command["action"]
                    .as_str()
                    .map(|s| s.split('.').next().unwrap_or(""))
            })
            .unwrap_or("");
        if allowed(kind).is_empty() {
            return fail(
                400,
                "This retained entry cannot be restored automatically. Export it for manual review",
            );
        }
        let mut data = payload["record"]["data"]
            .as_object()
            .cloned()
            .unwrap_or_default();
        if let Some(changes) = command["data"].as_object() {
            for (k, v) in changes {
                if !allowed(kind).contains(&k.as_str()) {
                    return fail(
                        400,
                        "This retained command needs manual review before restoration",
                    );
                }
                data.insert(k.clone(), v.clone());
            }
            if changes.contains_key("reminders") {
                data.remove("reminder_rules");
            }
        }
        let mut source = Record {
            id: recovery_id.clone(),
            kind: kind.into(),
            revision: 0,
            data: Value::Object(data),
        };
        if kind == "task" {
            match command["action"].as_str() {
                Some("task.complete") => source.data["status"] = json!("done"),
                Some("task.reopen" | "task.release" | "task.start") => {
                    source.data["status"] = json!("todo")
                }
                _ => {}
            }
        }
        let parent_field = if kind == "task" {
            "project_id"
        } else {
            "task_id"
        };
        let original = source.data[parent_field].as_str().filter(|s| !s.is_empty());
        let mut parent = input["parent_id"]
            .as_str()
            .filter(|s| !s.is_empty())
            .map(str::to_owned)
            .or_else(|| original.map(str::to_owned));
        if let Some(candidate) = &parent {
            if self.record(candidate).is_err() {
                parent = if let Some((workspace, source_generation)) = origin.rsplit_once(':') {
                    self.db.query_row("SELECT target FROM transfer_maps WHERE generation=? AND source=? AND source_generation=? AND source_id=?",params![generation,workspace,source_generation.parse::<i64>().unwrap_or(0),candidate],|r|r.get::<_,Option<String>>(0)).optional()?.flatten()
                } else {
                    None
                };
            }
        }
        if kind == "task" && parent.is_none() {
            source.data["project_id"] = json!("");
        }
        if matches!(kind, "comment" | "image") && parent.is_none() {
            return fail(
                400,
                "Choose an existing task for this recovered attachment or comment",
            );
        }
        let active: BTreeMap<String, String> = self
            .db
            .prepare("SELECT id,id FROM users WHERE active=1 AND role!='viewer'")?
            .query_map([], |r| Ok((r.get(0)?, r.get(1)?)))?
            .collect::<std::result::Result<_, _>>()?;
        let mut create = content(&source, parent.as_deref(), &active, &actor.user_id);
        let bytes = if kind == "image" {
            Some(self.vault.open(
                &format!("recovered-image:{recovery_id}"),
                &media.ok_or(Fault(400, "The retained image is missing".into()))?,
            )?)
        } else {
            None
        };
        let hash = bytes.as_ref().map(|bytes| crypto::digest(bytes));
        let fingerprint = semantic(&source, &create, hash.as_deref());
        for candidate in self
            .snapshot(actor.clone())?
            .records
            .into_iter()
            .filter(|r| r.kind == kind)
        {
            let parent = candidate.data[if kind == "task" {
                "project_id"
            } else {
                "task_id"
            }]
            .as_str();
            let data = content(&candidate, parent, &active, &actor.user_id);
            let candidate_hash = if kind == "image" {
                Some(crypto::digest(&self.image(&candidate.id)?))
            } else {
                None
            };
            if semantic(&candidate, &data, candidate_hash.as_deref()) == fingerprint {
                self.db.execute(
                    "INSERT INTO transfer_restored VALUES(?,?,?)",
                    params![generation, recovery_id, candidate.id],
                )?;
                tx.commit()?;
                return Ok(json!({"restored":false,"already_present":true,"target":candidate.id}));
            }
        }
        if let Some(bytes) = bytes {
            create["bytes"] = json!(STANDARD.encode(bytes));
        }
        let target = id();
        let action = Command {
            operation_id: id(),
            epoch: epoch.clone(),
            generation,
            action: format!("{kind}.create"),
            target: target.clone(),
            expected_revision: None,
            data: create,
        };
        self.command_inner(token, &action, true)?;
        if kind == "task" && source.data["status"] == "done" {
            self.command_inner(
                token,
                &Command {
                    operation_id: id(),
                    epoch,
                    generation,
                    action: "task.complete".into(),
                    target: target.clone(),
                    expected_revision: Some(1),
                    data: json!({}),
                },
                true,
            )?;
        }
        let mut record = self.record(&target)?;
        record.data["imported_from"] = json!({"source":origin,"id":retained.id,"author":source.data.get("created_by_name").or_else(||source.data.get("author")),"created_at":source.data.get("created_at")});
        self.save(&record, None)?;
        self.db.execute(
            "INSERT INTO transfer_restored VALUES(?,?,?)",
            params![generation, recovery_id, target],
        )?;
        tx.commit()?;
        Ok(json!({"restored":true,"already_present":false,"target":target}))
    }
}
