use super::*;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use std::{
    io::Write,
    net::{IpAddr, Ipv4Addr},
};

#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct NetworkConfig {
    pub bootstrap_url: String,
    pub endpoint: String,
    #[serde(default)]
    pub lan_endpoint: Option<String>,
    pub server_key: String,
    pub api: String,
    pub ca_pem: String,
    pub overlay: String,
}

impl NetworkConfig {
    pub fn validate(&self) -> Result<()> {
        let network: ipnet::Ipv4Net = self
            .overlay
            .parse()
            .map_err(|_| Fault(400, "Invalid peer network".into()))?;
        if network.prefix_len() != 24 || !network.network().is_private() {
            return fail(400, "Use a private /24 peer network");
        }
        let server = Ipv4Addr::from(u32::from(network.network()) + 1);
        if self.api != format!("https://{server}:8443") {
            return fail(
                400,
                "Device API must be the notes endpoint on the overlay server, port 8443",
            );
        }
        canonical_key(&self.server_key)?;
        let uri: axum::http::Uri = self
            .bootstrap_url
            .parse()
            .map_err(|_| Fault(400, "Invalid bootstrap URL".into()))?;
        let host: Ipv4Addr = uri.host().unwrap_or("").parse().map_err(|_| {
            Fault(
                400,
                "Enrollment uses an explicit private LAN IPv4 address".into(),
            )
        })?;
        if uri.scheme_str() != Some("https")
            || !host.is_private()
            || uri.path() != "/api/enrollment"
            || uri.query().is_some()
        {
            return fail(
                400,
                "Enrollment must use private LAN HTTPS at /api/enrollment",
            );
        }
        if self.endpoint.len() > 260
            || self
                .endpoint
                .chars()
                .any(|c| !c.is_ascii_alphanumeric() && !".-:[]".contains(c))
            || self
                .endpoint
                .rsplit_once(':')
                .and_then(|(_, p)| p.parse::<u16>().ok())
                .is_none_or(|p| p == 0)
        {
            return fail(400, "Invalid reachable WireGuard endpoint");
        }
        if let Some(endpoint) = &self.lan_endpoint {
            let (host, port) = endpoint
                .rsplit_once(':')
                .ok_or(Fault(400, "Invalid LAN UDP endpoint".into()))?;
            let address: Ipv4Addr = host
                .parse()
                .map_err(|_| Fault(400, "LAN endpoint must be a private IPv4 address".into()))?;
            if !address.is_private() || port.parse::<u16>().ok().is_none_or(|p| p == 0) {
                return fail(400, "Invalid LAN UDP endpoint");
            }
        }
        if self.ca_pem.len() > 12000
            || self.ca_pem.contains("PRIVATE KEY")
            || rustls_pemfile::certs(&mut std::io::Cursor::new(self.ca_pem.as_bytes()))
                .next()
                .transpose()
                .map_err(|_| Fault(400, "Invalid enrollment CA".into()))?
                .is_none()
        {
            return fail(400, "A public enrollment CA is required");
        }
        Ok(())
    }
}
fn canonical_key(value: &str) -> Result<()> {
    let bytes = STANDARD
        .decode(value)
        .map_err(|_| Fault(400, "Invalid peer key".into()))?;
    if bytes.len() != 32 || bytes == [0; 32] || STANDARD.encode(&bytes) != value {
        return fail(400, "Invalid canonical peer key");
    }
    Ok(())
}
fn secret(value: &str) -> Result<()> {
    if value.len() != 64 || !value.bytes().all(|c| c.is_ascii_hexdigit()) {
        return fail(400, "Invalid enrollment credential");
    }
    Ok(())
}
struct Device {
    id: String,
    user: String,
    public: String,
    address: Option<String>,
    name: String,
    state: String,
    claim: String,
    activation: String,
    expires: i64,
    verification: String,
    data: Value,
}
impl Store {
    fn device(&self, device_id: &str) -> Result<Device> {
        valid_id(device_id)?;
        let row=self.db.query_row("SELECT id,user_id,public_key,address,name,state,claim_hash,activation_hash,expires,verification,data FROM devices WHERE id=?",[device_id],|r|Ok((r.get::<_,String>(0)?,r.get::<_,String>(1)?,r.get::<_,String>(2)?,r.get::<_,Option<String>>(3)?,r.get::<_,String>(4)?,r.get::<_,String>(5)?,r.get::<_,String>(6)?,r.get::<_,String>(7)?,r.get::<_,i64>(8)?,r.get::<_,String>(9)?,r.get::<_,Vec<u8>>(10)?))).optional()?.ok_or(Fault(404,"Device not found".into()))?;
        let data = serde_json::from_slice(&self.vault.open(&format!("device:{}", row.0), &row.10)?)
            .map_err(|_| Fault(500, "Invalid encrypted device record".into()))?;
        Ok(Device {
            id: row.0,
            user: row.1,
            public: row.2,
            address: row.3,
            name: row.4,
            state: row.5,
            claim: row.6,
            activation: row.7,
            expires: row.8,
            verification: row.9,
            data,
        })
    }
    fn save_device_data(&self, id: &str, data: &Value) -> Result<()> {
        let bytes = self.vault.seal(
            &format!("device:{id}"),
            &serde_json::to_vec(data)
                .map_err(|_| Fault(500, "Device serialization failed".into()))?,
        )?;
        self.db
            .execute("UPDATE devices SET data=? WHERE id=?", params![bytes, id])?;
        Ok(())
    }
    fn enrollment_admin(&self, token: &str, user_id: &str) -> Result<Actor> {
        let actor = self.actor(token)?;
        if !actor.admin() || !actor.elevated {
            return fail(403, "Administrator and recent reauthentication required");
        }
        let role: Option<String> = self
            .db
            .query_row(
                "SELECT role FROM users WHERE id=? AND active=1",
                [user_id],
                |r| r.get(0),
            )
            .optional()?;
        let role = role.ok_or(Fault(404, "Active household user not found".into()))?;
        if actor.role != "owner" && ["admin", "owner"].contains(&role.as_str()) {
            return fail(
                403,
                "Only an owner can enroll administrator or owner devices",
            );
        }
        Ok(actor)
    }
    pub fn enrollment_invite(&mut self, token: &str, user_id: &str) -> Result<Value> {
        let actor = self.enrollment_admin(token, user_id)?;
        let network = self.network.as_ref().ok_or(Fault(
            503,
            "Host enrollment networking is not configured".into(),
        ))?;
        network.validate()?;
        self.db.execute(
            "DELETE FROM enrollment_invites WHERE expires<? AND device_id IS NULL",
            [now() - 86400],
        )?;
        let count: i64 = self.db.query_row(
            "SELECT COUNT(*) FROM enrollment_invites WHERE expires>?",
            [now()],
            |r| r.get(0),
        )?;
        if count >= 64 {
            return fail(
                429,
                "Too many invitations; wait for old invitations to expire",
            );
        }
        let (name, role): (String, String) =
            self.db
                .query_row("SELECT name,role FROM users WHERE id=?", [user_id], |r| {
                    Ok((r.get(0)?, r.get(1)?))
                })?;
        let raw = crypto::token();
        let identifier = id();
        let expires = now() + 600;
        let data = self.vault.seal(
            &format!("invite:{identifier}"),
            &serde_json::to_vec(network)
                .map_err(|_| Fault(500, "Network serialization failed".into()))?,
        )?;
        self.db.execute(
            "INSERT INTO enrollment_invites VALUES(?,?,?,?,?,?,NULL,?)",
            params![
                identifier,
                crypto::digest(raw.as_bytes()),
                actor.user_id,
                user_id,
                role,
                expires,
                data
            ],
        )?;
        self.audit(
            &actor,
            "device.invite",
            user_id,
            &json!({"expires":expires}),
        )?;
        let invitation = json!({"version":1,"url":network.bootstrap_url,"ca":network.ca_pem,"token":raw,"user":name,"role":role,"expires":expires});
        let code = format!(
            "fieldnotes://enroll#{}",
            URL_SAFE_NO_PAD.encode(
                serde_json::to_vec(&invitation)
                    .map_err(|_| Fault(500, "Invitation serialization failed".into()))?
            )
        );
        let qr = qrcode::QrCode::with_error_correction_level(code.as_bytes(), qrcode::EcLevel::L)
            .map_err(|_| Fault(500, "Invitation is too large for QR encoding".into()))?
            .render::<qrcode::render::svg::Color>()
            .min_dimensions(640, 640)
            .build();
        Ok(
            json!({"id":identifier,"code":code,"qr_svg":qr,"expires":expires,"user":name,"role":role}),
        )
    }
    pub fn enrollment_claim(&mut self, input: Value, source: &str) -> Result<Value> {
        fields(&input, &["token", "public_key", "claim_secret", "name"])?;
        let token = text(&input, "token", 64)?;
        secret(&token)?;
        let claim = text(&input, "claim_secret", 64)?;
        secret(&claim)?;
        let public = text(&input, "public_key", 44)?;
        canonical_key(&public)?;
        let name = text(&input, "name", 80)?;
        self.budget(&format!("enroll:{source}"), 30)?;
        let tx = rusqlite::Transaction::new_unchecked(
            &self.db,
            rusqlite::TransactionBehavior::Immediate,
        )?;
        let row=self.db.query_row("SELECT id,issuer,user_id,role,expires,device_id,data FROM enrollment_invites WHERE token_hash=?",[crypto::digest(token.as_bytes())],|r|Ok((r.get::<_,String>(0)?,r.get::<_,String>(1)?,r.get::<_,String>(2)?,r.get::<_,String>(3)?,r.get::<_,i64>(4)?,r.get::<_,Option<String>>(5)?,r.get::<_,Vec<u8>>(6)?))).optional()?.ok_or(Fault(410,"Invitation is invalid or expired".into()))?;
        if row.4 <= now() && row.5.is_none() {
            return fail(410, "Invitation is invalid or expired");
        }
        let issuer: Option<String> = self
            .db
            .query_row(
                "SELECT role FROM users WHERE id=? AND active=1",
                [&row.1],
                |r| r.get(0),
            )
            .optional()?;
        if issuer.as_deref() != Some("owner")
            && (issuer.as_deref() != Some("admin") || ["admin", "owner"].contains(&row.3.as_str()))
        {
            return fail(
                410,
                "Invitation authority changed; request a new invitation",
            );
        }
        let role: Option<String> = self
            .db
            .query_row(
                "SELECT role FROM users WHERE id=? AND active=1",
                [&row.2],
                |r| r.get(0),
            )
            .optional()?;
        if role.as_deref() != Some(row.3.as_str()) {
            return fail(410, "User permissions changed; request a new invitation");
        }
        let claim_hash = crypto::digest(claim.as_bytes());
        if let Some(existing) = row.5 {
            let device = self.device(&existing)?;
            if device.state != "active" && device.expires <= now() {
                return fail(410, "Enrollment expired; request a fresh invitation");
            }
            if device.public != public || device.claim != claim_hash || device.name != name {
                return fail(409,"This invitation was claimed by another request; ask your administrator to reissue it");
            }
            let result =
                json!({"id":device.id,"state":device.state,"verification":device.verification});
            tx.commit()?;
            return Ok(result);
        }
        let exists: bool = self.db.query_row(
            "SELECT EXISTS(SELECT 1 FROM devices WHERE public_key=?)",
            [&public],
            |r| r.get(0),
        )?;
        if exists {
            return fail(409,"This phone key already has a binding. Revoke its old server entry, wait for peer removal, then remove the inactive pairing before using a fresh invitation");
        }
        let count: i64 = self.db.query_row(
            "SELECT COUNT(*) FROM devices WHERE user_id=? AND state NOT IN ('revoked','expired')",
            [&row.2],
            |r| r.get(0),
        )?;
        if count >= 16 {
            return fail(429, "Revoke an old device before enrolling another");
        }
        let network: NetworkConfig =
            serde_json::from_slice(&self.vault.open(&format!("invite:{}", row.0), &row.6)?)
                .map_err(|_| Fault(500, "Invitation network data is invalid".into()))?;
        let subnet: ipnet::Ipv4Net = network
            .overlay
            .parse()
            .map_err(|_| Fault(500, "Invalid peer pool".into()))?;
        let address = (2..255)
            .map(|n| Ipv4Addr::from(u32::from(subnet.network()) + n).to_string())
            .find(|address| {
                self.db
                    .query_row(
                        "SELECT NOT EXISTS(SELECT 1 FROM devices WHERE address=?)",
                        [address],
                        |r| r.get::<_, bool>(0),
                    )
                    .unwrap_or(false)
            })
            .ok_or(Fault(
                409,
                "Device address pool is full; finish revoking old devices".into(),
            ))?;
        let identifier = id();
        let activation = crypto::token();
        let verification = crypto::token()[..8].to_ascii_uppercase();
        let psk = STANDARD.encode(
            hex::decode(crypto::token()).map_err(|_| Fault(500, "Key generation failed".into()))?,
        );
        let data = json!({"config":{"address":format!("{address}/32"),"endpoint":network.endpoint,"alternate_endpoints":network.lan_endpoint.iter().collect::<Vec<_>>(),"server_key":network.server_key,"api":network.api,"ca_pem":network.ca_pem,"preshared_key":psk},"activation_secret":activation,"activation_until":now()+600,"role":row.3});
        let encrypted = self.vault.seal(
            &format!("device:{identifier}"),
            &serde_json::to_vec(&data)
                .map_err(|_| Fault(500, "Device serialization failed".into()))?,
        )?;
        self.db.execute(
            "INSERT INTO devices VALUES(?,?,?,?,?,'pending_approval',?,?,?,?,?)",
            params![
                identifier,
                row.2,
                public,
                address,
                name,
                claim_hash,
                crypto::digest(activation.as_bytes()),
                now() + 600,
                verification,
                encrypted
            ],
        )?;
        self.db.execute(
            "UPDATE enrollment_invites SET device_id=? WHERE id=?",
            params![identifier, row.0],
        )?;
        tx.commit()?;
        Ok(json!({"id":identifier,"state":"pending_approval","verification":verification}))
    }
    pub fn enrollment_approve(
        &mut self,
        token: &str,
        id: &str,
        verification: &str,
    ) -> Result<Value> {
        let device = self.device(id)?;
        self.enrollment_admin(token, &device.user)?;
        self.budget(&format!("approve:{id}"), 8)?;
        let tx = rusqlite::Transaction::new_unchecked(
            &self.db,
            rusqlite::TransactionBehavior::Immediate,
        )?;
        let device = self.device(id)?;
        let actor = self.enrollment_admin(token, &device.user)?;
        if verification.replace(' ', "").to_ascii_uppercase() != device.verification {
            return fail(403, "Enter the code displayed on the phone");
        }
        if ["provisioning", "ready", "active"].contains(&device.state.as_str()) {
            return Ok(json!({"state":device.state}));
        }
        if device.state != "pending_approval" || device.expires <= now() {
            return fail(409, "This enrollment is no longer awaiting approval");
        }
        let role: String = self.db.query_row(
            "SELECT role FROM users WHERE id=? AND active=1",
            [&device.user],
            |r| r.get(0),
        )?;
        if device.data["role"] != role {
            return fail(410, "Permissions changed; issue a fresh invitation");
        }
        self.db
            .execute("UPDATE devices SET state='provisioning' WHERE id=?", [id])?;
        self.audit(&actor, "device.approve", id, &json!({}))?;
        tx.commit()?;
        self.reconcile_peers()?;
        Ok(json!({"state":"provisioning"}))
    }
    pub fn enrollment_revoke(&mut self, token: &str, id: &str) -> Result<Value> {
        let tx = rusqlite::Transaction::new_unchecked(
            &self.db,
            rusqlite::TransactionBehavior::Immediate,
        )?;
        let device = self.device(id)?;
        let actor = self.enrollment_admin(token, &device.user)?;
        if ["revoking", "revoked", "expired"].contains(&device.state.as_str()) {
            tx.commit()?;
            self.reconcile_peers()?;
            return Ok(json!({"state":self.device(id)?.state,"authorization_revoked":true}));
        }
        self.db.execute("DELETE FROM sessions WHERE id IN (SELECT session_id FROM device_sessions WHERE device_id=?)",[id])?;
        self.db
            .execute("UPDATE devices SET state='revoking' WHERE id=?", [id])?;
        self.audit(&actor, "device.revoke", id, &json!({}))?;
        tx.commit()?;
        self.reconcile_peers()?;
        Ok(json!({"state":self.device(id)?.state,"authorization_revoked":true}))
    }
    /// Remove only an explicitly retired binding after peer removal is confirmed.
    pub fn enrollment_purge(
        &mut self,
        token: &str,
        identifier: &str,
        input: Value,
    ) -> Result<Value> {
        valid_id(identifier)?;
        fields(&input, &["confirmation"])?;
        if input["confirmation"] != "REMOVE PAIRING" {
            return fail(400, "Type REMOVE PAIRING to remove an inactive binding");
        }
        let actor = self.actor(token)?;
        if !actor.admin() || !actor.elevated {
            return fail(403, "Administrator and recent reauthentication required");
        }
        self.reconcile_peers()?;
        let tx = rusqlite::Transaction::new_unchecked(
            &self.db,
            rusqlite::TransactionBehavior::Immediate,
        )?;
        let actor = self.actor(token)?;
        if !actor.admin() || !actor.elevated {
            return fail(403, "Administrator and recent reauthentication required");
        }
        let row:Option<(String,Option<String>,String)>=self.db.query_row(
            "SELECT d.state,d.address,u.role FROM devices d JOIN users u ON u.id=d.user_id WHERE d.id=?", [identifier], |r|Ok((r.get(0)?,r.get(1)?,r.get(2)?))).optional()?;
        if let Some((state, address, role)) = row {
            if actor.role != "owner" && ["owner", "admin"].contains(&role.as_str()) {
                return fail(
                    403,
                    "Only an owner can remove administrator or owner bindings",
                );
            }
            if !["revoked", "expired"].contains(&state.as_str()) || address.is_some() {
                return fail(409,"Pairing is still in use or peer removal is unconfirmed. Revoke it and wait for host acknowledgement first");
            }
            self.db.execute("DELETE FROM sessions WHERE id IN (SELECT session_id FROM device_sessions WHERE device_id=?)",[identifier])?;
            self.db.execute(
                "DELETE FROM enrollment_invites WHERE device_id=?",
                [identifier],
            )?;
            self.db
                .execute("DELETE FROM devices WHERE id=?", [identifier])?;
            self.audit(&actor, "device.purge", identifier, &json!({}))?;
        }
        tx.commit()?;
        Ok(json!({"purged":true}))
    }

    /// A phone may revoke only the enrolled identity bound to its own session.
    /// The private router independently binds that session to the TCP peer IP.
    pub fn revoke_self(&mut self, token: &str) -> Result<Value> {
        let tx = rusqlite::Transaction::new_unchecked(
            &self.db,
            rusqlite::TransactionBehavior::Immediate,
        )?;
        let actor = self.actor(token)?;
        let id: String = self
            .db
            .query_row(
                "SELECT device_id FROM device_sessions WHERE session_id=?",
                [&actor.session_id],
                |r| r.get(0),
            )
            .optional()?
            .ok_or(Fault(
                403,
                "This session is not an enrolled installation".into(),
            ))?;
        self.db.execute("DELETE FROM sessions WHERE id IN (SELECT session_id FROM device_sessions WHERE device_id=?)",[&id])?;
        self.db
            .execute("UPDATE devices SET state='revoking' WHERE id=?", [&id])?;
        self.audit(&actor, "device.revoke-self", &id, &json!({}))?;
        tx.commit()?;
        let reconciliation_failed = self.reconcile_peers().is_err();
        let peer_pending = reconciliation_failed || self.device(&id)?.state != "revoked";
        Ok(json!({"authorization_revoked":true,"peer_removal_pending":peer_pending,"device_id":id}))
    }
    pub fn enrollment_status(&mut self, input: Value) -> Result<Value> {
        fields(&input, &["id", "claim_secret"])?;
        let id = text(&input, "id", 36)?;
        let claim = text(&input, "claim_secret", 64)?;
        secret(&claim)?;
        self.reconcile_peers()?;
        let device = self.device(&id)?;
        if device.claim != crypto::digest(claim.as_bytes()) {
            return fail(403, "Enrollment credential not accepted");
        }
        if ["revoking", "revoked", "expired"].contains(&device.state.as_str())
            || (device.state != "active" && device.expires <= now())
        {
            return fail(410, "Enrollment expired or was revoked");
        }
        let (name, role): (String, String) = self.db.query_row(
            "SELECT name,role FROM users WHERE id=?",
            [&device.user],
            |r| Ok((r.get(0)?, r.get(1)?)),
        )?;
        let mut result = json!({"id":device.id,"state":device.state,"verification":device.verification,"user":name,"role":role});
        if device.state == "ready" {
            result["configuration"] = device.data["config"].clone();
            result["activation_secret"] = device.data["activation_secret"].clone();
        }
        Ok(result)
    }
    pub fn enrollment_cancel(&mut self, input: Value) -> Result<Value> {
        let tx = rusqlite::Transaction::new_unchecked(
            &self.db,
            rusqlite::TransactionBehavior::Immediate,
        )?;
        let claim = text(&input, "claim_secret", 64)?;
        secret(&claim)?;
        let id = if input.get("id").is_some() {
            fields(&input, &["id", "claim_secret"])?;
            text(&input, "id", 36)?
        } else {
            // A lost claim reply leaves no device ID on the phone. Cancel the
            // saved attempt without issuing a new claim or allocating a peer.
            fields(&input, &["token", "claim_secret", "public_key"])?;
            let token = text(&input, "token", 64)?;
            secret(&token)?;
            let public = text(&input, "public_key", 44)?;
            canonical_key(&public)?;
            let row: Option<(String, Option<String>)> = self
                .db
                .query_row(
                    "SELECT id,device_id FROM enrollment_invites WHERE token_hash=?",
                    [crypto::digest(token.as_bytes())],
                    |r| Ok((r.get(0)?, r.get(1)?)),
                )
                .optional()?;
            let (invitation, device) =
                row.ok_or(Fault(410, "Invitation is invalid or expired".into()))?;
            match device {
                None => {
                    self.db
                        .execute("DELETE FROM enrollment_invites WHERE id=?", [invitation])?;
                    tx.commit()?;
                    return Ok(json!({"canceled":true}));
                }
                Some(id) => {
                    if self.device(&id)?.public != public {
                        return fail(403, "Enrollment credential not accepted");
                    }
                    id
                }
            }
        };
        let device = self.device(&id)?;
        if device.claim != crypto::digest(claim.as_bytes()) {
            return fail(403, "Enrollment credential not accepted");
        }
        if device.state == "active" {
            return fail(
                409,
                "Activated phone: sign in or ask the administrator to revoke it",
            );
        }
        self.db.execute("UPDATE devices SET state='revoking' WHERE id=? AND state NOT IN ('revoked','revoking')", [&id])?;
        tx.commit()?;
        self.reconcile_peers()?;
        Ok(json!({"canceled":true}))
    }
    pub fn activate_device(&mut self, input: Value, peer: IpAddr) -> Result<Value> {
        fields(&input, &["id", "activation_secret"])?;
        let id = text(&input, "id", 36)?;
        let activation = text(&input, "activation_secret", 64)?;
        secret(&activation)?;
        self.reconcile_peers()?;
        let tx = rusqlite::Transaction::new_unchecked(
            &self.db,
            rusqlite::TransactionBehavior::Immediate,
        )?;

        let mut device = self.device(&id)?;
        if device.address.as_deref() != Some(peer.to_string().as_str())
            || device.activation != crypto::digest(activation.as_bytes())
        {
            return fail(
                403,
                "Activation must come through this phone's approved tunnel",
            );
        }
        if device.data["activation_until"].as_i64().unwrap_or(0) <= now() {
            return fail(
                410,
                "Activation receipt expired; sign in through your enrolled tunnel",
            );
        }
        if device.state == "active" {
            let result = device
                .data
                .get("activation_result")
                .ok_or(Fault(410, "Sign in through your enrolled tunnel".into()))?;
            self.actor(result["token"].as_str().unwrap_or(""))?;
            return Ok(result.clone());
        }
        if device.state != "ready" {
            return fail(409, "Wait for host peer provisioning to complete");
        }
        let (name, role): (String, String) = self.db.query_row(
            "SELECT name,role FROM users WHERE id=? AND active=1",
            [&device.user],
            |r| Ok((r.get(0)?, r.get(1)?)),
        )?;
        if device.data["role"] != role {
            return fail(410, "Permissions changed; issue a fresh invitation");
        }
        let token = crypto::token();
        let sid = crypto::digest(token.as_bytes());
        self.db.execute(
            "INSERT INTO sessions VALUES(?,?,?,?,0,?)",
            params![sid, device.user, device.name, now() + 30 * 86400, now()],
        )?;
        self.db
            .execute("INSERT INTO device_sessions VALUES(?,?)", params![sid, id])?;
        self.db.execute(
            "UPDATE devices SET state='active',expires=0 WHERE id=?",
            [&id],
        )?;
        let actor = Actor {
            user_id: device.user.clone(),
            name,
            role,
            session_id: sid,
            elevated: false,
        };
        let result = json!({"token":token,"actor":actor,"device_id":id});
        device.data["activation_result"] = result.clone();
        self.save_device_data(&id, &device.data)?;
        self.audit(&actor, "device.activate", &id, &json!({}))?;
        tx.commit()?;
        Ok(result)
    }
    pub fn authorize_device(&self, token: &str, peer: IpAddr) -> Result<()> {
        let actor = self.actor(token)?;
        let allowed:bool=self.db.query_row("SELECT EXISTS(SELECT 1 FROM device_sessions s JOIN devices d ON d.id=s.device_id WHERE s.session_id=? AND d.state='active' AND d.address=?)",params![actor.session_id,peer.to_string()],|r|r.get(0))?;
        if !allowed {
            return fail(
                403,
                "This session is not bound to the connected enrolled device",
            );
        }
        // Active device-bound sessions renew without retaining the password.
        // Expired/deleted sessions cannot reach here and are never resurrected.
        self.db.execute(
            "UPDATE sessions SET expires=? WHERE id=? AND expires<?",
            params![now() + 30 * 86400, actor.session_id, now() + 7 * 86400],
        )?;
        Ok(())
    }
    pub fn login_enrolled(
        &self,
        name: &str,
        password: &str,
        label: &str,
        peer: IpAddr,
    ) -> Result<(String, Actor)> {
        let device: Option<(String, String)> = self
            .db
            .query_row(
                "SELECT id,user_id FROM devices WHERE address=? AND state='active'",
                [peer.to_string()],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )
            .optional()?;
        let (id, user) = device.ok_or(Fault(403, "Enroll this phone before signing in".into()))?;
        let (token, actor) = self.login(name, password, label, &peer.to_string())?;
        if actor.user_id != user {
            self.db
                .execute("DELETE FROM sessions WHERE id=?", [&actor.session_id])?;
            return fail(403, "Sign in as the user assigned to this device");
        }
        self.db.execute(
            "INSERT INTO device_sessions VALUES(?,?)",
            params![actor.session_id, id],
        )?;
        Ok((token, actor))
    }
    pub fn enrollment_overview(&mut self, token: &str) -> Result<Value> {
        let actor = self.actor(token)?;
        if !actor.admin() {
            return fail(403, "Administrator required");
        }
        self.reconcile_peers()?;
        let devices=self.db.prepare("SELECT d.id,d.user_id,u.name,d.name,d.state,d.public_key,d.address,d.verification,d.expires,u.role FROM devices d JOIN users u ON u.id=d.user_id ORDER BY d.rowid DESC")?.query_map([],|r|Ok(json!({"id":r.get::<_,String>(0)?,"user_id":r.get::<_,String>(1)?,"user":r.get::<_,String>(2)?,"name":r.get::<_,String>(3)?,"state":r.get::<_,String>(4)?,"public_key":r.get::<_,String>(5)?,"address":r.get::<_,Option<String>>(6)?,"expires":r.get::<_,i64>(8)?,"can_manage":actor.role=="owner" || !["owner","admin"].contains(&r.get::<_,String>(9)?.as_str()),"can_purge":(["revoked","expired"].contains(&r.get::<_,String>(4)?.as_str()) && r.get::<_,Option<String>>(6)?.is_none())})))?.collect::<std::result::Result<Vec<_>,_>>()?;
        let ack = self.read_peer_ack();
        let error = ack
            .as_ref()
            .and_then(|a| a["error"].as_str().map(str::to_owned));
        let status = if self.network.is_none() {
            "not configured"
        } else if error.is_some() {
            "error"
        } else if ack.as_ref().is_some_and(|a| {
            a["applied_at"]
                .as_i64()
                .is_some_and(|t| t > now() - 60 && t <= now() + 5)
                && std::fs::read(self.data_dir.join("peers.json"))
                    .ok()
                    .is_some_and(|raw| a["digest"] == crypto::digest(&raw))
        }) {
            "current configuration acknowledged"
        } else {
            "waiting for a current host acknowledgement"
        };
        Ok(
            json!({"configured":self.network.is_some(),"devices":devices,"provisioner_error":error,"provisioner_status":status}),
        )
    }

    fn read_peer_ack(&self) -> Option<Value> {
        let path = self.peer_status.as_ref()?;
        let meta = std::fs::symlink_metadata(path).ok()?;
        if !meta.is_file() || meta.len() > 262144 {
            return None;
        }
        #[cfg(unix)]
        {
            use std::os::unix::fs::MetadataExt;
            let owner = if self.development {
                std::fs::metadata(&self.data_dir).ok()?.uid()
            } else {
                0
            };
            if meta.uid() != owner || meta.mode() & 0o022 != 0 {
                return None;
            }
        }
        serde_json::from_slice(&std::fs::read(path).ok()?).ok()
    }
    pub fn reconcile_peers(&mut self) -> Result<()> {
        if self.network.is_none() {
            return Ok(());
        }
        self.db.execute("UPDATE devices SET state='expired',address=NULL WHERE state='pending_approval' AND expires<=?",[now()])?;
        self.db.execute("UPDATE devices SET state='revoking' WHERE state IN ('provisioning','ready') AND expires<=?",[now()])?;
        self.db.execute("UPDATE devices SET state='revoking' WHERE user_id IN (SELECT id FROM users WHERE active=0) AND state NOT IN ('revoked','expired')",[])?;
        let ids=self.db.prepare("SELECT id FROM devices WHERE state IN ('provisioning','ready','active') ORDER BY id")?.query_map([],|r|r.get::<_,String>(0))?.collect::<std::result::Result<Vec<_>,_>>()?;
        let mut peers = Vec::new();
        let mut public = Vec::new();
        for id in ids {
            let device = self.device(&id)?;
            let address = format!(
                "{}/32",
                device
                    .address
                    .ok_or(Fault(500, "Enrolled peer address missing".into()))?
            );
            peers.push(json!({"id":device.id,"public_key":device.public,"address":address,"preshared_key":device.data["config"]["preshared_key"]}));
            public.push(json!({"id":device.id,"public_key":device.public,"address":address}));
        }
        let raw = serde_json::to_vec(&json!({"version":1,"peers":peers}))
            .map_err(|_| Fault(500, "Peer serialization failed".into()))?;
        let digest = crypto::digest(&raw);
        let path = self.data_dir.join("peers.json");
        if std::fs::read(&path).ok().as_deref() != Some(raw.as_slice()) {
            let temporary = self.data_dir.join(format!(".peers-{}", id()));
            let mut options = std::fs::OpenOptions::new();
            options.write(true).create_new(true);
            #[cfg(unix)]
            {
                use std::os::unix::fs::OpenOptionsExt;
                options.mode(0o600);
            }
            let write = (|| -> std::io::Result<()> {
                let mut f = options.open(&temporary)?;
                f.write_all(&raw)?;
                f.sync_all()?;
                std::fs::rename(&temporary, &path)?;
                std::fs::File::open(&self.data_dir)?.sync_all()
            })();
            if write.is_err() {
                let _ = std::fs::remove_file(temporary);
                return fail(
                    503,
                    "Peer manifest could not be saved; provisioning is not complete",
                );
            }
        }
        if let Some(ack) = self.read_peer_ack() {
            let fresh = ack["applied_at"]
                .as_i64()
                .is_some_and(|t| t <= now() + 5 && t > now() - 60);
            if fresh
                && ack["version"] == 1
                && ack["digest"] == digest
                && ack["error"].is_null()
                && ack["peers"] == json!(public)
            {
                self.db.execute(
                    "UPDATE devices SET state='ready' WHERE state='provisioning' AND expires>?",
                    [now()],
                )?;
                self.db.execute(
                    "UPDATE devices SET state='revoked',address=NULL WHERE state='revoking'",
                    [],
                )?;
            }
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    struct Directory(std::path::PathBuf);
    impl Drop for Directory {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }
    fn ack(store: &Store) {
        let raw = std::fs::read(store.data_dir.join("peers.json")).unwrap();
        let mut manifest: Value = serde_json::from_slice(&raw).unwrap();
        for peer in manifest["peers"].as_array_mut().unwrap() {
            peer.as_object_mut().unwrap().remove("preshared_key");
        }
        std::fs::write(store.peer_status.as_ref().unwrap(),serde_json::to_vec(&json!({"version":1,"digest":crypto::digest(&raw),"peers":manifest["peers"],"applied_at":now(),"error":null})).unwrap()).unwrap();
    }
    fn setup() -> (Directory, Store, String, Actor) {
        let dir = Directory(std::env::temp_dir().join(format!("field-notes-enrollment-{}", id())));
        std::fs::create_dir(&dir.0).unwrap();
        let key = dir.0.join("master");
        Vault::create(&key).unwrap();
        let mut s = Store::open(&dir.0.join("data"), &key).unwrap();
        s.initialize("owner", "disposable-enrollment-password")
            .unwrap();
        let (owner, actor) = s
            .login(
                "owner",
                "disposable-enrollment-password",
                "Test",
                "127.0.0.1",
            )
            .unwrap();
        s.reauthenticate(&actor, "disposable-enrollment-password")
            .unwrap();
        s.network = Some(NetworkConfig {
            bootstrap_url: "https://192.168.1.10:9443/api/enrollment".into(),
            endpoint: "home.example:49272".into(),
            lan_endpoint: Some("192.168.1.10:49272".into()),
            server_key: STANDARD.encode([3; 32]),
            api: "https://10.82.0.1:8443".into(),
            ca_pem: include_str!("../../../../tests/fixtures/enrollment-ca.crt").into(),
            overlay: "10.82.0.0/24".into(),
        });
        s.peer_status = Some(dir.0.join("ack"));
        s.development = true;
        (dir, s, owner, actor)
    }
    #[test]
    fn live_sessions_renew_only_from_their_peer_and_self_revoke_only_one_installation() {
        let (_dir, mut s, owner, actor) = setup();
        let enroll = |s: &mut Store, key: u8| {
            let invitation = s.enrollment_invite(&owner, &actor.user_id).unwrap();
            let raw = invitation["code"]
                .as_str()
                .unwrap()
                .strip_prefix("fieldnotes://enroll#")
                .unwrap();
            let payload: Value =
                serde_json::from_slice(&URL_SAFE_NO_PAD.decode(raw).unwrap()).unwrap();
            let claim = s.enrollment_claim(json!({"token":payload["token"],"claim_secret":crypto::token(),"public_key":STANDARD.encode([key;32]),"name":"Phone"}), "192.168.1.20").unwrap();
            let id = claim["id"].as_str().unwrap().to_owned();
            s.enrollment_approve(&owner, &id, claim["verification"].as_str().unwrap())
                .unwrap();
            ack(s);
            s.reconcile_peers().unwrap();
            let device = s.device(&id).unwrap();
            let peer: IpAddr = device.address.unwrap().parse().unwrap();
            let result = s.activate_device(
                json!({"id":id,"activation_secret":device.data["activation_secret"]}),
                peer,
            );
            (id, peer, result)
        };
        let (first, peer, result) = enroll(&mut s, 20);
        let active = result.unwrap();
        let token = active["token"].as_str().unwrap();
        let (_, other_peer, other) = enroll(&mut s, 21);
        let other = other.unwrap();
        let other_token = other["token"].as_str().unwrap();
        let session = crypto::digest(token.as_bytes());
        let near_expiry = now() + 60;
        s.db.execute(
            "UPDATE sessions SET expires=? WHERE id=?",
            params![near_expiry, session],
        )
        .unwrap();
        assert_eq!(s.authorize_device(token, other_peer).unwrap_err().0, 403);
        let expiry = |s: &Store| {
            s.db.query_row("SELECT expires FROM sessions WHERE id=?", [&session], |r| {
                r.get::<_, i64>(0)
            })
            .unwrap()
        };
        assert_eq!(expiry(&s), near_expiry);
        s.authorize_device(token, peer).unwrap();
        assert!(expiry(&s) > now() + 29 * 86400);
        s.db.execute(
            "UPDATE sessions SET expires=? WHERE id=?",
            params![now() - 1, session],
        )
        .unwrap();
        assert_eq!(s.authorize_device(token, peer).unwrap_err().0, 401);
        s.db.execute(
            "UPDATE sessions SET expires=? WHERE id=?",
            params![now() + 86400, session],
        )
        .unwrap();
        assert_eq!(s.revoke_self(&owner).unwrap_err().0, 403);
        let revoked = s.revoke_self(token).unwrap();
        assert_eq!(revoked["authorization_revoked"], true);
        assert_eq!(revoked["peer_removal_pending"], true);
        assert!(s.actor(token).is_err());
        assert!(s.revoke_self(token).is_err());
        s.authorize_device(other_token, other_peer).unwrap();
        assert!(s.actor(&owner).is_ok());
        ack(&s);
        s.reconcile_peers().unwrap();
        assert_eq!(s.device(&first).unwrap().state, "revoked");
        assert!(s.device(&first).unwrap().address.is_none());
    }
    #[test]
    fn expiry_permissions_and_unacknowledged_removal_do_not_reuse_a_peer_address() {
        let (_dir, mut s, owner, _actor) = setup();
        let member = s
            .create_user("Member", "disposable-member-password", "member")
            .unwrap();
        let claim = |s: &mut Store, byte: u8| {
            let invitation = s.enrollment_invite(&owner, &member).unwrap();
            let raw = invitation["code"]
                .as_str()
                .unwrap()
                .strip_prefix("fieldnotes://enroll#")
                .unwrap();
            let payload: Value =
                serde_json::from_slice(&URL_SAFE_NO_PAD.decode(raw).unwrap()).unwrap();
            let input = json!({"token":payload["token"],"claim_secret":crypto::token(),"public_key":STANDARD.encode([byte;32]),"name":"Phone"});
            (
                input.clone(),
                s.enrollment_claim(input, "192.168.1.20").unwrap(),
            )
        };
        let (input, pending) = claim(&mut s, 8);
        let id = pending["id"].as_str().unwrap();
        s.db.execute("UPDATE users SET role='viewer' WHERE id=?", [&member])
            .unwrap();
        assert_eq!(
            s.enrollment_approve(&owner, id, pending["verification"].as_str().unwrap())
                .unwrap_err()
                .0,
            410
        );
        s.db.execute("UPDATE users SET role='member' WHERE id=?", [&member])
            .unwrap();
        s.enrollment_approve(&owner, id, pending["verification"].as_str().unwrap())
            .unwrap();
        ack(&s);
        s.reconcile_peers().unwrap();
        assert_eq!(s.device(id).unwrap().state, "ready");
        s.db.execute(
            "UPDATE devices SET expires=? WHERE id=?",
            params![now() - 1, id],
        )
        .unwrap();
        s.reconcile_peers().unwrap();
        assert_eq!(s.device(id).unwrap().state, "revoking");
        assert_eq!(s.device(id).unwrap().address.as_deref(), Some("10.82.0.2"));
        assert_eq!(
            s.enrollment_status(json!({"id":id,"claim_secret":input["claim_secret"]}))
                .unwrap_err()
                .0,
            410
        );
        let (_, second) = claim(&mut s, 9);
        assert_eq!(
            s.device(second["id"].as_str().unwrap())
                .unwrap()
                .address
                .as_deref(),
            Some("10.82.0.3")
        );
        ack(&s);
        s.reconcile_peers().unwrap();
        assert!(s.device(id).unwrap().address.is_none());
        let (third_input, third) = claim(&mut s, 10);
        let third_id = third["id"].as_str().unwrap();
        assert_eq!(
            s.device(third_id).unwrap().address.as_deref(),
            Some("10.82.0.2")
        );
        s.enrollment_cancel(json!({"id":third_id,"claim_secret":third_input["claim_secret"]}))
            .unwrap();
        assert_eq!(s.device(third_id).unwrap().state, "revoked");
        let (member_token, _) = s
            .login("Member", "disposable-member-password", "Phone", "LAN")
            .unwrap();
        assert_eq!(
            s.enrollment_invite(&member_token, &member).unwrap_err().0,
            403
        );
    }
    #[test]
    fn enrollment_requires_approval_host_ack_and_the_bound_peer_and_revokes_immediately() {
        let (_dir, mut s, owner, actor) = setup();
        let invitation = s.enrollment_invite(&owner, &actor.user_id).unwrap();
        let code = invitation["code"]
            .as_str()
            .unwrap()
            .strip_prefix("fieldnotes://enroll#")
            .unwrap();
        let payload: Value =
            serde_json::from_slice(&URL_SAFE_NO_PAD.decode(code).unwrap()).unwrap();
        let claim_secret = crypto::token();
        let claim = json!({"token":payload["token"],"claim_secret":claim_secret,"public_key":STANDARD.encode([7;32]),"name":"Test phone"});
        let first = s.enrollment_claim(claim.clone(), "192.168.1.20").unwrap();
        let id = first["id"].as_str().unwrap();
        assert_eq!(
            s.enrollment_claim(claim.clone(), "192.168.1.20").unwrap()["id"],
            id
        );
        let mut thief = claim.clone();
        thief["public_key"] = json!(STANDARD.encode([8; 32]));
        assert_eq!(
            s.enrollment_claim(thief, "192.168.1.21").unwrap_err().0,
            409
        );
        let poll = json!({"id":id,"claim_secret":claim_secret});
        let pending = s.enrollment_status(poll.clone()).unwrap();
        assert_eq!(pending["state"], "pending_approval");
        assert!(pending.get("configuration").is_none());
        assert_eq!(
            s.enrollment_approve(&owner, id, "WRONG").unwrap_err().0,
            403
        );
        s.enrollment_approve(&owner, id, first["verification"].as_str().unwrap())
            .unwrap();
        assert_eq!(
            s.enrollment_status(poll.clone()).unwrap()["state"],
            "provisioning"
        );
        // A partial/incorrect host acknowledgement cannot furnish credentials.
        std::fs::write(
            s.peer_status.as_ref().unwrap(),
            br#"{"version":1,"digest":"wrong","peers":[],"error":null,"applied_at":0}"#,
        )
        .unwrap();
        assert_eq!(
            s.enrollment_status(poll.clone()).unwrap()["state"],
            "provisioning"
        );
        ack(&s);
        let ready = s.enrollment_status(poll.clone()).unwrap();
        assert_eq!(ready["state"], "ready");
        assert_eq!(ready["configuration"]["api"], "https://10.82.0.1:8443");
        assert!(ready["configuration"].get("private_key").is_none());
        let activate = json!({"id":id,"activation_secret":ready["activation_secret"]});
        assert_eq!(
            s.activate_device(activate.clone(), "127.0.0.1".parse().unwrap())
                .unwrap_err()
                .0,
            403
        );
        let source: IpAddr = "10.82.0.2".parse().unwrap();
        let active = s.activate_device(activate.clone(), source).unwrap();
        let token = active["token"].as_str().unwrap();
        assert_eq!(s.activate_device(activate, source).unwrap()["token"], token);
        s.authorize_device(token, source).unwrap();
        assert_eq!(
            s.authorize_device(token, "10.82.0.3".parse().unwrap())
                .unwrap_err()
                .0,
            403
        );
        assert_eq!(s.authorize_device(&owner, source).unwrap_err().0, 403);
        assert!(s
            .enrollment_status(poll)
            .unwrap()
            .get("configuration")
            .is_none());
        let revoked = s.enrollment_revoke(&owner, id).unwrap();
        assert_eq!(revoked["state"], "revoking");
        assert!(s.actor(token).is_err());
        // Peer removal is not falsely completed before the helper acknowledges it.
        ack(&s);
        s.reconcile_peers().unwrap();
        assert_eq!(s.device(id).unwrap().state, "revoked");
        assert!(s.device(id).unwrap().address.is_none());
        assert_eq!(
            s.activate_device(
                json!({"id":id,"activation_secret":ready["activation_secret"]}),
                source
            )
            .unwrap_err()
            .0,
            403
        );
        let bytes = std::fs::read(s.data_dir.join("field-notes.sqlite3")).unwrap();
        assert!(!bytes
            .windows(payload["token"].as_str().unwrap().len())
            .any(|w| w == payload["token"].as_str().unwrap().as_bytes()));
    }
}
