//! [`CloudLibrary`]: one cloud library on this device, kept in a state folder:
//!
//! ```text
//! <dir>/root/            local mirror of originals/: where the engine's sidecars (and downloaded originals) live
//! <dir>/thumbs/<id>.jpg  published thumbnails
//! <dir>/manifest.json    the last manifest downloaded (the library works offline from it)
//! <dir>/state.json       what has been applied / sent (see [`State`])
//! ```
//!
//! Smart previews are not in the state folder: they go into the engine's smart previews folder, where
//! the engine finds them.

use std::collections::{BTreeMap, HashMap, HashSet};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::{Duration, SystemTime};

use serde::{Deserialize, Serialize};
use serde_json::{Value, json};

use crate::manifest::{self, Item};
use crate::util::{self, iso, millis, sha256_hex, sidecar_version, temp_beside, utc_stamp, write_atomic};
use crate::{Engine, Error, Remote, Result};

/// Thumbnails and sidecars downloaded at once.
const JOBS: usize = 6;
/// The inbox rejects bigger sidecars.
const MAX_SIDECAR: u64 = 2 * 1024 * 1024;
/// Default size of the smart preview cache.
pub const DEFAULT_CACHE_CAP: u64 = 2 * 1024 * 1024 * 1024;

/// What is remembered per file between runs (`state.json`).
#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(default, rename_all = "camelCase")]
pub struct PhotoState {
    /// The engine's id for the photo.
    pub engine_id: u64,
    /// The manifest's `sidecarVersion` last processed (applied, or found equal to the local sidecar).
    pub manifest: Option<String>,
    /// The version a new edit starts from (the inbox file's `base`): `manifest`, or, after this device
    /// sent an edit, the version that edit will have once the PC applies it.
    pub base: Option<String>,
    /// SHA-256 of the local sidecar bytes the remote has (downloaded, or sent): a local sidecar with other
    /// bytes is an edit not sent yet.
    pub synced: Option<String>,
    /// The `sidecarVersion` the thumbnail on disk was published with (`none` = no sidecar).
    pub thumb: Option<String>,
    /// The stamp of the last inbox file for this photo (a new one is always later).
    pub stamp: Option<String>,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(default, rename_all = "camelCase")]
pub struct PreviewState {
    pub key: String,
    pub bytes: u64,
    /// Last opened, ms since 1970 (the least recent goes first when the cache is over its cap).
    pub used: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(default, rename_all = "camelCase")]
pub struct State {
    pub format: u32,
    /// By content hash (the remote's id).
    pub photos: BTreeMap<String, PhotoState>,
    /// Smart previews this library downloaded, by content hash.
    pub previews: BTreeMap<String, PreviewState>,
    pub cache_cap: u64,
    pub last_refresh: Option<String>,
    pub last_error: Option<String>,
    /// The `generated` stamp of the manifest last applied.
    pub generated: Option<String>,
}

impl Default for State {
    fn default() -> Self {
        State { format: 1, photos: BTreeMap::new(), previews: BTreeMap::new(), cache_cap: DEFAULT_CACHE_CAP, last_refresh: None, last_error: None, generated: None }
    }
}

struct Inner {
    state: State,
    items: Vec<Item>,
    /// A state file that could not be read was set aside: everything is treated as not applied.
    recovered: bool,
    pending_hint: usize,
}

/// What [`CloudLibrary::refresh`] did.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct RefreshSummary {
    /// Files in the library, partners included.
    pub total: usize,
    pub added: usize,
    pub updated: usize,
    pub removed: usize,
    /// Engine ids of the partners of raw+JPEG pairs: the UI shows the pair as one photo.
    pub hidden: Vec<u64>,
    /// Engine ids whose thumbnail was downloaded (the UI re-reads them).
    pub thumbs: Vec<u64>,
    /// Engine ids whose sidecar was taken from the PC.
    pub applied: Vec<u64>,
    /// Engine ids with a PC change not applied because of an edit here not sent yet.
    pub blocked: Vec<u64>,
    /// Things that failed (a thumbnail, a sidecar); the rest went through and the next refresh retries.
    pub failures: Vec<String>,
}

/// What [`CloudLibrary::push_edits`] did.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct PushSummary {
    pub sent: usize,
    pub failed: usize,
    pub errors: Vec<String>,
}

/// What [`CloudLibrary::status`] reports.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct Status {
    pub remote: String,
    pub device: String,
    /// Photos shown (partners of pairs not counted).
    pub photos: usize,
    pub hidden: usize,
    /// Edits not sent yet, as of the last scan.
    pub pending: usize,
    pub cache_bytes: u64,
    pub cache_cap: u64,
    pub last_refresh: Option<String>,
    pub last_error: Option<String>,
}

pub struct CloudLibrary {
    dir: PathBuf,
    root: PathBuf,
    thumbs: PathBuf,
    remote: Arc<dyn Remote>,
    label: String,
    device: String,
    inner: Mutex<Inner>,
    /// One refresh / push at a time.
    sync_lock: Mutex<()>,
    clock: Box<dyn Fn() -> SystemTime + Send + Sync>,
    /// path -> (modified, size, sha256) of sidecars already hashed.
    memo: Mutex<HashMap<PathBuf, (SystemTime, u64, String)>>,
}

/// `[A-Za-z0-9_-][A-Za-z0-9._-]{0,63}`
pub fn valid_device(name: &str) -> bool {
    let b = name.as_bytes();
    !b.is_empty() && b.len() <= 64 && (b[0].is_ascii_alphanumeric() || b[0] == b'_' || b[0] == b'-') && b.iter().all(|c| c.is_ascii_alphanumeric() || matches!(c, b'_' | b'-' | b'.'))
}

fn lock<T>(m: &Mutex<T>) -> MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|e| e.into_inner())
}

fn engine_err(e: String) -> Error {
    Error::Engine(e)
}

impl CloudLibrary {
    /// Open (or start) the library in state folder `dir`. `label` is shown in the status (`remote:`).
    /// Reads the cached manifest and the state; a state that does not parse is set aside and starts over.
    pub fn open(dir: &Path, remote: Arc<dyn Remote>, label: &str, device: &str) -> Result<Self> {
        if !valid_device(device) {
            return Err(Error::Invalid(format!("device name `{device}`: letters, digits, `_`, `-` and `.` (up to 64, not starting with a dot)")));
        }
        let dir = std::path::absolute(dir).map_err(|e| Error::io(dir.display(), e))?;
        let (root, thumbs) = (dir.join("root"), dir.join("thumbs"));
        for d in [&root, &thumbs] {
            std::fs::create_dir_all(d).map_err(|e| Error::io(d.display(), e))?;
        }
        let (state, recovered) = match std::fs::read(dir.join("state.json")) {
            Ok(b) => match serde_json::from_slice::<State>(&b) {
                Ok(s) => (s, false),
                Err(e) => {
                    log::warn!("cloud state unreadable ({e}): starting over");
                    let _ = std::fs::rename(dir.join("state.json"), dir.join("state.json.corrupt"));
                    (State::default(), true)
                }
            },
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => (State::default(), false),
            Err(e) => return Err(Error::io("state.json", e)),
        };
        let items = std::fs::read(dir.join("manifest.json")).ok().and_then(|b| manifest::parse(&b).ok()).map(|p| p.items).unwrap_or_default();
        Ok(CloudLibrary {
            dir,
            root,
            thumbs,
            remote,
            label: label.to_string(),
            device: device.to_string(),
            inner: Mutex::new(Inner { state, items, recovered, pending_hint: 0 }),
            sync_lock: Mutex::new(()),
            clock: Box::new(SystemTime::now),
            memo: Mutex::default(),
        })
    }

    /// Replace the clock (tests).
    pub fn set_clock(&mut self, clock: impl Fn() -> SystemTime + Send + Sync + 'static) {
        self.clock = Box::new(clock);
    }

    /// The local mirror: what `library.addRemote` is given as `root`.
    pub fn root(&self) -> &Path {
        &self.root
    }

    pub fn thumbs_dir(&self) -> &Path {
        &self.thumbs
    }

    pub fn device(&self) -> &str {
        &self.device
    }

    /// The state as it is now (a copy).
    pub fn state(&self) -> State {
        lock(&self.inner).state.clone()
    }

    /// Whether the state file had to be set aside when this library was opened.
    pub fn state_was_recovered(&self) -> bool {
        lock(&self.inner).recovered
    }

    fn now(&self) -> SystemTime {
        (self.clock)()
    }

    fn save(&self, inner: &Inner) -> Result<()> {
        let bytes = serde_json::to_vec(&inner.state).map_err(|e| Error::Io(format!("state: {e}")))?;
        write_atomic(&self.dir.join("state.json"), &bytes)
    }

    /// Download remote `path` to `dest` atomically.
    fn fetch(&self, path: &str, dest: &Path) -> Result<()> {
        if let Some(dir) = dest.parent() {
            std::fs::create_dir_all(dir).map_err(|e| Error::io(dir.display(), e))?;
        }
        let tmp = temp_beside(dest);
        let r = self.remote.get(path, &tmp).and_then(|()| std::fs::rename(&tmp, dest).map_err(|e| Error::io(dest.display(), e)));
        if r.is_err() {
            let _ = std::fs::remove_file(&tmp);
        }
        r
    }

    fn cloud_id_of(&self, engine_id: u64) -> Option<(String, Item)> {
        let g = lock(&self.inner);
        let (id, _) = g.state.photos.iter().find(|(_, p)| p.engine_id == engine_id)?;
        let item = g.items.iter().find(|i| i.id == *id)?.clone();
        Some((id.clone(), item))
    }

    /// Engine ids of the partners of raw+JPEG pairs (from the last refresh).
    pub fn hidden_ids(&self) -> Vec<u64> {
        let g = lock(&self.inner);
        let mut v: Vec<u64> = g.items.iter().filter(|i| i.alternate).filter_map(|i| g.state.photos.get(&i.id)).map(|p| p.engine_id).collect();
        v.sort_unstable();
        v
    }

    pub fn status(&self) -> Status {
        let g = lock(&self.inner);
        let hidden = g.items.iter().filter(|i| i.alternate).count();
        Status {
            remote: self.label.clone(),
            device: self.device.clone(),
            photos: g.items.len() - hidden,
            hidden,
            pending: g.pending_hint,
            cache_bytes: g.state.previews.values().map(|p| p.bytes).sum(),
            cache_cap: g.state.cache_cap,
            last_refresh: g.state.last_refresh.clone(),
            last_error: g.state.last_error.clone(),
        }
    }

    /// Note an error in the status (the app's failed calls show there).
    pub fn note_error(&self, e: &Error) {
        let mut g = lock(&self.inner);
        g.state.last_error = Some(e.to_string());
        let _ = self.save(&g);
    }

    // ---------------------------------------------------------------------------------------
    // refresh

    /// Bring the engine's library up to date with the remote: download the manifest, add / update / remove
    /// the photos, fetch new thumbnails, take over sidecars the PC changed (unless an edit here is not
    /// sent yet). Fails as a whole only when the manifest cannot be fetched or the engine refuses the
    /// photos; a thumbnail or sidecar that fails is listed in the summary and retried next time.
    pub fn refresh(&self, engine: &mut dyn Engine) -> Result<RefreshSummary> {
        let _sync = lock(&self.sync_lock);
        let r = self.refresh_inner(engine);
        let mut g = lock(&self.inner);
        match &r {
            Ok(_) => {
                g.state.last_error = None;
                g.state.last_refresh = Some(iso(self.now()));
            }
            Err(e) => g.state.last_error = Some(e.to_string()),
        }
        if let Err(e) = self.save(&g) {
            log::warn!("cloud state not saved: {e}");
        }
        r
    }

    fn refresh_inner(&self, engine: &mut dyn Engine) -> Result<RefreshSummary> {
        // 1. the manifest, to a temporary file first
        let part = self.dir.join("manifest.json.part");
        self.remote.get("mobile/manifest.json", &part)?;
        let bytes = std::fs::read(&part).map_err(|e| Error::io("manifest", e))?;
        let parsed = match manifest::parse(&bytes) {
            Ok(p) => p,
            Err(e) => {
                let _ = std::fs::remove_file(&part);
                return Err(e);
            }
        };
        std::fs::rename(&part, self.dir.join("manifest.json")).map_err(|e| Error::io("manifest", e))?;

        // 2. the photos. Rating, flag and label are the sidecar's to say once there is one (or the engine
        // has the photo): the manifest's copy is the PC's, so it would undo an edit made here and not sent yet.
        let mut photos = parsed.photos;
        {
            let g = lock(&self.inner);
            for (p, it) in photos.iter_mut().zip(&parsed.items) {
                if (it.version.is_some() || g.state.photos.contains_key(&it.id))
                    && let Some(o) = p.as_object_mut()
                {
                    for k in ["rating", "flag", "label"] {
                        o.remove(k);
                    }
                }
            }
        }
        let r = engine
            .call("library.addRemote", json!({"root": self.root.to_string_lossy(), "thumbs": self.thumbs.to_string_lossy(), "prune": true, "photos": photos}))
            .map_err(engine_err)?;
        let count = |k: &str| r.get(k).and_then(Value::as_u64).unwrap_or(0) as usize;
        let mut summary = RefreshSummary { total: parsed.items.len(), added: count("added"), updated: count("updated"), removed: count("removed"), ..Default::default() };
        let ids: HashMap<String, u64> = r.get("ids").and_then(Value::as_object).map(|o| o.iter().filter_map(|(k, v)| Some((k.clone(), v.as_u64()?))).collect()).unwrap_or_default();
        let items = parsed.items;
        {
            let mut g = lock(&self.inner);
            let listed: HashSet<&str> = items.iter().map(|i| i.id.as_str()).collect();
            let gone: Vec<String> = g.state.photos.keys().filter(|k| !listed.contains(k.as_str())).cloned().collect();
            for id in &gone {
                g.state.photos.remove(id);
                if let Some(p) = g.state.previews.remove(id) {
                    self.remove_preview_files(engine, &p.key);
                }
                let _ = std::fs::remove_file(self.thumbs.join(format!("{id}.jpg")));
            }
            for it in &items {
                if let Some(e) = ids.get(&it.id) {
                    g.state.photos.entry(it.id.clone()).or_default().engine_id = *e;
                }
            }
            g.state.generated = Some(parsed.generated);
            g.items = items.clone();
            g.recovered = false;
            self.save(&g)?;
        }
        let engine_of = |id: &str| ids.get(id).copied();
        summary.hidden = self.hidden_ids();

        // 3. thumbnails
        self.refresh_thumbs(&items, &mut summary, &engine_of);
        // 4. sidecars
        self.refresh_sidecars(engine, &items, &mut summary, &engine_of);
        let pending = self.scan_pending(engine).map(|p| p.len()).unwrap_or(0);
        lock(&self.inner).pending_hint = pending;
        Ok(summary)
    }

    fn refresh_thumbs(&self, items: &[Item], summary: &mut RefreshSummary, engine_of: &dyn Fn(&str) -> Option<u64>) {
        let remote_sizes: Option<HashMap<String, u64>> = match self.remote.list("mobile/thumbs", false) {
            Ok(v) => Some(v.into_iter().filter(|e| !e.is_dir).map(|e| (e.path, e.size)).collect()),
            Err(e) => {
                summary.failures.push(format!("thumbnail list: {e}"));
                None
            }
        };
        let wanted: Vec<(&Item, String)> = {
            let g = lock(&self.inner);
            items
                .iter()
                .filter_map(|it| {
                    let remote_path = format!("mobile/{}", it.thumb.as_deref()?);
                    let version = it.version.clone().unwrap_or_else(|| "none".into());
                    let local = self.thumbs.join(format!("{}.jpg", it.id));
                    let have = std::fs::metadata(&local).ok().map(|m| m.len());
                    let remote_size = remote_sizes.as_ref().and_then(|m| m.get(it.thumb.as_deref()?.rsplit('/').next()?)).copied();
                    let recorded = g.state.photos.get(&it.id).and_then(|p| p.thumb.clone());
                    let current = match (have, recorded.as_deref()) {
                        (None, _) => false,
                        (Some(_), Some(v)) if v != version => false,
                        (Some(h), _) => remote_size.is_none_or(|r| r == h),
                    };
                    (!current).then_some((it, remote_path))
                })
                .collect()
        };
        let results = util::parallel(&wanted, JOBS, |(it, remote_path)| self.fetch(remote_path, &self.thumbs.join(format!("{}.jpg", it.id))).map_err(|e| format!("thumbnail {}: {e}", it.id)));
        let mut g = lock(&self.inner);
        for ((it, _), r) in wanted.iter().zip(results) {
            match r {
                Ok(()) => {
                    g.state.photos.entry(it.id.clone()).or_default().thumb = Some(it.version.clone().unwrap_or_else(|| "none".into()));
                    summary.thumbs.extend(engine_of(&it.id));
                }
                Err(e) => summary.failures.push(e),
            }
        }
        // thumbnails of nothing listed
        if let Ok(rd) = std::fs::read_dir(&self.thumbs) {
            let listed: HashSet<String> = items.iter().map(|i| format!("{}.jpg", i.id)).collect();
            for e in rd.flatten() {
                if !listed.contains(e.file_name().to_string_lossy().as_ref()) {
                    let _ = std::fs::remove_file(e.path());
                }
            }
        }
        if let Err(e) = self.save(&g) {
            summary.failures.push(e.to_string());
        }
    }

    /// (path, exists) of the sidecar of each engine id.
    fn sidecar_paths(&self, engine: &mut dyn Engine, ids: &[u64]) -> Result<HashMap<u64, (PathBuf, bool)>> {
        if ids.is_empty() {
            return Ok(HashMap::new());
        }
        let r = engine.call("photo.sidecarPaths", json!({"ids": ids})).map_err(engine_err)?;
        Ok(r.get("paths")
            .and_then(Value::as_array)
            .into_iter()
            .flatten()
            .filter_map(|e| Some((e.get("id")?.as_u64()?, (PathBuf::from(e.get("path")?.as_str()?), e.get("exists")?.as_bool()?))))
            .collect())
    }

    /// sha256 of the file's bytes (None when it is not there), remembered by (modified, size).
    fn hash_file(&self, path: &Path) -> Option<String> {
        let md = std::fs::metadata(path).ok()?;
        let key = (md.modified().ok()?, md.len());
        if let Some((m, s, h)) = lock(&self.memo).get(path)
            && (*m, *s) == key
        {
            return Some(h.clone());
        }
        let h = sha256_hex(&std::fs::read(path).ok()?);
        lock(&self.memo).insert(path.to_path_buf(), (key.0, key.1, h.clone()));
        Some(h)
    }

    /// An edit here the remote does not have: a local sidecar whose bytes are neither the ones last
    /// synced nor the PC's current version.
    fn unsent(ps: Option<&PhotoState>, version: Option<&str>, hash: &str) -> bool {
        ps.and_then(|p| p.synced.as_deref()) != Some(hash) && version != Some(&hash[..16])
    }

    /// Never fails as a whole: what goes wrong is listed in `summary.failures`, and a photo whose sidecar
    /// was not applied keeps its old `manifest` version, so the next refresh tries it again.
    fn refresh_sidecars(&self, engine: &mut dyn Engine, items: &[Item], summary: &mut RefreshSummary, engine_of: &dyn Fn(&str) -> Option<u64>) {
        // files whose published sidecar changed since we last looked
        let changed: Vec<&Item> = {
            let g = lock(&self.inner);
            items.iter().filter(|it| it.version.is_some() && it.sidecar.is_some() && g.state.photos.get(&it.id).and_then(|p| p.manifest.as_deref()) != it.version.as_deref()).collect()
        };
        // (a PC that dropped a sidecar: nothing to take, but remember it)
        {
            let mut g = lock(&self.inner);
            for it in items.iter().filter(|i| i.version.is_none()) {
                if let Some(p) = g.state.photos.get_mut(&it.id) {
                    p.manifest = None;
                }
            }
        }
        let engine_ids: Vec<u64> = changed.iter().filter_map(|it| engine_of(&it.id)).collect();
        let paths = match self.sidecar_paths(engine, &engine_ids) {
            Ok(p) => p,
            Err(e) => {
                summary.failures.push(format!("sidecar paths: {e}"));
                return;
            }
        };
        enum Todo<'a> {
            Download(&'a Item, PathBuf),
            Equal(&'a Item, String),
        }
        let mut todo = Vec::new();
        for it in &changed {
            let Some(eid) = engine_of(&it.id) else { continue };
            let Some((path, exists)) = paths.get(&eid) else { continue };
            let local = exists.then(|| self.hash_file(path)).flatten();
            let ps = lock(&self.inner).state.photos.get(&it.id).cloned();
            match local {
                Some(h) if Self::unsent(ps.as_ref(), it.version.as_deref(), &h) => summary.blocked.push(eid),
                Some(h) if it.version.as_deref() == Some(&h[..16]) => todo.push(Todo::Equal(it, h)),
                _ => todo.push(Todo::Download(it, path.clone())),
            }
        }
        let downloads: Vec<(&Item, &PathBuf)> = todo.iter().filter_map(|t| if let Todo::Download(i, p) = t { Some((*i, p)) } else { None }).collect();
        let results = util::parallel(&downloads, JOBS, |(it, path)| {
            let remote_path = format!("originals/{}", it.sidecar.as_deref().unwrap_or_default());
            self.fetch(&remote_path, path).map_err(|e| format!("sidecar {}: {e}", it.id))
        });
        let mut done: Vec<(&Item, String)> = todo.iter().filter_map(|t| if let Todo::Equal(i, h) = t { Some((*i, h.clone())) } else { None }).collect();
        let mut fresh = Vec::new();
        // (a sidecar downloaded earlier whose read failed is on disk but not what was last synced: still news)
        {
            let g = lock(&self.inner);
            for (it, h) in &done {
                if g.state.photos.get(&it.id).and_then(|p| p.synced.as_deref()).is_some_and(|s| s != h) {
                    fresh.push(it.id.clone());
                }
            }
        }
        for ((it, path), r) in downloads.iter().zip(results) {
            match r {
                Ok(()) => match std::fs::read(path) {
                    Ok(b) => {
                        done.push((it, sha256_hex(&b)));
                        fresh.push(it.id.clone());
                    }
                    Err(e) => summary.failures.push(format!("sidecar {}: {e}", it.id)),
                },
                Err(e) => summary.failures.push(e),
            }
        }
        if done.is_empty() {
            return;
        }
        // all at once; when the engine refuses the batch, one by one, so one bad sidecar blocks no other
        let read_ids: Vec<u64> = done.iter().filter_map(|(it, _)| engine_of(&it.id)).collect();
        let mut failed: HashSet<u64> = HashSet::new();
        let mut read = |ids: &[u64], failed: &mut HashSet<u64>, summary: &mut RefreshSummary| -> std::result::Result<(), String> {
            let r = engine.call("photo.readMetadataFromFile", json!({"ids": ids, "onlyChanged": true}))?;
            for f in r.get("failed").and_then(Value::as_array).into_iter().flatten() {
                summary.failures.push(format!("sidecar of photo {}: {}", f["id"], f["error"].as_str().unwrap_or("unreadable")));
                failed.extend(f["id"].as_u64());
            }
            Ok(())
        };
        if read(&read_ids, &mut failed, summary).is_err() {
            for id in &read_ids {
                if let Err(e) = read(&[*id], &mut failed, summary) {
                    summary.failures.push(format!("sidecar of photo {id}: {e}"));
                    failed.insert(*id);
                }
            }
        }
        let mut g = lock(&self.inner);
        for (it, hash) in &done {
            let eid = engine_of(&it.id);
            if eid.is_some_and(|e| failed.contains(&e)) {
                continue;
            }
            let ps = g.state.photos.entry(it.id.clone()).or_default();
            ps.manifest = it.version.clone();
            ps.base = it.version.clone();
            ps.synced = Some(hash.clone());
            if fresh.contains(&it.id) {
                summary.applied.extend(eid);
            }
        }
        if let Err(e) = self.save(&g) {
            summary.failures.push(e.to_string());
        }
    }

    // ---------------------------------------------------------------------------------------
    // previews and originals

    fn smart_dir(&self, engine: &mut dyn Engine) -> Result<PathBuf> {
        let r = engine.call("library.smartPreviewsLocation", json!({})).map_err(engine_err)?;
        r.get("path").and_then(Value::as_str).map(PathBuf::from).ok_or_else(|| Error::Engine("no smart previews folder".into()))
    }

    fn preview_files(dir: &Path, key: &str) -> [PathBuf; 2] {
        [dir.join(format!("{key}.lcsp")), dir.join(key.get(..2).unwrap_or("00")).join(format!("{key}.lcsp"))]
    }

    fn remove_preview_files(&self, engine: &mut dyn Engine, key: &str) {
        if let Ok(dir) = self.smart_dir(engine) {
            for f in Self::preview_files(&dir, key) {
                let _ = std::fs::remove_file(f);
            }
        }
    }

    /// Make sure photo `engine_id` has its smart preview in the engine's folder, downloading it when
    /// not; then trim the cache to its cap (the least recently opened first, never this one).
    /// Returns whether it was downloaded: the engine then has to render the photo again.
    pub fn ensure_preview(&self, engine: &mut dyn Engine, engine_id: u64) -> Result<bool> {
        let (id, item) = self.cloud_id_of(engine_id).ok_or_else(|| Error::Invalid(format!("photo {engine_id} is not in the cloud library")))?;
        let (Some(preview), Some(key)) = (item.preview.clone(), item.preview_key.clone()) else {
            return Err(Error::Invalid("the PC published no smart preview for this photo".into()));
        };
        let dir = self.smart_dir(engine)?;
        let files = Self::preview_files(&dir, &key);
        let present = files.iter().find_map(|f| std::fs::metadata(f).ok().filter(|m| m.is_file() && m.len() > 0).map(|m| m.len()));
        let downloaded = present.is_none();
        let bytes = match present {
            Some(b) => b,
            None => {
                self.fetch(&format!("mobile/{preview}"), &files[0])?;
                let got = std::fs::metadata(&files[0]).map(|m| m.len()).map_err(|e| Error::io("preview", e))?;
                if item.preview_bytes.is_some_and(|want| want != got) {
                    let _ = std::fs::remove_file(&files[0]);
                    return Err(Error::Remote(format!("preview of photo {engine_id} is {got} bytes, expected {}", item.preview_bytes.unwrap_or(0))));
                }
                got
            }
        };
        let now = millis(self.now());
        {
            let mut g = lock(&self.inner);
            g.state.previews.insert(id.clone(), PreviewState { key, bytes, used: now });
            self.save(&g)?;
        }
        self.trim_cache(engine, Some(&id))?;
        Ok(downloaded)
    }

    /// Delete the least recently opened previews until the cache fits its cap (`keep` stays).
    fn trim_cache(&self, engine: &mut dyn Engine, keep: Option<&str>) -> Result<()> {
        let victims: Vec<(String, PreviewState)> = {
            let g = lock(&self.inner);
            let mut total: u64 = g.state.previews.values().map(|p| p.bytes).sum();
            let mut by_age: Vec<(&String, &PreviewState)> = g.state.previews.iter().filter(|(id, _)| Some(id.as_str()) != keep).collect();
            by_age.sort_by_key(|(_, p)| p.used);
            let mut out = Vec::new();
            for (id, p) in by_age {
                if total <= g.state.cache_cap {
                    break;
                }
                total -= p.bytes;
                out.push((id.clone(), p.clone()));
            }
            out
        };
        if victims.is_empty() {
            return Ok(());
        }
        let dir = self.smart_dir(engine)?;
        let mut g = lock(&self.inner);
        for (id, p) in victims {
            for f in Self::preview_files(&dir, &p.key) {
                let _ = std::fs::remove_file(f);
            }
            g.state.previews.remove(&id);
        }
        self.save(&g)
    }

    /// Change the preview cache's cap and trim to it.
    pub fn set_cache_cap(&self, engine: &mut dyn Engine, bytes: u64) -> Result<()> {
        {
            let mut g = lock(&self.inner);
            g.state.cache_cap = bytes;
            self.save(&g)?;
        }
        self.trim_cache(engine, None)
    }

    /// Download the original of photo `engine_id` into the mirror (for a full-size export); returns its path.
    pub fn ensure_original(&self, engine_id: u64) -> Result<PathBuf> {
        let (_, item) = self.cloud_id_of(engine_id).ok_or_else(|| Error::Invalid(format!("photo {engine_id} is not in the cloud library")))?;
        let mut dest = self.root.clone();
        for c in item.path.split('/').filter(|c| !c.is_empty()) {
            if c == ".." || c == "." {
                return Err(Error::Format(format!("bad path in the manifest: {}", item.path)));
            }
            dest.push(c);
        }
        if std::fs::metadata(&dest).is_ok_and(|m| m.is_file() && item.size.is_none_or(|s| s == m.len())) {
            return Ok(dest);
        }
        self.fetch(&format!("originals/{}", item.path), &dest)?;
        Ok(dest)
    }

    // ---------------------------------------------------------------------------------------
    // edits

    /// The edits here the remote does not have yet: (cloud id, engine id, sidecar path, sha256).
    fn scan_pending(&self, engine: &mut dyn Engine) -> Result<Vec<(String, u64, PathBuf, String)>> {
        let known: Vec<(String, u64)> = lock(&self.inner).state.photos.iter().map(|(k, p)| (k.clone(), p.engine_id)).collect();
        let paths = self.sidecar_paths(engine, &known.iter().map(|(_, e)| *e).collect::<Vec<_>>())?;
        let g = lock(&self.inner);
        let mut out = Vec::new();
        for (id, eid) in known {
            let Some((path, true)) = paths.get(&eid).map(|(p, e)| (p, *e)) else { continue };
            let Some(hash) = self.hash_file(path) else { continue };
            let version = g.items.iter().find(|i| i.id == id).and_then(|i| i.version.as_deref());
            if Self::unsent(g.state.photos.get(&id), version, &hash) {
                out.push((id, eid, path.clone(), hash));
            }
        }
        Ok(out)
    }

    /// How many edits are waiting to be sent.
    pub fn pending(&self, engine: &mut dyn Engine) -> Result<usize> {
        let n = self.scan_pending(engine)?.len();
        lock(&self.inner).pending_hint = n;
        Ok(n)
    }

    /// Send every edit not sent yet to `inbox/<device>/` as a new file. A file that cannot be sent (offline)
    /// stays pending for the next call.
    pub fn push_edits(&self, engine: &mut dyn Engine) -> Result<PushSummary> {
        let _sync = lock(&self.sync_lock);
        let pending = self.scan_pending(engine)?;
        let mut out = PushSummary::default();
        let outbox = self.dir.join("outbox");
        std::fs::create_dir_all(&outbox).map_err(|e| Error::io("outbox", e))?;
        for (id, eid, path, hash) in pending {
            let r = (|| -> Result<()> {
                let bytes = std::fs::read(&path).map_err(|e| Error::io(path.display(), e))?;
                if bytes.len() as u64 >= MAX_SIDECAR {
                    return Err(Error::Invalid(format!("the sidecar of photo {eid} is over 2 MB")));
                }
                // (what was read is what is sent and recorded, even if the engine rewrites the file meanwhile)
                let hash = if sha256_hex(&bytes) == hash { hash.clone() } else { sha256_hex(&bytes) };
                let (base, stamp) = {
                    let g = lock(&self.inner);
                    let last = g.state.photos.get(&id).and_then(|p| p.stamp.as_deref()).and_then(util::parse_stamp);
                    let mut t = self.now();
                    if let Some(last) = last
                        && t <= last
                    {
                        t = last + Duration::from_secs(1);
                    }
                    (g.state.photos.get(&id).and_then(|p| p.base.clone()).unwrap_or_else(|| "none".into()), utc_stamp(t))
                };
                let name = format!("{id}-{stamp}-{base}.xmp");
                let tmp = outbox.join(&name);
                write_atomic(&tmp, &bytes)?;
                let sent = self.remote.put(&tmp, &format!("inbox/{}/{name}", self.device));
                let _ = std::fs::remove_file(&tmp);
                sent?;
                let mut g = lock(&self.inner);
                let ps = g.state.photos.entry(id.clone()).or_default();
                ps.synced = Some(hash);
                ps.base = Some(sidecar_version(&bytes));
                ps.stamp = Some(stamp);
                self.save(&g)
            })();
            match r {
                Ok(()) => out.sent += 1,
                Err(e) => {
                    out.failed += 1;
                    out.errors.push(format!("photo {eid}: {e}"));
                }
            }
        }
        let mut g = lock(&self.inner);
        g.pending_hint = out.failed;
        if let Some(e) = out.errors.first() {
            g.state.last_error = Some(e.clone());
        }
        let _ = self.save(&g);
        Ok(out)
    }
}
