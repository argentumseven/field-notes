//! Household category definitions. User categories are metadata, never containers.
 //! No existing category IDs, item IDs, or collection parents are rewritten by
 //! renaming or archiving. No schema-version increase is required.
use super::*;
use std::collections::BTreeMap;

#[derive(Clone, Serialize, Deserialize)]
pub struct HouseholdCategory {
    pub id: String,
    pub name: String,
    pub archived: bool,
    pub revision: i64,
}
impl Store {
    pub(super) fn category_schema(&self) -> Result<()> {
        self.db.execute_batch("CREATE TABLE IF NOT EXISTS household_categories(id TEXT PRIMARY KEY,revision INTEGER NOT NULL,archived INTEGER NOT NULL DEFAULT 0,deleted INTEGER NOT NULL DEFAULT 0,name BLOB NOT NULL);")?;
        Ok(())
    }
    pub(super) fn custom_category_key(id: &str) -> String { format!("custom:{id}") }
    pub(super) fn category_by_id(&self, id: &str) -> Result<HouseholdCategory> {
        valid_id(id)?;
        let row: Option<(i64,i64,Vec<u8>)> = self.db.query_row(
            "SELECT revision,archived,name FROM household_categories WHERE id=? AND deleted=0",
            [id], |r| Ok((r.get(0)?,r.get(1)?,r.get(2)?))
        ).optional()?;
        let (revision, archived, ciphertext) = row.ok_or(Fault(404,"Category not found".into()))?;
        let name = String::from_utf8(self.vault.open(&format!("category:{id}"), &ciphertext)?)
            .map_err(|_| Fault(500,"Category name was not valid text".into()))?;
        Ok(HouseholdCategory{id:id.into(),name,archived:archived!=0,revision})
    }
    pub(super) fn custom_categories(&self) -> Result<Vec<HouseholdCategory>> {
        let ids = self.db.prepare("SELECT id FROM household_categories WHERE deleted=0 ORDER BY id")?
            .query_map([], |r| r.get::<_,String>(0))?
            .collect::<std::result::Result<Vec<_>,_>>()?;
        ids.iter().map(|id|self.category_by_id(id)).collect()
    }
    pub(super) fn category_allowed(&self, key: &str, unchanged: bool) -> Result<()> {
        if ["general","action","food","honey_do","home","errands","work","personal"].contains(&key) {
            return Ok(());
        }
        let Some(id) = key.strip_prefix("custom:") else {
            return fail(400,"Unknown category; choose a supported category");
        };
        let found = self.category_by_id(id)?;
        if found.archived && !unchanged {
            return fail(409,"Category is archived; keep its current assignment or choose an active category");
        }
        Ok(())
    }
    fn category_name(&self, input: &Value) -> Result<String> {
        let name = text(input,"name",80)?;
        if name.chars().any(char::is_control) || name.trim().is_empty() {
            return fail(400,"Category name must be a single line");
        }
        let previous = self.custom_categories()?;
        if previous.iter().any(|c| c.name.eq_ignore_ascii_case(&name) &&
            input["target"].as_str() != Some(c.id.as_str())) {
            return fail(409,"A category already has this name");
        }
        if ["General","Action","Food & cooking","Household","Errands","Work","Personal"]
            .iter().any(|v|v.eq_ignore_ascii_case(&name)) {
            return fail(409,"Use the existing built-in category instead");
        }
        Ok(name)
    }
    fn category_members(&self, key: &str) -> Result<Vec<Record>> {
        let ids = self.db.prepare("SELECT id FROM objects WHERE deleted=0 AND kind IN ('task','project','list','note','event') ORDER BY id")?
            .query_map([],|r|r.get::<_,String>(0))?
            .collect::<std::result::Result<Vec<_>,_>>()?;
        let mut items=Vec::new();
        for id in ids {
            let r=self.record(&id)?;
            if r.data["category"].as_str()==Some(key) {items.push(r);}
        }
        Ok(items)
    }
    pub(super) fn category_admin(&self, token:&str, actor:&Actor, b:&Value) -> Result<Value> {
        let action=text(b,"action",40)?;
        let target=b["target"].as_str().unwrap_or("");
        let outcome=match action.as_str() {
            "category.create" => {
                fields(b,&["action","target","name"])?;
                valid_id(target)?;
                if self.db.query_row("SELECT EXISTS(SELECT 1 FROM household_categories WHERE id=?)",[target],|r|r.get::<_,bool>(0))? {
                    return fail(409,"Category ID already exists; retry with a fresh ID");
                }
                if self.custom_categories()?.len() >= 64 {return fail(413,"Household category limit reached");}
                let name=self.category_name(b)?;
                self.db.execute(
                    "INSERT INTO household_categories(id,revision,archived,deleted,name) VALUES(?,1,0,0,?)",
                    params![target,self.vault.seal(&format!("category:{target}"),name.as_bytes())?]
                )?;
                json!({"category":self.category_by_id(target)?})
            }
            "category.rename" | "category.archive" | "category.reopen" |
            "category.delete" | "category.merge" => {
                valid_id(target)?;
                let before=self.category_by_id(target)?;
                if b["revision"].as_i64()!=Some(before.revision) {
                    return fail(409,"Category changed; review the current categories and retry");
                }
                let key=Self::custom_category_key(target);
                let members=self.category_members(&key)?;
                match action.as_str() {
                    "category.rename" => {
                        fields(b,&["action","target","revision","name"])?;
                        let name=self.category_name(b)?;
                        self.db.execute("UPDATE household_categories SET name=?,revision=revision+1 WHERE id=?",
                            params![self.vault.seal(&format!("category:{target}"),name.as_bytes())?,target])?;
                    }
                    "category.archive" | "category.reopen" => {
                        fields(b,&["action","target","revision"])?;
                        self.db.execute("UPDATE household_categories SET archived=?,revision=revision+1 WHERE id=?",
                            params![if action=="category.archive" {1}else{0},target])?;
                    }
                    "category.delete" => {
                        fields(b,&["action","target","revision","confirmation"])?;
                        if b["confirmation"]!="DELETE CATEGORY" {return fail(400,"Type DELETE CATEGORY");}
                        if !members.is_empty() {
                            return fail(409,"Category is still used. Reassign or merge its records first");
                        }
                        self.db.execute("UPDATE household_categories SET deleted=1,revision=revision+1 WHERE id=?",[target])?;
                    }
                    "category.merge" => {
                        fields(b,&["action","target","revision","into","contents","confirmation"])?;
                        if b["confirmation"]!="MERGE CATEGORY" {return fail(400,"Type MERGE CATEGORY");}
                        let into=b["into"].as_str().ok_or(Fault(400,"Choose a destination category".into()))?;
                        if into==key {return fail(400,"Merge into a different category");}
                        self.category_allowed(into,false)?;
                        let shown=b["contents"].as_array().ok_or(Fault(400,"Review the assigned records".into()))?;
                        let mut confirmed=BTreeMap::<String,i64>::new();
                        for row in shown {
                            let id=row["id"].as_str().ok_or(Fault(400,"Invalid record ID".into()))?;
                            let revision=row["revision"].as_i64().ok_or(Fault(400,"Invalid revision".into()))?;
                            valid_id(id)?;
                            if confirmed.insert(id.into(),revision).is_some() {return fail(400,"Repeated record ID");}
                        }
                        if members.len()!=confirmed.len()||members.iter().any(|r|confirmed.get(&r.id)!=Some(&r.revision)){
                            return fail(409,"Assigned records changed; review the merge again");
                        }
                        for row in &members {
                            self.command_inner(token,&Command{
                                operation_id:id(),epoch:self.meta()?.0,generation:self.meta()?.1,
                                action:format!("{}.update",row.kind),target:row.id.clone(),
                                expected_revision:Some(row.revision),
                                data:json!({"category":into,"category_override":true})
                            },false)?;
                        }
                        self.db.execute("UPDATE household_categories SET archived=1,revision=revision+1 WHERE id=?",[target])?;
                    }
                    _=>unreachable!(),
                }
                json!({"category":if action=="category.delete"{Value::Null}else{json!(self.category_by_id(target)?)},
                    "previous_assignments":members.len()})
            }
            _ => return fail(400,"Unknown category operation"),
        };
        self.audit(actor,&action,target,&json!({}))?;
        self.db.execute("INSERT INTO events(generation,object_id,action,actor,at) VALUES((SELECT generation FROM meta),?,?,?,?)",
            params![target,action,actor.user_id,now()])?;
        Ok(json!({"ok":true,"result":outcome,"sequence":self.meta()?.2}))
    }
}
