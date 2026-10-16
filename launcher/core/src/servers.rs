//! The game's server list (`servers.dat`, uncompressed NBT) and Server List Ping for player counts and latency.
use crate::nbt::{self, Tag};
use crate::{Error, Result};
use serde::Serialize;
use std::path::Path;
use std::time::{Duration, Instant};
use tokio::io::{AsyncReadExt, AsyncWriteExt};

#[derive(Debug, Clone, Serialize, PartialEq)]
pub struct SavedServer {
    /// Position in the list (commands take it together with the address, so a stale UI can't remove the wrong row).
    pub index: usize,
    pub name: String,
    pub address: String,
    /// `data:image/png;base64,...` from the last time the game pinged it.
    pub icon: Option<String>,
}

fn read_root(game_dir: &Path) -> Result<Tag> {
    match std::fs::read(game_dir.join("servers.dat")) {
        Ok(b) => Ok(nbt::read_any(&b)?.1),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(Tag::Compound(vec![("servers".into(), Tag::List(10, vec![]))])),
        Err(e) => Err(e.into()),
    }
}

fn entries(root: &Tag) -> Vec<Tag> {
    root.get("servers").and_then(|t| t.list()).map(|l| l.to_vec()).unwrap_or_default()
}

fn write_root(game_dir: &Path, root: &Tag) -> Result<()> {
    let path = game_dir.join("servers.dat");
    let tmp = game_dir.join("servers.dat.tmp");
    std::fs::write(&tmp, nbt::write("", root))?;
    std::fs::rename(tmp, path)?;
    Ok(())
}

fn with_servers(root: Tag, servers: Vec<Tag>) -> Tag {
    let mut fields = match root {
        Tag::Compound(f) => f,
        _ => vec![],
    };
    fields.retain(|(k, _)| k != "servers");
    fields.insert(0, ("servers".into(), Tag::List(10, servers)));
    Tag::Compound(fields)
}

pub fn list(game_dir: &Path) -> Result<Vec<SavedServer>> {
    Ok(entries(&read_root(game_dir)?)
        .iter()
        .enumerate()
        .filter(|(_, s)| s.get("hidden").and_then(|h| h.int()).unwrap_or(0) == 0)
        .map(|(index, s)| SavedServer {
            index,
            name: s.get("name").and_then(|t| t.str()).unwrap_or("Minecraft Server").to_string(),
            address: s.get("ip").and_then(|t| t.str()).unwrap_or_default().to_string(),
            icon: s.get("icon").and_then(|t| t.str()).filter(|i| !i.is_empty()).map(|i| format!("data:image/png;base64,{i}")),
        })
        .collect())
}

/// Append a server (like "Add Server" in the game). Does nothing if the address is already listed.
pub fn add(game_dir: &Path, name: &str, address: &str) -> Result<()> {
    let address = address.trim();
    if address.is_empty() || address.len() > 255 {
        return Err(Error::Other("Enter a server address".into()));
    }
    let root = read_root(game_dir)?;
    let mut list = entries(&root);
    if list.iter().any(|s| s.get("ip").and_then(|t| t.str()).is_some_and(|ip| ip.eq_ignore_ascii_case(address))) {
        return Ok(());
    }
    let name = if name.trim().is_empty() { "Minecraft Server" } else { name.trim() };
    list.push(Tag::Compound(vec![("name".into(), Tag::String(name.into())), ("ip".into(), Tag::String(address.into()))]));
    std::fs::create_dir_all(game_dir)?;
    write_root(game_dir, &with_servers(root, list))
}

pub fn remove(game_dir: &Path, index: usize, address: &str) -> Result<()> {
    let root = read_root(game_dir)?;
    let mut list = entries(&root);
    let matches = list.get(index).and_then(|s| s.get("ip")).and_then(|t| t.str()) == Some(address);
    if !matches {
        return Err(Error::Other("The server list changed. Reopen it and try again.".into()));
    }
    list.remove(index);
    write_root(game_dir, &with_servers(root, list))
}

// ---------- Server List Ping ----------

#[derive(Debug, Clone, Serialize, PartialEq)]
pub struct Ping {
    pub online: u32,
    pub max: u32,
    pub version: String,
    /// Plain-text MOTD (formatting codes removed).
    pub motd: String,
    pub favicon: Option<String>,
    pub latency_ms: u32,
}

fn varint(mut v: i32, out: &mut Vec<u8>) {
    loop {
        let mut b = (v & 0x7F) as u8;
        v = ((v as u32) >> 7) as i32;
        if v != 0 {
            b |= 0x80;
        }
        out.push(b);
        if v == 0 {
            break;
        }
    }
}

async fn read_varint<R: AsyncReadExt + Unpin>(r: &mut R) -> Result<i32> {
    let mut v: i32 = 0;
    for i in 0..5 {
        let b = r.read_u8().await?;
        v |= ((b & 0x7F) as i32) << (7 * i);
        if b & 0x80 == 0 {
            return Ok(v);
        }
    }
    Err(Error::Other("bad varint from server".into()))
}

fn packet(id: i32, body: &[u8]) -> Vec<u8> {
    let mut inner = Vec::new();
    varint(id, &mut inner);
    inner.extend(body);
    let mut out = Vec::new();
    varint(inner.len() as i32, &mut out);
    out.extend(inner);
    out
}

/// Flatten a chat component (string or JSON) to plain text without `§` codes.
pub fn plain_text(v: &serde_json::Value) -> String {
    let mut out = String::new();
    fn walk(v: &serde_json::Value, out: &mut String) {
        match v {
            serde_json::Value::String(s) => out.push_str(s),
            serde_json::Value::Array(a) => a.iter().for_each(|x| walk(x, out)),
            serde_json::Value::Object(o) => {
                if let Some(t) = o.get("text") {
                    walk(t, out);
                }
                if let Some(e) = o.get("extra") {
                    walk(e, out);
                }
            }
            _ => {}
        }
    }
    walk(v, &mut out);
    let mut clean = String::new();
    let mut skip = false;
    for c in out.chars() {
        if skip {
            skip = false;
        } else if c == '§' {
            skip = true;
        } else {
            clean.push(c);
        }
    }
    clean.trim().to_string()
}

/// `_minecraft._tcp` SRV record via DNS over HTTPS (the standard library has no SRV lookup).
async fn srv(client: &reqwest::Client, host: &str) -> Option<(String, u16)> {
    let url = format!("https://cloudflare-dns.com/dns-query?name=_minecraft._tcp.{host}&type=SRV");
    let v: serde_json::Value =
        client.get(url).header("accept", "application/dns-json").timeout(Duration::from_secs(2)).send().await.ok()?.json().await.ok()?;
    let data = v["Answer"].as_array()?.iter().find(|a| a["type"].as_u64() == Some(33))?["data"].as_str()?.to_string();
    // "priority weight port target."
    let p: Vec<&str> = data.split_whitespace().collect();
    Some((p.get(3)?.trim_end_matches('.').to_string(), p.get(2)?.parse().ok()?))
}

/// Ping `address` (`host[:port]`). Follows SRV records when no port is given.
pub async fn ping(client: &reqwest::Client, address: &str) -> Result<Ping> {
    let (host, port) = crate::launch::split_address(address);
    let a = address.trim();
    let has_port = if a.starts_with('[') { a.contains("]:") } else { a.matches(':').count() == 1 };
    let (conn_host, conn_port) = if has_port { (host.clone(), port) } else { srv(client, &host).await.unwrap_or((host.clone(), port)) };
    tokio::time::timeout(Duration::from_secs(5), ping_inner(&host, port, &conn_host, conn_port))
        .await
        .map_err(|_| Error::Other("The server didn't answer".into()))?
}

async fn ping_inner(host: &str, port: u16, conn_host: &str, conn_port: u16) -> Result<Ping> {
    let start = Instant::now();
    let mut s = tokio::net::TcpStream::connect((conn_host, conn_port)).await?;
    let connect_ms = start.elapsed().as_millis() as u32;
    let mut hs = Vec::new();
    varint(-1, &mut hs); // "any protocol": status works for every version
    varint(host.len() as i32, &mut hs);
    hs.extend(host.as_bytes());
    hs.extend(port.to_be_bytes());
    varint(1, &mut hs);
    s.write_all(&packet(0, &hs)).await?;
    s.write_all(&packet(0, &[])).await?;

    let len = read_varint(&mut s).await?;
    if !(1..=1 << 21).contains(&len) {
        return Err(Error::Other("bad status packet".into()));
    }
    let mut body = vec![0u8; len as usize];
    s.read_exact(&mut body).await?;
    let mut cur = std::io::Cursor::new(body);
    let id = read_varint(&mut cur).await?;
    if id != 0 {
        return Err(Error::Other("unexpected status packet".into()));
    }
    let slen = read_varint(&mut cur).await? as usize;
    let pos = cur.position() as usize;
    let json = cur.get_ref().get(pos..pos + slen).ok_or_else(|| Error::Other("truncated status".into()))?;
    let v: serde_json::Value = serde_json::from_slice(json)?;

    // Latency: a ping packet round trip, falling back to the connect time.
    let t = Instant::now();
    let mut latency = connect_ms;
    if s.write_all(&packet(1, &42i64.to_be_bytes())).await.is_ok() {
        if let Ok(Ok(_)) = tokio::time::timeout(Duration::from_secs(2), read_varint(&mut s)).await {
            latency = t.elapsed().as_millis() as u32;
        }
    }
    Ok(Ping {
        online: v["players"]["online"].as_u64().unwrap_or(0) as u32,
        max: v["players"]["max"].as_u64().unwrap_or(0) as u32,
        version: plain_text(&v["version"]["name"]),
        motd: plain_text(&v["description"]),
        favicon: v["favicon"].as_str().filter(|f| f.starts_with("data:image/")).map(String::from),
        latency_ms: latency.max(1),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn add_list_remove() {
        let g = std::env::temp_dir().join(format!("kite-servers-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&g);
        assert!(list(&g).unwrap().is_empty());
        add(&g, "Hypixel", "mc.hypixel.net").unwrap();
        add(&g, "", "play.example.net:25566").unwrap();
        add(&g, "dupe", "MC.HYPIXEL.NET").unwrap();
        let l = list(&g).unwrap();
        assert_eq!(l.len(), 2);
        assert_eq!(l[1].name, "Minecraft Server");
        assert!(remove(&g, 0, "wrong").is_err());
        remove(&g, 0, "mc.hypixel.net").unwrap();
        assert_eq!(list(&g).unwrap()[0].address, "play.example.net:25566");
        let _ = std::fs::remove_dir_all(&g);
    }

    #[test]
    fn motd_text() {
        let v: serde_json::Value = serde_json::json!({"text": "§aHello ", "extra": [{"text": "§lworld"}, "!"]});
        assert_eq!(plain_text(&v), "Hello world!");
        assert_eq!(plain_text(&serde_json::json!("§cRed")), "Red");
    }

    #[test]
    fn varints() {
        let mut o = Vec::new();
        varint(300, &mut o);
        assert_eq!(o, [0xAC, 0x02]);
        let mut o = Vec::new();
        varint(-1, &mut o);
        assert_eq!(o, [0xFF, 0xFF, 0xFF, 0xFF, 0x0F]);
    }

    #[tokio::test]
    async fn pings_a_fake_server() {
        let l = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = l.local_addr().unwrap().port();
        tokio::spawn(async move {
            let (mut s, _) = l.accept().await.unwrap();
            let mut buf = [0u8; 256];
            let _ = s.read(&mut buf).await;
            let json = br#"{"version":{"name":"1.21.11"},"players":{"max":100,"online":7},"description":{"text":"Hi"}}"#;
            let mut body = Vec::new();
            varint(json.len() as i32, &mut body);
            body.extend(json);
            s.write_all(&packet(0, &body)).await.unwrap();
            let _ = s.read(&mut buf).await;
            s.write_all(&packet(1, &42i64.to_be_bytes())).await.unwrap();
        });
        let p = ping(&reqwest::Client::new(), &format!("127.0.0.1:{port}")).await.unwrap();
        assert_eq!((p.online, p.max, p.motd.as_str(), p.version.as_str()), (7, 100, "Hi", "1.21.11"));
    }
}
