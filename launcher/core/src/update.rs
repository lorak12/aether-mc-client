//! Updates from the backend's signed manifest (`GET /v1/manifest/<channel>` -> `{ payload, signature }`).
//! `payload` is a JSON string signed with Ed25519; the launcher only trusts it when the signature verifies against
//! `manifest_public_key`. Downloads are checked against the SHA-256 in the manifest.
//!
//! Payload:
//! ```json
//! { "launcher": { "version": "0.2.0", "notes": "...", "downloads": { "windows-x86_64": { "url": "...", "sha256": "..." } } },
//!   "runtime": { "1.21.11": { "version": "0.2.0", "url": "...", "sha256": "..." } } }
//! ```
use crate::{Error, Result};
use ed25519_dalek::{Signature, Verifier, VerifyingKey};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::path::{Path, PathBuf};

#[derive(Debug, Clone, Deserialize)]
pub struct Signed {
    pub payload: String,
    pub signature: Option<String>,
}

#[derive(Debug, Clone, Deserialize, Default)]
pub struct Manifest {
    #[serde(default)]
    pub launcher: Option<LauncherRelease>,
    #[serde(default)]
    pub runtime: HashMap<String, Download>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct LauncherRelease {
    pub version: String,
    #[serde(default)]
    pub notes: String,
    #[serde(default)]
    pub downloads: HashMap<String, Download>,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct Download {
    #[serde(default)]
    pub version: String,
    pub url: String,
    pub sha256: String,
}

/// What the UI shows after a check.
#[derive(Debug, Clone, Serialize)]
pub struct UpdateInfo {
    pub current: String,
    pub latest: String,
    pub available: bool,
    pub notes: String,
    /// False when there's a newer version but no build for this platform.
    pub installable: bool,
}

pub fn platform_key() -> String {
    format!("{}-{}", std::env::consts::OS, std::env::consts::ARCH)
}

/// Compare dotted versions numerically ("0.10.0" > "0.9.3"); a pre-release suffix sorts before the release.
pub fn newer(a: &str, b: &str) -> bool {
    let parse = |v: &str| -> (Vec<u64>, bool) {
        let (core, pre) = v.trim_start_matches('v').split_once('-').map(|(c, _)| (c, true)).unwrap_or((v.trim_start_matches('v'), false));
        (core.split('.').map(|p| p.parse().unwrap_or(0)).collect(), pre)
    };
    let ((av, apre), (bv, bpre)) = (parse(a), parse(b));
    for i in 0..av.len().max(bv.len()) {
        let (x, y) = (av.get(i).copied().unwrap_or(0), bv.get(i).copied().unwrap_or(0));
        if x != y {
            return x > y;
        }
    }
    !apre && bpre
}

/// Verify `signed` with the hex Ed25519 public key and parse the payload.
pub fn verify(signed: &Signed, public_key_hex: &str) -> Result<Manifest> {
    let key: [u8; 32] = hex::decode(public_key_hex.trim())
        .ok()
        .and_then(|b| b.try_into().ok())
        .ok_or_else(|| Error::Other("manifest_public_key must be 32 bytes of hex".into()))?;
    let key = VerifyingKey::from_bytes(&key).map_err(|_| Error::Other("manifest_public_key isn't a valid Ed25519 key".into()))?;
    let sig: [u8; 64] = signed
        .signature
        .as_deref()
        .and_then(|s| hex::decode(s).ok())
        .and_then(|b| b.try_into().ok())
        .ok_or_else(|| Error::Other("The update manifest isn't signed".into()))?;
    key.verify(signed.payload.as_bytes(), &Signature::from_bytes(&sig))
        .map_err(|_| Error::Other("The update manifest's signature is wrong; ignoring it".into()))?;
    Ok(serde_json::from_str(&signed.payload)?)
}

pub async fn fetch(http: &reqwest::Client, backend: &str, channel: &str, public_key_hex: &str) -> Result<Manifest> {
    let r = http.get(format!("{}/v1/manifest/{channel}", backend.trim_end_matches('/'))).send().await?;
    if r.status() == reqwest::StatusCode::NOT_FOUND {
        return Ok(Manifest::default());
    }
    let signed: Signed = r.error_for_status()?.json().await?;
    verify(&signed, public_key_hex)
}

pub fn launcher_info(m: &Manifest, current: &str) -> UpdateInfo {
    match &m.launcher {
        Some(l) if newer(&l.version, current) => UpdateInfo {
            current: current.into(),
            latest: l.version.clone(),
            available: true,
            notes: l.notes.clone(),
            installable: l.downloads.contains_key(&platform_key()),
        },
        _ => UpdateInfo { current: current.into(), latest: current.into(), available: false, notes: String::new(), installable: false },
    }
}

/// Download `d` to `dest`, checking SHA-256.
pub async fn download(http: &reqwest::Client, d: &Download, dest: &Path) -> Result<()> {
    if !d.url.starts_with("https://") {
        return Err(Error::Other("update downloads must use https".into()));
    }
    let bytes = http.get(&d.url).send().await?.error_for_status()?.bytes().await?;
    let actual = hex::encode(Sha256::digest(&bytes));
    if !actual.eq_ignore_ascii_case(d.sha256.trim()) {
        return Err(Error::Checksum { path: dest.display().to_string(), expected: d.sha256.clone(), actual });
    }
    if let Some(p) = dest.parent() {
        std::fs::create_dir_all(p)?;
    }
    let part = dest.with_extension("part");
    std::fs::write(&part, &bytes)?;
    std::fs::rename(part, dest)?;
    Ok(())
}

/// Fetch a newer Kite client jar for `mc` into `<data root>/runtime/` (where `runtime::find_jar` looks).
/// Returns the new jar, or `None` if the manifest has nothing newer than what's there.
pub async fn update_runtime(http: &reqwest::Client, m: &Manifest, data_root: &Path, mc: &str) -> Result<Option<PathBuf>> {
    let Some(d) = m.runtime.get(mc) else { return Ok(None) };
    let dir = data_root.join("runtime");
    let dest = dir.join(format!("kite-{mc}-{}.jar", d.version));
    if dest.exists() {
        return Ok(None);
    }
    download(http, d, &dest).await?;
    // Older downloaded builds for this version go, so the newest-by-mtime pick is unambiguous.
    if let Ok(rd) = std::fs::read_dir(&dir) {
        for e in rd.flatten() {
            let n = e.file_name().to_string_lossy().to_string();
            if n.starts_with(&format!("kite-{mc}-")) && e.path() != dest {
                let _ = std::fs::remove_file(e.path());
            }
        }
    }
    Ok(Some(dest))
}

#[cfg(test)]
mod tests {
    use super::*;
    use ed25519_dalek::{Signer, SigningKey};

    #[test]
    fn versions() {
        assert!(newer("0.10.0", "0.9.3"));
        assert!(!newer("0.1.0", "0.1.0"));
        assert!(newer("0.2.0", "0.2.0-beta.1"));
        assert!(!newer("0.2.0-beta.1", "0.2.0"));
        assert!(newer("v1.0", "0.99"));
    }

    #[test]
    fn signature_checked() {
        let sk = SigningKey::from_bytes(&[7u8; 32]);
        let pk = hex::encode(sk.verifying_key().to_bytes());
        let payload = r#"{"launcher":{"version":"9.9.9","notes":"n","downloads":{}}}"#.to_string();
        let sig = hex::encode(sk.sign(payload.as_bytes()).to_bytes());
        let m = verify(&Signed { payload: payload.clone(), signature: Some(sig.clone()) }, &pk).unwrap();
        let info = launcher_info(&m, "0.1.0");
        assert!(info.available && !info.installable);
        let tampered = Signed { payload: payload.replace("9.9.9", "9.9.8"), signature: Some(sig) };
        assert!(verify(&tampered, &pk).is_err());
        assert!(verify(&Signed { payload, signature: None }, &pk).is_err());
        assert!(verify(&Signed { payload: "{}".into(), signature: Some("00".into()) }, "zz").is_err());
    }
}
