use aes_gcm::{
    aead::{Aead, KeyInit, Payload},
    Aes256Gcm, Nonce,
};
use argon2::{
    password_hash::{PasswordHash, PasswordHasher, PasswordVerifier, SaltString},
    Argon2,
};
use sha2::{Digest, Sha256};
use std::{
    fs::{self, OpenOptions},
    io::Write,
    path::Path,
};
use zeroize::Zeroizing;

pub struct Vault(Zeroizing<[u8; 32]>);
pub fn random<const N: usize>() -> [u8; N] {
    let mut x = [0; N];
    getrandom::getrandom(&mut x).expect("OS randomness unavailable");
    x
}
pub fn token() -> String {
    hex::encode(random::<32>())
}
pub fn digest(x: &[u8]) -> String {
    hex::encode(Sha256::digest(x))
}
pub fn password_hash(password: &str) -> Result<String, String> {
    if password.len() < 12 || password.len() > 256 {
        return Err("Use a password between 12 and 256 bytes".into());
    }
    let salt = SaltString::encode_b64(&random::<16>()).map_err(|_| "salt")?;
    Argon2::default()
        .hash_password(password.as_bytes(), &salt)
        .map(|h| h.to_string())
        .map_err(|_| "password hashing failed".into())
}
pub fn verify(password: &str, hash: &str) -> bool {
    PasswordHash::new(hash).is_ok_and(|h| {
        Argon2::default()
            .verify_password(password.as_bytes(), &h)
            .is_ok()
    })
}
impl Vault {
    pub fn create(path: &Path) -> Result<(), String> {
        if let Some(p) = path.parent() {
            fs::create_dir_all(p).map_err(|e| e.to_string())?;
        }
        let mut o = OpenOptions::new();
        o.write(true).create_new(true);
        #[cfg(unix)]
        {
            use std::os::unix::fs::OpenOptionsExt;
            o.mode(0o600);
        }
        let mut f = o.open(path).map_err(|e| format!("Create key file: {e}"))?;
        f.write_all(&random::<32>()).map_err(|e| e.to_string())?;
        f.sync_all().map_err(|e| e.to_string())
    }
    pub fn load(path: &Path) -> Result<Self, String> {
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let mode = fs::metadata(path)
                .map_err(|e| e.to_string())?
                .permissions()
                .mode();
            if mode & 0o077 != 0 {
                return Err("Key file must be accessible only by its owner (chmod 600)".into());
            }
        }
        let b = Zeroizing::new(fs::read(path).map_err(|e| format!("Read key: {e}"))?);
        let key: [u8; 32] = b
            .as_slice()
            .try_into()
            .map_err(|_| "Key must be exactly 32 bytes")?;
        Ok(Self(Zeroizing::new(key)))
    }
    pub fn seal(&self, aad: &str, bytes: &[u8]) -> Result<Vec<u8>, String> {
        let nonce = random::<12>();
        let cipher = Aes256Gcm::new_from_slice(self.0.as_ref()).map_err(|_| "key")?;
        let encrypted = cipher
            .encrypt(
                Nonce::from_slice(&nonce),
                Payload {
                    msg: bytes,
                    aad: aad.as_bytes(),
                },
            )
            .map_err(|_| "encryption failed")?;
        let mut out = vec![1];
        out.extend(nonce);
        out.extend(encrypted);
        Ok(out)
    }
    pub fn open(&self, aad: &str, bytes: &[u8]) -> Result<Vec<u8>, String> {
        if bytes.len() < 29 || bytes[0] != 1 {
            return Err("Invalid encrypted record".into());
        }
        Aes256Gcm::new_from_slice(self.0.as_ref())
            .map_err(|_| "key")?
            .decrypt(
                Nonce::from_slice(&bytes[1..13]),
                Payload {
                    msg: &bytes[13..],
                    aad: aad.as_bytes(),
                },
            )
            .map_err(|_| "Encrypted record authentication failed; check recovery key".into())
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn authenticated_encryption_binds_record_and_detects_tampering() {
        let v = Vault(Zeroizing::new(random()));
        let mut encrypted = v.seal("task:one", b"private title").unwrap();
        assert_eq!(v.open("task:one", &encrypted).unwrap(), b"private title");
        assert!(v.open("task:two", &encrypted).is_err());
        encrypted[15] ^= 1;
        assert!(v.open("task:one", &encrypted).is_err());
    }
}
