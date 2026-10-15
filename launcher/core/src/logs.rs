//! Log sharing: upload a log to mclo.gs (the paste service Minecraft players and support channels use) and get a link.
//! Access tokens and similar secrets are removed before anything leaves the machine; mclo.gs also hides IPs.
use crate::{Error, Result};
use serde::Deserialize;

const MCLOGS: &str = "https://api.mclo.gs/1/log";
/// mclo.gs keeps at most 10 MiB / 25k lines; send the end of longer logs.
const MAX_BYTES: usize = 9 * 1024 * 1024;
const MAX_LINES: usize = 24_000;

/// Mojang's log4j config writes XML events for launchers; turn them back into plain lines.
pub fn plain_lines(text: &str) -> Vec<String> {
    text.lines()
        .filter_map(|l| {
            let t = l.trim();
            if let Some(start) = t.find("<![CDATA[") {
                let msg = &t[start + 9..];
                Some(msg.split("]]>").next().unwrap_or(msg).to_string())
            } else if t.starts_with("<log4j:") || t.starts_with("</log4j:") {
                None
            } else {
                Some(l.to_string())
            }
        })
        .collect()
}

/// Remove secrets: JWT-looking tokens (Minecraft/Xbox access tokens) and the value after `--accessToken`.
pub fn redact(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    let mut hide_next = false;
    for (i, line) in text.split('\n').enumerate() {
        if i > 0 {
            out.push('\n');
        }
        let words: Vec<String> = line
            .split(' ')
            .map(|w| {
                if hide_next && !w.is_empty() {
                    hide_next = false;
                    return "[redacted]".to_string();
                }
                if w == "--accessToken" {
                    hide_next = true;
                }
                // A JWT anywhere in the word: three base64url parts, the first starting with "eyJ".
                let Some(start) = w.find("eyJ") else { return w.to_string() };
                let len = w[start..].find(|c: char| !(c.is_ascii_alphanumeric() || c == '-' || c == '_' || c == '.')).unwrap_or(w.len() - start);
                let token = &w[start..start + len];
                if token.matches('.').count() == 2 && token.len() > 40 {
                    w.replace(token, "[redacted]")
                } else {
                    w.to_string()
                }
            })
            .collect();
        out.push_str(&words.join(" "));
    }
    out
}

/// The last `MAX_LINES` lines / `MAX_BYTES` bytes.
fn tail(text: &str) -> String {
    let lines: Vec<&str> = text.lines().collect();
    let mut s = lines[lines.len().saturating_sub(MAX_LINES)..].join("\n");
    if s.len() > MAX_BYTES {
        let mut cut = s.len() - MAX_BYTES;
        while !s.is_char_boundary(cut) {
            cut += 1;
        }
        s = s[cut..].to_string();
    }
    s
}

#[derive(Deserialize)]
struct McLogsResponse {
    success: bool,
    #[serde(default)]
    url: Option<String>,
    #[serde(default)]
    error: Option<String>,
}

/// Upload `text` and return the share link.
pub async fn upload(client: &reqwest::Client, text: &str) -> Result<String> {
    let content = tail(&redact(text));
    if content.trim().is_empty() {
        return Err(Error::Other("The log is empty".into()));
    }
    let r: McLogsResponse = client.post(MCLOGS).form(&[("content", content.as_str())]).send().await?.error_for_status()?.json().await?;
    match (r.success, r.url) {
        (true, Some(url)) => Ok(url),
        _ => Err(Error::Other(format!("mclo.gs refused the log: {}", r.error.unwrap_or_else(|| "unknown error".into())))),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn redacts_tokens() {
        let jwt = format!("eyJhbGciOiJIUzI1NiJ9.{}.sig_part-1", "a".repeat(40));
        let r = redact(&format!("token={jwt} ok\nargs --accessToken secret123 --version 1.21"));
        assert!(!r.contains("secret123") && !r.contains("eyJhbGci"));
        assert!(r.contains("--version 1.21") && r.contains(" ok"));
    }

    #[test]
    fn xml_to_lines() {
        let x = "<log4j:Event a=\"b\">\n  <log4j:Message><![CDATA[Hello]]></log4j:Message>\n</log4j:Event>\nraw line";
        assert_eq!(plain_lines(x), ["Hello", "raw line"]);
    }

    #[test]
    fn tails_long_logs() {
        let long = "x\n".repeat(MAX_LINES + 10);
        assert_eq!(tail(&long).lines().count(), MAX_LINES);
    }
}
