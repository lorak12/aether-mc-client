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

/// A Java installation the user can pick instead of Mojang's runtime.
#[derive(Debug, Clone, serde::Serialize, PartialEq)]
pub struct JavaInstall {
    pub path: String,
    /// e.g. "21.0.5"
    pub version: String,
    pub major: u32,
    pub vendor: String,
    pub arch: String,
}

/// Major version from a `java.version` string ("1.8.0_402" -> 8, "21.0.5" -> 21).
pub fn major_of(version: &str) -> Option<u32> {
    let mut parts = version.split(['.', '_', '-', '+']);
    let first: u32 = parts.next()?.parse().ok()?;
    if first == 1 {
        parts.next()?.parse().ok()
    } else {
        Some(first)
    }
}

fn parse_properties(out: &str) -> Option<(String, String, String)> {
    let prop = |k: &str| {
        out.lines().find_map(|l| {
            let (key, v) = l.split_once('=')?;
            (key.trim() == k).then(|| v.trim().to_string())
        })
    };
    Some((prop("java.version")?, prop("java.vendor").unwrap_or_default(), prop("os.arch").unwrap_or_default()))
}

/// Run `path -XshowSettings:properties -version` and read what it is. `javaw` is swapped for `java` (no console output).
pub async fn probe(path: &std::path::Path) -> Result<JavaInstall> {
    let mut exe = path.to_path_buf();
    if exe.file_name().and_then(|n| n.to_str()).is_some_and(|n| n.eq_ignore_ascii_case("javaw.exe")) {
        let alt = exe.with_file_name("java.exe");
        if alt.exists() {
            exe = alt;
        }
    }
    if !exe.is_file() {
        return Err(Error::Other(format!("{} doesn't exist", path.display())));
    }
    let mut cmd = tokio::process::Command::new(&exe);
    cmd.args(["-XshowSettings:properties", "-version"]).stdin(std::process::Stdio::null());
    #[cfg(windows)]
    cmd.creation_flags(0x0800_0000);
    let out = tokio::time::timeout(std::time::Duration::from_secs(10), cmd.output())
        .await
        .map_err(|_| Error::Other("Java didn't answer".into()))?
        .map_err(|e| Error::Other(format!("couldn't run {}: {e}", exe.display())))?;
    let text = format!("{}{}", String::from_utf8_lossy(&out.stderr), String::from_utf8_lossy(&out.stdout));
    let (version, vendor, arch) = parse_properties(&text).ok_or_else(|| Error::Other(format!("{} isn't a Java runtime", path.display())))?;
    let major = major_of(&version).unwrap_or(0);
    Ok(JavaInstall { path: path.display().to_string(), version, major, vendor, arch })
}

/// Java executables in the usual install locations, JAVA_HOME, PATH and the launcher's own runtimes.
pub fn candidates(layout: &Layout) -> Vec<PathBuf> {
    let exe = if cfg!(windows) { "javaw.exe" } else { "java" };
    let mut roots: Vec<PathBuf> = vec![layout.root.join("runtimes")];
    if cfg!(windows) {
        for var in ["ProgramFiles", "ProgramFiles(x86)", "ProgramW6432"] {
            if let Some(p) = std::env::var_os(var) {
                let p = PathBuf::from(p);
                for vendor in ["Java", "Eclipse Adoptium", "Eclipse Foundation", "Microsoft", "Zulu", "BellSoft", "Amazon Corretto", "Semeru", "GraalVM"] {
                    roots.push(p.join(vendor));
                }
            }
        }
        if let Some(home) = std::env::var_os("USERPROFILE") {
            roots.push(PathBuf::from(home).join(".jdks"));
        }
    } else if cfg!(target_os = "macos") {
        roots.push("/Library/Java/JavaVirtualMachines".into());
    } else {
        roots.extend(["/usr/lib/jvm".into(), "/usr/lib64/jvm".into(), "/opt/java".into()]);
        if let Some(home) = std::env::var_os("HOME") {
            roots.push(PathBuf::from(home).join(".jdks"));
        }
    }
    let mut found: Vec<PathBuf> = Vec::new();
    let mut push = |p: PathBuf| {
        if p.is_file() && !found.contains(&p) {
            found.push(p);
        }
    };
    if let Some(h) = std::env::var_os("JAVA_HOME") {
        push(PathBuf::from(h).join("bin").join(exe));
    }
    for root in roots {
        let Ok(rd) = std::fs::read_dir(&root) else { continue };
        for e in rd.flatten() {
            let d = e.path();
            push(java_exe(&d));
            push(d.join("bin").join(exe));
            push(d.join("Contents").join("Home").join("bin").join(exe));
        }
    }
    if let Some(path) = std::env::var_os("PATH") {
        for d in std::env::split_paths(&path) {
            push(d.join(exe));
        }
    }
    found
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn versions_and_properties() {
        assert_eq!(major_of("1.8.0_402"), Some(8));
        assert_eq!(major_of("21.0.5"), Some(21));
        assert_eq!(major_of("17"), Some(17));
        assert_eq!(major_of("x"), None);
        let out = "Property settings:\n    java.vendor = Eclipse Adoptium\n    java.version = 21.0.5\n    os.arch = amd64\n";
        assert_eq!(parse_properties(out), Some(("21.0.5".into(), "Eclipse Adoptium".into(), "amd64".into())));
    }

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
