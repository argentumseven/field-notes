use super::*;

fn minute(value: &Value) -> Result<i64> {
    let text = value
        .as_str()
        .ok_or(Fault(400, "Choose a local time".into()))?;
    let bytes = text.as_bytes();
    if bytes.len() != 5
        || bytes[2] != b':'
        || !bytes
            .iter()
            .enumerate()
            .all(|(i, b)| i == 2 || b.is_ascii_digit())
    {
        return fail(400, "Choose a local time");
    }
    let hour: i64 = text[..2].parse().unwrap();
    let min: i64 = text[3..].parse().unwrap();
    if hour > 23 || min > 59 {
        return fail(400, "Choose a valid local time");
    }
    Ok(hour * 60 + min)
}
fn profile(rule: &Value) -> Result<()> {
    if !matches!(
        rule["importance"].as_str(),
        Some("quiet" | "normal" | "urgent")
    ) || !matches!(rule["sound"].as_str(), Some("chime" | "bell" | "pulse"))
        || !matches!(rule["volume"].as_i64(), Some(25 | 50 | 75 | 100))
        || !rule["vibrate"].is_boolean()
    {
        return fail(
            400,
            "Choose reminder importance, sound, level and vibration",
        );
    }
    valid_id(&text(rule, "id", 36)?)
}
impl Store {
    pub(super) fn reminders(data: &Value, record: &mut Record) -> Result<()> {
        let Some(value) = data.get("reminders") else {
            return Ok(());
        };
        fields(value, &["alerts", "repeat", "timing_version"])?;
        if value
            .get("timing_version")
            .is_some_and(|v| !matches!(v.as_i64(), Some(1 | 2)))
        {
            return fail(400, "Unsupported reminder timing version");
        }
        let alerts = value["alerts"]
            .as_array()
            .ok_or(Fault(400, "Reminders must contain an alert list".into()))?;
        if alerts.len() > 8 {
            return fail(400, "Use up to eight reminders for an item");
        }
        let mut ids = std::collections::BTreeSet::new();
        for alert in alerts {
            fields(
                alert,
                &[
                    "id",
                    "minutes_before",
                    "lead_time",
                    "day_time",
                    "importance",
                    "sound",
                    "volume",
                    "vibrate",
                ],
            )?;
            profile(alert)?;
            super::reminder_timing::validate_lead(alert, &value["timing_version"])?;
            super::reminder_timing::validate_day_time(alert, &value["timing_version"])?;
            if !ids.insert(alert["id"].as_str().unwrap())
                || !alert["minutes_before"]
                    .as_i64()
                    .is_some_and(|n| (0..=43_200).contains(&n))
            {
                return fail(
                    400,
                    "Reminder times must be unique rules, from the start to 30 days before",
                );
            }
        }
        if !value["repeat"].is_null() {
            let rule = &value["repeat"];
            fields(
                rule,
                &[
                    "id",
                    "weekdays",
                    "starts_on",
                    "ends_on",
                    "from",
                    "until",
                    "every_minutes",
                    "zone",
                    "importance",
                    "sound",
                    "volume",
                    "vibrate",
                ],
            )?;
            profile(rule)?;
            if !ids.insert(rule["id"].as_str().unwrap()) {
                return fail(400, "Reminder rule IDs must be distinct");
            }
            let start = text(rule, "starts_on", 10)?;
            Self::due(&start)?;
            let end = rule["ends_on"]
                .as_str()
                .ok_or(Fault(400, "Choose an optional last reminder day".into()))?;
            Self::due(end)?;
            if !end.is_empty() && end < start.as_str() {
                return fail(400, "The last reminder day cannot precede the first");
            }
            let days = rule["weekdays"]
                .as_array()
                .ok_or(Fault(400, "Choose reminder days".into()))?;
            let mut seen = std::collections::BTreeSet::new();
            if days.len() > 7
                || days.iter().any(|d| {
                    !d.as_i64()
                        .is_some_and(|n| (1..=7).contains(&n) && seen.insert(n))
                })
            {
                return fail(400, "Choose distinct weekdays");
            }
            minute(&rule["from"])?;
            minute(&rule["until"])?;
            let interval = rule["every_minutes"]
                .as_i64()
                .ok_or(Fault(400, "Choose a reminder interval".into()))?;
            if !(1..=1440).contains(&interval) {
                return fail(400, "Choose a reminder interval from 1 to 1440 minutes");
            }
            let zone = text(rule, "zone", 80)?;
            if zone.parse::<chrono_tz::Tz>().is_err() {
                return fail(400, "Unknown reminder timezone");
            }
        }
        let mut canonical = value.clone();
        if !super::reminder_timing::extended_timing(&canonical) {
            canonical.as_object_mut().unwrap().remove("timing_version");
        }
        record.data["reminders"] = canonical;
        Ok(())
    }
}
