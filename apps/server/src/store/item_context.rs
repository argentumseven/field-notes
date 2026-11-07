use super::*;
use std::sync::OnceLock;

#[derive(Deserialize)]
pub(super) struct ItemContext {
    label: String,
    choices: Vec<String>,
}

fn profiles() -> &'static BTreeMap<String, ItemContext> {
    static DATA: OnceLock<BTreeMap<String, ItemContext>> = OnceLock::new();
    DATA.get_or_init(|| {
        serde_json::from_str(include_str!("../../../../shared/item-contexts.json"))
            .expect("compiled item contexts must be valid")
    })
}

fn broad(value: &Value) -> &str {
    match value.as_str().unwrap_or("general") {
        "honey_do" => "home",
        key => key,
    }
}

pub(super) fn context(record: &Record, parent: Option<&Record>) -> &'static ItemContext {
    let key = match parent {
        Some(p) if p.kind == "list" => match p.data["template"].as_str().unwrap_or("general") {
            "groceries" => "groceries",
            "shopping" => "shopping",
            "general" => broad(&p.data["category"]),
            _ => "general",
        },
        _ => broad(
            record
                .data
                .get("category")
                .unwrap_or_else(|| parent.map(|p| &p.data["category"]).unwrap_or(&Value::Null)),
        ),
    };
    profiles().get(key).unwrap_or(&profiles()["general"])
}

impl Store {
    /// Presets constrain newly chosen labels, not previously saved content.
    /// Moves, type changes, old clients, imports and restores keep their values.
    pub(super) fn validate_item_context(
        &self,
        data: &Value,
        before: Option<&Record>,
        record: &Record,
        importing: bool,
    ) -> Result<()> {
        if record.kind != "task" || data.get("item_category").is_none() {
            return Ok(());
        }
        let value = record.data["item_category"].as_str().unwrap_or("");
        if importing
            || value.is_empty()
            || before.is_some_and(|r| r.data["item_category"].as_str() == Some(value))
        {
            return Ok(());
        }
        let parent = record.data["project_id"]
            .as_str()
            .filter(|v| !v.is_empty())
            .map(|id| self.record(id))
            .transpose()?;
        let profile = context(record, parent.as_ref());
        if !profile.choices.is_empty() && !profile.choices.iter().any(|choice| choice == value) {
            return fail(400, &format!("Choose a {} or leave it uncategorized. General and custom lists allow your own labels.", profile.label.to_lowercase()));
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn compiled_profiles_are_bounded_and_general_is_unrestricted() {
        assert!(profiles()["general"].choices.is_empty());
        assert_eq!(profiles().len(), 8);
        for profile in profiles().values() {
            assert!(!profile.label.is_empty());
            let unique: std::collections::BTreeSet<_> = profile.choices.iter().collect();
            assert_eq!(unique.len(), profile.choices.len());
            assert!(profile.choices.iter().all(|s| s.chars().count() <= 80));
        }
    }
    #[test]
    fn inheritance_matches_the_shared_client_cases() {
        let cases: Value = serde_json::from_str(include_str!(
            "../../../../tests/fixtures/item-contexts.json"
        ))
        .unwrap();
        for case in cases.as_array().unwrap() {
            let record = Record {
                id: "item".into(),
                kind: "task".into(),
                revision: 1,
                data: case["data"].clone(),
            };
            let parent = case["parent"].as_object().map(|p| Record {
                id: "parent".into(),
                kind: p["kind"].as_str().unwrap().into(),
                revision: 1,
                data: p["data"].clone(),
            });
            assert!(
                std::ptr::eq(
                    context(&record, parent.as_ref()),
                    &profiles()[case["expected"].as_str().unwrap()]
                ),
                "{}",
                case["name"]
            );
        }
    }
}
