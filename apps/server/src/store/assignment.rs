use super::*;

impl Store {
    pub fn directory_revision(&self) -> Result<i64> {
        Ok(self.db.query_row("SELECT COALESCE(MAX(seq),0) FROM audit WHERE action IN ('user.create','user.role','user.disable')", [], |r| r.get(0))?)
    }
    /// Household display directory only: never expose password hashes, sessions
    /// or administrative role controls to a content client.
    pub fn people(&self, token: &str) -> Result<Value> {
        self.actor(token)?;
        let people = self
            .db
            .prepare("SELECT id,name,active=1 AND role!='viewer' FROM users ORDER BY name COLLATE NOCASE,id")?
            .query_map([], |r| {
                Ok(json!({"id":r.get::<_,String>(0)?,"name":r.get::<_,String>(1)?,"can_assign":r.get::<_,bool>(2)?}))
            })?
            .collect::<std::result::Result<Vec<_>, _>>()?;
        Ok(json!({"people":people,"revision":self.directory_revision()?}))
    }

    pub(super) fn assignment(&self, data: &Value, record: &mut Record) -> Result<()> {
        let Some(value) = data.get("assignee") else {
            if record.revision == 0 {
                record.data["assignee"] = Value::Null;
            }
            return Ok(());
        };
        let id = if value.is_null() {
            ""
        } else {
            value
                .as_str()
                .ok_or(Fault(400, "Choose a household member or Unassigned".into()))?
        };
        let next = if id.is_empty() {
            Value::Null
        } else {
            json!(id)
        };
        if record.data["assignee"] == next {
            record.data["assignee"] = next;
            // Preserve historical assignments when a member becomes unavailable;
            // editing notes must not force an unrelated reassignment.
            return Ok(());
        }
        if record.data["status"] == "in_progress" {
            return fail(
                409,
                "Release the active task before handing it to another person",
            );
        }
        if !id.is_empty() {
            valid_id(id)?;
            let assignable: bool = self.db.query_row(
                "SELECT EXISTS(SELECT 1 FROM users WHERE id=? AND active=1 AND role!='viewer')",
                [id],
                |r| r.get(0),
            )?;
            if !assignable {
                return fail(
                    400,
                    "Choose an active household member with task permissions",
                );
            }
        }
        record.data["assignee"] = next;
        Ok(())
    }
}
