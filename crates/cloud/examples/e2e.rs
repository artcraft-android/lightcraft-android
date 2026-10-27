//! End-to-end check against a real remote, with the real engine in a temporary library:
//!
//!   cargo run -p lightcraft-cloud-client --example e2e -- <rclone.conf> <fs, e.g. "gdrive:LightCraft Cloud Test"> <device> <step>
//!
//! step `first`: refresh, open one preview, edit it, push. step `second`: refresh again and show that nothing is
//! re-sent. The temporary library lives in $LC_E2E_DIR (default /tmp/lc-e2e) between the steps. Writes only
//! `inbox/<device>/` on the remote.

use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::Instant;

use lightcraft_cloud_client::{CloudLibrary, Engine, RcloneRemote};
use lightcraft_engine::Session;
use serde_json::{Value, json};

struct Real(Session);

impl Engine for Real {
    fn call(&mut self, command: &str, params: Value) -> Result<Value, String> {
        self.0.execute(command, &params).map_err(|e| e.to_string())
    }
}

fn main() {
    let a: Vec<String> = std::env::args().collect();
    let (conf, fs, device, step) = (&a[1], &a[2], &a[3], a[4].as_str());
    let dir = PathBuf::from(std::env::var("LC_E2E_DIR").unwrap_or_else(|_| "/tmp/lc-e2e".into()));
    let so = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../apps/lightcraft-android/librclone-host/librclone.so");
    let t = Instant::now();
    let lap = |what: &str, t: &mut Instant| {
        println!("{what}: {:.2}s", t.elapsed().as_secs_f64());
        *t = Instant::now();
    };
    let mut t2 = t;
    let remote = RcloneRemote::open(Some(&so), Path::new(conf), fs, None).unwrap();
    lap("load librclone + config", &mut t2);
    let lib = CloudLibrary::open(&dir.join("cloud"), Arc::new(remote), fs, device).unwrap();
    let mut s = Session::new().with_fs();
    s.open_library(dir.join("library"), false).unwrap();
    s.execute("library.xmpPreferences", &json!({"autoWrite": true})).unwrap();
    let mut eng = Real(s);
    let r = lib.refresh(&mut eng).unwrap();
    lap("refresh", &mut t2);
    println!("total {} added {} updated {} thumbs {} applied {} blocked {} hidden {:?} failures {:?}", r.total, r.added, r.updated, r.thumbs.len(), r.applied.len(), r.blocked.len(), r.hidden, r.failures);
    let ids: Vec<u64> = eng.0.catalog.photos().map(|p| p.id.0).filter(|i| !r.hidden.contains(i)).collect();
    let id = *ids.iter().min().unwrap();
    println!("pending {}", lib.pending(&mut eng).unwrap());
    if step == "first" {
        let id = lightcraft_engine::catalog::PhotoId(id);
        println!("render before preview: {}", eng.0.render_now(id, 256, 256).map(|_| "ok".to_string()).unwrap_or_else(|e| e));
        let got = lib.ensure_preview(&mut eng, id.0).unwrap();
        lap("ensure_preview", &mut t2);
        eng.0.media.forget(id);
        let rendered = eng.0.render_now(id, 256, 256);
        println!("downloaded {got}; render after preview: {}", if rendered.is_ok() { "ok" } else { "FAILED" });
        lap("render", &mut t2);
        eng.0.execute("library.select", &json!({"ids": [id.0]})).unwrap();
        eng.0.execute("develop.set", &json!({"control": "light.exposure", "value": 0.5})).unwrap();
        println!("pending after edit {}", lib.pending(&mut eng).unwrap());
        let p = lib.push_edits(&mut eng).unwrap();
        lap("push_edits", &mut t2);
        println!("sent {} failed {} {:?}", p.sent, p.failed, p.errors);
        println!("edited photo {}", id.0);
    } else {
        let p = lib.push_edits(&mut eng).unwrap();
        println!("second push: sent {} (must be 0)", p.sent);
        let p0 = eng.0.catalog.photo(lightcraft_engine::catalog::PhotoId(id)).unwrap();
        println!("photo {id}: exposure {}", p0.develop.light.exposure);
    }
    println!("{:?}", lib.status());
    println!("total {:.2}s", t.elapsed().as_secs_f64());
}
