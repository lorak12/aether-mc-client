//! Mod loader install: merges a loader's launcher profile (Fabric / Quilt meta) into the vanilla version JSON.
use crate::profile::Loader;
use crate::version::{Argument, Arguments, Library, VersionJson};
use crate::{Error, Result};
use serde::Deserialize;
use std::collections::HashSet;

#[derive(Debug, Deserialize)]
struct LoaderEntry {
    loader: LoaderVersion,
}

#[derive(Debug, Deserialize)]
struct LoaderVersion {
    version: String,
    #[serde(default)]
    stable: Option<bool>,
}

/// The subset of a Fabric/Quilt `profile/json` we need.
#[derive(Debug, Deserialize)]
pub struct LoaderProfile {
    #[serde(rename = "mainClass")]
    pub main_class: String,
    #[serde(default)]
    pub arguments: Option<Arguments>,
    #[serde(default)]
    pub libraries: Vec<Library>,
}

fn meta_base(loader: Loader) -> Result<&'static str> {
    match loader {
        Loader::Fabric => Ok("https://meta.fabricmc.net/v2"),
        Loader::Quilt => Ok("https://meta.quiltmc.org/v3"),
        Loader::Forge | Loader::NeoForge => {
            Err(Error::Other("Forge and NeoForge profiles can't be launched yet. Use a Fabric, Quilt or Vanilla profile.".into()))
        }
        Loader::Vanilla => Err(Error::Other("vanilla has no loader".into())),
    }
}

/// Newest stable loader; Quilt publishes no stability flag, so there we prefer versions without a pre-release suffix.
fn pick(entries: &[LoaderEntry]) -> Option<&str> {
    entries
        .iter()
        .find(|e| e.loader.stable.unwrap_or_else(|| !e.loader.version.contains('-')))
        .or(entries.first())
        .map(|e| e.loader.version.as_str())
}

/// Apply `loader` to `version` in place. No-op for vanilla.
pub async fn apply(client: &reqwest::Client, version: &mut VersionJson, loader: Loader) -> Result<()> {
    if loader == Loader::Vanilla {
        return Ok(());
    }
    let base = meta_base(loader)?;
    let mc = version.id.clone();
    let entries: Vec<LoaderEntry> = client.get(format!("{base}/versions/loader/{mc}")).send().await?.error_for_status()?.json().await?;
    let name = format!("{loader:?}");
    let lv = pick(&entries).ok_or_else(|| Error::Other(format!("{name} doesn't support Minecraft {mc} yet")))?;
    let profile: LoaderProfile =
        client.get(format!("{base}/versions/loader/{mc}/{lv}/profile/json")).send().await?.error_for_status()?.json().await?;
    merge(version, profile);
    Ok(())
}

/// Loader libraries replace vanilla libraries with the same `group:artifact[:classifier]` (e.g. ASM).
pub fn merge(version: &mut VersionJson, p: LoaderProfile) {
    let ids: HashSet<String> = p.libraries.iter().map(Library::identity).collect();
    let vanilla = std::mem::take(&mut version.libraries);
    version.libraries = p.libraries;
    version.libraries.extend(vanilla.into_iter().filter(|l| !ids.contains(&l.identity())));
    version.main_class = p.main_class;
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
    fn picks_stable_loader() {
        let e: Vec<LoaderEntry> = serde_json::from_str(
            r#"[{"loader":{"version":"0.20.0-beta.1"}},{"loader":{"version":"0.19.2"}}]"#,
        )
        .unwrap();
        assert_eq!(pick(&e), Some("0.19.2"));
        let f: Vec<LoaderEntry> = serde_json::from_str(r#"[{"loader":{"version":"0.20.0","stable":false}},{"loader":{"version":"0.19.5","stable":true}}]"#).unwrap();
        assert_eq!(pick(&f), Some("0.19.5"));
        assert_eq!(pick(&[]), None);
    }
}
