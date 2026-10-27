//! `RcloneRemote` through the real librclone (host build: `apps/lightcraft-android/scripts/build-librclone.sh host`).
//! Skipped when the library is not built.

use std::path::{Path, PathBuf};

use lightcraft_cloud_client::{Error, RcloneRemote, Remote};

fn so() -> Option<PathBuf> {
    let p = std::env::var_os("LIBRCLONE").map(PathBuf::from).unwrap_or_else(|| Path::new(env!("CARGO_MANIFEST_DIR")).join("../../apps/lightcraft-android/librclone-host/librclone.so"));
    p.is_file().then_some(p)
}

#[test]
fn rclone_remote_lists_gets_and_puts_in_a_local_folder() {
    let Some(so) = so() else {
        eprintln!("skipped: librclone.so is not built");
        return;
    };
    let base = std::env::temp_dir().join(format!("lc-cloud-rclone-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&base);
    let remote_dir = base.join("remote Dir");
    std::fs::create_dir_all(remote_dir.join("mobile/thumbs")).unwrap();
    std::fs::write(remote_dir.join("mobile/manifest.json"), b"{}").unwrap();
    std::fs::write(remote_dir.join("mobile/thumbs/a.jpg"), b"12345").unwrap();
    let conf = base.join("rclone.conf");
    std::fs::write(&conf, "").unwrap();
    let r = RcloneRemote::open(Some(&so), &conf, &remote_dir.to_string_lossy(), None).unwrap();

    let mut top: Vec<_> = r.list("mobile", false).unwrap().into_iter().map(|e| (e.path, e.is_dir, e.size)).collect();
    top.sort();
    assert_eq!(top, vec![("manifest.json".to_string(), false, 2), ("thumbs".to_string(), true, 0)]);
    let mut all: Vec<_> = r.list("", true).unwrap().into_iter().filter(|e| !e.is_dir).map(|e| (e.path, e.size)).collect();
    all.sort();
    assert_eq!(all, vec![("mobile/manifest.json".to_string(), 2), ("mobile/thumbs/a.jpg".to_string(), 5)]);
    assert!(matches!(r.list("nope", false), Err(Error::NotFound(_))));

    let out = base.join("got/manifest.json");
    std::fs::create_dir_all(out.parent().unwrap()).unwrap();
    r.get("mobile/thumbs/a.jpg", &out).unwrap();
    assert_eq!(std::fs::read(&out).unwrap(), b"12345");
    assert!(matches!(r.get("mobile/missing.jpg", &base.join("got/x")), Err(Error::NotFound(_))));

    // a put under a new name, creating the folders
    let up = base.join("up.tmp");
    std::fs::write(&up, b"<xmp/>").unwrap();
    r.put(&up, "inbox/dev/abc-20261026T073320Z-none.xmp").unwrap();
    assert_eq!(std::fs::read(remote_dir.join("inbox/dev/abc-20261026T073320Z-none.xmp")).unwrap(), b"<xmp/>");
    assert_eq!(r.stat("inbox/dev/abc-20261026T073320Z-none.xmp").unwrap().map(|e| e.size), Some(6));
    assert_eq!(r.stat("inbox/dev/none").unwrap(), None);
    // a missing config is reported, not loaded
    assert!(RcloneRemote::open(Some(&so), &base.join("absent.conf"), "x:", None).is_err());
    let _ = std::fs::remove_dir_all(&base);
}

#[test]
fn a_missing_library_is_a_clear_error() {
    // (a different path than the real one: the process-wide library may already be loaded by the test above, which is fine)
    if RcloneRemote::available(Some(Path::new("/nonexistent/librclone.so"))) {
        return;
    }
    let e = RcloneRemote::open(Some(Path::new("/nonexistent/librclone.so")), Path::new("/x"), "x:", None).err().unwrap();
    assert!(e.to_string().contains("librclone.so not found"), "{e}");
}
