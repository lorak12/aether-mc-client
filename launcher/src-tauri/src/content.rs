//! Content: search, project pages, installs, updates, enable/disable, pack import and export.
//! Most commands take a `provider` ("modrinth" by default, or "curseforge").
use crate::{e, AppState, Res};
use aether_core::content::{self, Installer, Lock, LockEntry, LocalFile, Source};
use aether_core::curseforge;
use aether_core::modrinth::{Project, ProjectType, SearchQuery, SearchResponse, Version};
use aether_core::mrpack::{self, ExportOptions, ExportReport};
use aether_core::profile::Profile;
use serde::Serialize;
use tauri::State;

fn parse_kind(k: &str) -> Res<ProjectType> {
    serde_json::from_value(serde_json::Value::String(k.to_string())).map_err(e)
}

fn is_cf(provider: &Option<String>) -> bool {
    provider.as_deref() == Some("curseforge")
}

#[tauri::command]
pub async fn search_projects(s: State<'_, AppState>, q: SearchQuery, provider: Option<String>) -> Res<SearchResponse> {
    if is_cf(&provider) {
        return s.cf()?.search(&q).await.map_err(e);
    }
    s.api.search_query(&q).await.map_err(e)
}

#[tauri::command]
pub async fn project_details(s: State<'_, AppState>, id: String, provider: Option<String>) -> Res<Project> {
    if is_cf(&provider) {
        return s.cf()?.project(&id).await.map_err(e);
    }
    s.api.project(&id).await.map_err(e)
}

/// Versions of a project, newest first. With a profile, only versions that profile can use.
#[tauri::command]
pub async fn project_versions(s: State<'_, AppState>, project_id: String, kind: String, profile_id: Option<String>, provider: Option<String>) -> Res<Vec<Version>> {
    let kind = parse_kind(&kind)?;
    let profile = match profile_id {
        Some(id) => Some(s.profile(&id)?),
        None => None,
    };
    if is_cf(&provider) {
        let cf = s.cf()?;
        let files = match &profile {
            Some(p) => cf.files(&project_id, Some(&p.mc_version), curseforge::loader_for(kind, p.loader)).await,
            None => cf.files(&project_id, None, None).await,
        };
        return files.map(|f| f.iter().map(curseforge::CfFile::to_version).collect()).map_err(e);
    }
    match profile {
        Some(p) => s.api.versions(&project_id, &p.mc_version, &kind.loader_filter(p.loader.modrinth_id())).await.map_err(e),
        None => s.api.versions(&project_id, "", &[]).await.map_err(e),
    }
}

#[derive(Serialize)]
pub struct DepInfo {
    project_id: String,
    title: String,
    version: String,
}

#[derive(Serialize)]
pub struct InstallPlan {
    /// Version that would be installed for the project itself.
    version: String,
    /// Required dependencies that are not installed yet.
    dependencies: Vec<DepInfo>,
    /// Required dependencies with no compatible version.
    missing: Vec<String>,
}

fn installer<'a>(s: &'a AppState, profile: &'a Profile) -> Installer<'a> {
    Installer { api: &s.api, profile_dir: s.game_dir(profile), profile }
}

/// What installing would add, so the UI can ask before pulling in dependencies.
#[tauri::command]
pub async fn install_plan(
    s: State<'_, AppState>,
    profile_id: String,
    project_id: String,
    version_id: Option<String>,
    kind: String,
    provider: Option<String>,
) -> Res<InstallPlan> {
    let profile = s.profile(&profile_id)?;
    let kind = parse_kind(&kind)?;
    let (chosen, plan) = if is_cf(&provider) {
        curseforge::plan(s.cf()?, &profile, &s.game_dir(&profile), &project_id, version_id.as_deref(), kind).await.map_err(e)?
    } else {
        installer(&s, &profile).plan(&project_id, version_id.as_deref(), kind).await.map_err(e)?
    };
    let deps: Vec<&Version> = plan.install.iter().filter(|v| v.project_id != chosen.project_id).collect();
    let ids: Vec<String> = deps.iter().map(|v| v.project_id.clone()).collect();
    let titles: std::collections::HashMap<String, String> = if is_cf(&provider) {
        s.cf()?.mods(&ids).await.unwrap_or_default().into_iter().map(|m| (m.id.to_string(), m.name)).collect()
    } else {
        s.api.projects(&ids).await.unwrap_or_default().into_iter().map(|p| (p.id, p.title)).collect()
    };
    let dependencies = deps
        .iter()
        .map(|v| DepInfo { project_id: v.project_id.clone(), title: titles.get(&v.project_id).cloned().unwrap_or_else(|| v.name.clone()), version: v.version_number.clone() })
        .collect();
    Ok(InstallPlan { version: chosen.version_number, dependencies, missing: plan.missing })
}

/// Install a project into a profile. Without `version_id` the newest compatible version is used;
/// `with_deps = false` skips required dependencies.
#[tauri::command]
#[allow(clippy::too_many_arguments)]
pub async fn install_content(
    s: State<'_, AppState>,
    profile_id: String,
    project_id: String,
    kind: String,
    world: Option<String>,
    version_id: Option<String>,
    with_deps: Option<bool>,
    provider: Option<String>,
) -> Res<Vec<LockEntry>> {
    let profile = s.profile(&profile_id)?;
    let kind = parse_kind(&kind)?;
    let deps = with_deps.unwrap_or(true);
    let r = if is_cf(&provider) {
        curseforge::install(s.cf()?, &profile, &s.game_dir(&profile), &project_id, version_id.as_deref(), kind, world.as_deref(), deps).await
    } else {
        installer(&s, &profile).install(&project_id, version_id.as_deref(), kind, world.as_deref(), deps).await
    };
    let added = r.map_err(e)?;
    s.log(format!("installed {} into {}", added.iter().map(|a| a.title.as_str()).collect::<Vec<_>>().join(", "), profile.id));
    Ok(added)
}

/// Download a modpack and create a profile from it.
#[tauri::command]
pub async fn install_modpack(s: State<'_, AppState>, project_id: String, version_id: Option<String>, provider: Option<String>) -> Res<Profile> {
    let tmp = s.root.join("tmp");
    std::fs::create_dir_all(&tmp).map_err(e)?;
    if is_cf(&provider) {
        let cf = s.cf()?;
        let file = match version_id {
            Some(id) => cf.file(&id).await.map_err(e)?,
            None => {
                let files = cf.files(&project_id, None, None).await.map_err(e)?;
                files.iter().find(|f| f.release_type == 1).or(files.first()).cloned().ok_or("This modpack has no files")?
            }
        };
        let (Some(url), Some(sha1)) = (file.download_url.as_deref(), file.sha1()) else {
            return Err("This modpack's author doesn't allow downloads outside CurseForge.".into());
        };
        let path = tmp.join(format!("cf-{}.zip", file.id));
        aether_core::download::fetch_verified(cf.http(), url, &path, sha1).await.map_err(e)?;
        let r = curseforge::import(cf, &s.store(), &path).await.map_err(e);
        let _ = std::fs::remove_file(&path);
        return r;
    }
    let version = match version_id {
        Some(id) => s.api.version(&id).await.map_err(e)?,
        None => {
            let all = s.api.versions(&project_id, "", &[]).await.map_err(e)?;
            all.iter().find(|v| v.version_type == "release").or(all.first()).cloned().ok_or("This modpack has no versions")?
        }
    };
    let file = version.primary_file().ok_or("This modpack version has no file")?;
    let path = tmp.join(format!("{}.mrpack", version.id));
    aether_core::download::fetch_verified(s.api.http(), &file.url, &path, &file.hashes.sha1).await.map_err(e)?;
    let result = import_mrpack_file(&s, &path).await;
    let _ = std::fs::remove_file(&path);
    result
}

async fn import_mrpack_file(s: &AppState, path: &std::path::Path) -> Res<Profile> {
    let mut p = mrpack::import(s.api.http(), &s.store(), path).await.map_err(e)?;
    // Pinned loader version from the pack, modpack kind, and tracked files so updates and export work.
    if let Ok(idx) = mrpack::read_index(path) {
        if let Some(key) = mrpack::loader_key(p.loader) {
            p.loader_version = idx.dependencies.get(key).cloned();
        }
        p.meta.kind = aether_core::profile::ProfileKind::Modpack;
        let _ = s.store().save(&p);
    }
    if let Err(err) = content::adopt_local_files(&s.api, &s.game_dir(&p)).await {
        s.log(format!("couldn't identify modpack files: {err}"));
    }
    s.store().load(&p.id).map_err(e)
}

/// Import a `.mrpack` or a CurseForge modpack `.zip` from disk as a new profile.
#[tauri::command]
pub async fn import_pack(s: State<'_, AppState>, path: String) -> Res<Profile> {
    let p = std::path::PathBuf::from(&path);
    if mrpack::read_index(&p).is_ok() {
        return import_mrpack_file(&s, &p).await;
    }
    curseforge::import(s.cf()?, &s.store(), &p).await.map_err(e)
}

#[tauri::command]
pub fn list_installed(s: State<AppState>, profile_id: String) -> Res<Vec<LockEntry>> {
    let p = s.profile(&profile_id)?;
    Ok(Lock::load(&s.game_dir(&p)).entries)
}

/// Files in the content folders the launcher doesn't track (added by hand).
#[tauri::command]
pub fn list_local_files(s: State<AppState>, profile_id: String) -> Res<Vec<LocalFile>> {
    let p = s.profile(&profile_id)?;
    Ok(content::local_files(&s.game_dir(&p)))
}

/// Enable or disable a project (`id` = project id) or an untracked file (`file:<kind>:<name>`).
#[tauri::command]
pub async fn set_content_enabled(s: State<'_, AppState>, profile_id: String, id: String, enabled: bool) -> Res<()> {
    if s.running.lock().await.as_ref().is_some_and(|r| r.profile_id == profile_id) {
        return Err("Close the game first: it keeps its mods open.".into());
    }
    let p = s.profile(&profile_id)?;
    content::set_enabled(&s.game_dir(&p), &id, enabled).map_err(e)
}

#[tauri::command]
pub fn remove_local_file(s: State<AppState>, profile_id: String, id: String) -> Res<()> {
    let p = s.profile(&profile_id)?;
    content::remove_local(&s.game_dir(&p), &id).map_err(e)
}

/// Identify untracked files on Modrinth so they get updates. Returns how many were recognised.
#[tauri::command]
pub async fn adopt_local_files(s: State<'_, AppState>, profile_id: String) -> Res<usize> {
    let p = s.profile(&profile_id)?;
    content::adopt_local_files(&s.api, &s.game_dir(&p)).await.map_err(e)
}

#[tauri::command]
pub fn uninstall_content(s: State<AppState>, profile_id: String, project_id: String) -> Res<()> {
    let profile = s.profile(&profile_id)?;
    installer(&s, &profile).uninstall(&project_id).map_err(e)
}

#[derive(Serialize)]
pub struct UpdateInfo {
    project_id: String,
    title: String,
    current: String,
    latest: String,
}

async fn all_updates(s: &AppState, profile: &Profile) -> Res<Vec<(LockEntry, Version)>> {
    let mut ups = installer(s, profile).check_updates().await.map_err(e)?;
    let dir = s.game_dir(profile);
    if let Some(cf) = &s.cf {
        if Lock::load(&dir).entries.iter().any(|x| x.source == Source::Curseforge) {
            ups.extend(curseforge::check_updates(cf, profile, &dir).await.map_err(e)?);
        }
    }
    Ok(ups)
}

#[tauri::command]
pub async fn check_updates(s: State<'_, AppState>, profile_id: String) -> Res<Vec<UpdateInfo>> {
    let profile = s.profile(&profile_id)?;
    Ok(all_updates(&s, &profile)
        .await?
        .into_iter()
        .map(|(en, v)| UpdateInfo { project_id: en.project_id, title: en.title, current: en.version_id, latest: v.version_number })
        .collect())
}

async fn apply(s: &AppState, profile: &Profile, entry: &LockEntry, newer: &Version) -> Res<()> {
    if entry.source == Source::Curseforge && newer.primary_file().is_some_and(|f| f.url.is_empty()) {
        return Err(format!("{}'s author doesn't allow downloads outside CurseForge.", entry.title));
    }
    installer(s, profile).apply_update(entry, newer).await.map_err(e)
}

#[tauri::command]
pub async fn update_all(s: State<'_, AppState>, profile_id: String) -> Res<usize> {
    let profile = s.profile(&profile_id)?;
    let ups = all_updates(&s, &profile).await?;
    for (entry, newer) in &ups {
        apply(&s, &profile, entry, newer).await?;
    }
    Ok(ups.len())
}

/// Update one installed project to its newest compatible version. Returns false when it was already current.
#[tauri::command]
pub async fn update_content(s: State<'_, AppState>, profile_id: String, project_id: String) -> Res<bool> {
    let profile = s.profile(&profile_id)?;
    let ups = all_updates(&s, &profile).await?;
    let Some((entry, newer)) = ups.iter().find(|(en, _)| en.project_id == project_id) else { return Ok(false) };
    apply(&s, &profile, entry, newer).await?;
    Ok(true)
}

/// Folders and files that exist in the profile and can be added to an export.
#[tauri::command]
pub fn export_candidates(s: State<AppState>, profile_id: String) -> Res<Vec<String>> {
    let p = s.profile(&profile_id)?;
    Ok(mrpack::export_candidates(&s.game_dir(&p)))
}

/// Write the profile as a Modrinth pack to `dest` (picked in a save dialog).
#[tauri::command]
pub async fn export_mrpack(s: State<'_, AppState>, profile_id: String, dest: String, options: ExportOptions) -> Res<ExportReport> {
    let p = s.profile(&profile_id)?;
    // The pack needs a concrete loader version.
    let lv = match (&p.loader_version, p.loader) {
        (Some(v), _) => Some(v.clone()),
        (None, aether_core::profile::Loader::Vanilla) => None,
        (None, aether_core::profile::Loader::Forge | aether_core::profile::Loader::NeoForge) => {
            aether_core::forge::recommended(s.api.http(), p.loader, &p.mc_version).await.map_err(e)?
        }
        (None, l) => aether_core::loader::versions(s.api.http(), l, &p.mc_version).await.map_err(e)?.into_iter().find(|v| v.stable).map(|v| v.version),
    };
    let dest = std::path::PathBuf::from(dest);
    let dest = if dest.extension().is_some_and(|x| x == "mrpack") { dest } else { dest.with_extension("mrpack") };
    mrpack::export(&s.api, &p, &s.game_dir(&p), lv.as_deref(), &dest, &options).await.map_err(e)
}
