//! Key-bound bootstrap over the existing private API. No phone management access.
use super::*;

impl Store {
    pub fn tunnel_invitation(&mut self, token: &str, input: Value) -> Result<Value> {
        fields(&input, &["user_id", "public_key"])?;
        let user = text(&input, "user_id", 36)?;
        let public = text(&input, "public_key", 44)?;
        canonical_key(&public)?;
        let actor = self.enrollment_admin(token, &user)?;
        let network = self
            .network
            .clone()
            .ok_or(Fault(503, "Enrollment networking is not configured".into()))?;
        network.validate()?;
        self.reconcile_peers()?;
        let tx = rusqlite::Transaction::new_unchecked(
            &self.db,
            rusqlite::TransactionBehavior::Immediate,
        )?;
        let existing: Option<String> = self
            .db
            .query_row(
                "SELECT id FROM devices WHERE public_key=?",
                [&public],
                |r| r.get(0),
            )
            .optional()?;
        if let Some(existing) = existing {
            let device = self.device(&existing)?;
            if device.user == user
                && device.state == "invited"
                && device.expires > now()
                && device.data["issued_invitation"].is_object()
            {
                let issued = &device.data["issued_invitation"];
                let mut result = invitation_image(
                    issued["id"]
                        .as_str()
                        .ok_or(Fault(500, "Invalid saved invitation".into()))?,
                    &issued["payload"],
                )?;
                result["device_id"] = json!(existing);
                tx.commit()?;
                self.reconcile_peers()?;
                return Ok(result);
            }
            if device.user == user
                && ["revoked", "expired"].contains(&device.state.as_str())
                && device.address.is_none()
                && ((device.data["tunnel_enrollment"] == true
                    && device.data["activated_once"] != true)
                    || device.state == "expired")
            {
                // Only a never-activated reservation after fresh root removal
                // acknowledgement may be replaced. Offline active phones never qualify.
                self.db.execute(
                    "DELETE FROM enrollment_invites WHERE device_id=?",
                    [&existing],
                )?;
                self.db
                    .execute("DELETE FROM devices WHERE id=?", [&existing])?;
            } else {
                return fail(409, "This phone key already has a binding. Keep active pairings; revoke and remove an unused binding before issuing a replacement.");
            }
        }
        let count: i64 = self.db.query_row(
            "SELECT COUNT(*) FROM devices WHERE user_id=? AND state NOT IN ('revoked','expired')",
            [&user],
            |r| r.get(0),
        )?;
        if count >= 16 {
            return fail(429, "Revoke an old device before enrolling another");
        }
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
                .query_row("SELECT name,role FROM users WHERE id=?", [&user], |r| {
                    Ok((r.get(0)?, r.get(1)?))
                })?;
        let subnet: ipnet::Ipv4Net = network
            .overlay
            .parse()
            .map_err(|_| Fault(500, "Invalid peer pool".into()))?;
        let address = (2..255)
            .map(|n| Ipv4Addr::from(u32::from(subnet.network()) + n).to_string())
            .find(|a| {
                self.db
                    .query_row(
                        "SELECT NOT EXISTS(SELECT 1 FROM devices WHERE address=?)",
                        [a],
                        |r| r.get::<_, bool>(0),
                    )
                    .unwrap_or(false)
            })
            .ok_or(Fault(
                409,
                "Device address pool is full; finish revoking old devices".into(),
            ))?;
        let invitation_id = id();
        let device_id = id();
        let raw = crypto::token();
        let activation = crypto::token();
        let expires = now() + 600;
        let psk = STANDARD.encode(
            hex::decode(crypto::token()).map_err(|_| Fault(500, "Key generation failed".into()))?,
        );
        let config = json!({"address":format!("{address}/32"),"endpoint":network.endpoint,"alternate_endpoints":network.lan_endpoint.iter().collect::<Vec<_>>(),"server_key":network.server_key,"api":network.api,"ca_pem":network.ca_pem,"preshared_key":psk});
        let mut tunnel = config.clone();
        tunnel.as_object_mut().unwrap().remove("ca_pem");
        tunnel["public_key"] = json!(public);
        let invitation = json!({"version":2,"url":format!("{}/api/enrollment",network.api),"ca":network.ca_pem,"token":raw,"user":name,"role":role,"expires":expires,"tunnel":tunnel});
        let data = json!({"config":config,"activation_secret":activation,"activation_until":expires,"role":role,"tunnel_enrollment":true,"issued_invitation":{"id":invitation_id,"payload":invitation}});
        let mut result = invitation_image(&invitation_id, &invitation)?;
        result["device_id"] = json!(device_id);
        let encrypted = self.vault.seal(
            &format!("device:{device_id}"),
            &serde_json::to_vec(&data)
                .map_err(|_| Fault(500, "Device serialization failed".into()))?,
        )?;
        self.db.execute(
            "INSERT INTO devices VALUES(?,?,?,?,?,'invited','',?,?,?,?)",
            params![
                device_id,
                user,
                public,
                address,
                "Waiting for phone",
                crypto::digest(activation.as_bytes()),
                expires,
                "",
                encrypted
            ],
        )?;
        let sealed = self.vault.seal(
            &format!("invite:{invitation_id}"),
            &serde_json::to_vec(&network)
                .map_err(|_| Fault(500, "Network serialization failed".into()))?,
        )?;
        self.db.execute(
            "INSERT INTO enrollment_invites VALUES(?,?,?,?,?,?,?,?)",
            params![
                invitation_id,
                crypto::digest(raw.as_bytes()),
                actor.user_id,
                user,
                role,
                expires,
                device_id,
                sealed
            ],
        )?;
        self.audit(
            &actor,
            "device.invite",
            &user,
            &json!({"expires":expires,"transport":"wireguard","device_id":device_id}),
        )?;
        tx.commit()?;
        // Publication failure leaves a durable reservation. A retry must never
        // create a second identity or release an address before root removes it.
        self.reconcile_peers()?;
        Ok(result)
    }

    /// Bind unauthenticated enrollment routes to the key-proven WireGuard /32.
    /// These routes grant no content session and accept no browser credentials.
    pub fn authorize_enrollment_source(
        &self,
        input: &Value,
        peer: IpAddr,
        private: bool,
    ) -> Result<()> {
        let device_id: Option<String> = if input.get("token").is_some() {
            let token = text(input, "token", 64)?;
            secret(&token)?;
            self.db
                .query_row(
                    "SELECT device_id FROM enrollment_invites WHERE token_hash=?",
                    [crypto::digest(token.as_bytes())],
                    |r| r.get(0),
                )
                .optional()?
                .flatten()
        } else {
            input.get("id").and_then(Value::as_str).map(str::to_owned)
        };
        let Some(device_id) = device_id else {
            return if private {
                fail(403, "A key-bound tunnel invitation is required")
            } else {
                Ok(())
            };
        };
        let device = self.device(&device_id)?;
        if device.data["tunnel_enrollment"] == true {
            if !private || device.address.as_deref() != Some(peer.to_string().as_str()) {
                return fail(
                    403,
                    "Enrollment is restricted to this phone's private tunnel",
                );
            }
            if ![
                "invited",
                "pending_approval",
                "provisioning",
                "ready",
                "active",
            ]
            .contains(&device.state.as_str())
            {
                return fail(410, "Enrollment expired or was revoked");
            }
        } else if private {
            return fail(403, "A key-bound tunnel invitation is required");
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::store::enrollment::tests::{ack, setup};
    fn payload(value: &Value) -> Value {
        serde_json::from_slice(
            &URL_SAFE_NO_PAD
                .decode(value["code"].as_str().unwrap().split('#').nth(1).unwrap())
                .unwrap(),
        )
        .unwrap()
    }

    #[test]
    fn key_bound_tunnel_claim_cannot_use_management_or_another_peer_and_withholds_content() {
        let (_dir, mut s, owner, actor) = setup();
        let key = STANDARD.encode([71; 32]);
        let issued = s
            .tunnel_invitation(&owner, json!({"user_id":actor.user_id,"public_key":key}))
            .unwrap();
        let data = payload(&issued);
        assert_eq!(data["version"], 2);
        assert_eq!(data["url"], "https://10.82.0.1:8443/api/enrollment");
        assert!(!data.to_string().contains("private_key"));
        let id = issued["device_id"].as_str().unwrap();
        let peer: IpAddr = "10.82.0.2".parse().unwrap();
        let nonce = crypto::token();
        let claim =
            json!({"token":data["token"],"public_key":key,"claim_secret":nonce,"name":"My phone"});
        assert_eq!(
            s.authorize_enrollment_source(&claim, peer, false)
                .unwrap_err()
                .0,
            403
        );
        assert_eq!(
            s.authorize_enrollment_source(&claim, "10.82.0.3".parse().unwrap(), true)
                .unwrap_err()
                .0,
            403
        );
        s.authorize_enrollment_source(&claim, peer, true).unwrap();
        let manifest: Value =
            serde_json::from_slice(&std::fs::read(s.data_dir.join("peers.json")).unwrap()).unwrap();
        assert_eq!(manifest["peers"].as_array().unwrap().len(), 1);
        let claimed = s.enrollment_claim(claim.clone(), "10.82.0.2").unwrap();
        assert_eq!(claimed["id"], id);
        assert_eq!(
            s.enrollment_claim(claim.clone(), "10.82.0.2").unwrap(),
            claimed
        );
        let mut stolen = claim;
        stolen["claim_secret"] = json!(crypto::token());
        assert_eq!(s.enrollment_claim(stolen, "10.82.0.2").unwrap_err().0, 409);
        let status = json!({"id":id,"claim_secret":nonce});
        ack(&s);
        assert!(s
            .enrollment_status(status.clone())
            .unwrap()
            .get("configuration")
            .is_none());
        assert!(s
            .login_enrolled("owner", "disposable-enrollment-password", "Intruder", peer)
            .is_err());
        s.enrollment_approve(&owner, id, claimed["verification"].as_str().unwrap())
            .unwrap();
        // The preapproval peer acknowledgement matches, but approval still needs reconciliation.
        s.reconcile_peers().unwrap();
        let ready = s.enrollment_status(status).unwrap();
        assert_eq!(ready["state"], "ready");
        assert_eq!(
            ready["configuration"]["preshared_key"],
            data["tunnel"]["preshared_key"]
        );
        let active = s
            .activate_device(
                json!({"id":id,"activation_secret":ready["activation_secret"]}),
                peer,
            )
            .unwrap();
        s.authorize_device(active["token"].as_str().unwrap(), peer)
            .unwrap();
        assert!(s.device(id).unwrap().data["activated_once"] == true);
        let next = json!({"user_id":actor.user_id,"public_key":key});
        assert_eq!(
            s.tunnel_invitation(&owner, next.clone()).unwrap_err().0,
            409
        );
        s.enrollment_revoke(&owner, id).unwrap();
        ack(&s);
        s.reconcile_peers().unwrap();
        assert_eq!(
            s.tunnel_invitation(&owner, next).unwrap_err().0,
            409,
            "Previously activated phones cannot be discarded by automatic reservation cleanup"
        );
    }

    #[test]
    fn expired_unused_reservations_require_fresh_removal_ack_and_reissue_keeps_phone_key() {
        let (_dir, mut s, owner, actor) = setup();
        let key = STANDARD.encode([72; 32]);
        let input = json!({"user_id":actor.user_id,"public_key":key});
        let first = s.tunnel_invitation(&owner, input.clone()).unwrap();
        assert_eq!(
            s.tunnel_invitation(&owner, input.clone()).unwrap()["code"],
            first["code"]
        );
        let old = first["device_id"].as_str().unwrap();
        ack(&s);
        s.db.execute(
            "UPDATE devices SET expires=? WHERE id=?",
            params![now() - 1, old],
        )
        .unwrap();
        s.reconcile_peers().unwrap();
        assert_eq!(s.device(old).unwrap().state, "revoking");
        assert!(s.device(old).unwrap().address.is_some());
        assert_eq!(
            s.tunnel_invitation(&owner, input.clone()).unwrap_err().0,
            409
        );
        ack(&s);
        s.reconcile_peers().unwrap();
        assert!(s.device(old).unwrap().address.is_none());
        let next = s.tunnel_invitation(&owner, input).unwrap();
        assert_ne!(next["device_id"], first["device_id"]);
        assert_eq!(payload(&next)["tunnel"]["public_key"], key);
        assert_ne!(
            payload(&next)["tunnel"]["preshared_key"],
            payload(&first)["tunnel"]["preshared_key"]
        );
    }

    #[test]
    fn issuance_audit_failure_rolls_back_both_rows_and_publication_failure_is_retryable() {
        let (_dir, mut s, owner, actor) = setup();
        let input = json!({"user_id":actor.user_id,"public_key":STANDARD.encode([73;32])});
        s.db.execute_batch("CREATE TRIGGER fail_invite BEFORE INSERT ON audit WHEN NEW.action='device.invite' BEGIN SELECT RAISE(ABORT,'injected'); END;").unwrap();
        assert_eq!(
            s.tunnel_invitation(&owner, input.clone()).unwrap_err().0,
            500
        );
        assert_eq!(
            s.db.query_row("SELECT COUNT(*) FROM devices", [], |r| r.get::<_, i64>(0))
                .unwrap(),
            0
        );
        s.db.execute_batch("DROP TRIGGER fail_invite;").unwrap();
        let issued = s.tunnel_invitation(&owner, input.clone()).unwrap();
        std::fs::remove_file(s.data_dir.join("peers.json")).unwrap();
        std::fs::create_dir(s.data_dir.join("peers.json")).unwrap();
        assert_eq!(
            s.tunnel_invitation(&owner, input.clone()).unwrap_err().0,
            503
        );
        assert_eq!(
            s.db.query_row("SELECT COUNT(*) FROM devices", [], |r| r.get::<_, i64>(0))
                .unwrap(),
            1
        );
        std::fs::remove_dir(s.data_dir.join("peers.json")).unwrap();
        let retry = s.tunnel_invitation(&owner, input).unwrap();
        assert_eq!(payload(&retry)["version"], 2);
        assert_eq!(retry["code"], issued["code"]);
        assert_eq!(
            s.db.query_row("SELECT COUNT(*) FROM devices", [], |r| r.get::<_, i64>(0))
                .unwrap(),
            1
        );
    }

    #[test]
    fn legacy_expired_unapproved_attempt_can_migrate_using_the_same_phone_key() {
        let (_dir, mut s, owner, actor) = setup();
        let key = STANDARD.encode([74; 32]);
        let legacy = s.enrollment_invite(&owner, &actor.user_id).unwrap();
        let old = payload(&legacy);
        let claimed=s.enrollment_claim(json!({"token":old["token"],"claim_secret":crypto::token(),"public_key":key,"name":"Legacy attempt"}),"192.168.1.20").unwrap();
        s.db.execute(
            "UPDATE devices SET expires=? WHERE id=?",
            params![now() - 1, claimed["id"].as_str().unwrap()],
        )
        .unwrap();
        s.reconcile_peers().unwrap();
        let next = s
            .tunnel_invitation(&owner, json!({"user_id":actor.user_id,"public_key":key}))
            .unwrap();
        assert_eq!(payload(&next)["tunnel"]["public_key"], key);
        assert_eq!(payload(&next)["version"], 2);
    }
}
