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

// ---------------------------------------------------------------------------------------------
// library.addRemote and the photos it makes

fn hex(n: u8) -> String {
    format!("{:032x}", u128::from(n) * 0x0101_0101_0101_0101)
}

/// A manifest entry.
fn item(n: u8, path: &str) -> serde_json::Value {
    json!({"id": hex(n), "path": path, "size": 1000 + u64::from(n), "width": 6000, "height": 4000, "captured": "2024-05-01T09:30:00+01:00",
           "rating": n % 6, "flag": "none", "label": null, "camera": "ExampleCam X1", "lens": "Example 18-135mm"})
}

fn remote_session(root: &Path) -> Session {
    let mut s = Session::new().with_fs();
    // (beside the remote's folder, not in it: nothing under a remote root is ever there)
    let mut smart = root.as_os_str().to_owned();
    smart.push("-smart");
    s.media.smart_dir = Some(PathBuf::from(smart));
    s
}

fn photo_at<'a>(s: &'a Session, path: &Path) -> Option<&'a lightcraft_catalog::Photo> {
    s.catalog.photos().find(|p| matches!(&p.source, lightcraft_catalog::Source::File { path: f } if Path::new(f) == path)).map(|p| &**p)
}

/// A small smart preview for `p`, in the flat or the two-level layout, with a desktop-style header and tail.
fn put_smart(s: &Session, id: lightcraft_catalog::PhotoId, two_level: bool) -> PathBuf {
    let dir = s.media.smart_dir.clone().unwrap();
    std::fs::create_dir_all(&dir).unwrap();
    let flat = dir.join(crate::smart::file_name(s.catalog.photo(id).unwrap()));
    let img = lightcraft_scenes::demo_library()[0].render(64, 40);
    let bytes = crate::smart::tests::desktop_like(&crate::smart::encode(&img, None).unwrap(), &[7u8; 500]);
    let at = if two_level { crate::smart::sharded_file_path(&dir, s.catalog.photo(id).unwrap()) } else { flat };
    std::fs::create_dir_all(at.parent().unwrap()).unwrap();
    std::fs::write(&at, bytes).unwrap();
    at
}

#[test]
fn add_remote_adds_updates_prunes_and_is_idempotent() {
    let root = temp_dir("add");
    let mut s = remote_session(&root);
    let (a, b, c) = (root.join("A/one.ARW"), root.join("A/two.JPG"), root.join("B/three.jpg"));
    let all = json!([item(1, "A/one.ARW"), item(2, "A/two.JPG"), item(3, "B/three.jpg")]);
    let r = s.execute("library.addRemote", &json!({"root": root.to_string_lossy(), "photos": all})).unwrap();
    assert_eq!((r["added"].as_u64(), r["updated"].as_u64(), r["removed"].as_u64()), (Some(3), Some(0), Some(0)), "{r}");
    assert_eq!(s.catalog.photos().count(), 3);
    assert!(!a.exists(), "nothing was written, nothing read");
    let pa = photo_at(&s, &a).unwrap().clone();
    assert_eq!(r["ids"][hex(1)], pa.id.0);
    assert_eq!((pa.file_name.as_str(), pa.format.as_str(), pa.kind), ("one.ARW", "ARW", lightcraft_catalog::MediaKind::Raw));
    assert_eq!(photo_at(&s, &b).unwrap().kind, lightcraft_catalog::MediaKind::Image);
    assert_eq!((pa.content_hash.as_deref(), pa.width, pa.height, pa.file_size, pa.rating), (Some(hex(1).as_str()), 6000, 4000, 1001, 1));
    assert_eq!((pa.captured.as_deref(), pa.meta.camera.as_str(), pa.meta.lens.as_str()), (Some("2024-05-01T09:30:00"), "ExampleCam X1", "Example 18-135mm"));
    assert!(s.undo.is_empty(), "a sync is not an undo step");
    assert!(!s.pending_log.is_empty() || s.library.is_none(), "but it is journaled");
    // never missing: the originals are on the remote
    assert_eq!(s.execute("library.missing", &json!({})).unwrap(), json!([]));
    assert_eq!(s.execute("photo.smartPreview", &json!({"id": pa.id.0})).unwrap(), json!({"smartPreview": false, "originalOnline": false}));

    // the same manifest again changes nothing
    let r2 = s.execute("library.addRemote", &json!({"root": root.to_string_lossy(), "photos": all})).unwrap();
    assert_eq!((r2["added"].as_u64(), r2["updated"].as_u64(), r2["removed"].as_u64()), (Some(0), Some(0), Some(0)), "{r2}");
    assert_eq!(r2["ids"], r["ids"]);
    assert_eq!(s.catalog.photos().count(), 3);

    // metadata changed on the remote; fields left out stay; a local edit survives
    s.execute("library.select", &json!({"ids": [pa.id.0]})).unwrap();
    s.execute("develop.set", &json!({"control": "light.exposure", "value": 0.5})).unwrap();
    let r3 = s
        .execute(
            "library.addRemote",
            &json!({"root": root.to_string_lossy(), "photos": [{"id": hex(1), "path": "A/one.ARW", "rating": 4, "flag": "pick", "label": "red", "size": 5}, item(2, "A/two.JPG"), item(3, "B/three.jpg")]}),
        )
        .unwrap();
    assert_eq!((r3["added"].as_u64(), r3["updated"].as_u64()), (Some(0), Some(1)), "{r3}");
    let pa2 = photo_at(&s, &a).unwrap();
    assert_eq!((pa2.id, pa2.rating, pa2.flag, pa2.file_size, pa2.width), (pa.id, 4, lightcraft_catalog::Flag::Pick, 5, 6000));
    assert_eq!(pa2.label, Some(lightcraft_catalog::ColorLabel::Red));
    assert_eq!(pa2.meta.camera, "ExampleCam X1", "left out: left alone");
    assert_eq!(pa2.develop.light.exposure, 0.5, "the edit is the phone's");

    // a file moved on the remote keeps its record (and its edits)
    let r4 = s
        .execute(
            "library.addRemote",
            &json!({"root": root.to_string_lossy(), "photos": [item(1, "Moved/one.ARW"), item(2, "A/two.JPG"), item(3, "B/three.jpg")]}),
        )
        .unwrap();
    assert_eq!((r4["added"].as_u64(), r4["updated"].as_u64(), r4["removed"].as_u64()), (Some(0), Some(1), Some(0)), "{r4}");
    assert_eq!(s.catalog.photos().count(), 3);
    assert_eq!(photo_at(&s, &root.join("Moved/one.ARW")).unwrap().id, pa.id);
    assert!(photo_at(&s, &a).is_none());

    // photos that are not listed go only with `prune`
    let two = json!([item(1, "Moved/one.ARW"), item(3, "B/three.jpg")]);
    let r5 = s.execute("library.addRemote", &json!({"root": root.to_string_lossy(), "photos": two})).unwrap();
    assert_eq!((r5["removed"].as_u64(), s.catalog.photos().count()), (Some(0), 3), "{r5}");
    let r6 = s.execute("library.addRemote", &json!({"root": root.to_string_lossy(), "prune": true, "photos": two})).unwrap();
    assert_eq!((r6["removed"].as_u64(), s.catalog.photos().count()), (Some(1), 2), "{r6}");
    assert!(photo_at(&s, &b).is_none() && photo_at(&s, &c).is_some());
    assert!(r6["ids"].get(hex(2)).is_none());

    // a photo outside the root is left alone, and bad entries change nothing
    let other = temp_dir("add-other");
    s.execute("library.addRemote", &json!({"root": other.to_string_lossy(), "prune": true, "photos": []})).unwrap();
    assert_eq!(s.catalog.photos().count(), 2);
    for bad in [json!({"id": hex(9), "path": "../x.jpg"}), json!({"id": hex(9), "path": "/abs.jpg"}), json!({"id": "", "path": "x.jpg"}), json!({"id": hex(9)})] {
        assert!(s.execute("library.addRemote", &json!({"root": root.to_string_lossy(), "photos": [item(7, "ok.jpg"), bad]})).is_err());
    }
    assert!(s.execute("library.addRemote", &json!({"root": "relative", "photos": []})).is_err());
    assert_eq!(s.catalog.photos().count(), 2);
    let _ = std::fs::remove_dir_all(&root);
    let _ = std::fs::remove_dir_all(&other);
}

/// The cloud folders (and where their thumbnails are) and the photos survive closing the library.
#[test]
fn remote_photos_and_roots_persist_with_the_library() {
    let root = temp_dir("persist-root");
    let lib = temp_dir("persist-lib");
    let thumbs = root.join("thumbs");
    let r = {
        let mut s = Session::new().with_fs();
        s.open_library(&lib, false).unwrap();
        s.execute(
            "library.addRemote",
            &json!({"root": root.join("originals").to_string_lossy(), "thumbs": thumbs.to_string_lossy(), "photos": [item(1, "a.jpg"), item(2, "b.jpg")]}),
        )
        .unwrap()
    };
    let mut s = Session::new().with_fs();
    s.open_library(&lib, false).unwrap();
    assert_eq!(s.catalog.photos().count(), 2);
    let id = lightcraft_catalog::PhotoId(r["ids"][hex(1)].as_u64().unwrap());
    assert!(s.catalog.photo(id).is_some());
    let roots = s.media.availability.remotes();
    assert_eq!(roots.len(), 1);
    assert_eq!(roots[0].thumbs.as_deref(), Some(thumbs.to_string_lossy().as_ref()));
    assert_eq!(s.execute("library.missing", &json!({})).unwrap(), json!([]));
    // a second sync without `thumbs` keeps where they are
    s.execute("library.addRemote", &json!({"root": root.join("originals").to_string_lossy(), "photos": []})).unwrap();
    assert_eq!(s.media.availability.remotes(), roots);
    let _ = std::fs::remove_dir_all(&root);
    let _ = std::fs::remove_dir_all(&lib);
}

#[test]
fn remote_photo_renders_from_a_smart_preview_in_either_layout_or_fails_as_not_downloaded() {
    let root = temp_dir("render");
    for two_level in [false, true] {
        let mut s = remote_session(&root);
        s.execute("library.addRemote", &json!({"root": root.to_string_lossy(), "photos": [item(1, "A/one.ARW"), item(2, "A/two.jpg")]})).unwrap();
        let id = photo_at(&s, &root.join("A/one.ARW")).unwrap().id;
        let other = photo_at(&s, &root.join("A/two.jpg")).unwrap().id;
        let err = s.render_now(id, 48, 32).err().expect("no preview: no render");
        assert!(err.starts_with("not downloaded"), "{err}");
        let file = put_smart(&s, id, two_level);
        assert_eq!(s.execute("photo.smartPreview", &json!({"id": id.0})).unwrap()["smartPreview"], true, "a desktop file with a tail counts");
        s.media.forget(id);
        let plain = s.render_now(id, 48, 32).expect("renders from the smart preview").image;
        assert!(plain.width > 0);
        // and edits apply to it
        s.execute("library.select", &json!({"ids": [id.0]})).unwrap();
        s.execute("develop.set", &json!({"control": "light.exposure", "value": 1.5})).unwrap();
        s.media.forget(id);
        assert_ne!(s.render_now(id, 48, 32).unwrap().image.data, plain.data);
        // the other photo has none, which says so rather than failing some other way
        assert!(s.render_now(other, 48, 32).err().unwrap().starts_with("not downloaded"));
        // a damaged one is not used
        let full = std::fs::read(&file).unwrap();
        std::fs::write(&file, &full[..full.len() - 700]).unwrap();
        s.media.forget(id);
        assert!(s.render_now(id, 48, 32).is_err());
        let _ = std::fs::remove_dir_all(s.media.smart_dir.as_ref().unwrap());
    }
    let _ = std::fs::remove_dir_all(&root);
}

/// A solid JPEG `w × h`, as the publisher's thumbnails are.
fn jpeg(w: usize, h: usize, rgb: [u8; 3]) -> Vec<u8> {
    let img = lightcraft_raster::Rgba8 { width: w, height: h, data: vec![[rgb[0], rgb[1], rgb[2], 255]; w * h] };
    lightcraft_codecs::encode_jpeg(&lightcraft_codecs::EncodeImage::rgba8(&img), 90, lightcraft_codecs::ChromaSubsampling::S444, &Default::default()).unwrap()
}

#[test]
fn published_thumbnail_stands_in_until_a_smart_preview_is_there() {
    let root = temp_dir("thumb");
    let thumbs = root.join("mobile/thumbs");
    std::fs::create_dir_all(&thumbs).unwrap();
    let mut s = remote_session(&root);
    s.execute(
        "library.addRemote",
        &json!({"root": root.join("originals").to_string_lossy(), "thumbs": thumbs.to_string_lossy(), "photos": [item(1, "A/one.jpg"), item(2, "A/two.jpg")]}),
    )
    .unwrap();
    let id = photo_at(&s, &root.join("originals/A/one.jpg")).unwrap().id;
    let other = photo_at(&s, &root.join("originals/A/two.jpg")).unwrap().id;
    std::fs::write(thumbs.join(format!("{}.jpg", hex(1))), jpeg(512, 340, [200, 40, 40])).unwrap();
    let thumb = |s: &mut Session, id, edge| s.thumb_job(id, edge).unwrap().run();

    // no original, no smart preview: the published thumbnail, scaled to the grid's size, as it is
    let r = thumb(&mut s, id, 256);
    let img = r.rendered.expect("the published thumbnail");
    assert_eq!(img.image.width.max(img.image.height), 256);
    let px = img.image.data[img.image.data.len() / 2];
    assert!(px[0] > 170 && px[1] < 80 && px[2] < 80, "{px:?}");
    assert!(r.loaded.is_none(), "not kept as the photo's source");
    // edits don't touch it: the remote rendered them already
    s.execute("library.select", &json!({"ids": [id.0]})).unwrap();
    s.execute("develop.set", &json!({"control": "light.exposure", "value": 2.0})).unwrap();
    assert_eq!(thumb(&mut s, id, 256).rendered.unwrap().image.data, img.image.data);
    // 512 is the thumbnail itself
    assert_eq!(thumb(&mut s, id, 512).rendered.unwrap().image.width, 512);
    // a photo without one still says "not downloaded"
    assert!(thumb(&mut s, other, 256).rendered.err().unwrap().starts_with("not downloaded"));

    // once the smart preview is there the photo renders from it, with its edits
    put_smart(&s, id, true);
    let r = thumb(&mut s, id, 256);
    let developed = r.rendered.expect("from the smart preview");
    assert_ne!(developed.image.data, img.image.data);
    assert!(r.loaded.is_some());
    let _ = std::fs::remove_dir_all(&root);
}

#[test]
fn edits_write_the_sidecar_beside_the_absent_original() {
    let root = temp_dir("sidecar");
    let mut s = remote_session(&root);
    s.execute("library.xmpPreferences", &json!({"autoWrite": true})).unwrap();
    s.execute(
        "library.addRemote",
        &json!({"root": root.to_string_lossy(), "photos": [item(1, "Trip/Day 1/IMG_1.ARW"), item(2, "Trip/Day 1/IMG_1.JPG"), item(3, "Trip/IMG_2.jpg")]}),
    )
    .unwrap();
    assert!(!root.join("Trip").exists(), "adding writes nothing");
    let id = |name: &str| s.catalog.photos().find(|p| p.file_name == name).unwrap().id;
    let (raw, jpg, solo) = (id("IMG_1.ARW"), id("IMG_1.JPG"), id("IMG_2.jpg"));
    for (i, rating) in [(raw, 1u64), (jpg, 2), (solo, 3)] {
        s.execute("library.select", &json!({"ids": [i.0]})).unwrap();
        s.execute("photo.rate", &json!({"ids": [i.0], "rating": rating})).unwrap();
    }
    let rating = |f: &str| lightcraft_meta::parse_xmp(&std::fs::read_to_string(root.join(f)).unwrap()).unwrap().metadata.rating;
    // the raw owns the stem; its JPEG partner is named in full; the original is never touched
    assert_eq!(rating("Trip/Day 1/IMG_1.xmp"), Some(1));
    assert_eq!(rating("Trip/Day 1/IMG_1.JPG.xmp"), Some(2));
    assert_eq!(rating("Trip/IMG_2.xmp"), Some(3));
    assert!(!root.join("Trip/IMG_2.jpg").exists());
    // an edit lands in the same sidecar, merged
    s.execute("library.select", &json!({"ids": [solo.0]})).unwrap();
    s.execute("develop.set", &json!({"control": "light.exposure", "value": 0.75})).unwrap();
    let x = std::fs::read_to_string(root.join("Trip/IMG_2.xmp")).unwrap();
    assert!(x.contains("0.75"), "{x}");
    // a local photo's missing folder is not recreated
    let mut s2 = Session::new().with_fs();
    s2.execute("library.xmpPreferences", &json!({"autoWrite": true})).unwrap();
    let gone = temp_dir("sidecar-gone").join("drive/photos");
    s2.execute("library.addRemote", &json!({"root": root.join("other").to_string_lossy(), "photos": [item(5, "x.jpg")]})).unwrap();
    let mut local = (**s2.catalog.photos().next().unwrap()).clone();
    local.id = s2.catalog.alloc_photo_id();
    local.source = lightcraft_catalog::Source::File { path: gone.join("y.jpg").to_string_lossy().to_string() };
    s2.catalog.apply(lightcraft_catalog::Op::AddPhoto { photo: Box::new(local.clone()) }).unwrap();
    assert!(s2.save_sidecar(local.id).is_err());
    assert!(!gone.exists());
    let _ = std::fs::remove_dir_all(&root);
}

#[test]
fn read_only_changed_picks_up_a_downloaded_sidecar_of_an_absent_original() {
    let root = temp_dir("readback");
    let mut s = remote_session(&root);
    s.execute("library.xmpPreferences", &json!({"autoWrite": true})).unwrap();
    s.execute("library.addRemote", &json!({"root": root.to_string_lossy(), "photos": [item(1, "A/one.ARW")]})).unwrap();
    let id = photo_at(&s, &root.join("A/one.ARW")).unwrap().id;
    s.execute("library.select", &json!({"ids": [id.0]})).unwrap();
    let params = json!({"ids": [id.0], "onlyChanged": true});
    // no sidecar yet (and no original to take embedded XMP from)
    let r = s.execute("photo.readMetadataFromFile", &params).unwrap();
    assert_eq!(r["failed"].as_array().unwrap().len(), 1, "{r}");
    // the sync downloads the PC's sidecar
    let foreign = r#"<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmlns:crs="http://ns.adobe.com/camera-raw-settings/1.0/" xmp:Rating="5" crs:Exposure2012="+0.60"/>
</rdf:RDF></x:xmpmeta>"#;
    std::fs::create_dir_all(root.join("A")).unwrap();
    std::fs::write(root.join("A/one.xmp"), foreign).unwrap();
    let r = s.execute("photo.readMetadataFromFile", &params).unwrap();
    assert_eq!(r["read"].as_array().unwrap().len(), 1, "{r}");
    let p = s.catalog.photo(id).unwrap();
    assert_eq!(p.rating, 5);
    assert!((p.develop.light.exposure - 0.6).abs() < 1e-6);
    assert_eq!(std::fs::read_to_string(root.join("A/one.xmp")).unwrap(), foreign, "not written back");
    // read again: nothing new
    let r = s.execute("photo.readMetadataFromFile", &params).unwrap();
    assert_eq!((r["read"].as_array().unwrap().len(), r["unchanged"].as_array().unwrap().len()), (0, 1), "{r}");
    let _ = std::fs::remove_dir_all(&root);
}

/// Smart previews made by desktop LightCraft (kept out of the repository): they decode, and are
/// complete by `is_valid`. They are looked for in `$LIGHTCRAFT_DESKTOP_LCSP_DIR`, else in the
/// cloud publisher's test state; skipped when there are none on this machine.
#[test]
fn real_desktop_smart_previews_decode() {
    fn find(dir: &Path, out: &mut Vec<PathBuf>) {
        for e in std::fs::read_dir(dir).into_iter().flatten().flatten() {
            let p = e.path();
            if p.is_dir() {
                find(&p, out);
            } else if p.extension().is_some_and(|x| x == "lcsp") {
                out.push(p);
            }
        }
    }
    let dir = std::env::var_os("LIGHTCRAFT_DESKTOP_LCSP_DIR")
        .map(PathBuf::from)
        .or_else(|| std::env::var_os("HOME").map(|h| Path::new(&h).join(".local/state/lightcraft-cloud-test/mobile/previews")));
    let mut files = Vec::new();
    find(&dir.unwrap_or_default(), &mut files);
    for f in &files {
        assert!(crate::smart::is_valid(f), "{}", f.display());
        let (img, ..) = crate::smart::decode(&std::fs::read(f).unwrap()).unwrap();
        assert!(img.width > 0 && img.height > 0);
    }
    eprintln!("decoded {} desktop smart preview(s)", files.len());
}
