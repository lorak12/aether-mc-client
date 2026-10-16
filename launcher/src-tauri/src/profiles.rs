//! Profiles, global settings, version lists and Java detection.
use crate::{e, AppState, Res};
use kite_core::config::ConfigStatus;
use kite_core::java::{self, JavaInstall};
use kite_core::loader::{self, LoaderVersion};
use kite_core::profile::{Loader, Profile};
use kite_core::settings::GameSettings;
use kite_core::version::Manifest;
use serde::Serialize;
use tauri::{AppHandle, Manager, State};

#[tauri::command]
pub fn config_status(s: State<AppState>) -> ConfigStatus {
    s.cfg.status()
}

#[tauri::command]
pub fn list_profiles(s: State<AppState>) -> Res<Vec<Profile>> {
    s.store().list().map_err(e)
}

#[tauri::command]
pub fn create_profile(s: State<AppState>, name: String, mc_version: String, loader: Loader) -> Res<Profile> {
    s.store().create(&name, &mc_version, loader).map_err(e)
}

#[tauri::command]
pub async fn delete_profile(s: State<'_, AppState>, id: String) -> Res<()> {
    if s.running.lock().await.as_ref().is_some_and(|r| r.profile_id == id) {
        return Err("Close the game before deleting the profile it runs.".into());
    }
    s.store().delete(&id).map_err(e)
}

#[tauri::command]
pub fn duplicate_profile(s: State<AppState>, id: String) -> Res<Profile> {
    s.store().duplicate(&id).map_err(e)
}

/// Save what the UI edits: name, version, loader (+ pinned version), custom folder, overrides and display data.
/// The id, creation time and play stats stay as stored.
#[tauri::command]
pub fn update_profile(app: AppHandle, s: State<AppState>, profile: Profile) -> Res<Profile> {
    let store = s.store();
    let mut cur = store.load(&profile.id).map_err(e)?;
    let name = profile.name.trim();
    if !name.is_empty() {
        cur.name = name.chars().take(64).collect();
    }
    let changed_game = (!profile.mc_version.trim().is_empty() && profile.mc_version != cur.mc_version) || profile.loader != cur.loader;
    if !profile.mc_version.trim().is_empty() {
        cur.mc_version = profile.mc_version;
    }
    cur.loader = profile.loader;
    // A pinned loader version belongs to one game version and loader.
    cur.loader_version = if changed_game && profile.loader_version == cur.loader_version { None } else { profile.loader_version.filter(|v| !v.trim().is_empty()) };
    cur.game_dir = match profile.game_dir.map(|d| d.trim().to_string()).filter(|d| !d.is_empty()) {
        Some(d) => {
            let p = std::path::PathBuf::from(&d);
            if !p.is_absolute() {
                return Err("The game folder must be a full path".into());
            }
            std::fs::create_dir_all(&p).map_err(|err| format!("Couldn't use {d}: {err}"))?;
            let _ = app.asset_protocol_scope().allow_directory(&p, true);
            Some(d)
        }
        None => None,
    };
    cur.overrides = profile.overrides;
    let stats = (cur.meta.last_played, cur.meta.plays);
    cur.meta = profile.meta;
    cur.meta.description = cur.meta.description.chars().take(2000).collect();
    // Play stats are the launcher's own; the UI only fills them in once (moving data from older versions).
    if stats != (None, 0) {
        (cur.meta.last_played, cur.meta.plays) = stats;
    }
    if let Some(name) = cur.meta.cover.as_deref().and_then(|c| c.strip_prefix("file:")) {
        if name.contains(['/', '\\']) || !store.dir(&cur.id).join(name).is_file() {
            cur.meta.cover = None;
        }
    }
    store.save(&cur).map_err(e)?;
    store.load(&cur.id).map_err(e)
}

fn cover_files(dir: &std::path::Path) -> Vec<std::path::PathBuf> {
    std::fs::read_dir(dir)
        .map(|rd| rd.flatten().map(|e| e.path()).filter(|p| p.file_name().and_then(|n| n.to_str()).is_some_and(|n| n.starts_with("cover"))).collect())
        .unwrap_or_default()
}

/// Copy an image into the profile as its cover. Returns the updated profile.
#[tauri::command]
pub fn set_profile_cover(s: State<AppState>, id: String, path: String) -> Res<Profile> {
    let store = s.store();
    let mut p = store.load(&id).map_err(e)?;
    let src = std::path::PathBuf::from(&path);
    let ext = src.extension().and_then(|x| x.to_str()).map(|x| x.to_lowercase()).unwrap_or_default();
    if !["png", "jpg", "jpeg", "webp", "gif"].contains(&ext.as_str()) {
        return Err("Pick a PNG, JPG, WebP or GIF image".into());
    }
    let size = std::fs::metadata(&src).map_err(e)?.len();
    if size > 8 * 1024 * 1024 {
        return Err("That image is over 8 MB; pick a smaller one".into());
    }
    let dir = store.dir(&id);
    for old in cover_files(&dir) {
        let _ = std::fs::remove_file(old);
    }
    // A new name each time, so the webview doesn't show a cached old cover.
    let name = format!("cover-{}.{ext}", crate::now_unix());
    std::fs::copy(&src, dir.join(&name)).map_err(e)?;
    p.meta.cover = Some(format!("file:{name}"));
    store.save(&p).map_err(e)?;
    store.load(&id).map_err(e)
}

#[tauri::command]
pub fn get_settings(s: State<AppState>) -> GameSettings {
    s.settings().load()
}

#[tauri::command]
pub fn set_settings(s: State<AppState>, game: GameSettings) -> Res<GameSettings> {
    s.settings().save(&game).map_err(e)?;
    Ok(s.settings().load())
}

#[derive(Serialize)]
pub struct McVersion {
    id: String,
    kind: String,
}

#[tauri::command]
pub async fn mc_versions(s: State<'_, AppState>) -> Res<Vec<McVersion>> {
    let m = Manifest::fetch(s.api.http()).await.map_err(e)?;
    Ok(m.versions.into_iter().filter(|v| v.kind == "release").map(|v| McVersion { id: v.id, kind: v.kind }).collect())
}

#[tauri::command]
pub async fn loader_versions(s: State<'_, AppState>, loader: Loader, mc_version: String) -> Res<Vec<LoaderVersion>> {
    loader::versions(s.api.http(), loader, &mc_version).await.map_err(e)
}

/// Game versions the Kite client runs on (Fabric and Quilt).
#[tauri::command]
pub fn kite_versions() -> Vec<&'static str> {
    kite_core::runtime::SUPPORTED.iter().map(|(v, _)| *v).collect()
}

/// Java installations found on this computer, with their versions.
#[tauri::command]
pub async fn list_javas(s: State<'_, AppState>) -> Res<Vec<JavaInstall>> {
    let layout = kite_core::download::Layout::new(&s.root);
    let found = futures::future::join_all(java::candidates(&layout).into_iter().map(|p| async move { java::probe(&p).await.ok() })).await;
    let mut list: Vec<JavaInstall> = found.into_iter().flatten().collect();
    list.sort_by(|a, b| b.major.cmp(&a.major).then(a.path.cmp(&b.path)));
    Ok(list)
}

#[tauri::command]
pub async fn probe_java(path: String) -> Res<JavaInstall> {
    java::probe(std::path::Path::new(&path)).await.map_err(e)
}
