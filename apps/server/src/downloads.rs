//! Fixed public APK assets on the management LAN; never arbitrary file paths.
use crate::store::Fault;
use axum::{body::Body, http::HeaderValue, response::Response};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::{io::Read, path::PathBuf, sync::Arc};
use tokio::io::{AsyncReadExt, AsyncSeekExt};
pub const CHUNK_SIZE: u64 = 256 * 1024;
#[derive(Clone)]
pub struct Downloads {
    root: PathBuf,
    pub info: Value,
    size: u64,
    slots: Arc<tokio::sync::Semaphore>,
}
impl Downloads {
    pub fn load(root: PathBuf) -> Result<Self, String> {
        let info: Value = serde_json::from_slice(
            &std::fs::read(root.join("android.json")).map_err(|_| "Missing APK metadata")?,
        )
        .map_err(|_| "Invalid APK metadata")?;
        if info["version"] != env!("CARGO_PKG_VERSION")
            || info["architecture"] != "arm64-v8a"
            || !matches!(info["signing"].as_str(), Some("development" | "release"))
        {
            return Err("APK metadata must match the server release".into());
        }
        let mut file =
            std::fs::File::open(root.join("field-notes.apk")).map_err(|_| "Missing bundled APK")?;
        let size = file
            .metadata()
            .map_err(|_| "APK metadata unavailable")?
            .len();
        if size == 0 || size > 150 * 1024 * 1024 {
            return Err("APK exceeds download bound".into());
        }
        let mut hash = Sha256::new();
        let mut buffer = [0; 65536];
        loop {
            let n = file.read(&mut buffer).map_err(|_| "APK read failed")?;
            if n == 0 {
                break;
            }
            hash.update(&buffer[..n]);
        }
        if info["sha256"] != hex::encode(hash.finalize()) {
            return Err("Bundled APK checksum mismatch".into());
        }
        Ok(Self {
            root,
            info,
            size,
            slots: Arc::new(tokio::sync::Semaphore::new(4)),
        })
    }
    pub async fn file(&self, name: &str) -> Result<Response, Fault> {
        let permit = self
            .slots
            .clone()
            .try_acquire_owned()
            .map_err(|_| Fault(429, "Too many downloads; retry shortly".into()))?;
        let mime = match name {
            "field-notes.apk" => "application/vnd.android.package-archive",
            "field-notes.apk.sha256" | "field-notes.apk.signer.txt" => "text/plain; charset=utf-8",
            _ => return Err(Fault(404, "Download not found".into())),
        };
        let file = tokio::fs::File::open(self.root.join(name))
            .await
            .map_err(|_| Fault(404, "Download unavailable".into()))?;
        let length = file
            .metadata()
            .await
            .map_err(|_| Fault(503, "Download unavailable".into()))?
            .len();
        let stream =
            futures_util::stream::try_unfold((file, permit), |(mut file, permit)| async move {
                let mut bytes = vec![0; 65536];
                let n = file.read(&mut bytes).await?;
                if n == 0 {
                    Ok::<_, std::io::Error>(None)
                } else {
                    bytes.truncate(n);
                    Ok(Some((bytes, (file, permit))))
                }
            });
        let mut response = Response::new(Body::from_stream(stream));
        response
            .headers_mut()
            .insert("content-type", HeaderValue::from_static(mime));
        response.headers_mut().insert(
            "content-length",
            HeaderValue::from_str(&length.to_string())
                .map_err(|_| Fault(500, "Download failed".into()))?,
        );
        response.headers_mut().insert(
            "content-disposition",
            HeaderValue::from_str(&format!("attachment; filename=\"{name}\""))
                .map_err(|_| Fault(500, "Download failed".into()))?,
        );
        Ok(response)
    }
    pub fn metadata(&self) -> Value {
        json!({"available":true,"artifact":self.info})
    }
    pub fn update_metadata(&self) -> Value {
        json!({"available":true,"artifact":self.info,"package":"org.fieldnotes.app","size":self.size,"chunk_size":CHUNK_SIZE,"protocol":1})
    }
    pub async fn chunk(&self, digest: &str, index: u64) -> Result<Response, Fault> {
        if self.info["sha256"].as_str() != Some(digest) {
            return Err(Fault(
                409,
                "Server APK changed. Check for updates again".into(),
            ));
        }
        let offset = index
            .checked_mul(CHUNK_SIZE)
            .filter(|v| *v < self.size)
            .ok_or(Fault(404, "Update chunk not found".into()))?;
        let permit = self
            .slots
            .clone()
            .try_acquire_owned()
            .map_err(|_| Fault(429, "Too many downloads; retry shortly".into()))?;
        let mut file = tokio::fs::File::open(self.root.join("field-notes.apk"))
            .await
            .map_err(|_| Fault(503, "Update unavailable".into()))?;
        file.seek(std::io::SeekFrom::Start(offset))
            .await
            .map_err(|_| Fault(503, "Update unavailable".into()))?;
        let length = CHUNK_SIZE.min(self.size - offset);
        let stream = futures_util::stream::try_unfold(
            (file.take(length), permit),
            |(mut file, permit)| async move {
                let mut bytes = vec![0; 65536];
                let n = file.read(&mut bytes).await?;
                if n == 0 {
                    Ok::<_, std::io::Error>(None)
                } else {
                    bytes.truncate(n);
                    Ok(Some((bytes, (file, permit))))
                }
            },
        );
        let mut response = Response::new(Body::from_stream(stream));
        response.headers_mut().insert(
            "content-type",
            HeaderValue::from_static("application/octet-stream"),
        );
        response.headers_mut().insert(
            "content-length",
            HeaderValue::from_str(&length.to_string())
                .map_err(|_| Fault(500, "Update unavailable".into()))?,
        );
        Ok(response)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    struct Fixture(PathBuf);
    impl Fixture {
        fn new() -> Self {
            let root = std::env::temp_dir()
                .join(format!("field-notes-downloads-{}", crate::crypto::token()));
            std::fs::create_dir(&root).unwrap();
            let bytes = b"PK public binary fixture";
            std::fs::write(root.join("field-notes.apk"), bytes).unwrap();
            std::fs::write(root.join("android.json"),json!({"version":env!("CARGO_PKG_VERSION"),"architecture":"arm64-v8a","signing":"development","sha256":crate::crypto::digest(bytes)}).to_string()).unwrap();
            Self(root)
        }
    }
    impl Drop for Fixture {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }
    #[test]
    fn mismatched_bytes_are_never_offered() {
        let f = Fixture::new();
        assert!(Downloads::load(f.0.clone()).is_ok());
        std::fs::write(f.0.join("field-notes.apk"), b"altered").unwrap();
        assert!(Downloads::load(f.0.clone()).is_err());
    }
    #[tokio::test]
    async fn download_permits_live_until_body_drop() {
        let f = Fixture::new();
        let d = Downloads::load(f.0.clone()).unwrap();
        let mut responses = Vec::new();
        for _ in 0..4 {
            responses.push(d.file("field-notes.apk").await.unwrap());
        }
        assert!(matches!(
            d.file("field-notes.apk").await,
            Err(Fault(429, _))
        ));
        responses.pop();
        assert!(d.file("field-notes.apk").await.is_ok());
    }
}
