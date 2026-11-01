use super::*;

impl Store {
    pub fn admin_snapshot(&self, actor: &Actor) -> Result<Value> {
        if !actor.admin() {
            return fail(403, "Administrator role required");
        }
        let users=self.db.prepare("SELECT id,name,role,active FROM users ORDER BY name")?.query_map([],|r|Ok(json!({"id":r.get::<_,String>(0)?,"name":r.get::<_,String>(1)?,"role":r.get::<_,String>(2)?,"active":r.get::<_,i64>(3)?==1})))?.collect::<std::result::Result<Vec<_>,_>>()?;
        let sessions=self.db.prepare("SELECT s.id,u.name,s.name,s.created,s.expires FROM sessions s JOIN users u ON s.user_id=u.id WHERE s.expires>? ORDER BY s.created DESC")?.query_map([now()],|r|Ok(json!({"id":r.get::<_,String>(0)?,"user":r.get::<_,String>(1)?,"device":r.get::<_,String>(2)?,"created":r.get::<_,i64>(3)?,"expires":r.get::<_,i64>(4)?})))?.collect::<std::result::Result<Vec<_>,_>>()?;
        let audit=self.db.prepare("SELECT actor,action,target,at FROM audit ORDER BY seq DESC LIMIT 100")?.query_map([],|r|Ok(json!({"actor":r.get::<_,String>(0)?,"action":r.get::<_,String>(1)?,"target":r.get::<_,String>(2)?,"at":r.get::<_,i64>(3)?})))?.collect::<std::result::Result<Vec<_>,_>>()?;
        let (epoch, generation, sequence) = self.meta()?;
        Ok(
            json!({"users":users,"sessions":sessions,"audit":audit,"epoch":epoch,"generation":generation,"sequence":sequence,"version":env!("CARGO_PKG_VERSION")}),
        )
    }
    pub fn admin(&mut self, token: &str, body: Value) -> Result<Value> {
        let tx = rusqlite::Transaction::new_unchecked(
            &self.db,
            rusqlite::TransactionBehavior::Immediate,
        )?;
        let result = self.admin_inner(token, body)?;
        tx.commit()?;
        Ok(result)
    }
    fn admin_inner(&self, token: &str, b: Value) -> Result<Value> {
        let a = self.actor(token)?;
        if !a.admin() || !a.elevated {
            return fail(403, "Administrator and recent reauthentication required");
        }
        let action = text(&b, "action", 40)?;
        let target = b["target"].as_str().unwrap_or("");
        match action.as_str() {
            "user.create" => {
                fields(&b, &["action", "name", "password", "role"])?;
                let role = text(&b, "role", 20)?;
                if (role == "owner" || role == "admin") && a.role != "owner" {
                    return fail(403, "Owner required");
                }
                let uid = self.create_user(
                    &text(&b, "name", 64)?,
                    b["password"]
                        .as_str()
                        .ok_or(Fault(400, "Password required".into()))?,
                    &role,
                )?;
                self.audit(&a, &action, &uid, &json!({"role":role}))?;
            }
            "user.role" | "user.disable" => {
                if a.role != "owner" {
                    return fail(403, "Owner required");
                }
                valid_id(target)?;
                fields(&b, &["action", "target", "role"])?;
                let old: String = self
                    .db
                    .query_row(
                        "SELECT role FROM users WHERE id=? AND active=1",
                        [target],
                        |r| r.get(0),
                    )
                    .optional()?
                    .ok_or(Fault(404, "User not found".into()))?;
                if old == "owner" {
                    let n: i64 = self.db.query_row(
                        "SELECT COUNT(*) FROM users WHERE role='owner' AND active=1",
                        [],
                        |r| r.get(0),
                    )?;
                    if n <= 1 {
                        return fail(409, "Cannot remove the last owner");
                    }
                }
                if action == "user.disable" {
                    self.db
                        .execute("UPDATE users SET active=0 WHERE id=?", [target])?;
                } else {
                    let role = text(&b, "role", 20)?;
                    if !["owner", "admin", "member", "viewer"].contains(&role.as_str()) {
                        return fail(400, "Invalid role");
                    }
                    self.db
                        .execute("UPDATE users SET role=? WHERE id=?", params![role, target])?;
                }
                self.db
                    .execute("DELETE FROM sessions WHERE user_id=?", [target])?;
                self.audit(&a, &action, target, &json!({}))?;
            }
            "user.password" => {
                fields(&b, &["action", "target", "password", "sign_out_devices"])?;
                valid_id(target)?;
                let role: String = self
                    .db
                    .query_row(
                        "SELECT role FROM users WHERE id=? AND active=1",
                        [target],
                        |r| r.get(0),
                    )
                    .optional()?
                    .ok_or(Fault(404, "Active user not found".into()))?;
                if a.role != "owner" && ["owner", "admin"].contains(&role.as_str()) {
                    return fail(
                        403,
                        "Only an owner can reset administrator or owner passwords",
                    );
                }
                if target == a.user_id {
                    return fail(
                        403,
                        "Change your own password with current-password verification",
                    );
                }
                let password = b["password"]
                    .as_str()
                    .ok_or(Fault(400, "Password required".into()))?;
                let sign_out_devices = match b.get("sign_out_devices") {
                    None => false,
                    Some(Value::Bool(value)) => *value,
                    _ => return fail(400, "sign_out_devices must be a boolean"),
                };
                self.replace_password(
                    &a,
                    target,
                    password,
                    "user.password.reset",
                    sign_out_devices,
                )?;
            }
            "session.revoke" => {
                fields(&b, &["action", "target"])?;
                let role: Option<String> = self
                    .db
                    .query_row(
                        "SELECT u.role FROM sessions s JOIN users u ON s.user_id=u.id WHERE s.id=?",
                        [target],
                        |r| r.get(0),
                    )
                    .optional()?;
                if role == Some("owner".into()) && a.role != "owner" {
                    return fail(403, "Owner required");
                }
                self.db
                    .execute("DELETE FROM sessions WHERE id=?", [target])?;
                self.audit(&a, &action, target, &json!({}))?;
            }
            "workspace.clear" => {
                fields(&b, &["action", "confirmation", "generation"])?;
                if a.role != "owner" {
                    return fail(403, "Owner required");
                }
                if b["confirmation"] != "CLEAR WORKSPACE"
                    || b["generation"].as_i64() != Some(self.meta()?.1)
                {
                    return fail(409, "Confirmation or workspace generation does not match");
                }
                self.db.execute_batch("DELETE FROM blobs; DELETE FROM objects; DELETE FROM operations; UPDATE meta SET generation=generation+1 WHERE id=1;")?;
                self.audit(&a, &action, "workspace", &json!({}))?;
                self.db.execute("INSERT INTO events(generation,object_id,action,actor,at) VALUES((SELECT generation FROM meta),'workspace','workspace.clear',?,?)",params![a.user_id,now()])?;
            }
            _ => return fail(400, "Unsupported administration action"),
        }
        Ok(json!({"ok":true}))
    }
}
