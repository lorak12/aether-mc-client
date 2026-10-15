//! Minimal NBT (Minecraft's binary tag format) reader and writer, enough for `level.dat` and `servers.dat`.
//! Strings are Java "modified UTF-8".
use crate::{Error, Result};

#[derive(Debug, Clone, PartialEq)]
pub enum Tag {
    End,
    Byte(i8),
    Short(i16),
    Int(i32),
    Long(i64),
    Float(f32),
    Double(f64),
    ByteArray(Vec<i8>),
    String(String),
    /// Element type id and elements.
    List(u8, Vec<Tag>),
    Compound(Vec<(String, Tag)>),
    IntArray(Vec<i32>),
    LongArray(Vec<i64>),
}

impl Tag {
    pub fn id(&self) -> u8 {
        match self {
            Tag::End => 0,
            Tag::Byte(_) => 1,
            Tag::Short(_) => 2,
            Tag::Int(_) => 3,
            Tag::Long(_) => 4,
            Tag::Float(_) => 5,
            Tag::Double(_) => 6,
            Tag::ByteArray(_) => 7,
            Tag::String(_) => 8,
            Tag::List(..) => 9,
            Tag::Compound(_) => 10,
            Tag::IntArray(_) => 11,
            Tag::LongArray(_) => 12,
        }
    }

    pub fn get(&self, key: &str) -> Option<&Tag> {
        match self {
            Tag::Compound(v) => v.iter().find(|(k, _)| k == key).map(|(_, t)| t),
            _ => None,
        }
    }

    pub fn str(&self) -> Option<&str> {
        match self {
            Tag::String(s) => Some(s),
            _ => None,
        }
    }

    /// Any integer tag widened to i64.
    pub fn int(&self) -> Option<i64> {
        match self {
            Tag::Byte(v) => Some(*v as i64),
            Tag::Short(v) => Some(*v as i64),
            Tag::Int(v) => Some(*v as i64),
            Tag::Long(v) => Some(*v),
            _ => None,
        }
    }

    pub fn list(&self) -> Option<&[Tag]> {
        match self {
            Tag::List(_, v) => Some(v),
            _ => None,
        }
    }
}

struct Reader<'a> {
    b: &'a [u8],
    pos: usize,
    depth: u32,
}

impl<'a> Reader<'a> {
    fn take(&mut self, n: usize) -> Result<&'a [u8]> {
        let end = self.pos.checked_add(n).filter(|e| *e <= self.b.len()).ok_or_else(|| Error::Other("truncated NBT".into()))?;
        let s = &self.b[self.pos..end];
        self.pos = end;
        Ok(s)
    }
    fn u8(&mut self) -> Result<u8> {
        Ok(self.take(1)?[0])
    }
    fn i16(&mut self) -> Result<i16> {
        Ok(i16::from_be_bytes(self.take(2)?.try_into().unwrap()))
    }
    fn i32(&mut self) -> Result<i32> {
        Ok(i32::from_be_bytes(self.take(4)?.try_into().unwrap()))
    }
    fn i64(&mut self) -> Result<i64> {
        Ok(i64::from_be_bytes(self.take(8)?.try_into().unwrap()))
    }
    fn len(&mut self) -> Result<usize> {
        let n = self.i32()?;
        if n < 0 || n as usize > self.b.len() {
            return Err(Error::Other("bad NBT length".into()));
        }
        Ok(n as usize)
    }
    fn string(&mut self) -> Result<String> {
        let n = self.i16()? as u16 as usize;
        Ok(decode_mutf8(self.take(n)?))
    }

    fn payload(&mut self, id: u8) -> Result<Tag> {
        Ok(match id {
            1 => Tag::Byte(self.u8()? as i8),
            2 => Tag::Short(self.i16()?),
            3 => Tag::Int(self.i32()?),
            4 => Tag::Long(self.i64()?),
            5 => Tag::Float(f32::from_bits(self.i32()? as u32)),
            6 => Tag::Double(f64::from_bits(self.i64()? as u64)),
            7 => {
                let n = self.len()?;
                Tag::ByteArray(self.take(n)?.iter().map(|b| *b as i8).collect())
            }
            8 => Tag::String(self.string()?),
            9 => {
                self.depth += 1;
                if self.depth > 512 {
                    return Err(Error::Other("NBT nested too deep".into()));
                }
                let el = self.u8()?;
                let n = self.len()?;
                let mut v = Vec::with_capacity(n.min(4096));
                for _ in 0..n {
                    v.push(self.payload(el)?);
                }
                self.depth -= 1;
                Tag::List(el, v)
            }
            10 => {
                self.depth += 1;
                if self.depth > 512 {
                    return Err(Error::Other("NBT nested too deep".into()));
                }
                let mut v = Vec::new();
                loop {
                    let t = self.u8()?;
                    if t == 0 {
                        break;
                    }
                    let name = self.string()?;
                    v.push((name, self.payload(t)?));
                }
                self.depth -= 1;
                Tag::Compound(v)
            }
            11 => {
                let n = self.len()?;
                Tag::IntArray((0..n).map(|_| self.i32()).collect::<Result<_>>()?)
            }
            12 => {
                let n = self.len()?;
                Tag::LongArray((0..n).map(|_| self.i64()).collect::<Result<_>>()?)
            }
            0 => Tag::End,
            other => return Err(Error::Other(format!("unknown NBT tag {other}"))),
        })
    }
}

/// Parse an uncompressed NBT document; returns the root's name and tag.
pub fn read(bytes: &[u8]) -> Result<(String, Tag)> {
    let mut r = Reader { b: bytes, pos: 0, depth: 0 };
    let id = r.u8()?;
    if id != 10 {
        return Err(Error::Other("NBT root is not a compound".into()));
    }
    let name = r.string()?;
    Ok((name, r.payload(id)?))
}

/// Parse NBT that may be gzip-compressed (level.dat is, servers.dat isn't).
pub fn read_any(bytes: &[u8]) -> Result<(String, Tag)> {
    if bytes.starts_with(&[0x1f, 0x8b]) {
        use std::io::Read;
        let mut out = Vec::new();
        flate2::read::GzDecoder::new(bytes).take(256 * 1024 * 1024).read_to_end(&mut out)?;
        read(&out)
    } else {
        read(bytes)
    }
}

fn write_string(out: &mut Vec<u8>, s: &str) {
    let b = encode_mutf8(s);
    let b = &b[..b.len().min(u16::MAX as usize)];
    out.extend((b.len() as u16).to_be_bytes());
    out.extend(b);
}

fn write_payload(out: &mut Vec<u8>, t: &Tag) {
    match t {
        Tag::End => {}
        Tag::Byte(v) => out.push(*v as u8),
        Tag::Short(v) => out.extend(v.to_be_bytes()),
        Tag::Int(v) => out.extend(v.to_be_bytes()),
        Tag::Long(v) => out.extend(v.to_be_bytes()),
        Tag::Float(v) => out.extend(v.to_bits().to_be_bytes()),
        Tag::Double(v) => out.extend(v.to_bits().to_be_bytes()),
        Tag::ByteArray(v) => {
            out.extend((v.len() as i32).to_be_bytes());
            out.extend(v.iter().map(|b| *b as u8));
        }
        Tag::String(s) => write_string(out, s),
        Tag::List(el, v) => {
            out.push(if v.is_empty() { *el } else { v[0].id() });
            out.extend((v.len() as i32).to_be_bytes());
            for x in v {
                write_payload(out, x);
            }
        }
        Tag::Compound(v) => {
            for (k, x) in v {
                out.push(x.id());
                write_string(out, k);
                write_payload(out, x);
            }
            out.push(0);
        }
        Tag::IntArray(v) => {
            out.extend((v.len() as i32).to_be_bytes());
            for x in v {
                out.extend(x.to_be_bytes());
            }
        }
        Tag::LongArray(v) => {
            out.extend((v.len() as i32).to_be_bytes());
            for x in v {
                out.extend(x.to_be_bytes());
            }
        }
    }
}

/// Serialize an uncompressed NBT document with a compound root.
pub fn write(name: &str, root: &Tag) -> Vec<u8> {
    let mut out = vec![10];
    write_string(&mut out, name);
    write_payload(&mut out, root);
    out
}

/// Java modified UTF-8 -> String (via UTF-16 so surrogate pairs and encoded NULs come out right).
fn decode_mutf8(b: &[u8]) -> String {
    let mut units: Vec<u16> = Vec::with_capacity(b.len());
    let mut i = 0;
    while i < b.len() {
        let c = b[i] as u16;
        if c & 0x80 == 0 {
            units.push(c);
            i += 1;
        } else if c & 0xE0 == 0xC0 && i + 1 < b.len() {
            units.push(((c & 0x1F) << 6) | (b[i + 1] as u16 & 0x3F));
            i += 2;
        } else if c & 0xF0 == 0xE0 && i + 2 < b.len() {
            units.push(((c & 0x0F) << 12) | ((b[i + 1] as u16 & 0x3F) << 6) | (b[i + 2] as u16 & 0x3F));
            i += 3;
        } else {
            units.push(0xFFFD);
            i += 1;
        }
    }
    String::from_utf16_lossy(&units)
}

fn encode_mutf8(s: &str) -> Vec<u8> {
    let mut out = Vec::with_capacity(s.len());
    for u in s.encode_utf16() {
        match u {
            0x0001..=0x007F => out.push(u as u8),
            0x0000 | 0x0080..=0x07FF => {
                out.push(0xC0 | (u >> 6) as u8);
                out.push(0x80 | (u & 0x3F) as u8);
            }
            _ => {
                out.push(0xE0 | (u >> 12) as u8);
                out.push(0x80 | ((u >> 6) & 0x3F) as u8);
                out.push(0x80 | (u & 0x3F) as u8);
            }
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trip() {
        let t = Tag::Compound(vec![
            ("servers".into(), Tag::List(10, vec![Tag::Compound(vec![("name".into(), Tag::String("Hÿpixel 🎮".into())), ("ip".into(), Tag::String("mc.hypixel.net".into()))])])),
            ("n".into(), Tag::Long(-5)),
            ("f".into(), Tag::Float(1.5)),
            ("ia".into(), Tag::IntArray(vec![1, 2])),
            ("empty".into(), Tag::List(0, vec![])),
            ("nul".into(), Tag::String("a\0b".into())),
        ]);
        let bytes = write("", &t);
        let (name, back) = read(&bytes).unwrap();
        assert_eq!(name, "");
        assert_eq!(back, t);
        assert_eq!(back.get("servers").unwrap().list().unwrap()[0].get("name").unwrap().str(), Some("Hÿpixel 🎮"));
        // NUL is two bytes in modified UTF-8, never a raw zero.
        assert!(!encode_mutf8("a\0b").contains(&0));
    }

    #[test]
    fn gzip_and_garbage() {
        use std::io::Write;
        let t = Tag::Compound(vec![("Data".into(), Tag::Compound(vec![("LevelName".into(), Tag::String("World".into()))]))]);
        let mut gz = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::default());
        gz.write_all(&write("", &t)).unwrap();
        let (_, back) = read_any(&gz.finish().unwrap()).unwrap();
        assert_eq!(back.get("Data").unwrap().get("LevelName").unwrap().str(), Some("World"));
        assert!(read(&[10, 0, 0, 9, 0, 0]).is_err());
        assert!(read(&[1, 2, 3]).is_err());
        assert!(read(&[]).is_err());
    }
}
