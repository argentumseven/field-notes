//! Portable category metadata is additive. Destination definitions always win.
use super::*;

#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub(super) struct ArchiveCategory {
    id: String,
    name: String,
    archived: bool,
    #[serde(default)]
    deleted: bool,
}

pub(super) fn validate(categories: &[ArchiveCategory]) -> Result<()> {
    let mut ids = BTreeSet::new();
    for category in categories {
        valid_id(&category.id)?;
        if !ids.insert(&category.id)
            || category.name.is_empty()
            || category.name.len() > 80
            || category.name.trim() != category.name
            || category.name.chars().any(char::is_control)
        {
            return fail(400, "Invalid or repeated archive category definition");
        }
    }
    Ok(())
}

impl Store {
    pub(super) fn archive_categories(&self) -> Result<Vec<ArchiveCategory>> {
        // Include tombstones: restored records and retained drafts can still
        // refer to these IDs even though they are absent from new selections.
        let rows = self
            .db
            .prepare("SELECT id,name,archived,deleted FROM household_categories ORDER BY id")?
            .query_map([], |r| {
                Ok((
                    r.get::<_, String>(0)?,
                    r.get::<_, Vec<u8>>(1)?,
                    r.get::<_, bool>(2)?,
                    r.get::<_, bool>(3)?,
                ))
            })?
            .collect::<std::result::Result<Vec<_>, _>>()?;
        rows.into_iter()
            .map(|(id, bytes, archived, deleted)| {
                let name = String::from_utf8(self.vault.open(&format!("category:{id}"), &bytes)?)
                    .map_err(|_| Fault(500, "Category name was not valid text".into()))?;
                Ok(ArchiveCategory {
                    id,
                    name,
                    archived,
                    deleted,
                })
            })
            .collect()
    }

    pub(super) fn missing_archive_categories(&self, manifest: &Manifest) -> Result<usize> {
        let mut missing = 0;
        for category in &manifest.categories {
            let exists: bool = self.db.query_row(
                "SELECT EXISTS(SELECT 1 FROM household_categories WHERE id=?)",
                [&category.id],
                |r| r.get(0),
            )?;
            missing += usize::from(!exists);
        }
        Ok(missing)
    }

    pub(super) fn import_archive_categories(
        &self,
        actor: &Actor,
        manifest: &Manifest,
        committing: bool,
    ) -> Result<()> {
        if self.missing_archive_categories(manifest)? == 0 {
            return Ok(());
        }
        if !actor.admin() {
            return fail(
                403,
                "An administrator must import the missing category definitions first",
            );
        }
        if committing && !actor.elevated {
            return fail(
                403,
                "Confirm your administrator password before importing category definitions",
            );
        }
        for category in &manifest.categories {
            let exists: bool = self.db.query_row(
                "SELECT EXISTS(SELECT 1 FROM household_categories WHERE id=?)",
                [&category.id],
                |r| r.get(0),
            )?;
            if exists {
                continue;
            }
            if !category.deleted {
                if self.custom_categories()?.len() >= 64 {
                    return fail(
                        413,
                        "Household category limit reached; no import was applied",
                    );
                }
                self.category_name(&json!({"target":category.id,"name":category.name}))?;
            }
            self.db.execute("INSERT INTO household_categories(id,revision,archived,deleted,name) VALUES(?,1,?,?,?)",
                params![category.id,category.archived,category.deleted,self.vault.seal(&format!("category:{}",category.id),category.name.as_bytes())?])?;
            self.audit(actor, "category.import", &category.id, &json!({}))?;
            self.db.execute("INSERT INTO events(generation,object_id,action,actor,at) VALUES((SELECT generation FROM meta),?,'category.import',?,?)",params![category.id,actor.user_id,now()])?;
        }
        Ok(())
    }
}
