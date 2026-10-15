//! Microsoft -> Xbox Live -> XSTS -> Minecraft services login (device-code flow).
//! Requires an Azure AD app id (public client, "Mobile and desktop" platform) approved for Minecraft services.
use crate::launch::Session;
use crate::{Error, Result};
use serde::Deserialize;
use serde_json::json;
use std::time::Duration;

const SCOPE: &str = "XboxLive.signin offline_access";
const DEVICE_URL: &str = "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode";
const TOKEN_URL: &str = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";

#[derive(Debug, Clone, Deserialize, serde::Serialize)]
pub struct DeviceCode {
    pub device_code: String,
    pub user_code: String,
    pub verification_uri: String,
    pub expires_in: u64,
    pub interval: u64,
}

#[derive(Debug, Clone, Deserialize)]
pub struct MsTokens {
    pub access_token: String,
    pub refresh_token: Option<String>,
}

/// Everything needed to launch, plus the refresh token to store in the OS keychain.
#[derive(Debug, Clone)]
pub struct Account {
    pub session: Session,
    pub refresh_token: Option<String>,
}

pub async fn start_device_login(client: &reqwest::Client, client_id: &str) -> Result<DeviceCode> {
    let r = client.post(DEVICE_URL).form(&[("client_id", client_id), ("scope", SCOPE)]).send().await?;
    if !r.status().is_success() {
        let v: serde_json::Value = r.json().await.unwrap_or_default();
        return Err(Error::Auth(azure_error_message(v["error_description"].as_str().unwrap_or("unknown error"))));
    }
    Ok(r.json().await?)
}

/// Turn common Azure app-registration mistakes into instructions.
fn azure_error_message(desc: &str) -> String {
    if desc.contains("AADSTS70002") || desc.contains("AADSTS7000218") {
        "Your Azure app doesn't allow device sign-in. In the Azure portal open the app registration > Authentication > \
         Advanced settings, set \"Allow public client flows\" to Yes, save, and try again."
            .into()
    } else if desc.contains("AADSTS700016") {
        "Azure doesn't recognise this client id. Check azure_client_id in aether.config.json.".into()
    } else if desc.contains("AADSTS50059") || desc.contains("AADSTS9002346") || desc.contains("AADSTS50194") {
        "Your Azure app must allow personal Microsoft accounts (Supported account types: \"...and personal Microsoft accounts\").".into()
    } else {
        format!("Microsoft sign-in failed: {}", desc.lines().next().unwrap_or(desc))
    }
}

/// Poll until the user finishes signing in at `verification_uri`.
pub async fn poll_device_login(client: &reqwest::Client, client_id: &str, code: &DeviceCode) -> Result<MsTokens> {
    let deadline = std::time::Instant::now() + Duration::from_secs(code.expires_in);
    let mut interval = code.interval.max(1);
    loop {
        tokio::time::sleep(Duration::from_secs(interval)).await;
        if std::time::Instant::now() > deadline {
            return Err(Error::Auth("device code expired".into()));
        }
        let resp = client
            .post(TOKEN_URL)
            .form(&[
                ("grant_type", "urn:ietf:params:oauth:grant-type:device_code"),
                ("client_id", client_id),
                ("device_code", code.device_code.as_str()),
            ])
            .send()
            .await?;
        if resp.status().is_success() {
            return Ok(resp.json().await?);
        }
        let v: serde_json::Value = resp.json().await?;
        match v["error"].as_str() {
            Some("authorization_pending") => {}
            Some("slow_down") => interval += 5,
            other => return Err(Error::Auth(format!("login failed: {}", other.unwrap_or("unknown")))),
        }
    }
}

const AUTHORIZE_URL: &str = "https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize";

/// PKCE S256 challenge for a verifier (RFC 7636).
pub fn pkce_challenge(verifier: &str) -> String {
    use base64::Engine;
    use sha2::Digest;
    base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(sha2::Sha256::digest(verifier.as_bytes()))
}

/// The Microsoft sign-in page for the browser flow (email/password, passkeys, 2FA all happen there).
pub fn authorize_url(client_id: &str, redirect_uri: &str, verifier: &str, state: &str) -> String {
    let mut u = reqwest::Url::parse(AUTHORIZE_URL).expect("static url");
    u.query_pairs_mut()
        .append_pair("client_id", client_id)
        .append_pair("response_type", "code")
        .append_pair("redirect_uri", redirect_uri)
        .append_pair("response_mode", "query")
        .append_pair("scope", SCOPE)
        .append_pair("state", state)
        .append_pair("code_challenge", &pkce_challenge(verifier))
        .append_pair("code_challenge_method", "S256")
        .append_pair("prompt", "select_account");
    u.to_string()
}

/// Exchange the code from the browser redirect for tokens.
pub async fn exchange_code(client: &reqwest::Client, client_id: &str, code: &str, redirect_uri: &str, verifier: &str) -> Result<MsTokens> {
    let r = client
        .post(TOKEN_URL)
        .form(&[
            ("grant_type", "authorization_code"),
            ("client_id", client_id),
            ("code", code),
            ("redirect_uri", redirect_uri),
            ("code_verifier", verifier),
            ("scope", SCOPE),
        ])
        .send()
        .await?;
    if !r.status().is_success() {
        let v: serde_json::Value = r.json().await.unwrap_or_default();
        let desc = v["error_description"].as_str().unwrap_or("unknown error");
        if desc.contains("AADSTS50011") || desc.contains("AADSTS500113") {
            return Err(Error::Auth(
                "Your Azure app has no redirect for browser sign-in. In the Azure portal open the app registration > Authentication > \
                 Add a platform > Mobile and desktop applications, add http://localhost and save."
                    .into(),
            ));
        }
        return Err(Error::Auth(azure_error_message(desc)));
    }
    Ok(r.json().await?)
}

pub async fn refresh(client: &reqwest::Client, client_id: &str, refresh_token: &str) -> Result<MsTokens> {
    let r = client
        .post(TOKEN_URL)
        .form(&[
            ("grant_type", "refresh_token"),
            ("client_id", client_id),
            ("refresh_token", refresh_token),
            ("scope", SCOPE),
        ])
        .send()
        .await?
        .error_for_status()?;
    Ok(r.json().await?)
}

#[derive(Deserialize)]
struct XboxResp {
    #[serde(rename = "Token")]
    token: String,
    #[serde(rename = "DisplayClaims")]
    claims: Claims,
}
#[derive(Deserialize)]
struct Claims {
    xui: Vec<Xui>,
}
#[derive(Deserialize)]
struct Xui {
    uhs: String,
}

/// Exchange a Microsoft access token for a Minecraft session. Fails if the account does not own Java Edition.
pub async fn minecraft_session(client: &reqwest::Client, ms_access_token: &str) -> Result<Session> {
    let xbl: XboxResp = client
        .post("https://user.auth.xboxlive.com/user/authenticate")
        .json(&json!({
            "Properties": {"AuthMethod": "RPS", "SiteName": "user.auth.xboxlive.com", "RpsTicket": format!("d={ms_access_token}")},
            "RelyingParty": "http://auth.xboxlive.com",
            "TokenType": "JWT"
        }))
        .send()
        .await?
        .error_for_status()?
        .json()
        .await?;

    let xsts_resp = client
        .post("https://xsts.auth.xboxlive.com/xsts/authorize")
        .json(&json!({
            "Properties": {"SandboxId": "RETAIL", "UserTokens": [xbl.token]},
            "RelyingParty": "rp://api.minecraftservices.com/",
            "TokenType": "JWT"
        }))
        .send()
        .await?;
    if xsts_resp.status() == reqwest::StatusCode::UNAUTHORIZED {
        let v: serde_json::Value = xsts_resp.json().await.unwrap_or_default();
        return Err(Error::Auth(xsts_error_message(v["XErr"].as_u64())));
    }
    let xsts: XboxResp = xsts_resp.error_for_status()?.json().await?;
    let uhs = xsts.claims.xui.first().ok_or_else(|| Error::Auth("no Xbox user hash".into()))?.uhs.clone();

    let mc_resp = client
        .post("https://api.minecraftservices.com/authentication/login_with_xbox")
        .json(&json!({"identityToken": format!("XBL3.0 x={uhs};{}", xsts.token)}))
        .send()
        .await?;
    if mc_resp.status() == reqwest::StatusCode::FORBIDDEN {
        return Err(Error::Auth(
            "Minecraft rejected this Azure app. New app ids must be approved by Mojang first: submit it at https://aka.ms/mce-reviewappid and wait for approval."
                .into(),
        ));
    }
    let mc: serde_json::Value = mc_resp.error_for_status()?.json().await?;
    let access_token = mc["access_token"].as_str().ok_or_else(|| Error::Auth("no Minecraft token".into()))?.to_string();

    let ent: serde_json::Value = client
        .get("https://api.minecraftservices.com/entitlements/mcstore")
        .bearer_auth(&access_token)
        .send()
        .await?
        .error_for_status()?
        .json()
        .await?;
    if !owns_java(&ent) {
        return Err(Error::Auth("this Microsoft account does not own Minecraft: Java Edition".into()));
    }

    let profile: serde_json::Value = client
        .get("https://api.minecraftservices.com/minecraft/profile")
        .bearer_auth(&access_token)
        .send()
        .await?
        .error_for_status()?
        .json()
        .await?;
    Ok(Session {
        username: profile["name"].as_str().ok_or_else(|| Error::Auth("no profile name (create a profile at minecraft.net)".into()))?.into(),
        uuid: profile["id"].as_str().unwrap_or_default().into(),
        access_token,
    })
}

fn owns_java(entitlements: &serde_json::Value) -> bool {
    entitlements["items"]
        .as_array()
        .map(|a| a.iter().any(|i| matches!(i["name"].as_str(), Some("product_minecraft") | Some("game_minecraft"))))
        .unwrap_or(false)
}

fn xsts_error_message(code: Option<u64>) -> String {
    match code {
        Some(2148916233) => "this Microsoft account has no Xbox account; create one first".into(),
        Some(2148916235) => "Xbox Live is not available in your country".into(),
        Some(2148916238) => "child account: must be added to a family by an adult".into(),
        _ => "Xbox authorization failed".into(),
    }
}

/// Full chain from a refresh token (silent re-login).
pub async fn login_with_refresh(client: &reqwest::Client, client_id: &str, refresh_token: &str) -> Result<Account> {
    let tokens = refresh(client, client_id, refresh_token).await?;
    let session = minecraft_session(client, &tokens.access_token).await?;
    Ok(Account { session, refresh_token: tokens.refresh_token.or(Some(refresh_token.to_string())) })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn entitlement_check() {
        assert!(owns_java(&json!({"items":[{"name":"game_minecraft"}]})));
        assert!(!owns_java(&json!({"items":[]})));
        assert!(!owns_java(&json!({})));
    }

    #[test]
    fn pkce_and_authorize_url() {
        // RFC 7636 appendix B.
        assert_eq!(pkce_challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"), "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
        let u = authorize_url("cid", "http://localhost:5000", "v", "st");
        assert!(u.contains("client_id=cid") && u.contains("code_challenge_method=S256") && u.contains("redirect_uri=http%3A%2F%2Flocalhost%3A5000"));
    }

    #[test]
    fn azure_errors_are_actionable() {
        assert!(azure_error_message("AADSTS70002: The provided client is not supported").contains("Allow public client flows"));
        assert!(azure_error_message("AADSTS999: odd").starts_with("Microsoft sign-in failed"));
    }
}

/// Development-only session for testing the client before the Azure app is approved for Minecraft services.
/// The token is a placeholder, so it only joins offline-mode servers and singleplayer.
pub fn offline_session(username: &str) -> Result<Session> {
    let name = username.trim();
    if name.is_empty() || name.len() > 16 || !name.chars().all(|c| c.is_ascii_alphanumeric() || c == '_') {
        return Err(Error::Auth("Offline name must be 1-16 letters, digits or underscores".into()));
    }
    use sha1::{Digest, Sha1};
    let mut b: [u8; 16] = Sha1::digest(format!("OfflinePlayer:{name}").as_bytes())[..16].try_into().unwrap();
    b[6] = (b[6] & 0x0f) | 0x30;
    b[8] = (b[8] & 0x3f) | 0x80;
    Ok(Session { username: name.to_string(), uuid: hex::encode(b), access_token: "0".into() })
}

/// A player's current skin texture URL (https), from Mojang's session server. `None` when the uuid has no Mojang profile
/// (offline accounts) or the profile has no custom skin.
pub async fn skin_url(client: &reqwest::Client, uuid: &str) -> Result<Option<String>> {
    let id: String = uuid.chars().filter(|c| c.is_ascii_hexdigit()).collect::<String>().to_ascii_lowercase();
    if id.len() != 32 {
        return Ok(None);
    }
    let r = client
        .get(format!("https://sessionserver.mojang.com/session/minecraft/profile/{id}"))
        .timeout(Duration::from_secs(10))
        .send()
        .await?;
    // 204 / 404: no such profile.
    if r.status() == reqwest::StatusCode::NO_CONTENT || r.status() == reqwest::StatusCode::NOT_FOUND {
        return Ok(None);
    }
    let profile: serde_json::Value = r.error_for_status()?.json().await?;
    Ok(skin_from_profile(&profile))
}

/// The SKIN url inside a session-server profile's base64 `textures` property, upgraded to https (the launcher's CSP
/// only allows https images; textures.minecraft.net serves both).
pub fn skin_from_profile(profile: &serde_json::Value) -> Option<String> {
    use base64::Engine;
    let value = profile["properties"].as_array()?.iter().find(|p| p["name"] == "textures")?["value"].as_str()?;
    let decoded = base64::engine::general_purpose::STANDARD.decode(value).ok()?;
    let textures: serde_json::Value = serde_json::from_slice(&decoded).ok()?;
    let url = textures["textures"]["SKIN"]["url"].as_str()?;
    Some(url.strip_prefix("http://").map(|rest| format!("https://{rest}")).unwrap_or_else(|| url.to_string()))
}

#[cfg(test)]
mod skin_tests {
    use super::*;
    use base64::Engine;

    #[test]
    fn reads_the_skin_url_and_upgrades_it_to_https() {
        let textures = r#"{"profileId":"x","textures":{"SKIN":{"url":"http://textures.minecraft.net/texture/abc"}}}"#;
        let profile = json!({ "id": "x", "properties": [{ "name": "textures", "value": base64::engine::general_purpose::STANDARD.encode(textures) }] });
        assert_eq!(skin_from_profile(&profile).as_deref(), Some("https://textures.minecraft.net/texture/abc"));
        assert_eq!(skin_from_profile(&json!({ "properties": [] })), None);
        let no_skin = json!({ "properties": [{ "name": "textures", "value": base64::engine::general_purpose::STANDARD.encode(r#"{"textures":{}}"#) }] });
        assert_eq!(skin_from_profile(&no_skin), None);
    }
}

#[cfg(test)]
mod offline_tests {
    use super::*;

    #[test]
    fn offline_session_is_stable_and_validated() {
        let a = offline_session("Steve_Dev").unwrap();
        assert_eq!(a.uuid.len(), 32);
        // The backend's dev offline sign-in computes the same uuid (backend/src/app.ts offlineUuid).
        assert_eq!(a.uuid, "d39f581a49753f5e978a4d0237751c56");
        assert_eq!(a.uuid, offline_session(" Steve_Dev ").unwrap().uuid);
        assert!(offline_session("").is_err());
        assert!(offline_session("bad name!").is_err());
        assert!(offline_session("waytoolongusername1").is_err());
    }
}
