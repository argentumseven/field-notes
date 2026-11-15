use super::*;
use std::collections::{BTreeMap, BTreeSet};
pub(super) fn validate(plan: &Value) -> Result<()> {
    fields(plan, &["version", "items", "progress", "profile"])?;
    if plan["version"] != 1 {
        return fail(400, "Update the app for this cooking plan");
    }
    let items = plan["items"]
        .as_array()
        .ok_or(Fault(400, "Add cooking items".into()))?;
    if items.len() > 24 {
        return fail(400, "Use up to 24 cooking items");
    }
    let mut ids = BTreeSet::new();
    let mut item_ids = BTreeSet::new();
    let mut total = 0;
    for item in items {
        fields(item, &["id", "name", "notes", "stages"])?;
        valid_id(item["id"].as_str().unwrap_or(""))?;
        if !item_ids.insert(item["id"].as_str().unwrap()) {
            return fail(400, "Item IDs must be distinct");
        }
        text(item, "name", 160)?;
        if item["notes"].as_str().is_none_or(|v| v.len() > 8192) {
            return fail(400, "Use shorter item notes");
        }
        let stages = item["stages"]
            .as_array()
            .ok_or(Fault(400, "Add stages".into()))?;
        total += stages.len();
        if stages.len() > 12 || total > 64 {
            return fail(400, "Use up to 12 stages per item and 64 per plan");
        }
        for stage in stages {
            fields(
                stage,
                &[
                    "id",
                    "name",
                    "offset_minutes",
                    "duration_minutes",
                    "check_every_minutes",
                    "notes",
                    "notify_start",
                    "notify_end",
                    "after",
                    "wait_minutes",
                ],
            )?;
            let id = stage["id"].as_str().unwrap_or("");
            valid_id(id)?;
            if !ids.insert(id) {
                return fail(400, "Stage IDs must be distinct");
            }
            text(stage, "name", 160)?;
            if !stage["offset_minutes"]
                .as_i64()
                .is_some_and(|n| (-20160..=0).contains(&n))
                || !stage["duration_minutes"]
                    .as_i64()
                    .is_some_and(|n| (0..=20160).contains(&n))
                || !stage["check_every_minutes"]
                    .as_i64()
                    .is_some_and(|n| (0..=1440).contains(&n))
                || stage["notes"].as_str().is_none_or(|n| n.len() > 8192)
                || !stage["notify_start"].is_boolean()
                || !stage["notify_end"].is_boolean()
            {
                return fail(400, "Choose valid stage timing, checks and notes");
            }
        }
    }
    let stages: Vec<_> = items
        .iter()
        .flat_map(|i| i["stages"].as_array().unwrap())
        .collect();
    let mut pending = BTreeSet::new();
    let mut done = BTreeSet::new();
    for stage in &stages {
        let after = stage["after"]
            .as_array()
            .ok_or(Fault(400, "Choose phase dependencies".into()))?;
        let mut seen = BTreeSet::new();
        if after.len() > 64
            || after.iter().any(|id| {
                !ids.contains(id.as_str().unwrap_or(""))
                    || id == &stage["id"]
                    || !seen.insert(id.to_string())
            })
            || !stage["wait_minutes"]
                .as_i64()
                .is_some_and(|n| (0..=20160).contains(&n))
        {
            return fail(
                400,
                "Choose existing distinct dependencies and a waiting time",
            );
        }
        pending.insert(stage["id"].as_str().unwrap());
    }
    while !pending.is_empty() {
        let ready: Vec<_> = stages
            .iter()
            .filter(|s| {
                pending.contains(s["id"].as_str().unwrap())
                    && s["after"]
                        .as_array()
                        .unwrap()
                        .iter()
                        .all(|id| done.contains(id.as_str().unwrap()))
            })
            .map(|s| s["id"].as_str().unwrap())
            .collect();
        if ready.is_empty() {
            return fail(400, "Cooking dependencies cannot form a loop");
        }
        for id in ready {
            pending.remove(id);
            done.insert(id);
        }
    }
    let mut starts = BTreeMap::new();
    let mut remaining = stages.clone();
    while !remaining.is_empty() {
        let ready: Vec<_> = remaining
            .iter()
            .filter(|s| {
                stages
                    .iter()
                    .filter(|child| child["after"].as_array().unwrap().contains(&s["id"]))
                    .all(|child| starts.contains_key(child["id"].as_str().unwrap()))
            })
            .copied()
            .collect();
        for s in ready {
            let end = stages
                .iter()
                .filter(|child| child["after"].as_array().unwrap().contains(&s["id"]))
                .map(|child| {
                    starts[child["id"].as_str().unwrap()] - child["wait_minutes"].as_i64().unwrap()
                })
                .min()
                .unwrap_or(0)
                .min(s["offset_minutes"].as_i64().unwrap());
            let start = end - s["duration_minutes"].as_i64().unwrap();
            if start < -40320 {
                return fail(400, "The full preparation chain must fit within 28 days");
            }
            starts.insert(s["id"].as_str().unwrap(), start);
            remaining.retain(|r| r["id"] != s["id"]);
        }
    }
    let progress = plan["progress"]
        .as_array()
        .ok_or(Fault(400, "Invalid cooking progress".into()))?;
    if progress.len() > 1024 {
        return fail(
            400,
            "Cooking progress limit reached; clear completed history before adding more progress",
        );
    }
    let mut keys = BTreeSet::new();
    for p in progress {
        fields(p, &["occurrence", "stage_id", "state", "at"])?;
        if !p["occurrence"]
            .as_i64()
            .is_some_and(|n| (1..=4102444800).contains(&n))
            || !p["at"]
                .as_i64()
                .is_some_and(|n| (1..=4102444800).contains(&n))
            || !matches!(p["state"].as_str(), Some("running" | "done"))
            || !ids.contains(p["stage_id"].as_str().unwrap_or(""))
            || !keys.insert(format!("{}:{}", p["occurrence"], p["stage_id"]))
        {
            return fail(400, "Invalid or duplicate stage progress");
        }
    }
    if !plan["profile"].is_object() {
        return fail(400, "Choose cooking reminder sound settings");
    }
    let p = &plan["profile"];
    if !matches!(
        p["importance"].as_str(),
        Some("quiet" | "normal" | "urgent")
    ) || !matches!(
        p["sound"].as_str(),
        Some(
            "chime"
                | "bell"
                | "pulse"
                | "beacon"
                | "marimba"
                | "orbit"
                | "sunrise"
                | "siren"
                | "signal"
                | "crystal"
                | "radar"
                | "klaxon"
        )
    ) || !matches!(p["volume"].as_i64(), Some(25 | 50 | 75 | 100))
        || !p["gain"].as_i64().is_some_and(|n| (25..=200).contains(&n))
        || !matches!(p["duration"].as_i64(), Some(0 | 5 | 10 | 15 | 30 | 60))
        || !p["alarm"].is_boolean()
        || !p["untilDismissed"].is_boolean()
        || p["untilDismissed"] == true && p["alarm"] != true
    {
        return fail(400, "Choose valid cooking audio settings");
    }
    fields(
        p,
        &[
            "importance",
            "sound",
            "volume",
            "vibrate",
            "duration",
            "alarm",
            "unmute",
            "alarmVolume",
            "notificationUnmute",
            "notificationVolume",
            "gain",
            "untilDismissed",
        ],
    )?;
    if ["vibrate", "unmute", "notificationUnmute"]
        .iter()
        .any(|k| !p[*k].is_boolean())
        || ["alarmVolume", "notificationVolume"]
            .iter()
            .any(|k| !p[*k].as_i64().is_some_and(|n| (25..=100).contains(&n)))
        || p["unmute"] == true && p["alarm"] != true
        || p["notificationUnmute"] == true && p["alarm"] == true
    {
        return fail(400, "Choose valid cooking volume controls");
    }
    Ok(())
}
impl Store {
    pub(super) fn cooking_plan(
        &self,
        data: &Value,
        record: &mut Record,
        importing: bool,
    ) -> Result<()> {
        let Some(plan) = data.get("meal_plan") else {
            return Ok(());
        };
        if plan.is_null() {
            record.data.as_object_mut().unwrap().remove("meal_plan");
            return Ok(());
        }
        if !matches!(record.data["meal_kind"].as_str(), Some("dinner" | "prep"))
            || record.data["all_day"] == true
        {
            return fail(400, "Cooking plans need a timed dinner or prep session");
        }
        fields(plan, &["version", "items", "progress", "profile"])?;
        let mut saved = plan.clone();
        if !importing && record.revision > 0 {
            saved["progress"] = record.data["meal_plan"]["progress"]
                .as_array()
                .cloned()
                .map(Value::Array)
                .unwrap_or(json!([]));
            let ids: BTreeSet<_> = saved["items"]
                .as_array()
                .into_iter()
                .flatten()
                .flat_map(|i| i["stages"].as_array().into_iter().flatten())
                .filter_map(|s| s["id"].as_str().map(str::to_owned))
                .collect();
            if let Some(p) = saved["progress"].as_array_mut() {
                p.retain(|v| ids.contains(v["stage_id"].as_str().unwrap_or("")));
            }
        }
        validate(&saved)?;
        record.data["meal_plan"] = saved;
        Ok(())
    }
    pub(super) fn cooking_clear(
        &self,
        c: &Command,
        record: &mut Record,
        actor: &Actor,
    ) -> Result<()> {
        fields(&c.data, &[])?;
        if record.kind != "event" || (!actor.admin() && record.data["author_id"] != actor.user_id) {
            return fail(
                403,
                "Only the meal author or administrator may clear cooking history",
            );
        }
        let mut plan = record.data["meal_plan"].clone();
        validate(&plan)?;
        plan["progress"]
            .as_array_mut()
            .unwrap()
            .retain(|p| p["state"] != "done" || p["occurrence"].as_i64().unwrap() >= now() - 86400);
        record.data["meal_plan"] = plan;
        Ok(())
    }
    pub(super) fn cooking_progress(
        &self,
        c: &Command,
        record: &mut Record,
        actor: &Actor,
    ) -> Result<()> {
        if record.kind != "event" || (!actor.admin() && record.data["author_id"] != actor.user_id) {
            return fail(
                403,
                "Only the meal author or administrator may update cooking progress",
            );
        }
        fields(&c.data, &["occurrence", "stage_id", "state", "at"])?;
        let stage = c.data["stage_id"].as_str().unwrap_or("");
        let mut plan = record.data["meal_plan"].clone();
        validate(&plan)?;
        if !plan["items"].as_array().unwrap().iter().any(|i| {
            i["stages"]
                .as_array()
                .unwrap()
                .iter()
                .any(|s| s["id"] == stage)
        }) {
            return fail(404, "That cooking stage was removed");
        }
        let occurrence = c.data["occurrence"].as_i64().unwrap_or(0);
        if !(1..=4102444800).contains(&occurrence) {
            return fail(400, "Choose a valid meal occurrence");
        }
        // Progress cannot attach to a made-up occurrence of a recurring meal.
        if !occurrence_matches(record, occurrence) {
            return fail(
                409,
                "The meal occurrence changed; reopen it before updating progress",
            );
        }
        if !matches!(
            c.data["state"].as_str(),
            Some("planned" | "running" | "done")
        ) || !c.data["at"]
            .as_i64()
            .is_some_and(|n| (1..=4102444800).contains(&n))
        {
            return fail(400, "Choose a valid phase status and time");
        }
        let progress = plan["progress"].as_array_mut().unwrap();
        progress.retain(|p| !(p["occurrence"] == occurrence && p["stage_id"] == stage));
        if c.data["state"] != "planned" {
            progress.push(c.data.clone());
        }
        validate(&plan)?;
        record.data["meal_plan"] = plan;
        Ok(())
    }
}

fn occurrence_matches(record: &Record, at: i64) -> bool {
    use chrono::{Datelike, TimeZone};
    let first = record.data["starts_at"].as_i64().unwrap_or(0);
    let r = &record.data["recurrence"];
    if r.is_null() {
        return first == at;
    }
    let Some(zone) = r["zone"]
        .as_str()
        .and_then(|z| z.parse::<chrono_tz::Tz>().ok())
    else {
        return false;
    };
    let Some(start) = zone.timestamp_opt(first, 0).single() else {
        return false;
    };
    let Some(current) = zone.timestamp_opt(at, 0).single() else {
        return false;
    };
    let day = current.date_naive();
    let expected = if day == start.date_naive() {
        Some(first)
    } else {
        zone.from_local_datetime(&day.and_time(start.time()))
            .earliest()
            .map(|t| t.timestamp())
    };
    if expected != Some(at) {
        return false;
    }
    let date = day.to_string();
    let diff = (day - start.date_naive()).num_days();
    if diff < 0
        || start.time() != current.time()
        || r["until"]
            .as_str()
            .is_some_and(|s| !s.is_empty() && date.as_str() > s)
        || r["excluded"]
            .as_array()
            .is_some_and(|ds| ds.iter().any(|d| d == &date))
    {
        return false;
    }
    let n = r["interval"].as_i64().unwrap_or(1);
    match r["frequency"].as_str().unwrap_or("") {
        "daily" => diff % n == 0,
        "weekly" => {
            let weeks = (diff + start.weekday().num_days_from_sunday() as i64) / 7;
            weeks % n == 0
                && r["weekdays"].as_array().is_some_and(|ds| {
                    ds.iter()
                        .any(|d| d == current.weekday().number_from_monday())
                })
        }
        "monthly" => {
            let months = (current.year() - start.year()) as i64 * 12 + current.month() as i64
                - start.month() as i64;
            months % n == 0 && current.day() == start.day()
        }
        "dates" => {
            at == first
                || r["dates"]
                    .as_array()
                    .is_some_and(|ds| ds.iter().any(|d| d == &date))
        }
        _ => false,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn repeated_cooking_progress_uses_the_same_fold_and_gap_rules_as_the_calendar() {
        let record = Record {
            id: "meal".into(),
            kind: "event".into(),
            revision: 1,
            data: json!({"starts_at":1792906200i64,"recurrence":{"frequency":"daily","interval":1,"weekdays":[],"dates":[],"excluded":[],"until":"","zone":"America/New_York"}}),
        };
        // 01:30 local: the fall-back day has two instants, but only the earlier
        // occurrence is generated by the calendar. Do not attach progress to a phantom.
        use chrono::TimeZone;
        let zone: chrono_tz::Tz = "America/New_York".parse().unwrap();
        let first = zone
            .with_ymd_and_hms(2026, 10, 25, 1, 30, 0)
            .single()
            .unwrap()
            .timestamp();
        let mut r = record;
        r.data["starts_at"] = json!(first);
        let fold = zone.with_ymd_and_hms(2026, 11, 1, 1, 30, 0);
        assert!(occurrence_matches(&r, fold.earliest().unwrap().timestamp()));
        assert!(!occurrence_matches(&r, fold.latest().unwrap().timestamp()));
    }
}
