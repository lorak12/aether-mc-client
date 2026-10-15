//! Mod loader install: merges a loader's launcher profile into the vanilla version JSON.
//! Fabric and Quilt come from their meta servers; Forge and NeoForge run their installers (see `forge`).
use crate::download::Layout;
use crate::profile::Loader;
use crate::version::{Argument, Arguments, Library, VersionJson};
use crate::{forge, Error, Result};
use serde::{Deserialize, Serialize};
use std::collections::HashSet;
use std::path::Path;

#[derive(Debug, Deserialize)]
struct LoaderEntry {
    loader: MetaVersion,
}

#[derive(Debug, Deserialize)]
struct MetaVersion {
    version: String,
    #[serde(default)]
    stable: Option<bool>,
}

/// A loader version the UI can pin.
#[derive(Debug, Clone, Serialize, PartialEq)]
pub struct LoaderVersion {
    pub version: String,
    pub stable: bool,
}

/// The subset of a Fabric/Quilt `profile/json` (or a Forge `version.json`) we need.
#[derive(Debug, Deserialize)]
pub struct LoaderProfile {
    #[serde(rename = "mainClass")]
    pub main_class: String,
    #[serde(default)]
    pub arguments: Option<Arguments>,
    /// Old Forge: replaces the vanilla argument string.
    #[serde(rename = "minecraftArguments", default)]
    pub minecraft_arguments: Option<String>,
    #[serde(default)]
    pub libraries: Vec<Library>,
}

/// Meta server for a Fabric-like loader on `mc`. Fabric itself starts at 1.14; older releases (1.3 to 1.13.2) run the
/// same loader through Legacy Fabric, which publishes the same API with its own intermediary and LWJGL.
fn meta_base(loader: Loader, mc: &str) -> Option<&'static str> {
    match loader {
        Loader::Fabric if is_legacy_fabric(mc) => Some("https://meta.legacyfabric.net/v2"),
        Loader::Fabric => Some("https://meta.fabricmc.net/v2"),
        Loader::Quilt => Some("https://meta.quiltmc.org/v3"),
        _ => None,
    }
}

/// Releases before 1.14 ("1.8.9", "1.12.2", "1.13.2"): Fabric there means Legacy Fabric.
pub fn is_legacy_fabric(mc: &str) -> bool {
    let mut parts = mc.split('.');
    match (parts.next(), parts.next().and_then(|m| m.split(|c: char| !c.is_ascii_digit()).next()).and_then(|m| m.parse::<u32>().ok())) {
        (Some("1"), Some(minor)) => minor <= 13,
        _ => false,
    }
}

/// Quilt publishes no stability flag, so there versions without a pre-release suffix count as stable.
fn is_stable(e: &LoaderEntry) -> bool {
    e.loader.stable.unwrap_or_else(|| !e.loader.version.contains('-'))
}

/// Newest stable loader, else the newest of any kind.
fn pick(entries: &[LoaderEntry]) -> Option<&str> {
    entries.iter().find(|e| is_stable(e)).or(entries.first()).map(|e| e.loader.version.as_str())
}

/// Loader versions that support `mc`, newest first.
pub async fn versions(client: &reqwest::Client, loader: Loader, mc: &str) -> Result<Vec<LoaderVersion>> {
    match loader {
        Loader::Vanilla => Ok(vec![]),
        Loader::Forge | Loader::NeoForge => forge::versions(client, loader, mc).await,
        _ => {
            let base = meta_base(loader, mc).expect("fabric-like");
            let entries: Vec<LoaderEntry> = client.get(format!("{base}/versions/loader/{mc}")).send().await?.error_for_status()?.json().await?;
            Ok(entries.iter().map(|e| LoaderVersion { version: e.loader.version.clone(), stable: is_stable(e) }).collect())
        }
    }
}

/// Apply `loader` to `version` in place: `pinned` loader version, or the newest stable one. No-op for vanilla.
/// Forge and NeoForge run their installer the first time, using `java`.
pub async fn apply(
    client: &reqwest::Client,
    layout: &Layout,
    java: &Path,
    version: &mut VersionJson,
    loader: Loader,
    pinned: Option<&str>,
    progress: impl FnMut(usize, usize),
) -> Result<()> {
    let mc = version.id.clone();
    let name = format!("{loader:?}");
    match loader {
        Loader::Vanilla => Ok(()),
        Loader::Forge | Loader::NeoForge => {
            let lv = match pinned {
                Some(v) => v.to_string(),
                None => forge::recommended(client, loader, &mc).await?.ok_or_else(|| Error::Other(format!("{name} doesn't support Minecraft {mc} yet")))?,
            };
            let profile = forge::install(client, layout, java, version, loader, &lv, progress).await?;
            merge(version, profile);
            Ok(())
        }
        Loader::Fabric | Loader::Quilt => {
            let base = meta_base(loader, &mc).expect("fabric-like");
            let lv = match pinned {
                Some(v) => v.to_string(),
                None => {
                    let entries: Vec<LoaderEntry> =
                        client.get(format!("{base}/versions/loader/{mc}")).send().await?.error_for_status()?.json().await?;
                    pick(&entries).ok_or_else(|| Error::Other(format!("{name} doesn't support Minecraft {mc} yet")))?.to_string()
                }
            };
            let resp = client.get(format!("{base}/versions/loader/{mc}/{lv}/profile/json")).send().await?;
            if resp.status() == reqwest::StatusCode::NOT_FOUND || resp.status() == reqwest::StatusCode::BAD_REQUEST {
                return Err(Error::Other(format!("{name} {lv} isn't available for Minecraft {mc}. Pick another loader version.")));
            }
            let profile: LoaderProfile = resp.error_for_status()?.json().await?;
            merge(version, profile);
            Ok(())
        }
    }
}

/// Loader libraries replace vanilla libraries with the same `group:artifact[:classifier]` (e.g. ASM).
pub fn merge(version: &mut VersionJson, p: LoaderProfile) {
    let ids: HashSet<String> = p.libraries.iter().map(Library::identity).collect();
    let vanilla = std::mem::take(&mut version.libraries);
    version.libraries = p.libraries;
    version.libraries.extend(vanilla.into_iter().filter(|l| !ids.contains(&l.identity())));
    version.main_class = p.main_class;
    if let Some(args) = p.minecraft_arguments {
        version.minecraft_arguments = Some(args);
    }
    if let Some(extra) = p.arguments {
        match &mut version.arguments {
            Some(a) => {
                a.jvm.extend(extra.jvm);
                a.game.extend(extra.game);
            }
            None => {
                // Legacy JSON: loader game args go on the single argument string.
                let plain: Vec<String> = extra.game.into_iter().filter_map(|a| if let Argument::Plain(s) = a { Some(s) } else { None }).collect();
                if !plain.is_empty() {
                    let cur = version.minecraft_arguments.take().unwrap_or_default();
                    version.minecraft_arguments = Some(format!("{cur} {}", plain.join(" ")).trim().to_string());
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn merge_replaces_duplicate_libraries_and_main_class() {
        let mut v: VersionJson = serde_json::from_str(
            r#"{"id":"1.21.11","mainClass":"net.minecraft.client.main.Main","arguments":{"game":["--x"],"jvm":["-cp","${classpath}"]},
            "libraries":[{"name":"org.ow2.asm:asm:9.1"},{"name":"com.mojang:brigadier:1"}],
            "assetIndex":{"id":"27","sha1":"x","size":1,"url":"u"},"downloads":{"client":{"sha1":"x","size":1,"url":"u"}}}"#,
        )
        .unwrap();
        let p: LoaderProfile = serde_json::from_str(
            r#"{"mainClass":"net.fabricmc.loader.impl.launch.knot.KnotClient","arguments":{"game":[],"jvm":["-DFabricMcEmu= net.minecraft.client.main.Main "]},
            "libraries":[{"name":"org.ow2.asm:asm:9.10.1","url":"https://maven.fabricmc.net/"},{"name":"net.fabricmc:fabric-loader:0.19.5","url":"https://maven.fabricmc.net/"}]}"#,
        )
        .unwrap();
        merge(&mut v, p);
        assert_eq!(v.main_class, "net.fabricmc.loader.impl.launch.knot.KnotClient");
        let names: Vec<_> = v.libraries.iter().map(|l| l.name.as_str()).collect();
        assert_eq!(names, ["org.ow2.asm:asm:9.10.1", "net.fabricmc:fabric-loader:0.19.5", "com.mojang:brigadier:1"]);
        assert_eq!(v.arguments.unwrap().jvm.len(), 3);
    }

    #[test]
    fn old_forge_replaces_argument_string() {
        let mut v: VersionJson = serde_json::from_str(
            r#"{"id":"1.12.2","mainClass":"net.minecraft.client.main.Main","minecraftArguments":"--username ${auth_player_name}",
            "libraries":[],"assetIndex":{"id":"1.12","sha1":"x","size":1,"url":"u"},"downloads":{"client":{"sha1":"x","size":1,"url":"u"}}}"#,
        )
        .unwrap();
        let p: LoaderProfile = serde_json::from_str(
            r#"{"mainClass":"net.minecraft.launchwrapper.Launch","minecraftArguments":"--username ${auth_player_name} --tweakClass net.minecraftforge.fml.common.launcher.FMLTweaker","libraries":[]}"#,
        )
        .unwrap();
        merge(&mut v, p);
        assert!(v.minecraft_arguments.unwrap().ends_with("FMLTweaker"));
    }

    #[test]
    fn old_releases_use_legacy_fabric() {
        for v in ["1.7.10", "1.8.9", "1.12.2", "1.13.2", "1.3.2"] {
            assert!(is_legacy_fabric(v), "{v}");
            assert_eq!(meta_base(Loader::Fabric, v), Some("https://meta.legacyfabric.net/v2"));
        }
        for v in ["1.14", "1.14.4", "1.16.5", "1.21.11", "26.3", "26.1.2", "24w14a", "b1.7.3"] {
            assert!(!is_legacy_fabric(v), "{v}");
        }
        assert_eq!(meta_base(Loader::Quilt, "1.8.9"), Some("https://meta.quiltmc.org/v3"));
    }

    #[test]
    fn picks_stable_loader() {
        let e: Vec<LoaderEntry> = serde_json::from_str(r#"[{"loader":{"version":"0.20.0-beta.1"}},{"loader":{"version":"0.19.2"}}]"#).unwrap();
        assert_eq!(pick(&e), Some("0.19.2"));
        let f: Vec<LoaderEntry> = serde_json::from_str(r#"[{"loader":{"version":"0.20.0","stable":false}},{"loader":{"version":"0.19.5","stable":true}}]"#).unwrap();
        assert_eq!(pick(&f), Some("0.19.5"));
        assert_eq!(pick(&[]), None);
    }
}
