//! OS integration: opening folders and links, the live log window, system information.
use crate::{e, AppState, Res};
use serde::Serialize;
use std::path::{Path, PathBuf};
use tauri::{AppHandle, Manager, State};

/// A line from the UI for the launcher log (failed actions), shortened so a huge error can't flood it.
#[tauri::command]
pub fn log_line(s: State<AppState>, line: String) {
    let mut line = line.replace(['\r', '\n'], " ");
    if line.len() > 600 {
        let mut cut = 600;
        while !line.is_char_boundary(cut) {
            cut -= 1;
        }
        line.truncate(cut);
        line.push_str("...");
    }
    s.log(format!("ui: {line}"));
}

/// Open a folder, file or URL with the system handler.
pub fn open_with_system(target: &str) -> Res<()> {
    #[cfg(target_os = "windows")]
    let mut cmd = {
        // `explorer` mangles URLs with `&`; `rundll32 url.dll` passes them through intact.
        let mut c = if target.starts_with("http") { std::process::Command::new("rundll32") } else { std::process::Command::new("explorer") };
        if target.starts_with("http") {
            c.args(["url.dll,FileProtocolHandler", target]);
        } else {
            c.arg(target);
        }
        c
    };
    #[cfg(target_os = "macos")]
    let mut cmd = {
        let mut c = std::process::Command::new("open");
        c.arg(target);
        c
    };
    #[cfg(all(unix, not(target_os = "macos")))]
    let mut cmd = {
        let mut c = std::process::Command::new("xdg-open");
        c.arg(target);
        c
    };
    cmd.spawn().map(|_| ()).map_err(e)
}

/// Folders the UI can open. Profile folders need `profile_id`.
#[tauri::command]
pub fn open_path(s: State<AppState>, kind: String, profile_id: Option<String>) -> Res<()> {
    let dir = match kind.as_str() {
        "app" => s.root.clone(),
        "launcher-logs" => s.root.join("logs"),
        "config-file" => {
            return reveal(&s.root.join("kite.config.json"));
        }
        k => {
            let id = profile_id.ok_or("Pick a profile first")?;
            let p = s.profile(&id)?;
            let g = s.game_dir(&p);
            match k {
                "profile" => g,
                "client-logs" => g.join("logs"),
                "saves" | "screenshots" | "mods" | "resourcepacks" | "shaderpacks" | "config" | "crash-reports" | "backups" => g.join(k),
                other => return Err(format!("unknown folder {other}")),
            }
        }
    };
    std::fs::create_dir_all(&dir).map_err(e)?;
    open_with_system(&dir.to_string_lossy())
}

/// Paths the UI may reveal: inside the data folder or a profile's game folder.
fn allowed(s: &AppState, p: &Path) -> bool {
    let Ok(p) = p.canonicalize() else { return false };
    let mut roots: Vec<PathBuf> = vec![s.root.clone()];
    if let Ok(list) = s.store().list() {
        roots.extend(list.iter().map(|x| s.game_dir(x)));
    }
    roots.iter().filter_map(|r| r.canonicalize().ok()).any(|r| p.starts_with(r))
}

fn reveal(p: &Path) -> Res<()> {
    #[cfg(target_os = "windows")]
    {
        std::process::Command::new("explorer").arg(format!("/select,{}", p.display())).spawn().map(|_| ()).map_err(e)
    }
    #[cfg(target_os = "macos")]
    {
        std::process::Command::new("open").arg("-R").arg(p).spawn().map(|_| ()).map_err(e)
    }
    #[cfg(all(unix, not(target_os = "macos")))]
    {
        open_with_system(&p.parent().unwrap_or(p).to_string_lossy())
    }
}

/// Show a file in the file manager (a crash report, an exported pack, a backup).
#[tauri::command]
pub fn reveal_path(s: State<AppState>, path: String) -> Res<()> {
    let p = PathBuf::from(&path);
    if !p.exists() {
        return Err("That file doesn't exist any more".into());
    }
    if !allowed(&s, &p) && !p.extension().is_some_and(|x| x == "mrpack") {
        return Err("That file is outside the launcher's folders".into());
    }
    reveal(&p)
}

#[tauri::command]
pub fn open_url(url: String) -> Res<()> {
    if !(url.starts_with("https://") || url.starts_with("http://")) {
        return Err("Only web links can be opened".into());
    }
    open_with_system(&url)
}

#[tauri::command]
pub fn restart_app(app: AppHandle) {
    app.restart();
}

/// Show the live log window (a second frameless window told apart by its "logs" label).
/// Must be async: building a webview window from a sync command deadlocks WebView2 on Windows
/// (the window appears but stays blank white forever).
#[tauri::command]
pub async fn open_logs_window(app: AppHandle) -> Res<()> {
    if let Some(w) = app.get_webview_window("logs") {
        let _ = w.show();
        let _ = w.unminimize();
        return w.set_focus().map_err(e);
    }
    tauri::WebviewWindowBuilder::new(&app, "logs", tauri::WebviewUrl::App("index.html".into()))
        .title("Kite Client — Live logs")
        .inner_size(920.0, 580.0)
        .min_inner_size(560.0, 320.0)
        .decorations(false)
        .build()
        .map(|_| ())
        .map_err(e)
}

#[derive(Serialize)]
pub struct AppInfo {
    version: &'static str,
    data_dir: String,
    config_file: String,
    os: String,
}

#[tauri::command]
pub fn app_info(s: State<AppState>) -> AppInfo {
    AppInfo {
        version: env!("CARGO_PKG_VERSION"),
        data_dir: s.root.display().to_string(),
        config_file: s.root.join("kite.config.json").display().to_string(),
        os: format!("{} {}", std::env::consts::OS, std::env::consts::ARCH),
    }
}

/// Installed RAM in MB, for the memory slider. Falls back to 16 GB when unknown.
#[tauri::command]
pub fn system_memory_mb() -> u64 {
    total_memory_mb().unwrap_or(16384)
}

#[cfg(target_os = "windows")]
fn total_memory_mb() -> Option<u64> {
    #[repr(C)]
    struct MemoryStatusEx {
        length: u32,
        memory_load: u32,
        total_phys: u64,
        avail_phys: u64,
        total_page_file: u64,
        avail_page_file: u64,
        total_virtual: u64,
        avail_virtual: u64,
        avail_extended_virtual: u64,
    }
    #[link(name = "kernel32")]
    extern "system" {
        fn GlobalMemoryStatusEx(buf: *mut MemoryStatusEx) -> i32;
    }
    let mut m = MemoryStatusEx {
        length: std::mem::size_of::<MemoryStatusEx>() as u32,
        memory_load: 0,
        total_phys: 0,
        avail_phys: 0,
        total_page_file: 0,
        avail_page_file: 0,
        total_virtual: 0,
        avail_virtual: 0,
        avail_extended_virtual: 0,
    };
    // SAFETY: the struct matches MEMORYSTATUSEX and `length` is set as the API requires.
    let ok = unsafe { GlobalMemoryStatusEx(&mut m) };
    (ok != 0).then_some(m.total_phys / 1024 / 1024)
}

#[cfg(target_os = "linux")]
fn total_memory_mb() -> Option<u64> {
    let info = std::fs::read_to_string("/proc/meminfo").ok()?;
    let kb: u64 = info.lines().find(|l| l.starts_with("MemTotal:"))?.split_whitespace().nth(1)?.parse().ok()?;
    Some(kb / 1024)
}

#[cfg(target_os = "macos")]
fn total_memory_mb() -> Option<u64> {
    let out = std::process::Command::new("sysctl").args(["-n", "hw.memsize"]).output().ok()?;
    let bytes: u64 = String::from_utf8_lossy(&out.stdout).trim().parse().ok()?;
    Some(bytes / 1024 / 1024)
}
