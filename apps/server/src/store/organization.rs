use super::*;

impl Store {
    pub(super) fn container_ref(&self, value: &Value) -> Result<Option<String>> {
        let id = value
            .as_str()
            .ok_or(Fault(400, "Choose a list, project or Inbox".into()))?;
        if id.is_empty() {
            return Ok(None);
        }
        valid_id(id)?;
        if !["project", "list"].contains(&self.record(id)?.kind.as_str()) {
            return fail(400, "Tasks belong in a list, project or Inbox");
        }
        Ok(Some(id.to_owned()))
    }
    pub(super) fn organized_record(data: &Value, record: &mut Record, actor: &Actor) -> Result<()> {
        let note = record.kind == "note";
        fields(
            data,
            if note {
                &["title", "body", "pinned"][..]
            } else {
                &["title", "notes"][..]
            },
        )?;
        if record.revision == 0 {
            record.data = json!({"created_at":now(),"author_id":actor.user_id});
        }
        if data.get("title").is_some() || record.revision == 0 {
            record.data["title"] = json!(text(data, "title", if note { 240 } else { 160 })?);
        }
        let body = if note { "body" } else { "notes" };
        if let Some(value) = data.get(body) {
            let value = value
                .as_str()
                .ok_or(Fault(400, "Content must be text".into()))?;
            if value.len() > 16384 {
                return fail(400, "Content too long");
            }
            record.data[body] = json!(value);
        } else if record.data.get(body).is_none() {
            record.data[body] = json!("");
        }
        if note {
            if let Some(value) = data.get("pinned") {
                if !value.is_boolean() {
                    return fail(400, "Pinned must be true or false");
                };
                record.data["pinned"] = value.clone();
            } else if record.data.get("pinned").is_none() {
                record.data["pinned"] = json!(false);
            }
        }
        record.data["updated_at"] = json!(now());
        Ok(())
    }
}
