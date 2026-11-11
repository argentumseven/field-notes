use super::*;

impl Store {
    pub(super) fn recovery_delete_checkpoint(&self, actor: &Actor, body: &Value) -> Result<Value> {
        fields(
            body,
            &[
                "action",
                "epoch",
                "generation",
                "source",
                "history_revision",
                "operation_id",
                "confirmation",
            ],
        )?;
        if !actor.admin() || !actor.elevated {
            return fail(
                403,
                "Administrator and recent password verification required to delete a checkpoint",
            );
        }
        let source = text(body, "source", 36)?;
        valid_id(&source)?;
        let operation = text(body, "operation_id", 36)?;
        valid_id(&operation)?;
        if body["history_revision"].as_i64() != Some(self.recovery_revision()?) {
            return fail(
                409,
                "Recovery history changed; refresh before deleting a checkpoint",
            );
        }
        // Do not decrypt the snapshot: administrators must also be able to
        // remove a damaged copy. The stable ID, kind and lineage scope deletion.
        let index: Option<i64> = self.db.query_row(
            "SELECT version_index FROM recovery_snapshots WHERE id=? AND lineage=? AND kind='checkpoint'",
            params![source, self.recovery_lineage()?], |r| r.get(0),
        ).optional()?;
        let index = index.ok_or(Fault(
            410,
            "That content checkpoint is no longer available".into(),
        ))?;
        if body["confirmation"] != format!("DELETE CHECKPOINT #{index}") {
            return fail(
                400,
                "Type the exact confirmation phrase for the checkpoint being deleted",
            );
        }
        let digest = crypto::digest(
            &serde_json::to_vec(body)
                .map_err(|_| Fault(400, "Invalid checkpoint request".into()))?,
        );
        self.db
            .execute("DELETE FROM recovery_snapshots WHERE id=?", [&source])?;
        // Existing previews and delayed creates are fenced by this revision.
        // Preserve receipts so a lost delete reply can be checked or retried.
        self.db.execute(
            "UPDATE recovery_settings SET revision=revision+1 WHERE id=1",
            [],
        )?;
        self.recovery_gc_blobs()?;
        self.audit(actor, "checkpoint.delete", &source, &json!({"index":index}))?;
        let result = json!({"ok":true,"deleted_checkpoint_id":source,"epoch":self.meta()?.0,"generation":self.meta()?.1,"history_revision":self.recovery_revision()?});
        self.recovery_save_receipt(actor, &operation, &digest, &result)?;
        Ok(result)
    }
    pub(in crate::store) fn recovery_usage(&self) -> Result<Value> {
        let (versions,checkpoints,metadata):(i64,i64,i64)=self.db.query_row("SELECT COALESCE(SUM(kind='version'),0),COALESCE(SUM(kind='checkpoint'),0),COALESCE(SUM(length(data)),0) FROM recovery_snapshots",[],|r|Ok((r.get(0)?,r.get(1)?,r.get(2)?)))?;
        let (version_metadata_bytes, checkpoint_metadata_bytes): (i64, i64) = self.db.query_row(
            "SELECT COALESCE(SUM(CASE WHEN kind='version' THEN length(data) ELSE 0 END),0),COALESCE(SUM(CASE WHEN kind='checkpoint' THEN length(data) ELSE 0 END),0) FROM recovery_snapshots",
            [],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )?;
        let trash_records: i64 = self.db.query_row(
            "SELECT COUNT(*) FROM objects WHERE deleted=1 AND kind IN ('project','list','task','note','event')",
            [],
            |row| row.get(0),
        )?;
        let trash_recoverable_records: i64 = self.db.query_row(
            "SELECT COUNT(*) FROM objects o WHERE o.deleted=1 AND o.kind IN ('project','list','task','note','event') AND EXISTS(SELECT 1 FROM recovery_snapshots s WHERE s.lineage=(SELECT lineage FROM recovery_settings WHERE id=1) AND s.kind='version' AND s.target=o.id)",
            [],
            |row| row.get(0),
        )?;
        let blob_bytes: i64 = self.db.query_row(
            "SELECT COALESCE(SUM(length(bytes)),0) FROM recovery_blobs",
            [],
            |r| r.get(0),
        )?;
        let history_bytes: i64 = self.db.query_row(
            "SELECT COALESCE(SUM(length(data)),0) FROM history_details",
            [],
            |r| r.get(0),
        )?;
        let page_size: i64 = self.db.query_row("PRAGMA page_size", [], |r| r.get(0))?;
        let (pages, free): (i64, i64) = (
            self.db.query_row("PRAGMA page_count", [], |r| r.get(0))?,
            self.db
                .query_row("PRAGMA freelist_count", [], |r| r.get(0))?,
        );
        Ok(
            json!({"versions":versions,"checkpoints":checkpoints,"snapshot_bytes":metadata,"version_metadata_bytes":version_metadata_bytes,"checkpoint_metadata_bytes":checkpoint_metadata_bytes,"image_bytes":blob_bytes,"recovery_bytes":metadata+blob_bytes,"history_bytes":history_bytes,"trash_records":trash_records,"trash_recoverable_records":trash_recoverable_records,"database_allocated_bytes":pages*page_size,"database_reusable_bytes":free*page_size}),
        )
    }
    pub(super) fn recovery_gc_blobs(&self) -> Result<()> {
        self.db.execute("DELETE FROM recovery_blobs WHERE digest NOT IN (SELECT digest FROM recovery_blob_refs)",[])?;
        Ok(())
    }
    pub(in crate::store) fn recovery_trim(&self, protected: &BTreeSet<String>) -> Result<()> {
        let before = self.recovery_retained_counts()?;
        let policy = self.recovery_policy()?;
        self.recovery_trim_history(&policy)?;
        self.db
            .execute("DELETE FROM recovery_previews WHERE expires<=?", [now()])?;
        let rows=self.db.prepare("SELECT id,kind,target,at FROM recovery_snapshots ORDER BY version_index DESC,at DESC,id")?.query_map([],|r|Ok((r.get::<_,String>(0)?,r.get::<_,String>(1)?,r.get::<_,String>(2)?,r.get::<_,i64>(3)?)))?.collect::<std::result::Result<Vec<_>,_>>()?;
        let mut counts = BTreeMap::<String, i64>::new();
        let mut checkpoints = 0;
        let mut kept = 0;
        for (sid, kind, target, at) in rows {
            let count = if kind == "checkpoint" {
                checkpoints += 1;
                checkpoints
            } else {
                let count = counts.entry(target).or_default();
                *count += 1;
                *count
            };
            let maximum = if kind == "checkpoint" {
                policy.checkpoints
            } else {
                policy.versions_per_item
            };
            let expired = policy.days > 0 && at < now() - policy.days * 86400;
            if !protected.contains(&sid) && (expired || count > maximum || kept >= 50_000) {
                self.db
                    .execute("DELETE FROM recovery_snapshots WHERE id=?", [sid])?;
            } else {
                kept += 1;
            }
        }
        self.recovery_gc_blobs()?;
        let cap = policy.storage_mib * 1024 * 1024;
        loop {
            if self.recovery_usage()?["recovery_bytes"]
                .as_i64()
                .unwrap_or(i64::MAX)
                <= cap
            {
                break;
            }
            let candidates = self
                .db
                .prepare("SELECT id FROM recovery_snapshots ORDER BY at,version_index,id")?
                .query_map([], |r| r.get::<_, String>(0))?
                .collect::<std::result::Result<Vec<_>, _>>()?;
            let victim = candidates.into_iter().find(|sid| !protected.contains(sid));
            let Some(victim) = victim else {
                return fail(413,"Recovery storage is full. An administrator must increase its limit or purge saved versions before this change can be safely retained");
            };
            self.db
                .execute("DELETE FROM recovery_snapshots WHERE id=?", [victim])?;
            self.recovery_gc_blobs()?;
        }
        if before != self.recovery_retained_counts()? {
            self.db.execute(
                "UPDATE recovery_settings SET revision=revision+1 WHERE id=1",
                [],
            )?;
        }
        Ok(())
    }
    fn recovery_retained_counts(&self) -> Result<(i64, i64, i64)> {
        Ok(self.db.query_row("SELECT (SELECT COUNT(*) FROM events),(SELECT COUNT(*) FROM history_details),(SELECT COUNT(*) FROM recovery_snapshots)",[],|r|Ok((r.get(0)?,r.get(1)?,r.get(2)?)))?)
    }
    fn recovery_trim_history(&self, policy: &RecoveryPolicy) -> Result<()> {
        self.trim_history(
            policy.history_entries as usize,
            (policy.history_mib * 1024 * 1024) as usize,
        )?;
        let cutoff = if policy.history_days > 0 {
            now() - policy.history_days * 86400
        } else {
            0
        };
        self.db.execute("DELETE FROM events WHERE (at<? OR seq IN (SELECT seq FROM events ORDER BY seq DESC LIMIT -1 OFFSET ?)) AND seq!=(SELECT COALESCE(MAX(seq),0) FROM events) AND seq NOT IN (SELECT MIN(e.seq) FROM events e JOIN objects o ON o.id=e.object_id WHERE o.kind='task' AND o.deleted=0 GROUP BY e.object_id)",params![cutoff,policy.history_entries])?;
        self.db.execute(
            "DELETE FROM history_details WHERE seq IN (SELECT seq FROM events WHERE at<?)",
            [cutoff],
        )?;
        Ok(())
    }
    pub(crate) fn recovery_maintenance(&mut self) -> Result<()> {
        let tx = rusqlite::Transaction::new_unchecked(
            &self.db,
            rusqlite::TransactionBehavior::Immediate,
        )?;
        self.recovery_trim(&BTreeSet::new())?;
        tx.commit()?;
        Ok(())
    }
    pub(in crate::store) fn recovery_clear(&self) -> Result<()> {
        self.db.execute_batch("DELETE FROM recovery_previews; DELETE FROM recovery_receipts; DELETE FROM recovery_snapshots; DELETE FROM recovery_blob_refs; DELETE FROM recovery_blobs; DELETE FROM recovery_live_blobs; DELETE FROM recovery_counters; DELETE FROM recovery_incarnations;")?;
        self.db.execute("UPDATE recovery_settings SET lineage=?,revision=revision+1,start_generation=? WHERE id=1",params![id(),self.meta()?.1+1])?;
        Ok(())
    }
    pub(in crate::store) fn recovery_admin(&self, actor: &Actor, body: &Value) -> Result<Value> {
        if !actor.admin() || !actor.elevated {
            return fail(403, "Administrator and recent reauthentication required");
        }
        match body["action"].as_str().unwrap_or("") {
            "recovery.policy" => {
                fields(body, &["action", "policy", "revision"])?;
                if body["revision"].as_i64() != Some(self.recovery_revision()?) {
                    return fail(409, "Recovery settings changed; refresh before saving");
                }
                let policy: RecoveryPolicy = serde_json::from_value(body["policy"].clone())
                    .map_err(|_| Fault(400, "Choose complete recovery settings".into()))?;
                if !(0..=3650).contains(&policy.days)
                    || !(16..=2048).contains(&policy.storage_mib)
                    || !(2..=1000).contains(&policy.versions_per_item)
                    || !(1..=100).contains(&policy.checkpoints)
                    || !(0..=3650).contains(&policy.history_days)
                    || !(100..=10_000).contains(&policy.history_entries)
                    || !(1..=64).contains(&policy.history_mib)
                {
                    return fail(400, "Recovery settings exceed their supported limits");
                }
                self.db.execute(
                    "UPDATE recovery_settings SET policy=?,revision=revision+1 WHERE id=1",
                    [self.vault.seal(
                        "recovery-policy",
                        &serde_json::to_vec(&policy)
                            .map_err(|_| Fault(400, "Invalid recovery policy".into()))?,
                    )?],
                )?;
                self.recovery_trim(&BTreeSet::new())?;
                self.audit(
                    actor,
                    "recovery.policy",
                    "workspace",
                    &json!({"policy":policy}),
                )?;
            }
            "recovery.purge" => {
                fields(body, &["action", "scope", "revision", "confirmation"])?;
                if body["revision"].as_i64() != Some(self.recovery_revision()?)
                    || body["confirmation"] != "PURGE HISTORY"
                {
                    return fail(409, "Purge confirmation or settings revision changed");
                }
                let scope = body["scope"].as_str().unwrap_or("");
                if !matches!(scope, "versions" | "checkpoints" | "history" | "all") {
                    return fail(400, "Choose which retained history to purge");
                }
                if scope == "all" {
                    self.db.execute("DELETE FROM recovery_snapshots", [])?;
                } else if scope == "versions" || scope == "checkpoints" {
                    self.db.execute(
                        "DELETE FROM recovery_snapshots WHERE kind=?",
                        [if scope == "versions" {
                            "version"
                        } else {
                            "checkpoint"
                        }],
                    )?;
                }
                if scope == "history" || scope == "all" {
                    self.recovery_purge_trail(actor)?;
                }
                self.db.execute("DELETE FROM recovery_previews", [])?;
                // New checkpoint requests carry this revision; removing receipts
                // cannot turn a delayed pre-purge request into a new checkpoint.
                self.db.execute("DELETE FROM recovery_receipts", [])?;
                self.db.execute(
                    "UPDATE recovery_settings SET revision=revision+1 WHERE id=1",
                    [],
                )?;
                self.recovery_gc_blobs()?;
                self.audit(
                    actor,
                    "recovery.purge",
                    "workspace",
                    &json!({"scope":scope}),
                )?;
            }
            _ => return fail(400, "Unknown recovery administration action"),
        }
        Ok(
            json!({"ok":true,"policy":self.recovery_policy()?,"usage":self.recovery_usage()?,"history_revision":self.recovery_revision()?}),
        )
    }
    fn recovery_purge_trail(&self, actor: &Actor) -> Result<()> {
        // Keep bounded internal creation anchors so old tasks retain their
        // original creation time. The new purge event keeps sync monotonic.
        let floor = self.meta()?.2;
        self.db.execute(
            "UPDATE recovery_settings SET history_floor=? WHERE id=1",
            [floor],
        )?;
        self.db.execute("DELETE FROM history_details", [])?;
        self.db.execute("DELETE FROM events WHERE seq NOT IN (SELECT MIN(e.seq) FROM events e JOIN objects o ON o.id=e.object_id WHERE o.kind='task' AND o.deleted=0 GROUP BY e.object_id)",[])?;
        self.db.execute("INSERT INTO events(generation,object_id,action,actor,at) VALUES(?,'workspace','history.purge',?,?)",params![self.meta()?.1,actor.user_id,now()])?;
        Ok(())
    }
}
