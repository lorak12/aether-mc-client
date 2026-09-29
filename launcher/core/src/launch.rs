//! Builds the JVM command line for a version (modern `arguments` and legacy `minecraftArguments`).
use crate::download::{install_version, Installed, Layout};
use crate::profile::Profile;
use crate::version::{ArgValue, Argument, Manifest, VersionJson};
use crate::{java, loader, rules, Error, Result};
use std::collections::HashMap;
use std::path::PathBuf;

/// Authenticated session passed to the game. Real Microsoft tokens only.
#[derive(Debug, Clone)]
pub struct Session {
    pub username: String,
    pub uuid: String,
    pub access_token: String,
}

#[derive(Debug, Clone)]
pub struct LaunchConfig {
    pub java: PathBuf,
    pub game_dir: PathBuf,
    pub session: Session,
    pub memory_mb: u32,
    pub width: u32,
    pub height: u32,
    /// Extra JVM args, e.g. `-javaagent:...` for the Aether runtime.
    pub extra_jvm: Vec<String>,
    pub extra_game: Vec<String>,
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

/// Returns the full argv (java executable first).
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
    vars.insert("launcher_name", "Aether".into());
    vars.insert("launcher_version", env!("CARGO_PKG_VERSION").into());
    vars.insert("resolution_width", cfg.width.to_string());
    vars.insert("resolution_height", cfg.height.to_string());

    let features = HashMap::new(); // demo / quick-play / custom-resolution stay off; we pass --width/--height ourselves below

    let mut argv = vec![cfg.java.display().to_string(), format!("-Xmx{}M", cfg.memory_mb), format!("-Xms{}M", cfg.memory_mb.min(512))];
    // Belt and braces against Log4Shell on top of Mojang's config (no-op on unaffected versions).
    argv.push("-Dlog4j2.formatMsgNoLookups=true".into());
    argv.extend(installed.log_arg.iter().cloned());
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
    if !game.iter().any(|a| a == "--width") {
        game.extend(["--width".into(), cfg.width.to_string(), "--height".into(), cfg.height.to_string()]);
    }
    argv.extend(game);
    argv.extend(cfg.extra_game.iter().cloned());
    argv
}

/// Everything from "profile" to "ready to spawn": version JSON, loader, Java runtime, libraries, assets.
/// `progress(stage, done, total)` is called as files download. Returns the argv (java first).
pub async fn prepare(
    client: &reqwest::Client,
    layout: &Layout,
    profile: &Profile,
    game_dir: PathBuf,
    session: Session,
    mut progress: impl FnMut(&str, usize, usize),
) -> Result<Vec<String>> {
    progress("version", 0, 1);
    let manifest = Manifest::fetch(client).await?;
    let entry = manifest
        .find(&profile.mc_version)
        .ok_or_else(|| Error::Other(format!("Minecraft {} is not in Mojang's version list", profile.mc_version)))?;
    let mut version = VersionJson::fetch(client, entry).await?;
    loader::apply(client, &mut version, profile.loader).await?;

    let java = java::ensure_runtime(client, layout, version.java_component(), |d, t| progress("java", d, t)).await?;
    let installed = install_version(client, layout, &version, &mut progress).await?;

    let cfg = LaunchConfig {
        java,
        game_dir,
        session,
        memory_mb: profile.memory_mb,
        width: 1280,
        height: 720,
        extra_jvm: profile.jvm_args.clone(),
        extra_game: vec![],
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
            memory_mb: 2048,
            width: 854,
            height: 480,
            extra_jvm: vec!["-javaagent:a.jar".into()],
            extra_game: vec![],
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
}
