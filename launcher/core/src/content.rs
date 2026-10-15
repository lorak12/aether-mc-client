//! Content install pipeline: dependency resolution, lock file, install / uninstall / update check.
use crate::download::fetch_verified;
use crate::modrinth::{Modrinth, ProjectType, Version};
use crate::profile::Profile;
use crate::{Error, Result};
use serde::{Deserialize, Serialize};
use std::collections::HashSet;
use std::path::{Path, PathBuf};

pub const LOCK_FILE: &str = "content.lock.json";

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct LockEntry {
    pub project_id: String,
    pub version_id: String,
    pub title: String,
    pub project_type: ProjectType,
    pub filename: String,
    pub sha1: String,
    /// False when pulled in only as a dependency of something else.
    pub explicit: bool,
    /// Datapacks only: world folder name.
    #[serde(default)]
    pub world: Option<String>,
    /// Disabled entries keep their file renamed to `<filename>.disabled` so the game skips it.
    #[serde(default = "yes")]
    pub enabled: bool,
    #[serde(default)]
    pub source: Source,
}

/// Where a tracked project comes from.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Source {
    #[default]
    Modrinth,
    Curseforge,
}

fn yes() -> bool {
    true
}

pub const DISABLED_SUFFIX: &str = ".disabled";

impl LockEntry {
    /// The file's current name on disk.
    pub fn disk_name(&self) -> String {
        if self.enabled {
            self.filename.clone()
        } else {
            format!("{}{DISABLED_SUFFIX}", self.filename)
        }
    }
}

/// A file in a content folder that the lock file doesn't track (added by hand or by another launcher).
#[derive(Debug, Clone, Serialize, PartialEq)]
pub struct LocalFile {
    /// Name without `.disabled`.
    pub filename: String,
    pub project_type: ProjectType,
    pub enabled: bool,
    pub size_bytes: u64,
}

fn content_ext_ok(kind: ProjectType, name: &str) -> bool {
    let n = name.to_lowercase();
    match kind {
        ProjectType::Mod => n.ends_with(".jar"),
        _ => n.ends_with(".zip"),
    }
}

/// Files in mods / resourcepacks / shaderpacks that aren't in the lock (the Aether client jar excluded).
pub fn local_files(game_dir: &Path) -> Vec<LocalFile> {
    let lock = Lock::load(game_dir);
    let mut out = Vec::new();
    for kind in [ProjectType::Mod, ProjectType::Resourcepack, ProjectType::Shader] {
        let Ok(rd) = std::fs::read_dir(game_dir.join(kind.folder())) else { continue };
        for e in rd.flatten() {
            if !e.file_type().map(|t| t.is_file()).unwrap_or(false) {
                continue;
            }
            let raw = e.file_name().to_string_lossy().to_string();
            let (name, enabled) = match raw.strip_suffix(DISABLED_SUFFIX) {
                Some(n) => (n.to_string(), false),
                None => (raw.clone(), true),
            };
            if !content_ext_ok(kind, &name) || name == crate::runtime::INSTALLED_NAME {
                continue;
            }
            if lock.entries.iter().any(|x| x.project_type == kind && x.filename == name) {
                continue;
            }
            out.push(LocalFile { filename: name, project_type: kind, enabled, size_bytes: e.metadata().map(|m| m.len()).unwrap_or(0) });
        }
    }
    out.sort_by_key(|a| a.filename.to_lowercase());
    out
}

/// Rename `name` <-> `name.disabled` in `dir`. Missing files are fine (nothing to rename).
fn rename_enabled(dir: &Path, name: &str, enabled: bool) -> Result<()> {
    let on = dir.join(safe_filename(name)?);
    let off = dir.join(format!("{name}{DISABLED_SUFFIX}"));
    let (from, to) = if enabled { (off, on) } else { (on, off) };
    if from.exists() {
        if to.exists() {
            std::fs::remove_file(&to)?;
        }
        std::fs::rename(from, to)?;
    }
    Ok(())
}

fn parse_file_id(id: &str) -> Result<(ProjectType, &str)> {
    let rest = id.strip_prefix("file:").ok_or_else(|| Error::Other("bad file id".into()))?;
    let (kind, name) = rest.split_once(':').ok_or_else(|| Error::Other("bad file id".into()))?;
    let kind: ProjectType = serde_json::from_value(serde_json::Value::String(kind.into()))?;
    Ok((kind, safe_filename(name)?))
}

/// Enable or disable a tracked project (by id) or an untracked file (`file:<kind>:<name>`).
pub fn set_enabled(game_dir: &Path, id: &str, enabled: bool) -> Result<()> {
    if id.starts_with("file:") {
        let (kind, name) = parse_file_id(id)?;
        return rename_enabled(&game_dir.join(kind.folder()), name, enabled);
    }
    let mut lock = Lock::load(game_dir);
    let e = lock.entries.iter_mut().find(|e| e.project_id == id).ok_or_else(|| Error::Other("not installed".into()))?;
    if e.enabled == enabled {
        return Ok(());
    }
    let dir = dest_dir(game_dir, e.project_type, e.world.as_deref())?;
    rename_enabled(&dir, &e.filename, enabled)?;
    e.enabled = enabled;
    lock.save(game_dir)
}

/// Look untracked files up on Modrinth by hash and track the ones it knows (so they get updates and export as
/// downloads). Returns how many were adopted.
pub async fn adopt_local_files(api: &Modrinth, game_dir: &Path) -> Result<usize> {
    let files = local_files(game_dir);
    if files.is_empty() {
        return Ok(0);
    }
    let mut hashed = Vec::new();
    for f in &files {
        let name = if f.enabled { f.filename.clone() } else { format!("{}{DISABLED_SUFFIX}", f.filename) };
        if let Ok(bytes) = std::fs::read(game_dir.join(f.project_type.folder()).join(name)) {
            hashed.push((f, crate::download::sha1_hex(&bytes)));
        }
    }
    let hashes: Vec<String> = hashed.iter().map(|(_, h)| h.clone()).collect();
    let found = api.versions_by_hashes(&hashes).await?;
    if found.is_empty() {
        return Ok(0);
    }
    let ids: Vec<String> = found.values().map(|v| v.project_id.clone()).collect();
    let titles: std::collections::HashMap<String, String> = api.projects(&ids).await.unwrap_or_default().into_iter().map(|p| (p.id, p.title)).collect();
    let mut lock = Lock::load(game_dir);
    let mut n = 0;
    for (f, h) in hashed {
        let Some(v) = found.get(&h) else { continue };
        if lock.has_project(&v.project_id) {
            continue;
        }
        lock.entries.push(LockEntry {
            project_id: v.project_id.clone(),
            version_id: v.id.clone(),
            title: titles.get(&v.project_id).cloned().unwrap_or_else(|| v.name.clone()),
            project_type: f.project_type,
            filename: f.filename.clone(),
            sha1: h,
            explicit: true,
            world: None,
            enabled: f.enabled,
            source: Source::Modrinth,
        });
        n += 1;
    }
    lock.save(game_dir)?;
    Ok(n)
}

/// Delete an untracked file (`file:<kind>:<name>`), enabled or not.
pub fn remove_local(game_dir: &Path, id: &str) -> Result<()> {
    let (kind, name) = parse_file_id(id)?;
    let dir = game_dir.join(kind.folder());
    for p in [dir.join(name), dir.join(format!("{name}{DISABLED_SUFFIX}"))] {
        if p.exists() {
            std::fs::remove_file(p)?;
        }
    }
    Ok(())
}

#[derive(Debug, Default, Serialize, Deserialize)]
pub struct Lock {
    #[serde(default)]
    pub entries: Vec<LockEntry>,
}

impl Lock {
    pub fn path(profile_dir: &Path) -> PathBuf {
        profile_dir.join(LOCK_FILE)
    }
    pub fn load(profile_dir: &Path) -> Lock {
        std::fs::read(Self::path(profile_dir)).ok().and_then(|b| serde_json::from_slice(&b).ok()).unwrap_or_default()
    }
    pub fn save(&self, profile_dir: &Path) -> Result<()> {
        std::fs::write(Self::path(profile_dir), serde_json::to_vec_pretty(self)?)?;
        Ok(())
    }
    pub fn has_project(&self, id: &str) -> bool {
        self.entries.iter().any(|e| e.project_id == id)
    }
}

#[allow(async_fn_in_trait)]
/// Where to look up versions; abstracted so the resolver is testable offline.
pub trait VersionSource {
    async fn version_by_id(&self, id: &str) -> Result<Version>;
    async fn latest_for_project(&self, project_id: &str) -> Result<Option<Version>>;
}

pub struct ModrinthSource<'a> {
    pub api: &'a Modrinth,
    pub mc_version: &'a str,
    pub loaders: Vec<String>,
}

impl VersionSource for ModrinthSource<'_> {
    async fn version_by_id(&self, id: &str) -> Result<Version> {
        self.api.version(id).await
    }
    async fn latest_for_project(&self, project_id: &str) -> Result<Option<Version>> {
        Ok(self.api.versions(project_id, self.mc_version, &self.loaders).await?.into_iter().next())
    }
}

#[derive(Debug, Default)]
pub struct Plan {
    /// Versions to install, dependencies first.
    pub install: Vec<Version>,
    /// Required dependencies with no compatible version.
    pub missing: Vec<String>,
    /// Declared-incompatible projects that are already installed.
    pub conflicts: Vec<String>,
}

/// Resolve `root` plus its required dependencies. Skips anything already in `installed` (project ids).
pub async fn resolve<S: VersionSource>(src: &S, root: Version, installed: &HashSet<String>) -> Result<Plan> {
    let mut plan = Plan::default();
    let mut seen: HashSet<String> = installed.clone();
    let mut stack = vec![root];
    let mut ordered: Vec<Version> = Vec::new();
    while let Some(v) = stack.pop() {
        if ordered.iter().any(|o| o.project_id == v.project_id) {
            continue;
        }
        seen.insert(v.project_id.clone());
        for d in &v.dependencies {
            match d.dependency_type.as_str() {
                "required" => {
                    let pid = d.project_id.clone();
                    if let Some(p) = &pid {
                        if seen.contains(p) {
                            continue;
                        }
                    }
                    let dep = match (&d.version_id, &pid) {
                        (Some(vid), _) => Some(src.version_by_id(vid).await?),
                        (None, Some(p)) => src.latest_for_project(p).await?,
                        _ => None,
                    };
                    match dep {
                        Some(dv) => stack.push(dv),
                        None => plan.missing.push(pid.unwrap_or_else(|| "unknown".into())),
                    }
                }
                "incompatible" => {
                    if let Some(p) = &d.project_id {
                        if installed.contains(p) {
                            plan.conflicts.push(p.clone());
                        }
                    }
                }
                _ => {}
            }
        }
        ordered.push(v);
    }
    ordered.reverse(); // dependencies before dependents (best-effort for diamond graphs)
    plan.install = ordered;
    Ok(plan)
}

/// Destination directory for a file of `kind` inside a profile.
pub fn dest_dir(profile_dir: &Path, kind: ProjectType, world: Option<&str>) -> Result<PathBuf> {
    match kind {
        ProjectType::Datapack => {
            let w = world.ok_or_else(|| Error::Other("datapacks need a target world".into()))?;
            if w.contains(['/', '\\']) || w == ".." || w.is_empty() {
                return Err(Error::Other("invalid world name".into()));
            }
            Ok(profile_dir.join("saves").join(w).join("datapacks"))
        }
        ProjectType::Modpack => Err(Error::Other("modpacks are imported as new profiles".into())),
        k => Ok(profile_dir.join(k.folder())),
    }
}

/// Reject file names that could escape the target directory.
pub fn safe_filename(name: &str) -> Result<&str> {
    if name.is_empty() || name.contains(['/', '\\']) || name == ".." || name == "." {
        return Err(Error::Other(format!("unsafe file name from server: {name}")));
    }
    Ok(name)
}

pub struct Installer<'a> {
    pub api: &'a Modrinth,
    pub profile: &'a Profile,
    pub profile_dir: PathBuf,
}

impl Installer<'_> {
    fn loaders(&self, kind: ProjectType) -> Vec<String> {
        kind.loader_filter(self.profile.loader.modrinth_id())
    }

    /// Install a project's newest compatible version plus required dependencies. Returns lock entries added.
    pub async fn install_project(&self, project_id: &str, kind: ProjectType, world: Option<&str>) -> Result<Vec<LockEntry>> {
        self.install(project_id, None, kind, world, true).await
    }

    /// The version to install: `version_id` when given, otherwise the newest compatible release (or newest of any type).
    pub async fn choose(&self, project_id: &str, version_id: Option<&str>, kind: ProjectType) -> Result<Version> {
        if let Some(id) = version_id {
            return self.api.version(id).await;
        }
        let loaders = self.loaders(kind);
        let versions = self.api.versions(project_id, &self.profile.mc_version, &loaders).await?;
        versions
            .iter()
            .find(|v| v.version_type == "release")
            .or_else(|| versions.first())
            .cloned()
            .ok_or_else(|| Error::Other(format!("no version of {project_id} for {} ({:?})", self.profile.mc_version, loaders)))
    }

    /// Resolve what installing would add, without downloading anything.
    pub async fn plan(&self, project_id: &str, version_id: Option<&str>, kind: ProjectType) -> Result<(Version, Plan)> {
        let chosen = self.choose(project_id, version_id, kind).await?;
        let lock = Lock::load(&self.profile_dir);
        let installed: HashSet<String> = lock.entries.iter().map(|e| e.project_id.clone()).collect();
        let src = ModrinthSource { api: self.api, mc_version: &self.profile.mc_version, loaders: self.loaders(kind) };
        let plan = resolve(&src, chosen.clone(), &installed).await?;
        Ok((chosen, plan))
    }

    /// Install a specific (or the newest compatible) version. `with_deps = false` installs only the project itself.
    pub async fn install(
        &self,
        project_id: &str,
        version_id: Option<&str>,
        kind: ProjectType,
        world: Option<&str>,
        with_deps: bool,
    ) -> Result<Vec<LockEntry>> {
        let (chosen, plan) = self.plan(project_id, version_id, kind).await?;
        if let Some(c) = plan.conflicts.first() {
            return Err(Error::Other(format!("conflicts with installed project {c}")));
        }
        if with_deps {
            if let Some(m) = plan.missing.first() {
                return Err(Error::Other(format!("required dependency {m} has no compatible version")));
            }
        }
        let todo: Vec<Version> = if with_deps { plan.install } else { vec![chosen.clone()] };

        let mut lock = Lock::load(&self.profile_dir);
        let mut added = Vec::new();
        for v in todo {
            let is_root = v.project_id == chosen.project_id;
            let file = v.primary_file().ok_or_else(|| Error::Other("version has no files".into()))?;
            let name = safe_filename(&file.filename)?;
            let dir = dest_dir(&self.profile_dir, if is_root { kind } else { ProjectType::Mod }, world)?;
            fetch_verified(self.api.http(), &file.url, &dir.join(name), &file.hashes.sha1).await?;
            let title = if is_root { self.api.project(&v.project_id).await.map(|p| p.title).unwrap_or_else(|_| v.name.clone()) } else { v.name.clone() };
            let entry = LockEntry {
                project_id: v.project_id.clone(),
                version_id: v.id.clone(),
                title,
                project_type: if is_root { kind } else { ProjectType::Mod },
                filename: name.to_string(),
                sha1: file.hashes.sha1.clone(),
                explicit: is_root,
                world: if is_root { world.map(String::from) } else { None },
                enabled: true,
                source: Source::Modrinth,
            };
            lock.entries.push(entry.clone());
            added.push(entry);
        }
        lock.save(&self.profile_dir)?;
        Ok(added)
    }

    pub fn uninstall(&self, project_id: &str) -> Result<()> {
        let mut lock = Lock::load(&self.profile_dir);
        let Some(pos) = lock.entries.iter().position(|e| e.project_id == project_id) else {
            return Ok(());
        };
        let e = lock.entries.remove(pos);
        let dir = dest_dir(&self.profile_dir, e.project_type, e.world.as_deref())?;
        safe_filename(&e.filename)?;
        let _ = std::fs::remove_file(dir.join(e.disk_name()));
        lock.save(&self.profile_dir)
    }

    /// Entries with a newer compatible version available: (entry, newer version).
    pub async fn check_updates(&self) -> Result<Vec<(LockEntry, Version)>> {
        let lock = Lock::load(&self.profile_dir);
        let mut out = Vec::new();
        for kind in [ProjectType::Mod, ProjectType::Resourcepack, ProjectType::Shader, ProjectType::Datapack] {
            let entries: Vec<&LockEntry> = lock.entries.iter().filter(|e| e.project_type == kind && e.source == Source::Modrinth).collect();
            if entries.is_empty() {
                continue;
            }
            let hashes: Vec<String> = entries.iter().map(|e| e.sha1.clone()).collect();
            let latest = self.api.latest_for_hashes(&hashes, &self.loaders(kind), &self.profile.mc_version).await?;
            for e in entries {
                if let Some(v) = latest.get(&e.sha1) {
                    if v.id != e.version_id {
                        out.push((e.clone(), v.clone()));
                    }
                }
            }
        }
        Ok(out)
    }

    /// Replace an installed entry's file with a newer version's primary file (dependencies not re-resolved).
    pub async fn apply_update(&self, entry: &LockEntry, newer: &Version) -> Result<()> {
        let file = newer.primary_file().ok_or_else(|| Error::Other("version has no files".into()))?;
        let name = safe_filename(&file.filename)?;
        let dir = dest_dir(&self.profile_dir, entry.project_type, entry.world.as_deref())?;
        safe_filename(&entry.filename)?;
        let _ = std::fs::remove_file(dir.join(entry.disk_name()));
        fetch_verified(self.api.http(), &file.url, &dir.join(name), &file.hashes.sha1).await?;
        // A disabled project stays disabled after updating.
        if !entry.enabled {
            rename_enabled(&dir, name, false)?;
        }
        let mut lock = Lock::load(&self.profile_dir);
        if let Some(e) = lock.entries.iter_mut().find(|x| x.project_id == entry.project_id) {
            e.version_id = newer.id.clone();
            e.filename = name.to_string();
            e.sha1 = file.hashes.sha1.clone();
        }
        lock.save(&self.profile_dir)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::modrinth::{Dependency, Hashes, VersionFile};
    use std::collections::HashMap;

    fn ver(id: &str, project: &str, deps: Vec<(&str, &str)>) -> Version {
        Version {
            id: id.into(),
            project_id: project.into(),
            name: id.into(),
            version_number: "1".into(),
            version_type: "release".into(),
            game_versions: vec![],
            loaders: vec![],
            files: vec![VersionFile { url: "u".into(), filename: format!("{id}.jar"), primary: true, size: 1, hashes: Hashes { sha1: "x".into(), sha512: None } }],
            dependencies: deps
                .into_iter()
                .map(|(p, t)| Dependency { version_id: None, project_id: Some(p.into()), dependency_type: t.into() })
                .collect(),
            date_published: String::new(),
        }
    }

    struct Fake(HashMap<String, Version>);
    impl VersionSource for Fake {
        async fn version_by_id(&self, id: &str) -> Result<Version> {
            self.0.get(id).cloned().ok_or_else(|| Error::Other("nf".into()))
        }
        async fn latest_for_project(&self, p: &str) -> Result<Option<Version>> {
            Ok(self.0.values().find(|v| v.project_id == p).cloned())
        }
    }

    #[tokio::test]
    async fn resolves_transitive_deps_in_order() {
        // iris -> sodium -> fabric-api ; iris also optional dep on x (ignored)
        let src = Fake(HashMap::from([
            ("sodium1".into(), ver("sodium1", "sodium", vec![("fapi", "required")])),
            ("fapi1".into(), ver("fapi1", "fapi", vec![])),
        ]));
        let iris = ver("iris1", "iris", vec![("sodium", "required"), ("x", "optional")]);
        let plan = resolve(&src, iris, &HashSet::new()).await.unwrap();
        let ids: Vec<_> = plan.install.iter().map(|v| v.project_id.as_str()).collect();
        assert_eq!(ids, ["fapi", "sodium", "iris"]);
        assert!(plan.missing.is_empty());
    }

    #[tokio::test]
    async fn skips_installed_and_reports_missing_and_conflicts() {
        let src = Fake(HashMap::new());
        let a = ver("a1", "a", vec![("gone", "required"), ("optifine", "incompatible")]);
        let installed = HashSet::from(["optifine".to_string()]);
        let plan = resolve(&src, a, &installed).await.unwrap();
        assert_eq!(plan.missing, ["gone"]);
        assert_eq!(plan.conflicts, ["optifine"]);

        let b = ver("b1", "b", vec![("sodium", "required")]);
        let plan = resolve(&src, b, &HashSet::from(["sodium".to_string()])).await.unwrap();
        assert_eq!(plan.install.len(), 1);
    }

    #[test]
    fn dest_dirs() {
        let p = Path::new("/p");
        assert!(dest_dir(p, ProjectType::Mod, None).unwrap().ends_with("mods"));
        assert!(dest_dir(p, ProjectType::Datapack, None).is_err());
        assert!(dest_dir(p, ProjectType::Datapack, Some("..")).is_err());
        assert!(dest_dir(p, ProjectType::Datapack, Some("World")).unwrap().ends_with("datapacks"));
    }

    #[test]
    fn enable_disable_and_local_files() {
        let g = std::env::temp_dir().join(format!("aether-content-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&g);
        std::fs::create_dir_all(g.join("mods")).unwrap();
        std::fs::write(g.join("mods/sodium.jar"), b"s").unwrap();
        std::fs::write(g.join("mods/hand.jar"), b"h").unwrap();
        std::fs::write(g.join("mods/aether-client.jar"), b"a").unwrap();
        std::fs::write(g.join("mods/notes.txt"), b"n").unwrap();
        let entry = LockEntry {
            project_id: "AANobbMI".into(),
            version_id: "v".into(),
            title: "Sodium".into(),
            project_type: ProjectType::Mod,
            filename: "sodium.jar".into(),
            sha1: "x".into(),
            explicit: true,
            world: None,
            enabled: true,
            source: Source::Modrinth,
        };
        Lock { entries: vec![entry] }.save(&g).unwrap();

        set_enabled(&g, "AANobbMI", false).unwrap();
        assert!(g.join("mods/sodium.jar.disabled").exists() && !g.join("mods/sodium.jar").exists());
        assert!(!Lock::load(&g).entries[0].enabled);
        set_enabled(&g, "AANobbMI", true).unwrap();
        assert!(g.join("mods/sodium.jar").exists());

        let local = local_files(&g);
        assert_eq!(local.iter().map(|f| f.filename.as_str()).collect::<Vec<_>>(), ["hand.jar"]);
        set_enabled(&g, "file:mod:hand.jar", false).unwrap();
        assert!(!local_files(&g)[0].enabled);
        assert!(set_enabled(&g, "file:mod:../x.jar", true).is_err());
        remove_local(&g, "file:mod:hand.jar").unwrap();
        assert!(local_files(&g).is_empty());
        // Lock files written before the flag existed read as enabled.
        let old: LockEntry = serde_json::from_str(r#"{"project_id":"p","version_id":"v","title":"t","project_type":"mod","filename":"f.jar","sha1":"s","explicit":true}"#).unwrap();
        assert!(old.enabled);
        let _ = std::fs::remove_dir_all(&g);
    }

    #[test]
    fn filename_safety() {
        assert!(safe_filename("a.jar").is_ok());
        assert!(safe_filename("../a.jar").is_err());
        assert!(safe_filename("a\\b.jar").is_err());
    }
}
