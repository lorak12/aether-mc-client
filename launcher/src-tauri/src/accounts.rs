//! Accounts: Microsoft sign-in (browser or device code), dev offline accounts, the saved-accounts registry over the OS
//! keychain, and the loopback bridge the running game uses to switch accounts live.
use crate::{e, random_hex, AppState, Res};
use kite_core::auth::{self, DeviceCode};
use kite_core::launch::Session;
use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};
use tauri::{AppHandle, Emitter, Manager, State};

const KEYRING_SERVICE: &str = "kite-launcher";
/// Windows Credential Manager caps a secret at 2560 bytes; Microsoft refresh tokens can exceed that, so store in chunks.
const KEYRING_CHUNK: usize = 2000;

#[derive(Serialize)]
pub struct AccountInfo {
    username: String,
    uuid: String,
}

fn chunk_entry(uuid: &str, i: usize) -> keyring::Result<keyring::Entry> {
    keyring::Entry::new(KEYRING_SERVICE, &format!("{uuid}#{i}"))
}

/// Delete chunks `from..` until one is missing.
fn delete_chunks(uuid: &str, from: usize) {
    let mut i = from;
    while let Ok(entry) = chunk_entry(uuid, i) {
        if entry.delete_credential().is_err() {
            break;
        }
        i += 1;
    }
}

fn store_refresh(uuid: &str, token: &str) -> Res<()> {
    let chunks: Vec<&[u8]> = token.as_bytes().chunks(KEYRING_CHUNK).collect();
    for (i, c) in chunks.iter().enumerate() {
        chunk_entry(uuid, i).and_then(|en| en.set_secret(c)).map_err(|err| format!("couldn't save sign-in to the OS keychain: {err}"))?;
    }
    delete_chunks(uuid, chunks.len());
    Ok(())
}

fn load_refresh(uuid: &str) -> Option<String> {
    let mut bytes = Vec::new();
    for i in 0.. {
        match chunk_entry(uuid, i).and_then(|en| en.get_secret()) {
            Ok(c) => bytes.extend(c),
            Err(_) => break,
        }
    }
    String::from_utf8(bytes).ok().filter(|t| !t.is_empty())
}

/// Dev-only offline account: the player name is stored in this marker file instead of a keychain token.
fn offline_name(root: &Path) -> Option<String> {
    std::fs::read_to_string(root.join("offline_account")).ok().map(|n| n.trim().to_string()).filter(|n| !n.is_empty())
}

fn active_uuid(root: &Path) -> Option<String> {
    std::fs::read_to_string(root.join("active_account")).ok().map(|u| u.trim().to_string()).filter(|u| !u.is_empty())
}

/// Every account signed in on this machine. Tokens live in the keychain (per uuid); this file only lists them.
#[derive(Clone, Serialize, Deserialize)]
pub struct SavedAccount {
    pub uuid: String,
    pub username: String,
    /// "microsoft" or "offline"
    pub kind: String,
}

#[derive(Serialize)]
pub struct AccountList {
    accounts: Vec<SavedAccount>,
    active: Option<String>,
}

pub fn accounts_path(root: &Path) -> PathBuf {
    root.join("accounts.json")
}

pub fn load_accounts(root: &Path) -> Vec<SavedAccount> {
    let mut list: Vec<SavedAccount> = std::fs::read(accounts_path(root)).ok().and_then(|b| serde_json::from_slice(&b).ok()).unwrap_or_default();
    // Accounts signed in before the registry existed.
    if let Some(name) = offline_name(root) {
        if !list.iter().any(|a| a.kind == "offline" && a.username == name) {
            if let Ok(sess) = auth::offline_session(&name) {
                list.push(SavedAccount { uuid: sess.uuid, username: name, kind: "offline".into() });
            }
        }
    }
    if let Some(uuid) = active_uuid(root) {
        if !list.iter().any(|a| a.uuid == uuid) {
            let username = std::fs::read_to_string(root.join("active_account_name")).map(|n| n.trim().to_string()).unwrap_or_default();
            list.push(SavedAccount { uuid, username, kind: "microsoft".into() });
        }
    }
    list
}

pub fn save_accounts(root: &Path, list: &[SavedAccount]) -> Res<()> {
    std::fs::write(accounts_path(root), serde_json::to_vec_pretty(list).map_err(e)?).map_err(e)
}

fn remember_account(root: &Path, acc: SavedAccount) -> Res<()> {
    let mut list = load_accounts(root);
    list.retain(|a| a.uuid != acc.uuid);
    list.push(acc);
    save_accounts(root, &list)
}

/// The uuid of the account Play uses.
pub fn current_uuid(root: &Path) -> Option<String> {
    if let Some(name) = offline_name(root) {
        return auth::offline_session(&name).ok().map(|s| s.uuid);
    }
    active_uuid(root)
}

/// The active account, if any.
pub fn current_account(root: &Path) -> Option<SavedAccount> {
    let uuid = current_uuid(root)?;
    load_accounts(root).into_iter().find(|a| a.uuid == uuid)
}

fn activate_account(root: &Path, acc: &SavedAccount) -> Res<()> {
    if acc.kind == "offline" {
        std::fs::write(root.join("offline_account"), &acc.username).map_err(e)?;
    } else {
        let _ = std::fs::remove_file(root.join("offline_account"));
        std::fs::write(root.join("active_account"), &acc.uuid).map_err(e)?;
        std::fs::write(root.join("active_account_name"), &acc.username).map_err(e)?;
    }
    Ok(())
}

/// A fresh game session for `acc`: offline sessions directly, Microsoft ones by refreshing the saved sign-in.
pub async fn session_for(s: &AppState, acc: &SavedAccount) -> Res<Session> {
    if acc.kind == "offline" {
        return auth::offline_session(&acc.username).map_err(e);
    }
    // Held across the refresh so concurrent callers (the UI fires several backend calls at once) wait for one
    // refresh instead of each hitting login_with_xbox, which rate limits (429) after a handful of calls.
    let mut cache = SESSIONS.get_or_init(Default::default).lock().await;
    if let Some((session, at)) = cache.get(&acc.uuid) {
        if at.elapsed() < SESSION_TTL {
            return Ok(session.clone());
        }
    }
    let client_id = s.cfg.azure().map_err(e)?;
    let token = load_refresh(&acc.uuid).ok_or("Your sign-in expired or was removed. Sign in again (account menu, top right).")?;
    let account = auth::login_with_refresh(s.api.http(), client_id, &token)
        .await
        .map_err(|err| format!("Couldn't refresh your Microsoft sign-in ({err}). Sign in again from the account menu."))?;
    if let Some(r) = &account.refresh_token {
        store_refresh(&acc.uuid, r)?;
    }
    if account.session.username != acc.username {
        let _ = remember_account(&s.root, SavedAccount { username: account.session.username.clone(), ..acc.clone() });
        if active_uuid(&s.root).as_deref() == Some(acc.uuid.as_str()) {
            let _ = std::fs::write(s.root.join("active_account_name"), &account.session.username);
        }
    }
    s.log(format!("refreshed the Microsoft sign-in for {}", account.session.username));
    cache.insert(acc.uuid.clone(), (account.session.clone(), std::time::Instant::now()));
    Ok(account.session)
}

/// Minecraft sessions by account uuid. Their access tokens last 24 hours; reuse one for a few hours.
static SESSIONS: std::sync::OnceLock<tokio::sync::Mutex<std::collections::HashMap<String, (Session, std::time::Instant)>>> = std::sync::OnceLock::new();
const SESSION_TTL: std::time::Duration = std::time::Duration::from_secs(4 * 3600);

/// Drop a signed-out account's cached session.
async fn forget_session(uuid: &str) {
    if let Some(cache) = SESSIONS.get() {
        cache.lock().await.remove(uuid);
    }
}

/// Session for whoever Play uses now.
pub async fn current_session(s: &AppState) -> Res<(SavedAccount, Session)> {
    let acc = current_account(&s.root).ok_or("Sign in first (account menu, top right).")?;
    let session = session_for(s, &acc).await?;
    Ok((acc, session))
}

async fn finish_microsoft(s: &AppState, tokens: auth::MsTokens) -> Res<AccountInfo> {
    let session = auth::minecraft_session(s.api.http(), &tokens.access_token).await.map_err(e)?;
    let refresh = tokens.refresh_token.as_deref().ok_or("Microsoft returned no refresh token; try signing in again")?;
    store_refresh(&session.uuid, refresh)?;
    let acc = SavedAccount { uuid: session.uuid.clone(), username: session.username.clone(), kind: "microsoft".into() };
    // A Microsoft sign-in becomes the active account (replacing an offline one).
    activate_account(&s.root, &acc)?;
    remember_account(&s.root, acc)?;
    *s.token.lock().await = None;
    s.log(format!("signed in as {}", session.username));
    Ok(AccountInfo { username: session.username, uuid: session.uuid })
}

#[tauri::command]
pub async fn start_login(s: State<'_, AppState>) -> Res<DeviceCode> {
    let id = s.cfg.azure().map_err(e)?;
    let code = auth::start_device_login(s.api.http(), id).await.map_err(e)?;
    *s.pending_login.lock().await = Some(code.clone());
    Ok(code)
}

#[tauri::command]
pub async fn finish_login(s: State<'_, AppState>) -> Res<AccountInfo> {
    let id = s.cfg.azure().map_err(e)?.to_string();
    let code = s.pending_login.lock().await.clone().ok_or("no login in progress")?;
    let tokens = auth::poll_device_login(s.api.http(), &id, &code).await.map_err(e)?;
    finish_microsoft(&s, tokens).await
}

const DONE_PAGE: &str = r#"<!doctype html><meta charset="utf-8"><title>Kite Client</title><style>
body{margin:0;height:100vh;display:grid;place-items:center;background:#0b0d14;color:#e8ebf5;font:16px system-ui,sans-serif}
div{text-align:center}h1{font-size:22px;margin:0 0 8px}p{color:#9aa3b8;margin:0}</style>
<div><h1>%TITLE%</h1><p>%TEXT%</p></div>"#;

/// Read the request line of one loopback connection and answer with a small page. Returns the query string.
async fn read_redirect(stream: &mut tokio::net::TcpStream) -> Option<String> {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    let mut buf = vec![0u8; 8192];
    let n = tokio::time::timeout(std::time::Duration::from_secs(10), stream.read(&mut buf)).await.ok()?.ok()?;
    let req = String::from_utf8_lossy(&buf[..n]).to_string();
    let target = req.lines().next()?.split_whitespace().nth(1)?.to_string();
    let query = target.split_once('?').map(|(_, q)| q.to_string());
    let ok = query.as_deref().is_some_and(|q| q.contains("code="));
    let (title, text) = if ok { ("You're signed in", "You can close this tab and go back to Kite Client.") } else { ("Sign-in didn't finish", "Go back to Kite and try again.") };
    let body = DONE_PAGE.replace("%TITLE%", title).replace("%TEXT%", text);
    let resp = format!("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}", body.len());
    let _ = stream.write_all(resp.as_bytes()).await;
    // Favicon and other stray requests have no query: keep waiting for the real redirect.
    query
}

fn query_param(q: &str, key: &str) -> Option<String> {
    reqwest::Url::parse(&format!("http://x/?{q}")).ok()?.query_pairs().find(|(k, _)| k == key).map(|(_, v)| v.to_string())
}

/// Sign in through the browser: Microsoft's own page (email + password, passkeys, 2FA), redirected back to a
/// loopback port. Needs `http://localhost` as a "Mobile and desktop" redirect URI on the Azure app.
#[tauri::command]
pub async fn browser_login(s: State<'_, AppState>) -> Res<AccountInfo> {
    let client_id = s.cfg.azure().map_err(e)?.to_string();
    let v4 = tokio::net::TcpListener::bind(("127.0.0.1", 0)).await.map_err(e)?;
    let port = v4.local_addr().map_err(e)?.port();
    // Browsers may resolve "localhost" to ::1 first; listen there on the same port too when possible.
    let v6 = tokio::net::TcpListener::bind(("::1", port)).await.ok();
    let redirect = format!("http://localhost:{port}");
    let verifier = random_hex(32);
    let state = random_hex(16);
    crate::shell::open_with_system(&auth::authorize_url(&client_id, &redirect, &verifier, &state))?;

    let (tx, mut cancel) = tokio::sync::oneshot::channel();
    *s.login_cancel.lock().map_err(e)? = Some(tx);
    let deadline = tokio::time::Instant::now() + std::time::Duration::from_secs(300);
    let query = loop {
        let accepted = tokio::select! {
            r = v4.accept() => r.ok(),
            r = async { match &v6 { Some(l) => l.accept().await.ok(), None => std::future::pending().await } } => r,
            _ = &mut cancel => return Err("Sign-in cancelled".into()),
            _ = tokio::time::sleep_until(deadline) => return Err("Sign-in timed out. Try again.".into()),
        };
        let Some((mut stream, _)) = accepted else { continue };
        if let Some(q) = read_redirect(&mut stream).await {
            if query_param(&q, "code").is_some() || query_param(&q, "error").is_some() {
                break q;
            }
        }
    };
    s.login_cancel.lock().map_err(e)?.take();
    if query_param(&query, "state").as_deref() != Some(state.as_str()) {
        return Err("The sign-in reply didn't match this request. Try again.".into());
    }
    if let Some(err) = query_param(&query, "error") {
        let desc = query_param(&query, "error_description").unwrap_or(err);
        return Err(if desc.contains("cancel") || desc.contains("access_denied") { "Sign-in cancelled".into() } else { format!("Microsoft sign-in failed: {desc}") });
    }
    let code = query_param(&query, "code").ok_or("Microsoft sent no sign-in code")?;
    let tokens = auth::exchange_code(s.api.http(), &client_id, &code, &redirect, &verifier).await.map_err(e)?;
    finish_microsoft(&s, tokens).await
}

#[tauri::command]
pub fn cancel_login(s: State<AppState>) {
    if let Ok(mut c) = s.login_cancel.lock() {
        if let Some(tx) = c.take() {
            let _ = tx.send(());
        }
    }
}

/// Development only: play without Microsoft sign-in until the Azure app is approved for Minecraft services.
#[tauri::command]
pub async fn offline_login(s: State<'_, AppState>, name: String) -> Res<AccountInfo> {
    let session = auth::offline_session(&name).map_err(e)?;
    std::fs::write(s.root.join("offline_account"), &session.username).map_err(e)?;
    remember_account(&s.root, SavedAccount { uuid: session.uuid.clone(), username: session.username.clone(), kind: "offline".into() })?;
    *s.token.lock().await = None;
    Ok(AccountInfo { username: session.username, uuid: session.uuid })
}

#[tauri::command]
pub fn list_accounts(s: State<AppState>) -> AccountList {
    AccountList { accounts: load_accounts(&s.root), active: current_uuid(&s.root) }
}

/// Make a saved account the one Play signs in with.
#[tauri::command]
pub async fn switch_account(s: State<'_, AppState>, uuid: String) -> Res<SavedAccount> {
    let acc = load_accounts(&s.root).into_iter().find(|a| a.uuid == uuid).ok_or("That account isn't signed in on this device")?;
    activate_account(&s.root, &acc)?;
    *s.token.lock().await = None;
    Ok(acc)
}

/// Sign an account out of this device (deletes its saved sign-in).
#[tauri::command]
pub async fn remove_account(s: State<'_, AppState>, uuid: String) -> Res<()> {
    let mut list = load_accounts(&s.root);
    let Some(pos) = list.iter().position(|a| a.uuid == uuid) else { return Ok(()) };
    let acc = list.remove(pos);
    if acc.kind == "microsoft" {
        delete_chunks(&acc.uuid, 0);
        forget_session(&acc.uuid).await;
        if active_uuid(&s.root).as_deref() == Some(acc.uuid.as_str()) {
            let _ = std::fs::remove_file(s.root.join("active_account"));
            let _ = std::fs::remove_file(s.root.join("active_account_name"));
        }
    } else if offline_name(&s.root).as_deref() == Some(acc.username.as_str()) {
        let _ = std::fs::remove_file(s.root.join("offline_account"));
    }
    *s.token.lock().await = None;
    save_accounts(&s.root, &list)
}

#[tauri::command]
pub async fn logout(s: State<'_, AppState>) -> Res<()> {
    if let Some(uuid) = current_uuid(&s.root) {
        let mut list = load_accounts(&s.root);
        list.retain(|a| a.uuid != uuid);
        save_accounts(&s.root, &list)?;
    }
    if offline_name(&s.root).is_none() {
        if let Some(uuid) = active_uuid(&s.root) {
            delete_chunks(&uuid, 0);
            forget_session(&uuid).await;
        }
    }
    let _ = std::fs::remove_file(s.root.join("offline_account"));
    let _ = std::fs::remove_file(s.root.join("active_account"));
    let _ = std::fs::remove_file(s.root.join("active_account_name"));
    *s.token.lock().await = None;
    Ok(())
}

#[tauri::command]
pub fn account_name(s: State<AppState>) -> Option<String> {
    if let Some(n) = offline_name(&s.root) {
        return Some(n);
    }
    active_uuid(&s.root)?;
    // Signed in but the name isn't cached yet (it is after the next launch): empty string, not None.
    Some(std::fs::read_to_string(s.root.join("active_account_name")).map(|n| n.trim().to_string()).unwrap_or_default())
}

// ---------- live account switching ----------

/// While the game runs, a loopback-only endpoint hands it a fresh session for one of the saved accounts, so the
/// in-game account switcher swaps accounts without a restart. The game gets the port and a per-launch secret through
/// KITE_SESSION_PORT / KITE_SESSION_KEY and sends one JSON line `{"key", "uuid"}`; the reply is one JSON line
/// `{"ok", "username", "uuid", "token", "kind"}` or `{"ok": false, "error"}`.
pub struct SessionBridge {
    pub port: u16,
    pub key: String,
    pub stop: std::sync::Arc<std::sync::atomic::AtomicBool>,
}

pub fn start_session_bridge(app: AppHandle) -> std::io::Result<SessionBridge> {
    use std::sync::atomic::Ordering;
    let listener = std::net::TcpListener::bind(("127.0.0.1", 0))?;
    listener.set_nonblocking(true)?;
    let port = listener.local_addr()?.port();
    let key = random_hex(32);
    let stop = std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false));
    let (secret, stopped) = (key.clone(), stop.clone());
    std::thread::spawn(move || {
        while !stopped.load(Ordering::Relaxed) {
            match listener.accept() {
                Ok((stream, _)) => {
                    let (app, secret) = (app.clone(), secret.clone());
                    std::thread::spawn(move || serve_session(&app, stream, &secret));
                }
                Err(err) if err.kind() == std::io::ErrorKind::WouldBlock => std::thread::sleep(std::time::Duration::from_millis(150)),
                Err(_) => std::thread::sleep(std::time::Duration::from_millis(500)),
            }
        }
    });
    Ok(SessionBridge { port, key, stop })
}

fn serve_session(app: &AppHandle, stream: std::net::TcpStream, key: &str) {
    use std::io::{BufRead, Write};
    let _ = stream.set_nonblocking(false);
    let _ = stream.set_read_timeout(Some(std::time::Duration::from_secs(5)));
    let mut line = String::new();
    let reply = match std::io::BufReader::new(&stream).read_line(&mut line) {
        Ok(_) => session_reply(app, &line, key).unwrap_or_else(|err| serde_json::json!({ "ok": false, "error": err })),
        Err(_) => serde_json::json!({ "ok": false, "error": "bad request" }),
    };
    let mut out = &stream;
    let _ = writeln!(out, "{reply}");
}

fn session_reply(app: &AppHandle, line: &str, key: &str) -> Res<serde_json::Value> {
    let req: serde_json::Value = serde_json::from_str(line.trim()).map_err(|_| "bad request")?;
    if req.get("key").and_then(|k| k.as_str()) != Some(key) {
        return Err("not allowed".into());
    }
    let uuid = req.get("uuid").and_then(|u| u.as_str()).ok_or("no account given")?;
    let s = app.state::<AppState>();
    let acc = load_accounts(&s.root).into_iter().find(|a| a.uuid == uuid).ok_or("That account isn't signed in on this device")?;
    let session = tauri::async_runtime::block_on(session_for(&s, &acc))?;
    // Play uses this account from now on too.
    activate_account(&s.root, &SavedAccount { uuid: acc.uuid.clone(), username: session.username.clone(), kind: acc.kind.clone() })?;
    *tauri::async_runtime::block_on(s.token.lock()) = None;
    let _ = app.emit("account-switched", &acc.uuid);
    Ok(serde_json::json!({ "ok": true, "username": session.username, "uuid": session.uuid, "token": session.access_token, "kind": acc.kind }))
}

/// Skin texture URLs by uuid, kept for an hour so heads don't hammer Mojang's session server (rate limited).
static SKINS: std::sync::OnceLock<std::sync::Mutex<std::collections::HashMap<String, (Option<String>, std::time::Instant)>>> = std::sync::OnceLock::new();

/// A player's skin texture (https URL) for drawing their face; null for offline accounts and players without a skin.
#[tauri::command]
pub async fn skin_url(s: State<'_, AppState>, uuid: String) -> Res<Option<String>> {
    let key = uuid.replace('-', "").to_ascii_lowercase();
    let cache = SKINS.get_or_init(Default::default);
    if let Some((url, at)) = cache.lock().map_err(e)?.get(&key) {
        if at.elapsed() < std::time::Duration::from_secs(3600) {
            return Ok(url.clone());
        }
    }
    match auth::skin_url(s.api.http(), &key).await {
        Ok(url) => {
            cache.lock().map_err(e)?.insert(key, (url.clone(), std::time::Instant::now()));
            Ok(url)
        }
        Err(err) => {
            s.log(format!("skin lookup for {key} failed: {err}"));
            Err(e(err))
        }
    }
}
