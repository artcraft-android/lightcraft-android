//! The publisher's `mobile/manifest.json` (format 1; unknown fields are ignored).

use serde_json::{Value, json};

use crate::{Error, Result};

/// The newest manifest format this client reads.
pub const FORMAT: u64 = 1;

/// One file of the library: a photo, or a raw/JPEG partner (an alternate) of one.
#[derive(Debug, Clone, PartialEq)]
pub struct Item {
    /// The content hash.
    pub id: String,
    /// The original, relative to `originals/` and to the local mirror.
    pub path: String,
    pub sidecar: Option<String>,
    pub version: Option<String>,
    pub preview: Option<String>,
    pub preview_key: Option<String>,
    pub preview_bytes: Option<u64>,
    pub thumb: Option<String>,
    pub size: Option<u64>,
    /// A partner of another photo: shown as part of it, not on its own.
    pub alternate: bool,
}

fn text(v: &Value, k: &str) -> Option<String> {
    v.get(k).and_then(Value::as_str).map(str::trim).filter(|s| !s.is_empty()).map(str::to_string)
}

fn item(v: &Value, alternate: bool) -> Result<Item> {
    let bad = |what: &str| Error::Format(format!("manifest: a photo has no {what}"));
    Ok(Item {
        id: text(v, "id").ok_or_else(|| bad("id"))?,
        path: text(v, "path").ok_or_else(|| bad("path"))?,
        sidecar: text(v, "sidecar"),
        version: text(v, "sidecarVersion"),
        preview: text(v, "preview"),
        preview_key: text(v, "previewKey"),
        preview_bytes: v.get("previewBytes").and_then(Value::as_u64),
        thumb: text(v, "thumb"),
        size: v.get("size").and_then(Value::as_u64),
        alternate,
    })
}

/// A parsed manifest: its files (primaries and alternates, flattened) and what `library.addRemote`
/// is given for each.
pub struct Parsed {
    pub generated: String,
    pub items: Vec<Item>,
    /// Same order as `items`.
    pub photos: Vec<Value>,
}

pub fn parse(bytes: &[u8]) -> Result<Parsed> {
    let m: Value = serde_json::from_slice(bytes).map_err(|e| Error::Format(format!("manifest is not JSON: {e}")))?;
    let format = m.get("format").and_then(Value::as_u64).ok_or_else(|| Error::Format("manifest has no format".into()))?;
    if format > FORMAT {
        return Err(Error::Format(format!("this library is format {format}; this app reads format {FORMAT}: update the app")));
    }
    let list = m.get("photos").and_then(Value::as_array).ok_or_else(|| Error::Format("manifest has no photos".into()))?;
    let (mut items, mut photos) = (Vec::new(), Vec::new());
    for p in list {
        let main = item(p, false)?;
        let mut entry = json!({"id": main.id, "path": main.path});
        for k in ["size", "captured", "width", "height", "rating", "flag", "label", "camera", "lens"] {
            if let Some(v) = p.get(k).filter(|v| !v.is_null() || k == "label") {
                entry[k] = v.clone();
            }
        }
        items.push(main);
        photos.push(entry);
        for alt in p.get("alternates").and_then(Value::as_array).into_iter().flatten() {
            let a = item(alt, true)?;
            let mut e = json!({"id": a.id, "path": a.path});
            // (the partner is the same shot: its capture data is the primary's)
            for k in ["captured", "camera", "lens"] {
                if let Some(v) = p.get(k).filter(|v| !v.is_null()) {
                    e[k] = v.clone();
                }
            }
            for k in ["size", "width", "height"] {
                if let Some(v) = alt.get(k).filter(|v| !v.is_null()) {
                    e[k] = v.clone();
                }
            }
            items.push(a);
            photos.push(e);
        }
    }
    Ok(Parsed { generated: text(&m, "generated").unwrap_or_default(), items, photos })
}
