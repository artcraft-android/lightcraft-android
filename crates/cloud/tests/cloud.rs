//! The cloud client against a folder standing in for the remote, with a fake engine and with the real one.

use std::collections::BTreeSet;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, UNIX_EPOCH};

use lightcraft_cloud_client::{CloudLibrary, DirRemote, Engine, Error, Remote, RemoteEntry};
use lightcraft_engine::Session;
use serde_json::{Value, json};
use sha2::{Digest, Sha256};

fn temp_dir(tag: &str) -> PathBuf {
    let d = std::env::temp_dir().join(format!("lc-cloud-{tag}-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&d);
    std::fs::create_dir_all(&d).unwrap();
    d
}

fn hex(n: u8) -> String {
    format!("{:032x}", u128::from(n) * 0x0101_0101_0101_0101)
}

fn sha16(bytes: &[u8]) -> String {
    Sha256::digest(bytes).iter().map(|b| format!("{b:02x}")).collect::<String>()[..16].to_string()
}

fn xmp(rating: u8) -> Vec<u8> {
    format!(
        r#"<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmlns:crs="http://ns.adobe.com/camera-raw-settings/1.0/" xmp:Rating="{rating}" crs:Exposure2012="+0.60"/>
</rdf:RDF></x:xmpmeta>"#
    )
    .into_bytes()
}

// -------------------------------------------------------------------------------------------
// a remote that counts, and can go offline

struct Spy {
    inner: DirRemote,
    gets: Mutex<Vec<String>>,
    puts: Mutex<Vec<String>>,
    offline: AtomicBool,
}

impl Spy {
    fn new(root: &Path) -> Arc<Spy> {
        Arc::new(Spy { inner: DirRemote::new(root), gets: Mutex::default(), puts: Mutex::default(), offline: AtomicBool::new(false) })
    }
    fn gets(&self) -> Vec<String> {
        std::mem::take(&mut *self.gets.lock().unwrap())
    }
    fn puts(&self) -> Vec<String> {
        std::mem::take(&mut *self.puts.lock().unwrap())
    }
    fn check(&self) -> Result<(), Error> {
        if self.offline.load(Ordering::SeqCst) { Err(Error::Remote("offline".into())) } else { Ok(()) }
    }
}

impl Remote for Spy {
    fn list(&self, dir: &str, recursive: bool) -> Result<Vec<RemoteEntry>, Error> {
        self.check()?;
        self.inner.list(dir, recursive)
    }
    fn get(&self, path: &str, local: &Path) -> Result<(), Error> {
        self.check()?;
        self.gets.lock().unwrap().push(path.to_string());
        self.inner.get(path, local)
    }
    fn put(&self, local: &Path, path: &str) -> Result<(), Error> {
        self.check()?;
        self.puts.lock().unwrap().push(path.to_string());
        self.inner.put(local, path)
    }
}

// -------------------------------------------------------------------------------------------
// the PC's side

struct Entry {
    n: u8,
    path: String,
    sidecar: Option<String>,
    alt: Option<Box<Entry>>,
    preview_key: String,
}

struct Pc {
    dir: PathBuf,
    entries: Vec<Entry>,
}

impl Pc {
    fn new(dir: &Path) -> Pc {
        Pc { dir: dir.to_path_buf(), entries: Vec::new() }
    }
    fn write(&self, rel: &str, bytes: &[u8]) {
        let p = self.dir.join(rel);
        std::fs::create_dir_all(p.parent().unwrap()).unwrap();
        std::fs::write(p, bytes).unwrap();
    }
    fn file(&self, n: u8, path: &str, sidecar: &str) -> Entry {
        self.write(&format!("originals/{path}"), format!("original {n}").as_bytes());
        self.write(&format!("mobile/thumbs/{}.jpg", hex(n)), format!("thumb {n} v0").as_bytes());
        Entry { n, path: path.into(), sidecar: Some(sidecar.into()), alt: None, preview_key: hex(n + 100) }
    }
    fn add(&mut self, n: u8, path: &str, sidecar: &str) {
        let e = self.file(n, path, sidecar);
        self.entries.push(e);
    }
    /// A JPEG with its raw partner as an alternate.
    fn add_pair(&mut self, n: u8, jpg: &str, jpg_sidecar: &str, raw_n: u8, raw: &str, raw_sidecar: &str) {
        let mut e = self.file(n, jpg, jpg_sidecar);
        e.alt = Some(Box::new(self.file(raw_n, raw, raw_sidecar)));
        self.entries.push(e);
    }
    fn set_sidecar(&self, rel: &str, bytes: &[u8]) {
        self.write(&format!("originals/{rel}"), bytes);
    }
    fn set_preview(&self, n: u8, bytes: &[u8]) {
        self.write(&format!("mobile/previews/{}.lcsp", hex(n)), bytes);
    }
    fn version(&self, sidecar: &Option<String>) -> Value {
        match sidecar.as_ref().and_then(|s| std::fs::read(self.dir.join("originals").join(s)).ok()) {
            Some(b) => json!(sha16(&b)),
            None => Value::Null,
        }
    }
    fn json(&self, e: &Entry) -> Value {
        let id = hex(e.n);
        let preview = self.dir.join(format!("mobile/previews/{id}.lcsp"));
        json!({"id": id, "path": e.path, "size": format!("original {}", e.n).len(), "sidecar": e.sidecar, "sidecarVersion": self.version(&e.sidecar),
               "captured": "2024-05-01T09:30:00+01:00", "camera": "ExampleCam X1", "width": 6000, "height": 4000, "rating": 0, "flag": "none", "label": null,
               "thumb": format!("thumbs/{id}.jpg"), "preview": format!("previews/{id}.lcsp"), "previewKey": e.preview_key,
               "previewBytes": std::fs::metadata(preview).map(|m| m.len()).unwrap_or(0)})
    }
    fn publish(&self) {
        let photos: Vec<Value> = self
            .entries
            .iter()
            .map(|e| {
                let mut v = self.json(e);
                if let Some(a) = &e.alt {
                    v["alternates"] = json!([self.json(a)]);
                }
                v
            })
            .collect();
        let m = json!({"format": 1, "generated": "2026-10-27T02:25:25Z", "publisher": "test", "photos": photos, "future": {"x": 1}});
        self.write("mobile/manifest.json", m.to_string().as_bytes());
    }
    fn inbox(&self, device: &str) -> Vec<String> {
        let d = self.dir.join("inbox").join(device);
        let mut v: Vec<String> = std::fs::read_dir(d).map(|r| r.flatten().map(|e| e.file_name().to_string_lossy().into_owned()).collect()).unwrap_or_default();
        v.sort();
        v
    }
}

// -------------------------------------------------------------------------------------------
// engines

struct Real(Session);

impl Real {
    fn new(lib: &Path) -> Real {
        let mut s = Session::new().with_fs();
        s.open_library(lib, false).unwrap();
        s.execute("library.xmpPreferences", &json!({"autoWrite": true})).unwrap();
        Real(s)
    }
    fn id_at(&self, path: &Path) -> u64 {
        self.0
            .catalog
            .photos()
            .find(|p| matches!(&p.source, lightcraft_engine::catalog::Source::File { path: f } if Path::new(f) == path))
            .map(|p| p.id.0)
            .unwrap_or_else(|| panic!("no photo at {}", path.display()))
    }
    fn rating(&self, id: u64) -> u8 {
        self.0.catalog.photo(lightcraft_engine::catalog::PhotoId(id)).unwrap().rating
    }
    fn rate(&mut self, id: u64, r: u64) {
        self.0.execute("library.select", &json!({"ids": [id]})).unwrap();
        self.0.execute("photo.rate", &json!({"ids": [id], "rating": r})).unwrap();
    }
}

impl Engine for Real {
    fn call(&mut self, command: &str, params: Value) -> Result<Value, String> {
        self.0.execute(command, &params).map_err(|e| e.to_string())
    }
}

/// Answers like the engine would, and remembers what it was asked.
#[derive(Default)]
struct Fake {
    calls: Vec<(String, Value)>,
    smart: PathBuf,
    /// Engine ids whose sidecar cannot be read: a call that includes one fails as a whole.
    unreadable: Vec<u64>,
}

impl Engine for Fake {
    fn call(&mut self, command: &str, params: Value) -> Result<Value, String> {
        self.calls.push((command.to_string(), params.clone()));
        match command {
            "library.addRemote" => {
                let ids: serde_json::Map<String, Value> = params["photos"].as_array().unwrap().iter().enumerate().map(|(i, p)| (p["id"].as_str().unwrap().to_string(), json!(i as u64 + 1))).collect();
                Ok(json!({"added": ids.len(), "updated": 0, "removed": 0, "ids": ids}))
            }
            "photo.sidecarPaths" => {
                let root = self.calls.iter().find(|(c, _)| c == "library.addRemote").unwrap().1["root"].as_str().unwrap().to_string();
                let photos = self.calls.iter().rev().find(|(c, _)| c == "library.addRemote").unwrap().1["photos"].as_array().unwrap().clone();
                let paths: Vec<Value> = params["ids"]
                    .as_array()
                    .unwrap()
                    .iter()
                    .map(|i| {
                        let p = photos[i.as_u64().unwrap() as usize - 1]["path"].as_str().unwrap();
                        let path = format!("{root}/{}", Path::new(p).with_extension("xmp").display());
                        json!({"id": i, "exists": Path::new(&path).exists(), "path": path})
                    })
                    .collect();
                Ok(json!({"paths": paths}))
            }
            "photo.readMetadataFromFile" => {
                if params["ids"].as_array().unwrap().iter().any(|i| self.unreadable.contains(&i.as_u64().unwrap())) {
                    return Err("sidecar unreadable".into());
                }
                Ok(json!({"read": [], "unchanged": [], "failed": []}))
            }
            "library.smartPreviewsLocation" => Ok(json!({"path": self.smart.to_string_lossy()})),
            other => Err(format!("unexpected {other}")),
        }
    }
}

fn open(dir: &Path, spy: &Arc<Spy>, device: &str) -> CloudLibrary {
    CloudLibrary::open(dir, spy.clone(), "test:", device).unwrap()
}

fn sidecars_of(root: &Path) -> BTreeSet<String> {
    fn walk(base: &Path, d: &Path, out: &mut BTreeSet<String>) {
        for e in std::fs::read_dir(d).into_iter().flatten().flatten() {
            if e.path().is_dir() {
                walk(base, &e.path(), out);
            } else if e.path().extension().is_some_and(|x| x == "xmp") {
                out.insert(e.path().strip_prefix(base).unwrap().to_string_lossy().into_owned());
            }
        }
    }
    let mut out = BTreeSet::new();
    walk(root, root, &mut out);
    out
}

// -------------------------------------------------------------------------------------------

#[test]
fn refresh_hands_the_engine_primaries_and_alternates_and_thumbs_are_fetched_once() {
    let base = temp_dir("fake");
    let mut pc = Pc::new(&base.join("pc"));
    pc.add(1, "A/one.jpg", "A/one.xmp");
    pc.add_pair(2, "B/two.JPG", "B/two.JPG.xmp", 3, "B/two.ARW", "B/two.xmp");
    pc.publish();
    let spy = Spy::new(&base.join("pc"));
    let lib = open(&base.join("state"), &spy, "phone-1");
    let mut eng = Fake { smart: base.join("smart"), ..Default::default() };
    let r = lib.refresh(&mut eng).unwrap();
    let add = &eng.calls[0];
    assert_eq!(add.0, "library.addRemote");
    assert_eq!(add.1["prune"], true);
    assert_eq!(add.1["root"], lib.root().to_string_lossy().as_ref());
    assert_eq!(add.1["thumbs"], lib.thumbs_dir().to_string_lossy().as_ref());
    let ids: Vec<&str> = add.1["photos"].as_array().unwrap().iter().map(|p| p["id"].as_str().unwrap()).collect();
    assert_eq!(ids, vec![hex(1), hex(2), hex(3)], "primary, then its partner");
    assert_eq!((r.total, r.added, r.hidden.len()), (3, 3, 1));
    assert_eq!(r.hidden, vec![3], "the raw partner");
    assert_eq!(r.thumbs.len(), 3);
    for n in 1..=3 {
        assert_eq!(std::fs::read(lib.thumbs_dir().join(format!("{}.jpg", hex(n)))).unwrap(), format!("thumb {n} v0").as_bytes());
    }
    let st = lib.status();
    assert_eq!((st.photos, st.hidden, st.pending, st.remote.as_str(), st.device.as_str()), (2, 1, 0, "test:", "phone-1"));
    assert!(st.last_refresh.is_some() && st.last_error.is_none());
    // the same again: manifest only
    spy.gets();
    eng.calls.clear();
    let r = lib.refresh(&mut eng).unwrap();
    assert_eq!(spy.gets(), vec!["mobile/manifest.json".to_string()]);
    assert!(r.thumbs.is_empty() && r.applied.is_empty() && r.failures.is_empty(), "{r:?}");
    let _ = std::fs::remove_dir_all(&base);
}

#[test]
fn one_sidecar_failing_to_apply_does_not_block_the_others_and_is_retried() {
    let base = temp_dir("badsidecar");
    let mut pc = Pc::new(&base.join("pc"));
    pc.add(1, "A/one.jpg", "A/one.xmp");
    pc.add(2, "A/two.jpg", "A/two.xmp");
    pc.set_sidecar("A/one.xmp", &xmp(1));
    pc.set_sidecar("A/two.xmp", &xmp(1));
    pc.publish();
    let spy = Spy::new(&base.join("pc"));
    let lib = open(&base.join("state"), &spy, "phone");
    let mut eng = Fake { smart: base.join("smart"), ..Default::default() };
    lib.refresh(&mut eng).unwrap();
    // the PC edits both; the engine cannot read the first
    pc.set_sidecar("A/one.xmp", &xmp(2));
    pc.set_sidecar("A/two.xmp", &xmp(3));
    pc.publish();
    eng.unreadable = vec![1];
    eng.calls.clear();
    let r = lib.refresh(&mut eng).expect("a bad sidecar is not a failed refresh");
    assert_eq!(r.applied, vec![2], "{r:?}");
    assert_eq!(r.failures.len(), 1, "{r:?}");
    assert!(r.failures[0].contains("photo 1"), "{r:?}");
    let st = lib.state();
    assert_eq!(st.photos[&hex(1)].manifest.as_deref(), Some(sha16(&xmp(1)).as_str()), "not recorded: retried");
    assert_eq!(st.photos[&hex(2)].manifest.as_deref(), Some(sha16(&xmp(3)).as_str()));
    assert!(lib.status().last_error.is_none());
    // the engine can read it now: the next refresh takes it, once
    eng.unreadable.clear();
    let r = lib.refresh(&mut eng).unwrap();
    assert_eq!((r.applied.clone(), r.failures.len()), (vec![1], 0), "{r:?}");
    assert_eq!(lib.state().photos[&hex(1)].manifest.as_deref(), Some(sha16(&xmp(2)).as_str()));
    let r = lib.refresh(&mut eng).unwrap();
    assert!(r.applied.is_empty() && r.failures.is_empty(), "{r:?}");
    let _ = std::fs::remove_dir_all(&base);
}

#[test]
fn a_newer_manifest_with_an_unknown_format_is_refused_and_the_cache_kept() {
    let base = temp_dir("format");
    let mut pc = Pc::new(&base.join("pc"));
    pc.add(1, "A/one.jpg", "A/one.xmp");
    pc.publish();
    let spy = Spy::new(&base.join("pc"));
    let lib = open(&base.join("state"), &spy, "p");
    let mut eng = Fake { smart: base.join("smart"), ..Default::default() };
    lib.refresh(&mut eng).unwrap();
    pc.write("mobile/manifest.json", br#"{"format": 2, "photos": []}"#);
    let e = lib.refresh(&mut eng).unwrap_err();
    assert!(matches!(e, Error::Format(_)), "{e}");
    assert!(lib.status().last_error.is_some());
    assert_eq!(lib.status().photos, 1);
    // garbage is not a manifest either
    pc.write("mobile/manifest.json", b"<html>");
    assert!(lib.refresh(&mut eng).is_err());
    assert!(CloudLibrary::open(&base.join("state"), spy.clone(), "x", "bad name").is_err());
    let _ = std::fs::remove_dir_all(&base);
}

#[test]
fn the_real_engine_gets_pairs_thumbs_and_sidecars_and_a_second_refresh_changes_nothing() {
    let base = temp_dir("real");
    let mut pc = Pc::new(&base.join("pc"));
    pc.add(1, "A/one.jpg", "A/one.xmp");
    pc.add_pair(2, "B/two.JPG", "B/two.JPG.xmp", 3, "B/two.ARW", "B/two.xmp");
    pc.set_sidecar("A/one.xmp", &xmp(4));
    pc.set_sidecar("B/two.xmp", &xmp(2)); // the raw's (the engine's stem owner)
    pc.publish();
    let spy = Spy::new(&base.join("pc"));
    let lib = open(&base.join("state"), &spy, "phone-1");
    let mut eng = Real::new(&base.join("lib"));
    let r = lib.refresh(&mut eng).unwrap();
    assert_eq!((r.added, r.total), (3, 3));
    let root = lib.root().to_path_buf();
    let (one, two_jpg, two_raw) = (eng.id_at(&root.join("A/one.jpg")), eng.id_at(&root.join("B/two.JPG")), eng.id_at(&root.join("B/two.ARW")));
    assert_eq!(r.hidden, vec![two_raw]);
    // the pair's sidecars: the raw owns B/two.xmp; the JPEG has none published, and no file was made for it
    assert_eq!(sidecars_of(&root), ["A/one.xmp", "B/two.xmp"].map(String::from).into_iter().collect());
    assert_eq!((eng.rating(one), eng.rating(two_raw), eng.rating(two_jpg)), (4, 2, 0));
    assert_eq!(r.applied.len(), 2);
    assert_eq!(r.thumbs.len(), 3);
    // idempotent
    spy.gets();
    let r = lib.refresh(&mut eng).unwrap();
    assert_eq!(spy.gets(), vec!["mobile/manifest.json".to_string()]);
    assert_eq!((r.added, r.updated, r.removed, r.thumbs.len(), r.applied.len(), r.blocked.len()), (0, 0, 0, 0, 0, 0), "{r:?}");
    assert_eq!(lib.pending(&mut eng).unwrap(), 0);
    assert_eq!(lib.push_edits(&mut eng).unwrap().sent, 0);
    assert!(spy.puts().is_empty(), "nothing the PC has is sent back");
    // a PC sidecar change is applied, and a thumbnail the PC re-rendered is fetched again
    pc.set_sidecar("A/one.xmp", &xmp(1));
    pc.write(&format!("mobile/thumbs/{}.jpg", hex(1)), b"thumb 1 v1, longer");
    pc.publish();
    let r = lib.refresh(&mut eng).unwrap();
    assert_eq!((r.applied.clone(), r.thumbs.clone()), (vec![one], vec![one]));
    assert_eq!(eng.rating(one), 1);
    assert_eq!(std::fs::read(root.join("A/one.xmp")).unwrap(), xmp(1), "saved byte for byte");
    assert_eq!(std::fs::read(lib.thumbs_dir().join(format!("{}.jpg", hex(1)))).unwrap(), b"thumb 1 v1, longer");
    assert_eq!(lib.pending(&mut eng).unwrap(), 0, "taking the PC's sidecar is not an edit");
    // a photo that leaves the manifest leaves the library, with its thumbnail
    pc.entries.remove(0);
    pc.publish();
    let r = lib.refresh(&mut eng).unwrap();
    assert_eq!(r.removed, 1);
    assert!(!lib.thumbs_dir().join(format!("{}.jpg", hex(1))).exists());
    let _ = std::fs::remove_dir_all(&base);
}

#[test]
fn a_local_edit_is_sent_once_with_the_right_name_and_base() {
    let base = temp_dir("push");
    let mut pc = Pc::new(&base.join("pc"));
    pc.add(1, "A/one.jpg", "A/one.xmp");
    pc.add(2, "A/two.jpg", "A/two.xmp"); // no sidecar published
    pc.set_sidecar("A/one.xmp", &xmp(4));
    pc.publish();
    let spy = Spy::new(&base.join("pc"));
    let mut lib = open(&base.join("state"), &spy, "sonnet-test");
    let t0 = 1_793_000_000u64;
    lib.set_clock(move || UNIX_EPOCH + Duration::from_secs(t0));
    let mut eng = Real::new(&base.join("lib"));
    lib.refresh(&mut eng).unwrap();
    let root = lib.root().to_path_buf();
    let (one, two) = (eng.id_at(&root.join("A/one.jpg")), eng.id_at(&root.join("A/two.jpg")));
    eng.rate(one, 2);
    eng.rate(two, 5);
    assert_eq!(lib.pending(&mut eng).unwrap(), 2);
    let r = lib.push_edits(&mut eng).unwrap();
    assert_eq!((r.sent, r.failed), (2, 0), "{r:?}");
    let v_one = sha16(&xmp(4));
    assert_eq!(pc.inbox("sonnet-test"), vec![format!("{}-20261026T073320Z-{v_one}.xmp", hex(1)), format!("{}-20261026T073320Z-none.xmp", hex(2))]);
    // what was sent is the sidecar, whole
    let sent = std::fs::read(pc.dir.join("inbox/sonnet-test").join(format!("{}-20261026T073320Z-{v_one}.xmp", hex(1)))).unwrap();
    assert_eq!(sent, std::fs::read(root.join("A/one.xmp")).unwrap());
    assert_eq!(lightcraft_meta::parse_xmp(&String::from_utf8(sent.clone()).unwrap()).unwrap().metadata.rating, Some(2));
    assert!(sent.len() < 2 * 1024 * 1024);
    // not again
    assert_eq!(lib.pending(&mut eng).unwrap(), 0);
    assert_eq!(lib.push_edits(&mut eng).unwrap().sent, 0);
    assert!(lib.refresh(&mut eng).is_ok());
    assert_eq!(lib.push_edits(&mut eng).unwrap().sent, 0);
    assert_eq!(pc.inbox("sonnet-test").len(), 2);
    // a second edit in the same second gets a later stamp (inbox files are never overwritten), and
    // starts from the version the first one will have on the PC
    eng.rate(one, 3);
    assert_eq!(lib.push_edits(&mut eng).unwrap().sent, 1);
    let names = pc.inbox("sonnet-test");
    assert_eq!(names.len(), 3);
    assert!(names.contains(&format!("{}-20261026T073321Z-{}.xmp", hex(1), sha16(&std::fs::read(pc.dir.join("inbox/sonnet-test").join(format!("{}-20261026T073320Z-{v_one}.xmp", hex(1)))).unwrap()))), "{names:?}");
    // the PC applies the first and the second: the client takes nothing back and sends nothing
    let latest = std::fs::read(root.join("A/one.xmp")).unwrap();
    pc.set_sidecar("A/one.xmp", &latest);
    pc.publish();
    let r = lib.refresh(&mut eng).unwrap();
    assert!(r.applied.is_empty() && r.blocked.is_empty(), "{r:?}");
    assert_eq!(lib.push_edits(&mut eng).unwrap().sent, 0);
    assert_eq!(eng.rating(one), 3);
    let _ = std::fs::remove_dir_all(&base);
}

#[test]
fn an_unsent_edit_blocks_a_pc_change_until_it_is_sent_and_the_pc_may_win() {
    let base = temp_dir("block");
    let mut pc = Pc::new(&base.join("pc"));
    pc.add(1, "A/one.jpg", "A/one.xmp");
    pc.set_sidecar("A/one.xmp", &xmp(4));
    pc.publish();
    let spy = Spy::new(&base.join("pc"));
    let lib = open(&base.join("state"), &spy, "phone");
    let mut eng = Real::new(&base.join("lib"));
    lib.refresh(&mut eng).unwrap();
    let one = eng.id_at(&lib.root().join("A/one.jpg"));
    eng.rate(one, 1);
    let mine = std::fs::read(lib.root().join("A/one.xmp")).unwrap();
    pc.set_sidecar("A/one.xmp", &xmp(5));
    pc.publish();
    let r = lib.refresh(&mut eng).unwrap();
    assert_eq!((r.blocked.clone(), r.applied.len()), (vec![one], 0));
    assert_eq!(std::fs::read(lib.root().join("A/one.xmp")).unwrap(), mine, "my edit is still there");
    assert_eq!(eng.rating(one), 1);
    // sending it (base = what I started from); the PC decides: here its own change wins
    assert_eq!(lib.push_edits(&mut eng).unwrap().sent, 1);
    assert!(pc.inbox("phone")[0].ends_with(&format!("-{}.xmp", sha16(&xmp(4)))));
    let r = lib.refresh(&mut eng).unwrap();
    assert_eq!((r.applied.clone(), r.blocked.len()), (vec![one], 0), "{r:?}");
    assert_eq!(eng.rating(one), 5);
    assert_eq!(lib.pending(&mut eng).unwrap(), 0);
    let _ = std::fs::remove_dir_all(&base);
}

#[test]
fn offline_pushes_stay_pending_and_go_later() {
    let base = temp_dir("offline");
    let mut pc = Pc::new(&base.join("pc"));
    pc.add(1, "A/one.jpg", "A/one.xmp");
    pc.publish();
    let spy = Spy::new(&base.join("pc"));
    let lib = open(&base.join("state"), &spy, "phone");
    let mut eng = Real::new(&base.join("lib"));
    lib.refresh(&mut eng).unwrap();
    let one = eng.id_at(&lib.root().join("A/one.jpg"));
    eng.rate(one, 3);
    spy.offline.store(true, Ordering::SeqCst);
    let r = lib.push_edits(&mut eng).unwrap();
    assert_eq!((r.sent, r.failed), (0, 1));
    assert!(lib.refresh(&mut eng).is_err());
    assert_eq!(lib.status().pending, 1);
    assert!(lib.status().last_error.unwrap().contains("offline"));
    assert!(pc.inbox("phone").is_empty());
    spy.offline.store(false, Ordering::SeqCst);
    assert_eq!(lib.push_edits(&mut eng).unwrap().sent, 1);
    assert_eq!(pc.inbox("phone").len(), 1);
    assert_eq!(lib.status().pending, 0);
    // the state survives a restart: nothing is sent twice
    drop(lib);
    let lib = open(&base.join("state"), &spy, "phone");
    assert_eq!(lib.push_edits(&mut eng).unwrap().sent, 0);
    let _ = std::fs::remove_dir_all(&base);
}

#[test]
fn a_corrupt_state_starts_over_without_resending_the_pcs_own_sidecars() {
    let base = temp_dir("corrupt");
    let mut pc = Pc::new(&base.join("pc"));
    pc.add(1, "A/one.jpg", "A/one.xmp");
    pc.add(2, "A/two.jpg", "A/two.xmp");
    pc.set_sidecar("A/one.xmp", &xmp(4));
    pc.set_sidecar("A/two.xmp", &xmp(3));
    pc.publish();
    let spy = Spy::new(&base.join("pc"));
    let state = base.join("state");
    let lib = open(&state, &spy, "phone");
    let mut eng = Real::new(&base.join("lib"));
    lib.refresh(&mut eng).unwrap();
    let two = eng.id_at(&lib.root().join("A/two.jpg"));
    eng.rate(two, 1);
    assert_eq!(lib.push_edits(&mut eng).unwrap().sent, 1);
    drop(lib);
    spy.gets();
    spy.puts();
    for (round, garbage) in [&b"{\"photos\": [[[ not json"[..], b""].into_iter().enumerate() {
        std::fs::write(state.join("state.json"), garbage).unwrap();
        let lib = open(&state, &spy, "phone");
        assert!(lib.state_was_recovered());
        let r = lib.refresh(&mut eng).unwrap();
        // no sidecar is downloaded (they are on disk), and the one equal to the PC's is not sent;
        // the edited one is (its base is lost: `none`, so the PC settles it by time)
        assert!(spy.gets().iter().all(|g| !g.starts_with("originals/")), "{r:?}");
        assert!(r.applied.is_empty());
        if round == 1 {
            // the PC has the edit by now: all equal, nothing to do
            assert!(r.blocked.is_empty());
            assert_eq!(lib.push_edits(&mut eng).unwrap().sent, 0);
            assert!(spy.puts().is_empty());
            continue;
        }
        assert_eq!(r.blocked.len(), 1, "the edited photo is not overwritten: {r:?}");
        let sent = lib.push_edits(&mut eng).unwrap();
        assert_eq!(sent.sent, 1);
        let puts = spy.puts();
        assert_eq!(puts.len(), 1);
        assert!(puts[0].starts_with(&format!("inbox/phone/{}-", hex(2))) && puts[0].ends_with("-none.xmp"), "{puts:?}");
        // the PC applies the edit; the next refresh is quiet again
        let name = pc.inbox("phone").pop().unwrap();
        pc.set_sidecar("A/two.xmp", &std::fs::read(pc.dir.join("inbox/phone").join(name)).unwrap());
        pc.publish();
        assert!(lib.refresh(&mut eng).unwrap().applied.is_empty());
        assert_eq!(lib.push_edits(&mut eng).unwrap().sent, 0);
    }
    assert!(state.join("state.json.corrupt").exists());
    assert_eq!(eng.rating(two), 1);
    let _ = std::fs::remove_dir_all(&base);
}

fn lcsp() -> Vec<u8> {
    let img = lightcraft_scenes::demo_library()[0].render(64, 40);
    lightcraft_engine::smart::encode(&img, None).unwrap()
}

#[test]
fn a_preview_makes_the_photo_render_and_the_cache_drops_the_least_recently_opened() {
    let base = temp_dir("preview");
    let mut pc = Pc::new(&base.join("pc"));
    pc.add(1, "A/one.jpg", "A/one.xmp");
    pc.add(2, "A/two.jpg", "A/two.xmp");
    pc.add(3, "A/three.jpg", "A/three.xmp");
    pc.publish();
    let spy = Spy::new(&base.join("pc"));
    let mut lib = open(&base.join("state"), &spy, "phone");
    let clock = Arc::new(Mutex::new(1_793_000_000u64));
    let c2 = clock.clone();
    lib.set_clock(move || UNIX_EPOCH + Duration::from_secs(*c2.lock().unwrap()));
    let tick = |s: u64| *clock.lock().unwrap() += s;
    let mut eng = Real::new(&base.join("lib"));
    lib.refresh(&mut eng).unwrap();
    // the real keys, now the engine knows the photos
    let ids: Vec<u64> = ["one", "two", "three"].iter().map(|n| eng.id_at(&lib.root().join(format!("A/{n}.jpg")))).collect();
    for (i, id) in ids.iter().enumerate() {
        let key = lightcraft_engine::smart::file_name(eng.0.catalog.photo(lightcraft_engine::catalog::PhotoId(*id)).unwrap());
        pc.entries[i].preview_key = key.trim_end_matches(".lcsp").to_string();
        pc.set_preview(i as u8 + 1, &lcsp());
    }
    pc.publish();
    lib.refresh(&mut eng).unwrap();
    let size = lcsp().len() as u64;
    // not yet: no original, no preview
    assert!(eng.0.render_now(lightcraft_engine::catalog::PhotoId(ids[0]), 64, 40).is_err());
    assert!(lib.ensure_preview(&mut eng, ids[0]).unwrap(), "downloaded");
    eng.0.media.forget(lightcraft_engine::catalog::PhotoId(ids[0]));
    assert!(eng.0.render_now(lightcraft_engine::catalog::PhotoId(ids[0]), 64, 40).is_ok(), "renders from the preview");
    assert!(!lib.ensure_preview(&mut eng, ids[0]).unwrap(), "already there");
    let smart = eng.0.media.smart_dir.clone().unwrap();
    let files: Vec<PathBuf> = ids.iter().map(|id| smart.join(lightcraft_engine::smart::file_name(eng.0.catalog.photo(lightcraft_engine::catalog::PhotoId(*id)).unwrap()))).collect();
    let held = |n: usize| files[n].exists();
    // room for two
    lib.set_cache_cap(&mut eng, size * 2 + 10).unwrap();
    tick(10);
    lib.ensure_preview(&mut eng, ids[1]).unwrap();
    tick(10);
    lib.ensure_preview(&mut eng, ids[0]).unwrap(); // opened again: now `two` is the oldest
    tick(10);
    lib.ensure_preview(&mut eng, ids[2]).unwrap();
    assert_eq!((held(0), held(1), held(2)), (true, false, true), "the least recently opened went");
    assert_eq!(lib.status().cache_bytes, size * 2);
    // a smaller cap trims at once, but never the photo just opened
    lib.set_cache_cap(&mut eng, 1).unwrap();
    assert_eq!(lib.status().cache_bytes, 0);
    assert!(lib.ensure_preview(&mut eng, ids[1]).unwrap());
    assert_eq!((held(0), held(1), held(2)), (false, true, false));
    // an original comes down to the mirror
    let p = lib.ensure_original(ids[2]).unwrap();
    assert_eq!(std::fs::read(&p).unwrap(), b"original 3");
    assert_eq!(p, lib.root().join("A/three.jpg"));
    spy.gets();
    lib.ensure_original(ids[2]).unwrap();
    assert!(spy.gets().is_empty(), "not downloaded twice");
    assert!(lib.ensure_preview(&mut eng, 999_999).is_err(), "not a photo of the library");
    let _ = std::fs::remove_dir_all(&base);
}

#[test]
fn lru_with_a_fake_engine_counts_only_what_it_downloaded() {
    let base = temp_dir("lru");
    let mut pc = Pc::new(&base.join("pc"));
    for n in 1..=4 {
        pc.add(n, &format!("A/{n}.jpg"), &format!("A/{n}.xmp"));
        pc.set_preview(n, &vec![n; 1000]);
    }
    pc.publish();
    let spy = Spy::new(&base.join("pc"));
    let mut lib = open(&base.join("state"), &spy, "phone");
    let clock = Arc::new(Mutex::new(1_793_000_000u64));
    let c2 = clock.clone();
    lib.set_clock(move || UNIX_EPOCH + Duration::from_secs(*c2.lock().unwrap()));
    let mut eng = Fake { smart: base.join("smart"), ..Default::default() };
    lib.refresh(&mut eng).unwrap();
    lib.set_cache_cap(&mut eng, 2500).unwrap();
    for id in [1u64, 2, 1, 3, 4] {
        *clock.lock().unwrap() += 5;
        lib.ensure_preview(&mut eng, id).unwrap();
    }
    let on_disk: BTreeSet<String> = std::fs::read_dir(base.join("smart")).unwrap().flatten().map(|e| e.file_name().to_string_lossy().into_owned()).collect();
    let key = |n: u8| format!("{}.lcsp", hex(n + 100));
    assert_eq!(on_disk, [key(3), key(4)].into_iter().collect(), "1 and 2 were the least recent");
    let st = lib.state();
    assert_eq!(st.previews.len(), 2);
    assert_eq!(st.cache_cap, 2500);
    // a preview that is not the size the manifest says (cut short) is refused and not kept
    let mut m: Value = serde_json::from_slice(&std::fs::read(pc.dir.join("mobile/manifest.json")).unwrap()).unwrap();
    m["photos"][0]["previewBytes"] = json!(5000);
    pc.write("mobile/manifest.json", m.to_string().as_bytes());
    lib.refresh(&mut eng).unwrap();
    assert!(matches!(lib.ensure_preview(&mut eng, 1), Err(Error::Remote(_))));
    assert!(!base.join("smart").join(key(1)).exists());
    // the cap and the previews are remembered
    drop(lib);
    let lib = open(&base.join("state"), &spy, "phone");
    assert_eq!((lib.status().cache_bytes, lib.status().cache_cap), (2000, 2500));
    let _ = std::fs::remove_dir_all(&base);
}
