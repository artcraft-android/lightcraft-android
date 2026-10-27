//! Cloud libraries: photos whose originals live on a remote (`library.addRemote`).
//!
//! A phone shows a published library without holding any original. Each photo is an ordinary
//! record whose source path is where its original would be under a local mirror folder (the
//! *root*); the file is not there and is never downloaded. The root is remembered in the library's
//! preferences ([`crate::availability::RemoteRoot`]): its photos are offline (never "missing"),
//! render from their smart preview once one is in the smart previews folder, from the remote's
//! published thumbnail in the grid until then, and fail with "not downloaded" otherwise. Edits
//! write their XMP sidecar next to the (absent) original's path under the root.

use std::collections::{HashMap, HashSet};
use std::path::{Component, Path, PathBuf};

use lightcraft_catalog::{ColorLabel, Flag, MediaKind, Op, Photo, PhotoId, Source};
use serde_json::{Value, json};

use super::{CommandSpec, always, bad, cmd, str_param};
use crate::availability::RemoteRoot;
use crate::{Result, Session};

/// Ops per logged batch (keeps log lines a reasonable size).
const BATCH: usize = 500;

const RAW_EXTENSIONS: &[&str] = &["raw", "dng", "arw", "cr2", "cr3", "nef", "nrw", "raf", "orf", "rw2", "rwl", "pef"];

/// One photo of the remote's manifest, as `library.addRemote` is given it.
struct Entry {
    /// The content hash (the remote's id for the photo).
    id: String,
    /// Where the original would be: `<root>/<path>`.
    path: String,
    file_name: String,
    format: String,
    kind: MediaKind,
    size: Option<u64>,
    captured: Option<String>,
    width: Option<u32>,
    height: Option<u32>,
    rating: Option<u8>,
    flag: Option<Flag>,
    label: Option<Option<String>>,
    camera: Option<String>,
    lens: Option<String>,
}

/// `captured` as the catalog keeps it: local time, to the second, without a UTC offset.
fn local_time(t: &str) -> String {
    let t = t.trim();
    match t.get(..19) {
        Some(head) if head.as_bytes().get(10) == Some(&b'T') => head.to_string(),
        _ => t.to_string(),
    }
}

fn entry(root: &Path, v: &Value, i: usize) -> Result<Entry> {
    const C: &str = "library.addRemote";
    let at = |msg: &str| bad(C, format!("photos[{i}]: {msg}"));
    let text = |k: &str| v.get(k).and_then(Value::as_str).map(str::trim).filter(|t| !t.is_empty());
    let id = text("id").ok_or_else(|| at("missing `id`"))?;
    if !id.bytes().all(|b| b.is_ascii_alphanumeric()) {
        return Err(at("`id` is a content hash (letters and digits)"));
    }
    let rel = text("path").ok_or_else(|| at("missing `path`"))?;
    let mut path = root.to_path_buf();
    for c in Path::new(rel).components() {
        match c {
            Component::Normal(n) => path.push(n),
            Component::CurDir => {}
            _ => return Err(at("`path` is relative to the root, without `..`")),
        }
    }
    if path == root {
        return Err(at("`path` names no file"));
    }
    let name = path.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default();
    let ext = path.extension().map(|e| e.to_string_lossy().to_lowercase()).unwrap_or_default();
    let kind = match text("kind").map(str::to_lowercase).as_deref() {
        Some("raw") => MediaKind::Raw,
        Some("video") => MediaKind::Video,
        Some("image") => MediaKind::Image,
        Some(other) => return Err(at(&format!("unknown kind `{other}` (image|raw|video)"))),
        None if RAW_EXTENSIONS.contains(&ext.as_str()) => MediaKind::Raw,
        None => MediaKind::Image,
    };
    let num = |k: &str| v.get(k).and_then(Value::as_u64);
    let dim = |k: &str| num(k).map(|n| u32::try_from(n).unwrap_or(u32::MAX));
    let rating = match v.get("rating") {
        None | Some(Value::Null) => None,
        Some(r) => Some(r.as_u64().filter(|r| *r <= 5).ok_or_else(|| at("`rating` is 0 to 5"))? as u8),
    };
    let flag = match text("flag") {
        None => None,
        Some(f) => Some(Flag::parse(f).ok_or_else(|| at("`flag` is none|pick|reject"))?),
    };
    let label = match v.get("label") {
        None => None,
        Some(Value::Null) => Some(None),
        Some(l) => Some(l.as_str().map(|l| l.trim().to_string()).filter(|l| !l.is_empty())),
    };
    Ok(Entry {
        id: id.to_string(),
        file_name: text("fileName").map(str::to_string).unwrap_or(name),
        format: text("format").map(str::to_uppercase).unwrap_or_else(|| ext.to_uppercase()),
        path: path.to_string_lossy().to_string(),
        kind,
        size: num("size"),
        captured: text("captured").map(local_time),
        width: dim("width"),
        height: dim("height"),
        rating,
        flag,
        label,
        camera: v.get("camera").and_then(Value::as_str).map(str::to_string),
        lens: v.get("lens").and_then(Value::as_str).map(str::to_string),
    })
}

/// The label for `name` (a colour or a custom name in the catalog); an unknown one clears it.
fn label_of(s: &Session, name: &Option<String>) -> Option<ColorLabel> {
    name.as_deref().and_then(|n| s.catalog.label_from_name(n))
}

/// The photo among `ids` (records of one file) that is not a virtual copy.
fn master(cat: &lightcraft_catalog::Catalog, ids: &[PhotoId]) -> Option<PhotoId> {
    ids.iter().copied().find(|id| cat.photo(*id).is_some_and(|p| p.copy_of.is_none()))
}

/// The stored hash of a photo, without the `:dupN` suffix duplicates get.
fn hash_of(p: &Photo) -> Option<&str> {
    p.content_hash.as_deref().map(|h| h.split(':').next().unwrap_or(h)).filter(|h| !h.is_empty())
}

fn new_photo(s: &mut Session, e: &Entry, now: &str) -> Photo {
    let id = s.catalog.alloc_photo_id();
    let mut p = Photo::new(id, Source::File { path: e.path.clone() }, &e.file_name, &e.format, e.width.unwrap_or(1).max(1), e.height.unwrap_or(1).max(1), now);
    p.kind = e.kind;
    p.file_size = e.size.unwrap_or(0);
    p.captured = e.captured.clone();
    p.content_hash = Some(e.id.clone());
    p.rating = e.rating.unwrap_or(0);
    p.flag = e.flag.unwrap_or_default();
    p.label = e.label.as_ref().and_then(|l| label_of(s, l));
    p.meta.camera = e.camera.clone().unwrap_or_default();
    p.meta.lens = e.lens.clone().unwrap_or_default();
    p.develop = std::sync::Arc::new(p.import_defaults());
    p
}

/// The ops that bring `p` up to what `e` says (nothing for a photo that is already so). A field `e`
/// leaves out is left as it is.
fn update_ops(s: &Session, p: &Photo, e: &Entry) -> Vec<Op> {
    let mut ops = Vec::new();
    let id = p.id;
    let moved = !matches!(&p.source, Source::File { path } if *path == e.path) || p.file_name != e.file_name;
    if moved {
        ops.push(Op::Relink { id, file_name: e.file_name.clone(), source: Source::File { path: e.path.clone() }, format: (p.format != e.format).then(|| e.format.clone()) });
    }
    let (width, height, size) = (e.width.unwrap_or(p.width).max(1), e.height.unwrap_or(p.height).max(1), e.size.unwrap_or(p.file_size));
    if (width, height, size) != (p.width, p.height, p.file_size) || hash_of(p) != Some(e.id.as_str()) {
        ops.push(Op::SetContent { id, width, height, file_size: size, content_hash: Some(e.id.clone()), preview_only: p.preview_only.clone(), preview_checked: p.preview_checked.clone() });
    }
    if e.rating.is_some_and(|r| r != p.rating) {
        ops.push(Op::SetRating { id, rating: e.rating.unwrap_or_default() });
    }
    if e.flag.is_some_and(|f| f != p.flag) {
        ops.push(Op::SetFlag { id, flag: e.flag.unwrap_or_default() });
    }
    if let Some(l) = e.label.as_ref().map(|l| label_of(s, l)).filter(|l| *l != p.label) {
        ops.push(Op::SetLabel { id, label: l });
    }
    if e.captured.is_some() && e.captured != p.captured {
        ops.push(Op::SetCaptured { id, captured: e.captured.clone() });
    }
    let mut meta = p.meta.clone();
    meta.camera = e.camera.clone().unwrap_or(meta.camera);
    meta.lens = e.lens.clone().unwrap_or(meta.lens);
    if meta != p.meta {
        ops.push(Op::SetMeta { id, meta: Box::new(meta) });
    }
    ops
}

/// Apply `ops` as journaled batches that are not undo steps: nothing on the phone takes a sync back.
fn apply(s: &mut Session, ops: Vec<Op>) -> Result<()> {
    let mut ops = ops.into_iter().peekable();
    while ops.peek().is_some() {
        let batch: Vec<Op> = ops.by_ref().take(BATCH).collect();
        s.apply_system(Op::Batch { ops: batch })?;
    }
    Ok(())
}

fn add_remote(s: &mut Session, p: &Value) -> Result<Value> {
    const C: &str = "library.addRemote";
    let root = str_param(p, "root").map(Path::new).filter(|r| r.is_absolute()).ok_or_else(|| bad(C, "`root` is an absolute folder"))?;
    // (normalised: no trailing separator, no `.`)
    let root: PathBuf = root.components().collect();
    let thumbs = match p.get("thumbs") {
        None | Some(Value::Null) => None,
        Some(t) => Some(t.as_str().filter(|t| Path::new(t).is_absolute()).ok_or_else(|| bad(C, "`thumbs` is an absolute folder"))?.to_string()),
    };
    let prune = p.get("prune").and_then(Value::as_bool).unwrap_or(false);
    let list = p.get("photos").and_then(Value::as_array).ok_or_else(|| bad(C, "missing `photos`"))?;
    let mut entries = Vec::with_capacity(list.len());
    let mut seen_paths = HashSet::new();
    for (i, v) in list.iter().enumerate() {
        let e = entry(&root, v, i)?;
        if !seen_paths.insert(e.path.clone()) {
            return Err(bad(C, format!("photos[{i}]: `path` {} is listed twice", e.path)));
        }
        entries.push(e);
    }

    // remember the root (and where its thumbnails are) with the library
    let root_str = root.to_string_lossy().to_string();
    let mut remotes = s.media.availability.remotes();
    match remotes.iter_mut().find(|r| r.root == root_str) {
        Some(r) => {
            if thumbs.is_some() {
                r.thumbs = thumbs;
            }
        }
        None => remotes.push(RemoteRoot { root: root_str, thumbs }),
    }
    if remotes != s.media.availability.remotes() {
        s.media.availability.set_remotes(remotes);
        s.save_prefs()?;
    }

    // what the library already holds under the root
    let mut by_path: HashMap<String, Vec<PhotoId>> = HashMap::new();
    for ph in s.catalog.photos() {
        if let Source::File { path } = &ph.source
            && Path::new(path).starts_with(&root)
        {
            by_path.entry(path.clone()).or_default().push(ph.id);
        }
    }
    let listed: HashSet<&str> = entries.iter().map(|e| e.path.as_str()).collect();
    // records at no listed path, by content: a file that moved on the remote keeps its record
    let mut moved: HashMap<String, Vec<String>> = HashMap::new();
    for (path, ids) in &by_path {
        if !listed.contains(path.as_str())
            && let Some(h) = master(&s.catalog, ids).and_then(|id| s.catalog.photo(id)).and_then(|p| hash_of(p).map(str::to_string))
        {
            moved.entry(h).or_default().push(path.clone());
        }
    }
    for paths in moved.values_mut() {
        paths.sort();
        paths.reverse();
    }

    let now = (s.clock)();
    let (mut ops, mut gone) = (Vec::new(), Vec::new());
    let (mut added, mut updated) = (0, 0);
    let mut ids = serde_json::Map::new();
    let mut kept: HashSet<String> = HashSet::new();
    let mut touched: Vec<PhotoId> = Vec::new();
    for e in &entries {
        // the record at this path, else (a file that moved) one with this content at a path no longer listed
        let from = match by_path.get(&e.path) {
            Some(v) => Some((e.path.clone(), v.clone())),
            None => moved.get_mut(&e.id).and_then(Vec::pop).map(|old| (old.clone(), by_path.get(&old).cloned().unwrap_or_default())),
        };
        kept.insert(from.as_ref().map_or(e.path.clone(), |(old, _)| old.clone()));
        let Some((_, same)) = from else {
            let ph = new_photo(s, e, &now);
            ids.insert(e.id.clone(), json!(ph.id.0));
            ops.push(Op::AddPhoto { photo: Box::new(ph) });
            added += 1;
            continue;
        };
        // (virtual copies of the file follow it)
        if let Some(m) = master(&s.catalog, &same) {
            ids.insert(e.id.clone(), json!(m.0));
        }
        let mut changed = false;
        for id in same {
            let Some(ph) = s.catalog.photo(id) else { continue };
            let o = if ph.copy_of.is_none() {
                update_ops(s, ph, e)
            } else if !matches!(&ph.source, Source::File { path } if *path == e.path) {
                vec![Op::Relink { id, file_name: e.file_name.clone(), source: Source::File { path: e.path.clone() }, format: None }]
            } else {
                Vec::new()
            };
            if !o.is_empty() {
                changed = true;
                touched.push(id);
                ops.extend(o);
            }
        }
        updated += usize::from(changed);
    }
    if prune {
        for (path, ph_ids) in &by_path {
            if !kept.contains(path) {
                gone.extend(ph_ids.iter().copied());
            }
        }
        gone.sort();
    }
    let removed = gone.len();

    s.skip_auto_write = true;
    apply(s, ops)?;
    for chunk in gone.chunks(BATCH) {
        let op = s.catalog.delete_photos_permanently_ops(chunk);
        s.apply_system(op)?;
    }
    touched.iter().for_each(|id| s.media.forget(*id));
    if !gone.is_empty() {
        forget_photos(s, &gone);
        s.compact_soon();
    }
    Ok(json!({"added": added, "updated": updated, "removed": removed, "ids": ids}))
}

/// Drop what the session still holds of photos that left the catalog.
fn forget_photos(s: &mut Session, gone: &[PhotoId]) {
    let gone: HashSet<PhotoId> = gone.iter().copied().collect();
    let refers = |op: &Op| {
        let mut hit = false;
        op.photo_ids(&mut |id| hit |= gone.contains(&id));
        hit
    };
    let (u, r) = (s.undo.len(), s.redo.len());
    s.undo.retain(|e| !refers(&e.op));
    s.redo.retain(|e| !refers(&e.op));
    s.undo_dropped += (u - s.undo.len()) + (r - s.redo.len());
    s.selection.ids.retain(|id| !gone.contains(id));
    if s.selection.active.is_some_and(|id| gone.contains(&id)) {
        s.selection.active = s.selection.ids.first().copied();
    }
    gone.iter().for_each(|id| s.media.forget(*id));
}

pub fn specs() -> Vec<CommandSpec> {
    vec![
        // (not journaled in the session's command history: a manifest is large and sent again at every sync)
        cmd!(
            query "library.addRemote",
            "Add Remote Photos",
            [],
            None,
            "{root: absolute folder (the local mirror of the remote's originals; remembered with the library), thumbs?: absolute folder of the published thumbnails (<id>.jpg: shown in the grid for photos with neither original nor smart preview), prune?: bool (remove the photos under `root` that are not listed; default false), photos: [{id: content hash, path: relative POSIX path of the original, fileName?, size?, captured?: ISO 8601, width?, height?, rating?: 0-5, flag?: none|pick|reject, label?: colour or label name | null, camera?, lens?, format?, kind?: image|raw|video}]} — adds or updates one photo record per entry (source `<root>/<path>`, content hash = id; the file itself is not read and need not exist: the original is on the remote, so the photo is offline, never missing, and renders from its smart preview). A record at the same path (else, at an unlisted path, with the same content hash: the file moved) is updated, not duplicated; fields left out are left as they are. Journaled, but not an undo step; sidecars are not written. Raw+JPEG partners are listed as photos of their own → {added, updated, removed, ids: {<id>: photo id}}",
            always,
            add_remote
        ),
    ]
}
