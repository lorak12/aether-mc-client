//! The Aether backend (friends, chat, presence, store, news, servers) and launcher updates.
use crate::accounts::{self, current_account};
use crate::{e, AppState, Res};
use aether_core::backend::{self, Token};
use aether_core::update::{self, UpdateInfo};
use serde::Serialize;
use serde_json::Value;
use tauri::{AppHandle, State};

pub struct CachedToken {
    uuid: String,
    token: Token,
    /// Unix seconds; the backend's tokens last an hour.
    expires: i64,
}

fn backend(s: &AppState) -> Res<&backend::Backend> {
    s.backend.as_ref().ok_or_else(|| "Online features need the Aether service: set backend_url in aether.config.json.".to_string())
}

/// A valid backend token for the active account, signing in (Mojang join proof or offline dev login) when needed.
async fn token(s: &AppState, force: bool) -> Res<Token> {
    let acc = current_account(&s.root).ok_or("Sign in to use online features.")?;
    // Held until the new token is stored: parallel calls wait for one sign-in instead of each starting their own.
    let mut cached = s.token.lock().await;
    if let Some(c) = cached.as_ref() {
        if !force && c.uuid == acc.uuid && c.expires > crate::now_unix() + 60 {
            return Ok(c.token.clone());
        }
    }
    let b = backend(s)?;
    let t = if acc.kind == "offline" {
        b.login_offline(&acc.username).await.map_err(e)?
    } else {
        let session = accounts::session_for(s, &acc).await?;
        b.login_mojang(&session.username, &session.uuid, &session.access_token).await.map_err(e)?
    };
    *cached = Some(CachedToken { uuid: acc.uuid, token: t.clone(), expires: crate::now_unix() + 3300 });
    Ok(t)
}

/// Call a backend `/v1/...` route as the active account. Public routes (news, servers, catalogue) work signed out.
#[tauri::command]
pub async fn backend_call(s: State<'_, AppState>, method: String, path: String, body: Option<Value>, auth: Option<bool>) -> Res<Value> {
    let b = backend(&s)?;
    let auth = auth.unwrap_or(true);
    let mut tok = if auth { Some(token(&s, false).await?) } else { None };
    for attempt in 0..2 {
        match b.call(&method, &path, tok.as_ref().map(|t| t.token.as_str()), body.clone()).await {
            Ok(v) => return Ok(v),
            // Expired or revoked: sign in again once.
            Err(Ok(err)) if err.status == 401 && auth && attempt == 0 => tok = Some(token(&s, true).await?),
            Err(Ok(err)) => return Err(backend::message(&err)),
            Err(Err(err)) => return Err(format!("Couldn't reach the Aether service ({err}).")),
        }
    }
    Err("Couldn't sign in to the Aether service.".into())
}

#[derive(Serialize)]
pub struct BackendStatus {
    configured: bool,
    /// Signed in to the backend (has a token for the active account).
    signed_in: bool,
    uuid: Option<String>,
    name: Option<String>,
    error: Option<String>,
}

/// Sign in to the backend if needed and report the result (the UI calls this at start and after account switches).
#[tauri::command]
pub async fn backend_status(s: State<'_, AppState>) -> Res<BackendStatus> {
    if s.backend.is_none() {
        return Ok(BackendStatus { configured: false, signed_in: false, uuid: None, name: None, error: None });
    }
    match token(&s, false).await {
        Ok(t) => Ok(BackendStatus { configured: true, signed_in: true, uuid: Some(t.uuid), name: Some(t.name), error: None }),
        Err(err) => Ok(BackendStatus { configured: true, signed_in: false, uuid: None, name: None, error: Some(err) }),
    }
}

fn manifest_key(s: &AppState) -> Res<&str> {
    s.cfg.manifest_public_key.as_deref().ok_or_else(|| "Update checks need manifest_public_key in aether.config.json.".to_string())
}

#[tauri::command]
pub async fn check_update(s: State<'_, AppState>, channel: Option<String>) -> Res<UpdateInfo> {
    let b = backend(&s)?;
    let m = update::fetch(s.api.http(), b.base(), channel.as_deref().unwrap_or("stable"), manifest_key(&s)?).await.map_err(e)?;
    Ok(update::launcher_info(&m, env!("CARGO_PKG_VERSION")))
}

/// Download the new launcher installer, check it, start it and quit so it can replace this version.
#[tauri::command]
pub async fn install_update(app: AppHandle, s: State<'_, AppState>, channel: Option<String>) -> Res<()> {
    let b = backend(&s)?;
    let m = update::fetch(s.api.http(), b.base(), channel.as_deref().unwrap_or("stable"), manifest_key(&s)?).await.map_err(e)?;
    let rel = m.launcher.as_ref().filter(|l| update::newer(&l.version, env!("CARGO_PKG_VERSION"))).ok_or("You're already on the newest version")?;
    let d = rel.downloads.get(&update::platform_key()).ok_or("There's no download of this version for your system yet")?;
    let name = d.url.rsplit('/').next().filter(|n| !n.is_empty() && !n.contains(['\\', ':'])).unwrap_or("aether-setup");
    let dest = s.root.join("tmp").join(name);
    update::download(s.api.http(), d, &dest).await.map_err(e)?;
    s.log(format!("installing launcher update {} from {}", rel.version, dest.display()));
    std::process::Command::new(&dest).spawn().map_err(|err| format!("couldn't start the installer: {err}"))?;
    app.exit(0);
    Ok(())
}
