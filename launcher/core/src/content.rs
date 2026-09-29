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
fn safe_filename(name: &str) -> Result<&str> {
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
        let loaders = self.loaders(kind);
        let versions = self.api.versions(project_id, &self.profile.mc_version, &loaders).await?;
        // prefer release over beta/alpha
        let chosen = versions
            .iter()
            .find(|v| v.version_type == "release")
            .or_else(|| versions.first())
            .cloned()
            .ok_or_else(|| Error::Other(format!("no version of {project_id} for {} ({:?})", self.profile.mc_version, loaders)))?;

        let mut lock = Lock::load(&self.profile_dir);
        let installed: HashSet<String> = lock.entries.iter().map(|e| e.project_id.clone()).collect();
        let src = ModrinthSource { api: self.api, mc_version: &self.profile.mc_version, loaders };
        let plan = resolve(&src, chosen.clone(), &installed).await?;
        if let Some(c) = plan.conflicts.first() {
            return Err(Error::Other(format!("conflicts with installed project {c}")));
        }
        if let Some(m) = plan.missing.first() {
            return Err(Error::Other(format!("required dependency {m} has no compatible version")));
        }

        let mut added = Vec::new();
        for v in plan.install {
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
        let _ = std::fs::remove_file(dir.join(safe_filename(&e.filename)?));
        lock.save(&self.profile_dir)
    }

    /// Entries with a newer compatible version available: (entry, newer version).
    pub async fn check_updates(&self) -> Result<Vec<(LockEntry, Version)>> {
        let lock = Lock::load(&self.profile_dir);
        let mut out = Vec::new();
        for kind in [ProjectType::Mod, ProjectType::Resourcepack, ProjectType::Shader, ProjectType::Datapack] {
            let entries: Vec<&LockEntry> = lock.entries.iter().filter(|e| e.project_type == kind).collect();
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
        fetch_verified(self.api.http(), &file.url, &dir.join(name), &file.hashes.sha1).await?;
        if entry.filename != name {
            let _ = std::fs::remove_file(dir.join(safe_filename(&entry.filename)?));
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
    fn filename_safety() {
        assert!(safe_filename("a.jar").is_ok());
        assert!(safe_filename("../a.jar").is_err());
        assert!(safe_filename("a\\b.jar").is_err());
    }
}
