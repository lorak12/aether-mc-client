//! Modrinth API v2 client (no key required; a descriptive User-Agent is mandatory).
use crate::config::USER_AGENT;
use crate::Result;
use serde::Deserialize;
use std::collections::HashMap;

const BASE: &str = "https://api.modrinth.com/v2";

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, serde::Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum ProjectType {
    Mod,
    Resourcepack,
    Shader,
    Datapack,
    Modpack,
}

impl ProjectType {
    pub fn as_str(self) -> &'static str {
        match self {
            ProjectType::Mod => "mod",
            ProjectType::Resourcepack => "resourcepack",
            ProjectType::Shader => "shader",
            ProjectType::Datapack => "datapack",
            ProjectType::Modpack => "modpack",
        }
    }

    /// Profile subfolder for this type. Datapacks are per-world (`saves/<world>/datapacks`), handled by the caller.
    pub fn folder(self) -> &'static str {
        match self {
            ProjectType::Mod => "mods",
            ProjectType::Resourcepack => "resourcepacks",
            ProjectType::Shader => "shaderpacks",
            ProjectType::Datapack => "datapacks",
            ProjectType::Modpack => "",
        }
    }

    /// Modrinth `loaders` values to filter versions by. Only mods use the profile's mod loader.
    pub fn loader_filter(self, profile_loader: Option<&str>) -> Vec<String> {
        match self {
            ProjectType::Mod | ProjectType::Modpack => profile_loader.map(|l| vec![l.to_string()]).unwrap_or_default(),
            ProjectType::Resourcepack => vec!["minecraft".into()],
            ProjectType::Shader => vec!["iris".into(), "optifine".into(), "canvas".into(), "vanilla".into()],
            ProjectType::Datapack => vec!["datapack".into()],
        }
    }
}

#[derive(Debug, Clone, Deserialize, serde::Serialize)]
pub struct SearchHit {
    pub project_id: String,
    pub slug: String,
    pub title: String,
    pub description: String,
    pub icon_url: Option<String>,
    pub downloads: u64,
    pub author: String,
    pub categories: Vec<String>,
    pub project_type: String,
}

#[derive(Debug, Deserialize, serde::Serialize)]
pub struct SearchResponse {
    pub hits: Vec<SearchHit>,
    pub total_hits: u64,
}

#[derive(Debug, Clone, Deserialize)]
pub struct Hashes {
    pub sha1: String,
    #[serde(default)]
    pub sha512: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct VersionFile {
    pub url: String,
    pub filename: String,
    pub primary: bool,
    pub size: u64,
    pub hashes: Hashes,
}

#[derive(Debug, Clone, Deserialize)]
pub struct Dependency {
    pub version_id: Option<String>,
    pub project_id: Option<String>,
    pub dependency_type: String,
}

#[derive(Debug, Clone, Deserialize)]
pub struct Version {
    pub id: String,
    pub project_id: String,
    pub name: String,
    pub version_number: String,
    pub version_type: String,
    pub game_versions: Vec<String>,
    pub loaders: Vec<String>,
    pub files: Vec<VersionFile>,
    #[serde(default)]
    pub dependencies: Vec<Dependency>,
    #[serde(default)]
    pub date_published: String,
}

impl Version {
    pub fn primary_file(&self) -> Option<&VersionFile> {
        self.files.iter().find(|f| f.primary).or_else(|| self.files.first())
    }
}

#[derive(Debug, Clone, Deserialize)]
pub struct Project {
    pub id: String,
    pub slug: String,
    pub title: String,
    pub description: String,
    pub project_type: String,
    pub icon_url: Option<String>,
}

#[derive(Clone)]
pub struct Modrinth {
    client: reqwest::Client,
    base: String,
}

impl Modrinth {
    pub fn new() -> Result<Self> {
        let client = reqwest::Client::builder().user_agent(USER_AGENT).build()?;
        Ok(Self { client, base: BASE.into() })
    }

    pub fn http(&self) -> &reqwest::Client {
        &self.client
    }

    /// Search with facets. `mc_version`/`loader` narrow results to installable content.
    pub async fn search(
        &self,
        query: &str,
        kind: ProjectType,
        mc_version: Option<&str>,
        loader: Option<&str>,
        offset: u32,
        limit: u32,
    ) -> Result<SearchResponse> {
        let facets = build_facets(kind, mc_version, loader);
        let r = self
            .client
            .get(format!("{}/search", self.base))
            .query(&[
                ("query", query),
                ("facets", facets.as_str()),
                ("offset", &offset.to_string()),
                ("limit", &limit.min(50).to_string()),
                ("index", if query.is_empty() { "downloads" } else { "relevance" }),
            ])
            .send()
            .await?
            .error_for_status()?;
        Ok(r.json().await?)
    }

    pub async fn project(&self, id_or_slug: &str) -> Result<Project> {
        Ok(self.client.get(format!("{}/project/{}", self.base, id_or_slug)).send().await?.error_for_status()?.json().await?)
    }

    /// Versions of a project compatible with the given game version and loaders, newest first.
    pub async fn versions(&self, project: &str, mc_version: &str, loaders: &[String]) -> Result<Vec<Version>> {
        let mut q: Vec<(&str, String)> = vec![("game_versions", serde_json::to_string(&[mc_version])?)];
        if !loaders.is_empty() {
            q.push(("loaders", serde_json::to_string(loaders)?));
        }
        let r = self
            .client
            .get(format!("{}/project/{}/version", self.base, project))
            .query(&q)
            .send()
            .await?
            .error_for_status()?;
        Ok(r.json().await?)
    }

    pub async fn version(&self, id: &str) -> Result<Version> {
        Ok(self.client.get(format!("{}/version/{}", self.base, id)).send().await?.error_for_status()?.json().await?)
    }

    /// Identify local files by SHA1 (map: hash -> version).
    pub async fn versions_by_hashes(&self, sha1s: &[String]) -> Result<HashMap<String, Version>> {
        let r = self
            .client
            .post(format!("{}/version_files", self.base))
            .json(&serde_json::json!({"hashes": sha1s, "algorithm": "sha1"}))
            .send()
            .await?
            .error_for_status()?;
        Ok(r.json().await?)
    }

    /// Latest compatible version for each installed file hash (map: hash -> newest version).
    pub async fn latest_for_hashes(
        &self,
        sha1s: &[String],
        loaders: &[String],
        mc_version: &str,
    ) -> Result<HashMap<String, Version>> {
        let r = self
            .client
            .post(format!("{}/version_files/update", self.base))
            .json(&serde_json::json!({
                "hashes": sha1s, "algorithm": "sha1", "loaders": loaders, "game_versions": [mc_version]
            }))
            .send()
            .await?
            .error_for_status()?;
        Ok(r.json().await?)
    }
}

/// Modrinth facet syntax: outer array = AND, inner array = OR.
pub fn build_facets(kind: ProjectType, mc_version: Option<&str>, loader: Option<&str>) -> String {
    let mut f: Vec<Vec<String>> = vec![vec![format!("project_type:{}", kind.as_str())]];
    if let Some(v) = mc_version {
        f.push(vec![format!("versions:{v}")]);
    }
    if kind == ProjectType::Mod {
        if let Some(l) = loader {
            f.push(vec![format!("categories:{l}")]);
        }
    }
    serde_json::to_string(&f).unwrap()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn facets_shape() {
        let f = build_facets(ProjectType::Mod, Some("1.21.11"), Some("fabric"));
        assert_eq!(f, r#"[["project_type:mod"],["versions:1.21.11"],["categories:fabric"]]"#);
        // loader facet only applies to mods
        let f = build_facets(ProjectType::Shader, Some("1.21.11"), Some("fabric"));
        assert!(!f.contains("fabric"));
    }

    #[test]
    fn loader_filters() {
        assert_eq!(ProjectType::Mod.loader_filter(Some("fabric")), vec!["fabric"]);
        assert!(ProjectType::Mod.loader_filter(None).is_empty());
        assert!(ProjectType::Shader.loader_filter(Some("fabric")).contains(&"iris".to_string()));
        assert_eq!(ProjectType::Datapack.loader_filter(None), vec!["datapack"]);
    }
}
