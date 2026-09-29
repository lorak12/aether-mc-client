//! Import of Modrinth `.mrpack` modpacks into a new profile.
use crate::download::fetch_verified;
use crate::profile::{Loader, Profile, ProfileStore};
use crate::{Error, Result};
use serde::Deserialize;
use std::collections::HashMap;
use std::io::Read;
use std::path::{Component, Path, PathBuf};

/// Hosts the mrpack spec allows file downloads from.
const ALLOWED_HOSTS: &[&str] = &["cdn.modrinth.com", "github.com", "raw.githubusercontent.com", "gitlab.com"];

#[derive(Debug, Deserialize)]
pub struct Index {
    pub name: String,
    #[serde(default)]
    pub dependencies: HashMap<String, String>,
    #[serde(default)]
    pub files: Vec<IndexFile>,
}

#[derive(Debug, Deserialize)]
pub struct IndexFile {
    pub path: String,
    pub hashes: Hashes,
    pub downloads: Vec<String>,
    #[serde(default)]
    pub env: Option<Env>,
}

#[derive(Debug, Deserialize)]
pub struct Hashes {
    pub sha1: String,
}

#[derive(Debug, Deserialize)]
pub struct Env {
    #[serde(default)]
    pub client: Option<String>,
}

impl Index {
    pub fn mc_version(&self) -> Result<&str> {
        self.dependencies.get("minecraft").map(String::as_str).ok_or_else(|| Error::Other("mrpack has no minecraft dependency".into()))
    }

    pub fn loader(&self) -> Loader {
        let d = &self.dependencies;
        if d.contains_key("fabric-loader") {
            Loader::Fabric
        } else if d.contains_key("quilt-loader") {
            Loader::Quilt
        } else if d.contains_key("neoforge") {
            Loader::NeoForge
        } else if d.contains_key("forge") {
            Loader::Forge
        } else {
            Loader::Vanilla
        }
    }
}

/// Relative, no `..`, no absolute/drive prefixes.
pub fn safe_relative(path: &str) -> Option<PathBuf> {
    let p = Path::new(path);
    if p.as_os_str().is_empty() || path.contains('\\') {
        return None;
    }
    p.components().all(|c| matches!(c, Component::Normal(_))).then(|| p.to_path_buf())
}

fn host_allowed(url: &str) -> bool {
    url.strip_prefix("https://")
        .and_then(|r| r.split('/').next())
        .map(|h| ALLOWED_HOSTS.contains(&h))
        .unwrap_or(false)
}

pub fn read_index(mrpack: &Path) -> Result<Index> {
    let mut zip = zip::ZipArchive::new(std::fs::File::open(mrpack)?)?;
    let mut f = zip.by_name("modrinth.index.json")?;
    let mut buf = Vec::new();
    f.read_to_end(&mut buf)?;
    Ok(serde_json::from_slice(&buf)?)
}

/// Create a profile from a `.mrpack`: download listed files, then apply `overrides/` and `client-overrides/`.
pub async fn import(client: &reqwest::Client, store: &ProfileStore, mrpack: &Path) -> Result<Profile> {
    let index = read_index(mrpack)?;
    let profile = store.create(&index.name, index.mc_version()?, index.loader())?;
    let dir = store.dir(&profile.id);

    for f in &index.files {
        if f.env.as_ref().and_then(|e| e.client.as_deref()) == Some("unsupported") {
            continue;
        }
        let rel = safe_relative(&f.path).ok_or_else(|| Error::Other(format!("unsafe path in mrpack: {}", f.path)))?;
        let url = f.downloads.iter().find(|u| host_allowed(u)).ok_or_else(|| Error::Other(format!("no allowed download host for {}", f.path)))?;
        fetch_verified(client, url, &dir.join(rel), &f.hashes.sha1).await?;
    }

    let mut zip = zip::ZipArchive::new(std::fs::File::open(mrpack)?)?;
    for i in 0..zip.len() {
        let mut e = zip.by_index(i)?;
        if e.is_dir() {
            continue;
        }
        let name = e.name().to_string();
        let rest = name.strip_prefix("overrides/").or_else(|| name.strip_prefix("client-overrides/"));
        let Some(rest) = rest else { continue };
        let Some(rel) = safe_relative(rest) else { continue };
        let out = dir.join(rel);
        if let Some(p) = out.parent() {
            std::fs::create_dir_all(p)?;
        }
        std::io::copy(&mut e, &mut std::fs::File::create(out)?)?;
    }
    Ok(profile)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    #[test]
    fn path_safety() {
        assert!(safe_relative("mods/a.jar").is_some());
        assert!(safe_relative("../a.jar").is_none());
        assert!(safe_relative("/etc/passwd").is_none());
        assert!(safe_relative("mods\\a.jar").is_none());
        assert!(safe_relative("").is_none());
    }

    #[test]
    fn host_allowlist() {
        assert!(host_allowed("https://cdn.modrinth.com/data/x/y.jar"));
        assert!(!host_allowed("http://cdn.modrinth.com/x"));
        assert!(!host_allowed("https://evil.com/x"));
        assert!(!host_allowed("https://cdn.modrinth.com.evil.com/x"));
    }

    #[tokio::test]
    async fn imports_overrides_and_detects_loader() {
        let root = std::env::temp_dir().join(format!("aether-mrpack-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        std::fs::create_dir_all(&root).unwrap();
        let pack = root.join("t.mrpack");
        {
            let mut w = zip::ZipWriter::new(std::fs::File::create(&pack).unwrap());
            let o = zip::write::SimpleFileOptions::default();
            w.start_file("modrinth.index.json", o).unwrap();
            w.write_all(br#"{"name":"Test Pack","dependencies":{"minecraft":"1.21.11","fabric-loader":"0.17.0"},"files":[]}"#).unwrap();
            w.start_file("overrides/config/a.toml", o).unwrap();
            w.write_all(b"x=1").unwrap();
            w.start_file("overrides/../evil.txt", o).unwrap();
            w.write_all(b"x").unwrap();
            w.finish().unwrap();
        }
        let store = ProfileStore::new(&root);
        let p = import(&reqwest::Client::new(), &store, &pack).await.unwrap();
        assert_eq!(p.mc_version, "1.21.11");
        assert_eq!(p.loader, Loader::Fabric);
        assert!(store.dir(&p.id).join("config/a.toml").exists());
        assert!(!store.dir(&p.id).join("evil.txt").exists());
        assert!(!root.join("profiles/evil.txt").exists());
        let _ = std::fs::remove_dir_all(&root);
    }
}
