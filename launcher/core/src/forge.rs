//! Forge and NeoForge: version lists and the installer flow the official installer runs for a client install.
//!
//! Modern installers (Forge 1.13+, every NeoForge) carry `install_profile.json` (libraries, data, processors) and
//! `version.json` (the launcher profile). We download/extract the libraries, run the client-side processors with the
//! profile's Java (they patch the vanilla jar into the loader's client jar), then merge `version.json` like Fabric's.
//! Old installers (spec 0, Forge 1.12.2 and earlier) carry the universal jar and a `versionInfo` profile instead.
//! Results are cached under `<root>/loaders/<loader>-<version>/` so later launches skip the processors.
use crate::download::{fetch_maybe_verified, fetch_verified, sha1_hex, Layout};
use crate::loader::{LoaderProfile, LoaderVersion};
use crate::profile::Loader;
use crate::version::{maven_path, Library, VersionJson};
use crate::{Error, Result};
use serde::Deserialize;
use std::collections::HashMap;
use std::io::Read;
use std::path::{Path, PathBuf};

const FORGE_MAVEN: &str = "https://maven.minecraftforge.net";
const NEO_MAVEN: &str = "https://maven.neoforged.net/releases";
const FORGE_PROMOTIONS: &str = "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json";

/// Maven coordinates of the installer artifact (`group:artifact`), and the version string used on the maven.
struct Artifact {
    maven: &'static str,
    group_path: &'static str,
    artifact: &'static str,
}

fn artifact(loader: Loader, mc: &str) -> Result<Artifact> {
    Ok(match loader {
        Loader::Forge => Artifact { maven: FORGE_MAVEN, group_path: "net/minecraftforge", artifact: "forge" },
        // NeoForge's first release (1.20.1) kept Forge's artifact name and versioning.
        Loader::NeoForge if mc == "1.20.1" => Artifact { maven: NEO_MAVEN, group_path: "net/neoforged", artifact: "forge" },
        Loader::NeoForge => Artifact { maven: NEO_MAVEN, group_path: "net/neoforged", artifact: "neoforge" },
        _ => return Err(Error::Other("not a Forge-style loader".into())),
    })
}

/// Full maven version for a loader version the UI shows (Forge and NeoForge 1.20.1 prefix it with the game version).
fn maven_version(loader: Loader, mc: &str, lv: &str) -> String {
    let prefixed = loader == Loader::Forge || (loader == Loader::NeoForge && mc == "1.20.1");
    if prefixed && !lv.starts_with(&format!("{mc}-")) {
        format!("{mc}-{lv}")
    } else {
        lv.to_string()
    }
}

/// NeoForge versions encode the game version: 1.21.1 -> 21.1.x, 1.21 -> 21.0.x, 26.1 -> 26.1.x.
fn neoforge_prefix(mc: &str) -> String {
    let parts: Vec<&str> = mc.split('.').collect();
    match parts.as_slice() {
        ["1", minor] => format!("{minor}.0."),
        ["1", minor, patch, ..] => format!("{minor}.{patch}."),
        _ => format!("{mc}."),
    }
}

/// `<version>` values of a maven-metadata.xml, in file order (oldest first).
fn metadata_versions(xml: &str) -> Vec<String> {
    xml.split("<version>").skip(1).filter_map(|s| s.split("</version>").next()).map(|s| s.trim().to_string()).collect()
}

fn is_prerelease(v: &str) -> bool {
    let l = v.to_lowercase();
    l.contains("beta") || l.contains("alpha") || l.contains("-pre") || l.contains("-rc")
}

/// Versions supporting `mc`, newest first. Forge versions are shown without the `<mc>-` prefix.
pub async fn versions(client: &reqwest::Client, loader: Loader, mc: &str) -> Result<Vec<LoaderVersion>> {
    let a = artifact(loader, mc)?;
    let url = format!("{}/{}/{}/maven-metadata.xml", a.maven, a.group_path, a.artifact);
    let xml = client.get(url).send().await?.error_for_status()?.text().await?;
    let all = metadata_versions(&xml);
    let mut out: Vec<LoaderVersion> = if a.artifact == "forge" {
        let prefix = format!("{mc}-");
        all.iter()
            .filter_map(|v| v.strip_prefix(&prefix))
            .map(|rest| LoaderVersion { version: rest.to_string(), stable: !is_prerelease(rest) })
            .collect()
    } else {
        let prefix = neoforge_prefix(mc);
        all.iter().filter(|v| v.starts_with(&prefix)).map(|v| LoaderVersion { version: v.clone(), stable: !is_prerelease(v) }).collect()
    };
    out.reverse();
    Ok(out)
}

/// The version to use when none is pinned: Forge's promoted recommended/latest build, else the newest stable one.
pub async fn recommended(client: &reqwest::Client, loader: Loader, mc: &str) -> Result<Option<String>> {
    if loader == Loader::Forge {
        if let Ok(r) = client.get(FORGE_PROMOTIONS).send().await {
            if let Ok(v) = r.json::<serde_json::Value>().await {
                for key in [format!("{mc}-recommended"), format!("{mc}-latest")] {
                    if let Some(s) = v["promos"][&key].as_str() {
                        return Ok(Some(s.to_string()));
                    }
                }
            }
        }
    }
    let list = versions(client, loader, mc).await?;
    Ok(list.iter().find(|v| v.stable).or(list.first()).map(|v| v.version.clone()))
}

#[derive(Debug, Deserialize)]
struct InstallProfile {
    #[serde(default)]
    spec: u32,
    #[serde(default)]
    json: Option<String>,
    #[serde(default)]
    data: HashMap<String, SidedData>,
    #[serde(default)]
    processors: Vec<Processor>,
    #[serde(default)]
    libraries: Vec<Library>,
    // spec 0
    #[serde(default)]
    install: Option<LegacyInstall>,
    #[serde(rename = "versionInfo", default)]
    version_info: Option<serde_json::Value>,
}

#[derive(Debug, Deserialize)]
struct SidedData {
    #[serde(default)]
    client: Option<String>,
}

#[derive(Debug, Deserialize)]
struct Processor {
    #[serde(default)]
    sides: Option<Vec<String>>,
    jar: String,
    #[serde(default)]
    classpath: Vec<String>,
    #[serde(default)]
    args: Vec<String>,
    #[serde(default)]
    outputs: HashMap<String, String>,
}

#[derive(Debug, Deserialize)]
struct LegacyInstall {
    path: String,
    #[serde(rename = "filePath")]
    file_path: String,
}

fn read_entry(zip: &mut zip::ZipArchive<std::fs::File>, name: &str) -> Result<Option<Vec<u8>>> {
    let name = name.trim_start_matches('/');
    let mut f = match zip.by_name(name) {
        Ok(f) => f,
        Err(zip::result::ZipError::FileNotFound) => return Ok(None),
        Err(e) => return Err(e.into()),
    };
    let mut buf = Vec::new();
    f.read_to_end(&mut buf)?;
    Ok(Some(buf))
}

fn write_file(path: &Path, bytes: &[u8]) -> Result<()> {
    if let Some(p) = path.parent() {
        std::fs::create_dir_all(p)?;
    }
    std::fs::write(path, bytes)?;
    Ok(())
}

fn lib_path(layout: &Layout, coords: &str) -> Result<PathBuf> {
    maven_path(coords).map(|p| layout.libraries().join(p)).ok_or_else(|| Error::Other(format!("bad library name {coords}")))
}

/// Install (or reuse) `loader` `lv` for the vanilla `version` and return its launcher profile, libraries marked local.
pub async fn install(
    client: &reqwest::Client,
    layout: &Layout,
    java: &Path,
    vanilla: &VersionJson,
    loader: Loader,
    lv: &str,
    mut progress: impl FnMut(usize, usize),
) -> Result<LoaderProfile> {
    let mc = vanilla.id.as_str();
    let a = artifact(loader, mc)?;
    let full = maven_version(loader, mc, lv);
    let dir = layout.root.join("loaders").join(format!("{}-{full}", a.artifact));
    let installer = dir.join("installer.jar");
    let url = format!("{}/{}/{}/{full}/{}-{full}-installer.jar", a.maven, a.group_path, a.artifact, a.artifact);
    if !installer.exists() {
        // The maven publishes a .sha1 next to each file; use it when it's there.
        let sha = match client.get(format!("{url}.sha1")).send().await {
            Ok(r) if r.status().is_success() => r.text().await.ok().map(|t| t.trim().chars().take(40).collect::<String>()).filter(|s| s.len() == 40),
            _ => None,
        };
        if let Err(e) = fetch_maybe_verified(client, &url, &installer, sha.as_deref()).await {
            if matches!(&e, Error::Http(h) if h.status() == Some(reqwest::StatusCode::NOT_FOUND)) {
                return Err(Error::Other(format!("{loader:?} {lv} doesn't exist for Minecraft {mc}. Pick another loader version.")));
            }
            return Err(e);
        }
    }
    let mut zip = zip::ZipArchive::new(std::fs::File::open(&installer)?)?;
    let profile: InstallProfile = serde_json::from_slice(
        &read_entry(&mut zip, "install_profile.json")?.ok_or_else(|| Error::Other("installer has no install_profile.json".into()))?,
    )?;

    if let (Some(inst), Some(info)) = (&profile.install, &profile.version_info) {
        return legacy(layout, &mut zip, inst, info.clone());
    }
    if profile.spec > 1 {
        // Spec 1 is current; newer specs are additive so far, but say so if one breaks.
        eprintln!("forge installer spec {} (newer than this launcher knows)", profile.spec);
    }
    let json_name = profile.json.clone().unwrap_or_else(|| "/version.json".into());
    let version: LoaderProfile =
        serde_json::from_slice(&read_entry(&mut zip, &json_name)?.ok_or_else(|| Error::Other("installer has no version.json".into()))?)?;

    // Libraries: download, or take from the installer's bundled maven/, or (client jar) produced by a processor.
    let processors: Vec<&Processor> =
        profile.processors.iter().filter(|p| p.sides.as_ref().is_none_or(|s| s.iter().any(|x| x == "client"))).collect();
    let all_libs: Vec<&Library> = profile.libraries.iter().chain(version.libraries.iter()).collect();
    let total = all_libs.len() + processors.len() + 1;
    let mut done = 0;
    let mut generated = Vec::new();
    for lib in &all_libs {
        let Some(d) = lib.main_download() else { continue };
        let dest = layout.libraries().join(&d.path);
        let have = match &d.sha1 {
            Some(s) => dest.exists() && std::fs::read(&dest).map(|b| sha1_hex(&b).eq_ignore_ascii_case(s)).unwrap_or(false),
            None => dest.exists(),
        };
        if !have {
            if !d.url.is_empty() {
                fetch_maybe_verified(client, &d.url, &dest, d.sha1.as_deref()).await?;
            } else if let Some(bytes) = read_entry(&mut zip, &format!("maven/{}", d.path))? {
                write_file(&dest, &bytes)?;
            } else {
                generated.push(dest);
            }
        }
        done += 1;
        progress(done, total);
    }

    let marker = dir.join("installed.json");
    let cached = marker.exists() && generated.iter().all(|p| p.exists());
    if !cached {
        let client_jar = layout.client_jar(mc);
        fetch_verified(client, &vanilla.downloads.client.url, &client_jar, &vanilla.downloads.client.sha1).await?;
        let mut data: HashMap<String, String> = HashMap::new();
        data.insert("SIDE".into(), "client".into());
        data.insert("MINECRAFT_JAR".into(), client_jar.display().to_string());
        data.insert("MINECRAFT_VERSION".into(), mc.to_string());
        data.insert("ROOT".into(), layout.root.display().to_string());
        data.insert("INSTALLER".into(), installer.display().to_string());
        data.insert("LIBRARY_DIR".into(), layout.libraries().display().to_string());
        let data_dir = dir.join("data");
        for (k, v) in &profile.data {
            let Some(raw) = &v.client else { continue };
            let value = if let Some(coords) = raw.strip_prefix('[').and_then(|r| r.strip_suffix(']')) {
                lib_path(layout, coords)?.display().to_string()
            } else if let Some(lit) = raw.strip_prefix('\'').and_then(|r| r.strip_suffix('\'')) {
                lit.to_string()
            } else if raw.starts_with('/') {
                let rel = raw.trim_start_matches('/');
                if rel.split('/').any(|c| c == "..") {
                    return Err(Error::Other(format!("unsafe installer path {raw}")));
                }
                let out = data_dir.join(rel);
                let bytes = read_entry(&mut zip, raw)?.ok_or_else(|| Error::Other(format!("installer is missing {raw}")))?;
                write_file(&out, &bytes)?;
                out.display().to_string()
            } else {
                raw.clone()
            };
            data.insert(k.clone(), value);
        }

        for p in processors {
            run_processor(layout, java, p, &data, &dir).await?;
            done += 1;
            progress(done, total);
        }
        if let Some(missing) = generated.iter().find(|p| !p.exists()) {
            return Err(Error::Other(format!("the {loader:?} installer didn't produce {}", missing.display())));
        }
        std::fs::write(&marker, serde_json::json!({ "version": full }).to_string())?;
        let _ = std::fs::remove_dir_all(&data_dir);
    }
    progress(total, total);

    let mut version = version;
    for l in &mut version.libraries {
        l.local = true;
    }
    Ok(version)
}

/// Resolve a processor argument: `{KEY}` data values (also inside longer strings) and `[coords]` library paths.
fn resolve_arg(arg: &str, data: &HashMap<String, String>, layout: &Layout) -> Result<String> {
    if let Some(coords) = arg.strip_prefix('[').and_then(|r| r.strip_suffix(']')) {
        return Ok(lib_path(layout, coords)?.display().to_string());
    }
    if let Some(lit) = arg.strip_prefix('\'').and_then(|r| r.strip_suffix('\'')) {
        return Ok(lit.to_string());
    }
    let mut out = String::new();
    let mut rest = arg;
    while let Some(start) = rest.find('{') {
        let Some(len) = rest[start..].find('}') else { break };
        let key = &rest[start + 1..start + len];
        out.push_str(&rest[..start]);
        match data.get(key) {
            Some(v) => out.push_str(v),
            None => return Err(Error::Other(format!("installer argument uses unknown value {{{key}}}"))),
        }
        rest = &rest[start + len + 1..];
    }
    out.push_str(rest);
    Ok(out)
}

fn main_class(jar: &Path) -> Result<String> {
    let mut zip = zip::ZipArchive::new(std::fs::File::open(jar)?)?;
    let manifest = read_entry(&mut zip, "META-INF/MANIFEST.MF")?.ok_or_else(|| Error::Other(format!("{} has no manifest", jar.display())))?;
    String::from_utf8_lossy(&manifest)
        .lines()
        .find_map(|l| l.strip_prefix("Main-Class:").map(|m| m.trim().to_string()))
        .ok_or_else(|| Error::Other(format!("{} has no Main-Class", jar.display())))
}

async fn run_processor(layout: &Layout, java: &Path, p: &Processor, data: &HashMap<String, String>, cwd: &Path) -> Result<()> {
    // Outputs already present with the right checksums: nothing to do.
    let outputs: Vec<(PathBuf, String)> = p
        .outputs
        .iter()
        .map(|(k, v)| Ok((PathBuf::from(resolve_arg(k, data, layout)?), resolve_arg(v, data, layout)?)))
        .collect::<Result<_>>()?;
    let up_to_date = |outs: &[(PathBuf, String)]| {
        outs.iter().all(|(path, sha)| std::fs::read(path).map(|b| sha1_hex(&b).eq_ignore_ascii_case(sha)).unwrap_or(false))
    };
    if !outputs.is_empty() && up_to_date(&outputs) {
        return Ok(());
    }

    let jar = lib_path(layout, &p.jar)?;
    let main = main_class(&jar)?;
    let sep = if cfg!(windows) { ";" } else { ":" };
    let mut cp = vec![jar.display().to_string()];
    for c in &p.classpath {
        cp.push(lib_path(layout, c)?.display().to_string());
    }
    let args: Vec<String> = p.args.iter().map(|a| resolve_arg(a, data, layout)).collect::<Result<_>>()?;
    let mut cmd = tokio::process::Command::new(java);
    cmd.arg("-cp").arg(cp.join(sep)).arg(&main).args(&args).current_dir(cwd).stdin(std::process::Stdio::null());
    #[cfg(windows)]
    {
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        cmd.creation_flags(CREATE_NO_WINDOW);
    }
    let out = cmd.output().await.map_err(|e| Error::Other(format!("couldn't run the loader installer with {}: {e}", java.display())))?;
    if !out.status.success() {
        let text = format!("{}{}", String::from_utf8_lossy(&out.stdout), String::from_utf8_lossy(&out.stderr));
        let tail: Vec<&str> = text.lines().rev().take(12).collect::<Vec<_>>().into_iter().rev().collect();
        return Err(Error::Other(format!("loader installer step {main} failed:\n{}", tail.join("\n"))));
    }
    if !outputs.is_empty() && !up_to_date(&outputs) {
        return Err(Error::Other(format!("loader installer step {main} produced a file with the wrong checksum")));
    }
    Ok(())
}

/// Spec 0 (Forge 1.12.2 and older): extract the universal jar; libraries come from Forge's or Mojang's maven.
fn legacy(layout: &Layout, zip: &mut zip::ZipArchive<std::fs::File>, inst: &LegacyInstall, info: serde_json::Value) -> Result<LoaderProfile> {
    let universal = lib_path(layout, &inst.path)?;
    if !universal.exists() {
        let bytes = read_entry(zip, &inst.file_path)?.ok_or_else(|| Error::Other(format!("installer is missing {}", inst.file_path)))?;
        write_file(&universal, &bytes)?;
    }
    let mut profile: LoaderProfile = serde_json::from_value(info)?;
    profile.libraries.retain(|l| l.clientreq != Some(false));
    for l in &mut profile.libraries {
        if l.name == inst.path {
            l.local = true;
            continue;
        }
        let base = match l.url.as_deref() {
            Some(u) if u.contains("files.minecraftforge.net") => format!("{FORGE_MAVEN}/"),
            Some(u) => u.replacen("http://", "https://", 1),
            None => "https://libraries.minecraft.net/".into(),
        };
        if l.downloads.is_none() {
            l.url = Some(base);
        }
    }
    Ok(profile)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn version_mapping() {
        assert_eq!(neoforge_prefix("1.21.1"), "21.1.");
        assert_eq!(neoforge_prefix("1.21"), "21.0.");
        assert_eq!(neoforge_prefix("26.1"), "26.1.");
        assert_eq!(maven_version(Loader::Forge, "1.20.1", "47.3.0"), "1.20.1-47.3.0");
        assert_eq!(maven_version(Loader::Forge, "1.20.1", "1.20.1-47.3.0"), "1.20.1-47.3.0");
        assert_eq!(maven_version(Loader::NeoForge, "1.21.1", "21.1.172"), "21.1.172");
        assert_eq!(maven_version(Loader::NeoForge, "1.20.1", "47.1.106"), "1.20.1-47.1.106");
    }

    #[test]
    fn parses_metadata() {
        let xml = "<metadata><versioning><versions><version>1.20.1-47.2.0</version>\n<version>1.20.1-47.3.0</version><version>1.21-51.0.0</version></versions></versioning></metadata>";
        assert_eq!(metadata_versions(xml), ["1.20.1-47.2.0", "1.20.1-47.3.0", "1.21-51.0.0"]);
        assert!(is_prerelease("21.1.0-beta"));
        assert!(!is_prerelease("21.1.172"));
    }

    #[test]
    fn processor_arguments() {
        let layout = Layout::new("/r");
        let mut data = HashMap::new();
        data.insert("SIDE".to_string(), "client".to_string());
        data.insert("MAPPINGS".to_string(), "/m.txt".to_string());
        assert_eq!(resolve_arg("{SIDE}", &data, &layout).unwrap(), "client");
        assert_eq!(resolve_arg("--x={MAPPINGS}", &data, &layout).unwrap(), "--x=/m.txt");
        assert_eq!(resolve_arg("'lit'", &data, &layout).unwrap(), "lit");
        assert!(resolve_arg("[a.b:c:1]", &data, &layout).unwrap().replace('\\', "/").ends_with("libraries/a/b/c/1/c-1.jar"));
        assert!(resolve_arg("{NOPE}", &data, &layout).is_err());
        assert_eq!(resolve_arg("--plain", &data, &layout).unwrap(), "--plain");
    }

    #[test]
    fn legacy_profile_libraries() {
        let dir = std::env::temp_dir().join(format!("aether-forge-legacy-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let jar = dir.join("i.jar");
        {
            use std::io::Write;
            let mut w = zip::ZipWriter::new(std::fs::File::create(&jar).unwrap());
            w.start_file("forge-universal.jar", zip::write::SimpleFileOptions::default()).unwrap();
            w.write_all(b"u").unwrap();
            w.finish().unwrap();
        }
        let layout = Layout::new(&dir);
        let mut zip = zip::ZipArchive::new(std::fs::File::open(&jar).unwrap()).unwrap();
        let inst = LegacyInstall { path: "net.minecraftforge:forge:1.12.2-14.23.5.2860".into(), file_path: "forge-universal.jar".into() };
        let info = serde_json::json!({
            "mainClass": "net.minecraft.launchwrapper.Launch",
            "minecraftArguments": "--tweakClass x",
            "libraries": [
                {"name": "net.minecraftforge:forge:1.12.2-14.23.5.2860"},
                {"name": "net.minecraft:launchwrapper:1.12"},
                {"name": "org.ow2.asm:asm-all:5.2", "url": "http://files.minecraftforge.net/maven/"},
                {"name": "jline:jline:2.13", "url": "http://files.minecraftforge.net/maven/", "clientreq": false}
            ]
        });
        let p = legacy(&layout, &mut zip, &inst, info).unwrap();
        assert_eq!(p.libraries.len(), 3);
        assert!(p.libraries[0].local);
        assert_eq!(p.libraries[1].url.as_deref(), Some("https://libraries.minecraft.net/"));
        assert!(p.libraries[2].main_download().unwrap().url.starts_with("https://maven.minecraftforge.net/org/ow2/asm"));
        assert!(lib_path(&layout, &inst.path).unwrap().exists());
        let _ = std::fs::remove_dir_all(&dir);
    }
}
