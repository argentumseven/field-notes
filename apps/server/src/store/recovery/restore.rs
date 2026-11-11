use super::*;

#[derive(Serialize, Deserialize)]
struct RestorePlan {
    epoch: String,
    generation: i64,
    source: String,
    source_digest: String,
    checkpoint: bool,
    target: Option<String>,
    current_digest: String,
    directory_digest: String,
    policy_revision: i64,
    #[serde(default)]
    hierarchy_mode: String,
    #[serde(default)]
    desired_digest: String,
    #[serde(default)]
    source_at: i64,
}

impl Store {
    pub(super) fn recovery_result(&self, actor: &Actor, body: &Value) -> Result<Value> {
        let operation = text(body, "operation_id", 36)?;
        valid_id(&operation)?;
        let row: Option<(String, String, String, String, Vec<u8>)> = self
            .db
            .query_row(
                "SELECT actor,session,epoch,lineage,data FROM recovery_receipts WHERE id=?",
                [&operation],
                |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?, r.get(4)?)),
            )
            .optional()?;
        let Some((user, _session, epoch, lineage, bytes)) = row else {
            return Ok(json!({"known":false}));
        };
        if user != actor.user_id || epoch != self.meta()?.0 || lineage != self.recovery_lineage()? {
            return Ok(json!({"known":false}));
        }
        let result: Value = serde_json::from_slice(
            &self
                .vault
                .open(&format!("recovery-receipt:{operation}"), &bytes)?,
        )
        .map_err(|_| Fault(500, "Recovery result could not be read".into()))?;
        Ok(json!({"known":true,"result":result}))
    }
    fn recovery_source(&self, sid: &str) -> Result<(String, String, i64, SavedState)> {
        valid_id(sid)?;
        let row:Option<(String,String,i64,Vec<u8>)>=self.db.query_row("SELECT kind,digest,version_index,data FROM recovery_snapshots WHERE id=? AND lineage=?",params![sid,self.recovery_lineage()?],|r|Ok((r.get(0)?,r.get(1)?,r.get(2)?,r.get(3)?))).optional()?;
        let (kind, digest, index, data) = row.ok_or(Fault(
            410,
            "That saved state expired or was purged; current content was not changed".into(),
        ))?;
        Ok((kind, digest, index, self.recovery_decode(sid, &data)?))
    }
    fn recovery_current(&self, target: Option<&str>) -> Result<ContentState> {
        if let Some(target) = target {
            if self.recovery_object(target)?.is_none() {
                return Ok(ContentState {
                    root: Some(target.into()),
                    records: vec![],
                    ancestors: vec![],
                    blobs: BTreeMap::new(),
                });
            }
        }
        let mut content = self.recovery_content(target)?;
        // The capture cache deduplicates immutable images. A restore preview
        // must also check the actual live bytes, so corruption cannot look like
        // a no-op merely because the image metadata still matches.
        for row in content
            .records
            .iter()
            .filter(|r| r.record.kind == "image" && !r.deleted)
        {
            let encrypted: Option<Vec<u8>> = self
                .db
                .query_row(
                    "SELECT bytes FROM blobs WHERE id=?",
                    [&row.record.id],
                    |r| r.get(0),
                )
                .optional()?;
            let digest = encrypted
                .and_then(|bytes| {
                    self.vault
                        .open(&format!("blob:{}", row.record.id), &bytes)
                        .ok()
                })
                .map(|bytes| crypto::digest(&zeroize::Zeroizing::new(bytes)))
                .unwrap_or_else(|| "unavailable".into());
            content.blobs.insert(row.record.id.clone(), digest);
        }
        Ok(content)
    }
    fn recovery_state_digest(content: &ContentState) -> Result<String> {
        Ok(crypto::digest(&serde_json::to_vec(content).map_err(
            |_| Fault(500, "Recovery comparison failed".into()),
        )?))
    }
    fn recovery_directory_digest(&self) -> Result<String> {
        let people = self
            .db
            .prepare("SELECT id,name,role,active FROM users ORDER BY id")?
            .query_map([], |r| {
                Ok((
                    r.get::<_, String>(0)?,
                    r.get::<_, String>(1)?,
                    r.get::<_, String>(2)?,
                    r.get::<_, bool>(3)?,
                ))
            })?
            .collect::<std::result::Result<Vec<_>, _>>()?;
        Ok(crypto::digest(&serde_json::to_vec(&people).map_err(
            |_| Fault(500, "People could not be compared".into()),
        )?))
    }
    fn recovery_authorize(
        &self,
        actor: &Actor,
        checkpoint: bool,
        desired: &ContentState,
        current: &ContentState,
    ) -> Result<()> {
        if actor.role == "viewer" {
            return fail(403, "Your role is read-only");
        }
        if checkpoint && !actor.admin() {
            return fail(
                403,
                "Only an administrator may restore all household content",
            );
        }
        if checkpoint {
            return Ok(());
        }
        let target = desired
            .root
            .as_deref()
            .ok_or(Fault(500, "Saved item identity is missing".into()))?;
        for row in desired
            .records
            .iter()
            .chain(current.records.iter())
            .filter(|r| r.record.id == target)
        {
            if matches!(row.record.kind.as_str(), "event" | "comment" | "image")
                && !actor.admin()
                && row.record.data["author_id"] != actor.user_id
            {
                return fail(
                    403,
                    "Only the author or an administrator may restore this item",
                );
            }
        }
        if !actor.admin()
            && current.records.iter().any(|row| {
                row.record.id == target
                    && !row.deleted
                    && row.record.kind == "task"
                    && row.record.data["claimant"]
                        .as_str()
                        .is_some_and(|id| !id.is_empty() && id != actor.user_id)
            })
        {
            return fail(403,"Another person is working on this task; release it or ask an administrator before restoring");
        }
        Ok(())
    }
    fn recovery_desired(
        &self,
        saved: &ContentState,
        current: &ContentState,
    ) -> Result<ContentState> {
        let mut desired = saved.clone();
        if saved.root.as_ref().is_some_and(|root| {
            saved
                .records
                .iter()
                .any(|r| &r.record.id == root && r.deleted)
        }) {
            desired.ancestors.clear();
            return Ok(desired);
        }
        for ancestor in saved.ancestors.iter().rev() {
            let existing = self.recovery_object(&ancestor.record.id)?;
            if existing.is_none_or(|row| row.deleted) {
                let mut restored = ancestor.clone();
                restored.deleted = false;
                desired.records.push(restored);
            }
        }
        let desired_ids = desired
            .records
            .iter()
            .filter(|r| !r.deleted)
            .map(|r| r.record.id.clone())
            .collect::<BTreeSet<_>>();
        for row in &mut desired.records {
            if row.deleted {
                continue;
            }
            if let Some(parent) = row.parent.as_ref() {
                if !desired_ids.contains(parent)
                    && (saved.root.is_none()
                        || self.recovery_object(parent)?.is_none_or(|row| row.deleted))
                {
                    return fail(
                        409,
                        "A parent item is unavailable. Restore its saved state first",
                    );
                }
            }
            if let Some(old) = current
                .records
                .iter()
                .find(|r| r.record.id == row.record.id)
            {
                if old.record.kind != row.record.kind {
                    return fail(409, "Item identity changed; this state cannot replace it");
                }
            }
            if row.record.kind == "project" {
                if let Some(id) = row.record.data["assignee"]
                    .as_str()
                    .filter(|s| !s.is_empty())
                {
                    let active:bool=self.db.query_row("SELECT EXISTS(SELECT 1 FROM users WHERE id=? AND active=1 AND role!='viewer')",[id],|r|r.get(0))?;
                    if !active {
                        row.record.data["assignee"] = Value::Null;
                    }
                }
            }
            if row.record.kind == "task" {
                row.record.data["claimant"] = Value::Null;
                if row.record.data["status"] == "in_progress" {
                    row.record.data["status"] = json!("todo")
                }
                if let Some(assigned) = row.record.data["assignee"]
                    .as_str()
                    .filter(|s| !s.is_empty())
                {
                    let active:bool=self.db.query_row("SELECT EXISTS(SELECT 1 FROM users WHERE id=? AND active=1 AND role!='viewer')",[assigned],|r|r.get(0))?;
                    if !active {
                        row.record.data["assignee"] = Value::Null;
                    }
                }
            }
        }
        let mut overlay = desired.records.clone();
        let kept = desired
            .records
            .iter()
            .filter(|r| !r.deleted)
            .map(|r| r.record.id.as_str())
            .collect::<HashSet<_>>();
        for row in &current.records {
            if !kept.contains(row.record.id.as_str()) {
                let mut removed = row.clone();
                removed.deleted = true;
                overlay.push(removed);
            }
        }
        self.project_validate(&overlay)?;
        Ok(desired)
    }
    // Project/list versions save their own settings. Existing members are
    // recovered through stable object IDs and current parent bindings; missing
    // descendants are restored only when explicitly requested. No new IDs are
    // allocated, and live descendants are never overwritten.
    fn recovery_hierarchy(
        &self,
        saved: &ContentState,
        mode: &str,
        source_at: i64,
    ) -> Result<(ContentState, ContentState, Value)> {
        let root = saved.root.as_deref().ok_or(Fault(400, "A collection identity is required".into()))?;
        let mut current = self.recovery_current(Some(root))?;
        let mut proposed = saved.clone();
        let mut pending = vec![root.to_owned()];
        let mut visited = HashSet::from([root.to_owned()]);
        let mut linked = 0usize;
        let mut missing = 0usize;
        let mut respawned = 0usize;
        while let Some(parent) = pending.pop() {
            let children = self.db
                .prepare("SELECT id FROM objects WHERE parent=? ORDER BY id")?
                .query_map([&parent], |r| r.get::<_, String>(0))?
                .collect::<std::result::Result<Vec<_>, _>>()?;
            for id in children {
                if !visited.insert(id.clone()) {
                    return fail(409, "Collection hierarchy contains a duplicate or cycle");
                }
                if visited.len() > 300 {
                    return fail(413, "This collection is too large for item-level restoration; use a reviewed content checkpoint");
                }
                let row = self.recovery_object(&id)?
                    .ok_or(Fault(409, "A linked record disappeared; review the restore again".into()))?;
                if !matches!(row.record.kind.as_str(), "list" | "task" | "image" | "comment") {
                    return fail(409, "Collection has an unsupported linked record");
                }
                if row.deleted {
                    // Only an item that existed when this specific version was
                    // captured can belong to its restored hierarchy.
                    if row.record.data["created_at"].as_i64().is_some_and(|at| at > source_at) {
                        continue;
                    }
                    let deleted_at = if let Some(at) = row.record.data["deleted_at"].as_i64() {
                        Some(at)
                    } else {
                        self.db.query_row(
                            "SELECT MAX(at) FROM events WHERE object_id=? AND action=?",
                            params![id, format!("{}.delete", row.record.kind)],
                            |r| r.get::<_, Option<i64>>(0),
                        )?
                    };
                    if deleted_at.is_some_and(|at| at < source_at) {
                        continue;
                    }
                    if deleted_at.is_none() {
                        if mode == "respawn" {
                            return fail(409, "A deleted member has no verifiable deletion time. Use bindings-only recovery or a reviewed checkpoint");
                        }
                        continue;
                    }
                }
                let parent_field = if matches!(row.record.kind.as_str(), "task" | "list") {
                    "project_id"
                } else {
                    "task_id"
                };
                if row.record.data[parent_field].as_str() != Some(parent.as_str()) {
                    return fail(409, "Linked record parent binding is inconsistent; no content was restored");
                }
                if matches!(row.record.kind.as_str(), "task" | "list") {
                    pending.push(id.clone());
                }
                current.records.push(row.clone());
                if !row.deleted {
                    linked += 1;
                    if row.record.kind == "image" {
                        let encrypted: Option<Vec<u8>> = self.db.query_row(
                            "SELECT bytes FROM blobs WHERE id=?", [&id], |r| r.get(0)
                        ).optional()?;
                        let digest = encrypted.and_then(|bytes| {
                            self.vault.open(&format!("blob:{id}"), &bytes).ok()
                        }).map(|bytes| crypto::digest(&bytes))
                          .unwrap_or_else(|| "unavailable".into());
                        current.blobs.insert(id.clone(), digest.clone());
                        proposed.blobs.insert(id.clone(), digest);
                    }
                    // An existing member is its own binding. In particular,
                    // do not replace newer edits with older recovery content.
                    proposed.records.push(row);
                } else {
                    missing += 1;
                    if mode == "respawn" {
                        let (restored, digest) = self.recovery_hierarchy_source(&row)?;
                        if let Some(digest) = digest {
                            proposed.blobs.insert(id.clone(), digest);
                        }
                        proposed.records.push(restored);
                        respawned += 1;
                    } else {
                        // Preserve the tombstone (and its revision); an ordinary
                        // collection restore must not resurrect deleted items.
                        proposed.records.push(row);
                    }
                }
            }
        }
        current.records.sort_by(|a, b| a.record.id.cmp(&b.record.id));
        proposed.records.sort_by(|a, b| a.record.id.cmp(&b.record.id));
        Ok((current, proposed, json!({
            "mode": mode,
            "linked_existing": linked,
            "missing": missing,
            "respawned": respawned,
            "kept_deleted": missing - respawned,
        })))
    }

    // Prefer the deleted item's own retained version; a task version can also
    // supply an older comment/image and its encrypted blob. Never fabricate a
    // record from a compact tombstone, or reparent a different saved identity.
    fn recovery_hierarchy_source(
        &self,
        deleted: &SavedRecord,
    ) -> Result<(SavedRecord, Option<String>)> {
        let id = &deleted.record.id;
        let mut sources = vec![id.to_owned()];
        if let Some(parent) = deleted.parent.as_ref() {
            if self.recovery_object(parent)?.is_some_and(|r| r.record.kind == "task") {
                sources.push(parent.clone());
            }
        }
        for target in sources {
            let snapshots = self.db.prepare(
                "SELECT id,data FROM recovery_snapshots WHERE lineage=? AND kind='version' AND target=? ORDER BY version_index DESC LIMIT 64"
            )?.query_map(params![self.recovery_lineage()?, target], |r| {
                Ok((r.get::<_, String>(0)?, r.get::<_, Vec<u8>>(1)?))
            })?.collect::<std::result::Result<Vec<_>, _>>()?;
            for (snapshot_id, bytes) in snapshots {
                let state = self.recovery_decode(&snapshot_id, &bytes)?;
                if let Some(candidate) = state.content.records.iter().find(|r| {
                    !r.deleted && r.record.id == *id &&
                    r.record.kind == deleted.record.kind && r.parent == deleted.parent
                }) {
                    let digest = if candidate.record.kind == "image" {
                        let Some(digest) = state.content.blobs.get(id) else {
                            continue;
                        };
                        // Check the retained ciphertext and digest in the
                        // preview, not only after the user confirms a restore.
                        let encrypted: Option<Vec<u8>> = self.db.query_row(
                            "SELECT bytes FROM recovery_blobs WHERE digest=?",
                            [digest], |r| r.get(0),
                        ).optional()?;
                        let Some(encrypted) = encrypted else {
                            continue;
                        };
                        let plain = self.vault.open(
                            &format!("recovery-blob:{digest}"), &encrypted
                        )?;
                        if crypto::digest(&plain) != *digest {
                            return fail(409, "A saved image failed its integrity check. No content was changed");
                        }
                        Some(digest.clone())
                    } else {
                        None
                    };
                    return Ok((candidate.clone(), digest));
                }
            }
        }
        fail(409, &format!(
            "No complete retained version for deleted {}. Restore only the collection, or use a suitable content checkpoint",
            Self::recovery_title(&deleted.record)
        ))
    }
    fn recovery_semantic(row: &SavedRecord) -> Value {
        let mut data = row.record.data.as_object().cloned().unwrap_or_default();
        for field in [
            "updated_at",
            "updated_by",
            "updated_by_name",
            "last_activity_at",
            "last_activity_by",
            "last_activity_by_name",
            "recovery_incarnation",
        ] {
            data.remove(field);
        }
        json!({"kind":row.record.kind,"parent":row.parent,"deleted":row.deleted,"data":if row.deleted{json!({})}else{Value::Object(data)}})
    }
    fn recovery_changes(
        &self,
        current: &ContentState,
        desired: &ContentState,
        checkpoint: bool,
    ) -> Result<Vec<Value>> {
        let mut entries = Vec::new();
        let mut ids = current
            .records
            .iter()
            .chain(desired.records.iter())
            .map(|r| r.record.id.clone())
            .collect::<BTreeSet<_>>();
        if !checkpoint {
            ids.extend(
                desired
                    .ancestors
                    .iter()
                    .filter(|r| desired.records.iter().any(|d| d.record.id == r.record.id))
                    .map(|r| r.record.id.clone()),
            );
        }
        for target in ids {
            let before = current
                .records
                .iter()
                .find(|r| r.record.id == target && !r.deleted);
            let after = desired
                .records
                .iter()
                .find(|r| r.record.id == target && !r.deleted);
            let image_changed = before.is_some_and(|r| r.record.kind == "image")
                && after.is_some()
                && current.blobs.get(&target) != desired.blobs.get(&target);
            if !image_changed
                && before.map(Self::recovery_semantic) == after.map(Self::recovery_semantic)
            {
                continue;
            }
            let subject = after.or(before);
            if let Some(subject) = subject {
                let mut raw = Vec::new();
                let fields =
                    before
                        .into_iter()
                        .flat_map(|r| r.record.data.as_object().into_iter().flat_map(|o| o.keys()))
                        .chain(after.into_iter().flat_map(|r| {
                            r.record.data.as_object().into_iter().flat_map(|o| o.keys())
                        }))
                        .cloned()
                        .collect::<BTreeSet<_>>();
                for field in fields {
                    if field.starts_with("updated_")
                        || field.starts_with("last_activity_")
                        || field.starts_with("created_")
                        || field == "author_id"
                        || field == "recovery_incarnation"
                    {
                        continue;
                    }
                    super::super::history::history_diff(
                        &field,
                        before.and_then(|r| r.record.data.get(&field)),
                        after.and_then(|r| r.record.data.get(&field)),
                        &mut raw,
                    );
                }
                if image_changed {
                    raw.push((
                        "image_content".into(),
                        json!(if current
                            .blobs
                            .get(&target)
                            .is_some_and(|s| s != "unavailable")
                        {
                            "Current attachment"
                        } else {
                            "Unavailable or damaged"
                        }),
                        json!("Saved attachment"),
                    ));
                }
                raw.retain(|(_, old, new)| {
                    old != new
                        && if before.is_none() || after.is_none() {
                            let value = if after.is_some() { new } else { old };
                            !value.is_null()
                                && value != &json!(false)
                                && value != &json!(0)
                                && value != &json!("")
                                && value != &json!([])
                        } else {
                            true
                        }
                });
                raw.sort_by_key(|(field, _, _)| match field.as_str() {
                    "title" | "name" => 0,
                    "notes" | "body" => 1,
                    "starts_at" | "start_date" | "ends_at" | "end_date" => 2,
                    "status" => 3,
                    _ => 4,
                });
                let total = raw.len();
                let mut differences = Vec::new();
                for (field, old, new) in raw.into_iter().take(64) {
                    let old = self.history_value(&field, &old)?;
                    let new = self.history_value(&field, &new)?;
                    let (previous, old_cut) =
                        super::super::history::history_excerpt(old.clone(), &new);
                    let (next, new_cut) = super::super::history::history_excerpt(new, &old);
                    differences.push(json!({"field":field,"label":super::super::history::history_label(&field),"before":previous,"after":next,"before_truncated":old_cut,"after_truncated":new_cut,"truncated":old_cut||new_cut}));
                }
                let mut entry = json!({"id":target,"kind":subject.record.kind,"title":Self::recovery_title(&subject.record),"action":if before.is_none(){"restore"}else if after.is_none(){"remove"}else{"change"},"changes":differences,"total_fields":total});
                while serde_json::to_vec(&entry)
                    .map_err(|_| Fault(500, "Recovery preview could not be encoded".into()))?
                    .len()
                    > 32 * 1024
                {
                    entry["changes"]
                        .as_array_mut()
                        .ok_or(Fault(500, "Invalid recovery preview".into()))?
                        .pop();
                }
                entries.push(entry);
            }
        }
        Ok(entries)
    }
    // Presentation is a complete item, rather than a list of attribute deltas.
    // Image bytes stay in the encrypted store; previews name attached files.
    fn recovery_item_view(&self, content: &ContentState, target: &str) -> Result<Value> {
        let Some(root) = content
            .records
            .iter()
            .find(|r| r.record.id == target && !r.deleted)
        else {
            return Ok(Value::Null);
        };
        let mut fields = Vec::new();
        for field in [
            "title",
            "name",
            "body",
            "note_type",
            "ingredients",
            "instructions",
            "notes",
            "location",
            "status",
            "priority",
            "category",
            "item_category",
            "project_id",
            "assignee",
            "due",
            "planned_for",
            "quantity",
            "store",
            "link_url",
            "price",
            "currency",
            "estimate_minutes",
            "pinned",
            "blocked",
            "all_day",
            "start_date",
            "end_date",
            "starts_at",
            "ends_at",
            "recurrence",
            "remind_at",
            "on_radar",
            "reminders",
            "reminder_options",
            "template",
            "fields",
            "perpetual",
            "notify_additions",
            "item_reminders",
            "items_are_tasks",
        ] {
            if let Some(value) = root.record.data.get(field) {
                if !value.is_null() && value != &json!("") && value != &json!([]) {
                    let display = if field == "project_id" {
                        // A missing parent will be restored from this state.
                        // Its retained title can differ from the live tombstone.
                        content
                            .records
                            .iter()
                            .find(|r| !r.deleted && value.as_str() == Some(r.record.id.as_str()))
                            .map(|r| json!(Self::recovery_title(&r.record)))
                    } else {
                        None
                    };
                    fields.push(json!({"field":field,"label":super::super::history::history_label(field),"value":display.unwrap_or(self.recovery_display_value(field,value)?)}));
                }
            }
        }
        let children = content
            .records
            .iter()
            .filter(|r| !r.deleted && r.parent.as_deref() == Some(target))
            .collect::<Vec<_>>();
        Ok(
            json!({"title":Self::recovery_title(&root.record),"kind":root.record.kind,"fields":fields,
            "comments":children.iter().filter(|r|r.record.kind=="comment").count(),
            "images":children.iter().filter(|r|r.record.kind=="image").count(),
            "children":children.iter().take(20).map(|r|json!({"kind":r.record.kind,"title":Self::recovery_title(&r.record),"body":r.record.data["body"],"author":r.record.data["author"]})).collect::<Vec<_>>(),
            "children_limited":children.len()>20}),
        )
    }
    fn recovery_display_value(&self, path: &str, value: &Value) -> Result<Value> {
        match value {
            Value::Object(object) => Ok(Value::Object(
                object
                    .iter()
                    .filter(|(key, _)| key.as_str() != "id")
                    .map(|(key, value)| {
                        Ok((
                            key.clone(),
                            self.recovery_display_value(&format!("{path}.{key}"), value)?,
                        ))
                    })
                    .collect::<Result<_>>()?,
            )),
            Value::Array(items) if !path.ends_with(".people") => Ok(Value::Array(
                items
                    .iter()
                    .map(|value| self.recovery_display_value(path, value))
                    .collect::<Result<_>>()?,
            )),
            _ => self.history_value(path, value),
        }
    }
    pub(super) fn recovery_preview(&self, actor: &Actor, body: &Value) -> Result<Value> {
        fields(body, &["action", "epoch", "generation", "source", "hierarchy_mode"])?;
        let sid = text(body, "source", 36)?;
        let (kind, source_digest, index, saved) = self.recovery_source(&sid)?;
        let source_at: i64 = self.db.query_row(
            "SELECT at FROM recovery_snapshots WHERE id=? AND lineage=?",
            params![sid, self.recovery_lineage()?],
            |r| r.get(0),
        )?;
        let checkpoint = kind == "checkpoint";
        let collection = !checkpoint && saved.content.root.as_ref().is_some_and(|target|
            saved.content.records.iter().any(|row|
                row.record.id == *target &&
                !row.deleted &&
                matches!(row.record.kind.as_str(), "project" | "list")
            )
        );
        // Requests from older clients have no hierarchy_mode and keep the
        // pre-existing single-item recovery behavior. New clients must opt in.
        let hierarchy_mode = match body.get("hierarchy_mode") {
            None => "",
            Some(Value::String(value)) if value == "bindings" && collection => "bindings",
            Some(Value::String(value)) if value == "respawn" && collection => "respawn",
            _ => return fail(400, "Hierarchy recovery requires an explicit supported mode and a saved live project or list"),
        };
        if hierarchy_mode == "respawn" && !actor.admin() {
            return fail(403, "Administrator authorization is required to restore missing linked records");
        }
        let (current, selected, hierarchy) = if hierarchy_mode.is_empty() {
            (self.recovery_current(saved.content.root.as_deref())?, saved.content.clone(), Value::Null)
        } else {
            let (current, selected, summary) = self.recovery_hierarchy(&saved.content, hierarchy_mode, source_at)?;
            (current, selected, summary)
        };
        self.recovery_authorize(actor, checkpoint, &selected, &current)?;
        let desired = self.recovery_desired(&selected, &current)?;
        let changes = self.recovery_changes(&current, &desired, checkpoint)?;
        let plan = RestorePlan {
            epoch: self.meta()?.0,
            generation: self.meta()?.1,
            source: sid.clone(),
            source_digest,
            checkpoint,
            target: saved.content.root.clone(),
            current_digest: Self::recovery_state_digest(&current)?,
            directory_digest: self.recovery_directory_digest()?,
            policy_revision: self.recovery_revision()?,
            hierarchy_mode: hierarchy_mode.to_owned(),
            desired_digest: Self::recovery_state_digest(&desired)?,
            source_at,
        };
        let count: i64 = self
            .db
            .query_row("SELECT COUNT(*) FROM recovery_previews", [], |r| r.get(0))?;
        if count >= 100 {
            return fail(429,"Too many recovery previews are open. Close old previews or wait for them to expire");
        }
        let preview = id();
        let expires = now() + 600;
        self.db.execute(
            "INSERT INTO recovery_previews VALUES(?,?,?,?,?,?)",
            params![
                preview,
                self.recovery_lineage()?,
                actor.user_id,
                actor.session_id,
                expires,
                self.vault.seal(
                    &format!("recovery-preview:{preview}"),
                    &serde_json::to_vec(&plan)
                        .map_err(|_| Fault(500, "Recovery preview could not be saved".into()))?
                )?
            ],
        )?;
        let item_state = saved.content.root.as_deref().map(|target| -> Result<Value> {
            Ok(json!({"current":self.recovery_item_view(&current,target)?,"saved":self.recovery_item_view(&desired,target)?}))
        }).transpose()?;
        let count = changes.len();
        let confirmation = format!(
            "RESTORE {} #{index}",
            if checkpoint { "CHECKPOINT" } else { "ITEM" }
        );
        Ok(
            json!({"preview_id":preview,"expires_at":expires,"epoch":plan.epoch,"generation":plan.generation,"source":sid,"source_index":index,"title":saved.name,"checkpoint":checkpoint,"reauth_required":true,"confirmation_phrase":confirmation,"change_count":count,"restored":changes.iter().filter(|c|c["action"]=="restore").count(),"removed":changes.iter().filter(|c|c["action"]=="remove").count(),"changed":changes.iter().filter(|c|c["action"]=="change").count(),"changes":changes.into_iter().take(50).collect::<Vec<_>>(),"preview_limited":count>50,"saves_current_state":true,"clears_claims":true,"fences_offline_writes":checkpoint,"item_state":item_state,"hierarchy":hierarchy}),
        )
    }
    pub(super) fn recovery_receipt(
        &self,
        actor: &Actor,
        operation: &str,
        digest: &str,
    ) -> Result<Option<Value>> {
        let row: Option<(String, String, String, String, String, Vec<u8>)> = self
            .db
            .query_row(
                "SELECT actor,session,epoch,lineage,digest,data FROM recovery_receipts WHERE id=?",
                [operation],
                |r| {
                    Ok((
                        r.get(0)?,
                        r.get(1)?,
                        r.get(2)?,
                        r.get(3)?,
                        r.get(4)?,
                        r.get(5)?,
                    ))
                },
            )
            .optional()?;
        let Some((user, session, epoch, lineage, original, data)) = row else {
            return Ok(None);
        };
        if user != actor.user_id
            || session != actor.session_id
            || epoch != self.meta()?.0
            || lineage != self.recovery_lineage()?
            || original != digest
        {
            return fail(
                409,
                "This recovery operation ID belongs to another request or session",
            );
        }
        serde_json::from_slice(
            &self
                .vault
                .open(&format!("recovery-receipt:{operation}"), &data)?,
        )
        .map(Some)
        .map_err(|_| Fault(500, "Recovery result could not be read".into()))
    }
    pub(super) fn recovery_save_receipt(
        &self,
        actor: &Actor,
        operation: &str,
        digest: &str,
        result: &Value,
    ) -> Result<()> {
        let count: i64 = self
            .db
            .query_row("SELECT COUNT(*) FROM recovery_receipts", [], |r| r.get(0))?;
        if count >= 10_000 {
            return fail(413,"Recovery receipt capacity reached; an administrator must review retained recovery data");
        }
        self.db.execute(
            "INSERT INTO recovery_receipts VALUES(?,?,?,?,?,?,?)",
            params![
                operation,
                actor.user_id,
                actor.session_id,
                self.meta()?.0,
                self.recovery_lineage()?,
                digest,
                self.vault.seal(
                    &format!("recovery-receipt:{operation}"),
                    &serde_json::to_vec(result)
                        .map_err(|_| Fault(500, "Recovery receipt could not be saved".into()))?
                )?
            ],
        )?;
        Ok(())
    }
    pub(super) fn recovery_apply(&self, actor: &Actor, body: &Value) -> Result<Value> {
        fields(
            body,
            &[
                "action",
                "epoch",
                "generation",
                "preview_id",
                "operation_id",
                "confirmation",
                "reviewed",
            ],
        )?;
        let operation = text(body, "operation_id", 36)?;
        valid_id(&operation)?;
        let digest = crypto::digest(
            &serde_json::to_vec(body).map_err(|_| Fault(400, "Invalid recovery request".into()))?,
        );
        // Check receipts before loading the expiring preview; a lost response
        // must never apply a second restore or create a second checkpoint.
        if let Some(result) = self.recovery_receipt(actor, &operation, &digest)? {
            return Ok(result);
        }
        let preview = text(body, "preview_id", 36)?;
        valid_id(&preview)?;
        let row: Option<(String, String, String, i64, Vec<u8>)> = self
            .db
            .query_row(
                "SELECT lineage,actor,session,expires,data FROM recovery_previews WHERE id=?",
                [&preview],
                |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?, r.get(4)?)),
            )
            .optional()?;
        let (lineage, user, session, expires, bytes) = row.ok_or(Fault(
            410,
            "This preview expired or was purged; make a new preview before restoring".into(),
        ))?;
        if lineage != self.recovery_lineage()?
            || user != actor.user_id
            || session != actor.session_id
        {
            return fail(
                403,
                "This preview belongs to a different account or session",
            );
        }
        if expires <= now() {
            return fail(410, "This preview expired; current content was not changed");
        }
        let plan: RestorePlan = serde_json::from_slice(
            &self
                .vault
                .open(&format!("recovery-preview:{preview}"), &bytes)?,
        )
        .map_err(|_| Fault(500, "Recovery preview could not be read".into()))?;
        if plan.epoch != self.meta()?.0
            || plan.generation != self.meta()?.1
            || plan.policy_revision != self.recovery_revision()?
        {
            return fail(
                409,
                "Workspace or recovery settings changed; make a new preview",
            );
        }
        if !actor.elevated {
            return fail(
                403,
                "Recent password verification required before restoring content",
            );
        }
        if plan.checkpoint && !actor.admin() {
            return fail(403, "Administrator required to restore all content");
        }
        let (_, source_digest, index, saved) = self.recovery_source(&plan.source)?;
        let confirmation = format!(
            "RESTORE {} #{index}",
            if plan.checkpoint {
                "CHECKPOINT"
            } else {
                "ITEM"
            }
        );
        if body["confirmation"] != confirmation || body["reviewed"] != true {
            return fail(400, "Review the changes and type the exact confirmation phrase from this preview. Update Field Notes if the confirmation step is unavailable");
        }
        if source_digest != plan.source_digest {
            return fail(409, "Saved source changed; make a new preview");
        }
        let (current, selected) = if plan.hierarchy_mode.is_empty() {
            (self.recovery_current(plan.target.as_deref())?, saved.content.clone())
        } else {
            if !matches!(plan.hierarchy_mode.as_str(), "bindings" | "respawn") {
                return fail(409, "Invalid saved hierarchy choice; review a new preview");
            }
            if plan.hierarchy_mode == "respawn" && !actor.admin() {
                return fail(403, "Administrator authorization required for recovering missing contents");
            }
            let (current, selected, _) = self.recovery_hierarchy(&saved.content, &plan.hierarchy_mode, plan.source_at)?;
            (current, selected)
        };
        self.recovery_authorize(actor, plan.checkpoint, &selected, &current)?;
        if Self::recovery_state_digest(&current)? != plan.current_digest
            || self.recovery_directory_digest()? != plan.directory_digest
        {
            return fail(409,"Content or people changed after this preview. No restore was applied; review a new preview");
        }
        let desired = self.recovery_desired(&selected, &current)?;
        if !plan.desired_digest.is_empty()
            && Self::recovery_state_digest(&desired)? != plan.desired_digest
        {
            return fail(409, "Linked recovery sources changed; review a new preview");
        }
        let changes = self.recovery_changes(&current, &desired, plan.checkpoint)?;
        let mut protected = BTreeSet::from([plan.source.clone()]);
        let before_checkpoint = if plan.checkpoint && !changes.is_empty() {
            let sid = self.recovery_save(
                actor,
                "checkpoint",
                "",
                (
                    &format!("Before restoring {}", saved.name)
                        .chars()
                        .take(120)
                        .collect::<String>(),
                    "checkpoint.before_restore",
                ),
                &current,
                true,
            )?;
            protected.insert(sid.clone());
            Some(sid)
        } else {
            None
        };
        let targets = current
            .records
            .iter()
            .chain(desired.records.iter())
            .map(|r| r.record.id.clone())
            .collect::<BTreeSet<_>>();
        protected.extend(self.recovery_capture_for_restore(actor, &targets, "restore.before")?);
        self.recovery_trim(&protected)?;
        if plan.checkpoint && !changes.is_empty() {
            self.db
                .execute("UPDATE meta SET generation=generation+1 WHERE id=1", [])?;
        }
        let mut changed_ids = changes
            .iter()
            .filter_map(|c| c["id"].as_str())
            .map(str::to_owned)
            .collect::<BTreeSet<_>>();
        if !changes.is_empty() {
            let mut fenced_tasks = BTreeSet::new();
            // Restoring a leaf can revive its missing parent task. Those tasks
            // need the same stale-child fence as an explicit task restore.
            for row in desired
                .records
                .iter()
                .filter(|r| !r.deleted && r.record.kind == "task")
            {
                if self
                    .recovery_object(&row.record.id)?
                    .is_none_or(|r| r.deleted)
                {
                    fenced_tasks.insert(row.record.id.clone());
                }
            }
            if let Some(target) = plan.target.as_deref() {
                if current
                    .records
                    .iter()
                    .chain(desired.records.iter())
                    .any(|r| r.record.id == target && r.record.kind == "task")
                {
                    changed_ids.insert(target.to_owned());
                    fenced_tasks.insert(target.to_owned());
                }
            }
            for task in fenced_tasks {
                self.db.execute("INSERT INTO recovery_incarnations VALUES(?,1) ON CONFLICT(target) DO UPDATE SET value=value+1",[task])?;
            }
        }
        for target in &changed_ids {
            let after = desired
                .records
                .iter()
                .find(|r| r.record.id == *target && !r.deleted);
            self.recovery_write(actor, target, after, &desired, index)?;
        }
        let used: i64 = self.db.query_row(
            "SELECT COALESCE(SUM(length(data)),0) FROM objects",
            [],
            |r| r.get(0),
        )?;
        let images: i64 = self.db.query_row(
            "SELECT COALESCE(SUM(length(bytes)),0) FROM blobs",
            [],
            |r| r.get(0),
        )?;
        let count: i64 = self
            .db
            .query_row("SELECT COUNT(*) FROM objects", [], |r| r.get(0))?;
        if used > 8 * 1024 * 1024 || images > 128 * 1024 * 1024 || count > 10_000 {
            return fail(
                413,
                "The restored state would exceed live content capacity; no changes were committed",
            );
        }
        protected.extend(self.recovery_capture_for_restore(actor, &targets, "restore.after")?);
        protected.remove(&plan.source);
        self.recovery_trim(&protected)?;
        let action = if plan.checkpoint {
            "checkpoint.restore"
        } else {
            "item.restore"
        };
        self.audit(actor,action,plan.target.as_deref().unwrap_or("workspace"),&json!({"source":plan.source,"source_index":index,"changed":changes.len(),"before_checkpoint":before_checkpoint}))?;
        if plan.checkpoint && !changes.is_empty() {
            self.db.execute("INSERT INTO events(generation,object_id,action,actor,at) VALUES(?,'workspace','workspace.restore',?,?)",params![self.meta()?.1,actor.user_id,now()])?;
        }
        let result = json!({"ok":true,"operation_id":operation,"epoch":self.meta()?.0,"generation":self.meta()?.1,"sequence":self.meta()?.2,"changed":changes.len(),"target":plan.target,"before_checkpoint":before_checkpoint,"source_index":index});
        self.recovery_save_receipt(actor, &operation, &digest, &result)?;
        self.db
            .execute("DELETE FROM recovery_previews WHERE id=?", [preview])?;
        Ok(result)
    }
    fn recovery_write(
        &self,
        actor: &Actor,
        target: &str,
        after: Option<&SavedRecord>,
        desired: &ContentState,
        index: i64,
    ) -> Result<()> {
        let previous = self.recovery_object(target)?;
        let before = previous
            .as_ref()
            .filter(|r| !r.deleted)
            .map(|r| r.record.clone());
        let revision = previous.as_ref().map_or(1, |r| r.record.revision + 1);
        let record = if let Some(row) = after {
            let mut record = row.record.clone();
            record.revision = revision;
            for (field, value) in [
                ("updated_by", json!(actor.user_id)),
                ("updated_by_name", json!(actor.name)),
                ("updated_at", json!(now())),
                ("last_activity_by", json!(actor.user_id)),
                ("last_activity_by_name", json!(actor.name)),
                ("last_activity_at", json!(now())),
            ] {
                record.data[field] = value;
            }
            let bytes = self.vault.seal(
                &format!("{}:{target}", record.kind),
                &serde_json::to_vec(&record.data)
                    .map_err(|_| Fault(500, "Restored content could not be encoded".into()))?,
            )?;
            self.db.execute("INSERT INTO objects(id,kind,parent,revision,deleted,data) VALUES(?,?,?,?,0,?) ON CONFLICT(id) DO UPDATE SET parent=excluded.parent,revision=excluded.revision,deleted=0,data=excluded.data",params![target,record.kind,row.parent,revision,bytes])?;
            if record.kind == "image" {
                let digest = desired.blobs.get(target).ok_or(Fault(
                    410,
                    "The retained image is unavailable; no restore was applied".into(),
                ))?;
                let encrypted: Vec<u8> = self
                    .db
                    .query_row(
                        "SELECT bytes FROM recovery_blobs WHERE digest=?",
                        [digest],
                        |r| r.get(0),
                    )
                    .optional()?
                    .ok_or(Fault(
                        410,
                        "The retained image expired; no restore was applied".into(),
                    ))?;
                let plain = self
                    .vault
                    .open(&format!("recovery-blob:{digest}"), &encrypted)?;
                if crypto::digest(&plain) != *digest {
                    return fail(500, "Retained image integrity check failed");
                }
                self.db.execute("INSERT INTO blobs VALUES(?,?) ON CONFLICT(id) DO UPDATE SET bytes=excluded.bytes",params![target,self.vault.seal(&format!("blob:{target}"),&plain)?])?;
                self.db.execute("INSERT INTO recovery_live_blobs VALUES(?,?) ON CONFLICT(id) DO UPDATE SET digest=excluded.digest",params![target,digest])?;
            }
            record
        } else {
            let row = previous
                .as_ref()
                .ok_or(Fault(409, "Removed item changed after preview".into()))?;
            self.db.execute("DELETE FROM blobs WHERE id=?", [target])?;
            self.db.execute(
                "UPDATE objects SET deleted=1,revision=? WHERE id=?",
                params![revision, target],
            )?;
            self.recovery_compact_deleted(target)?;
            row.record.clone()
        };
        self.db.execute(
            "INSERT INTO events(generation,object_id,action,actor,at) VALUES(?,?,?,?,?)",
            params![
                self.meta()?.1,
                target,
                format!("{}.restore", record.kind),
                actor.user_id,
                now()
            ],
        )?;
        let sequence = self.db.last_insert_rowid();
        self.history_capture(
            sequence,
            actor,
            before.as_ref(),
            after.map(|_| &record),
            false,
            Some(&json!(format!("Restored saved version #{index}"))),
        )?;
        Ok(())
    }
}
