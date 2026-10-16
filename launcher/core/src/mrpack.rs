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

// ---------- export ----------

/// What goes into an exported pack besides the tracked downloads.
#[derive(Debug, Clone, Deserialize)]
pub struct ExportOptions {
    pub name: String,
    /// The pack's own version, e.g. "1.0.0".
    pub version: String,
    #[serde(default)]
    pub summary: String,
    /// Extra files and folders (relative to the game folder) copied into `overrides/`, e.g. `config`, `options.txt`.
    #[serde(default)]
    pub include: Vec<String>,
}

#[derive(Debug, Clone, serde::Serialize)]
pub struct ExportReport {
    pub path: String,
    /// Files the pack downloads from Modrinth.
    pub downloads: usize,
    /// Files stored inside the pack.
    pub overrides: usize,
}

/// The `dependencies` key for a loader in `modrinth.index.json`.
pub fn loader_key(loader: Loader) -> Option<&'static str> {
    match loader {
        Loader::Fabric => Some("fabric-loader"),
        Loader::Quilt => Some("quilt-loader"),
        Loader::Forge => Some("forge"),
        Loader::NeoForge => Some("neoforge"),
        Loader::Vanilla => None,
    }
}

/// Folders and files offered for inclusion (those that exist in the game folder).
pub fn export_candidates(game_dir: &Path) -> Vec<String> {
    ["config", "options.txt", "servers.dat", "defaultconfigs", "kubejs", "resourcepacks", "shaderpacks", "saves", "schematics"]
        .iter()
        .filter(|p| game_dir.join(p).exists())
        .map(|s| s.to_string())
        .collect()
}

fn add_path(zip: &mut zip::ZipWriter<std::fs::File>, src: &Path, rel: &str, opts: zip::write::SimpleFileOptions, count: &mut usize) -> Result<()> {
    if src.is_dir() {
        for e in std::fs::read_dir(src)?.flatten() {
            let name = e.file_name().to_string_lossy().to_string();
            if name == "session.lock" || name.ends_with(crate::content::DISABLED_SUFFIX) {
                continue;
            }
            add_path(zip, &e.path(), &format!("{rel}/{name}"), opts, count)?;
        }
    } else if src.is_file() {
        zip.start_file(format!("overrides/{rel}"), opts)?;
        std::io::copy(&mut std::fs::File::open(src)?, zip)?;
        *count += 1;
    }
    Ok(())
}

/// Write a Modrinth pack of `profile` to `dest`. Tracked, enabled Modrinth content becomes downloads; untracked files
/// and the chosen extra paths go into `overrides/`. Disabled content is left out.
pub async fn export(
    api: &crate::modrinth::Modrinth,
    profile: &Profile,
    game_dir: &Path,
    loader_version: Option<&str>,
    dest: &Path,
    opts: &ExportOptions,
) -> Result<ExportReport> {
    use crate::content::{local_files, Lock};
    use crate::modrinth::ProjectType;
    let lock = Lock::load(game_dir);
    let tracked: Vec<_> = lock.entries.iter().filter(|e| e.enabled && e.project_type != ProjectType::Datapack).collect();
    let mut versions = HashMap::new();
    let ids: Vec<String> = tracked.iter().map(|e| e.version_id.clone()).collect();
    for chunk in ids.chunks(100) {
        for v in api.versions_by_ids(chunk).await? {
            versions.insert(v.id.clone(), v);
        }
    }

    let mut files = Vec::new();
    let mut as_override: Vec<(PathBuf, String)> = Vec::new();
    for e in &tracked {
        let rel = format!("{}/{}", e.project_type.folder(), e.filename);
        let file = versions.get(&e.version_id).and_then(|v| v.files.iter().find(|f| f.hashes.sha1.eq_ignore_ascii_case(&e.sha1)));
        match file {
            Some(f) => files.push(serde_json::json!({
                "path": rel,
                "hashes": { "sha1": f.hashes.sha1, "sha512": f.hashes.sha512.clone().unwrap_or_default() },
                "env": { "client": "required", "server": if e.project_type == ProjectType::Mod { "required" } else { "unsupported" } },
                "downloads": [f.url],
                "fileSize": f.size,
            })),
            None => as_override.push((game_dir.join(&rel), rel)),
        }
    }
    for f in local_files(game_dir).into_iter().filter(|f| f.enabled) {
        let rel = format!("{}/{}", f.project_type.folder(), f.filename);
        as_override.push((game_dir.join(&rel), rel));
    }

    let mut deps = serde_json::Map::new();
    deps.insert("minecraft".into(), profile.mc_version.clone().into());
    if let (Some(key), Some(v)) = (loader_key(profile.loader), loader_version) {
        deps.insert(key.into(), v.into());
    }
    let mut index = serde_json::json!({
        "formatVersion": 1,
        "game": "minecraft",
        "versionId": opts.version,
        "name": opts.name,
        "files": files,
        "dependencies": deps,
    });
    if !opts.summary.trim().is_empty() {
        index["summary"] = opts.summary.trim().into();
    }

    if let Some(p) = dest.parent() {
        std::fs::create_dir_all(p)?;
    }
    let part = dest.with_extension("mrpack.part");
    let mut overrides = 0;
    {
        let mut zip = zip::ZipWriter::new(std::fs::File::create(&part)?);
        let o = zip::write::SimpleFileOptions::default().compression_method(zip::CompressionMethod::Deflated);
        zip.start_file("modrinth.index.json", o)?;
        std::io::Write::write_all(&mut zip, serde_json::to_string_pretty(&index)?.as_bytes())?;
        for (src, rel) in &as_override {
            add_path(&mut zip, src, rel, o, &mut overrides)?;
        }
        // Folders also hold tracked content; only copy what isn't a download already.
        let downloaded: std::collections::HashSet<String> =
            index["files"].as_array().into_iter().flatten().filter_map(|f| f["path"].as_str().map(String::from)).collect();
        for inc in &opts.include {
            let rel = safe_relative(inc).ok_or_else(|| Error::Other(format!("can't include {inc}")))?;
            let rel_str = rel.to_string_lossy().replace('\\', "/");
            let src = game_dir.join(&rel);
            if matches!(rel_str.as_str(), "mods" | "resourcepacks" | "shaderpacks") {
                // Content folders: add only files that aren't downloads or already-added overrides.
                if let Ok(rd) = std::fs::read_dir(&src) {
                    for e in rd.flatten() {
                        let r = format!("{rel_str}/{}", e.file_name().to_string_lossy());
                        if !downloaded.contains(&r) && !as_override.iter().any(|(_, x)| *x == r) {
                            add_path(&mut zip, &e.path(), &r, o, &mut overrides)?;
                        }
                    }
                }
            } else {
                add_path(&mut zip, &src, &rel_str, o, &mut overrides)?;
            }
        }
        zip.finish()?;
    }
    std::fs::rename(&part, dest)?;
    Ok(ExportReport { path: dest.display().to_string(), downloads: index["files"].as_array().map_or(0, |a| a.len()), overrides })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    #[tokio::test]
    async fn export_writes_overrides_and_index() {
        let root = std::env::temp_dir().join(format!("kite-export-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        let store = ProfileStore::new(&root);
        let p = store.create("Pack", "1.21.11", Loader::Fabric).unwrap();
        let g = store.dir(&p.id);
        std::fs::write(g.join("mods/hand.jar"), b"jar").unwrap();
        std::fs::write(g.join("mods/off.jar.disabled"), b"off").unwrap();
        std::fs::write(g.join("config/a.toml"), b"x=1").unwrap();
        std::fs::write(g.join("options.txt"), b"fov:1").unwrap();
        let dest = root.join("out/Pack.mrpack");
        let opts = ExportOptions { name: "Pack".into(), version: "1.0.0".into(), summary: "".into(), include: vec!["config".into(), "options.txt".into()] };
        let api = crate::modrinth::Modrinth::new().unwrap();
        let r = export(&api, &p, &g, Some("0.17.2"), &dest, &opts).await.unwrap();
        assert_eq!((r.downloads, r.overrides), (0, 3));
        let idx = read_index(&dest).unwrap();
        assert_eq!(idx.mc_version().unwrap(), "1.21.11");
        assert_eq!(idx.loader(), Loader::Fabric);
        assert_eq!(idx.dependencies["fabric-loader"], "0.17.2");
        let mut zip = zip::ZipArchive::new(std::fs::File::open(&dest).unwrap()).unwrap();
        assert!(zip.by_name("overrides/mods/hand.jar").is_ok());
        assert!(zip.by_name("overrides/config/a.toml").is_ok());
        assert!(zip.by_name("overrides/mods/off.jar.disabled").is_err());
        assert!(export(&api, &p, &g, None, &dest, &ExportOptions { include: vec!["../x".into()], ..opts }).await.is_err());
        let _ = std::fs::remove_dir_all(&root);
    }

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
        let root = std::env::temp_dir().join(format!("kite-mrpack-{}", std::process::id()));
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
