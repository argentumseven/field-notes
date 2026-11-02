//! Direct TLS configuration. A failed rotation never replaces a valid live pair.
use axum_server::accept::Accept;
use std::{fs::File, io::BufReader, path::PathBuf, sync::Arc};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::TcpStream,
};

/// Plain HTTP on the management TLS port gets a readable error, never an app,
/// login endpoint or session cookie. All other traffic still requires rustls.
#[derive(Clone)]
pub struct HttpsOnly {
    pub origin: String,
    pub allowed: Vec<ipnet::IpNet>,
}
impl<S: Send + 'static> Accept<TcpStream, S> for HttpsOnly {
    type Stream = TcpStream;
    type Service = S;
    type Future = std::pin::Pin<
        Box<dyn std::future::Future<Output = std::io::Result<(TcpStream, S)>> + Send>,
    >;
    fn accept(&self, mut stream: TcpStream, service: S) -> Self::Future {
        let guard = self.clone();
        Box::pin(async move {
            let mut first = [0u8; 1];
            let count =
                tokio::time::timeout(std::time::Duration::from_secs(5), stream.peek(&mut first))
                    .await
                    .map_err(|_| {
                        std::io::Error::new(
                            std::io::ErrorKind::TimedOut,
                            "Protocol detection timed out",
                        )
                    })??;
            if count == 1 && first[0].is_ascii_uppercase() {
                if !guard.allowed.iter().any(|network| {
                    network.contains(
                        &stream
                            .peer_addr()
                            .map(|p| p.ip())
                            .unwrap_or(std::net::Ipv4Addr::UNSPECIFIED.into()),
                    )
                }) {
                    return Err(std::io::Error::new(
                        std::io::ErrorKind::PermissionDenied,
                        "Management source denied",
                    ));
                }
                let body = format!("HTTPS is required on this port. Open {}/\n", guard.origin);
                let response=format!("HTTP/1.1 400 Bad Request\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: {}\r\nCache-Control: no-store\r\nConnection: close\r\nX-Content-Type-Options: nosniff\r\n\r\n{}",body.len(),body);
                let _ = tokio::time::timeout(std::time::Duration::from_secs(2), async {
                    stream.write_all(response.as_bytes()).await?;
                    stream.shutdown().await?;
                    // Drain bounded plaintext bytes so dropping an unread
                    // request cannot reset the TCP connection over the error.
                    let mut discarded = [0u8; 1024];
                    for _ in 0..64 {
                        if stream.read(&mut discarded).await? == 0 {
                            break;
                        }
                    }
                    Ok::<(), std::io::Error>(())
                })
                .await;
                return Err(std::io::Error::new(
                    std::io::ErrorKind::InvalidData,
                    "Plain HTTP refused on HTTPS port",
                ));
            }
            Ok((stream, service))
        })
    }
}

fn read_pair(cert: &PathBuf, key: &PathBuf) -> Result<rustls::ServerConfig, String> {
    let chain =
        rustls_pemfile::certs(&mut BufReader::new(File::open(cert).map_err(|e| {
            format!("Cannot read TLS certificate {}: {e}", cert.display())
        })?))
        .collect::<Result<Vec<_>, _>>()
        .map_err(|e| e.to_string())?;
    let private = rustls_pemfile::private_key(&mut BufReader::new(
        File::open(key)
            .map_err(|e| format!("Cannot read TLS private key {}: {e}", key.display()))?,
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
