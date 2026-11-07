use super::*;

pub(super) fn extended_timing(rules: &Value) -> bool {
    rules["alerts"]
        .as_array()
        .is_some_and(|alerts| alerts.iter().any(|a| a.get("lead_time").is_some()))
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
    if version.as_i64() != Some(1)
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
pub(super) fn legacy_rules(canonical: &Value) -> Value {
    if !canonical.is_object() {
        return canonical.clone();
    }
    let mut value = canonical.clone();
    if let Some(object) = value.as_object_mut() {
        object.remove("timing_version");
    }
    if let Some(alerts) = value["alerts"].as_array_mut() {
        alerts.retain(|a| a.get("lead_time").is_none());
    }
    value
}
