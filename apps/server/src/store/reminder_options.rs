use super::*;

impl Store {
    pub(super) fn reminder_options(
        &self,
        data: &Value,
        record: &mut Record,
        _actor: &Actor,
    ) -> Result<()> {
        let default = json!({"audience":{"mode":"everyone","people":[]},"playback":{}});
        let Some(value) = data.get("reminder_options").or({
            if record.revision == 0 {
                Some(&default)
            } else {
                None
            }
        }) else {
            return Ok(());
        };
        fields(value, &["audience", "playback"])?;
        let audience = &value["audience"];
        fields(audience, &["mode", "people"])?;
        if !matches!(
            audience["mode"].as_str(),
            Some("everyone" | "people" | "assigned")
        ) {
            return fail(400, "Choose who receives reminders");
        }
        let people = audience["people"]
            .as_array()
            .ok_or(Fault(400, "Choose reminder recipients".into()))?;
        let mut seen = std::collections::BTreeSet::new();
        let unchanged_audience = record
            .data
            .get("reminder_options")
            .and_then(|v| v.get("audience"))
            == Some(audience);
        if people.len() > 64 || (audience["mode"] == "people" && people.is_empty()) {
            return fail(400, "Choose one or more reminder recipients (maximum 64)");
        }
        for person in people {
            let id = person
                .as_str()
                .ok_or(Fault(400, "Invalid reminder recipient".into()))?;
            valid_id(id)?;
            let active: bool = self.db.query_row(
                "SELECT EXISTS(SELECT 1 FROM users WHERE id=? AND active=1)",
                [id],
                |r| r.get(0),
            )?;
            if !seen.insert(id) || (!active && !unchanged_audience) {
                return fail(400, "Choose distinct active household people for reminders");
            }
        }
        let sounds = value["playback"]
            .as_object()
            .ok_or(Fault(400, "Choose reminder sound settings".into()))?;
        if sounds.len() > 9 {
            return fail(400, "Too many reminder sound settings");
        }
        for (id, p) in sounds {
            valid_id(id)?;
            fields(
                p,
                &[
                    "tone",
                    "duration_seconds",
                    "alarm",
                    "unmute",
                    "alarm_volume",
                ],
            )?;
            if !matches!(
                p["tone"].as_str(),
                Some(
                    "" | "chime"
                        | "bell"
                        | "pulse"
                        | "beacon"
                        | "marimba"
                        | "orbit"
                        | "sunrise"
                        | "siren"
                )
            ) || !matches!(
                p["duration_seconds"].as_i64(),
                Some(0 | 5 | 10 | 15 | 30 | 60)
            ) || !p["alarm"].is_boolean()
                || !p["unmute"].is_boolean()
                || !p["alarm_volume"]
                    .as_i64()
                    .is_some_and(|n| (25..=100).contains(&n))
                || (p["unmute"] == true && p["alarm"] != true)
            {
                return fail(
                    400,
                    "Choose a supported sound, duration, alarm mode and volume",
                );
            }
        }
        record.data["reminder_options"] = value.clone();
        Ok(())
    }
    pub(super) fn reminder_recipient(record: &Record, user: &str) -> bool {
        let Some(audience) = record
            .data
            .get("reminder_options")
            .and_then(|v| v.get("audience"))
        else {
            return true;
        };
        match audience["mode"].as_str() {
            Some("everyone") => true,
            Some("people") => audience["people"]
                .as_array()
                .is_some_and(|a| a.iter().any(|p| p.as_str() == Some(user))),
            Some("assigned") => record.data["assignee"]
                .as_str()
                .filter(|s| !s.is_empty())
                .or_else(|| record.data["created_by"].as_str())
                .or_else(|| record.data["author_id"].as_str())
                .is_none_or(|id| id == user),
            _ => false,
        }
    }
    /// Preserve shared calendar content while suppressing alerts for other
    /// people, including clients that predate recipient controls. Modern
    /// editors can still inspect the original rules in the additive field.
    pub fn notification_projection(mut record: Record, actor: &Actor) -> Record {
        if matches!(record.kind.as_str(), "event" | "task" | "note")
            && !Self::reminder_recipient(&record, &actor.user_id)
        {
            record.data["reminder_rules"] =
                record.data.get("reminders").cloned().unwrap_or(Value::Null);
            record.data["reminders"] = json!({"alerts":[],"repeat":null});
        }
        record
    }
    pub fn compatibility() -> Value {
        let release: Value = serde_json::from_str(include_str!(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../../release.json"
        )))
        .expect("valid release metadata");
        json!({"server_version":env!("CARGO_PKG_VERSION"),"protocol_min":1,"protocol_max":1,"minimum_client_code":10,"recommended_client_code":release["android_version_code"],"features":["reminder-options-v1","household-planner-v3","history-v1","history-items-v1","content-recovery-v1"]})
    }
}
