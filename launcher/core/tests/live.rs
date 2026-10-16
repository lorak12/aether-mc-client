//! Network tests against Mojang. Run with: cargo test -p kite-core --test live -- --ignored --nocapture
use kite_core::version::{Manifest, VersionJson};

const TARGETS: &[&str] = &["1.7.10", "1.8.9", "1.12.2", "1.21.11", "26.1", "26.2", "26.2.1", "26.3"];

#[tokio::test]
#[ignore]
async fn target_versions_parse() {
    let client = reqwest::Client::new();
    let manifest = Manifest::fetch(&client).await.unwrap();
    println!("latest release: {}", manifest.latest.release);
    for id in TARGETS {
        let Some(entry) = manifest.find(id) else {
            println!("{id}: NOT IN MANIFEST");
            continue;
        };
        let v = VersionJson::fetch(&client, entry).await.unwrap();
        println!("{id}: java {} libs {} main {}", v.java_major(), v.libraries.len(), v.main_class);
    }
}

/// Full install + start of a real game with a placeholder session (no multiplayer), killed after 25s.
/// Set KITE_TEST_ROOT to reuse a cache (e.g. the launcher's app data folder), KITE_TEST_PROFILES=1.21.11:fabric,1.8.9:vanilla,
/// KITE_TEST_EXTRA_MODS to add a folder of mod jars.
#[tokio::test]
#[ignore]
async fn prepare_and_boot() {
    use kite_core::download::Layout;
    use kite_core::launch::{prepare, LaunchRequest, Session};
    use kite_core::settings::GameSettings;
    use kite_core::profile::{Loader, ProfileStore};

    let root = std::env::var("KITE_TEST_ROOT").map(std::path::PathBuf::from).unwrap_or_else(|_| std::env::temp_dir().join("kite-live"));
    // A loader version can be pinned: 1.20.1:forge:47.3.0
    let wanted = std::env::var("KITE_TEST_PROFILES").unwrap_or_else(|_| "1.21.11:fabric".into());
    let client = reqwest::Client::new();
    let games = std::env::temp_dir().join("kite-live-games");
    let store = ProfileStore::new(&games);
    for spec in wanted.split(',') {
        let parts: Vec<&str> = spec.split(':').collect();
        let (mc, loader) = (parts[0], parts[1]);
        let loader: Loader = serde_json::from_value(serde_json::Value::String(loader.into())).unwrap();
        let mut profile = store.create(&format!("live {mc}"), mc, loader).unwrap();
        profile.loader_version = parts.get(2).map(|v| v.to_string());
        let client_jar = kite_core::runtime::prepare_profile(&root, &profile, &store.dir(&profile.id)).unwrap();
        println!("{spec}: kite client = {client_jar:?}");
        // KITE_TEST_EXTRA_MODS=<folder>: also load these jars, to check compatibility with a real mod set.
        if let Ok(extra) = std::env::var("KITE_TEST_EXTRA_MODS") {
            for f in std::fs::read_dir(extra).unwrap().flatten() {
                if f.path().extension().is_some_and(|e| e == "jar") {
                    std::fs::copy(f.path(), store.dir(&profile.id).join("mods").join(f.file_name())).unwrap();
                }
            }
        }
        let session = Session { username: "KiteTest".into(), uuid: "00000000000000000000000000000000".into(), access_token: "0".into() };
        let mut last = String::new();
        let req = LaunchRequest { game_dir: store.dir(&profile.id), session, settings: GameSettings::default(), extra_jvm: vec![], quick_play: None };
        let argv = prepare(&client, &Layout::new(&root), &profile, req, |s, d, t| {
            if s != last || d == t {
                println!("{spec}: {s} {d}/{t}");
                last = s.to_string();
            }
        })
        .await
        .unwrap();
        let log = store.dir(&profile.id).join("out.log");
        let f = std::fs::File::create(&log).unwrap();
        let mut child = std::process::Command::new(&argv[0])
            .args(&argv[1..])
            .current_dir(store.dir(&profile.id))
            .stdout(f.try_clone().unwrap())
            .stderr(f)
            .spawn()
            .unwrap();
        tokio::time::sleep(std::time::Duration::from_secs(25)).await;
        let alive = child.try_wait().unwrap().is_none();
        let _ = child.kill();
        let _ = child.wait();
        let out = std::fs::read_to_string(&log).unwrap_or_default();
        let tail: Vec<&str> = out.lines().rev().take(25).collect::<Vec<_>>().into_iter().rev().collect();
        println!("{spec}: alive after 25s = {alive}\n{}", tail.join("\n"));
        assert!(alive, "{spec} exited early");
        if client_jar.is_some() {
            assert!(out.contains("Kite loaded"), "{spec}: Kite client did not load");
        }
    }
    let _ = std::fs::remove_dir_all(&games);
}

#[tokio::test]
#[ignore]
async fn modrinth_search_and_versions() {
    use kite_core::modrinth::{Modrinth, ProjectType};
    let api = Modrinth::new().unwrap();
    let r = api.search("sodium", ProjectType::Mod, Some("1.21.11"), Some("fabric"), 0, 5).await.unwrap();
    println!("hits: {} first: {:?}", r.total_hits, r.hits.first().map(|h| &h.slug));
    assert!(r.hits.iter().any(|h| h.slug == "sodium"));
    let v = api.versions("sodium", "1.21.11", &["fabric".to_string()]).await.unwrap();
    println!("sodium versions: {}", v.len());
    assert!(!v.is_empty());
    let shaders = api.search("complementary", ProjectType::Shader, Some("1.21.11"), None, 0, 3).await.unwrap();
    println!("shaders: {}", shaders.hits.len());
}
