//! What the game keeps in a profile folder: worlds (`saves/`), their backups and screenshots.
use crate::{nbt, Error, Result};
use serde::Serialize;
use std::io::Write;
use std::path::{Path, PathBuf};

#[derive(Debug, Clone, Serialize, PartialEq)]
pub struct World {
    /// Folder name in `saves/`, what commands take.
    pub id: String,
    pub name: String,
    /// survival | creative | adventure | spectator | hardcore
    pub mode: String,
    /// Unix milliseconds.
    pub last_played: Option<i64>,
    pub size_bytes: u64,
    pub version: Option<String>,
    /// `icon.png`, when the game made one.
    pub icon: Option<String>,
}

#[derive(Debug, Clone, Serialize, PartialEq)]
pub struct Screenshot {
    pub name: String,
    pub path: String,
    /// Unix milliseconds.
    pub at: i64,
    pub size_bytes: u64,
}

#[derive(Debug, Clone, Serialize, PartialEq)]
pub struct Backup {
    pub name: String,
    pub path: String,
    pub at: i64,
    pub size_bytes: u64,
}

/// A single folder or file name: no separators, no `.`/`..`.
pub fn safe_name(name: &str) -> Result<&str> {
    if name.is_empty() || name == "." || name == ".." || name.contains(['/', '\\', ':']) || name.contains('\0') {
        return Err(Error::Other(format!("invalid name: {name}")));
    }
    Ok(name)
}

pub fn dir_size(p: &Path) -> u64 {
    let Ok(rd) = std::fs::read_dir(p) else { return 0 };
    rd.flatten()
        .map(|e| match e.file_type() {
            Ok(t) if t.is_dir() => dir_size(&e.path()),
            Ok(t) if t.is_file() => e.metadata().map(|m| m.len()).unwrap_or(0),
            _ => 0,
        })
        .sum()
}

fn mtime_ms(p: &Path) -> i64 {
    std::fs::metadata(p)
        .and_then(|m| m.modified())
        .ok()
        .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

fn mode_name(game_type: i64, hardcore: bool) -> &'static str {
    if hardcore {
        return "hardcore";
    }
    match game_type {
        1 => "creative",
        2 => "adventure",
        3 => "spectator",
        _ => "survival",
    }
}

/// Read one world folder. `None` if it has no readable `level.dat`.
pub fn read_world(dir: &Path) -> Option<World> {
    let id = dir.file_name()?.to_str()?.to_string();
    let level = std::fs::read(dir.join("level.dat")).ok()?;
    let (_, root) = nbt::read_any(&level).ok()?;
    let data = root.get("Data")?;
    let name = data.get("LevelName").and_then(|t| t.str()).filter(|n| !n.trim().is_empty()).unwrap_or(&id).to_string();
    let hardcore = data.get("hardcore").and_then(|t| t.int()).unwrap_or(0) != 0;
    let mode = mode_name(data.get("GameType").and_then(|t| t.int()).unwrap_or(0), hardcore).to_string();
    let last_played = data.get("LastPlayed").and_then(|t| t.int()).or_else(|| Some(mtime_ms(&dir.join("level.dat"))));
    let version = data.get("Version").and_then(|v| v.get("Name")).and_then(|t| t.str()).map(String::from);
    let icon = dir.join("icon.png");
    Some(World {
        id,
        name,
        mode,
        last_played,
        size_bytes: dir_size(dir),
        version,
        icon: icon.exists().then(|| icon.display().to_string()),
    })
}

/// Worlds in `<game_dir>/saves`, most recently played first.
pub fn list_worlds(game_dir: &Path) -> Vec<World> {
    let Ok(rd) = std::fs::read_dir(game_dir.join("saves")) else { return vec![] };
    let mut out: Vec<World> = rd.flatten().filter(|e| e.path().is_dir()).filter_map(|e| read_world(&e.path())).collect();
    out.sort_by(|a, b| b.last_played.cmp(&a.last_played).then(a.name.cmp(&b.name)));
    out
}

pub fn delete_world(game_dir: &Path, id: &str) -> Result<()> {
    let dir = game_dir.join("saves").join(safe_name(id)?);
    if !dir.join("level.dat").exists() {
        return Err(Error::Other(format!("{id} isn't a world")));
    }
    std::fs::remove_dir_all(dir)?;
    Ok(())
}

/// `YYYY-MM-DD_HH-MM-SS` (UTC) for a unix time, without a date crate.
pub fn timestamp(unix: i64) -> String {
    let days = unix.div_euclid(86_400);
    let secs = unix.rem_euclid(86_400);
    // Howard Hinnant's civil-from-days.
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let y = yoe + era * 400 + i64::from(m <= 2);
    format!("{y:04}-{m:02}-{d:02}_{:02}-{:02}-{:02}", secs / 3600, secs % 3600 / 60, secs % 60)
}

fn now_unix() -> i64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs() as i64).unwrap_or(0)
}

pub fn backups_dir(game_dir: &Path) -> PathBuf {
    game_dir.join("backups")
}

/// Zip `saves/<id>` into `backups/<id>_<timestamp>.zip`. `session.lock` is skipped (the game holds it open).
pub fn backup_world(game_dir: &Path, id: &str) -> Result<Backup> {
    let src = game_dir.join("saves").join(safe_name(id)?);
    if !src.is_dir() {
        return Err(Error::Other(format!("{id} isn't a world")));
    }
    let dir = backups_dir(game_dir);
    std::fs::create_dir_all(&dir)?;
    let name = format!("{id}_{}.zip", timestamp(now_unix()));
    let dest = dir.join(&name);
    let part = dir.join(format!("{name}.part"));
    {
        let mut zip = zip::ZipWriter::new(std::fs::File::create(&part)?);
        let opts = zip::write::SimpleFileOptions::default().compression_method(zip::CompressionMethod::Deflated);
        add_dir(&mut zip, &src, id, opts)?;
        zip.finish()?;
    }
    std::fs::rename(&part, &dest)?;
    Ok(Backup { name, path: dest.display().to_string(), at: now_unix() * 1000, size_bytes: std::fs::metadata(&dest)?.len() })
}

fn add_dir(zip: &mut zip::ZipWriter<std::fs::File>, dir: &Path, prefix: &str, opts: zip::write::SimpleFileOptions) -> Result<()> {
    for e in std::fs::read_dir(dir)?.flatten() {
        let name = e.file_name().to_string_lossy().to_string();
        let rel = format!("{prefix}/{name}");
        let ft = e.file_type()?;
        if ft.is_dir() {
            zip.add_directory(rel.clone(), opts)?;
            add_dir(zip, &e.path(), &rel, opts)?;
        } else if ft.is_file() && name != "session.lock" {
            zip.start_file(rel, opts)?;
            let mut f = std::fs::File::open(e.path())?;
            std::io::copy(&mut f, zip)?;
        }
    }
    Ok(())
}

pub fn list_backups(game_dir: &Path) -> Vec<Backup> {
    let Ok(rd) = std::fs::read_dir(backups_dir(game_dir)) else { return vec![] };
    let mut out: Vec<Backup> = rd
        .flatten()
        .filter(|e| e.path().extension().is_some_and(|x| x == "zip"))
        .map(|e| Backup {
            name: e.file_name().to_string_lossy().to_string(),
            path: e.path().display().to_string(),
            at: mtime_ms(&e.path()),
            size_bytes: e.metadata().map(|m| m.len()).unwrap_or(0),
        })
        .collect();
    out.sort_by_key(|a| std::cmp::Reverse(a.at));
    out
}

/// Unzip a backup into `saves/`, under a new folder name if the world exists.
pub fn restore_backup(game_dir: &Path, backup: &str) -> Result<String> {
    let path = backups_dir(game_dir).join(safe_name(backup)?);
    let mut zip = zip::ZipArchive::new(std::fs::File::open(&path)?)?;
    let top = zip
        .file_names()
        .next()
        .and_then(|n| n.split('/').next())
        .map(String::from)
        .ok_or_else(|| Error::Other("empty backup".into()))?;
    safe_name(&top)?;
    let saves = game_dir.join("saves");
    let mut target = top.clone();
    let mut n = 2;
    while saves.join(&target).exists() {
        target = format!("{top} ({n})");
        n += 1;
    }
    for i in 0..zip.len() {
        let mut e = zip.by_index(i)?;
        let Some(rel) = e.enclosed_name() else { continue };
        let Ok(rest) = rel.strip_prefix(&top) else { continue };
        let out = saves.join(&target).join(rest);
        if e.is_dir() {
            std::fs::create_dir_all(&out)?;
            continue;
        }
        if let Some(p) = out.parent() {
            std::fs::create_dir_all(p)?;
        }
        let mut f = std::fs::File::create(&out)?;
        std::io::copy(&mut e, &mut f)?;
        f.flush()?;
    }
    Ok(target)
}

const SHOT_EXT: &[&str] = &["png", "jpg", "jpeg"];

/// Screenshots in `<game_dir>/screenshots`, newest first.
pub fn list_screenshots(game_dir: &Path) -> Vec<Screenshot> {
    let Ok(rd) = std::fs::read_dir(game_dir.join("screenshots")) else { return vec![] };
    let mut out: Vec<Screenshot> = rd
        .flatten()
        .filter(|e| e.path().extension().and_then(|x| x.to_str()).is_some_and(|x| SHOT_EXT.contains(&x.to_lowercase().as_str())))
        .map(|e| Screenshot {
            name: e.file_name().to_string_lossy().to_string(),
            path: e.path().display().to_string(),
            at: mtime_ms(&e.path()),
            size_bytes: e.metadata().map(|m| m.len()).unwrap_or(0),
        })
        .collect();
    out.sort_by_key(|a| std::cmp::Reverse(a.at));
    out
}

pub fn screenshot_path(game_dir: &Path, name: &str) -> Result<PathBuf> {
    let p = game_dir.join("screenshots").join(safe_name(name)?);
    if !p.is_file() {
        return Err(Error::Other(format!("{name} doesn't exist")));
    }
    Ok(p)
}

pub fn delete_screenshot(game_dir: &Path, name: &str) -> Result<()> {
    std::fs::remove_file(screenshot_path(game_dir, name)?)?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::nbt::Tag;

    fn tmp(tag: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("aether-gf-{tag}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        std::fs::create_dir_all(&d).unwrap();
        d
    }

    fn make_world(game: &Path, id: &str, name: &str, mode: i32, last: i64) {
        let dir = game.join("saves").join(id);
        std::fs::create_dir_all(dir.join("region")).unwrap();
        let data = Tag::Compound(vec![(
            "Data".into(),
            Tag::Compound(vec![
                ("LevelName".into(), Tag::String(name.into())),
                ("GameType".into(), Tag::Int(mode)),
                ("LastPlayed".into(), Tag::Long(last)),
                ("Version".into(), Tag::Compound(vec![("Name".into(), Tag::String("1.21.11".into()))])),
            ]),
        )]);
        let mut gz = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::default());
        gz.write_all(&nbt::write("", &data)).unwrap();
        std::fs::write(dir.join("level.dat"), gz.finish().unwrap()).unwrap();
        std::fs::write(dir.join("region").join("r.0.0.mca"), vec![0u8; 100]).unwrap();
        std::fs::write(dir.join("session.lock"), b"x").unwrap();
    }

    #[test]
    fn worlds_backups_restore() {
        let g = tmp("worlds");
        make_world(&g, "old", "Old World", 1, 10);
        make_world(&g, "new", "New World", 0, 20);
        std::fs::create_dir_all(g.join("saves").join("not-a-world")).unwrap();
        let w = list_worlds(&g);
        assert_eq!(w.iter().map(|w| w.id.as_str()).collect::<Vec<_>>(), ["new", "old"]);
        assert_eq!(w[1].mode, "creative");
        assert_eq!(w[0].version.as_deref(), Some("1.21.11"));
        assert!(w[0].size_bytes >= 100);

        let b = backup_world(&g, "new").unwrap();
        assert!(b.name.starts_with("new_") && b.name.ends_with(".zip"));
        assert_eq!(list_backups(&g).len(), 1);
        let restored = restore_backup(&g, &b.name).unwrap();
        assert_eq!(restored, "new (2)");
        assert!(g.join("saves/new (2)/region/r.0.0.mca").exists());
        assert!(!g.join("saves/new (2)/session.lock").exists());

        delete_world(&g, "old").unwrap();
        assert!(!g.join("saves/old").exists());
        assert!(delete_world(&g, "..").is_err());
        assert!(delete_world(&g, "not-a-world").is_err());
        let _ = std::fs::remove_dir_all(&g);
    }

    #[test]
    fn screenshots() {
        let g = tmp("shots");
        std::fs::create_dir_all(g.join("screenshots")).unwrap();
        std::fs::write(g.join("screenshots/a.png"), b"x").unwrap();
        std::fs::write(g.join("screenshots/notes.txt"), b"x").unwrap();
        assert_eq!(list_screenshots(&g).len(), 1);
        assert!(delete_screenshot(&g, "../a.png").is_err());
        delete_screenshot(&g, "a.png").unwrap();
        assert!(list_screenshots(&g).is_empty());
        let _ = std::fs::remove_dir_all(&g);
    }

    #[test]
    fn timestamps() {
        assert_eq!(timestamp(0), "1970-01-01_00-00-00");
        assert_eq!(timestamp(1_791_504_000), "2026-10-09_00-00-00");
        assert_eq!(timestamp(951_782_400 + 3661), "2000-02-29_01-01-01");
    }
}
