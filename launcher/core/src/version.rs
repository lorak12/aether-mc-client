//! Mojang version manifest + per-version JSON models (modern and legacy formats).
use crate::rules::{self, Rule};
use crate::Result;
use serde::Deserialize;
use std::collections::HashMap;

pub const MANIFEST_URL: &str = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

#[derive(Debug, Deserialize)]
pub struct Manifest {
    pub latest: Latest,
    pub versions: Vec<ManifestEntry>,
}

#[derive(Debug, Deserialize)]
pub struct Latest {
    pub release: String,
    pub snapshot: String,
}

#[derive(Debug, Clone, Deserialize)]
pub struct ManifestEntry {
    pub id: String,
    #[serde(rename = "type")]
    pub kind: String,
    pub url: String,
    pub sha1: String,
}

impl Manifest {
    pub async fn fetch(client: &reqwest::Client) -> Result<Manifest> {
        Ok(client.get(MANIFEST_URL).send().await?.error_for_status()?.json().await?)
    }
    pub fn find(&self, id: &str) -> Option<&ManifestEntry> {
        self.versions.iter().find(|v| v.id == id)
    }
}

#[derive(Debug, Clone, Deserialize)]
pub struct Artifact {
    #[serde(default)]
    pub path: Option<String>,
    pub sha1: String,
    pub size: u64,
    pub url: String,
}

#[derive(Debug, Clone, Deserialize)]
pub struct AssetIndexRef {
    pub id: String,
    pub sha1: String,
    pub size: u64,
    pub url: String,
}

#[derive(Debug, Clone, Deserialize)]
pub struct JavaVersion {
    pub component: String,
    #[serde(rename = "majorVersion")]
    pub major_version: u32,
}

#[derive(Debug, Clone, Deserialize)]
pub struct LibraryDownloads {
    pub artifact: Option<Artifact>,
    #[serde(default)]
    pub classifiers: HashMap<String, Artifact>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct Extract {
    #[serde(default)]
    pub exclude: Vec<String>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct Library {
    pub name: String,
    #[serde(default)]
    pub downloads: Option<LibraryDownloads>,
    /// Maven repository base (Fabric/Quilt style libraries have no `downloads` block).
    #[serde(default)]
    pub url: Option<String>,
    #[serde(default)]
    pub sha1: Option<String>,
    #[serde(default)]
    pub rules: Vec<Rule>,
    /// Legacy natives: os name -> classifier (may contain `${arch}`).
    #[serde(default)]
    pub natives: HashMap<String, String>,
    #[serde(default)]
    pub extract: Option<Extract>,
}

impl Library {
    pub fn is_allowed(&self) -> bool {
        rules::allowed(&self.rules, &HashMap::new())
    }

    /// Classifier artifact for native libs on the current OS (legacy 1.7-1.18 style).
    pub fn native_artifact(&self) -> Option<&Artifact> {
        let classifier = self.natives.get(rules::current_os())?;
        let bits = if cfg!(target_pointer_width = "64") { "64" } else { "32" };
        let classifier = classifier.replace("${arch}", bits);
        self.downloads.as_ref()?.classifiers.get(&classifier)
    }

    /// Classpath jar: Mojang `downloads.artifact`, or a Maven path built from the coordinates when only `url` is given.
    /// Modern (1.19+) natives are ordinary artifacts gated by rules; they go on the classpath too.
    pub fn main_download(&self) -> Option<LibDownload> {
        if let Some(a) = self.downloads.as_ref().and_then(|d| d.artifact.as_ref()) {
            let path = a.path.clone().unwrap_or_else(|| a.url.rsplit('/').next().unwrap_or("unknown.jar").to_string());
            return Some(LibDownload { url: a.url.clone(), path, sha1: Some(a.sha1.clone()) });
        }
        let base = self.url.as_deref()?;
        let path = maven_path(&self.name)?;
        Some(LibDownload { url: format!("{}/{path}", base.trim_end_matches('/')), path, sha1: self.sha1.clone() })
    }

    /// `group:artifact[:classifier]`, used to let loader libraries replace vanilla ones.
    pub fn identity(&self) -> String {
        let p: Vec<&str> = self.name.split('@').next().unwrap_or("").split(':').collect();
        match p.as_slice() {
            [g, a, _, c, ..] => format!("{g}:{a}:{c}"),
            [g, a, ..] => format!("{g}:{a}"),
            _ => self.name.clone(),
        }
    }
}

/// A resolved library jar: where to fetch it, where it goes under `libraries/`, and its checksum if known.
#[derive(Debug, Clone)]
pub struct LibDownload {
    pub url: String,
    pub path: String,
    pub sha1: Option<String>,
}

/// `group:artifact:version[:classifier][@ext]` -> `group/path/artifact/version/artifact-version[-classifier].ext`
pub fn maven_path(coords: &str) -> Option<String> {
    let (coords, ext) = coords.split_once('@').unwrap_or((coords, "jar"));
    let p: Vec<&str> = coords.split(':').collect();
    let (g, a, v) = (p.first()?, p.get(1)?, p.get(2)?);
    let file = match p.get(3) {
        Some(c) => format!("{a}-{v}-{c}.{ext}"),
        None => format!("{a}-{v}.{ext}"),
    };
    Some(format!("{}/{a}/{v}/{file}", g.replace('.', "/")))
}

#[derive(Debug, Clone, Deserialize)]
#[serde(untagged)]
pub enum ArgValue {
    One(String),
    Many(Vec<String>),
}

#[derive(Debug, Clone, Deserialize)]
#[serde(untagged)]
pub enum Argument {
    Plain(String),
    Conditional { rules: Vec<Rule>, value: ArgValue },
}

#[derive(Debug, Clone, Deserialize, Default)]
pub struct Arguments {
    #[serde(default)]
    pub game: Vec<Argument>,
    #[serde(default)]
    pub jvm: Vec<Argument>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct Downloads {
    pub client: Artifact,
}

#[derive(Debug, Clone, Deserialize)]
pub struct VersionJson {
    pub id: String,
    #[serde(rename = "mainClass")]
    pub main_class: String,
    #[serde(default)]
    pub arguments: Option<Arguments>,
    /// Legacy (<= 1.12.2) single string of game args.
    #[serde(rename = "minecraftArguments", default)]
    pub minecraft_arguments: Option<String>,
    pub libraries: Vec<Library>,
    #[serde(rename = "assetIndex")]
    pub asset_index: AssetIndexRef,
    pub assets: Option<String>,
    pub downloads: Downloads,
    #[serde(rename = "javaVersion", default)]
    pub java_version: Option<JavaVersion>,
    #[serde(default)]
    pub logging: Option<Logging>,
}

/// Mojang-supplied log4j config; for 1.7-1.18 it is also the Log4Shell mitigation.
#[derive(Debug, Clone, Deserialize)]
pub struct Logging {
    pub client: Option<LoggingClient>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct LoggingClient {
    /// e.g. `-Dlog4j.configurationFile=${path}`
    pub argument: String,
    pub file: LogFile,
}

#[derive(Debug, Clone, Deserialize)]
pub struct LogFile {
    pub id: String,
    pub sha1: String,
    pub url: String,
}

impl VersionJson {
    pub async fn fetch(client: &reqwest::Client, entry: &ManifestEntry) -> Result<VersionJson> {
        Ok(client.get(&entry.url).send().await?.error_for_status()?.json().await?)
    }

    /// Java major version required; pre-1.17 JSONs omit the field => 8.
    pub fn java_major(&self) -> u32 {
        self.java_version.as_ref().map(|j| j.major_version).unwrap_or(8)
    }

    /// Mojang Java runtime component (e.g. `java-runtime-delta`); `jre-legacy` when the JSON predates the field.
    pub fn java_component(&self) -> &str {
        self.java_version.as_ref().map(|j| j.component.as_str()).unwrap_or("jre-legacy")
    }
}

#[derive(Debug, Deserialize)]
pub struct AssetIndex {
    pub objects: HashMap<String, AssetObject>,
    #[serde(default)]
    pub map_to_resources: bool,
    #[serde(default)]
    pub r#virtual: bool,
}

#[derive(Debug, Deserialize)]
pub struct AssetObject {
    pub hash: String,
    pub size: u64,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_legacy_version() {
        let j = r#"{"id":"1.8.9","mainClass":"net.minecraft.client.main.Main","minecraftArguments":"--username ${auth_player_name}",
        "libraries":[{"name":"a:b:1","natives":{"windows":"natives-windows"}}],
        "assetIndex":{"id":"1.8","sha1":"x","size":1,"url":"u"},"assets":"1.8",
        "downloads":{"client":{"sha1":"x","size":1,"url":"u"}}}"#;
        let v: VersionJson = serde_json::from_str(j).unwrap();
        assert_eq!(v.java_major(), 8);
        assert!(v.minecraft_arguments.is_some());
    }

    #[test]
    fn parses_modern_arguments() {
        let j = r#"{"id":"1.21.11","mainClass":"m","arguments":{"game":["--x",{"rules":[{"action":"allow","features":{"is_demo_user":true}}],"value":"--demo"}],
        "jvm":[{"rules":[{"action":"allow","os":{"name":"windows"}}],"value":["-Da=b","-Dc=d"]}]},
        "libraries":[],"assetIndex":{"id":"27","sha1":"x","size":1,"url":"u"},
        "downloads":{"client":{"sha1":"x","size":1,"url":"u"}},"javaVersion":{"component":"java-runtime-delta","majorVersion":21}}"#;
        let v: VersionJson = serde_json::from_str(j).unwrap();
        assert_eq!(v.java_major(), 21);
        assert_eq!(v.java_component(), "java-runtime-delta");
        assert_eq!(v.arguments.unwrap().game.len(), 2);
    }

    #[test]
    fn maven_coordinates() {
        assert_eq!(maven_path("net.fabricmc:fabric-loader:0.19.5").unwrap(), "net/fabricmc/fabric-loader/0.19.5/fabric-loader-0.19.5.jar");
        assert_eq!(maven_path("a.b:c:1:natives-windows").unwrap(), "a/b/c/1/c-1-natives-windows.jar");
        assert_eq!(maven_path("a:b:1@zip").unwrap(), "a/b/1/b-1.zip");
        assert!(maven_path("broken").is_none());
    }

    #[test]
    fn maven_library_download() {
        let l: Library = serde_json::from_str(r#"{"name":"org.ow2.asm:asm:9.10.1","url":"https://maven.fabricmc.net/","sha1":"ab"}"#).unwrap();
        let d = l.main_download().unwrap();
        assert_eq!(d.url, "https://maven.fabricmc.net/org/ow2/asm/asm/9.10.1/asm-9.10.1.jar");
        assert_eq!(d.sha1.as_deref(), Some("ab"));
        assert_eq!(l.identity(), "org.ow2.asm:asm");
    }
}
