use super::*;

pub(super) const LIST_FIELDS: &[&str] = &[
    "notes",
    "quantity",
    "store",
    "link_url",
    "price",
    "assignee",
    "due",
    "planned_for",
    "priority",
    "estimate_minutes",
    "images",
];
impl Store {
    pub(super) fn category(data: &Value, record: &mut Record) -> Result<()> {
        if data.get("category").is_some_and(|v| !v.is_string()) {
            return fail(400, "Choose a supported household category");
        }
        let category = data
            .get("category")
            .or_else(|| record.data.get("category"))
            .and_then(Value::as_str)
            .unwrap_or("general");
        if ![
            "general", "action", "food", "honey_do", "home", "errands", "work", "personal",
        ]
        .contains(&category)
        {
            return fail(400, "Choose a supported household category");
        }
        record.data["category"] = json!(category);
        Ok(())
    }
    pub(super) fn list_schema(data: &Value, record: &mut Record) -> Result<()> {
        if record.kind != "list" {
            return Ok(());
        }
        let template = data
            .get("template")
            .or_else(|| record.data.get("template"))
            .and_then(Value::as_str)
            .unwrap_or("general")
            .to_owned();
        if !["general", "groceries", "shopping", "custom"].contains(&template.as_str()) {
            return fail(400, "Choose a list type");
        }
        let defaults = match template.as_str() {
            "groceries" => json!(["quantity", "store", "notes"]),
            "shopping" => json!(["quantity", "link_url", "images"]),
            _ => json!(["notes"]),
        };
        let schema = data
            .get("fields")
            .or_else(|| record.data.get("fields"))
            .unwrap_or(&defaults)
            .clone();
        let choices = schema
            .as_array()
            .ok_or(Fault(400, "Choose list fields".into()))?;
        let mut seen = std::collections::BTreeSet::new();
        if choices.len() > LIST_FIELDS.len()
            || choices.iter().any(|v| {
                !v.as_str()
                    .is_some_and(|s| LIST_FIELDS.contains(&s) && seen.insert(s))
            })
        {
            return fail(400, "Unsupported or repeated list field");
        }
        let perpetual = data
            .get("perpetual")
            .or_else(|| record.data.get("perpetual"))
            .unwrap_or(&Value::Bool(true))
            .clone();
        if !perpetual.is_boolean() {
            return fail(400, "Choose whether this is a rolling list");
        }
        record.data["template"] = json!(template);
        record.data["fields"] = schema.clone();
        record.data["perpetual"] = perpetual.clone();
        for field in ["notify_additions", "item_reminders"] {
            let value = data
                .get(field)
                .or_else(|| record.data.get(field))
                .cloned()
                .unwrap_or(json!(field != "item_reminders" || template != "groceries"));
            if !value.is_boolean() {
                return fail(400, "Choose list notification settings");
            }
            record.data[field] = value;
        }
        Ok(())
    }
    pub(super) fn list_item_fields(data: &Value, record: &mut Record) -> Result<()> {
        for (field, max) in [
            ("item_category", 320),
            ("quantity", 80),
            ("store", 120),
            ("link_url", 2048),
            ("price", 16),
            ("currency", 3),
        ] {
            let Some(value) = data.get(field) else {
                continue;
            };
            let s = value
                .as_str()
                .ok_or(Fault(400, format!("{field} must be text")))?;
            if s.len() > max {
                return fail(400, "List field is too long");
            }
            if field == "item_category"
                && (s.chars().count() > 80 || s.chars().any(char::is_control))
            {
                return fail(
                    400,
                    "Item category must be a single line of at most 80 characters",
                );
            }
            if field == "price" && !s.is_empty() {
                let parts: Vec<_> = s.split('.').collect();
                if parts.len() > 2
                    || parts[0].is_empty()
                    || parts[0].len() > 9
                    || !parts[0].bytes().all(|c| c.is_ascii_digit())
                    || parts.get(1).is_some_and(|p| {
                        p.is_empty() || p.len() > 2 || !p.bytes().all(|c| c.is_ascii_digit())
                    })
                {
                    return fail(
                        400,
                        "Enter a nonnegative price with up to two decimal places",
                    );
                }
            }
            if field == "currency" && (s.len() != 3 || !s.bytes().all(|c| c.is_ascii_uppercase())) {
                return fail(400, "Choose a three-letter currency code");
            }
            if field == "link_url" && !s.is_empty() {
                let uri = s
                    .split('#')
                    .next()
                    .unwrap_or("")
                    .parse::<axum::http::Uri>()
                    .map_err(|_| Fault(400, "Enter a full HTTP or HTTPS link".into()))?;
                if !matches!(uri.scheme_str(), Some("http" | "https"))
                    || uri
                        .authority()
                        .is_none_or(|a| a.as_str().contains('@') || a.host().is_empty())
                    || s.chars().any(|c| c.is_whitespace() || c.is_control())
                {
                    return fail(
                        400,
                        "Use an HTTP or HTTPS link without embedded credentials",
                    );
                }
            }
            if field == "item_category" && s.is_empty() && record.data.get(field).is_none() {
                continue;
            }
            record.data[field] = value.clone();
        }
        Ok(())
    }
}
