//! CurseForge API v1 client (needs an API key from console.curseforge.com). Results are mapped onto the Modrinth
//! types so the UI and the dependency resolver handle both providers the same way. Ids are CurseForge's numeric ids
//! as strings: project = mod id, version = file id.
use crate::config::USER_AGENT;
use crate::content::{dest_dir, resolve, safe_filename, Lock, LockEntry, Source, VersionSource};
use crate::download::fetch_verified;
use crate::modrinth::{Dependency, GalleryImage, Hashes, Project, ProjectType, SearchHit, SearchQuery, SearchResponse, Version, VersionFile};
use crate::profile::{Loader, Profile};
use crate::{Error, Result};
use serde::Deserialize;
use std::collections::{HashMap, HashSet};
use std::path::Path;

const BASE: &str = "https://api.curseforge.com/v1";
const MINECRAFT: u32 = 432;

pub fn class_id(kind: ProjectType) -> u32 {
    match kind {
        ProjectType::Mod => 6,
        ProjectType::Resourcepack => 12,
        ProjectType::Modpack => 4471,
        ProjectType::Shader => 6552,
        ProjectType::Datapack => 6945,
    }
}

fn kind_of_class(class: u32) -> ProjectType {
    match class {
        12 => ProjectType::Resourcepack,
        4471 => ProjectType::Modpack,
        6552 => ProjectType::Shader,
        6945 => ProjectType::Datapack,
        _ => ProjectType::Mod,
    }
}

/// CurseForge `modLoaderType`.
pub fn loader_type(name: &str) -> Option<u32> {
    match name.to_lowercase().as_str() {
        "forge" => Some(1),
        "fabric" => Some(4),
        "quilt" => Some(5),
        "neoforge" => Some(6),
        _ => None,
    }
}

const LOADER_NAMES: &[&str] = &["forge", "fabric", "quilt", "neoforge"];

#[derive(Debug, Deserialize)]
struct Data<T> {
    data: T,
}

#[derive(Debug, Deserialize)]
struct Paged<T> {
    data: Vec<T>,
    pagination: Pagination,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Pagination {
    total_count: u64,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CfMod {
    pub id: u64,
    pub name: String,
    pub slug: String,
    #[serde(default)]
    pub summary: String,
    #[serde(default)]
    pub download_count: f64,
    #[serde(default)]
    pub thumbs_up_count: u64,
    #[serde(default)]
    pub class_id: Option<u32>,
    #[serde(default)]
    pub logo: Option<CfAsset>,
    #[serde(default)]
    pub screenshots: Vec<CfAsset>,
    #[serde(default)]
    pub authors: Vec<CfAuthor>,
    #[serde(default)]
    pub categories: Vec<CfCategory>,
    #[serde(default)]
    pub links: Option<CfLinks>,
    #[serde(default)]
    pub date_modified: String,
    #[serde(default)]
    pub date_released: String,
    #[serde(default)]
    pub latest_files_indexes: Vec<CfFileIndex>,
    #[serde(default)]
    pub allow_mod_distribution: Option<bool>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CfAsset {
    #[serde(default)]
    pub url: String,
    #[serde(default)]
    pub thumbnail_url: String,
    #[serde(default)]
    pub title: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct CfAuthor {
    pub name: String,
}

#[derive(Debug, Clone, Deserialize)]
pub struct CfCategory {
    #[serde(default)]
    pub slug: String,
    #[serde(default)]
    pub name: String,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CfLinks {
    #[serde(default)]
    pub website_url: Option<String>,
    #[serde(default)]
    pub wiki_url: Option<String>,
    #[serde(default)]
    pub issues_url: Option<String>,
    #[serde(default)]
    pub source_url: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CfFileIndex {
    pub game_version: String,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CfFile {
    pub id: u64,
    pub mod_id: u64,
    pub display_name: String,
    pub file_name: String,
    /// 1 release, 2 beta, 3 alpha
    #[serde(default)]
    pub release_type: u32,
    #[serde(default)]
    pub file_date: String,
    #[serde(default)]
    pub file_length: u64,
    /// `None` when the author doesn't allow downloads outside CurseForge.
    #[serde(default)]
    pub download_url: Option<String>,
    #[serde(default)]
    pub game_versions: Vec<String>,
    #[serde(default)]
    pub dependencies: Vec<CfDependency>,
    #[serde(default)]
    pub hashes: Vec<CfHash>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CfDependency {
    pub mod_id: u64,
    /// 1 embedded, 2 optional, 3 required, 4 tool, 5 incompatible, 6 include
    pub relation_type: u32,
}

#[derive(Debug, Clone, Deserialize)]
pub struct CfHash {
    pub value: String,
    /// 1 = sha1, 2 = md5
    pub algo: u32,
}

impl CfFile {
    pub fn sha1(&self) -> Option<&str> {
        self.hashes.iter().find(|h| h.algo == 1).map(|h| h.value.as_str())
    }

    pub fn to_version(&self) -> Version {
        let loaders: Vec<String> = self.game_versions.iter().map(|v| v.to_lowercase()).filter(|v| LOADER_NAMES.contains(&v.as_str())).collect();
        let game_versions: Vec<String> =
            self.game_versions.iter().filter(|v| v.chars().next().is_some_and(|c| c.is_ascii_digit())).cloned().collect();
        Version {
            id: self.id.to_string(),
            project_id: self.mod_id.to_string(),
            name: self.display_name.clone(),
            version_number: self.display_name.clone(),
            version_type: match self.release_type {
                2 => "beta",
                3 => "alpha",
                _ => "release",
            }
            .into(),
            game_versions,
            loaders,
            files: vec![VersionFile {
                url: self.download_url.clone().unwrap_or_default(),
                filename: self.file_name.clone(),
                primary: true,
                size: self.file_length,
                hashes: Hashes { sha1: self.sha1().unwrap_or_default().to_string(), sha512: None },
            }],
            dependencies: self
                .dependencies
                .iter()
                .filter_map(|d| {
                    let t = match d.relation_type {
                        3 => "required",
                        2 => "optional",
                        5 => "incompatible",
                        1 | 6 => "embedded",
                        _ => return None,
                    };
                    Some(Dependency { version_id: None, project_id: Some(d.mod_id.to_string()), dependency_type: t.into() })
                })
                .collect(),
            date_published: self.file_date.clone(),
        }
    }
}

impl CfMod {
    fn to_hit(&self, kind: ProjectType) -> SearchHit {
        let mut versions: Vec<String> = self.latest_files_indexes.iter().map(|i| i.game_version.clone()).collect();
        versions.dedup();
        SearchHit {
            project_id: self.id.to_string(),
            slug: self.slug.clone(),
            title: self.name.clone(),
            description: self.summary.clone(),
            icon_url: self.logo.as_ref().map(|l| if l.thumbnail_url.is_empty() { l.url.clone() } else { l.thumbnail_url.clone() }),
            downloads: self.download_count as u64,
            author: self.authors.first().map(|a| a.name.clone()).unwrap_or_default(),
            categories: self.categories.iter().map(|c| c.slug.clone()).collect(),
            project_type: self.class_id.map(kind_of_class).unwrap_or(kind).as_str().into(),
            display_categories: self.categories.iter().map(|c| c.name.clone()).collect(),
            follows: self.thumbs_up_count,
            date_modified: self.date_modified.clone(),
            featured_gallery: self.screenshots.first().map(|s| s.url.clone()),
            versions,
        }
    }
}

#[derive(Clone)]
pub struct CurseForge {
    client: reqwest::Client,
    key: String,
}

impl CurseForge {
    pub fn new(key: &str) -> Result<Self> {
        Ok(Self { client: reqwest::Client::builder().user_agent(USER_AGENT).build()?, key: key.to_string() })
    }

    pub fn http(&self) -> &reqwest::Client {
        &self.client
    }

    async fn get<T: serde::de::DeserializeOwned>(&self, path: &str, query: &[(&str, String)]) -> Result<T> {
        let r = self.client.get(format!("{BASE}{path}")).header("x-api-key", &self.key).query(query).send().await?;
        if r.status() == reqwest::StatusCode::FORBIDDEN || r.status() == reqwest::StatusCode::UNAUTHORIZED {
            return Err(Error::Other("CurseForge rejected the API key. Check curseforge_api_key in aether.config.json.".into()));
        }
        Ok(r.error_for_status()?.json().await?)
    }

    async fn post<T: serde::de::DeserializeOwned>(&self, path: &str, body: serde_json::Value) -> Result<T> {
        let r = self.client.post(format!("{BASE}{path}")).header("x-api-key", &self.key).json(&body).send().await?;
        Ok(r.error_for_status()?.json().await?)
    }

    pub async fn search(&self, q: &SearchQuery) -> Result<SearchResponse> {
        let kind = q.kind.unwrap_or(ProjectType::Mod);
        let sort = match q.sort.as_deref() {
            Some("downloads") => 6,
            Some("newest") => 11,
            Some("updated") => 3,
            Some("follows") => 12,
            _ => 2,
        };
        let mut query: Vec<(&str, String)> = vec![
            ("gameId", MINECRAFT.to_string()),
            ("classId", class_id(kind).to_string()),
            ("searchFilter", q.query.clone()),
            ("sortField", sort.to_string()),
            ("sortOrder", "desc".into()),
            ("index", q.offset.min(9_950).to_string()),
            ("pageSize", (if q.limit == 0 { 20 } else { q.limit.min(50) }).to_string()),
        ];
        if let Some(v) = q.game_versions.first() {
            query.push(("gameVersion", v.clone()));
        }
        if matches!(kind, ProjectType::Mod | ProjectType::Modpack) {
            if let Some(t) = q.loaders.iter().find_map(|l| loader_type(l)) {
                query.push(("modLoaderType", t.to_string()));
            }
        }
        let r: Paged<CfMod> = self.get("/mods/search", &query).await?;
        Ok(SearchResponse { hits: r.data.iter().map(|m| m.to_hit(kind)).collect(), total_hits: r.pagination.total_count.min(10_000) })
    }

    pub async fn get_mod(&self, id: &str) -> Result<CfMod> {
        Ok(self.get::<Data<CfMod>>(&format!("/mods/{}", num(id)?), &[]).await?.data)
    }

    pub async fn mods(&self, ids: &[String]) -> Result<Vec<CfMod>> {
        let ids: Vec<u64> = ids.iter().filter_map(|i| i.parse().ok()).collect();
        if ids.is_empty() {
            return Ok(vec![]);
        }
        Ok(self.post::<Data<Vec<CfMod>>>("/mods", serde_json::json!({ "modIds": ids })).await?.data)
    }

    /// Project page data. `body` is HTML (`body_html` is set) because that's what CurseForge stores.
    pub async fn project(&self, id: &str) -> Result<Project> {
        let m = self.get_mod(id).await?;
        let body: String = self.get::<Data<String>>(&format!("/mods/{}/description", m.id), &[]).await.map(|d| d.data).unwrap_or_default();
        let mut game_versions: Vec<String> = m.latest_files_indexes.iter().map(|i| i.game_version.clone()).collect();
        game_versions.sort();
        game_versions.dedup();
        let links = m.links.clone();
        Ok(Project {
            id: m.id.to_string(),
            slug: m.slug.clone(),
            title: m.name.clone(),
            description: m.summary.clone(),
            project_type: m.class_id.map(kind_of_class).unwrap_or(ProjectType::Mod).as_str().into(),
            icon_url: m.logo.as_ref().map(|l| l.url.clone()),
            body,
            body_html: true,
            downloads: m.download_count as u64,
            followers: m.thumbs_up_count,
            published: m.date_released.clone(),
            updated: m.date_modified.clone(),
            categories: m.categories.iter().map(|c| c.name.clone()).collect(),
            loaders: vec![],
            game_versions,
            versions: vec![],
            gallery: m.screenshots.iter().enumerate().map(|(i, s)| GalleryImage { url: s.url.clone(), featured: i == 0, title: s.title.clone() }).collect(),
            source_url: links.as_ref().and_then(|l| l.source_url.clone()),
            issues_url: links.as_ref().and_then(|l| l.issues_url.clone()),
            wiki_url: links.as_ref().and_then(|l| l.wiki_url.clone()),
            discord_url: None,
            page_url: links.as_ref().and_then(|l| l.website_url.clone()),
        })
    }

    /// Files of a mod, newest first, optionally filtered by game version and loader.
    pub async fn files(&self, mod_id: &str, mc: Option<&str>, loader: Option<u32>) -> Result<Vec<CfFile>> {
        let mut q: Vec<(&str, String)> = vec![("pageSize", "50".into())];
        if let Some(v) = mc.filter(|v| !v.is_empty()) {
            q.push(("gameVersion", v.into()));
        }
        if let Some(l) = loader {
            q.push(("modLoaderType", l.to_string()));
        }
        let mut files = self.get::<Paged<CfFile>>(&format!("/mods/{}/files", num(mod_id)?), &q).await?.data;
        files.sort_by(|a, b| b.file_date.cmp(&a.file_date));
        Ok(files)
    }

    pub async fn files_by_ids(&self, ids: &[u64]) -> Result<Vec<CfFile>> {
        if ids.is_empty() {
            return Ok(vec![]);
        }
        Ok(self.post::<Data<Vec<CfFile>>>("/mods/files", serde_json::json!({ "fileIds": ids })).await?.data)
    }

    pub async fn file(&self, file_id: &str) -> Result<CfFile> {
        self.files_by_ids(&[num(file_id)?]).await?.into_iter().next().ok_or_else(|| Error::Other("CurseForge file not found".into()))
    }
}

fn num(id: &str) -> Result<u64> {
    id.parse().map_err(|_| Error::Other(format!("not a CurseForge id: {id}")))
}

/// Loader filter for a project type in a profile.
pub fn loader_for(kind: ProjectType, loader: Loader) -> Option<u32> {
    match kind {
        ProjectType::Mod | ProjectType::Modpack => loader.modrinth_id().and_then(loader_type),
        _ => None,
    }
}

struct CfSource<'a> {
    cf: &'a CurseForge,
    mc: &'a str,
    loader: Option<u32>,
}

impl VersionSource for CfSource<'_> {
    async fn version_by_id(&self, id: &str) -> Result<Version> {
        Ok(self.cf.file(id).await?.to_version())
    }
    async fn latest_for_project(&self, project_id: &str) -> Result<Option<Version>> {
        let files = self.cf.files(project_id, Some(self.mc), self.loader).await?;
        Ok(files.iter().find(|f| f.release_type == 1).or(files.first()).map(CfFile::to_version))
    }
}

fn not_distributable(title: &str) -> Error {
    Error::Other(format!("{title}'s author doesn't allow downloads outside CurseForge. Download it from its CurseForge page and drop it in the folder."))
}

/// The file to install for a project: `file_id`, or the newest compatible release (else newest of any type).
pub async fn choose(cf: &CurseForge, profile: &Profile, project_id: &str, file_id: Option<&str>, kind: ProjectType) -> Result<CfFile> {
    if let Some(id) = file_id {
        return cf.file(id).await;
    }
    let files = cf.files(project_id, Some(&profile.mc_version), loader_for(kind, profile.loader)).await?;
    files
        .iter()
        .find(|f| f.release_type == 1)
        .or(files.first())
        .cloned()
        .ok_or_else(|| Error::Other(format!("No version of this project works with Minecraft {} ({:?})", profile.mc_version, profile.loader)))
}

/// What installing would add (dependencies resolved), without downloading.
pub async fn plan(cf: &CurseForge, profile: &Profile, game_dir: &Path, project_id: &str, file_id: Option<&str>, kind: ProjectType) -> Result<(Version, crate::content::Plan)> {
    let chosen = choose(cf, profile, project_id, file_id, kind).await?.to_version();
    let installed: HashSet<String> = Lock::load(game_dir).entries.iter().map(|e| e.project_id.clone()).collect();
    let src = CfSource { cf, mc: &profile.mc_version, loader: loader_for(ProjectType::Mod, profile.loader) };
    let plan = resolve(&src, chosen.clone(), &installed).await?;
    Ok((chosen, plan))
}

/// Install a CurseForge project (and its required dependencies) into a profile.
#[allow(clippy::too_many_arguments)]
pub async fn install(
    cf: &CurseForge,
    profile: &Profile,
    game_dir: &Path,
    project_id: &str,
    file_id: Option<&str>,
    kind: ProjectType,
    world: Option<&str>,
    with_deps: bool,
) -> Result<Vec<LockEntry>> {
    let (chosen, plan) = plan(cf, profile, game_dir, project_id, file_id, kind).await?;
    if let Some(c) = plan.conflicts.first() {
        return Err(Error::Other(format!("conflicts with installed project {c}")));
    }
    if with_deps {
        if let Some(m) = plan.missing.first() {
            return Err(Error::Other(format!("required dependency {m} has no compatible version")));
        }
    }
    let todo: Vec<Version> = if with_deps { plan.install } else { vec![chosen.clone()] };
    let ids: Vec<String> = todo.iter().map(|v| v.project_id.clone()).collect();
    let titles: HashMap<String, String> = cf.mods(&ids).await.unwrap_or_default().into_iter().map(|m| (m.id.to_string(), m.name)).collect();
    let mut lock = Lock::load(game_dir);
    let mut added = Vec::new();
    for v in todo {
        let is_root = v.project_id == chosen.project_id;
        let title = titles.get(&v.project_id).cloned().unwrap_or_else(|| v.name.clone());
        let file = v.primary_file().ok_or_else(|| Error::Other("version has no files".into()))?;
        if file.url.is_empty() {
            return Err(not_distributable(&title));
        }
        if file.hashes.sha1.is_empty() {
            return Err(Error::Other(format!("CurseForge has no checksum for {title}; not installing an unverified file")));
        }
        let name = safe_filename(&file.filename)?;
        let k = if is_root { kind } else { ProjectType::Mod };
        let dir = dest_dir(game_dir, k, world)?;
        fetch_verified(cf.http(), &file.url, &dir.join(name), &file.hashes.sha1).await?;
        lock.entries.retain(|e| e.project_id != v.project_id);
        let entry = LockEntry {
            project_id: v.project_id.clone(),
            version_id: v.id.clone(),
            title,
            project_type: k,
            filename: name.to_string(),
            sha1: file.hashes.sha1.clone(),
            explicit: is_root,
            world: if is_root { world.map(String::from) } else { None },
            enabled: true,
            source: Source::Curseforge,
        };
        lock.entries.push(entry.clone());
        added.push(entry);
    }
    lock.save(game_dir)?;
    Ok(added)
}

/// CurseForge entries with a newer compatible file: (entry, newer version).
pub async fn check_updates(cf: &CurseForge, profile: &Profile, game_dir: &Path) -> Result<Vec<(LockEntry, Version)>> {
    let lock = Lock::load(game_dir);
    let mut out = Vec::new();
    for e in lock.entries.iter().filter(|e| e.source == Source::Curseforge) {
        let files = cf.files(&e.project_id, Some(&profile.mc_version), loader_for(e.project_type, profile.loader)).await?;
        let newest = files.iter().find(|f| f.release_type == 1).or(files.first());
        if let Some(f) = newest {
            // File ids only grow, so a bigger id is a newer upload.
            if f.id > e.version_id.parse().unwrap_or(u64::MAX) {
                out.push((e.clone(), f.to_version()));
            }
        }
    }
    Ok(out)
}

// ---------- modpacks ----------

#[derive(Debug, Deserialize)]
pub struct Manifest {
    pub name: String,
    pub minecraft: ManifestMc,
    #[serde(default)]
    pub files: Vec<ManifestFile>,
    #[serde(default = "overrides")]
    pub overrides: String,
}

fn overrides() -> String {
    "overrides".into()
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ManifestMc {
    pub version: String,
    #[serde(default)]
    pub mod_loaders: Vec<ManifestLoader>,
}

#[derive(Debug, Deserialize)]
pub struct ManifestLoader {
    /// e.g. "forge-47.3.0", "fabric-0.16.10", "neoforge-21.1.172"
    pub id: String,
    #[serde(default)]
    pub primary: bool,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ManifestFile {
    #[serde(rename = "projectID")]
    pub project_id: u64,
    #[serde(rename = "fileID")]
    pub file_id: u64,
    #[serde(default = "yes")]
    pub required: bool,
}

fn yes() -> bool {
    true
}

impl Manifest {
    /// (loader, version) from the primary mod loader entry.
    pub fn loader(&self) -> (Loader, Option<String>) {
        let l = self.minecraft.mod_loaders.iter().find(|l| l.primary).or(self.minecraft.mod_loaders.first());
        let Some(l) = l else { return (Loader::Vanilla, None) };
        let (name, ver) = l.id.split_once('-').unwrap_or((l.id.as_str(), ""));
        let loader = match name {
            "forge" => Loader::Forge,
            "neoforge" => Loader::NeoForge,
            "fabric" => Loader::Fabric,
            "quilt" => Loader::Quilt,
            _ => Loader::Vanilla,
        };
        (loader, Some(ver.to_string()).filter(|v| !v.is_empty()))
    }
}

/// Import a CurseForge modpack zip as a new profile.
pub async fn import(cf: &CurseForge, store: &crate::profile::ProfileStore, zip_path: &Path) -> Result<Profile> {
    use std::io::Read;
    let mut zip = zip::ZipArchive::new(std::fs::File::open(zip_path)?)?;
    let manifest: Manifest = {
        let mut f = zip.by_name("manifest.json").map_err(|_| Error::Other("This isn't a CurseForge modpack (no manifest.json)".into()))?;
        let mut b = Vec::new();
        f.read_to_end(&mut b)?;
        serde_json::from_slice(&b)?
    };
    let (loader, lv) = manifest.loader();
    let mut profile = store.create(&manifest.name, &manifest.minecraft.version, loader)?;
    profile.loader_version = lv;
    profile.meta.kind = crate::profile::ProfileKind::Modpack;
    store.save(&profile)?;
    let dir = store.dir(&profile.id);

    let wanted: Vec<&ManifestFile> = manifest.files.iter().filter(|f| f.required).collect();
    let files = {
        let mut all = Vec::new();
        for chunk in wanted.iter().map(|f| f.file_id).collect::<Vec<_>>().chunks(500) {
            all.extend(cf.files_by_ids(chunk).await?);
        }
        all
    };
    let mods: HashMap<u64, CfMod> = {
        let ids: Vec<String> = wanted.iter().map(|f| f.project_id.to_string()).collect();
        let mut m = HashMap::new();
        for chunk in ids.chunks(500) {
            for x in cf.mods(chunk).await? {
                m.insert(x.id, x);
            }
        }
        m
    };
    let mut blocked = Vec::new();
    let mut lock = Lock::load(&dir);
    for f in &files {
        let m = mods.get(&f.mod_id);
        let kind = m.and_then(|m| m.class_id).map(kind_of_class).unwrap_or(ProjectType::Mod);
        let title = m.map(|m| m.name.clone()).unwrap_or_else(|| f.display_name.clone());
        let (Some(url), Some(sha1)) = (f.download_url.as_deref(), f.sha1()) else {
            blocked.push(title);
            continue;
        };
        let name = safe_filename(&f.file_name)?;
        let target = dest_dir(&dir, if kind == ProjectType::Datapack || kind == ProjectType::Modpack { ProjectType::Mod } else { kind }, None)?;
        fetch_verified(cf.http(), url, &target.join(name), sha1).await?;
        lock.entries.push(LockEntry {
            project_id: f.mod_id.to_string(),
            version_id: f.id.to_string(),
            title,
            project_type: kind,
            filename: name.to_string(),
            sha1: sha1.to_string(),
            explicit: true,
            world: None,
            enabled: true,
            source: Source::Curseforge,
        });
    }
    lock.save(&dir)?;

    let prefix = format!("{}/", manifest.overrides.trim_end_matches('/'));
    for i in 0..zip.len() {
        let mut e = zip.by_index(i)?;
        if e.is_dir() {
            continue;
        }
        let name = e.name().to_string();
        let Some(rest) = name.strip_prefix(&prefix) else { continue };
        let Some(rel) = crate::mrpack::safe_relative(rest) else { continue };
        let out = dir.join(rel);
        if let Some(p) = out.parent() {
            std::fs::create_dir_all(p)?;
        }
        std::io::copy(&mut e, &mut std::fs::File::create(out)?)?;
    }
    if !blocked.is_empty() {
        profile.meta.description = format!(
            "Download these from CurseForge yourself (their authors block other launchers) and put them in the mods folder: {}",
            blocked.join(", ")
        );
        store.save(&profile)?;
    }
    Ok(profile)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn file_maps_to_version() {
        let f: CfFile = serde_json::from_str(
            r#"{"id":5000,"modId":238222,"displayName":"JEI 15.2","fileName":"jei.jar","releaseType":2,"fileDate":"2024-01-01T00:00:00Z",
            "fileLength":10,"downloadUrl":"https://edge.forgecdn.net/files/5/0/jei.jar","gameVersions":["1.20.1","Forge","Client","NeoForge"],
            "dependencies":[{"modId":1,"relationType":3},{"modId":2,"relationType":2},{"modId":3,"relationType":4}],
            "hashes":[{"value":"md5x","algo":2},{"value":"abc","algo":1}]}"#,
        )
        .unwrap();
        let v = f.to_version();
        assert_eq!((v.id.as_str(), v.project_id.as_str(), v.version_type.as_str()), ("5000", "238222", "beta"));
        assert_eq!(v.loaders, ["forge", "neoforge"]);
        assert_eq!(v.game_versions, ["1.20.1"]);
        assert_eq!(v.files[0].hashes.sha1, "abc");
        assert_eq!(v.dependencies.len(), 2);
        assert_eq!(v.dependencies[0].dependency_type, "required");
    }

    #[test]
    fn manifest_loader() {
        let m: Manifest = serde_json::from_str(
            r#"{"name":"P","minecraft":{"version":"1.20.1","modLoaders":[{"id":"forge-47.3.0","primary":true}]},"files":[{"projectID":1,"fileID":2}]}"#,
        )
        .unwrap();
        assert_eq!(m.loader(), (Loader::Forge, Some("47.3.0".into())));
        assert_eq!(m.overrides, "overrides");
        assert!(m.files[0].required);
    }

    #[test]
    fn ids() {
        assert_eq!(class_id(ProjectType::Shader), 6552);
        assert_eq!(loader_for(ProjectType::Mod, Loader::NeoForge), Some(6));
        assert_eq!(loader_for(ProjectType::Resourcepack, Loader::Fabric), None);
        assert!(num("abc").is_err());
    }
}
