mod admin;
mod auth;
mod commands;
mod enrollment;
mod organization;
mod planning;
pub use enrollment::NetworkConfig;

use crate::crypto::{self, Vault};
use base64::{engine::general_purpose::STANDARD, Engine};
use rusqlite::{params, Connection, OptionalExtension};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::{
    collections::BTreeMap,
    io::Cursor,
    path::Path,
    time::{SystemTime, UNIX_EPOCH},
};
use uuid::Uuid;

pub type Result<T> = std::result::Result<T, Fault>;
#[derive(Debug)]
pub struct Fault(pub u16, pub String);
impl From<rusqlite::Error> for Fault {
    fn from(_: rusqlite::Error) -> Self {
        Self(500, "Storage operation failed".into())
    }
}
impl From<String> for Fault {
    fn from(e: String) -> Self {
        Self(500, e)
    }
}
pub fn fail<T>(status: u16, msg: &str) -> Result<T> {
    Err(Fault(status, msg.into()))
}
pub fn now() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs() as i64
}
pub fn id() -> String {
    Uuid::new_v4().to_string()
}
pub fn valid_id(s: &str) -> Result<()> {
    Uuid::parse_str(s).map_err(|_| Fault(400, "Expected a UUID".into()))?;
    Ok(())
}
fn text(v: &Value, key: &str, max: usize) -> Result<String> {
    let s = v.get(key).and_then(Value::as_str).unwrap_or("").trim();
    if s.is_empty() || s.len() > max {
        return fail(400, &format!("{key} must contain 1–{max} bytes"));
    }
    Ok(s.into())
}
fn fields(v: &Value, allowed: &[&str]) -> Result<()> {
    let obj = v
        .as_object()
        .ok_or(Fault(400, "Expected an object".into()))?;
    if obj.keys().any(|k| !allowed.contains(&k.as_str())) {
        return fail(400, "Unexpected field");
    }
    Ok(())
}

#[derive(Clone, Serialize, Deserialize)]
pub struct Actor {
    pub user_id: String,
    pub name: String,
    pub role: String,
    pub session_id: String,
    pub elevated: bool,
}
impl Actor {
    pub fn admin(&self) -> bool {
        self.role == "owner" || self.role == "admin"
    }
}
#[derive(Serialize, Deserialize, Clone)]
pub struct Record {
    pub id: String,
    pub kind: String,
    pub revision: i64,
    pub data: Value,
}
#[derive(Serialize, Deserialize, Clone)]
#[serde(deny_unknown_fields)]
pub struct Command {
    pub operation_id: String,
    pub epoch: String,
    pub generation: i64,
    pub action: String,
    pub target: String,
    pub expected_revision: Option<i64>,
    pub data: Value,
}
#[derive(Serialize)]
pub struct Snapshot {
    pub protocol: u16,
    pub epoch: String,
    pub generation: i64,
    pub sequence: i64,
    pub records: Vec<Record>,
    pub actor: Actor,
}
pub struct Store {
    pub db: Connection,
    pub vault: Vault,
    pub network: Option<NetworkConfig>,
    pub peer_status: Option<std::path::PathBuf>,
    pub development: bool,
    data_dir: std::path::PathBuf,
}

impl Store {
    pub fn open(data: &Path, key: &Path) -> std::result::Result<Self, String> {
        std::fs::create_dir_all(data).map_err(|e| e.to_string())?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            std::fs::set_permissions(data, std::fs::Permissions::from_mode(0o700))
                .map_err(|e| e.to_string())?;
        }
        let vault = Vault::load(key)?;
        let db = Connection::open(data.join("field-notes.sqlite3")).map_err(|e| e.to_string())?;
        let version: i64 = db
            .query_row("PRAGMA user_version", [], |r| r.get(0))
            .map_err(|e| e.to_string())?;
        if version > 2 {
            return Err(
                "Database was created by a newer server; refusing a schema downgrade".into(),
            );
        }
        db.busy_timeout(std::time::Duration::from_secs(5))
            .map_err(|e| e.to_string())?;
        db.execute_batch("PRAGMA journal_mode=WAL; PRAGMA foreign_keys=ON; PRAGMA secure_delete=ON; PRAGMA synchronous=FULL;
          CREATE TABLE IF NOT EXISTS meta(id INTEGER PRIMARY KEY CHECK(id=1),epoch TEXT NOT NULL,generation INTEGER NOT NULL,check_key BLOB NOT NULL);
          CREATE TABLE IF NOT EXISTS users(id TEXT PRIMARY KEY,name TEXT NOT NULL UNIQUE COLLATE NOCASE,hash TEXT NOT NULL,role TEXT NOT NULL CHECK(role IN ('owner','admin','member','viewer')),active INTEGER NOT NULL DEFAULT 1);
          CREATE TABLE IF NOT EXISTS sessions(id TEXT PRIMARY KEY,user_id TEXT NOT NULL REFERENCES users(id),name TEXT NOT NULL,expires INTEGER NOT NULL,elevated INTEGER NOT NULL DEFAULT 0,created INTEGER NOT NULL);
          CREATE TABLE IF NOT EXISTS objects(id TEXT PRIMARY KEY,kind TEXT NOT NULL,parent TEXT,revision INTEGER NOT NULL,deleted INTEGER NOT NULL DEFAULT 0,data BLOB NOT NULL);
          CREATE INDEX IF NOT EXISTS objects_parent ON objects(parent);
          CREATE TABLE IF NOT EXISTS operations(id TEXT PRIMARY KEY,user_id TEXT NOT NULL,session_id TEXT NOT NULL,digest TEXT NOT NULL,result BLOB NOT NULL);
          CREATE TABLE IF NOT EXISTS events(seq INTEGER PRIMARY KEY AUTOINCREMENT,generation INTEGER NOT NULL,object_id TEXT NOT NULL,action TEXT NOT NULL,actor TEXT NOT NULL,at INTEGER NOT NULL);
          CREATE TABLE IF NOT EXISTS audit(seq INTEGER PRIMARY KEY AUTOINCREMENT,actor TEXT NOT NULL,action TEXT NOT NULL,target TEXT NOT NULL,at INTEGER NOT NULL,data BLOB NOT NULL);
          CREATE TABLE IF NOT EXISTS blobs(id TEXT PRIMARY KEY REFERENCES objects(id) DEFERRABLE INITIALLY DEFERRED,bytes BLOB NOT NULL);
          CREATE TABLE IF NOT EXISTS login_limits(key TEXT PRIMARY KEY,attempts INTEGER NOT NULL,until_time INTEGER NOT NULL);
          CREATE TABLE IF NOT EXISTS enrollment_invites(id TEXT PRIMARY KEY,token_hash TEXT NOT NULL UNIQUE,issuer TEXT NOT NULL REFERENCES users(id),user_id TEXT NOT NULL REFERENCES users(id),role TEXT NOT NULL,expires INTEGER NOT NULL,device_id TEXT,data BLOB NOT NULL);
          CREATE TABLE IF NOT EXISTS devices(id TEXT PRIMARY KEY,user_id TEXT NOT NULL REFERENCES users(id),public_key TEXT NOT NULL UNIQUE,address TEXT UNIQUE,name TEXT NOT NULL,state TEXT NOT NULL,claim_hash TEXT NOT NULL,activation_hash TEXT NOT NULL,expires INTEGER NOT NULL,verification TEXT NOT NULL,data BLOB NOT NULL);
          CREATE TABLE IF NOT EXISTS device_sessions(session_id TEXT PRIMARY KEY REFERENCES sessions(id) ON DELETE CASCADE,device_id TEXT NOT NULL REFERENCES devices(id));
          PRAGMA user_version=2;").map_err(|e|e.to_string())?;
        let check: Option<Vec<u8>> = db
            .query_row("SELECT check_key FROM meta WHERE id=1", [], |r| r.get(0))
            .optional()
            .map_err(|e| e.to_string())?;
        if let Some(b) = check {
            if vault.open("key-check", &b)? != b"field-notes-v1" {
                return Err("Incorrect key".into());
            }
        } else {
            let count: i64 = db
                .query_row(
                    "SELECT (SELECT COUNT(*) FROM users)+(SELECT COUNT(*) FROM objects)",
                    [],
                    |r| r.get(0),
                )
                .map_err(|e| e.to_string())?;
            if count != 0 {
                return Err(
                    "Vault metadata missing from an existing installation; recovery required"
                        .into(),
                );
            }
            db.execute(
                "INSERT INTO meta VALUES(1,?,1,?)",
                params![id(), vault.seal("key-check", b"field-notes-v1")?],
            )
            .map_err(|e| e.to_string())?;
        }
        Ok(Self {
            db,
            vault,
            network: None,
            peer_status: None,
            development: false,
            data_dir: data.to_path_buf(),
        })
    }
    pub fn meta(&self) -> Result<(String, i64, i64)> {
        Ok(self.db.query_row("SELECT epoch,generation,COALESCE((SELECT MAX(seq) FROM events),0) FROM meta WHERE id=1",[],|r|Ok((r.get(0)?,r.get(1)?,r.get(2)?)))?)
    }
    pub fn record(&self, oid: &str) -> Result<Record> {
        let row: Option<(String, i64, Vec<u8>)> = self
            .db
            .query_row(
                "SELECT kind,revision,data FROM objects WHERE id=? AND deleted=0",
                [oid],
                |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
            )
            .optional()?;
        let (kind, revision, data) = row.ok_or(Fault(404, "Item no longer exists".into()))?;
        let decrypted = self.vault.open(&format!("{kind}:{oid}"), &data)?;
        let mut data: Value = serde_json::from_slice(&decrypted)
            .map_err(|_| Fault(500, "Invalid encrypted data".into()))?;
        if kind == "task" {
            let created: i64 = self.db.query_row(
                "SELECT COALESCE(MIN(at),0) FROM events WHERE object_id=?",
                [oid],
                |r| r.get(0),
            )?;
            let object = data
                .as_object_mut()
                .ok_or(Fault(500, "Invalid task data".into()))?;
            object.entry("created_at").or_insert(json!(created));
            object.entry("priority").or_insert(json!(3));
            object.entry("estimate_minutes").or_insert(json!(0));
            object.entry("planned_for").or_insert(json!(""));
            object.entry("pinned").or_insert(json!(false));
            object.entry("blocked").or_insert(json!(false));
        }
        Ok(Record {
            id: oid.into(),
            kind,
            revision,
            data,
        })
    }
    fn save(&self, r: &Record, parent: Option<&str>) -> Result<()> {
        let raw =
            serde_json::to_vec(&r.data).map_err(|_| Fault(500, "Serialization failed".into()))?;
        let used: i64 = self.db.query_row(
            "SELECT COALESCE(SUM(length(data)),0) FROM objects",
            [],
            |row| row.get(0),
        )?;
        if used + raw.len() as i64 > 8 * 1024 * 1024 {
            return fail(
                413,
                "Workspace metadata capacity reached; export and clear old data",
            );
        }
        let encrypted = self.vault.seal(&format!("{}:{}", r.kind, r.id), &raw)?;
        self.db.execute("INSERT INTO objects(id,kind,parent,revision,data) VALUES(?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET revision=excluded.revision,data=excluded.data",params![r.id,r.kind,parent,r.revision,encrypted])?;
        Ok(())
    }
    pub fn snapshot(&self, actor: Actor) -> Result<Snapshot> {
        let (epoch, generation, sequence) = self.meta()?;
        let ids: Vec<String> = self
            .db
            .prepare("SELECT id FROM objects WHERE deleted=0 ORDER BY rowid")?
            .query_map([], |r| r.get(0))?
            .collect::<std::result::Result<_, _>>()?;
        let records = ids
            .iter()
            .map(|i| self.record(i))
            .collect::<Result<Vec<_>>>()?;
        Ok(Snapshot {
            protocol: 1,
            epoch,
            generation,
            sequence,
            records,
            actor,
        })
    }
    pub(super) fn audit(&self, a: &Actor, action: &str, target: &str, data: &Value) -> Result<()> {
        let encrypted = self.vault.seal(
            "audit",
            &serde_json::to_vec(data).map_err(|_| Fault(500, "Serialization".into()))?,
        )?;
        self.db.execute(
            "INSERT INTO audit(actor,action,target,at,data) VALUES(?,?,?,?,?)",
            params![a.user_id, action, target, now(), encrypted],
        )?;
        Ok(())
    }
    pub fn export_backup(&self) -> Result<Vec<u8>> {
        let data = self.db.serialize("main")?;
        Ok(self.vault.seal("backup:v1", &data)?)
    }
    pub fn stats(&self) -> Result<BTreeMap<String, i64>> {
        let mut out = BTreeMap::new();
        for table in ["objects", "blobs", "users", "sessions", "operations"] {
            out.insert(
                table.into(),
                self.db
                    .query_row(&format!("SELECT COUNT(*) FROM {table}"), [], |r| r.get(0))?,
            );
        }
        Ok(out)
    }
}
