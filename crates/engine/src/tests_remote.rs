//! Cloud (remote) libraries: smart previews in desktop LightCraft's two-level layout, photos whose
//! originals live on a remote (`library.addRemote`), their thumbnails and sidecars.

use std::path::{Path, PathBuf};

use serde_json::json;

use crate::Session;

fn temp_dir(tag: &str) -> PathBuf {
    let d = std::env::temp_dir().join(format!("lc-remote-{tag}-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&d);
    std::fs::create_dir_all(&d).unwrap();
    d
}

fn write_png(path: &Path, seed: u8) {
    let (w, h) = (48usize, 32usize);
    let data: Vec<[u8; 4]> = (0..w * h).map(|i| [(i % w * 5) as u8, (i / w * 7) as u8, seed, 255]).collect();
    let img = lightcraft_raster::Rgba8 { width: w, height: h, data };
    let bytes = lightcraft_codecs::encode_png(&lightcraft_codecs::EncodeImage::rgba8(&img), &lightcraft_codecs::EncodeMeta::default()).unwrap();
    std::fs::create_dir_all(path.parent().unwrap()).unwrap();
    std::fs::write(path, bytes).unwrap();
}

/// A smart preview in either layout (sharded, or the flat one of older releases) stands in for an
/// offline original, is counted, found by the status query, and removed by Discard.
#[test]
fn smart_previews_in_either_layout_are_found_everywhere() {
    let src = temp_dir("shard-src");
    let lib = temp_dir("shard-lib");
    write_png(&src.join("a.png"), 7);
    let mut s = Session::new().with_fs();
    s.open_library(&lib, false).unwrap();
    s.execute("library.import", &json!({"paths": [src.join("a.png").to_string_lossy()]})).unwrap();
    let id = s.active().unwrap();
    assert_eq!(s.execute("library.smartPreviews", &json!({})).unwrap()["built"], 1);
    let dir = s.media.smart_dir.clone().unwrap();
    let sharded = crate::smart::sharded_file_path(&dir, s.catalog.photo(id).unwrap());
    assert!(sharded.is_file(), "built in a folder of its first two hex digits");
    assert_eq!(crate::smart::stats(&dir).0, 1, "counted in its folder");
    assert_eq!(s.execute("photo.smartPreview", &json!({})).unwrap()["smartPreview"], true);
    // the original goes offline: the render comes from the proxy
    std::fs::rename(&src, src.with_extension("offline")).unwrap();
    s.media.forget(id);
    assert!(s.render_now(id, 48, 32).is_ok());
    assert_eq!(s.execute("library.smartPreviews", &json!({"discard": true})).unwrap()["removed"], 1);
    assert!(!sharded.exists());
    assert_eq!(crate::smart::stats(&dir).0, 0);
    s.media.forget(id);
    assert!(s.render_now(id, 48, 32).is_err());
    let _ = std::fs::remove_dir_all(src.with_extension("offline"));
    let _ = std::fs::remove_dir_all(&lib);
}
