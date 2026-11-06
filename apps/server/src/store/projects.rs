//! Project/work-list continuity. Categories describe work; parents group it.
use super::*;
use std::collections::BTreeSet;
use std::collections::HashMap;

impl Store {
    pub(super) fn project_fields(&self, input: &Value, record: &mut Record) -> Result<()> {
        if record.kind == "project" {
            let status = input
                .get("status")
                .or_else(|| record.data.get("status"))
                .cloned()
                .unwrap_or(json!("active"));
            if !matches!(status.as_str(), Some("active" | "on_hold" | "archived")) {
                return fail(400, "Choose Active, On hold or Finished for the project");
            }
            record.data["status"] = status;
            if input.get("due").is_some_and(|v| !v.is_string()) {
                return fail(400, "Choose an optional project target day");
            }
            let due = input
                .get("due")
                .or_else(|| record.data.get("due"))
                .and_then(Value::as_str)
                .unwrap_or("");
            Self::due(due)?;
            record.data["due"] = json!(due);
            self.assignment(input, record)?;
        } else if record.kind == "list" {
            let value = input
                .get("project_id")
                .or_else(|| record.data.get("project_id"))
                .cloned()
                .unwrap_or(json!(""));
            let parent = value
                .as_str()
                .ok_or(Fault(400, "Choose a project or Standalone list".into()))?;
            if !parent.is_empty() {
                valid_id(parent)?;
                if self.record(parent)?.kind != "project" {
                    return fail(400, "A list can belong to a project");
                }
            }
            record.data["project_id"] = json!(parent);
            let work = input
                .get("items_are_tasks")
                .or_else(|| record.data.get("items_are_tasks"))
                .cloned()
                .unwrap_or(json!(false));
            if !work.is_boolean() {
                return fail(400, "Choose whether list items are tasks");
            }
            if record.data["items_are_tasks"] == true && work == false {
                let ids = self
                    .db
                    .prepare("SELECT id FROM objects WHERE parent=? AND kind='task' AND deleted=0")?
                    .query_map([&record.id], |r| r.get::<_, String>(0))?
                    .collect::<std::result::Result<Vec<_>, _>>()?;
                for id in ids {
                    if self.record(&id)?.data["status"] == "in_progress" {
                        return fail(409,"Release or finish active work before converting this task list to a reference checklist");
                    }
                }
            }
            record.data["items_are_tasks"] = work;
        }
        Ok(())
    }
    pub(super) fn project_for_record(&self, record: &Record) -> Result<Option<String>> {
        if record.kind == "project" {
            return Ok(Some(record.id.clone()));
        }
        let parent = if matches!(record.kind.as_str(), "task" | "list") {
            record.data["project_id"].as_str()
        } else {
            record.data["task_id"].as_str()
        };
        let Some(parent) = parent.filter(|s| !s.is_empty()) else {
            return Ok(None);
        };
        let Some(row) = self.history_record(parent)? else {
            return Ok(None);
        };
        if row.kind == "project" {
            Ok(Some(row.id))
        } else if matches!(row.kind.as_str(), "list" | "task") {
            self.project_for_record(&row)
        } else {
            Ok(None)
        }
    }
    /// Validate the resulting graph, including restores/imports, before commit.
    /// It deliberately does not auto-complete tasks, change dates or drop claims.
    pub(super) fn project_validate(&self, overlay: &[recovery::SavedRecord]) -> Result<()> {
        self.project_validate_inner(overlay, None)
    }
    pub(super) fn project_validate_roots(&self, roots: &BTreeSet<String>) -> Result<()> {
        self.project_validate_inner(&[], Some(roots))
    }
    fn project_validate_inner(
        &self,
        overlay: &[recovery::SavedRecord],
        roots: Option<&BTreeSet<String>>,
    ) -> Result<()> {
        let mut records = HashMap::new();
        let mut query=self.db.prepare("SELECT id,kind,revision,data FROM objects WHERE deleted=0 AND kind IN ('project','list','task')")?;
        let rows = query.query_map([], |r| {
            Ok((
                r.get::<_, String>(0)?,
                r.get::<_, String>(1)?,
                r.get::<_, i64>(2)?,
                r.get::<_, Vec<u8>>(3)?,
            ))
        })?;
        for row in rows {
            let (id, kind, revision, data) = row?;
            let data = serde_json::from_slice(&self.vault.open(&format!("{kind}:{id}"), &data)?)
                .map_err(|_| Fault(500, "Project content could not be read".into()))?;
            records.insert(
                id.clone(),
                Record {
                    id,
                    kind,
                    revision,
                    data,
                },
            );
        }
        for row in overlay {
            if row.deleted {
                records.remove(&row.record.id);
            } else if matches!(row.record.kind.as_str(), "project" | "list" | "task") {
                records.insert(row.record.id.clone(), row.record.clone());
            }
        }
        let mut work: HashMap<String, (usize, usize)> = HashMap::new();
        for record in records.values() {
            if !matches!(record.kind.as_str(), "list" | "task") {
                continue;
            }
            let parent = record.data["project_id"].as_str().unwrap_or("");
            if parent.is_empty() {
                continue;
            }
            let container = records.get(parent).ok_or(Fault(
                409,
                "A parent collection is missing. Restore or move its content before continuing"
                    .into(),
            ))?;
            let project = if record.kind == "list" {
                if container.kind != "project" {
                    return fail(409, "Lists can only be grouped under projects");
                }
                None
            } else if container.kind == "project" {
                Some(parent)
            } else if container.kind == "list" {
                if container.data["items_are_tasks"] == true {
                    container.data["project_id"]
                        .as_str()
                        .filter(|s| !s.is_empty())
                } else {
                    None
                }
            } else {
                return fail(409, "Tasks need a list, project or Inbox");
            };
            if let Some(project) = project {
                let counts = work.entry(project.into()).or_default();
                counts.0 += usize::from(record.data["status"] != "done");
                counts.1 += usize::from(record.data["status"] == "in_progress");
            }
        }
        for project in records
            .values()
            .filter(|r| r.kind == "project" && roots.is_none_or(|ids| ids.contains(&r.id)))
        {
            let (open, working) = work.get(&project.id).copied().unwrap_or_default();
            match project.data["status"].as_str().unwrap_or("active") {
                "archived" if open>0=>return fail(409,&format!("{} has {open} unfinished task(s). Reopen the project before adding/restoring work, or finish or move its work before archiving",Self::history_title(project))),
                "on_hold" if working>0=>return fail(409,&format!("{} has work in progress. Release or finish it before putting the project on hold; resume the project before starting work",Self::history_title(project))),
                "active"|"archived"|"on_hold"=>{},_=>return fail(400,"Invalid project state"),
            }
        }
        Ok(())
    }
}
