//! Client for the Kite backend (`backend/`): sign-in and authenticated JSON calls.
//!
//! Sign-in proves account ownership the way servers do: ask the backend for a nonce, tell Mojang's session server that
//! this account "joined" that nonce, then let the backend check with Mojang. Offline (dev) accounts use
//! `/v1/auth/offline`, which the backend only allows when started with `KITE_ALLOW_OFFLINE=1`.
use crate::{Error, Result};
use serde::Deserialize;
use serde_json::Value;

const MOJANG_JOIN: &str = "https://sessionserver.mojang.com/session/minecraft/join";

#[derive(Debug, Clone, Deserialize)]
pub struct Token {
    pub token: String,
    pub uuid: String,
    pub name: String,
}

#[derive(Clone)]
pub struct Backend {
    http: reqwest::Client,
    base: String,
}

/// A failed call: the HTTP status and the backend's `error` code, so callers can react (401 = sign in again).
#[derive(Debug)]
pub struct ApiError {
    pub status: u16,
    pub code: String,
}

impl Backend {
    pub fn new(http: reqwest::Client, base: &str) -> Self {
        Self { http, base: base.trim_end_matches('/').to_string() }
    }

    pub fn base(&self) -> &str {
        &self.base
    }

    async fn nonce(&self) -> Result<String> {
        let v: Value = self.http.post(format!("{}/v1/auth/nonce", self.base)).send().await?.error_for_status()?.json().await?;
        v["serverId"].as_str().map(String::from).ok_or_else(|| Error::Other("backend sent no nonce".into()))
    }

    /// Sign in with a Minecraft session (Microsoft account).
    pub async fn login_mojang(&self, username: &str, uuid: &str, access_token: &str) -> Result<Token> {
        let server_id = self.nonce().await?;
        let r = self
            .http
            .post(MOJANG_JOIN)
            .json(&serde_json::json!({ "accessToken": access_token, "selectedProfile": uuid, "serverId": server_id }))
            .send()
            .await?;
        if !r.status().is_success() {
            return Err(Error::Auth(format!("Mojang refused the sign-in proof ({})", r.status())));
        }
        let r = self.http.post(format!("{}/v1/auth/mojang", self.base)).json(&serde_json::json!({ "username": username, "serverId": server_id })).send().await?;
        if !r.status().is_success() {
            return Err(Error::Auth(format!("The Kite service didn't accept the sign-in ({})", r.status())));
        }
        Ok(r.json().await?)
    }

    /// Sign in with an offline (development) account.
    pub async fn login_offline(&self, username: &str) -> Result<Token> {
        let r = self.http.post(format!("{}/v1/auth/offline", self.base)).json(&serde_json::json!({ "username": username })).send().await?;
        if r.status() == reqwest::StatusCode::FORBIDDEN || r.status() == reqwest::StatusCode::NOT_FOUND {
            return Err(Error::Auth("Online features need a Microsoft account (this server doesn't allow offline accounts).".into()));
        }
        if !r.status().is_success() {
            return Err(Error::Auth(format!("The Kite service didn't accept the sign-in ({})", r.status())));
        }
        Ok(r.json().await?)
    }

    /// Call `/v1/...` with the token. Non-2xx answers come back as `Err(Ok(ApiError))`, transport errors as `Err(Err(_))`.
    pub async fn call(&self, method: &str, path: &str, token: Option<&str>, body: Option<Value>) -> std::result::Result<Value, std::result::Result<ApiError, Error>> {
        if !path.starts_with("/v1/") || path.contains("..") {
            return Err(Err(Error::Other(format!("not an API path: {path}"))));
        }
        let m = reqwest::Method::from_bytes(method.to_uppercase().as_bytes()).map_err(|_| Err(Error::Other("bad method".into())))?;
        let mut req = self.http.request(m, format!("{}{path}", self.base)).timeout(std::time::Duration::from_secs(20));
        if let Some(t) = token {
            req = req.bearer_auth(t);
        }
        if let Some(b) = body {
            req = req.json(&b);
        }
        let r = req.send().await.map_err(|e| Err(e.into()))?;
        let status = r.status().as_u16();
        let v: Value = r.json().await.unwrap_or(Value::Null);
        if (200..300).contains(&status) {
            Ok(v)
        } else {
            Err(Ok(ApiError { status, code: v["error"].as_str().unwrap_or("error").to_string() }))
        }
    }
}

/// A friendly message for a backend error code.
pub fn message(e: &ApiError) -> String {
    match (e.status, e.code.as_str()) {
        (401, _) => "Your Kite sign-in expired. Try again.".into(),
        (429, _) => "Too many requests. Wait a minute and try again.".into(),
        (_, "insufficient_coins") => "You don't have enough coins for that.".into(),
        (_, "already_owned") => "You already own that.".into(),
        (_, "unknown_user") => "Nobody with that name has used Kite yet.".into(),
        (_, "already_friends") => "You're already friends.".into(),
        (_, "request_exists") => "You already sent a request.".into(),
        (_, "blocked") => "You can't add this player.".into(),
        (_, "self") => "That's you.".into(),
        (_, "not_friends") => "You can only message friends.".into(),
        (_, "payments_disabled") => "Buying coins isn't available yet.".into(),
        (_, "not_found") => "That doesn't exist any more.".into(),
        (s, c) if s >= 500 => format!("The Kite service had a problem ({c}). Try again later."),
        (_, c) => format!("The Kite service said: {}", c.replace('_', " ")),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn rejects_non_api_paths() {
        let b = Backend::new(reqwest::Client::new(), "http://127.0.0.1:9/");
        assert_eq!(b.base(), "http://127.0.0.1:9");
        assert!(matches!(b.call("GET", "/health", None, None).await, Err(Err(_))));
        assert!(matches!(b.call("GET", "/v1/../x", None, None).await, Err(Err(_))));
    }

    #[test]
    fn messages() {
        assert!(message(&ApiError { status: 401, code: "unauthorized".into() }).contains("expired"));
        assert!(message(&ApiError { status: 400, code: "weird_thing".into() }).contains("weird thing"));
    }
}
