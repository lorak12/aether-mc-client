//! Game settings: the global defaults (`settings.json` in the data root) and per-profile overrides, resolved into what
//! a launch uses. Field names are camelCase on the wire because the UI edits these objects directly.
use crate::Result;
use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
pub enum Gc {
    #[default]
    G1GC,
    ZGC,
    Shenandoah,
    Parallel,
}

impl Gc {
    pub fn flag(self) -> &'static str {
        match self {
            Gc::G1GC => "-XX:+UseG1GC",
            Gc::ZGC => "-XX:+UseZGC",
            Gc::Shenandoah => "-XX:+UseShenandoahGC",
            Gc::Parallel => "-XX:+UseParallelGC",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct GameSettings {
    pub memory_mb: u32,
    pub width: u32,
    pub height: u32,
    pub fullscreen: bool,
    /// Extra JVM arguments, space separated. GC flags here are ignored in favour of `gc`.
    pub jvm_args: String,
    pub gc: Gc,
    /// Shell command run before the game starts; a non-zero exit cancels the launch.
    pub pre_launch: String,
    /// Command the java invocation is wrapped in (e.g. `prime-run`, `gamemoderun`).
    pub wrapper: String,
    /// Shell command run after the game exits.
    pub post_exit: String,
    pub env: Vec<(String, String)>,
    /// Java executable to use instead of Mojang's runtime. Empty = automatic.
    pub java_path: String,
    /// Use the shared `options.txt` (video settings, keybinds, sound...) instead of the profile's own. See `options`.
    pub share_options: bool,
}

impl Default for GameSettings {
    fn default() -> Self {
        Self {
            memory_mb: 4096,
            width: 1280,
            height: 720,
            fullscreen: false,
            jvm_args: String::new(),
            gc: Gc::G1GC,
            pre_launch: String::new(),
            wrapper: String::new(),
            post_exit: String::new(),
            env: Vec::new(),
            java_path: String::new(),
            share_options: true,
        }
    }
}

impl GameSettings {
    /// Clamp values a hand-edited file or a bad request could make unusable.
    pub fn sanitized(mut self) -> Self {
        self.memory_mb = self.memory_mb.clamp(512, 65536);
        self.width = self.width.clamp(320, 15360);
        self.height = self.height.clamp(240, 8640);
        self.env.retain(|(k, _)| !k.trim().is_empty() && !k.contains('='));
        self
    }

    /// JVM arguments for this configuration: heap, the user's extra flags (minus GC choices), the GC flag.
    pub fn jvm_args(&self) -> Vec<String> {
        let mut out = vec![format!("-Xmx{}M", self.memory_mb), format!("-Xms{}M", self.memory_mb.min(512))];
        out.extend(split_args(&self.jvm_args).into_iter().filter(|a| !is_gc_flag(a) && !a.starts_with("-Xmx") && !a.starts_with("-Xms")));
        out.push(self.gc.flag().into());
        out
    }
}

fn is_gc_flag(a: &str) -> bool {
    a.starts_with("-XX:+Use") && a.ends_with("GC") || a.starts_with("-XX:-Use") && a.ends_with("GC")
}

/// Split a command line on whitespace, keeping "double quoted" parts together.
pub fn split_args(s: &str) -> Vec<String> {
    let mut out = Vec::new();
    let mut cur = String::new();
    let mut quoted = false;
    let mut has = false;
    for c in s.chars() {
        match c {
            '"' => {
                quoted = !quoted;
                has = true;
            }
            c if c.is_whitespace() && !quoted => {
                if has {
                    out.push(std::mem::take(&mut cur));
                    has = false;
                }
            }
            c => {
                cur.push(c);
                has = true;
            }
        }
    }
    if has {
        out.push(cur);
    }
    out
}

/// One overridable value. `value` is kept while `on` is false so toggling back restores it.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Ov<T> {
    pub on: bool,
    pub value: T,
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct Overrides {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub memory_mb: Option<Ov<u32>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub width: Option<Ov<u32>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub height: Option<Ov<u32>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub fullscreen: Option<Ov<bool>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub jvm_args: Option<Ov<String>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub gc: Option<Ov<Gc>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub pre_launch: Option<Ov<String>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub wrapper: Option<Ov<String>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub post_exit: Option<Ov<String>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub env: Option<Ov<Vec<(String, String)>>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub java_path: Option<Ov<String>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub share_options: Option<Ov<bool>>,
}

fn pick<T: Clone>(global: &T, ov: &Option<Ov<T>>) -> T {
    match ov {
        Some(o) if o.on => o.value.clone(),
        _ => global.clone(),
    }
}

/// What a profile launches with: its own value where it overrides, the global one otherwise.
pub fn effective(g: &GameSettings, o: &Overrides) -> GameSettings {
    GameSettings {
        memory_mb: pick(&g.memory_mb, &o.memory_mb),
        width: pick(&g.width, &o.width),
        height: pick(&g.height, &o.height),
        fullscreen: pick(&g.fullscreen, &o.fullscreen),
        jvm_args: pick(&g.jvm_args, &o.jvm_args),
        gc: pick(&g.gc, &o.gc),
        pre_launch: pick(&g.pre_launch, &o.pre_launch),
        wrapper: pick(&g.wrapper, &o.wrapper),
        post_exit: pick(&g.post_exit, &o.post_exit),
        env: pick(&g.env, &o.env),
        java_path: pick(&g.java_path, &o.java_path),
        share_options: pick(&g.share_options, &o.share_options),
    }
    .sanitized()
}

/// Overrides equivalent to a format-1 profile's memory and JVM arguments (those were already resolved values).
pub fn overrides_from_legacy(memory_mb: Option<u32>, jvm_args: &[String]) -> Overrides {
    let defaults = GameSettings::default();
    let mut o = Overrides::default();
    if let Some(m) = memory_mb.filter(|m| *m != defaults.memory_mb) {
        o.memory_mb = Some(Ov { on: true, value: m });
    }
    let gc = jvm_args.iter().find_map(|a| match a.as_str() {
        "-XX:+UseZGC" => Some(Gc::ZGC),
        "-XX:+UseShenandoahGC" => Some(Gc::Shenandoah),
        "-XX:+UseParallelGC" => Some(Gc::Parallel),
        "-XX:+UseG1GC" => Some(Gc::G1GC),
        _ => None,
    });
    if let Some(gc) = gc.filter(|g| *g != defaults.gc) {
        o.gc = Some(Ov { on: true, value: gc });
    }
    let user: Vec<&str> = jvm_args.iter().map(String::as_str).filter(|a| !is_gc_flag(a)).collect();
    if !user.is_empty() {
        o.jvm_args = Some(Ov { on: true, value: user.join(" ") });
    }
    o
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
struct SettingsFile {
    #[serde(default)]
    game: GameSettings,
}

/// `settings.json` in the data root.
pub struct SettingsStore {
    path: PathBuf,
}

impl SettingsStore {
    pub fn new(data_root: &Path) -> Self {
        Self { path: data_root.join("settings.json") }
    }

    pub fn exists(&self) -> bool {
        self.path.exists()
    }

    pub fn load(&self) -> GameSettings {
        std::fs::read(&self.path)
            .ok()
            .and_then(|b| serde_json::from_slice::<SettingsFile>(&b).ok())
            .map(|f| f.game.sanitized())
            .unwrap_or_default()
    }

    pub fn save(&self, game: &GameSettings) -> Result<()> {
        if let Some(p) = self.path.parent() {
            std::fs::create_dir_all(p)?;
        }
        let tmp = self.path.with_extension("json.tmp");
        std::fs::write(&tmp, serde_json::to_vec_pretty(&SettingsFile { game: game.clone().sanitized() })?)?;
        std::fs::rename(tmp, &self.path)?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn overrides_win_only_when_on() {
        let g = GameSettings::default();
        let o = Overrides {
            memory_mb: Some(Ov { on: true, value: 8192 }),
            width: Some(Ov { on: false, value: 1920 }),
            ..Default::default()
        };
        let e = effective(&g, &o);
        assert_eq!(e.memory_mb, 8192);
        assert_eq!(e.width, 1280);
    }

    #[test]
    fn jvm_args_replace_gc_and_heap_flags() {
        let s = GameSettings { jvm_args: "-XX:+UseZGC -Dfoo=1 -Xmx1G \"-Dbar=a b\"".into(), gc: Gc::G1GC, memory_mb: 2048, ..Default::default() };
        assert_eq!(s.jvm_args(), ["-Xmx2048M", "-Xms512M", "-Dfoo=1", "-Dbar=a b", "-XX:+UseG1GC"]);
    }

    #[test]
    fn camel_case_wire_format() {
        let o: Overrides = serde_json::from_str(r#"{"memoryMb":{"on":true,"value":6000},"env":{"on":true,"value":[["A","1"]]}}"#).unwrap();
        assert_eq!(o.memory_mb.unwrap().value, 6000);
        let e = effective(&GameSettings::default(), &Overrides { env: Some(Ov { on: true, value: vec![("".into(), "x".into()), ("K".into(), "v".into())] }), ..Default::default() });
        assert_eq!(e.env, vec![("K".to_string(), "v".to_string())]);
        let g: GameSettings = serde_json::from_str(r#"{"memoryMb":3000,"gc":"ZGC"}"#).unwrap();
        assert_eq!((g.memory_mb, g.gc, g.width), (3000, Gc::ZGC, 1280));
    }

    #[test]
    fn legacy_profile_values_become_overrides() {
        let o = overrides_from_legacy(Some(6144), &["-XX:+UseZGC".into(), "-Dx=1".into()]);
        assert_eq!(o.memory_mb.unwrap().value, 6144);
        assert_eq!(o.gc.unwrap().value, Gc::ZGC);
        assert_eq!(o.jvm_args.unwrap().value, "-Dx=1");
        assert_eq!(overrides_from_legacy(Some(4096), &["-XX:+UseG1GC".into()]), Overrides::default());
    }

    #[test]
    fn store_round_trip() {
        let dir = std::env::temp_dir().join(format!("aether-settings-{}", std::process::id()));
        let s = SettingsStore::new(&dir);
        assert_eq!(s.load(), GameSettings::default());
        s.save(&GameSettings { memory_mb: 100, fullscreen: true, ..Default::default() }).unwrap();
        let l = s.load();
        assert_eq!((l.memory_mb, l.fullscreen), (512, true));
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn split_args_quotes() {
        assert_eq!(split_args("  a \"b c\" d"), ["a", "b c", "d"]);
        assert_eq!(split_args(""), Vec::<String>::new());
        assert_eq!(split_args("\"\""), [""]);
    }
}
