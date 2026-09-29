//! App-level configuration. All secrets/ids are optional so everything compiles and runs without them;
//! features that need a missing value return `Error::Other("... not configured")`.
//!
//! Sources, highest priority first: environment variables, `aether.config.json` in the data root.
//!   AETHER_AZURE_CLIENT_ID, AETHER_CURSEFORGE_API_KEY, AETHER_BACKEND_URL
use crate::{Error, Result};
use serde::{Deserialize, Serialize};
use std::path::Path;

pub const USER_AGENT: &str = concat!("aether-launcher/", env!("CARGO_PKG_VERSION"), " (contact: set-in-config)");

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct AppConfig {
    /// Azure AD application (client) id approved for Minecraft services.
    #[serde(default)]
    pub azure_client_id: Option<String>,
    /// CurseForge API key from console.curseforge.com.
    #[serde(default)]
    pub curseforge_api_key: Option<String>,
    /// Base URL of the Aether backend (cosmetics, badge, runtime manifest).
    #[serde(default)]
    pub backend_url: Option<String>,
    /// Ed25519 public key (hex) used to verify runtime update manifests.
    #[serde(default)]
    pub manifest_public_key: Option<String>,
}

impl AppConfig {
    pub fn load(root: &Path) -> AppConfig {
        let mut cfg: AppConfig = std::fs::read(root.join("aether.config.json"))
            .ok()
            .and_then(|b| serde_json::from_slice(&b).ok())
            .unwrap_or_default();
        // Empty strings (the placeholder file) count as "not set".
        for v in [&mut cfg.azure_client_id, &mut cfg.curseforge_api_key, &mut cfg.backend_url, &mut cfg.manifest_public_key] {
            if v.as_deref().map(|s| s.trim().is_empty()).unwrap_or(false) {
                *v = None;
            }
        }
        let env = |k: &str| std::env::var(k).ok().filter(|v| !v.trim().is_empty());
        if let Some(v) = env("AETHER_AZURE_CLIENT_ID") {
            cfg.azure_client_id = Some(v);
        }
        if let Some(v) = env("AETHER_CURSEFORGE_API_KEY") {
            cfg.curseforge_api_key = Some(v);
        }
        if let Some(v) = env("AETHER_BACKEND_URL") {
            cfg.backend_url = Some(v);
        }
        if let Some(v) = env("AETHER_MANIFEST_PUBLIC_KEY") {
            cfg.manifest_public_key = Some(v);
        }
        cfg
    }

    /// Create `aether.config.json` with empty placeholders if missing, so users just fill in values.
    pub fn ensure_file(root: &Path) -> std::io::Result<()> {
        let path = root.join("aether.config.json");
        if path.exists() {
            return Ok(());
        }
        std::fs::create_dir_all(root)?;
        std::fs::write(
            path,
            "{\n  \"azure_client_id\": \"\",\n  \"curseforge_api_key\": \"\",\n  \"backend_url\": \"\",\n  \"manifest_public_key\": \"\"\n}\n",
        )
    }

    pub fn azure(&self) -> Result<&str> {
        self.azure_client_id.as_deref().ok_or_else(|| Error::Other("azure_client_id not configured".into()))
    }
    pub fn curseforge(&self) -> Result<&str> {
        self.curseforge_api_key.as_deref().ok_or_else(|| Error::Other("curseforge_api_key not configured".into()))
    }
    pub fn backend(&self) -> Result<&str> {
        self.backend_url.as_deref().ok_or_else(|| Error::Other("backend_url not configured".into()))
    }

    /// Which optional features are live; the UI uses this to show "add key to enable" hints.
    pub fn status(&self) -> ConfigStatus {
        ConfigStatus {
            login: self.azure_client_id.is_some(),
            curseforge: self.curseforge_api_key.is_some(),
            backend: self.backend_url.is_some(),
        }
    }
}

#[derive(Debug, Clone, Serialize)]
pub struct ConfigStatus {
    pub login: bool,
    pub curseforge: bool,
    pub backend: bool,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn missing_values_error_not_panic() {
        let c = AppConfig::default();
        assert!(c.azure().is_err());
        assert!(!c.status().login);
    }

    #[test]
    fn loads_file() {
        let dir = std::env::temp_dir().join(format!("aether-cfg-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("aether.config.json"), r#"{"azure_client_id":"abc"}"#).unwrap();
        let c = AppConfig::load(&dir);
        assert_eq!(c.azure().unwrap(), "abc");
        let _ = std::fs::remove_dir_all(&dir);
    }
}
