//! Verified, parallel downloads into a shared cache, plus version install (jar, libraries, natives, assets).
use crate::version::{Artifact, AssetIndex, VersionJson};
use crate::{Error, Result};
use futures::stream::{self, StreamExt};
use sha1::{Digest, Sha1};
use std::path::{Path, PathBuf};
use tokio::io::AsyncWriteExt;

const CONCURRENCY: usize = 16;
const ASSET_BASE: &str = "https://resources.download.minecraft.net";

/// Directory layout shared by all profiles.
#[derive(Debug, Clone)]
pub struct Layout {
    pub root: PathBuf,
}

impl Layout {
    pub fn new(root: impl Into<PathBuf>) -> Self {
        Self { root: root.into() }
    }
    pub fn libraries(&self) -> PathBuf {
        self.root.join("libraries")
    }
    pub fn assets(&self) -> PathBuf {
        self.root.join("assets")
    }
    pub fn version_dir(&self, id: &str) -> PathBuf {
        self.root.join("versions").join(id)
    }
    pub fn client_jar(&self, id: &str) -> PathBuf {
        self.version_dir(id).join(format!("{id}.jar"))
    }
    pub fn natives_dir(&self, id: &str) -> PathBuf {
        self.version_dir(id).join("natives")
    }
}

pub fn sha1_hex(bytes: &[u8]) -> String {
    hex::encode(Sha1::digest(bytes))
}

async fn file_matches(path: &Path, sha1: &str) -> bool {
    match tokio::fs::read(path).await {
        Ok(b) => sha1_hex(&b).eq_ignore_ascii_case(sha1),
        Err(_) => false,
    }
}

/// Download `url` to `dest` unless a file with the expected SHA1 is already present.
/// Writes to a `.part` file and renames only after verification.
pub async fn fetch_verified(client: &reqwest::Client, url: &str, dest: &Path, sha1: &str) -> Result<()> {
    if file_matches(dest, sha1).await {
        return Ok(());
    }
    if let Some(parent) = dest.parent() {
        tokio::fs::create_dir_all(parent).await?;
    }
    let part = dest.with_extension("part");
    let mut resp = client.get(url).send().await?.error_for_status()?;
    let mut hasher = Sha1::new();
    let mut file = tokio::fs::File::create(&part).await?;
    while let Some(chunk) = resp.chunk().await? {
        hasher.update(&chunk);
        file.write_all(&chunk).await?;
    }
    file.flush().await?;
    drop(file);
    let actual = hex::encode(hasher.finalize());
    if !actual.eq_ignore_ascii_case(sha1) {
        let _ = tokio::fs::remove_file(&part).await;
        return Err(Error::Checksum { path: dest.display().to_string(), expected: sha1.into(), actual });
    }
    tokio::fs::rename(&part, dest).await?;
    Ok(())
}

/// Like `fetch_verified`, but for files published without a checksum (some Maven libraries):
/// an existing file is trusted, a missing one is downloaded as-is.
pub async fn fetch_maybe_verified(client: &reqwest::Client, url: &str, dest: &Path, sha1: Option<&str>) -> Result<()> {
    if let Some(sha1) = sha1 {
        return fetch_verified(client, url, dest, sha1).await;
    }
    if tokio::fs::try_exists(dest).await.unwrap_or(false) {
        return Ok(());
    }
    if let Some(parent) = dest.parent() {
        tokio::fs::create_dir_all(parent).await?;
    }
    let bytes = client.get(url).send().await?.error_for_status()?.bytes().await?;
    let part = dest.with_extension("part");
    tokio::fs::write(&part, &bytes).await?;
    tokio::fs::rename(&part, dest).await?;
    Ok(())
}

pub(crate) type Job = (String, PathBuf, Option<String>);

pub(crate) async fn fetch_all(client: &reqwest::Client, jobs: Vec<Job>, mut progress: impl FnMut(usize, usize)) -> Result<()> {
    let total = jobs.len();
    let mut done = 0;
    let mut results = stream::iter(jobs.into_iter().map(|(url, dest, sha)| {
        let client = client.clone();
        async move { fetch_maybe_verified(&client, &url, &dest, sha.as_deref()).await }
    }))
    .buffer_unordered(CONCURRENCY);
    while let Some(r) = results.next().await {
        r?;
        done += 1;
        progress(done, total);
    }
    Ok(())
}

/// Files a version needs on disk. Returned so the launcher can build the classpath.
#[derive(Debug, Default)]
pub struct Installed {
    pub classpath: Vec<PathBuf>,
    pub natives_dir: PathBuf,
    /// JVM argument pointing at Mojang's log4j config, already expanded.
    pub log_arg: Option<String>,
}

/// Download everything required to run `version`: client jar, libraries, legacy natives (extracted), assets.
pub async fn install_version(
    client: &reqwest::Client,
    layout: &Layout,
    version: &VersionJson,
    mut progress: impl FnMut(&str, usize, usize),
) -> Result<Installed> {
    let mut installed = Installed { natives_dir: layout.natives_dir(&version.id), ..Default::default() };

    // Libraries
    let mut jobs = Vec::new();
    let mut native_jars: Vec<(PathBuf, Vec<String>)> = Vec::new();
    for lib in version.libraries.iter().filter(|l| l.is_allowed()) {
        if let Some(d) = lib.main_download() {
            let dest = layout.libraries().join(&d.path);
            if !installed.classpath.contains(&dest) {
                jobs.push((d.url, dest.clone(), d.sha1));
                installed.classpath.push(dest);
            }
        }
        if let Some(a) = lib.native_artifact() {
            let dest = artifact_dest(layout, a);
            jobs.push((a.url.clone(), dest.clone(), Some(a.sha1.clone())));
            let exclude = lib.extract.as_ref().map(|e| e.exclude.clone()).unwrap_or_default();
            native_jars.push((dest, exclude));
        }
    }
    fetch_all(client, jobs, |d, t| progress("libraries", d, t)).await?;
    for (jar, exclude) in native_jars {
        extract_natives(&jar, &installed.natives_dir, &exclude)?;
    }

    // Client jar
    let jar = layout.client_jar(&version.id);
    fetch_verified(client, &version.downloads.client.url, &jar, &version.downloads.client.sha1).await?;
    installed.classpath.push(jar);

    // Assets
    let index_path = layout.assets().join("indexes").join(format!("{}.json", version.asset_index.id));
    fetch_verified(client, &version.asset_index.url, &index_path, &version.asset_index.sha1).await?;
    let index: AssetIndex = serde_json::from_slice(&tokio::fs::read(&index_path).await?)?;
    let object_path = |hash: &str| layout.assets().join("objects").join(&hash[..2]).join(hash);
    let jobs = index
        .objects
        .values()
        .map(|o| (format!("{ASSET_BASE}/{}/{}", &o.hash[..2], o.hash), object_path(&o.hash), Some(o.hash.clone())))
        .collect();
    fetch_all(client, jobs, |d, t| progress("assets", d, t)).await?;

    // Very old indexes expect assets by name under assets/virtual/legacy.
    if index.r#virtual || index.map_to_resources {
        let virt = layout.assets().join("virtual").join("legacy");
        for (name, o) in &index.objects {
            let dest = virt.join(name);
            if !dest.starts_with(&virt) || name.contains("..") || dest.exists() {
                continue;
            }
            if let Some(p) = dest.parent() {
                std::fs::create_dir_all(p)?;
            }
            std::fs::copy(object_path(&o.hash), dest)?;
        }
    }

    // log4j config (also the Log4Shell mitigation for older versions).
    if let Some(log) = version.logging.as_ref().and_then(|l| l.client.as_ref()) {
        let path = layout.assets().join("log_configs").join(&log.file.id);
        fetch_verified(client, &log.file.url, &path, &log.file.sha1).await?;
        installed.log_arg = Some(log.argument.replace("${path}", &path.display().to_string()));
    }

    Ok(installed)
}

fn artifact_dest(layout: &Layout, a: &Artifact) -> PathBuf {
    let rel = a.path.clone().unwrap_or_else(|| a.url.rsplit('/').next().unwrap_or("unknown.jar").to_string());
    layout.libraries().join(rel)
}

/// Extract native libraries from `jar` into `dest`, skipping excluded prefixes (e.g. META-INF/).
pub fn extract_natives(jar: &Path, dest: &Path, exclude: &[String]) -> Result<()> {
    std::fs::create_dir_all(dest)?;
    let mut zip = zip::ZipArchive::new(std::fs::File::open(jar)?)?;
    for i in 0..zip.len() {
        let mut entry = zip.by_index(i)?;
        if entry.is_dir() {
            continue;
        }
        // enclosed_name rejects path traversal ("zip slip").
        let Some(name) = entry.enclosed_name() else { continue };
        let name_str = name.to_string_lossy().replace('\\', "/");
        if exclude.iter().any(|e| name_str.starts_with(e.as_str())) {
            continue;
        }
        let out = dest.join(&name);
        if let Some(p) = out.parent() {
            std::fs::create_dir_all(p)?;
        }
        let mut f = std::fs::File::create(out)?;
        std::io::copy(&mut entry, &mut f)?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sha1_known_vector() {
        assert_eq!(sha1_hex(b"abc"), "a9993e364706816aba3e25717850c26c9cd0d89d");
    }

    #[test]
    fn extract_skips_excluded_and_traversal() {
        use std::io::Write;
        let dir = std::env::temp_dir().join(format!("aether-test-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let jar = dir.join("n.jar");
        {
            let mut w = zip::ZipWriter::new(std::fs::File::create(&jar).unwrap());
            let opts = zip::write::SimpleFileOptions::default();
            w.start_file("lwjgl.dll", opts).unwrap();
            w.write_all(b"x").unwrap();
            w.start_file("META-INF/MANIFEST.MF", opts).unwrap();
            w.write_all(b"x").unwrap();
            w.start_file("../evil.dll", opts).unwrap();
            w.write_all(b"x").unwrap();
            w.finish().unwrap();
        }
        let out = dir.join("out");
        extract_natives(&jar, &out, &["META-INF/".to_string()]).unwrap();
        assert!(out.join("lwjgl.dll").exists());
        assert!(!out.join("META-INF").exists());
        assert!(!dir.join("evil.dll").exists());
        let _ = std::fs::remove_dir_all(&dir);
    }
}
