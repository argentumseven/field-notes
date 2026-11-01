mod crypto;
mod downloads;
mod http;
mod store;
mod tls;
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
/// Docker publishes the management listener from a private namespace. The
/// wildcard is opt-in, container-only, and retains actual-source CIDR checks.
fn validate_container_listener(
    admin: SocketAddr,
    device: SocketAddr,
    origin: &str,
    network: &store::NetworkConfig,
    allowed: &[ipnet::IpNet],
    in_container: bool,
) -> Result<(), String> {
    if !in_container || !admin.is_ipv4() || !admin.ip().is_unspecified() {
        return Err("Container networking requires Docker and an IPv4 namespace listener".into());
    }
    network.validate().map_err(|error| error.1)?;
    let uri: axum::http::Uri = network
        .bootstrap_url
        .parse()
        .map_err(|_| "Invalid bootstrap URL")?;
    if network.bootstrap_url != format!("{origin}/api/enrollment")
        || uri.port_u16() != Some(admin.port())
        || network.api != format!("https://{device}")
    {
        return Err(
            "Container ingress, public enrollment origin and private API must agree".into(),
        );
    }
    let overlay: ipnet::Ipv4Net = network
        .overlay
        .parse()
        .map_err(|_| "Invalid tunnel network")?;
    let ranges: Vec<ipnet::Ipv4Net> = ["10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"]
        .into_iter()
        .map(|value| value.parse().unwrap())
        .collect();
    if allowed.is_empty()
        || allowed.iter().any(|net| match net {
            ipnet::IpNet::V4(net) => {
                !ranges
                    .iter()
                    .any(|range| range.contains(&net.network()) && range.contains(&net.broadcast()))
                    || net.contains(&overlay.network())
                    || overlay.contains(&net.network())
            }
            _ => true,
        })
    {
        return Err(
            "Container management sources must be private and separate from phone peers".into(),
        );
    }
    Ok(())
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
        println!("Field Notes {}\nCommands: init, serve, backup, restore, stats\nOptions: --data DIR --key-file FILE\ninit: --name NAME (password from stdin)\nserve: --admin-bind LAN_IP:9443 --admin-allow LAN_CIDR --device-bind WG_IP:8443 --origin https://LAN_IP:9443 --tls-cert FILE --tls-key FILE --network-config FILE --peer-status FILE\nLocal HTTP development only: --dev\nbackup/restore: --file FILE (restore requires a NEW data directory)", env!("CARGO_PKG_VERSION"));
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
            .execute_batch("BEGIN IMMEDIATE; DELETE FROM sessions; DELETE FROM operations; DELETE FROM enrollment_invites; UPDATE devices SET state='revoking';")
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
    let mut store = Store::open(&data, &key)?;
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
    let dev = args.iter().any(|a| a == "--dev");
    let admin: SocketAddr = arg(
        &args,
        "--admin-bind",
        if dev {
            "127.0.0.1:8787"
        } else {
            "127.0.0.1:9443"
        },
    )
    .parse()
    .map_err(|_| "Invalid admin address")?;
    let device: SocketAddr = arg(
        &args,
        "--device-bind",
        if dev {
            "127.0.0.1:8788"
        } else {
            "10.82.0.1:8443"
        },
    )
    .parse()
    .map_err(|_| "Invalid device address")?;
    let container_network = args.iter().any(|value| value == "--container-network");
    if container_network && dev {
        return Err("Container listeners require TLS".into());
    }
    if dev && (!admin.ip().is_loopback() || !device.ip().is_loopback()) {
        return Err("Development HTTP is loopback-only".into());
    }
    if !dev
        && [admin.ip(), device.ip()]
            .iter()
            .enumerate()
            .any(|(index, ip)| match ip {
                std::net::IpAddr::V4(ip) => {
                    !(ip.is_private()
                        || ip.is_loopback()
                        || container_network && index == 0 && ip.is_unspecified())
                }
                std::net::IpAddr::V6(ip) => !ip.is_unique_local() && !ip.is_loopback(),
            })
    {
        return Err("HTTPS must bind to explicit private LAN/tunnel addresses".into());
    }
    let admin_allow: Vec<ipnet::IpNet> = arg(&args, "--admin-allow", "")
        .split(',')
        .filter(|s| !s.is_empty())
        .map(|s| {
            s.parse()
                .map_err(|_| "Invalid management source prefix".to_string())
        })
        .collect::<Result<_, _>>()?;
    if !dev && admin_allow.is_empty() {
        return Err("Specify --admin-allow with the management LAN source prefix".into());
    }
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
    let network_file = arg(&args, "--network-config", "");
    if !network_file.is_empty() {
        let network: store::NetworkConfig =
            serde_json::from_slice(&std::fs::read(&network_file).map_err(|e| e.to_string())?)
                .map_err(|e| e.to_string())?;
        network.validate().map_err(|e| e.1)?;
        if container_network {
            validate_container_listener(
                admin,
                device,
                &origin,
                &network,
                &admin_allow,
                std::path::Path::new("/.dockerenv").is_file(),
            )?;
        }
        if !dev
            && !container_network
            && (network.bootstrap_url != format!("https://{admin}/api/enrollment")
                || network.api != format!("https://{device}"))
        {
            return Err(
                "Enrollment endpoints must match the actual LAN and tunnel HTTPS listeners".into(),
            );
        }
        store.network = Some(network);
    }
    if container_network && network_file.is_empty() {
        return Err("Container listeners require enrolled network configuration".into());
    }
    store.development = dev;
    let status = arg(&args, "--peer-status", "");
    if !status.is_empty() {
        store.peer_status = Some(PathBuf::from(status));
    }
    let bind_devices = store.network.is_some() || !dev;
    let app = http::App {
        downloads: match arg(&args, "--downloads", "") {
            value if value.is_empty() => None,
            value => match downloads::Downloads::load(PathBuf::from(value)) {
                Ok(downloads) => Some(Arc::new(downloads)),
                Err(reason) => {
                    eprintln!("Field Notes: Android downloads disabled: {reason}");
                    None
                }
            },
        },
        store: Arc::new(Mutex::new(store)),
        origin,
        dev,
        device: false,
        gate: Arc::new(tokio::sync::Semaphore::new(16)),
        requests: Arc::new(tokio::sync::Semaphore::new(24)),
        admin_allow,
        bind_devices,
    };
    let peer_store = app.store.clone();
    tokio::spawn(async move {
        let mut interval = tokio::time::interval(std::time::Duration::from_secs(2));
        loop {
            interval.tick().await;
            let store = peer_store.clone();
            let _=tokio::task::spawn_blocking(move|| {
                if let Ok(mut s)=store.lock() {if s.reconcile_peers().is_err(){eprintln!("Field Notes: peer reconciliation is pending; no new device activation was acknowledged");}}
            }).await;
        }
    });
    if !dev {
        let cert = arg(&args, "--tls-cert", "");
        let key = arg(&args, "--tls-key", "");
        if cert.is_empty() || key.is_empty() {
            return Err(
                "Direct HTTPS requires --tls-cert and --tls-key; no plaintext fallback exists"
                    .into(),
            );
        }
        let config = tls::start(PathBuf::from(cert), PathBuf::from(key))?;
        let mut device_app = app.clone();
        device_app.device = true;
        println!("Field Notes: direct LAN HTTPS {admin}; private device HTTPS {device}. TLS 1.3; no HTTP or reverse proxy.");
        tokio::try_join!(
            axum_server::bind_rustls(admin, config.clone())
                .serve(http::router(app).into_make_service_with_connect_info::<SocketAddr>()),
            axum_server::bind_rustls(device, config).serve(
                http::router(device_app).into_make_service_with_connect_info::<SocketAddr>()
            ),
        )
        .map_err(|e| e.to_string())?;
        return Ok(());
    }
    let admin_listener = tokio::net::TcpListener::bind(admin)
        .await
        .map_err(|e| e.to_string())?;
    let device_listener = tokio::net::TcpListener::bind(device)
        .await
        .map_err(|e| e.to_string())?;
    let mut device_app = app.clone();
    device_app.device = true;
    println!(
        "Field Notes {}: disposable loopback HTTP admin {admin}; device {device}.",
        env!("CARGO_PKG_VERSION")
    );
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

#[cfg(test)]
mod container_listener_tests {
    use super::*;
    fn config() -> store::NetworkConfig {
        use base64::Engine;
        store::NetworkConfig {
            bootstrap_url: "https://192.168.1.10:9443/api/enrollment".into(),
            endpoint: "home.example:49272".into(),
            lan_endpoint: None,
            server_key: base64::engine::general_purpose::STANDARD.encode([3; 32]),
            api: "https://10.82.0.1:8443".into(),
            ca_pem: include_str!("../../../tests/fixtures/enrollment-ca.crt").into(),
            overlay: "10.82.0.0/24".into(),
        }
    }
    #[test]
    fn container_listener_requires_matching_origin_tls_network_and_private_sources() {
        let a = "0.0.0.0:9443".parse().unwrap();
        let d = "10.82.0.1:8443".parse().unwrap();
        let origin = "https://192.168.1.10:9443";
        let sources = vec!["192.168.1.0/24".parse().unwrap()];
        assert!(validate_container_listener(a, d, origin, &config(), &sources, true).is_ok());
        assert!(validate_container_listener(a, d, origin, &config(), &sources, false).is_err());
        assert!(
            validate_container_listener(a, d, "https://other:9443", &config(), &sources, true)
                .is_err()
        );
        assert!(validate_container_listener(
            a,
            d,
            origin,
            &config(),
            &["0.0.0.0/0".parse().unwrap()],
            true
        )
        .is_err());
        assert!(validate_container_listener(
            a,
            d,
            origin,
            &config(),
            &["10.0.0.0/8".parse().unwrap()],
            true
        )
        .is_err());
        assert!(validate_container_listener(
            a,
            d,
            origin,
            &config(),
            &["10.0.0.0/7".parse().unwrap()],
            true
        )
        .is_err());
    }
}
