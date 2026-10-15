//! Tauri shell: thin IPC layer over `aether-core`. All logic lives in the core crate; these modules adapt it to
//! commands, events, the tray and the OS keychain.
mod accounts;
mod content;
mod files;
mod game;
mod online;
mod profiles;
mod shell;

use aether_core::backend::Backend;
use aether_core::config::AppConfig;
use aether_core::curseforge::CurseForge;
use aether_core::modrinth::Modrinth;
use aether_core::profile::{Profile, ProfileStore};
use aether_core::settings::SettingsStore;
use std::io::Write;
use std::path::PathBuf;
use tauri::Manager;
use tokio::sync::Mutex;

pub(crate) struct AppState {
    root: PathBuf,
    cfg: AppConfig,
    api: Modrinth,
    cf: Option<CurseForge>,
    backend: Option<Backend>,
    /// Device-code login in progress.
    pending_login: Mutex<Option<aether_core::auth::DeviceCode>>,
    /// Browser login in progress: set to cancel it.
    login_cancel: std::sync::Mutex<Option<tokio::sync::oneshot::Sender<()>>>,
    /// The game currently starting or running.
    running: Mutex<Option<game::Running>>,
    /// Aether backend sign-in, per account uuid.
    token: Mutex<Option<online::CachedToken>>,
    log: std::sync::Mutex<Option<std::fs::File>>,
}

impl AppState {
    fn store(&self) -> ProfileStore {
        ProfileStore::new(&self.root)
    }
    fn settings(&self) -> SettingsStore {
        SettingsStore::new(&self.root)
    }
    fn profile(&self, id: &str) -> Res<Profile> {
        self.store().load(id).map_err(e)
    }
    /// Where a profile's game files and content live.
    fn game_dir(&self, p: &Profile) -> PathBuf {
        self.store().game_dir(p)
    }
    fn cf(&self) -> Res<&CurseForge> {
        self.cf.as_ref().ok_or_else(|| "CurseForge needs an API key: set curseforge_api_key in aether.config.json (Settings > About shows where).".to_string())
    }
    /// Append a line to `<root>/logs/launcher.log` (what "Upload logs" shares) and stderr.
    fn log(&self, msg: impl AsRef<str>) {
        let line = format!("[{}] {}", aether_core::gamefiles::timestamp(now_unix()), msg.as_ref());
        eprintln!("{line}");
        if let Ok(mut f) = self.log.lock() {
            if let Some(f) = f.as_mut() {
                let _ = writeln!(f, "{line}");
            }
        }
    }
}

pub(crate) type Res<T> = Result<T, String>;
pub(crate) fn e<E: std::fmt::Display>(err: E) -> String {
    err.to_string()
}

/// Commands that change something (or start a long task): each call is written to the launcher log as an action trail.
/// Reads and polling (lists, searches, log tails, pings) stay out so the log remains readable.
const LOGGED_COMMANDS: &[&str] = &[
    "create_profile", "delete_profile", "duplicate_profile", "update_profile", "set_profile_cover", "set_settings",
    "install_content", "install_modpack", "import_pack", "set_content_enabled", "remove_local_file", "adopt_local_files",
    "uninstall_content", "update_all", "update_content", "export_mrpack", "delete_world", "backup_world", "restore_backup",
    "add_server", "remove_server", "delete_screenshot", "start_login", "finish_login", "browser_login", "cancel_login",
    "offline_login", "logout", "switch_account", "remove_account", "launch_profile", "kill_game", "upload_logs",
    "install_update", "restart_app",
];

pub(crate) fn now_unix() -> i64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs() as i64).unwrap_or(0)
}

/// Random hex from the OS RNG.
pub(crate) fn random_hex(bytes: usize) -> String {
    let mut b = vec![0u8; bytes];
    getrandom::fill(&mut b).expect("OS random source");
    b.iter().map(|x| format!("{x:02x}")).collect()
}

/// Start a new launcher log (the previous one is kept as `launcher.prev.log`).
fn open_log(root: &std::path::Path) -> Option<std::fs::File> {
    let dir = root.join("logs");
    std::fs::create_dir_all(&dir).ok()?;
    let path = dir.join("launcher.log");
    if path.exists() {
        let _ = std::fs::rename(&path, dir.join("launcher.prev.log"));
    }
    std::fs::File::create(path).ok()
}

/// Show and focus the main window (tray, game exit, second instance).
pub(crate) fn show_main(app: &tauri::AppHandle) {
    if let Some(w) = app.get_webview_window("main") {
        let _ = w.show();
        let _ = w.unminimize();
        let _ = w.set_focus();
    }
}

fn build_tray(app: &tauri::App) -> tauri::Result<()> {
    use tauri::menu::{Menu, MenuItem};
    use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
    let show = MenuItem::with_id(app, "show", "Show Aether", true, None::<&str>)?;
    let stop = MenuItem::with_id(app, "stop", "Stop the game", true, None::<&str>)?;
    let quit = MenuItem::with_id(app, "quit", "Quit", true, None::<&str>)?;
    let menu = Menu::with_items(app, &[&show, &stop, &quit])?;
    let mut tray = TrayIconBuilder::with_id("main").tooltip("Aether").menu(&menu).show_menu_on_left_click(false);
    if let Some(icon) = app.default_window_icon() {
        tray = tray.icon(icon.clone());
    }
    tray.on_menu_event(|app, ev| match ev.id().as_ref() {
        "show" => show_main(app),
        "stop" => {
            let app = app.clone();
            tauri::async_runtime::spawn(async move {
                let _ = game::kill(&app).await;
            });
        }
        "quit" => app.exit(0),
        _ => {}
    })
    .on_tray_icon_event(|tray, ev| {
        if let TrayIconEvent::Click { button: MouseButton::Left, button_state: MouseButtonState::Up, .. } = ev {
            show_main(tray.app_handle());
        }
    })
    .build(app)?;
    Ok(())
}

pub fn run() {
    let handler: Box<dyn Fn(tauri::ipc::Invoke<tauri::Wry>) -> bool + Send + Sync> = Box::new(tauri::generate_handler![
        // profiles and settings
        profiles::config_status,
        profiles::list_profiles,
        profiles::create_profile,
        profiles::delete_profile,
        profiles::duplicate_profile,
        profiles::update_profile,
        profiles::set_profile_cover,
        profiles::get_settings,
        profiles::set_settings,
        profiles::mc_versions,
        profiles::loader_versions,
        profiles::aether_versions,
        profiles::list_javas,
        profiles::probe_java,
        // content
        content::search_projects,
        content::project_details,
        content::project_versions,
        content::install_plan,
        content::install_content,
        content::install_modpack,
        content::list_installed,
        content::list_local_files,
        content::set_content_enabled,
        content::remove_local_file,
        content::adopt_local_files,
        content::uninstall_content,
        content::check_updates,
        content::update_all,
        content::update_content,
        content::import_pack,
        content::export_candidates,
        content::export_mrpack,
        // worlds, servers, screenshots
        files::list_worlds,
        files::delete_world,
        files::backup_world,
        files::list_backups,
        files::restore_backup,
        files::list_servers,
        files::add_server,
        files::remove_server,
        files::ping_server,
        files::list_screenshots,
        files::delete_screenshot,
        files::copy_screenshot,
        // accounts
        accounts::start_login,
        accounts::finish_login,
        accounts::browser_login,
        accounts::cancel_login,
        accounts::offline_login,
        accounts::logout,
        accounts::account_name,
        accounts::list_accounts,
        accounts::switch_account,
        accounts::remove_account,
        accounts::skin_url,
        // game
        game::launch_profile,
        game::kill_game,
        game::running_profile,
        game::read_game_log,
        game::upload_logs,
        // online
        online::backend_call,
        online::backend_status,
        online::check_update,
        online::install_update,
        // shell
        shell::open_path,
        shell::open_url,
        shell::reveal_path,
        shell::restart_app,
        shell::open_logs_window,
        shell::system_memory_mb,
        shell::app_info,
        shell::log_line,
    ]);
    tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_clipboard_manager::init())
        .setup(|app| {
            let root = app.path().app_data_dir()?;
            std::fs::create_dir_all(&root)?;
            AppConfig::ensure_file(&root)?;
            let cfg = AppConfig::load(&root);
            let api = Modrinth::new()?;
            let cf = cfg.curseforge_api_key.as_deref().and_then(|k| CurseForge::new(k).ok());
            let backend = cfg.backend_url.as_deref().map(|u| Backend::new(api.http().clone(), u));
            let state = AppState {
                log: std::sync::Mutex::new(open_log(&root)),
                root,
                cfg,
                api,
                cf,
                backend,
                pending_login: Mutex::new(None),
                login_cancel: std::sync::Mutex::new(None),
                running: Mutex::new(None),
                token: Mutex::new(None),
            };
            state.log(format!("Aether {} starting; data folder {}", env!("CARGO_PKG_VERSION"), state.root.display()));
            state.log(format!(
                "system: {} {}, {} logical CPUs; backend {}; CurseForge {}",
                std::env::consts::OS,
                std::env::consts::ARCH,
                std::thread::available_parallelism().map(|n| n.get()).unwrap_or(0),
                state.cfg.backend_url.as_deref().unwrap_or("not configured"),
                if state.cf.is_some() { "enabled" } else { "disabled (no API key)" }
            ));
            // Screenshots, world icons and covers in custom game folders are served through the asset protocol too.
            if let Ok(list) = state.store().list() {
                for p in list.iter().filter(|p| p.game_dir.is_some()) {
                    let _ = app.asset_protocol_scope().allow_directory(state.game_dir(p), true);
                }
            }
            app.manage(state);
            if let Err(err) = build_tray(app) {
                eprintln!("tray icon unavailable: {err}");
            }
            Ok(())
        })
        .invoke_handler(move |invoke| {
            let cmd = invoke.message.command();
            if LOGGED_COMMANDS.contains(&cmd) {
                if let Some(state) = invoke.message.webview_ref().try_state::<AppState>() {
                    state.log(format!("action: {cmd}"));
                }
            }
            handler(invoke)
        })
        .run(tauri::generate_context!())
        .expect("error while running Aether");
}
