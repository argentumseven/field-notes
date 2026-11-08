use super::*;

pub(super) fn extended_timing(rules: &Value) -> bool {
    rules["alerts"].as_array().is_some_and(|alerts| {
        alerts
            .iter()
            .any(|a| a.get("lead_time").is_some() || a.get("day_time").is_some())
    })
}

pub(super) fn validate_lead(alert: &Value, version: &Value) -> Result<()> {
    let Some(lead) = alert.get("lead_time") else {
        return Ok(());
    };
    fields(lead, &["amount", "unit", "zone"])?;
    let maximum = match lead["unit"].as_str() {
        Some("minutes") => 5_270_400,
        Some("hours") => 87_840,
        Some("days") => 3660,
        Some("weeks") => 522,
        Some("months") => 120,
        Some("years") => 10,
        _ => return fail(400, "Choose minutes, hours, days, weeks, months or years"),
    };
    if !matches!(version.as_i64(), Some(1 | 2))
        || alert["minutes_before"].as_i64() != Some(0)
        || !lead["amount"]
            .as_i64()
            .is_some_and(|n| (0..=maximum).contains(&n))
        || lead["zone"]
            .as_str()
            .is_none_or(|z| z.len() > 80 || z.parse::<chrono_tz::Tz>().is_err())
    {
        return fail(
            400,
            "Choose one valid reminder amount, unit and calendar timezone (up to ten years)",
        );
    }
    Ok(())
}

/// Old strict decoders keep receiving the original compatible shape. Do not let
/// an old phone treat an unfamiliar calendar lead as an at-start reminder.
pub(super) fn has_day_time(rules: &Value) -> bool {
    rules["alerts"]
        .as_array()
        .is_some_and(|a| a.iter().any(|r| r.get("day_time").is_some()))
}
pub(super) fn validate_day_time(alert: &Value, version: &Value) -> Result<()> {
    let Some(day) = alert.get("day_time") else {
        return Ok(());
    };
    fields(day, &["days_before", "time", "zone"])?;
    let time = day["time"].as_str().unwrap_or("");
    if version.as_i64() != Some(2)
        || alert["minutes_before"].as_i64() != Some(0)
        || alert.get("lead_time").is_some()
        || !day["days_before"]
            .as_i64()
            .is_some_and(|n| (0..=3660).contains(&n))
        || time.len() != 5
        || time.as_bytes().get(2) != Some(&b':')
        || chrono::NaiveTime::parse_from_str(time, "%H:%M").is_err()
        || day["zone"]
            .as_str()
            .is_none_or(|z| z.len() > 80 || z.parse::<chrono_tz::Tz>().is_err())
    {
        return fail(
            400,
            "Choose a valid reminder day, clock time and calendar timezone",
        );
    }
    Ok(())
}
/// Projection for 0.10's strict timing-v1 scheduler.
pub(super) fn timing_v1_rules(canonical: &Value) -> Value {
    if !canonical.is_object() {
        return canonical.clone();
    }
    let mut value = canonical.clone();
    if let Some(alerts) = value["alerts"].as_array_mut() {
        alerts.retain(|a| a.get("day_time").is_none())
    }
    if extended_timing(&value) {
        value["timing_version"] = json!(1)
    } else {
        value.as_object_mut().unwrap().remove("timing_version");
    }
    value
}
/// Original strict decoders never receive an unfamiliar rule as an at-start alert.
pub(super) fn legacy_rules(canonical: &Value) -> Value {
    if !canonical.is_object() {
        return canonical.clone();
    }
    let mut value = timing_v1_rules(canonical);
    if let Some(object) = value.as_object_mut() {
        object.remove("timing_version");
    }
    if let Some(alerts) = value["alerts"].as_array_mut() {
        alerts.retain(|a| a.get("lead_time").is_none())
    }
    value
}
