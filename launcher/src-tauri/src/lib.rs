//! Tauri shell: thin IPC layer over `aether-core`. All logic lives in the core crate.
use aether_core::auth::{self, DeviceCode};
use aether_core::config::{AppConfig, ConfigStatus};
use aether_core::content::{Installer, Lock, LockEntry};
use aether_core::download::Layout;
use aether_core::launch;
use aether_core::modrinth::{Modrinth, ProjectType, SearchResponse};
use aether_core::profile::{Loader, Profile, ProfileStore};
use aether_core::version::Manifest;
use serde::Serialize;
use std::path::PathBuf;
use tauri::{AppHandle, Emitter, Manager, State};
use tokio::sync::Mutex;

struct AppState {
    root: PathBuf,
    cfg: AppConfig,
    api: Modrinth,
    /// Device-code login in progress.
    pending_login: Mutex<Option<DeviceCode>>,
    /// Profile id of the game currently starting or running.
    running: Mutex<Option<String>>,
}

impl AppState {
    fn store(&self) -> ProfileStore {
        ProfileStore::new(&self.root)
    }
}

type Res<T> = Result<T, String>;
fn e<E: std::fmt::Display>(err: E) -> String {
    err.to_string()
}

#[tauri::command]
fn config_status(s: State<AppState>) -> ConfigStatus {
    s.cfg.status()
}

#[tauri::command]
fn list_profiles(s: State<AppState>) -> Res<Vec<Profile>> {
    s.store().list().map_err(e)
}

#[tauri::command]
fn create_profile(s: State<AppState>, name: String, mc_version: String, loader: Loader) -> Res<Profile> {
    s.store().create(&name, &mc_version, loader).map_err(e)
}

#[tauri::command]
fn delete_profile(s: State<AppState>, id: String) -> Res<()> {
    s.store().delete(&id).map_err(e)
}

#[tauri::command]
fn duplicate_profile(s: State<AppState>, id: String) -> Res<Profile> {
    s.store().duplicate(&id).map_err(e)
}

#[tauri::command]
fn update_profile(s: State<AppState>, profile: Profile) -> Res<()> {
    // Only the mutable settings may change from the UI; id/version/loader stay as stored.
    let store = s.store();
    let mut cur = store.load(&profile.id).map_err(e)?;
    cur.name = profile.name;
    cur.memory_mb = profile.memory_mb.clamp(512, 65536);
    cur.jvm_args = profile.jvm_args;
    store.save(&cur).map_err(e)
}

#[derive(Serialize)]
struct McVersion {
    id: String,
    kind: String,
}

/// Release versions plus any explicitly requested ids (the client's supported list may include snapshots-style ids).
#[tauri::command]
async fn mc_versions(s: State<'_, AppState>) -> Res<Vec<McVersion>> {
    let m = Manifest::fetch(s.api.http()).await.map_err(e)?;
    Ok(m.versions.into_iter().filter(|v| v.kind == "release").map(|v| McVersion { id: v.id, kind: v.kind }).collect())
}

fn parse_kind(k: &str) -> Res<ProjectType> {
    serde_json::from_value(serde_json::Value::String(k.to_string())).map_err(e)
}

#[tauri::command]
async fn search_content(
    s: State<'_, AppState>,
    query: String,
    kind: String,
    profile_id: Option<String>,
    offset: u32,
) -> Res<SearchResponse> {
    let kind = parse_kind(&kind)?;
    let (mc, loader) = match profile_id {
        Some(id) => {
            let p = s.store().load(&id).map_err(e)?;
            (Some(p.mc_version), p.loader.modrinth_id().map(String::from))
        }
        None => (None, None),
    };
    s.api.search(&query, kind, mc.as_deref(), loader.as_deref(), offset, 20).await.map_err(e)
}

#[tauri::command]
async fn install_content(
    s: State<'_, AppState>,
    profile_id: String,
    project_id: String,
    kind: String,
    world: Option<String>,
) -> Res<Vec<LockEntry>> {
    let profile = s.store().load(&profile_id).map_err(e)?;
    let inst = Installer { api: &s.api, profile_dir: s.store().dir(&profile.id), profile: &profile };
    inst.install_project(&project_id, parse_kind(&kind)?, world.as_deref()).await.map_err(e)
}

#[tauri::command]
fn list_installed(s: State<AppState>, profile_id: String) -> Res<Vec<LockEntry>> {
    s.store().load(&profile_id).map_err(e)?;
    Ok(Lock::load(&s.store().dir(&profile_id)).entries)
}

#[tauri::command]
fn uninstall_content(s: State<AppState>, profile_id: String, project_id: String) -> Res<()> {
    let profile = s.store().load(&profile_id).map_err(e)?;
    let api = &s.api;
    Installer { api, profile_dir: s.store().dir(&profile.id), profile: &profile }.uninstall(&project_id).map_err(e)
}

#[derive(Serialize)]
struct UpdateInfo {
    project_id: String,
    title: String,
    current: String,
    latest: String,
}

#[tauri::command]
async fn check_updates(s: State<'_, AppState>, profile_id: String) -> Res<Vec<UpdateInfo>> {
    let profile = s.store().load(&profile_id).map_err(e)?;
    let inst = Installer { api: &s.api, profile_dir: s.store().dir(&profile.id), profile: &profile };
    let ups = inst.check_updates().await.map_err(e)?;
    Ok(ups
        .into_iter()
        .map(|(en, v)| UpdateInfo { project_id: en.project_id, title: en.title, current: en.version_id, latest: v.version_number })
        .collect())
}

#[tauri::command]
async fn update_all(s: State<'_, AppState>, profile_id: String) -> Res<usize> {
    let profile = s.store().load(&profile_id).map_err(e)?;
    let inst = Installer { api: &s.api, profile_dir: s.store().dir(&profile.id), profile: &profile };
    let ups = inst.check_updates().await.map_err(e)?;
    for (entry, newer) in &ups {
        inst.apply_update(entry, newer).await.map_err(e)?;
    }
    Ok(ups.len())
}

#[tauri::command]
async fn import_mrpack(s: State<'_, AppState>, path: String) -> Res<Profile> {
    aether_core::mrpack::import(s.api.http(), &s.store(), std::path::Path::new(&path)).await.map_err(e)
}

const KEYRING_SERVICE: &str = "aether-launcher";
/// Windows Credential Manager caps a secret at 2560 bytes; Microsoft refresh tokens can exceed that, so store in chunks.
const KEYRING_CHUNK: usize = 2000;

#[tauri::command]
async fn start_login(s: State<'_, AppState>) -> Res<DeviceCode> {
    let id = s.cfg.azure().map_err(e)?;
    let code = auth::start_device_login(s.api.http(), id).await.map_err(e)?;
    *s.pending_login.lock().await = Some(code.clone());
    Ok(code)
}

#[derive(Serialize)]
struct AccountInfo {
    username: String,
    uuid: String,
}

fn chunk_entry(uuid: &str, i: usize) -> keyring::Result<keyring::Entry> {
    keyring::Entry::new(KEYRING_SERVICE, &format!("{uuid}#{i}"))
}

/// Delete chunks `from..` until one is missing.
fn delete_chunks(uuid: &str, from: usize) {
    let mut i = from;
    while let Ok(entry) = chunk_entry(uuid, i) {
        if entry.delete_credential().is_err() {
            break;
        }
        i += 1;
    }
}

fn store_refresh(uuid: &str, token: &str) -> Res<()> {
    let chunks: Vec<&[u8]> = token.as_bytes().chunks(KEYRING_CHUNK).collect();
    for (i, c) in chunks.iter().enumerate() {
        chunk_entry(uuid, i).and_then(|en| en.set_secret(c)).map_err(|err| format!("couldn't save sign-in to the OS keychain: {err}"))?;
    }
    delete_chunks(uuid, chunks.len());
    Ok(())
}

fn load_refresh(uuid: &str) -> Option<String> {
    let mut bytes = Vec::new();
    for i in 0.. {
        match chunk_entry(uuid, i).and_then(|en| en.get_secret()) {
            Ok(c) => bytes.extend(c),
            Err(_) => break,
        }
    }
    String::from_utf8(bytes).ok().filter(|t| !t.is_empty())
}

fn active_uuid(root: &std::path::Path) -> Option<String> {
    std::fs::read_to_string(root.join("active_account")).ok().map(|u| u.trim().to_string()).filter(|u| !u.is_empty())
}

#[tauri::command]
async fn finish_login(s: State<'_, AppState>) -> Res<AccountInfo> {
    let id = s.cfg.azure().map_err(e)?.to_string();
    let code = s.pending_login.lock().await.clone().ok_or("no login in progress")?;
    let tokens = auth::poll_device_login(s.api.http(), &id, &code).await.map_err(e)?;
    let session = auth::minecraft_session(s.api.http(), &tokens.access_token).await.map_err(e)?;
    let refresh = tokens.refresh_token.as_deref().ok_or("Microsoft returned no refresh token; try signing in again")?;
    store_refresh(&session.uuid, refresh)?;
    // remember which account is active
    std::fs::write(s.root.join("active_account"), &session.uuid).map_err(e)?;
    std::fs::write(s.root.join("active_account_name"), &session.username).map_err(e)?;
    Ok(AccountInfo { username: session.username, uuid: session.uuid })
}

#[tauri::command]
fn logout(s: State<AppState>) -> Res<()> {
    if let Some(uuid) = active_uuid(&s.root) {
        delete_chunks(&uuid, 0);
    }
    let _ = std::fs::remove_file(s.root.join("active_account"));
    let _ = std::fs::remove_file(s.root.join("active_account_name"));
    Ok(())
}

#[tauri::command]
fn account_name(s: State<AppState>) -> Option<String> {
    active_uuid(&s.root)?;
    // Signed in but the name isn't cached yet (it is after the next launch): empty string, not None.
    Some(std::fs::read_to_string(s.root.join("active_account_name")).map(|n| n.trim().to_string()).unwrap_or_default())
}

#[derive(Clone, Serialize)]
struct LaunchProgress {
    stage: String,
    done: usize,
    total: usize,
}

#[derive(Clone, Serialize)]
struct GameExit {
    code: Option<i32>,
    /// Last lines of output when the game crashed.
    log_tail: Option<String>,
}

fn log_tail(path: &std::path::Path, lines: usize) -> String {
    let text = std::fs::read(path).map(|b| String::from_utf8_lossy(&b).into_owned()).unwrap_or_default();
    // Mojang's log4j config emits XML events for launchers; keep just the messages and raw stderr lines.
    let all: Vec<&str> = text
        .lines()
        .filter_map(|l| {
            let t = l.trim();
            if let Some(start) = t.find("<![CDATA[") {
                let msg = &t[start + 9..];
                Some(msg.split("]]>").next().unwrap_or(msg))
            } else if t.starts_with("<log4j:") || t.starts_with("</log4j:") {
                None
            } else {
                Some(l)
            }
        })
        .collect();
    all[all.len().saturating_sub(lines)..].join("\n")
}

/// Refresh the Microsoft session, install whatever the profile needs, and start Minecraft.
/// Emits `launch-progress` while preparing and `game-exit` when the game closes. Returns the player name.
#[tauri::command]
async fn launch_profile(app: AppHandle, s: State<'_, AppState>, profile_id: String) -> Res<String> {
    {
        let mut running = s.running.lock().await;
        if running.is_some() {
            return Err("Minecraft is already running".into());
        }
        *running = Some(profile_id.clone());
    }
    let result = start_game(&app, &s, &profile_id).await;
    if result.is_err() {
        *s.running.lock().await = None;
    }
    result
}

async fn start_game(app: &AppHandle, s: &AppState, profile_id: &str) -> Res<String> {
    let store = s.store();
    let profile = store.load(profile_id).map_err(e)?;
    let http = s.api.http();

    let emit = |stage: &str, done: usize, total: usize| {
        // Throttle: assets alone are thousands of files.
        if done == 0 || done == total || done % (total / 50).max(1) == 0 {
            let _ = app.emit("launch-progress", LaunchProgress { stage: stage.into(), done, total });
        }
    };

    emit("account", 0, 1);
    let client_id = s.cfg.azure().map_err(e)?;
    let uuid = active_uuid(&s.root).ok_or("Sign in with Microsoft first (Settings)")?;
    let token = load_refresh(&uuid).ok_or("Your sign-in expired or was removed. Sign in again in Settings.")?;
    let account = auth::login_with_refresh(http, client_id, &token)
        .await
        .map_err(|err| format!("Couldn't refresh your Microsoft sign-in ({err}). Sign in again in Settings."))?;
    if let Some(r) = &account.refresh_token {
        store_refresh(&uuid, r)?;
    }
    let username = account.session.username.clone();
    let _ = std::fs::write(s.root.join("active_account_name"), &username);

    let game_dir = store.dir(&profile.id);
    let layout = Layout::new(&s.root);
    let argv = launch::prepare(http, &layout, &profile, game_dir.clone(), account.session, emit).await.map_err(e)?;

    let logs = game_dir.join("logs");
    std::fs::create_dir_all(&logs).map_err(e)?;
    let log_path = logs.join("launcher-output.log");
    let log = std::fs::File::create(&log_path).map_err(e)?;
    let mut child = std::process::Command::new(&argv[0])
        .args(&argv[1..])
        .current_dir(&game_dir)
        .stdin(std::process::Stdio::null())
        .stdout(log.try_clone().map_err(e)?)
        .stderr(log)
        .spawn()
        .map_err(|err| format!("couldn't start Java ({}): {err}", argv[0]))?;

    let _ = app.emit("launch-progress", LaunchProgress { stage: "running".into(), done: 1, total: 1 });
    let app = app.clone();
    tauri::async_runtime::spawn_blocking(move || {
        let code = child.wait().ok().and_then(|st| st.code());
        let crashed = code != Some(0);
        let log_tail = crashed.then(|| log_tail(&log_path, 40));
        let state = app.state::<AppState>();
        *state.running.blocking_lock() = None;
        let _ = app.emit("game-exit", GameExit { code, log_tail });
    });
    Ok(username)
}

#[tauri::command]
async fn running_profile(s: State<'_, AppState>) -> Res<Option<String>> {
    Ok(s.running.lock().await.clone())
}

#[tauri::command]
fn has_account(s: State<AppState>) -> bool {
    active_uuid(&s.root).is_some()
}

pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .setup(|app| {
            let root = app.path().app_data_dir()?;
            std::fs::create_dir_all(&root)?;
            AppConfig::ensure_file(&root)?;
            eprintln!("Aether config file: {}", root.join("aether.config.json").display());
            let cfg = AppConfig::load(&root);
            app.manage(AppState { root, cfg, api: Modrinth::new()?, pending_login: Mutex::new(None), running: Mutex::new(None) });
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            config_status,
            list_profiles,
            create_profile,
            delete_profile,
            duplicate_profile,
            update_profile,
            mc_versions,
            search_content,
            install_content,
            list_installed,
            uninstall_content,
            check_updates,
            update_all,
            import_mrpack,
            start_login,
            finish_login,
            logout,
            has_account,
            account_name,
            launch_profile,
            running_profile,
        ])
        .run(tauri::generate_context!())
        .expect("error while running Aether");
}
