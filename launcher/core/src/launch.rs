//! Builds the JVM command line for a version (modern `arguments` and legacy `minecraftArguments`).
use crate::download::{install_version, Installed, Layout};
use crate::profile::Profile;
use crate::settings::{split_args, GameSettings};
use crate::version::{ArgValue, Argument, Manifest, VersionJson};
use crate::{java, loader, rules, Error, Result};
use std::collections::{HashMap, HashSet};
use std::path::PathBuf;

/// Authenticated session passed to the game. Real Microsoft tokens only.
#[derive(Debug, Clone)]
pub struct Session {
    pub username: String,
    pub uuid: String,
    pub access_token: String,
}

/// Where to go straight after the game starts.
#[derive(Debug, Clone, PartialEq)]
pub enum QuickPlay {
    /// `host[:port]`
    Server(String),
    /// World folder name in `saves/`.
    World(String),
}

#[derive(Debug, Clone)]
pub struct LaunchConfig {
    pub java: PathBuf,
    pub game_dir: PathBuf,
    pub session: Session,
    /// Effective settings (heap, GC, JVM args, resolution, wrapper).
    pub settings: GameSettings,
    /// JVM args from the launcher itself (system properties), after the user's.
    pub extra_jvm: Vec<String>,
    pub quick_play: Option<QuickPlay>,
}

fn classpath_sep() -> &'static str {
    if cfg!(windows) {
        ";"
    } else {
        ":"
    }
}

fn expand(s: &str, vars: &HashMap<&str, String>) -> String {
    let mut out = s.to_string();
    for (k, v) in vars {
        out = out.replace(&format!("${{{k}}}"), v);
    }
    out
}

fn resolve(args: &[Argument], features: &HashMap<String, bool>) -> Vec<String> {
    let mut out = Vec::new();
    for a in args {
        match a {
            Argument::Plain(s) => out.push(s.clone()),
            Argument::Conditional { rules, value } => {
                if rules::allowed(rules, features) {
                    match value {
                        ArgValue::One(s) => out.push(s.clone()),
                        ArgValue::Many(v) => out.extend(v.iter().cloned()),
                    }
                }
            }
        }
    }
    out
}

/// `features` the version's conditional arguments test for (e.g. `is_quick_play_multiplayer`).
fn declared_features(version: &VersionJson) -> HashSet<String> {
    let mut out = HashSet::new();
    if let Some(a) = &version.arguments {
        for arg in a.game.iter().chain(a.jvm.iter()) {
            if let Argument::Conditional { rules, .. } = arg {
                for r in rules {
                    if let Some(f) = &r.features {
                        out.extend(f.keys().cloned());
                    }
                }
            }
        }
    }
    out
}

/// `host[:port]` with the default port 25565. Bracketed IPv6 (`[::1]:25565`) is understood.
pub fn split_address(addr: &str) -> (String, u16) {
    let a = addr.trim();
    if let Some(rest) = a.strip_prefix('[') {
        if let Some((host, tail)) = rest.split_once(']') {
            let port = tail.strip_prefix(':').and_then(|p| p.parse().ok()).unwrap_or(25565);
            return (host.to_string(), port);
        }
    }
    match a.rsplit_once(':') {
        Some((h, p)) if !h.contains(':') => (h.to_string(), p.parse().unwrap_or(25565)),
        _ => (a.to_string(), 25565),
    }
}

/// Returns the full argv (wrapper, if any, then the java executable).
pub fn build_command(layout: &Layout, version: &VersionJson, installed: &Installed, cfg: &LaunchConfig) -> Vec<String> {
    let classpath = installed
        .classpath
        .iter()
        .map(|p| p.display().to_string())
        .collect::<Vec<_>>()
        .join(classpath_sep());

    let mut vars: HashMap<&str, String> = HashMap::new();
    vars.insert("auth_player_name", cfg.session.username.clone());
    vars.insert("auth_uuid", cfg.session.uuid.clone());
    vars.insert("auth_access_token", cfg.session.access_token.clone());
    vars.insert("auth_session", format!("token:{}:{}", cfg.session.access_token, cfg.session.uuid));
    vars.insert("user_type", "msa".into());
    vars.insert("user_properties", "{}".into());
    vars.insert("clientid", String::new());
    vars.insert("auth_xuid", String::new());
    vars.insert("version_name", version.id.clone());
    vars.insert("version_type", "release".into());
    vars.insert("game_directory", cfg.game_dir.display().to_string());
    vars.insert("assets_root", layout.assets().display().to_string());
    vars.insert("game_assets", layout.assets().join("virtual").join("legacy").display().to_string());
    vars.insert("assets_index_name", version.asset_index.id.clone());
    vars.insert("natives_directory", installed.natives_dir.display().to_string());
    vars.insert("library_directory", layout.libraries().display().to_string());
    vars.insert("classpath_separator", classpath_sep().into());
    vars.insert("classpath", classpath);
    vars.insert("launcher_name", "Kite".into());
    vars.insert("launcher_version", env!("CARGO_PKG_VERSION").into());
    vars.insert("resolution_width", cfg.settings.width.to_string());
    vars.insert("resolution_height", cfg.settings.height.to_string());
    vars.insert("quick_play_path", cfg.game_dir.join("logs").join("quickPlay.json").display().to_string());

    // Demo mode stays off. Quick play uses the version's own arguments when it declares them (1.20+).
    let declared = declared_features(version);
    let mut features: HashMap<String, bool> = HashMap::new();
    let mut legacy_server: Option<(String, u16)> = None;
    match &cfg.quick_play {
        Some(QuickPlay::Server(addr)) if declared.contains("is_quick_play_multiplayer") => {
            features.insert("is_quick_play_multiplayer".into(), true);
            vars.insert("quick_play_multiplayer", addr.trim().to_string());
        }
        Some(QuickPlay::Server(addr)) => legacy_server = Some(split_address(addr)),
        Some(QuickPlay::World(w)) if declared.contains("is_quick_play_singleplayer") => {
            features.insert("is_quick_play_singleplayer".into(), true);
            vars.insert("quick_play_singleplayer", w.clone());
        }
        _ => {}
    }

    let mut argv = split_args(&cfg.settings.wrapper);
    argv.push(cfg.java.display().to_string());
    // Belt and braces against Log4Shell on top of Mojang's config (no-op on unaffected versions).
    argv.push("-Dlog4j2.formatMsgNoLookups=true".into());
    argv.extend(installed.log_arg.iter().cloned());
    argv.extend(cfg.settings.jvm_args());
    argv.extend(cfg.extra_jvm.iter().cloned());

    let mut game: Vec<String>;
    match &version.arguments {
        Some(a) => {
            argv.extend(resolve(&a.jvm, &features).into_iter().map(|s| expand(&s, &vars)));
            game = resolve(&a.game, &features);
        }
        None => {
            argv.push(format!("-Djava.library.path={}", installed.natives_dir.display()));
            argv.push("-cp".into());
            argv.push(vars["classpath"].clone());
            game = version
                .minecraft_arguments
                .as_deref()
                .unwrap_or_default()
                .split_whitespace()
                .map(String::from)
                .collect();
        }
    }
    argv.push(version.main_class.clone());
    game = game.into_iter().map(|s| expand(&s, &vars)).collect();
    // Resolution: passed directly (the versions' own has_custom_resolution argument stays off so it isn't doubled).
    game.extend(["--width".into(), cfg.settings.width.to_string(), "--height".into(), cfg.settings.height.to_string()]);
    if cfg.settings.fullscreen {
        game.push("--fullscreen".into());
    }
    if let Some((host, port)) = legacy_server {
        game.extend(["--server".into(), host, "--port".into(), port.to_string()]);
    }
    argv.extend(game);
    argv
}

/// Everything a launch needs besides the profile.
pub struct LaunchRequest {
    pub game_dir: PathBuf,
    pub session: Session,
    pub settings: GameSettings,
    pub extra_jvm: Vec<String>,
    pub quick_play: Option<QuickPlay>,
}

/// Everything from "profile" to "ready to spawn": version JSON, Java runtime, loader, libraries, assets.
/// `progress(stage, done, total)` is called as files download. Returns the argv (wrapper and java first).
pub async fn prepare(
    client: &reqwest::Client,
    layout: &Layout,
    profile: &Profile,
    req: LaunchRequest,
    mut progress: impl FnMut(&str, usize, usize),
) -> Result<Vec<String>> {
    progress("version", 0, 1);
    let manifest = Manifest::fetch(client).await?;
    let entry = manifest
        .find(&profile.mc_version)
        .ok_or_else(|| Error::Other(format!("Minecraft {} is not in Mojang's version list", profile.mc_version)))?;
    let mut version = VersionJson::fetch(client, entry).await?;

    let java = match req.settings.java_path.trim() {
        "" => java::ensure_runtime(client, layout, version.java_component(), |d, t| progress("java", d, t)).await?,
        custom => {
            let p = PathBuf::from(custom);
            if !p.is_file() {
                return Err(Error::Other(format!("The Java set in settings doesn't exist: {custom}")));
            }
            p
        }
    };
    progress("loader", 0, 1);
    loader::apply(client, layout, &java, &mut version, profile.loader, profile.loader_version.as_deref(), |d, t| progress("loader", d, t)).await?;
    let installed = install_version(client, layout, &version, &mut progress).await?;

    let cfg = LaunchConfig {
        java,
        game_dir: req.game_dir,
        session: req.session,
        settings: req.settings,
        extra_jvm: req.extra_jvm,
        quick_play: req.quick_play,
    };
    Ok(build_command(layout, &version, &installed, &cfg))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn cfg() -> LaunchConfig {
        LaunchConfig {
            java: "java".into(),
            game_dir: "gd".into(),
            session: Session { username: "Steve".into(), uuid: "u".into(), access_token: "tok".into() },
            settings: GameSettings { memory_mb: 2048, width: 854, height: 480, ..Default::default() },
            extra_jvm: vec!["-javaagent:a.jar".into()],
            quick_play: None,
        }
    }

    #[test]
    fn legacy_args_expand() {
        let j = r#"{"id":"1.8.9","mainClass":"Main","minecraftArguments":"--username ${auth_player_name} --accessToken ${auth_access_token} --version ${version_name}",
        "libraries":[],"assetIndex":{"id":"1.8","sha1":"x","size":1,"url":"u"},"downloads":{"client":{"sha1":"x","size":1,"url":"u"}}}"#;
        let v: VersionJson = serde_json::from_str(j).unwrap();
        let inst = Installed { classpath: vec!["a.jar".into(), "b.jar".into()], natives_dir: "nat".into(), log_arg: Some("-Dlog4j.configurationFile=x.xml".into()) };
        let argv = build_command(&Layout::new("root"), &v, &inst, &cfg());
        assert!(argv.contains(&"-javaagent:a.jar".to_string()));
        assert!(argv.contains(&"-Dlog4j.configurationFile=x.xml".to_string()));
        assert!(argv.iter().any(|a| a.starts_with("-Djava.library.path=")));
        let i = argv.iter().position(|a| a == "Main").unwrap();
        assert_eq!(&argv[i + 1..i + 3], ["--username", "Steve"]);
        assert!(argv.contains(&"--width".to_string()));
        assert!(!argv.iter().any(|a| a.contains("${")));
    }

    #[test]
    fn modern_args_expand() {
        let j = r#"{"id":"1.21.11","mainClass":"M","arguments":{"game":["--username","${auth_player_name}"],
        "jvm":["-Djava.library.path=${natives_directory}","-cp","${classpath}"]},
        "libraries":[],"assetIndex":{"id":"27","sha1":"x","size":1,"url":"u"},"downloads":{"client":{"sha1":"x","size":1,"url":"u"}}}"#;
        let v: VersionJson = serde_json::from_str(j).unwrap();
        let inst = Installed { classpath: vec!["a.jar".into()], natives_dir: "nat".into(), log_arg: None };
        let argv = build_command(&Layout::new("root"), &v, &inst, &cfg());
        assert!(argv.contains(&"a.jar".to_string()));
        assert!(!argv.iter().any(|a| a.contains("${")));
    }

    const QP: &str = r#"{"id":"1.21.11","mainClass":"M","arguments":{"game":["--username","${auth_player_name}",
        {"rules":[{"action":"allow","features":{"has_custom_resolution":true}}],"value":["--width","${resolution_width}","--height","${resolution_height}"]},
        {"rules":[{"action":"allow","features":{"is_quick_play_multiplayer":true}}],"value":["--quickPlayMultiplayer","${quick_play_multiplayer}"]},
        {"rules":[{"action":"allow","features":{"is_quick_play_singleplayer":true}}],"value":["--quickPlaySingleplayer","${quick_play_singleplayer}"]}],
        "jvm":["-cp","${classpath}"]},
        "libraries":[],"assetIndex":{"id":"27","sha1":"x","size":1,"url":"u"},"downloads":{"client":{"sha1":"x","size":1,"url":"u"}}}"#;

    #[test]
    fn quick_play_uses_version_arguments() {
        let v: VersionJson = serde_json::from_str(QP).unwrap();
        let inst = Installed { classpath: vec![], natives_dir: "nat".into(), log_arg: None };
        let mut c = cfg();
        c.quick_play = Some(QuickPlay::Server("play.example.net".into()));
        c.settings.fullscreen = true;
        c.settings.wrapper = "prime-run".into();
        let argv = build_command(&Layout::new("root"), &v, &inst, &c);
        assert_eq!(argv[0], "prime-run");
        assert_eq!(argv[1], "java");
        let i = argv.iter().position(|a| a == "--quickPlayMultiplayer").unwrap();
        assert_eq!(argv[i + 1], "play.example.net");
        assert!(!argv.contains(&"--quickPlaySingleplayer".to_string()));
        assert!(argv.contains(&"--fullscreen".to_string()));
        assert_eq!(argv.iter().filter(|a| *a == "--width").count(), 1);
        assert!(argv.contains(&"-Xmx2048M".to_string()) && argv.contains(&"-XX:+UseG1GC".to_string()));

        c.quick_play = Some(QuickPlay::World("My World".into()));
        let argv = build_command(&Layout::new("root"), &v, &inst, &c);
        let i = argv.iter().position(|a| a == "--quickPlaySingleplayer").unwrap();
        assert_eq!(argv[i + 1], "My World");
    }

    #[test]
    fn old_versions_join_with_server_and_port() {
        let j = r#"{"id":"1.8.9","mainClass":"Main","minecraftArguments":"--username ${auth_player_name}",
        "libraries":[],"assetIndex":{"id":"1.8","sha1":"x","size":1,"url":"u"},"downloads":{"client":{"sha1":"x","size":1,"url":"u"}}}"#;
        let v: VersionJson = serde_json::from_str(j).unwrap();
        let inst = Installed { classpath: vec![], natives_dir: "nat".into(), log_arg: None };
        let mut c = cfg();
        c.quick_play = Some(QuickPlay::Server("mc.hypixel.net:25566".into()));
        let argv = build_command(&Layout::new("root"), &v, &inst, &c);
        let i = argv.iter().position(|a| a == "--server").unwrap();
        assert_eq!(&argv[i + 1..i + 4], ["mc.hypixel.net", "--port", "25566"]);
    }

    #[test]
    fn addresses() {
        assert_eq!(split_address("a.b"), ("a.b".into(), 25565));
        assert_eq!(split_address("a.b:1234"), ("a.b".into(), 1234));
        assert_eq!(split_address("[::1]:7"), ("::1".into(), 7));
        assert_eq!(split_address("::1"), ("::1".into(), 25565));
    }
}
