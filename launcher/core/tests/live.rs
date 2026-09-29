//! Network tests against Mojang. Run with: cargo test -p aether-core --test live -- --ignored --nocapture
use aether_core::version::{Manifest, VersionJson};

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
/// Set AETHER_TEST_ROOT to reuse a cache (e.g. the launcher's app data folder), AETHER_TEST_PROFILES=1.21.11:fabric,1.8.9:vanilla.
#[tokio::test]
#[ignore]
async fn prepare_and_boot() {
    use aether_core::download::Layout;
    use aether_core::launch::{prepare, Session};
    use aether_core::profile::{Loader, ProfileStore};

    let root = std::env::var("AETHER_TEST_ROOT").map(std::path::PathBuf::from).unwrap_or_else(|_| std::env::temp_dir().join("aether-live"));
    let wanted = std::env::var("AETHER_TEST_PROFILES").unwrap_or_else(|_| "1.21.11:fabric".into());
    let client = reqwest::Client::new();
    let games = std::env::temp_dir().join("aether-live-games");
    let store = ProfileStore::new(&games);
    for spec in wanted.split(',') {
        let (mc, loader) = spec.split_once(':').unwrap();
        let loader: Loader = serde_json::from_value(serde_json::Value::String(loader.into())).unwrap();
        let profile = store.create(&format!("live {mc}"), mc, loader).unwrap();
        let session = Session { username: "AetherTest".into(), uuid: "00000000000000000000000000000000".into(), access_token: "0".into() };
        let mut last = String::new();
        let argv = prepare(&client, &Layout::new(&root), &profile, store.dir(&profile.id), session, |s, d, t| {
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
    }
    let _ = std::fs::remove_dir_all(&games);
}

#[tokio::test]
#[ignore]
async fn modrinth_search_and_versions() {
    use aether_core::modrinth::{Modrinth, ProjectType};
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
