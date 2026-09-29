//! Java runtime provisioning from Mojang's runtime manifests (the same JREs the official launcher uses).
use crate::download::{fetch_all, Layout};
use crate::{Error, Result};
use serde::Deserialize;
use std::collections::HashMap;
use std::path::PathBuf;

const RUNTIME_INDEX: &str =
    "https://launchermeta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json";

#[derive(Deserialize)]
struct Release {
    manifest: ManifestRef,
}

#[derive(Deserialize)]
struct ManifestRef {
    url: String,
}

#[derive(Deserialize)]
struct RuntimeManifest {
    files: HashMap<String, RuntimeFile>,
}

#[derive(Deserialize)]
struct RuntimeFile {
    #[serde(rename = "type")]
    kind: String,
    #[serde(default)]
    executable: bool,
    #[serde(default)]
    downloads: Option<RuntimeDownloads>,
    /// Symlink target; only used on Unix.
    #[serde(default)]
    #[cfg_attr(not(unix), allow(dead_code))]
    target: Option<String>,
}

#[derive(Deserialize)]
struct RuntimeDownloads {
    raw: RawDownload,
}

#[derive(Deserialize)]
struct RawDownload {
    sha1: String,
    url: String,
}

/// Mojang's platform key for the current OS/arch, `None` where no runtimes are published.
fn platform() -> Option<&'static str> {
    Some(match (std::env::consts::OS, std::env::consts::ARCH) {
        ("windows", "x86_64") => "windows-x64",
        ("windows", "x86") => "windows-x86",
        ("windows", "aarch64") => "windows-arm64",
        ("macos", "aarch64") => "mac-os-arm64",
        ("macos", _) => "mac-os",
        ("linux", "x86_64") => "linux",
        ("linux", "x86") => "linux-i386",
        _ => return None,
    })
}

/// Path of the java executable inside a runtime directory. `javaw` on Windows so no console window opens.
fn java_exe(dir: &std::path::Path) -> PathBuf {
    if cfg!(windows) {
        dir.join("bin").join("javaw.exe")
    } else if cfg!(target_os = "macos") {
        dir.join("jre.bundle").join("Contents").join("Home").join("bin").join("java")
    } else {
        dir.join("bin").join("java")
    }
}

/// Download (or verify) the Mojang runtime `component` and return its java executable.
pub async fn ensure_runtime(
    client: &reqwest::Client,
    layout: &Layout,
    component: &str,
    mut progress: impl FnMut(usize, usize),
) -> Result<PathBuf> {
    let unavailable = || Error::Other(format!("Mojang publishes no Java runtime '{component}' for this platform"));
    let platform = platform().ok_or_else(unavailable)?;
    let index: HashMap<String, HashMap<String, Vec<Release>>> =
        client.get(RUNTIME_INDEX).send().await?.error_for_status()?.json().await?;
    let release = index.get(platform).and_then(|p| p.get(component)).and_then(|r| r.first()).ok_or_else(unavailable)?;
    let manifest: RuntimeManifest = client.get(&release.manifest.url).send().await?.error_for_status()?.json().await?;

    let dir = layout.root.join("runtimes").join(component);
    let mut jobs = Vec::new();
    let mut executables = Vec::new();
    for (name, f) in &manifest.files {
        let dest = dir.join(name);
        // Reject anything that would escape the runtime directory.
        if name.split(['/', '\\']).any(|c| c == "..") || !dest.starts_with(&dir) {
            continue;
        }
        match (f.kind.as_str(), &f.downloads) {
            ("directory", _) => std::fs::create_dir_all(&dest)?,
            ("file", Some(d)) => {
                jobs.push((d.raw.url.clone(), dest.clone(), Some(d.raw.sha1.clone())));
                if f.executable {
                    executables.push(dest);
                }
            }
            _ => {}
        }
    }
    fetch_all(client, jobs, &mut progress).await?;

    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        for p in &executables {
            std::fs::set_permissions(p, std::fs::Permissions::from_mode(0o755))?;
        }
        for (name, f) in &manifest.files {
            if let ("link", Some(target)) = (f.kind.as_str(), &f.target) {
                let link = dir.join(name);
                if !link.exists() && !name.contains("..") {
                    let _ = std::os::unix::fs::symlink(target, link);
                }
            }
        }
    }
    #[cfg(not(unix))]
    let _ = executables;

    let exe = java_exe(&dir);
    if !exe.exists() {
        return Err(Error::Other(format!("Java runtime installed but {} is missing", exe.display())));
    }
    Ok(exe)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn platform_known_here() {
        if cfg!(all(windows, target_arch = "x86_64")) {
            assert_eq!(platform(), Some("windows-x64"));
        }
    }

    #[test]
    fn parses_runtime_manifest() {
        let j = r#"{"files":{"bin":{"type":"directory"},"bin/java.exe":{"type":"file","executable":true,
        "downloads":{"raw":{"sha1":"ab","size":1,"url":"u"}}},"legal/x":{"type":"link","target":"../y"}}}"#;
        let m: RuntimeManifest = serde_json::from_str(j).unwrap();
        assert_eq!(m.files.len(), 3);
        assert!(m.files["bin/java.exe"].executable);
    }
}
