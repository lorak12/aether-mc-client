//! Minecraft's own settings (`options.txt`: video, controls and keybinds, sound, chat, accessibility) shared by
//! every profile. One copy lives in `shared/` in the data root. Before a launch its values go into the profile's
//! `options.txt`; after the game exits the profile's values go back, so a change made in any profile shows up in
//! all of them.
//!
//! Values are merged key by key, never replaced wholesale: a key only one game version (or one modpack's mod)
//! knows about is kept for the profiles that use it. Resource pack lists stay per profile because each profile
//! has its own packs. Versions before 1.13 store keybinds as LWJGL 2 key codes instead of names, so they share a
//! separate copy.
use crate::Result;
use std::path::{Path, PathBuf};

/// Keys that belong to one profile.
const PER_PROFILE: &[&str] = &["resourcePacks", "incompatibleResourcePacks"];

/// `options.txt` lines in file order.
pub type Options = Vec<(String, String)>;

pub fn parse(text: &str) -> Options {
    text.lines()
        .filter_map(|l| {
            let l = l.trim_end_matches('\r');
            let (k, v) = l.split_once(':')?;
            (!k.is_empty()).then(|| (k.to_string(), v.to_string()))
        })
        .collect()
}

pub fn serialize(o: &Options) -> String {
    o.iter().map(|(k, v)| format!("{k}:{v}\n")).collect()
}

/// `base` with every shareable value from `top` set on it (replacing in place, new keys appended).
pub fn merge(base: &Options, top: &Options) -> Options {
    let mut out = base.clone();
    for (k, v) in top {
        if PER_PROFILE.contains(&k.as_str()) {
            continue;
        }
        match out.iter_mut().find(|(ok, _)| ok == k) {
            Some(slot) => slot.1 = v.clone(),
            None => out.push((k.clone(), v.clone())),
        }
    }
    out
}

/// Versions whose keybinds are LWJGL 2 key codes (alpha, beta and release 1.0 to 1.12.2).
pub fn is_legacy(mc_version: &str) -> bool {
    let v = mc_version.trim();
    if v.starts_with(['a', 'b', 'c']) || v.starts_with("rd-") || v.starts_with("inf-") {
        return true;
    }
    match v.strip_prefix("1.") {
        Some(rest) => rest.split(|c: char| !c.is_ascii_digit()).next().and_then(|m| m.parse::<u32>().ok()).is_some_and(|m| m < 13),
        None => false,
    }
}

/// The shared copy for profiles of this version.
pub fn shared_path(data_root: &Path, mc_version: &str) -> PathBuf {
    data_root.join("shared").join(if is_legacy(mc_version) { "options-legacy.txt" } else { "options.txt" })
}

fn read(path: &Path) -> Option<Options> {
    std::fs::read_to_string(path).ok().map(|t| parse(&t))
}

fn write(path: &Path, o: &Options) -> Result<()> {
    if let Some(dir) = path.parent() {
        std::fs::create_dir_all(dir)?;
    }
    let tmp = path.with_extension("txt.tmp");
    std::fs::write(&tmp, serialize(o))?;
    std::fs::rename(&tmp, path)?;
    Ok(())
}

/// Before a launch: put the shared settings into `game_dir/options.txt`. The first time, the shared copy starts
/// from the most recently changed `options.txt` among `seeds` (other profiles of the same kind), or from this
/// profile's own file. Returns whether anything was written.
pub fn push(data_root: &Path, game_dir: &Path, mc_version: &str, seeds: &[PathBuf]) -> Result<bool> {
    let shared_file = shared_path(data_root, mc_version);
    let own_file = game_dir.join("options.txt");
    let shared = match read(&shared_file) {
        Some(s) => s,
        None => {
            let newest = seeds
                .iter()
                .chain(std::iter::once(&own_file))
                .filter_map(|p| Some((std::fs::metadata(p).ok()?.modified().ok()?, p)))
                .max_by_key(|(t, _)| *t)
                .and_then(|(_, p)| read(p));
            let Some(seed) = newest else { return Ok(false) };
            let seed = merge(&Vec::new(), &seed);
            write(&shared_file, &seed)?;
            seed
        }
    };
    let own = read(&own_file).unwrap_or_default();
    let merged = merge(&own, &shared);
    if merged == own {
        return Ok(false);
    }
    write(&own_file, &merged)?;
    Ok(true)
}

/// After the game exits: take the profile's settings back into the shared copy.
pub fn pull(data_root: &Path, game_dir: &Path, mc_version: &str) -> Result<()> {
    let Some(own) = read(&game_dir.join("options.txt")) else { return Ok(()) };
    let shared_file = shared_path(data_root, mc_version);
    let shared = read(&shared_file).unwrap_or_default();
    let merged = merge(&shared, &own);
    if merged != shared {
        write(&shared_file, &merged)?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tmp(name: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("aether-options-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        std::fs::create_dir_all(&d).unwrap();
        d
    }

    #[test]
    fn parses_and_round_trips() {
        let text = "version:3955\nfov:0.25\nresourcePacks:[\"vanilla\",\"file/x.zip\"]\nkey_key.attack:key.mouse.left\n";
        let o = parse(text);
        assert_eq!(o.len(), 4);
        assert_eq!(o[2], ("resourcePacks".into(), "[\"vanilla\",\"file/x.zip\"]".into()));
        assert_eq!(serialize(&o), text);
        assert_eq!(parse("a:b:c\r\n\nnocolon\n"), vec![("a".into(), "b:c".into())]);
    }

    #[test]
    fn merge_keeps_order_unknown_keys_and_resource_packs() {
        let base = parse("fov:0\nresourcePacks:[\"a\"]\nkey_key.modpack:key.keyboard.z\n");
        let top = parse("resourcePacks:[\"b\"]\nfov:1\nguiScale:3\n");
        assert_eq!(serialize(&merge(&base, &top)), "fov:1\nresourcePacks:[\"a\"]\nkey_key.modpack:key.keyboard.z\nguiScale:3\n");
    }

    #[test]
    fn legacy_versions() {
        for v in ["1.12.2", "1.8.9", "1.7.10", "b1.7.3", "a1.2.6", "rd-132211"] {
            assert!(is_legacy(v), "{v}");
        }
        for v in ["1.13", "1.21.11", "26.3", "24w14a", "1.20.1-rc1"] {
            assert!(!is_legacy(v), "{v}");
        }
    }

    #[test]
    fn settings_follow_the_player_between_profiles() {
        let root = tmp("flow");
        let (a, b, old) = (root.join("a"), root.join("b"), root.join("old"));
        for d in [&a, &b, &old] {
            std::fs::create_dir_all(d).unwrap();
        }
        std::fs::write(a.join("options.txt"), "fov:0.5\nresourcePacks:[\"a\"]\n").unwrap();
        std::fs::write(old.join("options.txt"), "fov:0.9\n").unwrap();

        // First launch of b: seeded from a (the newest), b gets a's fov but no resource packs.
        assert!(push(&root, &b, "1.21.11", &[a.join("options.txt")]).unwrap());
        assert_eq!(std::fs::read_to_string(b.join("options.txt")).unwrap(), "fov:0.5\n");

        // The player changes settings in b; they reach a on its next launch, a's packs stay.
        std::fs::write(b.join("options.txt"), "fov:0.7\nkey_key.jump:key.keyboard.x\nresourcePacks:[\"b\"]\n").unwrap();
        pull(&root, &b, "1.21.11").unwrap();
        push(&root, &a, "1.21.11", &[]).unwrap();
        assert_eq!(std::fs::read_to_string(a.join("options.txt")).unwrap(), "fov:0.7\nresourcePacks:[\"a\"]\nkey_key.jump:key.keyboard.x\n");

        // 1.12.2 has its own copy.
        assert!(!push(&root, &old, "1.12.2", &[]).unwrap());
        assert_eq!(std::fs::read_to_string(old.join("options.txt")).unwrap(), "fov:0.9\n");
        assert!(shared_path(&root, "1.12.2").exists());
        let _ = std::fs::remove_dir_all(&root);
    }
}
