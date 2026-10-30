use super::*;

impl Store {
    pub(super) fn planning(data: &Value, record: &mut Record) -> Result<()> {
        for (field, default, min, max) in [("priority", 3, 1, 4), ("estimate_minutes", 0, 0, 480)] {
            let value = match data.get(field) {
                Some(v) => v
                    .as_i64()
                    .ok_or(Fault(400, format!("{field} must be an integer")))?,
                None => record
                    .data
                    .get(field)
                    .and_then(Value::as_i64)
                    .unwrap_or(default),
            };
            if !(min..=max).contains(&value) {
                return fail(400, &format!("{field} is outside its supported range"));
            }
            record.data[field] = json!(value);
        }
        for field in ["pinned", "blocked"] {
            let value = match data.get(field) {
                Some(v) => v
                    .as_bool()
                    .ok_or(Fault(400, format!("{field} must be true or false")))?,
                None => record
                    .data
                    .get(field)
                    .and_then(Value::as_bool)
                    .unwrap_or(false),
            };
            record.data[field] = json!(value);
        }
        if let Some(value) = data.get("planned_for") {
            let date = value
                .as_str()
                .ok_or(Fault(400, "Planned day must be text or empty".into()))?;
            Self::due(date)?;
            record.data["planned_for"] = json!(date);
        } else if record.data.get("planned_for").is_none() {
            record.data["planned_for"] = json!("");
        }
        Ok(())
    }

    pub(super) fn calendar_event(data: &Value, record: &mut Record, actor: &Actor) -> Result<()> {
        fields(
            data,
            &[
                "title",
                "notes",
                "all_day",
                "start_date",
                "end_date",
                "starts_at",
                "ends_at",
            ],
        )?;
        if record.revision == 0 {
            record.data = json!({"author_id":actor.user_id,"created_at":now(),"notes":""});
        }
        if record.revision > 0 && !actor.admin() && record.data["author_id"] != actor.user_id {
            return fail(
                403,
                "Only the event author or administrator may change this event",
            );
        }
        for (key, value) in data.as_object().unwrap() {
            record.data[key] = value.clone();
        }
        let title = text(&record.data, "title", 240)?;
        let notes = record.data["notes"]
            .as_str()
            .ok_or(Fault(400, "Notes must be text".into()))?;
        if notes.len() > 16384 {
            return fail(400, "Notes too long");
        }
        let all_day = record.data["all_day"]
            .as_bool()
            .ok_or(Fault(400, "Choose all-day or a time interval".into()))?;
        if all_day {
            let start = text(&record.data, "start_date", 10)?;
            let end = text(&record.data, "end_date", 10)?;
            Self::due(&start)?;
            Self::due(&end)?;
            let duration = date_days(&end) - date_days(&start);
            if !(1..=31).contains(&duration) {
                return fail(
                    400,
                    "All-day events need an exclusive end date 1–31 days after their start",
                );
            }
            record.data["starts_at"] = Value::Null;
            record.data["ends_at"] = Value::Null;
        } else {
            let start = record.data["starts_at"]
                .as_i64()
                .ok_or(Fault(400, "Start must be a UTC Unix second".into()))?;
            let end = record.data["ends_at"]
                .as_i64()
                .ok_or(Fault(400, "End must be a UTC Unix second".into()))?;
            if start < 0 || end > 7_258_118_400 || !(1..=31 * 86400).contains(&(end - start)) {
                return fail(400, "Invalid event time interval (maximum 31 days)");
            }
            record.data["start_date"] = json!("");
            record.data["end_date"] = json!("");
        }
        record.data["title"] = json!(title);
        record.data["updated_at"] = json!(now());
        Ok(())
    }
}

// Validated proleptic Gregorian civil date -> Unix day; time zones do not enter
// all-day events. Timed events store instants and clients render their local zone.
fn date_days(date: &str) -> i64 {
    let mut year: i64 = date[..4].parse().unwrap();
    let month: i64 = date[5..7].parse().unwrap();
    let day: i64 = date[8..].parse().unwrap();
    year -= i64::from(month <= 2);
    let era = year / 400;
    let yoe = year - era * 400;
    let mp = month + if month > 2 { -3 } else { 9 };
    era * 146097 + yoe * 365 + yoe / 4 - yoe / 100 + (153 * mp + 2) / 5 + day - 1 - 719468
}
