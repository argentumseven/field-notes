use super::*;

impl Store {
    pub fn initialize(&mut self, name: &str, password: &str) -> Result<()> {
        let n: i64 = self
            .db
            .query_row("SELECT COUNT(*) FROM users", [], |r| r.get(0))?;
        if n != 0 {
            return fail(409, "Installation already initialized");
        }
        self.create_user(name, password, "owner")?;
        Ok(())
    }
    pub fn create_user(&self, name: &str, password: &str, role: &str) -> Result<String> {
        if name.len() < 2
            || name.len() > 64
            || !name
                .chars()
                .all(|c| c.is_alphanumeric() || "._- ".contains(c))
        {
            return fail(400, "Invalid user name");
        }
        if !["owner", "admin", "member", "viewer"].contains(&role) {
            return fail(400, "Invalid role");
        }
        let hash = crypto::password_hash(password).map_err(|e| Fault(400, e))?;
        let uid = id();
        self.db
            .execute(
                "INSERT INTO users(id,name,hash,role) VALUES(?,?,?,?)",
                params![uid, name, hash, role],
            )
            .map_err(|_| Fault(409, "User already exists".into()))?;
        Ok(uid)
    }
    fn budget(&self, key: &str, limit: i64) -> Result<()> {
        self.db
            .execute("DELETE FROM login_limits WHERE until_time<?", [now()])?;
        let n: i64 = self.db.query_row(
            "SELECT COALESCE((SELECT attempts FROM login_limits WHERE key=?),0)",
            [key],
            |r| r.get(0),
        )?;
        if n >= limit {
            return fail(429, "Too many attempts; try again in five minutes");
        }
        self.db.execute("INSERT INTO login_limits VALUES(?,1,?) ON CONFLICT(key) DO UPDATE SET attempts=attempts+1",params![key,now()+300])?;
        Ok(())
    }
    pub fn login(
        &self,
        name: &str,
        password: &str,
        device: &str,
        source: &str,
    ) -> Result<(String, Actor)> {
        if name.len() > 64 || password.len() > 256 || device.len() > 80 {
            return fail(400, "Invalid credentials input");
        }
        self.budget(&format!("source:{source}"), 30)?;
        self.budget(&format!("name:{}", name.to_lowercase()), 8)?;
        let row: Option<(String, String, String, String)> = self
            .db
            .query_row(
                "SELECT id,name,hash,role FROM users WHERE name=? AND active=1",
                [name],
                |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?)),
            )
            .optional()?;
        let dummy="$argon2id$v=19$m=19456,t=2,p=1$c2FsdHNhbHRzYWx0c2FsdA$XHjPVr/gF6IYb8NSEnA6cocGWLoPIrM7D4lnlU7hWQw";
        let ok = crypto::verify(password, row.as_ref().map_or(dummy, |x| x.2.as_str()));
        if !ok || row.is_none() {
            return fail(401, "Invalid user name or password");
        }
        let (uid, name, _, role) = row.unwrap();
        let token = crypto::token();
        let sid = crypto::digest(token.as_bytes());
        self.db
            .execute("DELETE FROM sessions WHERE expires<?", [now()])?;
        let n: i64 = self.db.query_row(
            "SELECT COUNT(*) FROM sessions WHERE user_id=?",
            [&uid],
            |r| r.get(0),
        )?;
        if n >= 32 {
            return fail(409, "Too many sessions; revoke an old device");
        }
        self.db.execute(
            "INSERT INTO sessions VALUES(?,?,?,?,0,?)",
            params![sid, uid, device, now() + 86400 * 30, now()],
        )?;
        Ok((
            token,
            Actor {
                user_id: uid,
                name,
                role,
                session_id: sid,
                elevated: false,
            },
        ))
    }
    pub fn actor(&self, token: &str) -> Result<Actor> {
        if token.len() != 64 {
            return fail(401, "Sign in required");
        }
        let sid = crypto::digest(token.as_bytes());
        self.db.query_row("SELECT u.id,u.name,u.role,s.elevated FROM sessions s JOIN users u ON u.id=s.user_id WHERE s.id=? AND s.expires>? AND u.active=1",params![sid,now()],|r|Ok(Actor{user_id:r.get(0)?,name:r.get(1)?,role:r.get(2)?,session_id:sid.clone(),elevated:r.get::<_,i64>(3)?>now()})).optional()?.ok_or(Fault(401,"Session expired or revoked".into()))
    }
    pub fn reauthenticate(&self, actor: &Actor, password: &str) -> Result<()> {
        self.budget(&format!("reauth:{}", actor.user_id), 5)?;
        let hash: String =
            self.db
                .query_row("SELECT hash FROM users WHERE id=?", [&actor.user_id], |r| {
                    r.get(0)
                })?;
        if password.len() > 256 || !crypto::verify(password, &hash) {
            return fail(401, "Password not accepted");
        }
        self.db.execute(
            "UPDATE sessions SET elevated=? WHERE id=?",
            params![now() + 300, actor.session_id],
        )?;
        Ok(())
    }
}
