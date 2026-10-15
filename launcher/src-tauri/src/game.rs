//! Launching, stopping and watching the game, plus its logs.
use crate::accounts::{self, accounts_path, load_accounts, save_accounts};
use crate::{e, show_main, AppState, Res};
use aether_core::download::Layout;
use aether_core::launch::{self, LaunchRequest, QuickPlay};
use aether_core::settings::{effective, split_args};
use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use tauri::{AppHandle, Emitter, Manager, State};

pub struct Running {
    pub profile_id: String,
    pid: Option<u32>,
    /// Set when the player stopped the game from the launcher, so the exit isn't reported as a crash.
    killed: Arc<AtomicBool>,
}

#[derive(Clone, Serialize)]
struct LaunchProgress {
    stage: String,
    done: usize,
    total: usize,
}

#[derive(Clone, Serialize)]
struct GameExit {
    profile_id: String,
    code: Option<i32>,
    /// Stopped from the launcher (not a crash).
    killed: bool,
    /// Last lines of output when the game crashed.
    log_tail: Option<String>,
    /// The game's output log, to open from the crash notice.
    log_path: String,
    /// A crash report the game wrote during this session.
    crash_report: Option<String>,
    /// Account uuid the player picked in the game's account switcher: switch to it and play again.
    relaunch: Option<String>,
}

/// Where to go once the game is up: `{ "server": "host:port" }` or `{ "world": "<folder>" }`.
#[derive(Debug, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum QuickPlayArg {
    Server(String),
    World(String),
}

/// The uuid in the game's relaunch request (and remove the request so it fires once).
fn read_relaunch(path: &Path) -> Option<String> {
    let bytes = std::fs::read(path).ok()?;
    let _ = std::fs::remove_file(path);
    let v: serde_json::Value = serde_json::from_slice(&bytes).ok()?;
    v.get("uuid")?.as_str().filter(|u| !u.is_empty()).map(str::to_string)
}

/// The launch command for the log, with the access token (and anything after --accessToken) hidden.
fn redacted_command(argv: &[String]) -> String {
    let mut out = Vec::with_capacity(argv.len());
    let mut hide = false;
    for a in argv {
        out.push(if hide { "<hidden>".to_string() } else if a.contains(' ') { format!("\"{a}\"") } else { a.clone() });
        hide = a == "--accessToken";
    }
    out.join(" ")
}

fn log_tail(path: &Path, lines: usize) -> String {
    let text = std::fs::read(path).map(|b| String::from_utf8_lossy(&b).into_owned()).unwrap_or_default();
    let all = aether_core::logs::plain_lines(&text);
    all[all.len().saturating_sub(lines)..].join("\n")
}

/// Newest file in `crash-reports/` written after `since`.
fn crash_report_since(game_dir: &Path, since: std::time::SystemTime) -> Option<PathBuf> {
    std::fs::read_dir(game_dir.join("crash-reports"))
        .ok()?
        .flatten()
        .filter_map(|e| Some((e.metadata().ok()?.modified().ok()?, e.path())))
        .filter(|(t, _)| *t >= since)
        .max_by_key(|(t, _)| *t)
        .map(|(_, p)| p)
}

/// Run a user hook through the shell, in the game folder, with the INST_* variables. Returns the exit code.
fn run_hook(cmd: &str, cwd: &Path, env: &[(String, String)]) -> Res<i32> {
    #[cfg(windows)]
    let mut c = {
        use std::os::windows::process::CommandExt;
        let mut c = std::process::Command::new("cmd");
        c.arg("/C").raw_arg(cmd).creation_flags(0x0800_0000);
        c
    };
    #[cfg(not(windows))]
    let mut c = {
        let mut c = std::process::Command::new("sh");
        c.arg("-c").arg(cmd);
        c
    };
    c.current_dir(cwd).envs(env.iter().map(|(k, v)| (k.as_str(), v.as_str()))).stdin(std::process::Stdio::null());
    let st = c.status().map_err(|err| format!("couldn't run `{cmd}`: {err}"))?;
    Ok(st.code().unwrap_or(-1))
}

/// Refresh the session, install what the profile needs, run the pre-launch hook and start Minecraft.
/// Emits `launch-progress` while preparing and `game-exit` when the game closes. Returns the player name.
#[tauri::command]
pub async fn launch_profile(app: AppHandle, s: State<'_, AppState>, profile_id: String, quick_play: Option<QuickPlayArg>) -> Res<String> {
    let killed = Arc::new(AtomicBool::new(false));
    {
        let mut running = s.running.lock().await;
        if let Some(r) = running.as_ref() {
            let name = s.profile(&r.profile_id).map(|p| p.name).unwrap_or_else(|_| "Minecraft".into());
            return Err(format!("{name} is already running. Stop it first (Play button menu or tray icon)."));
        }
        *running = Some(Running { profile_id: profile_id.clone(), pid: None, killed: killed.clone() });
    }
    let result = start_game(&app, &s, &profile_id, quick_play, killed).await;
    if let Err(err) = &result {
        *s.running.lock().await = None;
        s.log(format!("launch of {profile_id} failed: {err}"));
    }
    result
}

async fn start_game(app: &AppHandle, s: &AppState, profile_id: &str, quick_play: Option<QuickPlayArg>, killed: Arc<AtomicBool>) -> Res<String> {
    let store = s.store();
    let mut profile = store.load(profile_id).map_err(e)?;
    let http = s.api.http();
    let t0 = std::time::Instant::now();
    s.log(format!(
        "launching \"{}\" ({}): Minecraft {} {:?} {}{}",
        profile.name,
        profile.id,
        profile.mc_version,
        profile.loader,
        profile.loader_version.as_deref().unwrap_or(""),
        match &quick_play {
            Some(QuickPlayArg::Server(a)) => format!(", joining server {a}"),
            Some(QuickPlayArg::World(w)) => format!(", opening world {w}"),
            None => String::new(),
        }
    ));
    // Each preparation stage is logged once when it starts and once when it finishes.
    let last_stage = std::sync::Mutex::new(String::new());
    let emit = |stage: &str, done: usize, total: usize| {
        if let Ok(mut last) = last_stage.lock() {
            if *last != stage {
                s.log(format!("  {stage}: started ({total} item{})", if total == 1 { "" } else { "s" }));
                *last = stage.to_string();
            } else if done == total && total > 1 {
                s.log(format!("  {stage}: done ({total} items, {:.1}s since launch)", t0.elapsed().as_secs_f32()));
            }
        }
        // Throttle: assets alone are thousands of files.
        if done == 0 || done == total || done.is_multiple_of((total / 50).max(1)) {
            let _ = app.emit("launch-progress", LaunchProgress { stage: stage.into(), done, total });
        }
    };

    emit("account", 0, 1);
    let (account, session) = accounts::current_session(s).await?;
    let username = session.username.clone();
    s.log(format!("  account: {username} ({}, {})", account.kind, account.uuid));
    let game_dir = s.game_dir(&profile);
    std::fs::create_dir_all(&game_dir).map_err(e)?;
    let settings = effective(&s.settings().load(), &profile.overrides);
    s.log(format!("  game folder: {}", game_dir.display()));
    s.log(format!(
        "  settings: memory {} MB, GC {:?}, java {}, window {}, extra JVM args \"{}\", wrapper \"{}\"",
        settings.memory_mb,
        settings.gc,
        if settings.java_path.trim().is_empty() { "auto" } else { settings.java_path.as_str() },
        if settings.fullscreen { "fullscreen".to_string() } else { format!("{}x{}", settings.width, settings.height) },
        settings.jvm_args,
        settings.wrapper
    ));
    let quick_play = match quick_play {
        Some(QuickPlayArg::Server(a)) if !a.trim().is_empty() => Some(QuickPlay::Server(a.trim().to_string())),
        Some(QuickPlayArg::World(w)) => {
            aether_core::gamefiles::safe_name(&w).map_err(e)?;
            if !game_dir.join("saves").join(&w).join("level.dat").exists() {
                return Err(format!("The world {w} doesn't exist any more"));
            }
            Some(QuickPlay::World(w))
        }
        _ => None,
    };

    emit("aether-client", 0, 1);
    // A newer client build from the signed manifest, when the backend is configured (best effort).
    if let (Some(b), Some(key)) = (&s.backend, &s.cfg.manifest_public_key) {
        if aether_core::runtime::adapter_for(&profile.mc_version, profile.loader).is_some() {
            let fut = async {
                let m = aether_core::update::fetch(http, b.base(), "stable", key).await?;
                aether_core::update::update_runtime(http, &m, &s.root, &profile.mc_version).await
            };
            match tokio::time::timeout(std::time::Duration::from_secs(8), fut).await {
                Ok(Ok(Some(p))) => s.log(format!("  downloaded client update {}", p.display())),
                Ok(Ok(None)) => s.log("  Aether client is up to date"),
                Ok(Err(err)) => s.log(format!("  client update check failed: {err}")),
                Err(_) => s.log("  client update check timed out (8 s), using the installed build"),
            }
        }
    }
    // Supported profiles get the Aether in-game client; without a built jar they launch plain.
    let extra_jvm = aether_core::runtime::prepare_launch(&s.root, &profile, &game_dir).map_err(e)?;
    if aether_core::runtime::adapter_for(&profile.mc_version, profile.loader).is_some() {
        s.log(format!("  Aether client: {}", if extra_jvm.is_empty() { "installed into the mods folder" } else { "added with JVM arguments" }));
    } else {
        s.log(format!("  Aether client: not available for {} {:?}, launching without it", profile.mc_version, profile.loader));
    }
    let layout = Layout::new(&s.root);
    let wrapper_len = split_args(&settings.wrapper).len();
    let req = LaunchRequest { game_dir: game_dir.clone(), session, settings: settings.clone(), extra_jvm, quick_play };
    let argv = launch::prepare(http, &layout, &profile, req, emit).await.map_err(e)?;
    let java = argv.get(wrapper_len).cloned().unwrap_or_default();
    s.log(format!("  prepared in {:.1}s; java: {java}", t0.elapsed().as_secs_f32()));
    s.log(format!("  command: {}", redacted_command(&argv)));

    let hook_env: Vec<(String, String)> = vec![
        ("INST_ID".into(), profile.id.clone()),
        ("INST_NAME".into(), profile.name.clone()),
        ("INST_DIR".into(), store.dir(&profile.id).display().to_string()),
        ("INST_MC_DIR".into(), game_dir.display().to_string()),
        ("INST_JAVA".into(), java.clone()),
    ];
    if !settings.pre_launch.trim().is_empty() {
        emit("pre-launch", 0, 1);
        let (cmd, dir, env) = (settings.pre_launch.clone(), game_dir.clone(), hook_env.clone());
        s.log(format!("  pre-launch command: {}", settings.pre_launch));
        let code = tauri::async_runtime::spawn_blocking(move || run_hook(&cmd, &dir, &env)).await.map_err(e)??;
        s.log(format!("  pre-launch command exited with {code}"));
        if code != 0 {
            return Err(format!("The pre-launch command failed (exit code {code}), so the game wasn't started."));
        }
    }
    if killed.load(Ordering::Relaxed) {
        return Err("Launch cancelled".into());
    }

    // Video settings, keybinds and the rest of options.txt follow the player between profiles.
    let share_options = settings.share_options;
    if share_options {
        let legacy = aether_core::options::is_legacy(&profile.mc_version);
        let seeds: Vec<PathBuf> = store
            .list()
            .unwrap_or_default()
            .iter()
            .filter(|p| p.id != profile.id && aether_core::options::is_legacy(&p.mc_version) == legacy)
            .map(|p| s.game_dir(p).join("options.txt"))
            .collect();
        match aether_core::options::push(&s.root, &game_dir, &profile.mc_version, &seeds) {
            Ok(_) => s.log("  shared game options applied"),
            Err(err) => s.log(format!("  shared game options: {err}")),
        }
    }

    let logs = game_dir.join("logs");
    std::fs::create_dir_all(&logs).map_err(e)?;
    let log_path = logs.join("launcher-output.log");
    let log = std::fs::File::create(&log_path).map_err(e)?;
    let mut cmd = std::process::Command::new(&argv[0]);
    cmd.args(&argv[1..])
        .current_dir(&game_dir)
        .stdin(std::process::Stdio::null())
        .stdout(log.try_clone().map_err(e)?)
        .stderr(log)
        .envs(settings.env.iter().map(|(k, v)| (k.as_str(), v.as_str())))
        .envs(hook_env.iter().map(|(k, v)| (k.as_str(), v.as_str())));
    // The in-game client syncs cosmetics and the client badge with the same backend.
    if let Some(url) = &s.cfg.backend_url {
        cmd.env("AETHER_BACKEND_URL", url);
    }
    // The in-game account switcher lists the saved accounts (names/uuids only, no tokens) and asks for a relaunch
    // by writing the chosen uuid to the relaunch file before quitting.
    let _ = save_accounts(&s.root, &load_accounts(&s.root));
    let relaunch_path = s.root.join("relaunch.json");
    let _ = std::fs::remove_file(&relaunch_path);
    cmd.env("AETHER_ACCOUNTS_FILE", accounts_path(&s.root));
    cmd.env("AETHER_RELAUNCH_FILE", &relaunch_path);
    // Live switching (see SessionBridge); without it the game falls back to the relaunch file.
    let bridge = match accounts::start_session_bridge(app.clone()) {
        Ok(b) => Some(b),
        Err(err) => {
            s.log(format!("  account switch bridge unavailable ({err}); in-game switching will relaunch"));
            None
        }
    };
    if let Some(b) = &bridge {
        cmd.env("AETHER_SESSION_PORT", b.port.to_string());
        cmd.env("AETHER_SESSION_KEY", &b.key);
    }
    let started = std::time::SystemTime::now();
    let mut child = cmd.spawn().map_err(|err| {
        if let Some(b) = &bridge {
            b.stop.store(true, Ordering::Relaxed);
        }
        format!("couldn't start Java ({}): {err}", argv[0])
    })?;
    s.log(format!(
        "started {} ({} {:?}) as {username}, pid {}, {:.1}s after clicking Play; game output: {}",
        profile.id,
        profile.mc_version,
        profile.loader,
        child.id(),
        t0.elapsed().as_secs_f32(),
        log_path.display()
    ));
    if let Some(r) = s.running.lock().await.as_mut() {
        r.pid = Some(child.id());
    }
    profile.meta.last_played = Some((crate::now_unix() * 1000) as u64);
    profile.meta.plays += 1;
    let _ = store.save(&profile);

    let _ = app.emit("launch-progress", LaunchProgress { stage: "running".into(), done: 1, total: 1 });
    let app = app.clone();
    let (post_exit, pid_profile, mc_version) = (settings.post_exit.clone(), profile.id.clone(), profile.mc_version.clone());
    tauri::async_runtime::spawn_blocking(move || {
        let code = child.wait().ok().and_then(|st| st.code());
        if let Some(b) = &bridge {
            b.stop.store(true, Ordering::Relaxed);
        }
        let state = app.state::<AppState>();
        let was_killed = killed.load(Ordering::Relaxed);
        if share_options {
            if let Err(err) = aether_core::options::pull(&state.root, &game_dir, &mc_version) {
                state.log(format!("shared game options: {err}"));
            }
        }
        if !post_exit.trim().is_empty() {
            if let Err(err) = run_hook(&post_exit, &game_dir, &hook_env) {
                state.log(format!("post-exit command: {err}"));
            }
        }
        let crashed = code != Some(0) && !was_killed;
        let crash_report = crash_report_since(&game_dir, started).map(|p| p.display().to_string());
        let log_tail = crashed.then(|| log_tail(&log_path, 40));
        let relaunch = read_relaunch(&relaunch_path);
        let played = started.elapsed().map(|d| d.as_secs()).unwrap_or(0);
        state.log(format!(
            "{pid_profile} exited with {code:?}{} after {}m {}s",
            if was_killed { " (stopped from the launcher)" } else if crashed { " (crashed)" } else { "" },
            played / 60,
            played % 60
        ));
        if let Some(r) = &crash_report {
            state.log(format!("  crash report: {r}"));
        }
        if let Some(t) = &log_tail {
            state.log(format!("  last lines of the game output:\n{t}"));
        }
        if let Some(u) = &relaunch {
            state.log(format!("  the game asked to relaunch as account {u}"));
        }
        *state.running.blocking_lock() = None;
        // The window may be hidden to the tray while playing.
        if relaunch.is_none() {
            show_main(&app);
        }
        let _ = app.emit(
            "game-exit",
            GameExit { profile_id: pid_profile, code, killed: was_killed, log_tail, log_path: log_path.display().to_string(), crash_report, relaunch },
        );
    });
    Ok(username)
}

/// Stop the running game (its whole process tree). Also cancels a launch that's still preparing.
pub async fn kill(app: &AppHandle) -> Res<()> {
    let s = app.state::<AppState>();
    let running = s.running.lock().await;
    let Some(r) = running.as_ref() else { return Ok(()) };
    r.killed.store(true, Ordering::Relaxed);
    let Some(pid) = r.pid else { return Ok(()) };
    s.log(format!("stopping {} (pid {pid})", r.profile_id));
    #[cfg(windows)]
    let st = {
        use std::os::windows::process::CommandExt;
        std::process::Command::new("taskkill").args(["/PID", &pid.to_string(), "/T", "/F"]).creation_flags(0x0800_0000).status()
    };
    #[cfg(not(windows))]
    let st = std::process::Command::new("kill").args(["-KILL", &pid.to_string()]).status();
    st.map_err(|err| format!("couldn't stop the game: {err}"))?;
    Ok(())
}

#[tauri::command]
pub async fn kill_game(app: AppHandle) -> Res<()> {
    kill(&app).await
}

#[tauri::command]
pub async fn running_profile(s: State<'_, AppState>) -> Res<Option<String>> {
    Ok(s.running.lock().await.as_ref().map(|r| r.profile_id.clone()))
}

#[derive(Serialize)]
pub struct LogChunk {
    /// New log lines since `offset` (log4j XML reduced to messages).
    lines: Vec<String>,
    /// Byte offset to pass next time.
    offset: u64,
    /// Profile the log belongs to, if a game is running.
    profile_id: Option<String>,
}

/// Read what the running game wrote since `offset`. Without a running game, reads the given profile's last log.
#[tauri::command]
pub async fn read_game_log(s: State<'_, AppState>, offset: u64, profile_id: Option<String>) -> Res<LogChunk> {
    use std::io::{Read, Seek, SeekFrom};
    let id = match s.running.lock().await.as_ref().map(|r| r.profile_id.clone()).or(profile_id) {
        Some(id) => id,
        None => return Ok(LogChunk { lines: vec![], offset: 0, profile_id: None }),
    };
    let Ok(p) = s.profile(&id) else { return Ok(LogChunk { lines: vec![], offset: 0, profile_id: None }) };
    let path = s.game_dir(&p).join("logs").join("launcher-output.log");
    let Ok(mut f) = std::fs::File::open(&path) else {
        return Ok(LogChunk { lines: vec![], offset: 0, profile_id: Some(id) });
    };
    let len = f.metadata().map_err(e)?.len();
    // The file is recreated at every launch: start over when it shrank.
    let start = if offset > len { 0 } else { offset };
    f.seek(SeekFrom::Start(start)).map_err(e)?;
    let mut buf = Vec::new();
    f.read_to_end(&mut buf).map_err(e)?;
    // Only hand out complete lines; the rest is read next time.
    let end = buf.iter().rposition(|&b| b == b'\n').map(|i| i + 1).unwrap_or(0);
    let lines = aether_core::logs::plain_lines(&String::from_utf8_lossy(&buf[..end])).into_iter().filter(|l| !l.trim().is_empty()).collect();
    Ok(LogChunk { lines, offset: start + end as u64, profile_id: Some(id) })
}

#[derive(Serialize)]
pub struct UploadedLogs {
    launcher: Option<String>,
    game: Option<String>,
    /// Which profile's game log was shared.
    profile: Option<String>,
}

/// Share the launcher log and a profile's latest game log on mclo.gs. Secrets are removed first.
#[tauri::command]
pub async fn upload_logs(s: State<'_, AppState>, profile_id: Option<String>) -> Res<UploadedLogs> {
    let http = s.api.http();
    let launcher_text = std::fs::read_to_string(s.root.join("logs").join("launcher.log")).unwrap_or_default();
    let launcher = if launcher_text.trim().is_empty() { None } else { Some(aether_core::logs::upload(http, &launcher_text).await.map_err(e)?) };
    let mut game = None;
    let mut profile = None;
    let id = match profile_id {
        Some(id) => Some(id),
        None => s.running.lock().await.as_ref().map(|r| r.profile_id.clone()),
    };
    if let Some(id) = id {
        let p = s.profile(&id)?;
        let logs = s.game_dir(&p).join("logs");
        let path = [logs.join("latest.log"), logs.join("launcher-output.log")].into_iter().find(|p| p.exists());
        if let Some(path) = path {
            let text = aether_core::logs::plain_lines(&std::fs::read_to_string(path).unwrap_or_default()).join("\n");
            if !text.trim().is_empty() {
                game = Some(aether_core::logs::upload(http, &text).await.map_err(e)?);
                profile = Some(p.name);
            }
        }
    }
    if launcher.is_none() && game.is_none() {
        return Err("There are no logs to share yet".into());
    }
    Ok(UploadedLogs { launcher, game, profile })
}
