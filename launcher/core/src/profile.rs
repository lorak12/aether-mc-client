//! Profiles: isolated game directories with their own version, loader, JVM settings and content lock.
use crate::settings::{overrides_from_legacy, Overrides};
use crate::{Error, Result};
use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};

pub const PROFILE_FORMAT: u32 = 2;

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

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "lowercase")]
pub enum ProfileKind {
    /// Runs the Kite client (Fabric/Quilt on a supported version).
    #[default]
    Client,
    Vanilla,
    Modpack,
}

/// Display data and stats the launcher keeps with the profile.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct ProfileMeta {
    pub kind: ProfileKind,
    pub description: String,
    /// A CSS background (gradient), or `file:<name>` for an image in the profile folder.
    pub cover: Option<String>,
    /// Client profiles only: load just the Kite client, not the other jars in `mods/`.
    pub builtin_only: bool,
    /// Unix milliseconds.
    pub last_played: Option<u64>,
    pub plays: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Profile {
    #[serde(default = "one")]
    pub format: u32,
    pub id: String,
    pub name: String,
    pub mc_version: String,
    pub loader: Loader,
    /// Pinned loader version; `None` = newest stable at launch.
    #[serde(default)]
    pub loader_version: Option<String>,
    #[serde(default)]
    pub created_unix: u64,
    /// Folder the game runs in, when not the profile's own folder.
    #[serde(default)]
    pub game_dir: Option<String>,
    #[serde(default)]
    pub overrides: Overrides,
    #[serde(default)]
    pub meta: ProfileMeta,
    /// The profile's folder, filled in when loaded (not stored).
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub dir: String,
    /// Format 1 kept resolved memory/JVM values on the profile; read once to migrate them into `overrides`.
    #[serde(default, skip_serializing)]
    memory_mb: Option<u32>,
    #[serde(default, skip_serializing)]
    jvm_args: Option<Vec<String>>,
}

fn one() -> u32 {
    1
}

impl Profile {
    fn migrate(&mut self) -> bool {
        if self.format >= PROFILE_FORMAT {
            return false;
        }
        if self.format < 2 {
            self.overrides = overrides_from_legacy(self.memory_mb, self.jvm_args.as_deref().unwrap_or_default());
            self.meta.kind = if self.loader == Loader::Vanilla { ProfileKind::Vanilla } else { ProfileKind::Client };
        }
        self.memory_mb = None;
        self.jvm_args = None;
        self.format = PROFILE_FORMAT;
        true
    }
}

/// Subdirectories created for every profile.
pub const SUBDIRS: &[&str] = &["mods", "resourcepacks", "shaderpacks", "saves", "config", "screenshots"];

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
        let created_unix = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0);
        let mut profile = Profile {
            format: PROFILE_FORMAT,
            id: id.clone(),
            name: name.to_string(),
            mc_version: mc_version.to_string(),
            loader,
            loader_version: None,
            created_unix,
            game_dir: None,
            overrides: Overrides::default(),
            meta: ProfileMeta { kind: if loader == Loader::Vanilla { ProfileKind::Vanilla } else { ProfileKind::Client }, ..Default::default() },
            dir: String::new(),
            memory_mb: None,
            jvm_args: None,
        };
        for d in SUBDIRS {
            std::fs::create_dir_all(self.dir(&id).join(d))?;
        }
        self.save(&profile)?;
        profile.dir = self.dir(&id).display().to_string();
        Ok(profile)
    }

    pub fn save(&self, p: &Profile) -> Result<()> {
        if !Self::valid_id(&p.id) {
            return Err(Error::Other("invalid profile id".into()));
        }
        std::fs::create_dir_all(self.dir(&p.id))?;
        let stored = Profile { dir: String::new(), ..p.clone() };
        // Write then rename so a crash mid-write never leaves a half profile.json.
        let path = self.dir(&p.id).join("profile.json");
        let tmp = path.with_extension("json.tmp");
        std::fs::write(&tmp, serde_json::to_vec_pretty(&stored)?)?;
        std::fs::rename(tmp, path)?;
        Ok(())
    }

    fn read(&self, id: &str) -> Result<Profile> {
        let mut p: Profile = serde_json::from_slice(&std::fs::read(self.dir(id).join("profile.json"))?)?;
        if p.migrate() {
            self.save(&p)?;
        }
        p.dir = self.dir(id).display().to_string();
        Ok(p)
    }

    pub fn load(&self, id: &str) -> Result<Profile> {
        if !Self::valid_id(id) {
            return Err(Error::Other("invalid profile id".into()));
        }
        self.read(id)
    }

    pub fn list(&self) -> Result<Vec<Profile>> {
        let mut out = Vec::new();
        let Ok(rd) = std::fs::read_dir(&self.root) else { return Ok(out) };
        for e in rd.flatten() {
            let Some(id) = e.file_name().to_str().map(String::from) else { continue };
            if Self::valid_id(&id) {
                if let Ok(p) = self.read(&id) {
                    out.push(p);
                }
            }
        }
        out.sort_by_key(|p| p.created_unix);
        Ok(out)
    }

    /// Where the game runs and content lives: the custom folder if one is set, the profile folder otherwise.
    pub fn game_dir(&self, p: &Profile) -> PathBuf {
        match p.game_dir.as_deref().map(str::trim).filter(|d| !d.is_empty()) {
            Some(d) if Path::new(d).is_absolute() => PathBuf::from(d),
            _ => self.dir(&p.id),
        }
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
        copy.loader_version = src.loader_version.clone();
        copy.overrides = src.overrides.clone();
        copy.meta = ProfileMeta { last_played: None, plays: 0, ..src.meta.clone() };
        self.save(&copy)?;
        let from = self.game_dir(&src);
        for d in ["mods", "resourcepacks", "shaderpacks", "config"] {
            copy_dir(&from.join(d), &self.dir(&copy.id).join(d))?;
        }
        for f in ["content.lock.json", "options.txt", "servers.dat"] {
            if from.join(f).exists() {
                std::fs::copy(from.join(f), self.dir(&copy.id).join(f))?;
            }
        }
        for e in std::fs::read_dir(self.dir(id))?.flatten() {
            let name = e.file_name().to_string_lossy().to_string();
            if name.starts_with("cover") {
                std::fs::copy(e.path(), self.dir(&copy.id).join(&name))?;
            }
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
impl ProfileStore {
    fn create_unsaved_for_test(&self, id: &str) -> Profile {
        serde_json::from_str(&format!(r#"{{"format":2,"id":"{id}","name":"x","mc_version":"1.21.11","loader":"fabric"}}"#)).unwrap()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tmp(tag: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("kite-prof-{tag}-{}", std::process::id()));
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
        assert_eq!(a.meta.kind, ProfileKind::Vanilla);
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
    fn migrates_format_1() {
        let root = tmp("mig");
        let s = ProfileStore::new(&root);
        std::fs::create_dir_all(s.dir("old")).unwrap();
        std::fs::write(
            s.dir("old").join("profile.json"),
            r#"{"format":1,"id":"old","name":"Old","mc_version":"1.21.11","loader":"fabric","memory_mb":6144,"jvm_args":["-XX:+UseZGC"],"created_unix":5}"#,
        )
        .unwrap();
        let p = s.load("old").unwrap();
        assert_eq!(p.format, PROFILE_FORMAT);
        assert_eq!(p.overrides.memory_mb.as_ref().unwrap().value, 6144);
        assert_eq!(p.meta.kind, ProfileKind::Client);
        assert!(p.dir.ends_with("old"));
        let raw = std::fs::read_to_string(s.dir("old").join("profile.json")).unwrap();
        assert!(raw.contains("\"format\": 2") && !raw.contains("memory_mb") && !raw.contains("\"dir\""));
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn custom_game_dir_must_be_absolute() {
        let s = ProfileStore::new(Path::new("/data"));
        let mut p = s.create_unsaved_for_test("x");
        assert_eq!(s.game_dir(&p), s.dir("x"));
        p.game_dir = Some("relative/dir".into());
        assert_eq!(s.game_dir(&p), s.dir("x"));
        let abs = std::env::temp_dir().join("kite-custom");
        p.game_dir = Some(abs.display().to_string());
        assert_eq!(s.game_dir(&p), abs);
    }

    #[test]
    fn rejects_path_traversal_ids() {
        let root = tmp("trav");
        let s = ProfileStore::new(&root);
        assert!(s.load("../x").is_err());
        assert!(s.delete("..").is_err());
    }
}
