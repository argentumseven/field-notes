//! Encrypted, bounded content recovery. Identity and host configuration never
//! enter a version or content checkpoint. Every restore commits a new state.
use super::*;
use std::collections::{BTreeSet, HashSet};

mod restore;
mod retention;
mod snapshots;

#[derive(Serialize, Deserialize, Clone, PartialEq)]
#[serde(deny_unknown_fields)]
pub(super) struct RecoveryPolicy {
    pub enabled: bool,
    pub days: i64,
    pub storage_mib: i64,
    pub versions_per_item: i64,
    pub checkpoints: i64,
    pub history_days: i64,
    pub history_entries: i64,
    pub history_mib: i64,
}
impl Default for RecoveryPolicy {
    fn default() -> Self {
        Self {
            enabled: true,
            days: 90,
            storage_mib: 512,
            versions_per_item: 50,
            checkpoints: 10,
            history_days: 90,
            history_entries: 10_000,
            history_mib: 64,
        }
    }
}
#[derive(Serialize, Deserialize, Clone)]
pub(super) struct SavedRecord {
    pub(super) record: Record,
    pub(super) parent: Option<String>,
    pub(super) deleted: bool,
}
#[derive(Serialize, Deserialize, Clone)]
struct ContentState {
    root: Option<String>,
    records: Vec<SavedRecord>,
    ancestors: Vec<SavedRecord>,
    blobs: BTreeMap<String, String>,
}
#[derive(Serialize, Deserialize, Clone)]
struct SavedState {
    name: String,
    content: ContentState,
}

impl Store {
    pub(super) fn recovery_schema(&self) -> Result<()> {
        self.db.execute_batch("CREATE TABLE IF NOT EXISTS recovery_settings(id INTEGER PRIMARY KEY CHECK(id=1),lineage TEXT NOT NULL,revision INTEGER NOT NULL DEFAULT 1,policy BLOB NOT NULL,history_floor INTEGER NOT NULL DEFAULT 0,start_generation INTEGER NOT NULL DEFAULT 1);
          CREATE TABLE IF NOT EXISTS recovery_counters(lineage TEXT NOT NULL,target TEXT NOT NULL,value INTEGER NOT NULL,PRIMARY KEY(lineage,target));
          CREATE TABLE IF NOT EXISTS recovery_snapshots(id TEXT PRIMARY KEY,lineage TEXT NOT NULL,kind TEXT NOT NULL,target TEXT NOT NULL,version_index INTEGER NOT NULL,record_revision INTEGER NOT NULL,at INTEGER NOT NULL,actor TEXT NOT NULL,action TEXT NOT NULL,digest TEXT NOT NULL,data BLOB NOT NULL);
          CREATE INDEX IF NOT EXISTS recovery_by_target ON recovery_snapshots(lineage,target,version_index DESC);
          CREATE INDEX IF NOT EXISTS recovery_by_age ON recovery_snapshots(at);
          CREATE TABLE IF NOT EXISTS recovery_blobs(digest TEXT PRIMARY KEY,bytes BLOB NOT NULL);
          CREATE TABLE IF NOT EXISTS recovery_blob_refs(snapshot TEXT NOT NULL REFERENCES recovery_snapshots(id) ON DELETE CASCADE,digest TEXT NOT NULL REFERENCES recovery_blobs(digest),PRIMARY KEY(snapshot,digest));
          CREATE TABLE IF NOT EXISTS recovery_live_blobs(id TEXT PRIMARY KEY,digest TEXT NOT NULL);
          CREATE TABLE IF NOT EXISTS recovery_incarnations(target TEXT PRIMARY KEY,value INTEGER NOT NULL);
          CREATE TABLE IF NOT EXISTS recovery_previews(id TEXT PRIMARY KEY,lineage TEXT NOT NULL,actor TEXT NOT NULL,session TEXT NOT NULL,expires INTEGER NOT NULL,data BLOB NOT NULL);
          CREATE TABLE IF NOT EXISTS recovery_receipts(id TEXT PRIMARY KEY,actor TEXT NOT NULL,session TEXT NOT NULL,epoch TEXT NOT NULL,lineage TEXT NOT NULL,digest TEXT NOT NULL,data BLOB NOT NULL);")?;
        let exists: bool =
            self.db
                .query_row("SELECT EXISTS(SELECT 1 FROM recovery_settings)", [], |r| {
                    r.get(0)
                })?;
        if !exists {
            self.db.execute(
                "INSERT INTO recovery_settings(id,lineage,policy,start_generation) VALUES(1,?,?,?)",
                params![
                    id(),
                    self.vault.seal(
                        "recovery-policy",
                        &serde_json::to_vec(&RecoveryPolicy::default()).map_err(|_| Fault(
                            500,
                            "Recovery policy could not be encoded".into()
                        ))?
                    )?,
                    self.meta()?.1
                ],
            )?;
        }
        Ok(())
    }
    fn recovery_lineage(&self) -> Result<String> {
        Ok(self.db.query_row(
            "SELECT lineage FROM recovery_settings WHERE id=1",
            [],
            |r| r.get(0),
        )?)
    }
    pub(super) fn recovery_policy(&self) -> Result<RecoveryPolicy> {
        let bytes: Vec<u8> =
            self.db
                .query_row("SELECT policy FROM recovery_settings WHERE id=1", [], |r| {
                    r.get(0)
                })?;
        serde_json::from_slice(&self.vault.open("recovery-policy", &bytes)?)
            .map_err(|_| Fault(500, "Recovery settings need attention".into()))
    }
    pub(crate) fn recovery_revision(&self) -> Result<i64> {
        Ok(self.db.query_row(
            "SELECT revision FROM recovery_settings WHERE id=1",
            [],
            |r| r.get(0),
        )?)
    }
    pub(super) fn recovery_history_floor(&self) -> Result<i64> {
        Ok(self.db.query_row(
            "SELECT history_floor FROM recovery_settings WHERE id=1",
            [],
            |r| r.get(0),
        )?)
    }
    pub(super) fn recovery_start_generation(&self) -> Result<i64> {
        Ok(self.db.query_row(
            "SELECT start_generation FROM recovery_settings WHERE id=1",
            [],
            |r| r.get(0),
        )?)
    }
    pub fn content_recovery(&mut self, token: &str, body: Value) -> Result<Value> {
        let actor = self.actor(token)?;
        self.budget(&format!("recovery:{}", actor.user_id), 120)?;
        let tx = rusqlite::Transaction::new_unchecked(
            &self.db,
            rusqlite::TransactionBehavior::Immediate,
        )?;
        let (epoch, generation, _) = self.meta()?;
        if body["action"] == "result" {
            fields(&body, &["action", "epoch", "generation", "operation_id"])?;
            if body["epoch"] != epoch {
                return fail(
                    409,
                    "The server identity changed; review recovery before continuing",
                );
            }
            let result = self.recovery_result(&actor, &body)?;
            tx.commit()?;
            return Ok(result);
        }
        if matches!(body["action"].as_str(), Some("apply" | "checkpoint.create")) {
            let operation = text(&body, "operation_id", 36)?;
            valid_id(&operation)?;
            let digest = crypto::digest(
                &serde_json::to_vec(&body)
                    .map_err(|_| Fault(400, "Invalid recovery request".into()))?,
            );
            if let Some(result) = self.recovery_receipt(&actor, &operation, &digest)? {
                tx.commit()?;
                return Ok(result);
            }
        }
        if body["epoch"] != epoch || body["generation"] != generation {
            return fail(409, "Workspace changed; refresh recovery before continuing");
        }
        self.recovery_trim(&BTreeSet::new())?;
        let result = match body["action"].as_str().unwrap_or("") {
            "status" => {
                fields(&body, &["action", "epoch", "generation"])?;
                json!({"epoch":epoch,"generation":generation,"lineage":self.recovery_lineage()?,"policy":self.recovery_policy()?,"usage":self.recovery_usage()?,"history_revision":self.recovery_revision()?})
            }
            "versions" | "checkpoints" => self.recovery_list(&actor, &body)?,
            "checkpoint.create" => self.recovery_create_checkpoint(&actor, &body)?,
            "preview" => self.recovery_preview(&actor, &body)?,
            "apply" => self.recovery_apply(&actor, &body)?,
            _ => return fail(400, "Unknown recovery action"),
        };
        self.recovery_gc_blobs()?;
        tx.commit()?;
        Ok(result)
    }
    pub(super) fn recovery_incarnation(&self, target: &str) -> Result<i64> {
        Ok(self
            .db
            .query_row(
                "SELECT value FROM recovery_incarnations WHERE target=?",
                [target],
                |r| r.get(0),
            )
            .optional()?
            .unwrap_or(0))
    }
    pub(super) fn recovery_parent_fence(&self, target: &str, data: &Value) -> Result<()> {
        let current = self.recovery_incarnation(target)?;
        let supplied = data.get("parent_incarnation").and_then(Value::as_i64);
        if (current > 0 && supplied != Some(current)) || supplied.is_some_and(|n| n != current) {
            return fail(409,"This task was restored after the draft was made. Update or synchronize Field Notes and review the retained attachment or comment before adding it");
        }
        if data.get("parent_incarnation").is_some() && supplied.is_none() {
            return fail(400, "Invalid task restore identity");
        }
        Ok(())
    }
    fn recovery_list(&self, actor: &Actor, body: &Value) -> Result<Value> {
        fields(
            body,
            &["action", "epoch", "generation", "target", "before", "limit"],
        )?;
        let checkpoint = body["action"] == "checkpoints";
        let target = body["target"].as_str().unwrap_or("");
        if !checkpoint {
            valid_id(target)?;
        }
        let before = body["before"].as_i64().unwrap_or(i64::MAX);
        let limit = body["limit"].as_i64().unwrap_or(25);
        if !(1..=50).contains(&limit) || before < 1 {
            return fail(400, "Choose a recovery page up to 50 entries");
        }
        let rows=self.db.prepare("SELECT id,version_index,record_revision,at,actor,action FROM recovery_snapshots WHERE lineage=? AND kind=? AND target=? AND version_index<? ORDER BY version_index DESC LIMIT ?")?.query_map(params![self.recovery_lineage()?,if checkpoint{"checkpoint"}else{"version"},target,before,limit+1],|r|Ok((r.get::<_,String>(0)?,r.get::<_,i64>(1)?,r.get::<_,i64>(2)?,r.get::<_,i64>(3)?,r.get::<_,String>(4)?,r.get::<_,String>(5)?)))?.collect::<std::result::Result<Vec<_>,_>>()?;
        let more = rows.len() > limit as usize;
        let mut entries = Vec::new();
        for (sid, index, revision, at, user, action) in rows.into_iter().take(limit as usize) {
            // Read/decrypt one state at a time. A configured page may contain
            // many large checkpoints; retaining every ciphertext here would
            // exceed the app container's 512 MiB memory budget.
            let bytes: Vec<u8> = self.db.query_row(
                "SELECT data FROM recovery_snapshots WHERE id=?",
                [&sid],
                |r| r.get(0),
            )?;
            let person: String = self
                .db
                .query_row("SELECT name FROM users WHERE id=?", [user], |r| r.get(0))
                .optional()?
                .unwrap_or_else(|| "Former household member".into());
            let saved = match self.recovery_decode(&sid, &bytes) {
                Ok(saved) => saved,
                Err(_) => {
                    entries.push(json!({"id":sid,"index":index,"record_revision":revision,"at":at,"actor_name":person,"action":action,"title":"Unreadable saved state","kind":null,"deleted":false,"records":0,"images":0,"can_restore":false,"problem":"This saved state failed its integrity check. Other retained states remain available."}));
                    continue;
                }
            };
            let root = saved
                .content
                .root
                .as_ref()
                .and_then(|root| saved.content.records.iter().find(|r| &r.record.id == root));
            let own = root.is_none_or(|r| {
                !matches!(r.record.kind.as_str(), "event" | "image" | "comment")
                    || r.record.data["author_id"] == actor.user_id
            });
            entries.push(json!({"id":sid,"index":index,"record_revision":revision,"at":at,"actor_name":person,"action":action,"title":saved.name,"kind":root.map(|r|r.record.kind.as_str()),"deleted":root.is_some_and(|r|r.deleted),"records":saved.content.records.iter().filter(|r|!r.deleted).count(),"images":saved.content.blobs.len(),"can_restore":actor.role!="viewer"&&(!checkpoint||actor.admin())&&(actor.admin()||own)}));
        }
        let next = entries
            .last()
            .and_then(|r| r["index"].as_i64())
            .unwrap_or(before);
        Ok(
            json!({"epoch":self.meta()?.0,"generation":self.meta()?.1,"lineage":self.recovery_lineage()?,"entries":entries,"more":more,"next_before":next,"history_revision":self.recovery_revision()?}),
        )
    }
    fn recovery_create_checkpoint(&self, actor: &Actor, body: &Value) -> Result<Value> {
        fields(
            body,
            &[
                "action",
                "epoch",
                "generation",
                "name",
                "operation_id",
                "history_revision",
            ],
        )?;
        if !actor.admin() {
            return fail(
                403,
                "An administrator must create whole-content checkpoints",
            );
        }
        let operation = text(body, "operation_id", 36)?;
        valid_id(&operation)?;
        let digest = crypto::digest(
            &serde_json::to_vec(body)
                .map_err(|_| Fault(400, "Invalid checkpoint request".into()))?,
        );
        if let Some(result) = self.recovery_receipt(actor, &operation, &digest)? {
            return Ok(result);
        }
        if body["history_revision"].as_i64() != Some(self.recovery_revision()?) {
            return fail(
                409,
                "Recovery history changed; refresh before creating a checkpoint",
            );
        }
        let name = text(body, "name", 120)?;
        let content = self.recovery_content(None)?;
        let sid = self.recovery_save(
            actor,
            "checkpoint",
            "",
            (&name, "checkpoint.create"),
            &content,
            true,
        )?;
        self.recovery_trim(&BTreeSet::from([sid.clone()]))?;
        self.audit(
            actor,
            "checkpoint.create",
            &sid,
            &json!({"records":content.records.len()}),
        )?;
        let result = json!({"ok":true,"checkpoint_id":sid,"epoch":self.meta()?.0,"generation":self.meta()?.1});
        self.recovery_save_receipt(actor, &operation, &digest, &result)?;
        Ok(result)
    }
}
