//! Direct TLS configuration. A failed rotation never replaces a valid live pair.
use std::{fs::File, io::BufReader, path::PathBuf, sync::Arc};

fn read_pair(cert: &PathBuf, key: &PathBuf) -> Result<rustls::ServerConfig, String> {
    let chain = rustls_pemfile::certs(&mut BufReader::new(
        File::open(cert).map_err(|e| e.to_string())?,
    ))
    .collect::<Result<Vec<_>, _>>()
    .map_err(|e| e.to_string())?;
    let private = rustls_pemfile::private_key(&mut BufReader::new(
        File::open(key).map_err(|e| e.to_string())?,
    ))
    .map_err(|e| e.to_string())?
    .ok_or("No TLS private key found")?;
    let mut tls = rustls::ServerConfig::builder_with_provider(Arc::new(
        rustls::crypto::aws_lc_rs::default_provider(),
    ))
    .with_protocol_versions(&[&rustls::version::TLS13])
    .map_err(|e| e.to_string())?
    .with_no_client_auth()
    .with_single_cert(chain, private)
    .map_err(|e| e.to_string())?;
    tls.alpn_protocols = vec![b"h2".to_vec(), b"http/1.1".to_vec()];
    Ok(tls)
}
pub fn start(cert: PathBuf, key: PathBuf) -> Result<axum_server::tls_rustls::RustlsConfig, String> {
    let config =
        axum_server::tls_rustls::RustlsConfig::from_config(Arc::new(read_pair(&cert, &key)?));
    let running = config.clone();
    tokio::spawn(async move {
        let mut interval = tokio::time::interval(std::time::Duration::from_secs(60));
        loop {
            interval.tick().await;
            match read_pair(&cert,&key) {
                Ok(next) => running.reload_from_config(Arc::new(next)),
                Err(_) => eprintln!("Field Notes: certificate renewal is invalid or incomplete; retaining the current TLS pair"),
            }
        }
    });
    Ok(config)
}
