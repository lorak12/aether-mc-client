//! Profiles: isolated game directories with their own version, loader, JVM settings and content lock.
use crate::{Error, Result};
use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};

pub const PROFILE_FORMAT: u32 = 1;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Loader {
    Vanilla,
    Fabric,
    Quilt,
    Forge,
    NeoForge,
}

impl Loader {
    /// Modrinth loader id, `None` for vanilla.
    pub fn modrinth_id(self) -> Option<&'static str> {
        match self {
            Loader::Vanilla => None,
            Loader::Fabric => Some("fabric"),
            Loader::Quilt => Some("quilt"),
            Loader::Forge => Some("forge"),
            Loader::NeoForge => Some("neoforge"),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Profile {
    #[serde(default = "one")]
    pub format: u32,
    pub id: String,
    pub name: String,
    pub mc_version: String,
    pub loader: Loader,
    #[serde(default = "default_mem")]
    pub memory_mb: u32,
    #[serde(default)]
    pub jvm_args: Vec<String>,
    #[serde(default)]
    pub created_unix: u64,
}

fn one() -> u32 {
    PROFILE_FORMAT
}
fn default_mem() -> u32 {
    4096
}

/// Subdirectories created for every profile.
pub const SUBDIRS: &[&str] = &["mods", "resourcepacks", "shaderpacks", "saves", "config"];

pub struct ProfileStore {
    root: PathBuf,
}

/// Turn a display name into a filesystem-safe id.
pub fn slugify(name: &str) -> String {
    let s: String = name
        .to_lowercase()
        .chars()
        .map(|c| if c.is_ascii_alphanumeric() { c } else { '-' })
        .collect();
    let s = s.split('-').filter(|p| !p.is_empty()).collect::<Vec<_>>().join("-");
    if s.is_empty() { "profile".into() } else { s }
}

impl ProfileStore {
    pub fn new(data_root: &Path) -> Self {
        Self { root: data_root.join("profiles") }
    }

    pub fn dir(&self, id: &str) -> PathBuf {
        self.root.join(id)
    }

    fn valid_id(id: &str) -> bool {
        !id.is_empty() && id.chars().all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_')
    }

    pub fn create(&self, name: &str, mc_version: &str, loader: Loader) -> Result<Profile> {
        let base = slugify(name);
        let mut id = base.clone();
        let mut n = 2;
        while self.dir(&id).exists() {
            id = format!("{base}-{n}");
            n += 1;
        }
        let profile = Profile {
            format: PROFILE_FORMAT,
            id: id.clone(),
            name: name.to_string(),
            mc_version: mc_version.to_string(),
            loader,
            memory_mb: default_mem(),
            jvm_args: vec![],
            created_unix: std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.as_secs())
                .unwrap_or(0),
        };
        for d in SUBDIRS {
            std::fs::create_dir_all(self.dir(&id).join(d))?;
        }
        self.save(&profile)?;
        Ok(profile)
    }

    pub fn save(&self, p: &Profile) -> Result<()> {
        if !Self::valid_id(&p.id) {
            return Err(Error::Other("invalid profile id".into()));
        }
        std::fs::create_dir_all(self.dir(&p.id))?;
        std::fs::write(self.dir(&p.id).join("profile.json"), serde_json::to_vec_pretty(p)?)?;
        Ok(())
    }

    pub fn load(&self, id: &str) -> Result<Profile> {
        if !Self::valid_id(id) {
            return Err(Error::Other("invalid profile id".into()));
        }
        Ok(serde_json::from_slice(&std::fs::read(self.dir(id).join("profile.json"))?)?)
    }

    pub fn list(&self) -> Result<Vec<Profile>> {
        let mut out = Vec::new();
        let Ok(rd) = std::fs::read_dir(&self.root) else { return Ok(out) };
        for e in rd.flatten() {
            if let Ok(b) = std::fs::read(e.path().join("profile.json")) {
                if let Ok(p) = serde_json::from_slice::<Profile>(&b) {
                    out.push(p);
                }
            }
        }
        out.sort_by_key(|p| p.created_unix);
        Ok(out)
    }

    pub fn delete(&self, id: &str) -> Result<()> {
        if !Self::valid_id(id) {
            return Err(Error::Other("invalid profile id".into()));
        }
        std::fs::remove_dir_all(self.dir(id))?;
        Ok(())
    }

    /// Duplicate a profile including its content (saves excluded to keep copies small).
    pub fn duplicate(&self, id: &str) -> Result<Profile> {
        let src = self.load(id)?;
        let mut copy = self.create(&format!("{} copy", src.name), &src.mc_version, src.loader)?;
        copy.memory_mb = src.memory_mb;
        copy.jvm_args = src.jvm_args.clone();
        self.save(&copy)?;
        for d in ["mods", "resourcepacks", "shaderpacks", "config"] {
            copy_dir(&self.dir(id).join(d), &self.dir(&copy.id).join(d))?;
        }
        let lock = self.dir(id).join("content.lock.json");
        if lock.exists() {
            std::fs::copy(lock, self.dir(&copy.id).join("content.lock.json"))?;
        }
        Ok(copy)
    }
}

fn copy_dir(from: &Path, to: &Path) -> Result<()> {
    std::fs::create_dir_all(to)?;
    let Ok(rd) = std::fs::read_dir(from) else { return Ok(()) };
    for e in rd.flatten() {
        let dest = to.join(e.file_name());
        if e.path().is_dir() {
            copy_dir(&e.path(), &dest)?;
        } else {
            std::fs::copy(e.path(), dest)?;
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tmp(tag: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("aether-prof-{tag}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        d
    }

    #[test]
    fn slug() {
        assert_eq!(slugify("My PvP Profile!"), "my-pvp-profile");
        assert_eq!(slugify("///"), "profile");
    }

    #[test]
    fn crud_and_unique_ids() {
        let root = tmp("crud");
        let s = ProfileStore::new(&root);
        let a = s.create("PvP", "1.8.9", Loader::Vanilla).unwrap();
        let b = s.create("PvP", "1.21.11", Loader::Fabric).unwrap();
        assert_eq!(a.id, "pvp");
        assert_eq!(b.id, "pvp-2");
        assert!(s.dir("pvp").join("mods").is_dir());
        assert_eq!(s.list().unwrap().len(), 2);
        let d = s.duplicate("pvp-2").unwrap();
        assert_eq!(d.mc_version, "1.21.11");
        s.delete("pvp").unwrap();
        assert_eq!(s.list().unwrap().len(), 2);
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn rejects_path_traversal_ids() {
        let root = tmp("trav");
        let s = ProfileStore::new(&root);
        assert!(s.load("../x").is_err());
        assert!(s.delete("..").is_err());
    }
}
