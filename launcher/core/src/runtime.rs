//! The Kite in-game client (the Java runtime in `runtime/`): which profiles can run it, where its jar is, and
//! installing it into a profile's `mods` folder before launch. Listed releases have a built and audited adapter on
//! Fabric or Quilt; 1.8.9 and 1.12.2 run on Legacy Fabric.
//!
//! Jar lookup, first hit wins:
//!   1. `KITE_RUNTIME_DIR` (a folder holding `kite-<mc>-*.jar`)
//!   2. `<data root>/runtime/` (where a packaged build or the updater puts it)
//!   3. the repo's Gradle output `runtime/<adapter dir>/build/libs/` (development checkouts)
use crate::profile::{Loader, Profile};
use crate::Result;
use std::path::{Path, PathBuf};

/// File name inside `mods/`. Fixed so a newer build replaces the old one instead of loading twice.
pub const INSTALLED_NAME: &str = "kite-client.jar";

/// Game versions the client supports and their adapter folders under `runtime/`, newest first. The UI reads this
/// through a command. The legacy ones are a separate Gradle build (Legacy Fabric's toolchain).
pub const SUPPORTED: &[(&str, &str)] = &[
    ("26.3", "v26_3"),
    ("26.2", "v26_2"),
    ("26.1.2", "v26_1_2"),
    ("26.1.1", "v26_1_1"),
    ("26.1", "v26_1"),
    ("1.21.11", "v1_21_11"),
    ("1.21.10", "v1_21_10"),
    ("1.21.9", "v1_21_9"),
    ("1.21.8", "v1_21_8"),
    ("1.21.7", "v1_21_7"),
    ("1.21.6", "v1_21_6"),
    ("1.21.5", "v1_21_5"),
    ("1.21.4", "v1_21_4"),
    ("1.21.3", "v1_21_3"),
    ("1.21.1", "v1_21_1"),
    ("1.21", "v1_21"),
    ("1.20.6", "v1_20_6"),
    ("1.20.5", "v1_20_5"),
    ("1.20.4", "v1_20_4"),
    ("1.20.3", "v1_20_3"),
    ("1.20.2", "v1_20_2"),
    ("1.20.1", "v1_20_1"),
    ("1.20", "v1_20"),
    ("1.19.4", "v1_19_4"),
    ("1.19.3", "v1_19_3"),
    ("1.19.2", "v1_19_2"),
    ("1.19", "v1_19"),
    ("1.18.2", "v1_18_2"),
    ("1.17.1", "v1_17_1"),
    ("1.16.5", "v1_16_5"),
    ("1.12.2", "legacy/v1_12_2"),
    ("1.8.9", "legacy/v1_8_9"),
    ("1.7.10", "legacy/v1_7_10"),
];

/// Adapter folder for this profile, if the client supports it. Quilt loads Fabric mods, so it gets the Fabric
/// adapter too (Quilt has no builds before 1.14, so old versions are Fabric only in practice).
pub fn adapter_for(mc_version: &str, loader: Loader) -> Option<&'static str> {
    if !matches!(loader, Loader::Fabric | Loader::Quilt) {
        return None;
    }
    SUPPORTED.iter().find(|(v, _)| *v == mc_version).map(|(_, a)| *a)
}

/// Folder holding only the client jar, for profiles set to load built-in mods only.
pub fn builtin_dir(game_dir: &Path) -> PathBuf {
    game_dir.join(".kite").join("builtin-mods")
}

/// JVM properties that point the loader at `dir` instead of `mods/`.
pub fn mods_folder_args(loader: Loader, dir: &Path) -> Vec<String> {
    match loader {
        Loader::Fabric => vec![format!("-Dfabric.modsFolder={}", dir.display())],
        Loader::Quilt => vec![format!("-Dloader.modsDir={}", dir.display())],
        _ => vec![],
    }
}

/// Newest `kite-<mc>-*.jar` in `dir` (ignoring `-sources` / `-dev` jars).
fn newest_jar(dir: &Path, mc_version: &str) -> Option<PathBuf> {
    let prefix = format!("kite-{mc_version}-");
    std::fs::read_dir(dir)
        .ok()?
        .filter_map(|e| e.ok())
        .map(|e| e.path())
        .filter(|p| {
            let name = p.file_name().and_then(|n| n.to_str()).unwrap_or("");
            name.starts_with(&prefix) && name.ends_with(".jar") && !name.ends_with("-sources.jar") && !name.ends_with("-dev.jar")
        })
        .max_by_key(|p| p.metadata().and_then(|m| m.modified()).ok())
}

/// Locate the client jar for `profile`, or `None` if the version is unsupported or no build is available.
pub fn find_jar(data_root: &Path, profile: &Profile) -> Option<PathBuf> {
    let adapter = adapter_for(&profile.mc_version, profile.loader)?;
    let mut dirs = Vec::new();
    if let Some(d) = std::env::var_os("KITE_RUNTIME_DIR") {
        dirs.push(PathBuf::from(d));
    }
    dirs.push(data_root.join("runtime"));
    dirs.push(Path::new(env!("CARGO_MANIFEST_DIR")).join("../../runtime").join(adapter).join("build/libs"));
    dirs.iter().find_map(|d| newest_jar(d, &profile.mc_version))
}

/// Copy `jar` to `<game_dir>/mods/kite-client.jar` unless an identical copy is already there.
pub fn install(game_dir: &Path, jar: &Path) -> Result<PathBuf> {
    let mods = game_dir.join("mods");
    std::fs::create_dir_all(&mods)?;
    let dest = mods.join(INSTALLED_NAME);
    let new = std::fs::read(jar)?;
    if std::fs::read(&dest).ok().as_deref() != Some(new.as_slice()) {
        std::fs::write(&dest, new)?;
    }
    Ok(dest)
}

/// Remove the client from a profile it no longer applies to (e.g. the profile switched to vanilla).
pub fn uninstall(game_dir: &Path) -> Result<()> {
    let dest = game_dir.join("mods").join(INSTALLED_NAME);
    if dest.exists() {
        std::fs::remove_file(dest)?;
    }
    Ok(())
}

/// Install (or remove) the client for `profile` before launch. Returns the installed jar, if any.
pub fn prepare_profile(data_root: &Path, profile: &Profile, game_dir: &Path) -> Result<Option<PathBuf>> {
    match find_jar(data_root, profile) {
        Some(jar) => install(game_dir, &jar).map(Some),
        None => {
            if adapter_for(&profile.mc_version, profile.loader).is_none() {
                uninstall(game_dir)?;
            }
            Ok(None)
        }
    }
}

/// Like [`prepare_profile`], and for "built-in mods only" profiles also a mods folder with just the client.
/// Returns the JVM arguments the launch needs.
pub fn prepare_launch(data_root: &Path, profile: &Profile, game_dir: &Path) -> Result<Vec<String>> {
    let jar = prepare_profile(data_root, profile, game_dir)?;
    if !profile.meta.builtin_only || adapter_for(&profile.mc_version, profile.loader).is_none() {
        return Ok(vec![]);
    }
    let dir = builtin_dir(game_dir);
    std::fs::create_dir_all(&dir)?;
    match jar {
        Some(j) => {
            install_into(&dir, &j)?;
        }
        None => {
            let _ = std::fs::remove_file(dir.join(INSTALLED_NAME));
        }
    }
    Ok(mods_folder_args(profile.loader, &dir))
}

fn install_into(dir: &Path, jar: &Path) -> Result<()> {
    let dest = dir.join(INSTALLED_NAME);
    let new = std::fs::read(jar)?;
    if std::fs::read(&dest).ok().as_deref() != Some(new.as_slice()) {
        std::fs::write(&dest, new)?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tmp(name: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("kite-runtime-test-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        std::fs::create_dir_all(&d).unwrap();
        d
    }

    #[test]
    fn only_fabric_like_profiles_on_listed_versions() {
        assert_eq!(adapter_for("1.21.11", Loader::Fabric), Some("v1_21_11"));
        assert_eq!(adapter_for("1.21.11", Loader::Quilt), Some("v1_21_11"));
        assert_eq!(adapter_for("1.21.11", Loader::Vanilla), None);
        assert_eq!(adapter_for("1.21.11", Loader::Forge), None);
        assert_eq!(adapter_for("26.3", Loader::Fabric), Some("v26_3"));
        assert_eq!(adapter_for("26.1.1", Loader::Fabric), Some("v26_1_1"));
        assert_eq!(adapter_for("26.1", Loader::Fabric), Some("v26_1"));
        assert_eq!(adapter_for("1.8.9", Loader::Fabric), Some("legacy/v1_8_9"));
        assert_eq!(adapter_for("1.20.1", Loader::Fabric), Some("v1_20_1"));
        assert_eq!(adapter_for("1.19.4", Loader::Fabric), Some("v1_19_4"));
        assert_eq!(adapter_for("1.19.3", Loader::Fabric), Some("v1_19_3"));
        // Versions without an adapter (1.21.2 was replaced by 1.21.3 within days).
        assert_eq!(adapter_for("1.21.9", Loader::Fabric), Some("v1_21_9"));
        assert_eq!(adapter_for("1.21.2", Loader::Fabric), None);
        assert_eq!(adapter_for("1.17.1", Loader::Fabric), Some("v1_17_1"));
        assert_eq!(adapter_for("1.16.5", Loader::Fabric), Some("v1_16_5"));
        assert_eq!(adapter_for("1.7.10", Loader::Fabric), Some("legacy/v1_7_10"));
        assert_eq!(adapter_for("1.7.2", Loader::Fabric), None);
        for version in ["1.7.2", "1.9.4", "1.14.4", "1.15.2", "1.18.1"] {
            assert_eq!(adapter_for(version, Loader::Fabric), None);
        }
    }

    #[test]
    fn every_adapter_folder_exists() {
        let runtime = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../runtime");
        for (v, dir) in SUPPORTED {
            assert!(runtime.join(dir).join("build.gradle.kts").is_file(), "{v}: runtime/{dir} has no build.gradle.kts");
        }
    }

    #[test]
    fn builtin_only_uses_its_own_mods_folder() {
        let d = tmp("builtin");
        let mut p: Profile = serde_json::from_str(r#"{"format":2,"id":"x","name":"x","mc_version":"1.21.11","loader":"fabric"}"#).unwrap();
        assert!(prepare_launch(&d, &p, &d.join("g")).unwrap().is_empty());
        p.meta.builtin_only = true;
        let args = prepare_launch(&d, &p, &d.join("g")).unwrap();
        assert!(args[0].starts_with("-Dfabric.modsFolder=") && args[0].contains("builtin-mods"));
        p.loader = Loader::Quilt;
        assert!(prepare_launch(&d, &p, &d.join("g")).unwrap()[0].starts_with("-Dloader.modsDir="));
        p.loader = Loader::Forge;
        assert!(prepare_launch(&d, &p, &d.join("g")).unwrap().is_empty());
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn picks_release_jar_and_installs_idempotently() {
        let d = tmp("pick");
        std::fs::write(d.join("kite-1.21.11-0.1.0.jar"), b"release").unwrap();
        std::fs::write(d.join("kite-1.21.11-0.1.0-sources.jar"), b"src").unwrap();
        std::fs::write(d.join("kite-26.1-0.1.0.jar"), b"other").unwrap();
        let jar = newest_jar(&d, "1.21.11").unwrap();
        assert!(jar.ends_with("kite-1.21.11-0.1.0.jar"));

        let game = d.join("game");
        let dest = install(&game, &jar).unwrap();
        assert_eq!(std::fs::read(&dest).unwrap(), b"release");
        install(&game, &jar).unwrap();
        assert_eq!(std::fs::read_dir(game.join("mods")).unwrap().count(), 1);
        uninstall(&game).unwrap();
        assert!(!dest.exists());
        let _ = std::fs::remove_dir_all(&d);
    }
}
