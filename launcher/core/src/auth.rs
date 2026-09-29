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
    fn azure_errors_are_actionable() {
        assert!(azure_error_message("AADSTS70002: The provided client is not supported").contains("Allow public client flows"));
        assert!(azure_error_message("AADSTS999: odd").starts_with("Microsoft sign-in failed"));
    }
}
