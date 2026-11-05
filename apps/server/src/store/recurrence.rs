use super::*;

impl Store {
    pub(super) fn recurrence(data: &Value, record: &mut Record) -> Result<()> {
        let Some(value) = data.get("recurrence") else {
            return Ok(());
        };
        if value.is_null() {
            record.data["recurrence"] = Value::Null;
            return Ok(());
        }
        fields(
            value,
            &[
                "frequency",
                "interval",
                "weekdays",
                "dates",
                "until",
                "excluded",
                "zone",
            ],
        )?;
        let frequency = value["frequency"].as_str().unwrap_or("");
        if !["daily", "weekly", "monthly", "dates"].contains(&frequency)
            || !value["interval"]
                .as_i64()
                .is_some_and(|v| (1..=365).contains(&v))
            || value["zone"]
                .as_str()
                .is_none_or(|z| z.parse::<chrono_tz::Tz>().is_err())
        {
            return fail(400, "Choose a repeat pattern, interval and time zone");
        }
        let days = value["weekdays"]
            .as_array()
            .ok_or(Fault(400, "Choose repeat weekdays".into()))?;
        let mut seen = std::collections::BTreeSet::new();
        if days.len() > 7
            || days.iter().any(|d| {
                !d.as_i64()
                    .is_some_and(|n| (1..=7).contains(&n) && seen.insert(n))
            })
            || (frequency == "weekly" && days.is_empty())
        {
            return fail(400, "Choose distinct weekdays for a weekly event");
        }
        for field in ["dates", "excluded"] {
            let dates = value[field]
                .as_array()
                .ok_or(Fault(400, "Choose repeat dates".into()))?;
            let mut seen = std::collections::BTreeSet::new();
            if dates.len() > 128 || (field == "dates" && frequency == "dates" && dates.is_empty()) {
                return fail(
                    400,
                    "Choose up to 128 individual dates or skipped occurrences",
                );
            }
            for date in dates {
                let date = date
                    .as_str()
                    .ok_or(Fault(400, "Repeat dates must be calendar dates".into()))?;
                if date.len() != 10 || !seen.insert(date) {
                    return fail(400, "Choose distinct calendar dates");
                }
                Self::due(date)?;
            }
        }
        let until = value["until"]
            .as_str()
            .ok_or(Fault(400, "Choose an optional last repeat day".into()))?;
        Self::due(until)?;
        let zone = value["zone"]
            .as_str()
            .unwrap()
            .parse::<chrono_tz::Tz>()
            .unwrap();
        let first = if record.data["all_day"] == true {
            record.data["start_date"].as_str().unwrap_or("").to_owned()
        } else {
            chrono::DateTime::from_timestamp(record.data["starts_at"].as_i64().unwrap_or(0), 0)
                .ok_or(Fault(400, "Invalid event start".into()))?
                .with_timezone(&zone)
                .format("%Y-%m-%d")
                .to_string()
        };
        if !until.is_empty() && until < first.as_str() {
            return fail(
                400,
                "The last repeat day must be on or after the event start",
            );
        }
        let last = if record.data["all_day"] == true {
            record.data["end_date"]
                .as_str()
                .unwrap_or(&first)
                .to_owned()
        } else {
            record.data["ends_at"]
                .as_i64()
                .and_then(|at| chrono::DateTime::from_timestamp(at, 0))
                .map(|at| at.with_timezone(&zone).format("%Y-%m-%d").to_string())
                .unwrap_or_else(|| first.clone())
        };
        let start_day = chrono::NaiveDate::parse_from_str(&first, "%Y-%m-%d")
            .map_err(|_| Fault(400, "Invalid repeat start day".into()))?;
        let end_day = chrono::NaiveDate::parse_from_str(&last, "%Y-%m-%d")
            .map_err(|_| Fault(400, "Invalid repeat end day".into()))?;
        if (end_day - start_day).num_days() > 31 {
            return fail(400, "A repeating event may span at most 31 days");
        }
        record.data["recurrence"] = value.clone();
        Ok(())
    }
}
