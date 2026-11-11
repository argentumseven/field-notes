use super::*;
type RecoveryRow = (String, Option<String>, i64, bool, Vec<u8>);

impl Store {
    pub(in crate::store) fn recovery_compact_deleted(&self, target: &str) -> Result<()> {
        let Some(row) = self.recovery_object(target)? else {
            return Ok(());
        };
        if !row.deleted {
            return fail(500, "Cannot compact a live item");
        }
        let mut data = serde_json::Map::new();
        for field in [
            "title",
            "name",
            "project_id",
            "task_id",
            "author_id",
            "created_by",
            "created_by_name",
            "created_at",
            "deleted_at",
        ] {
            if let Some(value) = row.record.data.get(field) {
                data.insert(field.into(), value.clone());
            }
        }
        // Tombstone timestamps live in the existing encrypted record payload:
        // no schema migration, and older deleted records remain readable.
        if !data.contains_key("deleted_at") {
            data.insert("deleted_at".into(), json!(now()));
        }
        if let Some(body) = row.record.data["body"].as_str() {
            data.insert(
                "body".into(),
                json!(body.chars().take(240).collect::<String>()),
            );
        }
        self.db.execute(
            "UPDATE objects SET data=? WHERE id=? AND deleted=1",
            params![
                self.vault.seal(
                    &format!("{}:{target}", row.record.kind),
                    &serde_json::to_vec(&data).map_err(|_| Fault(
                        500,
                        "Removed item metadata could not be encoded".into()
                    ))?
                )?,
                target
            ],
        )?;
        Ok(())
    }
    pub(super) fn recovery_object(&self, target: &str) -> Result<Option<SavedRecord>> {
        let row: Option<RecoveryRow> = self
            .db
            .query_row(
                "SELECT kind,parent,revision,deleted,data FROM objects WHERE id=?",
                [target],
                |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?, r.get(4)?)),
            )
            .optional()?;
        row.map(|(kind, parent, revision, deleted, bytes)| {
            let mut data: Value =
                serde_json::from_slice(&self.vault.open(&format!("{kind}:{target}"), &bytes)?)
                    .map_err(|_| Fault(500, "Saved content could not be read".into()))?;
            // Legacy tasks derive their original creation time from the trail.
            // Freeze it into full saved states before retention can remove it.
            if kind == "task" && !deleted && data.get("created_at").is_none() {
                data["created_at"] = self.record(target)?.data["created_at"].clone();
            }
            Ok(SavedRecord {
                record: Record {
                    id: target.into(),
                    kind,
                    revision,
                    data,
                },
                parent,
                deleted,
            })
        })
        .transpose()
    }
    pub(super) fn recovery_blob(&self, image: &str) -> Result<String> {
        let cached:Option<String>=self.db.query_row("SELECT l.digest FROM recovery_live_blobs l JOIN recovery_blobs b ON b.digest=l.digest WHERE l.id=?",[image],|r|r.get(0)).optional()?;
        if let Some(digest) = cached {
            return Ok(digest);
        }
        let encrypted: Vec<u8> =
            self.db
                .query_row("SELECT bytes FROM blobs WHERE id=?", [image], |r| r.get(0))?;
        let plain = self.vault.open(&format!("blob:{image}"), &encrypted)?;
        let digest = crypto::digest(&plain);
        self.db.execute(
            "INSERT OR IGNORE INTO recovery_blobs VALUES(?,?)",
            params![
                digest,
                self.vault
                    .seal(&format!("recovery-blob:{digest}"), &plain)?
            ],
        )?;
        self.db.execute("INSERT INTO recovery_live_blobs VALUES(?,?) ON CONFLICT(id) DO UPDATE SET digest=excluded.digest",params![image,digest])?;
        Ok(digest)
    }
    pub(super) fn recovery_content(&self, target: Option<&str>) -> Result<ContentState> {
        let mut records = Vec::new();
        let mut ancestors = Vec::new();
        if let Some(target) = target {
            let root = self
                .recovery_object(target)?
                .ok_or(Fault(404, "Item has no saved state".into()))?;
            if root.record.kind == "task" && !root.deleted {
                let children = self
                    .db
                    .prepare("SELECT id FROM objects WHERE parent=? AND deleted=0 ORDER BY id")?
                    .query_map([target], |r| r.get::<_, String>(0))?
                    .collect::<std::result::Result<Vec<_>, _>>()?;
                for child in children {
                    if let Some(row) = self.recovery_object(&child)? {
                        records.push(row)
                    }
                }
            }
            let mut parent = root.parent.clone();
            let mut seen = HashSet::from([target.to_owned()]);
            while let Some(pid) = parent {
                if !seen.insert(pid.clone()) || ancestors.len() >= 3 {
                    return fail(500, "Content parent chain needs review");
                }
                let Some(row) = self.recovery_object(&pid)? else {
                    break;
                };
                parent = row.parent.clone();
                ancestors.push(row);
            }
            records.push(root);
        } else {
            let ids = self
                .db
                .prepare("SELECT id FROM objects WHERE deleted=0 ORDER BY id")?
                .query_map([], |r| r.get::<_, String>(0))?
                .collect::<std::result::Result<Vec<_>, _>>()?;
            for target in ids {
                if let Some(row) = self.recovery_object(&target)? {
                    records.push(row)
                }
            }
        }
        records.sort_by(|a, b| a.record.id.cmp(&b.record.id));
        let mut blobs = BTreeMap::new();
        for row in records
            .iter()
            .chain(ancestors.iter())
            .filter(|r| r.record.kind == "image" && !r.deleted)
        {
            blobs.insert(row.record.id.clone(), self.recovery_blob(&row.record.id)?);
        }
        Ok(ContentState {
            root: target.map(str::to_owned),
            records,
            ancestors,
            blobs,
        })
    }
    pub(super) fn recovery_title(row: &Record) -> String {
        row.data
            .get("title")
            .or_else(|| row.data.get("name"))
            .or_else(|| row.data.get("body"))
            .and_then(Value::as_str)
            .unwrap_or("Item")
            .chars()
            .take(240)
            .collect()
    }
    pub(super) fn recovery_decode(&self, sid: &str, encrypted: &[u8]) -> Result<SavedState> {
        serde_json::from_slice(&self.vault.open(&format!("recovery:{sid}"), encrypted)?)
            .map_err(|_| Fault(500, "Saved version could not be read".into()))
    }
    pub(super) fn recovery_save(
        &self,
        actor: &Actor,
        kind: &str,
        target: &str,
        label: (&str, &str),
        content: &ContentState,
        force: bool,
    ) -> Result<String> {
        let (name, action) = label;
        let lineage = self.recovery_lineage()?;
        let digest = crypto::digest(
            &serde_json::to_vec(
                &json!({"root":content.root,"records":content.records,"blobs":content.blobs}),
            )
            .map_err(|_| Fault(500, "Content could not be encoded".into()))?,
        );
        if !force {
            let previous:Option<(String,String)>=self.db.query_row("SELECT id,digest FROM recovery_snapshots WHERE lineage=? AND kind=? AND target=? ORDER BY version_index DESC LIMIT 1",params![lineage,kind,target],|r|Ok((r.get(0)?,r.get(1)?))).optional()?;
            if let Some((sid, old)) = previous {
                if old == digest {
                    return Ok(sid);
                }
            }
        }
        let bytes = serde_json::to_vec(&SavedState {
            name: name.into(),
            content: content.clone(),
        })
        .map_err(|_| Fault(500, "Content could not be encoded".into()))?;
        if bytes.len() > 16 * 1024 * 1024 {
            return fail(413, "Saved content exceeds the recovery metadata limit");
        }
        let counter = if kind == "checkpoint" {
            "workspace"
        } else {
            target
        };
        let index:i64=self.db.query_row("INSERT INTO recovery_counters VALUES(?,?,1) ON CONFLICT(lineage,target) DO UPDATE SET value=value+1 RETURNING value",params![lineage,counter],|r|r.get(0))?;
        let revision = content
            .records
            .iter()
            .find(|r| r.record.id == target)
            .map_or(0, |r| r.record.revision);
        let sid = id();
        self.db.execute(
            "INSERT INTO recovery_snapshots VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            params![
                sid,
                lineage,
                kind,
                target,
                index,
                revision,
                now(),
                actor.user_id,
                action,
                digest,
                self.vault.seal(&format!("recovery:{sid}"), &bytes)?
            ],
        )?;
        for digest in content.blobs.values().collect::<BTreeSet<_>>() {
            self.db.execute(
                "INSERT INTO recovery_blob_refs VALUES(?,?)",
                params![sid, digest],
            )?;
        }
        Ok(sid)
    }
    pub(in crate::store) fn recovery_subjects(
        &self,
        c: &Command,
        record: &Record,
        children: &[Record],
    ) -> BTreeSet<String> {
        let mut targets = BTreeSet::from([c.target.clone()]);
        if matches!(record.kind.as_str(), "comment" | "image") {
            if let Some(parent) = c
                .data
                .get("task_id")
                .or_else(|| record.data.get("task_id"))
                .and_then(Value::as_str)
            {
                targets.insert(parent.to_owned());
            }
        }
        for child in children {
            targets.insert(child.id.clone());
        }
        targets
    }
    pub(in crate::store) fn recovery_capture(
        &self,
        actor: &Actor,
        targets: &BTreeSet<String>,
        action: &str,
    ) -> Result<BTreeSet<String>> {
        self.recovery_capture_inner(actor, targets, action, false)
    }
    pub(super) fn recovery_capture_for_restore(
        &self,
        actor: &Actor,
        targets: &BTreeSet<String>,
        action: &str,
    ) -> Result<BTreeSet<String>> {
        self.recovery_capture_inner(actor, targets, action, true)
    }
    fn recovery_capture_inner(
        &self,
        actor: &Actor,
        targets: &BTreeSet<String>,
        action: &str,
        force: bool,
    ) -> Result<BTreeSet<String>> {
        if !force && !self.recovery_policy()?.enabled {
            return Ok(BTreeSet::new());
        }
        let mut protected = BTreeSet::new();
        for target in targets {
            let Some(row) = self.recovery_object(target)? else {
                continue;
            };
            let content = self.recovery_content(Some(target))?;
            let sid = self.recovery_save(
                actor,
                "version",
                target,
                (&Self::recovery_title(&row.record), action),
                &content,
                false,
            )?;
            protected.insert(sid);
        }
        Ok(protected)
    }
}
