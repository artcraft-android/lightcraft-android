use super::*;

fn temp_dir(tag: &str) -> std::path::PathBuf {
    let d = std::env::temp_dir().join(format!("lc-mobile-{tag}-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&d);
    std::fs::create_dir_all(&d).unwrap();
    d
}

fn first_photo(s: &MobileSession) -> u64 {
    let photos = s.photos();
    assert!(!photos.is_empty(), "the demo library has photos");
    photos[0].id
}

fn control(s: &MobileSession, id: &str) -> DevelopControl {
    s.develop_controls().into_iter().find(|c| c.id == id).unwrap()
}

#[test]
fn demo_lists_photos_controls_and_commands() {
    let s = MobileSession::demo();
    let id = first_photo(&s);
    s.open_photo(id).unwrap();
    assert_eq!(s.active_photo(), Some(id));
    let commands = s.commands();
    assert!(commands.iter().any(|c| c.id == "develop.set" && c.enabled));
    assert!(commands.iter().any(|c| c.id == "edit.undo" && !c.enabled && c.disabled_reason.is_some()));
    let exposure = control(&s, "light.exposure");
    assert_eq!((exposure.section.as_str(), exposure.section_label.as_str()), ("light", "Light"));
    assert_eq!((exposure.min, exposure.max, exposure.default_value), (-5.0, 5.0, 0.0));
    assert_eq!(exposure.track, SliderTrack::Centered);
    // demo photos are rendered images: relative white balance, like the desktop's
    assert!(s.develop_controls().iter().all(|c| c.id != "wb.temp"));
    let temp = control(&s, RELATIVE_TEMP);
    assert_eq!((temp.min, temp.max, temp.value, temp.track), (-100.0, 100.0, 0.0, SliderTrack::Temp));
    s.set_control(RELATIVE_TEMP.into(), 40.0).unwrap();
    assert!((control(&s, RELATIVE_TEMP).value - 40.0).abs() < 1e-6);
    assert!(s.execute("develop.get".into(), String::new()).unwrap().contains("\"temp\""));
    s.reset_control(RELATIVE_TEMP.into()).unwrap();
    assert!(control(&s, RELATIVE_TEMP).value.abs() < 1e-6);
    let tint = control(&s, "wb.tint");
    assert_eq!((tint.min, tint.max), (-100.0, 100.0));
}

/// The editor's Undo reaches back only to where the photo was opened: a library step before it (an
/// album here) stays out of its reach, and comes back once the editor is left.
#[test]
fn the_editors_undo_stops_where_the_photo_was_opened() {
    let s = MobileSession::demo();
    let id = first_photo(&s);
    s.execute("album.create".into(), r#"{"name": "Before"}"#.into()).unwrap();
    assert!(s.history().undo.is_some(), "the library step is undoable from the library");
    s.open_photo(id).unwrap();
    assert_eq!(s.history().undo, None, "nothing of this photo's to undo yet");
    assert_eq!(s.undo().unwrap().undo, None, "undo does nothing");
    assert!(s.library_overview().albums.iter().any(|a| a.name == "Before"), "the album stays");
    s.set_control("light.exposure".into(), 0.5).unwrap();
    assert!(s.history().undo.is_some());
    let after = s.undo().unwrap();
    assert_eq!((after.undo, after.redo.is_some()), (None, true), "the edit is undone, and no further");
    assert!(s.library_overview().albums.iter().any(|a| a.name == "Before"));
    s.close_editor();
    assert!(s.history().undo.is_some(), "out of the editor the library step is undoable again");
}

/// A tool's Cancel takes back every step since it opened, for good: Redo doesn't offer them, and
/// the steps before the tool stay, however many steps (or none) it made.
#[test]
fn discarded_steps_are_not_redoable() {
    let s = MobileSession::demo();
    let id = first_photo(&s);
    s.open_photo(id).unwrap();
    s.set_control("light.exposure".into(), 0.5).unwrap();
    let mark = s.history_depth();
    assert_eq!(s.discard_to(mark).unwrap().undo.as_deref(), Some("Exposure"), "nothing done: nothing taken back");
    s.execute("crop.set".into(), r#"{"rect": [0.0, 0.25, 1.0, 0.5]}"#.into()).unwrap();
    s.execute("crop.straighten".into(), r#"{"angle": 10}"#.into()).unwrap();
    let h = s.discard_to(mark).unwrap();
    assert_eq!((h.undo.is_some(), h.redo), (true, None), "the exposure stays; nothing to redo");
    assert_eq!(control(&s, "light.exposure").value, 0.5);
    assert_eq!(s.history_depth(), mark);
    assert_eq!(s.discard_to(0).unwrap().undo, None, "no further back than the photo's opening");
    assert_eq!(control(&s, "light.exposure").value, 0.0);
}

/// The export sheet's sizes follow the crop: full size is the cropped size, and a long edge stays
/// what it says.
#[test]
fn export_sizes_follow_the_crop() {
    let s = MobileSession::demo();
    let p = s.photos().into_iter().find(|p| p.width > p.height).unwrap();
    s.open_photo(p.id).unwrap();
    assert_eq!(s.export_size(p.id, String::new()).unwrap(), ImageSize { width: p.width, height: p.height });
    s.execute("crop.aspect".into(), r#"{"aspect": "1x1"}"#.into()).unwrap();
    assert_eq!(s.export_size(p.id, String::new()).unwrap(), ImageSize { width: p.height, height: p.height });
    let small = s.export_size(p.id, r#"{"longEdge": 2048}"#.into()).unwrap();
    assert_eq!((small.width, small.height), (2048, 2048));
}

/// Cancel with a finger still down: the open adjustment is taken back too, and nothing before.
#[test]
fn discarding_ends_an_open_adjustment_first() {
    let s = MobileSession::demo();
    let id = first_photo(&s);
    s.open_photo(id).unwrap();
    s.set_control("light.exposure".into(), 0.5).unwrap();
    let mark = s.history_depth();
    s.begin_adjustment("Contrast".into()).unwrap();
    s.set_control("light.contrast".into(), 30.0).unwrap();
    let h = s.discard_to(mark).unwrap();
    assert_eq!((h.undo.as_deref(), h.redo), (Some("Exposure"), None));
    assert_eq!((control(&s, "light.contrast").value, control(&s, "light.exposure").value), (0.0, 0.5));
}

/// An untouched gallery copy discarded while the next photo is already in the editor: that photo
/// stays the one being edited, and Undo doesn't offer the discard.
#[test]
fn discarding_a_photo_keeps_the_editor_and_its_history() {
    let s = MobileSession::demo();
    let photos = s.photos();
    let (a, b) = (photos[0].id, photos[1].id);
    s.open_photo(b).unwrap();
    s.discard_photo(a).unwrap();
    assert!(s.photos().iter().all(|p| p.id != a), "a is gone");
    assert_eq!(s.active_photo(), Some(b));
    assert_eq!(s.history().undo, None, "nothing of b's to undo, and not the discard");
    s.set_control("light.exposure".into(), 0.5).unwrap();
    assert_eq!(s.history().undo.as_deref(), Some("Exposure"));
    assert_eq!(s.undo().unwrap().undo, None);
}

/// A message's Undo takes back the step it reported, and nothing else: not once a later step came.
#[test]
fn undo_at_takes_back_only_its_own_step() {
    let s = MobileSession::demo();
    let id = first_photo(&s);
    s.execute("album.create".into(), r#"{"name": "First"}"#.into()).unwrap();
    let first = s.history_depth();
    s.execute("album.create".into(), r#"{"name": "Second"}"#.into()).unwrap();
    assert!(!s.undo_at(first).unwrap(), "a later step came: nothing is undone");
    let names = |s: &MobileSession| s.library_overview().albums.iter().map(|a| a.name.clone()).collect::<Vec<_>>();
    assert!(names(&s).contains(&"First".to_string()) && names(&s).contains(&"Second".to_string()));
    let second = s.history_depth();
    assert!(s.undo_at(second).unwrap());
    assert!(!names(&s).contains(&"Second".to_string()) && names(&s).contains(&"First".to_string()));
    assert!(s.undo_at(first).unwrap(), "now the first is the newest again");
    // in the editor, no further back than the photo's opening
    s.execute("album.create".into(), r#"{"name": "Third"}"#.into()).unwrap();
    let third = s.history_depth();
    s.open_photo(id).unwrap();
    assert!(!s.undo_at(third).unwrap());
}

#[test]
fn a_slider_drag_is_one_undo_step() {
    let s = MobileSession::demo();
    let id = first_photo(&s);
    s.open_photo(id).unwrap();
    let before = control(&s, "light.exposure").value;
    let hash = s.photo(id).unwrap().settings_hash;
    s.begin_adjustment("Exposure".into()).unwrap();
    for v in [0.25, 0.5, 1.25] {
        s.set_control("light.exposure".into(), v).unwrap();
    }
    s.end_adjustment().unwrap();
    assert_eq!(control(&s, "light.exposure").value, 1.25);
    assert_ne!(s.photo(id).unwrap().settings_hash, hash, "thumbnail caches see the edit");
    assert_eq!(s.history(), EditHistory { undo: Some("Exposure".into()), redo: None });
    let h = s.undo().unwrap();
    assert_eq!(control(&s, "light.exposure").value, before);
    assert_eq!(h.redo.as_deref(), Some("Exposure"));
    s.redo().unwrap();
    assert_eq!(control(&s, "light.exposure").value, 1.25);
    s.reset_control("light.exposure".into()).unwrap();
    assert_eq!(control(&s, "light.exposure").value, 0.0);
}

/// Given a guide drawn on the crop view, when it's added through the view's mapping, then the
/// photo is straightened along it (the view shows it upright) and the view's points map back to
/// the guide the photo keeps; a point off any photo maps to nothing.
#[test]
fn guided_upright_guides_stay_where_they_were_drawn() {
    let s = MobileSession::demo();
    let id = first_photo(&s);
    s.open_photo(id).unwrap();
    assert!(s.upright_guides(id).is_empty());
    let a = s.guide_point(id, 0.2, 0.1);
    let b = s.guide_point(id, 0.25, 0.9);
    s.execute("geometry.guides".into(), json!({"guides": [[a[0], a[1], b[0], b[1]]], "add": true}).to_string()).unwrap();
    let g = s.upright_guides(id);
    assert_eq!(g.len(), 4);
    assert!((g[0] - g[2]).abs() < 1e-3, "one guide makes its line upright: {g:?}");
    let back = [s.guide_point(id, g[0], g[1]), s.guide_point(id, g[2], g[3])];
    for (got, want) in back.iter().flatten().zip([a[0], a[1], b[0], b[1]]) {
        assert!((got - want).abs() < 1e-6, "{back:?}");
    }
    // a second guide: the correction now warps the view, and both guides still map back to where they were drawn
    let c = s.guide_point(id, 0.8, 0.1);
    let d = s.guide_point(id, 0.75, 0.9);
    s.execute("geometry.guides".into(), json!({"guides": [[c[0], c[1], d[0], d[1]]], "add": true}).to_string()).unwrap();
    let g = s.upright_guides(id);
    assert_eq!(g.len(), 8);
    assert!(s.guide_point(u64::MAX, 0.5, 0.5).is_empty());
    assert!(s.guide_point(id, f64::NAN, 0.5).is_empty());
}

#[test]
fn renders_fit_the_requested_size() {
    let s = MobileSession::demo();
    let id = first_photo(&s);
    s.open_photo(id).unwrap();
    for (draft, before) in [(false, false), (true, false), (false, true)] {
        let img = s.render_view(id, 96, 64, draft, before, None).unwrap();
        assert!(img.width <= 96 && img.height <= 64 && (img.width == 96 || img.height == 64), "{}x{}", img.width, img.height);
        assert_eq!(img.rgba.len(), img.width as usize * img.height as usize * 4);
        assert!(img.rgba.as_chunks::<4>().0.iter().all(|px| px[3] == 255), "opaque");
        assert_eq!(img.histogram.luma.len(), 256);
        assert_eq!(img.draft, draft);
    }
    let thumb = s.render_thumbnail(id, 100).unwrap();
    assert_eq!(thumb.width.max(thumb.height), 128, "rounded up to a cached size");
    s.release_view();
}

#[test]
fn quick_views_and_prefetch_prepare_the_editor() {
    let s = MobileSession::demo();
    let photos = s.photos();
    let (a, b) = (photos[0].id, photos[1].id);
    // nothing rendered yet: a thumbnail-size stand-in
    let quick = s.render_quick_view(a, 1000).unwrap();
    assert!(quick.width.max(quick.height) <= 512 && quick.draft, "{}x{}", quick.width, quick.height);
    // prefetched: the photo's own view render is there at once
    s.prefetch_view(b, 120, 80);
    let view = s.render_quick_view(b, 1000).unwrap();
    assert_eq!((view.width.max(view.height), view.photo), (120, b));
    assert!(s.render_quick_view(u64::MAX, 64).is_none());
    s.prefetch_view(u64::MAX, 64, 64);
    // the before view keeps its own stages: switching back and forth renders both
    s.open_photo(a).unwrap();
    for before in [false, true, false, true] {
        assert!(s.render_view(a, 64, 64, false, before, None).is_ok());
    }
}

#[test]
fn presets_profiles_and_the_panel_state() {
    let s = MobileSession::demo();
    let id = first_photo(&s);
    s.open_photo(id).unwrap();
    let presets = s.presets();
    assert!(presets.len() >= 20 && presets.iter().all(|p| p.builtin && !p.group.is_empty()), "{}", presets.len());
    let thumb = s.render_preset_thumbnail(id, presets[0].id.clone(), 96).unwrap();
    assert_eq!(thumb.width.max(thumb.height), 96);
    assert!(matches!(s.render_preset_thumbnail(id, "no-such-preset".into(), 96), Err(MobileError::Command(_))));
    s.apply_preset(presets[0].id.clone(), 50.0).unwrap();
    assert!(s.history().undo.is_some_and(|u| u.starts_with("Preset:")));
    let profiles = s.profiles();
    assert!(profiles.iter().any(|p| p.id == "lc.color") && profiles.iter().any(|p| p.id == "lc.mono"));
    assert!(s.render_profile_thumbnail(id, "lc.mono".into(), 64).is_ok());
    assert!(s.render_profile_thumbnail(id, "no.such.profile".into(), 64).is_err());
    let modes = white_balance_modes();
    assert_eq!(modes[0], Choice { id: "asShot".into(), label: "As Shot".into() });
    assert_eq!(modes.last().map(|m| m.id.as_str()), Some("custom"));
    s.execute("develop.wb".into(), r#"{"mode": "daylight"}"#.into()).unwrap();
    s.execute("develop.treatment".into(), r#"{"bw": true}"#.into()).unwrap();
    let st = s.develop_state().unwrap();
    assert_eq!((st.wb_mode.as_str(), st.black_and_white, st.vignette_style.as_str()), ("daylight", true, "highlightPriority"));
    s.execute("develop.profile".into(), r#"{"id": "lc.vivid"}"#.into()).unwrap();
    s.execute("develop.treatment".into(), r#"{"bw": false}"#.into()).unwrap();
    let st = s.develop_state().unwrap();
    assert_eq!((st.profile.as_str(), st.profile_name.as_str(), st.black_and_white), ("lc.vivid", "Vivid", false));
    assert!(s.open_photo(u64::MAX).is_err(), "an unknown photo is an error");
    s.execute("library.select".into(), r#"{"ids": []}"#.into()).unwrap();
    assert!(s.develop_state().is_none(), "nothing is being edited");
}

#[test]
fn the_title_menu_counts_views_and_albums() {
    let s = MobileSession::demo();
    let all = s.photos().len() as u32;
    let o = s.library_overview();
    assert_eq!(o.all, all);
    assert_eq!(o.edited + o.unedited, all, "every photo is edited or not");
    assert_eq!(o.deleted, 0);
    // the filter's places (the demo's are location lines), with the counts the filter agrees with
    let place = o.places.first().expect("the demo photos have places");
    let shown = s.execute("library.filter".into(), format!(r#"{{"place": "{}"}}"#, place.name.to_uppercase())).unwrap();
    assert!(shown.contains(&format!("\"count\":{}", place.count)), "{shown} for {place:?}");
    s.execute("library.clearFilter".into(), "{}".into()).unwrap();
    // a crop to a quarter of the height makes the photo four times as wide for its height;
    // deleting one moves it to Recently Deleted
    let id = first_photo(&s);
    let before = s.photo(id).unwrap().aspect;
    s.open_photo(id).unwrap();
    s.execute("crop.set".into(), r#"{"rect": [0.0, 0.25, 1.0, 0.5]}"#.into()).unwrap();
    let edited = s.photo(id).unwrap();
    assert!(edited.edited && edited.modified.is_some(), "{edited:?}");
    assert!((edited.aspect / before - 4.0).abs() < 0.05, "{before} → {}", edited.aspect);
    s.execute("album.create".into(), r#"{"name": "Trip", "addSelected": true}"#.into()).unwrap();
    s.execute("photo.delete".into(), format!(r#"{{"ids": [{}]}}"#, s.photos()[1].id)).unwrap();
    let o = s.library_overview();
    assert_eq!((o.all, o.deleted, o.edited + o.unedited), (all - 1, 1, all - 1));
    let trip = o.albums.iter().find(|a| a.name == "Trip").unwrap();
    assert_eq!((trip.count, trip.cover, trip.smart), (1, Some(id), false));
    assert!(s.photos().iter().all(|p| p.aspect > 0.0 && !p.imported.is_empty()));
}

#[test]
fn export_encodes_a_file_without_writing_it() {
    let s = MobileSession::demo();
    let id = first_photo(&s);
    let jpeg = s.export(id, r#"{"format": "jpeg", "quality": 80, "longEdge": 64}"#.into()).unwrap();
    assert_eq!(jpeg.mime_type, "image/jpeg");
    assert!(jpeg.file_name.to_ascii_lowercase().ends_with(".jpg"), "{}", jpeg.file_name);
    assert_eq!(&jpeg.bytes[..2], &[0xFF, 0xD8]);
    assert_eq!(jpeg.width.max(jpeg.height), 64);
    let png = s.export(id, r#"{"format": "png", "longEdge": 32}"#.into()).unwrap();
    assert_eq!((png.mime_type.as_str(), &png.bytes[1..4]), ("image/png", &b"PNG"[..]));
}

#[test]
fn bad_input_is_an_error_not_a_crash() {
    let s = MobileSession::demo();
    assert!(matches!(s.execute("no.such.command".into(), "{}".into()), Err(MobileError::Command(_))));
    assert!(matches!(s.execute("photo.rate".into(), "{not json".into()), Err(MobileError::Command(_))));
    assert!(s.open_photo(u64::MAX).is_err(), "an unknown photo is an error, not a crash");
    s.execute("library.select".into(), r#"{"ids": []}"#.into()).unwrap();
    assert!(s.active_photo().is_none(), "nothing selected");
    assert!(s.set_control("light.exposure".into(), 1.0).is_err(), "no photo is being edited");
    s.open_photo(first_photo(&s)).unwrap();
    assert!(s.set_control("no.such.control".into(), 1.0).is_err());
    for v in [f64::NAN, f64::INFINITY] {
        assert!(matches!(s.set_control("light.exposure".into(), v), Err(MobileError::Command(m)) if m.contains("not a value")));
    }
    s.set_control("light.exposure".into(), 99.0).unwrap();
    assert_eq!(control(&s, "light.exposure").value, 5.0, "clamped to the range");
    assert!(matches!(s.render_view(u64::MAX, 64, 64, false, false, None), Err(MobileError::NoPhoto(_))));
    assert!(matches!(s.render_thumbnail(u64::MAX, 64), Err(MobileError::NoPhoto(_))));
    assert!(s.render_view(first_photo(&s), 0, 0, false, false, None).is_ok(), "a zero size renders one pixel");
    assert!(s.export(u64::MAX, String::new()).is_err());
    assert!(s.export(first_photo(&s), "[".into()).is_err());
    assert!(matches!(MobileSession::demo().redo(), Err(MobileError::Command(_))), "nothing to redo");
    for dir in ["", "relative/library"] {
        assert!(matches!(MobileSession::open(dir.into(), false), Err(MobileError::Library(_))), "{dir:?}");
    }
}

#[test]
fn a_library_on_disk_keeps_edits_and_imports() {
    let dir = temp_dir("library");
    let lib = dir.join("library").to_string_lossy().to_string();
    let id = {
        let s = MobileSession::open(lib.clone(), true).unwrap();
        let id = first_photo(&s);
        s.open_photo(id).unwrap();
        s.set_control("light.exposure".into(), 0.75).unwrap();
        // a file to import: one of the demo photos as a JPEG
        let jpeg = s.export(id, r#"{"format": "jpeg", "longEdge": 48}"#.into()).unwrap();
        let file = dir.join("incoming.jpg");
        std::fs::write(&file, &jpeg.bytes).unwrap();
        let report = s.import(vec![file.to_string_lossy().to_string()], true, false).unwrap();
        assert_eq!(report.imported.len(), 1, "{report:?}");
        assert!(!file.exists(), "moved into the library");
        let new = s.photo(report.imported[0]).unwrap();
        assert_eq!((new.format.as_str(), new.raw), ("JPEG", false));
        assert!(s.render_thumbnail(new.id, 128).is_ok());
        // the same photo again (another copy of it): a duplicate that names the photo it is
        let again = dir.join("again.jpg");
        std::fs::write(&again, &jpeg.bytes).unwrap();
        let report = s.import(vec![again.to_string_lossy().to_string()], true, false).unwrap();
        assert_eq!((report.imported.len(), report.duplicates), (0, 1), "{report:?}");
        assert_eq!(report.existing, vec![new.id]);
        // a gallery photo opened and left untouched goes again, its copy with it
        let copy = dir.join("copy.jpg");
        std::fs::write(&copy, &jpeg.bytes).unwrap();
        s.execute("photo.deletePermanently".into(), format!(r#"{{"ids": [{}]}}"#, new.id)).unwrap();
        let report = s.import(vec![copy.to_string_lossy().to_string()], true, false).unwrap();
        let back = report.imported[0];
        let path = match &s.lock().catalog.photo(PhotoId(back)).unwrap().source {
            catalog::Source::File { path } => std::path::PathBuf::from(path),
            other => panic!("{other:?}"),
        };
        assert!(path.starts_with(std::path::Path::new(&lib).join("Originals")) && path.exists(), "{path:?}");
        s.discard_photo(back).unwrap();
        assert!(!path.exists(), "its copy deleted");
        assert!(s.photo(back).is_none());
        assert!(s.photos().iter().all(|p| p.id != back));
        assert!(s.discard_photo(back).is_err(), "gone already");
        // Deleted's Delete takes the library's copy with it (it once stayed, and a re-import got a `-1` name)
        let third = dir.join("third.jpg");
        std::fs::write(&third, &jpeg.bytes).unwrap();
        let report = s.import(vec![third.to_string_lossy().to_string()], true, false).unwrap();
        let gone = report.imported[0];
        let kept = match &s.lock().catalog.photo(PhotoId(gone)).unwrap().source {
            catalog::Source::File { path } => std::path::PathBuf::from(path),
            other => panic!("{other:?}"),
        };
        assert!(kept.exists());
        s.execute("photo.delete".into(), format!(r#"{{"ids": [{gone}]}}"#)).unwrap();
        assert_eq!(s.delete_permanently(vec![gone]).unwrap(), 1);
        assert!(!kept.exists(), "its copy deleted");
        assert!(s.photo(gone).is_none());
        // the same file brought in again lands where the first copy was, under the same name
        let again = dir.join("third.jpg");
        std::fs::write(&again, &jpeg.bytes).unwrap();
        let report = s.import(vec![again.to_string_lossy().to_string()], true, false).unwrap();
        let back = match &s.lock().catalog.photo(PhotoId(report.imported[0])).unwrap().source {
            catalog::Source::File { path } => std::path::PathBuf::from(path),
            other => panic!("{other:?}"),
        };
        assert_eq!(back, kept, "the same name again, no `-1`");
        // a duplicate, asked to (the Gallery's Import duplicate): a photo of its own
        let dup = dir.join("dup.jpg");
        std::fs::write(&dup, &jpeg.bytes).unwrap();
        let report = s.import(vec![dup.to_string_lossy().to_string()], true, true).unwrap();
        assert_eq!((report.imported.len(), report.duplicates), (1, 0), "{report:?}");
        assert!(!dup.exists(), "moved into the library");
        id
    };
    let s = MobileSession::open(lib, true).unwrap();
    s.open_photo(id).unwrap();
    assert_eq!(control(&s, "light.exposure").value, 0.75, "saved as it was made");
    assert!(MobileSession::open(dir.join("library").to_string_lossy().to_string(), false).is_err(), "locked while open");
    drop(s);
    let _ = std::fs::remove_dir_all(&dir);
}

#[test]
fn the_crop_tool_sees_the_crop_over_the_whole_photo() {
    let s = MobileSession::demo();
    let id = first_photo(&s);
    s.open_photo(id).unwrap();
    let f = s.crop_frame(id).unwrap();
    assert_eq!(f.rect, vec![0.0, 0.0, 1.0, 1.0]);
    assert_eq!((f.angle, f.aspect_w, f.aspect_h), (0.0, 0, 0));
    assert_eq!(f.corners, vec![0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0]);
    // the right half: its corners are where the rectangle says (no straightening)
    s.execute("crop.set".into(), r#"{"rect": [0.5, 0.0, 1.0, 1.0]}"#.into()).unwrap();
    let f = s.crop_frame(id).unwrap();
    assert!((f.corners[0] - 0.5).abs() < 1e-9 && (f.corners[2] - 1.0).abs() < 1e-9, "{:?}", f.corners);
    // the crop view is the whole photo, the view only its right half
    let whole = s.render_crop_view(id, 200, 200, false).unwrap();
    let cropped = s.render_view(id, 200, 200, false, false, None).unwrap();
    let wide = |i: &RenderedImage| i.width as f64 / i.height as f64;
    assert!((wide(&whole) / wide(&cropped) - 2.0).abs() < 0.1, "{}×{} vs {}×{}", whole.width, whole.height, cropped.width, cropped.height);
    // straightened, the corners turn about the middle
    s.execute("crop.straighten".into(), r#"{"angle": 10}"#.into()).unwrap();
    let f = s.crop_frame(id).unwrap();
    assert!((f.angle - 10.0).abs() < 1e-9);
    assert!(f.corners.iter().any(|c| (c - c.round()).abs() > 1e-3), "{:?}", f.corners);
    assert!(s.crop_frame(u64::MAX).is_none());
}

#[test]
fn remove_spots_and_the_view_geometry() {
    let s = MobileSession::demo();
    let id = first_photo(&s);
    s.open_photo(id).unwrap();
    // uncropped, the view is the photo
    let g = s.view_geometry(id).unwrap();
    let at = |g: &ViewGeometry, u: f64, v: f64| {
        (g.to_photo[0] * u + g.to_photo[2] * v + g.to_photo[4], g.to_photo[1] * u + g.to_photo[3] * v + g.to_photo[5])
    };
    let (x, y) = at(&g, 0.5, 0.5);
    assert!((x - 0.5).abs() < 1e-6 && (y - 0.5).abs() < 1e-6, "{x} {y}");
    // cropped to the right half: the view's left edge is the photo's middle
    s.execute("crop.set".into(), r#"{"rect": [0.5, 0.0, 1.0, 1.0]}"#.into()).unwrap();
    let g = s.view_geometry(id).unwrap();
    let (x, _) = at(&g, 0.0, 0.5);
    assert!((x - 0.5).abs() < 1e-3, "{x}");
    assert!(s.spots(id).is_empty());
    s.execute("spot.add".into(), r#"{"mode": "heal", "points": [[0.7, 0.4], [0.72, 0.42]], "size": 0.03}"#.into()).unwrap();
    let spots = s.spots(id);
    assert_eq!(spots.len(), 1);
    assert_eq!((spots[0].mode.as_str(), spots[0].points.len(), spots[0].size), ("heal", 4, 0.03));
    assert_eq!(s.selected_spot(), Some(0));
    assert!(s.view_geometry(u64::MAX).is_none() && s.spots(u64::MAX).is_empty());
}

#[test]
fn masks_list_their_shape_and_adjustments() {
    let s = MobileSession::demo();
    let id = first_photo(&s);
    s.open_photo(id).unwrap();
    assert!(s.masks(id).is_empty() && s.selected_mask().is_none());
    s.execute("mask.add".into(), r#"{"kind": "radial", "center": [0.4, 0.5], "rx": 0.2, "ry": 0.1}"#.into()).unwrap();
    s.execute("mask.adjust".into(), r#"{"values": {"exposure": 1.0}}"#.into()).unwrap();
    let m = s.masks(id);
    assert_eq!(m.len(), 1);
    assert_eq!((m[0].kind.as_str(), m[0].parts, m[0].visible), ("radial", 1, true));
    let shape: Value = serde_json::from_str(&m[0].shape).unwrap();
    assert_eq!(shape["rx"], 0.2);
    let adjust: Value = serde_json::from_str(&m[0].adjust).unwrap();
    assert_eq!(adjust["exposure"], 1.0);
    assert_eq!(s.selected_mask(), Some(m[0].id));
    // a part taken away from it: listed with its own shape
    s.execute("mask.addComponent".into(), r#"{"op": "subtract", "kind": "radial", "center": [0.4, 0.5], "rx": 0.05, "ry": 0.05}"#.into()).unwrap();
    let parts = &s.masks(id)[0].components;
    assert_eq!(parts.len(), 2);
    assert_eq!((parts[0].op.as_str(), parts[1].op.as_str(), parts[1].kind.as_str()), ("add", "subtract", "radial"));
    let small: Value = serde_json::from_str(&parts[1].shape).unwrap();
    assert_eq!(small["rx"], 0.05);
    // the view with the mask shown over it differs from the view, as each mode, colour and opacity asks
    let overlay = |view: &str, color: u32, opacity: u8| Some(MaskOverlay { id: m[0].id, view: view.into(), color, opacity });
    let plain = s.render_view(id, 96, 96, false, false, None).unwrap();
    let red = s.render_view(id, 96, 96, false, false, overlay("color", 0xFF0000, 50)).unwrap();
    assert_ne!(plain.rgba, red.rgba);
    let blue = s.render_view(id, 96, 96, false, false, overlay("color", 0x0000FF, 50)).unwrap();
    assert_ne!(red.rgba, blue.rgba, "the colour asked");
    let faint = s.render_view(id, 96, 96, false, false, overlay("color", 0xFF0000, 10)).unwrap();
    assert_ne!(red.rgba, faint.rgba, "the opacity asked");
    // white on black: only greys, white in the mask's middle and black in the corner
    let wb = s.render_view(id, 96, 96, false, false, overlay("whiteOnBlack", 0, 50)).unwrap();
    assert!(wb.rgba.as_chunks::<4>().0.iter().all(|px| px[0] == px[1] && px[1] == px[2]));
    assert_eq!(&wb.rgba[..3], &[0, 0, 0]);
    // an unknown mode is the colour overlay; an opacity past 100 is 100
    let odd = s.render_view(id, 96, 96, false, false, overlay("sparkles", 0xFF0000, 50)).unwrap();
    assert_eq!(odd.rgba, red.rgba);
    let full = s.render_view(id, 96, 96, false, false, overlay("color", 0xFF0000, 100)).unwrap();
    assert_eq!(s.render_view(id, 96, 96, false, false, overlay("color", 0xFF0000, 255)).unwrap().rgba, full.rgba);
}

#[test]
fn the_curve_tool_sees_the_curves_as_the_engine_applies_them() {
    let s = MobileSession::demo();
    let id = first_photo(&s);
    s.open_photo(id).unwrap();
    let c = s.tone_curve(id).unwrap();
    assert!(c.master.is_empty() && c.red.is_empty());
    assert_eq!(c.parametric.len(), CURVE_SAMPLES);
    // a straight parametric curve: y = x
    assert!(c.parametric.iter().enumerate().all(|(i, y)| (y - i as f64 / (CURVE_SAMPLES - 1) as f64).abs() < 1e-3));
    assert_eq!(c.preset.as_deref(), Some("Linear"));
    assert!(c.presets.iter().any(|p| p == "Strong Contrast"));
    // a point curve, set as the Curve tool does
    s.execute("develop.curve".into(), r#"{"channel": "master", "points": [[0, 0], [0.5, 0.6], [1, 1]]}"#.into()).unwrap();
    let c = s.tone_curve(id).unwrap();
    assert_eq!(c.master, vec![0.0, 0.0, 0.5, 0.6, 1.0, 1.0]);
    assert_eq!(c.preset, None);
    // drawn through its points, never above 1
    let line = point_curve(c.master.clone(), 5);
    assert_eq!(line.len(), 5);
    assert!((line[2] - 0.6).abs() < 1e-9 && (line[4] - 1.0).abs() < 1e-9);
    assert!(line.windows(2).all(|w| w[1] >= w[0]));
    // shadows lifted: the parametric curve rises in the dark end
    s.set_control("curve.shadows".into(), 60.0).unwrap();
    let c = s.tone_curve(id).unwrap();
    assert!(c.parametric[16] > 16.0 / 128.0 + 0.01, "{}", c.parametric[16]);
    // too few points: a straight line; odd numbers ignore the last
    assert_eq!(point_curve(vec![0.3], 3), vec![0.0, 0.5, 1.0]);
}

/// A .cube imported (`profile.import`) is one of the profiles: listed in its group, its look
/// rendered for the browser, and still there when the library opens again.
#[test]
fn imported_luts_are_profiles_too() {
    let dir = temp_dir("luts");
    let lib = dir.join("library").to_string_lossy().to_string();
    let cube_path = dir.join("Warm.cube");
    let n = 5;
    let mut cube = String::from("LUT_3D_SIZE 5\n");
    for b in 0..n {
        for g in 0..n {
            for r in 0..n {
                let f = |v: usize| v as f32 / (n - 1) as f32;
                cube.push_str(&format!("{} {} {}\n", (f(r) * 1.1).min(1.0), f(g), f(b) * 0.9));
            }
        }
    }
    std::fs::write(&cube_path, &cube).unwrap();
    let id = {
        let s = MobileSession::open(lib.clone(), true).unwrap();
        let photo = first_photo(&s);
        let r = s.execute("profile.import".into(), serde_json::json!({"paths": [cube_path.to_string_lossy()]}).to_string()).unwrap();
        assert!(r.contains("\"imported\""), "{r}");
        let p = s.profiles().into_iter().find(|p| p.id.starts_with("lut:")).expect("the imported profile is listed");
        assert_eq!((p.name.as_str(), p.group.as_str()), ("Warm", "Imported"));
        assert!(s.render_profile_thumbnail(photo, p.id.clone(), 48).is_ok());
        p.id
    };
    let s = MobileSession::open(lib, false).unwrap();
    assert!(s.profiles().iter().any(|p| p.id == id), "the library keeps it");
    let _ = std::fs::remove_dir_all(&dir);
}

#[test]
fn versions_and_edits_carry_the_phones_time() {
    // the engine's default clock is a fixed test date; the phone's sessions use the real one
    let fixed = "2026-09-30T12:00:00";
    let s = MobileSession::demo();
    let id = first_photo(&s);
    s.open_photo(id).unwrap();
    s.execute("version.create".into(), r#"{"name": "Now"}"#.into()).unwrap();
    let v: serde_json::Value = serde_json::from_str(&s.execute("photo.inspect".into(), format!(r#"{{"id": {id}}}"#)).unwrap()).unwrap();
    let created = v["versions"].as_array().and_then(|a| a.last()).and_then(|x| x["created"].as_str()).unwrap_or_default().to_string();
    assert!(!created.is_empty() && created != fixed, "{created}");
    let lib = temp_dir("clock").to_string_lossy().into_owned();
    let s = MobileSession::open(lib, false).unwrap();
    assert_ne!((s.lock().clock)(), fixed);
}

#[test]
fn folders_come_with_their_albums() {
    let s = MobileSession::demo();
    let f: serde_json::Value =
        serde_json::from_str(&s.execute("album.create".into(), r#"{"name": "Trips", "folder": true}"#.into()).unwrap()).unwrap();
    let folder = f["id"].as_u64().unwrap();
    let a: serde_json::Value =
        serde_json::from_str(&s.execute("album.create".into(), format!(r#"{{"name": "Rome", "parent": {folder}}}"#)).unwrap()).unwrap();
    let album = a["id"].as_u64().unwrap();
    let o = s.library_overview();
    let trips = o.albums.iter().find(|x| x.id == folder).unwrap();
    assert!(trips.folder && trips.count == 1 && trips.parent.is_none());
    let rome = o.albums.iter().find(|x| x.id == album).unwrap();
    assert!(!rome.folder && rome.parent == Some(folder));
}

/// A zoomed view renders only the window on screen: it is that part of the view rendered whole at
/// the frame's size, and costs the window's pixels whatever the zoom.
#[test]
fn a_view_window_is_that_part_of_the_whole_frame() {
    let s = MobileSession::demo();
    let id = first_photo(&s);
    s.open_photo(id).unwrap();
    let whole = s.render_view(id, 900, 900, false, false, None).unwrap();
    let (w, h) = (whole.width, whole.height);
    let (x, y, ww, wh) = (w / 3, h / 4, 160, 120);
    let win = s.render_view_window(id, w, h, x, y, ww, wh, false, None).unwrap().expect("no spots: never refused");
    assert_eq!((win.width, win.height), (ww, wh));
    let mut diff = 0u64;
    for j in 0..wh {
        for i in 0..ww {
            let a = (((y + j) * w + x + i) * 4) as usize;
            let b = ((j * ww + i) * 4) as usize;
            for c in 0..3 {
                diff += u64::from(whole.rgba[a + c].abs_diff(win.rgba[b + c]));
            }
        }
    }
    let mean = diff as f64 / f64::from(ww * wh * 3);
    assert!(mean < 3.0, "the window matches the whole render there (mean difference {mean:.2})");
    // zoomed far in, the window is still only what was asked for
    let deep = s.render_view_window(id, w * 6, h * 6, w * 3, h * 3, 1080, 1500, false, None).unwrap().unwrap();
    assert_eq!((deep.width, deep.height), (1080, 1500));
    // a window past the frame's edge is cut to the frame
    let edge = s.render_view_window(id, w, h, w - 50, h - 40, 400, 400, false, None).unwrap().unwrap();
    assert_eq!((edge.width, edge.height), (50, 40));
    assert!(matches!(s.render_view_window(u64::MAX, w, h, 0, 0, 10, 10, false, None), Err(MobileError::NoPhoto(_))));
}

/// The folder library: a photo file from the demo library (the `n`th photo, so each file differs).
fn demo_file(dir: &std::path::Path, name: &str, n: usize) -> std::path::PathBuf {
    let demo = MobileSession::demo();
    let jpeg = demo.export(demo.photos()[n].id, r#"{"format": "jpeg", "longEdge": 64}"#.into()).unwrap();
    let file = dir.join(name);
    std::fs::create_dir_all(dir).unwrap();
    std::fs::write(&file, &jpeg.bytes).unwrap();
    file
}

/// A library on disk that writes sidecars and watches `folder`, as the app sets it up.
fn folder_library(tag: &str, folder: &std::path::Path) -> Arc<MobileSession> {
    let lib = temp_dir(tag).join("library").to_string_lossy().to_string();
    let s = MobileSession::open(lib, false).unwrap();
    s.execute("library.xmpPreferences".into(), r#"{"autoWrite": true}"#.into()).unwrap();
    s.execute("library.autoImport".into(), json!({"folder": folder, "copy": false}).to_string()).unwrap();
    s
}

fn exposure_of(s: &MobileSession, name: &str) -> f64 {
    let id = s.photos().into_iter().find(|p| p.file_name == name).unwrap().id;
    s.open_photo(id).unwrap();
    let v = control(s, "light.exposure").value;
    s.close_editor();
    v
}

#[test]
fn an_in_place_import_leaves_the_files_and_reads_their_sidecars() {
    let dir = temp_dir("inplace");
    let folder = dir.join("Camera");
    let (a, b) = (demo_file(&folder, "a.jpg", 0), demo_file(&folder.join("trip"), "b.jpg", 1));
    // a's sidecar comes from another library that edited it
    {
        let other = folder_library("inplace-other", &folder);
        other.import(vec![a.to_string_lossy().to_string()], false, false).unwrap();
        let id = other.photos()[0].id;
        other.open_photo(id).unwrap();
        other.set_control("light.exposure".into(), 0.75).unwrap();
    }
    assert!(folder.join("a.xmp").is_file(), "the other library wrote a sidecar next to the photo");
    let s = folder_library("inplace-lib", &folder);
    let report = s.import(vec![folder.to_string_lossy().to_string()], false, false).unwrap();
    assert_eq!(report.imported.len(), 2, "subfolders too: {report:?}");
    assert!(a.is_file() && b.is_file(), "nothing moved");
    assert!(!dir.join("inplace-lib").join("library").join("Originals").exists(), "and nothing copied into the library");
    assert!((exposure_of(&s, "a.jpg") - 0.75).abs() < 1e-6, "the sidecar's lc:settings");
    assert!(exposure_of(&s, "b.jpg").abs() < 1e-6, "no sidecar: untouched");
}

#[test]
fn with_auto_write_an_edit_goes_to_the_sidecar_next_to_the_photo() {
    let dir = temp_dir("autowrite");
    let folder = dir.join("Camera");
    let a = demo_file(&folder, "a.jpg", 0);
    let s = folder_library("autowrite-lib", &folder);
    let id = s.import(vec![a.to_string_lossy().to_string()], false, false).unwrap().imported[0];
    s.open_photo(id).unwrap();
    s.set_control("light.exposure".into(), 0.5).unwrap();
    let xmp = std::fs::read_to_string(folder.join("a.xmp")).unwrap();
    assert!(xmp.contains("lc:settings"), "{xmp}");
    // and another library reading it gets the edit
    let other = folder_library("autowrite-other", &folder);
    other.import(vec![a.to_string_lossy().to_string()], false, false).unwrap();
    assert!((exposure_of(&other, "a.jpg") - 0.5).abs() < 1e-6);
}

#[test]
fn a_sidecar_changed_from_outside_is_read_by_the_sync_and_an_unchanged_one_is_not() {
    let dir = temp_dir("sync");
    let folder = dir.join("Camera");
    let a = demo_file(&folder, "a.jpg", 0);
    let b = demo_file(&folder, "b.jpg", 1);
    let s = folder_library("sync-lib", &folder);
    let first = s.sync_folder(folder.to_string_lossy().to_string()).unwrap();
    assert_eq!(first.added.len(), 2, "{first:?}");
    assert!(first.updated.is_empty(), "no sidecar yet: {first:?}");
    // an edit here writes a.xmp; the sync looks at it once and finds nothing new in it
    let a_id = s.photos().into_iter().find(|p| p.file_name == "a.jpg").unwrap().id;
    s.open_photo(a_id).unwrap();
    s.set_control("light.exposure".into(), 0.5).unwrap();
    s.close_editor();
    let depth = s.history_depth();
    let again = s.sync_folder(folder.to_string_lossy().to_string()).unwrap();
    assert_eq!((again.checked, again.updated.len(), again.added.len()), (1, 0, 0), "{again:?}");
    assert_eq!(s.history_depth(), depth, "nothing read: no undo step");
    let none = s.sync_folder(folder.to_string_lossy().to_string()).unwrap();
    assert_eq!((none.checked, none.updated.len()), (0, 0), "an unchanged sidecar is not looked at again: {none:?}");
    // another app edits a.xmp (lc:settings from another library) and b.xmp (a foreign crs: edit)
    {
        let other = folder_library("sync-other", &folder);
        other.import(vec![a.to_string_lossy().to_string()], false, false).unwrap();
        let id = other.photos()[0].id;
        other.open_photo(id).unwrap();
        other.set_control("light.exposure".into(), -0.25).unwrap();
    }
    std::fs::write(
        b.with_extension("xmp"),
        r#"<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmlns:crs="http://ns.adobe.com/camera-raw-settings/1.0/"
    xmp:Rating="4" crs:Exposure2012="+1.10"/>
</rdf:RDF></x:xmpmeta>"#,
    )
    .unwrap();
    let synced = s.sync_folder(folder.to_string_lossy().to_string()).unwrap();
    assert_eq!(synced.updated.len(), 2, "{synced:?}");
    assert!((exposure_of(&s, "a.jpg") + 0.25).abs() < 1e-6, "lc:settings");
    assert!((exposure_of(&s, "b.jpg") - 1.1).abs() < 1e-6, "crs:Exposure2012");
    assert_eq!(s.photos().into_iter().find(|p| p.file_name == "b.jpg").unwrap().rating, 4);
    // the change is one undo step, and syncing again finds nothing more
    assert_eq!(s.history_depth(), depth + 1);
    let quiet = s.sync_folder(folder.to_string_lossy().to_string()).unwrap();
    assert!(quiet.updated.is_empty() && quiet.added.is_empty(), "{quiet:?}");
}

/// A sidecar another app or a sync tool just wrote must not be rewritten by reading it: the sync
/// tool would see a new version, hand it back, and the two would keep passing it to each other.
#[test]
fn reading_a_sidecar_in_the_sync_does_not_write_it_back() {
    let dir = temp_dir("pingpong");
    let folder = dir.join("Camera");
    let a = demo_file(&folder, "a.jpg", 0);
    let s = folder_library("pingpong-lib", &folder);
    s.sync_folder(folder.to_string_lossy().to_string()).unwrap();
    // a foreign sidecar with something of its own in it, and a rating different from the library's
    let foreign = r#"<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description rdf:about="" xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmlns:crs="http://ns.adobe.com/camera-raw-settings/1.0/"
    xmp:Rating="2" crs:Exposure2012="+0.65"/>
</rdf:RDF></x:xmpmeta>"#;
    let sidecar = a.with_extension("xmp");
    std::fs::write(&sidecar, foreign).unwrap();
    let mtime = std::fs::metadata(&sidecar).unwrap().modified().unwrap();
    let synced = s.sync_folder(folder.to_string_lossy().to_string()).unwrap();
    assert_eq!(synced.updated.len(), 1, "{synced:?}");
    assert_eq!(std::fs::read_to_string(&sidecar).unwrap(), foreign, "byte for byte what the other app wrote");
    assert_eq!(std::fs::metadata(&sidecar).unwrap().modified().unwrap(), mtime);
    // while an edit made here is written, as before
    let id = s.photos()[0].id;
    s.open_photo(id).unwrap();
    s.set_control("light.exposure".into(), 0.1).unwrap();
    assert_ne!(std::fs::read_to_string(&sidecar).unwrap(), foreign);
}

// ---------------------------------------------------------------------------------------------
// cloud library (a folder on this computer stands in for the remote; needs the host librclone)

fn host_librclone() -> bool {
    std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("librclone-host/librclone.so").is_file()
}

fn cloud_hex(n: u8) -> String {
    format!("{:032x}", u128::from(n) * 0x0101_0101_0101_0101)
}

/// A published library with a JPEG + raw pair (the JPEG is the photo) and one more photo.
fn cloud_remote(dir: &std::path::Path) -> std::path::PathBuf {
    let remote = dir.join("pc");
    let put = |rel: &str, bytes: &[u8]| {
        let p = remote.join(rel);
        std::fs::create_dir_all(p.parent().unwrap()).unwrap();
        std::fs::write(p, bytes).unwrap();
    };
    let entry = |n: u8, path: &str, sidecar: &str| {
        put(&format!("originals/{path}"), b"original");
        put(&format!("mobile/thumbs/{}.jpg", cloud_hex(n)), b"thumb");
        put(&format!("mobile/previews/{}.lcsp", cloud_hex(n)), b"preview");
        serde_json::json!({"id": cloud_hex(n), "path": path, "sidecar": sidecar, "sidecarVersion": null, "width": 6000, "height": 4000,
            "thumb": format!("thumbs/{}.jpg", cloud_hex(n)), "preview": format!("previews/{}.lcsp", cloud_hex(n)), "previewKey": cloud_hex(n + 100), "previewBytes": 7})
    };
    let mut pair = entry(1, "A/shot.JPG", "A/shot.JPG.xmp");
    pair["alternates"] = serde_json::json!([entry(2, "A/shot.ARW", "A/shot.xmp")]);
    let photos = serde_json::json!([pair, entry(3, "B/solo.jpg", "B/solo.xmp")]);
    put("mobile/manifest.json", serde_json::json!({"format": 1, "generated": "x", "photos": photos}).to_string().as_bytes());
    remote
}

#[test]
fn the_cloud_library_connects_refreshes_sends_edits_and_comes_back_after_a_restart() {
    if !host_librclone() {
        eprintln!("skipped: scripts/build-librclone.sh host has not been run");
        return;
    }
    let base = temp_dir("cloud");
    let remote = cloud_remote(&base);
    let conf = base.join("rclone.conf");
    std::fs::write(&conf, "").unwrap();
    let lib = base.join("lib");
    let s = MobileSession::open(lib.to_string_lossy().to_string(), false).unwrap();
    let st = s.cloud_status();
    assert!(!st.connected && st.remote.is_empty());
    assert!(matches!(s.cloud_refresh(), Err(MobileError::Cloud(_))));
    // not a cloud library / bad names are refused up front
    assert!(s.cloud_connect(conf.to_string_lossy().to_string(), base.to_string_lossy().to_string(), "phone".into()).is_err());
    assert!(s.cloud_connect(conf.to_string_lossy().to_string(), remote.to_string_lossy().to_string(), "bad name!".into()).is_err());
    s.cloud_connect(conf.to_string_lossy().to_string(), remote.to_string_lossy().to_string(), "pixel-9".into()).unwrap();
    assert!(s.cloud_status().connected);

    let r = s.cloud_refresh().unwrap();
    assert_eq!((r.total, r.added, r.hidden.len(), r.thumbs.len()), (3, 3, 1, 3), "{r:?}");
    assert_eq!(s.cloud_hidden_photos(), r.hidden);
    let shown: Vec<PhotoInfo> = s.photos().into_iter().filter(|p| !r.hidden.contains(&p.id)).collect();
    assert_eq!(shown.len(), 2);
    let st = s.cloud_status();
    assert_eq!((st.photos, st.hidden, st.pending_uploads, st.device.as_str()), (2, 1, 0, "pixel-9"));
    assert!(st.last_refresh.is_some() && st.last_error.is_none());
    assert_eq!(s.cloud_refresh().unwrap().thumbs.len(), 0, "nothing new the second time");

    // an original and a preview come down
    let solo = shown.iter().find(|p| p.file_name == "solo.jpg").unwrap().id;
    let path = s.cloud_download_original(solo).unwrap();
    assert_eq!(std::fs::read(&path).unwrap(), b"original");
    assert!(s.cloud_open(solo).unwrap().downloaded);
    assert!(!s.cloud_open(solo).unwrap().downloaded);
    assert!(s.cloud_status().cache_bytes > 0);
    s.cloud_set_cache_cap(1).unwrap();
    assert_eq!(s.cloud_status().cache_bytes, 0);

    // an edit goes to the inbox once
    s.execute("library.select".into(), format!("{{\"ids\": [{solo}]}}")).unwrap();
    s.execute("photo.rate".into(), format!("{{\"ids\": [{solo}], \"rating\": 4}}")).unwrap();
    assert_eq!(s.cloud_status().pending_uploads, 1);
    assert_eq!(s.cloud_push().unwrap(), 1);
    assert_eq!(s.cloud_push().unwrap(), 0);
    let sent: Vec<String> = std::fs::read_dir(remote.join("inbox/pixel-9")).unwrap().flatten().map(|e| e.file_name().to_string_lossy().into_owned()).collect();
    assert_eq!(sent.len(), 1);
    assert!(sent[0].starts_with(&cloud_hex(3)) && sent[0].ends_with("-none.xmp"), "{sent:?}");
    assert_eq!(s.cloud_status().pending_uploads, 0);

    // a new launch connects again by itself
    drop(s);
    let s = MobileSession::open(lib.to_string_lossy().to_string(), false).unwrap();
    let st = s.cloud_status();
    assert!(st.connected && st.remote == remote.to_string_lossy() && st.photos == 2, "{st:?}");
    assert_eq!(s.cloud_push().unwrap(), 0);
    s.cloud_disconnect().unwrap();
    assert!(!s.cloud_status().connected);
    drop(s);
    let s = MobileSession::open(lib.to_string_lossy().to_string(), false).unwrap();
    assert!(!s.cloud_status().connected, "disconnected stays disconnected");
    let _ = std::fs::remove_dir_all(&base);
}
