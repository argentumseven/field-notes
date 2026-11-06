//! Bounded-memory authenticated backup files. Version 1 remains readable.
use crate::{crypto, store::Store};
use std::{
    fs::{self, File, OpenOptions},
    io::{Read, Write},
    path::{Path, PathBuf},
};
use zeroize::Zeroizing;
const MAGIC: &[u8] = b"FIELDNOTES-BACKUP-2\n";
const CHUNK: usize = 256 * 1024;
const MAX: u64 = 8 * 1024 * 1024 * 1024;

pub struct BackupFile {
    pub path: PathBuf,
    pub size: u64,
}
impl Drop for BackupFile {
    fn drop(&mut self) {
        let _ = fs::remove_file(&self.path);
    }
}
impl BackupFile {
    fn new(directory: &Path, suffix: &str) -> Result<Self, String> {
        let path = directory.join(format!(
            ".field-notes-backup-{}.{suffix}",
            crate::store::id()
        ));
        private_file(&path)?;
        Ok(Self { path, size: 0 })
    }
    pub fn copy_to(&self, destination: &Path) -> Result<(), String> {
        let mut output = private_file(destination)?;
        let result = (|| {
            std::io::copy(
                &mut File::open(&self.path).map_err(|_| "Read encrypted backup")?,
                &mut output,
            )
            .map_err(|_| "Write encrypted backup")?;
            output.sync_all().map_err(|_| "Save encrypted backup")
        })()
        .map_err(str::to_owned);
        if result.is_err() {
            let _ = fs::remove_file(destination);
        }
        result
    }
}
fn private_file(path: &Path) -> Result<File, String> {
    let mut options = OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    options
        .open(path)
        .map_err(|_| "Create a new private backup file; an existing file is never replaced".into())
}
fn aad(header: &[u8], index: u64) -> String {
    format!("backup:v2:{}:{index}", hex::encode(header))
}
fn encrypt(
    vault: &crypto::Vault,
    input: &mut impl Read,
    output: &mut impl Write,
    length: u64,
) -> Result<(), String> {
    if !(16..=MAX).contains(&length) {
        return Err("Backup size exceeds its supported range".into());
    }
    let mut header = MAGIC.to_vec();
    header.extend(crypto::random::<16>());
    header.extend(length.to_be_bytes());
    output
        .write_all(&header)
        .map_err(|_| "Write backup header")?;
    let mut remaining = length;
    let mut index = 0;
    let mut buffer = Zeroizing::new(vec![0u8; CHUNK]);
    while remaining > 0 {
        let n = remaining.min(CHUNK as u64) as usize;
        input
            .read_exact(&mut buffer[..n])
            .map_err(|_| "Read complete database snapshot")?;
        let sealed = vault.seal(&aad(&header, index), &buffer[..n])?;
        output
            .write_all(&sealed)
            .map_err(|_| "Write encrypted backup chunk")?;
        remaining -= n as u64;
        index += 1;
    }
    Ok(())
}
fn decrypt(
    vault: &crypto::Vault,
    input: &mut impl Read,
    output: &mut impl Write,
) -> Result<(), String> {
    let mut magic = vec![0u8; MAGIC.len()];
    input
        .read_exact(&mut magic)
        .map_err(|_| "Backup is incomplete")?;
    if magic != MAGIC {
        return Err("Unrecognized backup format".into());
    }
    let mut rest = [0u8; 24];
    input
        .read_exact(&mut rest)
        .map_err(|_| "Backup header is incomplete")?;
    let length = u64::from_be_bytes(rest[16..].try_into().map_err(|_| "Invalid backup header")?);
    if !(16..=MAX).contains(&length) {
        return Err("Invalid backup size".into());
    }
    let mut header = magic;
    header.extend(rest);
    let mut remaining = length;
    let mut index = 0;
    while remaining > 0 {
        let n = remaining.min(CHUNK as u64) as usize;
        let mut sealed = vec![0u8; n + 29];
        input
            .read_exact(&mut sealed)
            .map_err(|_| "Backup chunk is incomplete")?;
        let plain = Zeroizing::new(vault.open(&aad(&header, index), &sealed)?);
        if plain.len() != n || index == 0 && !plain.starts_with(b"SQLite format 3\0") {
            return Err("Invalid database backup".into());
        }
        output
            .write_all(&plain)
            .map_err(|_| "Write restored database")?;
        remaining -= n as u64;
        index += 1;
    }
    let mut extra = [0];
    if input.read(&mut extra).map_err(|_| "Read backup ending")? != 0 {
        return Err("Unexpected trailing backup data".into());
    }
    Ok(())
}
pub fn create(store: &Store, directory: &Path) -> Result<BackupFile, String> {
    let snapshot = BackupFile::new(directory, "sqlite3")?;
    store
        .db
        .execute(
            "VACUUM main INTO ?",
            [snapshot.path.to_string_lossy().as_ref()],
        )
        .map_err(|_| "Could not create a consistent backup snapshot; check free disk space")?;
    let length = fs::metadata(&snapshot.path)
        .map_err(|_| "Read snapshot size")?
        .len();
    let mut result = BackupFile::new(directory, "encrypted")?;
    // The file was created privately above; reopen without truncating a path
    // supplied by another user. The directory belongs to the service account.
    let mut writer = OpenOptions::new()
        .write(true)
        .open(&result.path)
        .map_err(|_| "Open private backup output")?;
    encrypt(
        &store.vault,
        &mut File::open(&snapshot.path).map_err(|_| "Read database snapshot")?,
        &mut writer,
        length,
    )?;
    writer.sync_all().map_err(|_| "Save encrypted backup")?;
    result.size = fs::metadata(&result.path)
        .map_err(|_| "Read backup size")?
        .len();
    Ok(result)
}
struct Staging {
    path: PathBuf,
    keep: bool,
}
impl Drop for Staging {
    fn drop(&mut self) {
        if !self.keep {
            let _ = fs::remove_dir_all(&self.path);
        }
    }
}
fn private_directory(path: &Path) -> Result<(), String> {
    let mut builder = fs::DirBuilder::new();
    #[cfg(unix)]
    {
        use std::os::unix::fs::DirBuilderExt;
        builder.mode(0o700);
    }
    builder
        .create(path)
        .map_err(|_| "A new private restore directory is required".into())
}
pub fn restore(source: &Path, destination: &Path, key: &Path) -> Result<(), String> {
    if destination.exists() {
        return Err("Restore requires a nonexistent destination directory; existing content is never replaced".into());
    }
    let vault = crypto::Vault::load(key)?;
    let parent = destination
        .parent()
        .filter(|p| !p.as_os_str().is_empty())
        .unwrap_or(Path::new("."));
    fs::create_dir_all(parent).map_err(|_| "Create restore parent directory")?;
    let stage = Staging {
        path: parent.join(format!(".field-notes-restore-{}", crate::store::id())),
        keep: false,
    };
    private_directory(&stage.path)?;
    let mut reader = File::open(source).map_err(|_| "Read backup file")?;
    let mut first = [0u8; 1];
    reader
        .read_exact(&mut first)
        .map_err(|_| "Backup is empty")?;
    let mut reader = File::open(source).map_err(|_| "Read backup file")?;
    let mut file = private_file(&stage.path.join("field-notes.sqlite3"))?;
    if first[0] == 1 {
        // Legacy archives used one authenticated record. Preserve compatibility
        // with those files; new archives use bounded chunks throughout.
        let mut bytes = Vec::new();
        reader
            .read_to_end(&mut bytes)
            .map_err(|_| "Read legacy backup")?;
        let plain = Zeroizing::new(vault.open("backup:v1", &bytes)?);
        if !plain.starts_with(b"SQLite format 3\0") {
            return Err("Invalid database backup".into());
        }
        file.write_all(&plain)
            .map_err(|_| "Write restored database")?;
    } else {
        decrypt(&vault, &mut reader, &mut file)?;
    }
    file.sync_all().map_err(|_| "Save restored database")?;
    drop(file);
    let store = Store::open(&stage.path, key)?;
    let integrity: String = store
        .db
        .query_row("PRAGMA quick_check", [], |r| r.get(0))
        .map_err(|_| "Restored database integrity could not be checked")?;
    if integrity != "ok" {
        return Err("Backup contains an invalid database".into());
    }

    store.db.execute_batch("BEGIN IMMEDIATE; DELETE FROM sessions; DELETE FROM operations; DELETE FROM enrollment_invites; UPDATE devices SET state='revoking';").map_err(|_|"Prepare restored identity state")?;
    store
        .db
        .execute("UPDATE meta SET epoch=? WHERE id=1", [crate::store::id()])
        .map_err(|_| "Create restored server epoch")?;
    store
        .db
        .execute_batch("COMMIT; PRAGMA wal_checkpoint(TRUNCATE);")
        .map_err(|_| "Commit restored database")?;
    drop(store);
    // Atomically reserve the destination before publishing a verified database.
    // Another restore cannot overwrite an installation that won this race.
    private_directory(destination)?;
    let mut reservation = Staging {
        path: destination.to_owned(),
        keep: false,
    };
    fs::rename(&stage.path, destination).map_err(|_| "Publish restored database")?;
    reservation.keep = true;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    fn fixture() -> (Staging, crypto::Vault) {
        let p =
            std::env::temp_dir().join(format!("field-notes-backup-test-{}", crate::store::id()));
        private_directory(&p).unwrap();
        crypto::Vault::create(&p.join("key")).unwrap();
        let v = crypto::Vault::load(&p.join("key")).unwrap();
        (
            Staging {
                path: p,
                keep: false,
            },
            v,
        )
    }
    #[test]
    fn chunks_authenticate_lengths_order_endings_and_content() {
        let (_dir, v) = fixture();
        let mut plain = b"SQLite format 3\0".to_vec();
        plain.resize(CHUNK * 2 + 97, 42);
        let mut sealed = Vec::new();
        encrypt(&v, &mut plain.as_slice(), &mut sealed, plain.len() as u64).unwrap();
        let mut restored = Vec::new();
        decrypt(&v, &mut sealed.as_slice(), &mut restored).unwrap();
        assert_eq!(restored, plain);
        for offset in [
            MAGIC.len(),
            MAGIC.len() + 16,
            MAGIC.len() + 24 + 31,
            sealed.len() - 1,
        ] {
            let mut changed = sealed.clone();
            changed[offset] ^= 1;
            assert!(decrypt(&v, &mut changed.as_slice(), &mut Vec::new()).is_err());
        }
        for length in [0, MAGIC.len(), sealed.len() - 1] {
            assert!(decrypt(&v, &mut &sealed[..length], &mut Vec::new()).is_err());
        }
        let mut extra = sealed.clone();
        extra.push(0);
        assert!(decrypt(&v, &mut extra.as_slice(), &mut Vec::new()).is_err());
        let start = MAGIC.len() + 24;
        let mut reordered = sealed.clone();
        let first = sealed[start..start + CHUNK + 29].to_vec();
        reordered.copy_within(start + CHUNK + 29..start + 2 * (CHUNK + 29), start);
        reordered[start + CHUNK + 29..start + 2 * (CHUNK + 29)].copy_from_slice(&first);
        assert!(decrypt(&v, &mut reordered.as_slice(), &mut Vec::new()).is_err());
    }
    #[test]
    fn legacy_archives_restore_with_a_fresh_epoch_and_private_destination() {
        let (dir, v) = fixture();
        let live = dir.path.join("live");
        let mut store = Store::open(&live, &dir.path.join("key")).unwrap();
        store
            .initialize("owner", "legacy-backup-test-password")
            .unwrap();
        let old = store.meta().unwrap().0;
        let serialized = store.db.serialize("main").unwrap();
        let legacy = v.seal("backup:v1", &serialized).unwrap();
        let file = dir.path.join("legacy.backup");
        fs::write(&file, legacy).unwrap();
        let target = dir.path.join("restored");
        restore(&file, &target, &dir.path.join("key")).unwrap();
        let restored = Store::open(&target, &dir.path.join("key")).unwrap();
        assert_ne!(restored.meta().unwrap().0, old);
        let name: String = restored
            .db
            .query_row("SELECT name FROM users", [], |r| r.get(0))
            .unwrap();
        assert_eq!(name, "owner");
        assert!(restore(&file, &target, &dir.path.join("key")).is_err());
        assert_eq!(
            restored
                .db
                .query_row("SELECT name FROM users", [], |r| r.get::<_, String>(0))
                .unwrap(),
            "owner"
        );
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            assert_eq!(
                fs::metadata(&target).unwrap().permissions().mode() & 0o777,
                0o700
            );
            assert_eq!(
                fs::metadata(target.join("field-notes.sqlite3"))
                    .unwrap()
                    .permissions()
                    .mode()
                    & 0o777,
                0o600
            );
        }
    }
    #[test]
    fn wrong_keys_and_failed_restores_never_publish_a_destination() {
        let (dir, v) = fixture();
        let mut sealed = Vec::new();
        let plain = b"SQLite format 3\0invalid";
        encrypt(&v, &mut plain.as_slice(), &mut sealed, plain.len() as u64).unwrap();
        let input = dir.path.join("bad.backup");
        fs::write(&input, &sealed).unwrap();
        let target = dir.path.join("restored");
        assert!(restore(&input, &target, &dir.path.join("key")).is_err());
        assert!(!target.exists());
        let (_other, wrong) = fixture();
        assert!(decrypt(&wrong, &mut sealed.as_slice(), &mut Vec::new()).is_err());
    }
}
