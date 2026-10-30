mod crypto;
mod http;
mod store;
use std::{
    io::{self, Read, Write},
    net::SocketAddr,
    path::PathBuf,
    sync::{Arc, Mutex},
};
use store::Store;

fn arg(args: &[String], key: &str, default: &str) -> String {
    args.windows(2)
        .find(|w| w[0] == key)
        .map(|w| w[1].clone())
        .unwrap_or(default.into())
}
fn private_write(path: &std::path::Path, data: &[u8]) -> Result<(), String> {
    let mut options = std::fs::OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    let mut f = options.open(path).map_err(|e| e.to_string())?;
    f.write_all(data).map_err(|e| e.to_string())?;
    f.sync_all().map_err(|e| e.to_string())
}
#[tokio::main]
async fn main() {
    if let Err(e) = run().await {
        eprintln!("Field Notes: {e}");
        std::process::exit(1);
    }
}
async fn run() -> Result<(), String> {
    let args: Vec<String> = std::env::args().collect();
    let action = args.get(1).map(String::as_str).unwrap_or("help");
    if action == "help" {
        println!("Field Notes 0.1.0\nCommands: init, serve, backup, restore, stats\nOptions: --data DIR --key-file FILE\ninit: --name NAME (password from stdin)\nserve: --admin-bind 127.0.0.1:8787 --device-bind 127.0.0.1:8788 --origin https://notes.home\nLocal HTTP development only: --dev\nbackup/restore: --file FILE (restore requires a NEW data directory)");
        return Ok(());
    }
    let data = PathBuf::from(arg(&args, "--data", ".local/data"));
    let key = PathBuf::from(arg(&args, "--key-file", ".local/secrets/master.key"));
    if action == "init" {
        if data.join("field-notes.sqlite3").exists() {
            return Err("Existing database; initialization refused".into());
        }
        if !key.exists() {
            crypto::Vault::create(&key)?;
        }
        let mut password = zeroize::Zeroizing::new(String::new());
        io::stdin()
            .read_to_string(&mut password)
            .map_err(|e| e.to_string())?;
        let mut store = Store::open(&data, &key)?;
        store
            .initialize(
                &arg(&args, "--name", "owner"),
                password.trim_end_matches(['\r', '\n']),
            )
            .map_err(|e| e.1)?;
        println!("Initialized. Back up the master key separately; it is required for recovery.");
        return Ok(());
    }
    if !data.join("field-notes.sqlite3").exists() && action != "restore" {
        return Err("Run init first; missing installations are never silently created".into());
    }
    if action == "restore" {
        if data.exists() {
            return Err("Restore requires a nonexistent destination directory. Stop services and restore separately.".into());
        }
        let vault = crypto::Vault::load(&key)?;
        let bytes = std::fs::read(arg(&args, "--file", "")).map_err(|e| e.to_string())?;
        let plain = zeroize::Zeroizing::new(vault.open("backup:v1", &bytes)?);
        if !plain.starts_with(b"SQLite format 3\0") {
            return Err("Invalid backup".into());
        }
        std::fs::create_dir_all(&data).map_err(|e| e.to_string())?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            std::fs::set_permissions(&data, std::fs::Permissions::from_mode(0o700))
                .map_err(|e| e.to_string())?;
        }
        private_write(&data.join("field-notes.sqlite3"), &plain)?;
        let store = Store::open(&data, &key)?;
        store
            .db
            .execute_batch("BEGIN IMMEDIATE; DELETE FROM sessions; DELETE FROM operations;")
            .map_err(|e| e.to_string())?;
        store
            .db
            .execute("UPDATE meta SET epoch=? WHERE id=1", [store::id()])
            .map_err(|e| e.to_string())?;
        store
            .db
            .execute_batch("COMMIT")
            .map_err(|e| e.to_string())?;
        println!("Restored with a fresh server epoch. All sessions revoked; reconnect and reconcile client drafts.");
        return Ok(());
    }
    let store = Store::open(&data, &key)?;
    if action == "backup" {
        private_write(
            &PathBuf::from(arg(&args, "--file", "field-notes.backup")),
            &store.export_backup().map_err(|e| e.1)?,
        )?;
        println!("Encrypted backup created; keep the recovery key separately.");
        return Ok(());
    }
    if action == "stats" {
        println!(
            "{}",
            serde_json::to_string(&store.stats().map_err(|e| e.1)?).map_err(|e| e.to_string())?
        );
        return Ok(());
    }
    if action != "serve" {
        return Err("Unknown command; run help".into());
    }
    let service_lock = std::fs::OpenOptions::new()
        .read(true)
        .write(true)
        .create(true)
        .truncate(false)
        .open(data.join("server.lock"))
        .map_err(|e| e.to_string())?;
    service_lock.try_lock().map_err(|_| {
        "Another server is using this database; stop it before starting a second instance"
    })?;
    let admin: SocketAddr = arg(&args, "--admin-bind", "127.0.0.1:8787")
        .parse()
        .map_err(|_| "Invalid admin address")?;
    let device: SocketAddr = arg(&args, "--device-bind", "127.0.0.1:8788")
        .parse()
        .map_err(|_| "Invalid device address")?;
    // TLS proxies run on the same host. Never expose these plaintext listeners.
    if !admin.ip().is_loopback() || !device.ip().is_loopback() {
        return Err(
            "HTTP listeners must bind to loopback; expose only the configured TLS proxy".into(),
        );
    }
    let dev = args.iter().any(|a| a == "--dev");
    let origin = arg(
        &args,
        "--origin",
        if dev {
            "http://127.0.0.1:8787"
        } else {
            "https://notes.home"
        },
    );
    if (!dev && !origin.starts_with("https://")) || origin.ends_with('/') {
        return Err("Origin must be an exact HTTPS origin without trailing slash".into());
    }
    let app = http::App {
        store: Arc::new(Mutex::new(store)),
        origin,
        dev,
        device: false,
        gate: Arc::new(tokio::sync::Semaphore::new(16)),
        requests: Arc::new(tokio::sync::Semaphore::new(24)),
    };
    let admin_listener = tokio::net::TcpListener::bind(admin)
        .await
        .map_err(|e| e.to_string())?;
    let device_listener = tokio::net::TcpListener::bind(device)
        .await
        .map_err(|e| e.to_string())?;
    let mut device_app = app.clone();
    device_app.device = true;
    println!("Field Notes {}: LAN proxy backend {admin}; device proxy backend {device}. No public HTTP listeners.",env!("CARGO_PKG_VERSION"));
    let a = axum::serve(
        admin_listener,
        http::router(app).into_make_service_with_connect_info::<SocketAddr>(),
    );
    let d = axum::serve(
        device_listener,
        http::router(device_app).into_make_service_with_connect_info::<SocketAddr>(),
    );
    tokio::try_join!(async { a.await.map_err(|e| e.to_string()) }, async {
        d.await.map_err(|e| e.to_string())
    })?;
    Ok(())
}
