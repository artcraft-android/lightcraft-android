//! V1 and V2 render as they did before process V3, on every platform: 16-bit samples of small
//! synthetic renders, captured on this repository's main at 246cd25f (before process V3 existed here;
//! upstream's own capture, at its 76b5377, has the tone model rendered photos had before issue #7), within
//! [`LSB`]. `tests_light::v1_and_v2_render_as_before_v3` checks the same byte for byte, but only
//! on macOS aarch64, where its hashes were captured.

use lightcraft_develop::{DevelopSettings, ProcessVersion, controls};
use lightcraft_raster::Rgb32f;

use crate::{DeepSamples, OutputDepth, RenderRequest, SourceInfo, render};

/// 480 × 320, arithmetic only (no platform maths): luminance rising over 12 stops left to right,
/// four colour bands top to bottom, thin bright and dark lines every 16 px (so V2's
/// Highlights / Shadows median has something to keep), and a few clipped highlights.
fn scene() -> Rgb32f {
    Rgb32f::from_fn(480, 320, |x, y| {
        let v = 0.0002 * 1.0175f32.powi(x as i32);
        let c = match y / 80 {
            0 => [v, v, v],
            1 => [v * 1.3, v * 0.8, v * 0.4],
            2 => [v * 0.35, v * 0.9, v * 1.4],
            _ => [v * 0.9, v * 0.3, v * 1.1],
        };
        if x % 16 == 7 {
            c.map(|k| k * 2.5)
        } else if y % 16 == 11 {
            c.map(|k| k * 0.35)
        } else if x > 440 && y % 40 < 3 {
            [4.0, 4.0, 4.0]
        } else {
            c
        }
    })
}

fn camera_look() -> SourceInfo {
    let knots = std::array::from_fn(|i| {
        let x = 0.004 * 1.18f32.powi(i as i32);
        [x, 1.0 - (-2.0 * x).exp()]
    });
    let curve = crate::tone::CameraTone::new(knots).unwrap().with_chroma([1.3, 1.2, 1.1, 1.0, 0.9, 0.7, 0.5, 0.4]).unwrap();
    SourceInfo { raw: true, as_shot_temp: 5200.0, as_shot_tint: 4.0, camera_tone: Some(curve), ..Default::default() }
}

/// Where the samples are taken (output pixels of the 480 × 320 render).
const AT: [(usize, usize); 12] =
    [(20, 20), (120, 30), (232, 40), (300, 27), (400, 60), (470, 1), (60, 100), (200, 125), (350, 140), (90, 200), (263, 210), (420, 290)];

/// 16-bit samples of V1 and V2 renders (the same order as `LEGACY`): three sources (rendered,
/// raw, raw with a camera look) × two edits (plain, a typical edit with Contrast, Highlights,
/// Shadows, Whites, Blacks and Clarity) × two processes.
fn legacy_samples() -> Vec<[u16; 3]> {
    let infos = [SourceInfo::default(), SourceInfo { raw: true, as_shot_temp: 5200.0, as_shot_tint: 4.0, ..Default::default() }, camera_look()];
    let mut edited = DevelopSettings::for_raw(5200.0, 4.0);
    for (id, v) in [
        ("light.exposure", 0.4),
        ("light.contrast", 30.0),
        ("light.highlights", -60.0),
        ("light.shadows", 40.0),
        ("light.whites", 15.0),
        ("light.blacks", -20.0),
        ("effects.clarity", 20.0),
    ] {
        controls::set(&mut edited, id, v);
    }
    let req = RenderRequest { depth: OutputDepth::U16, ..RenderRequest::fit(480, 480) };
    let src = scene();
    let mut out = Vec::new();
    for info in &infos {
        for s in [DevelopSettings::for_raw(5200.0, 4.0), edited.clone()] {
            for process in [ProcessVersion(1), ProcessVersion(2)] {
                let s = DevelopSettings { process, ..s.clone() };
                let deep = render(&src, info, &s, &req).deep.unwrap();
                let DeepSamples::U16(v) = deep.samples else { panic!("16-bit samples") };
                for (x, y) in AT {
                    let i = (y * deep.width + x) * 3;
                    out.push([v[i], v[i + 1], v[i + 2]]);
                }
            }
        }
    }
    out
}

/// Largest difference allowed from the captured samples, in 16-bit steps. The renders call the
/// platform's `powf` / `exp2` / `log2`, whose last bit can differ between platforms: a relative
/// change of ~1e-7, far below one step (1.5e-5), so a sample moves by at most one step where it
/// sits at a rounding boundary. A change of how V1 or V2 render moves samples by far more.
const LSB: u16 = 2;

#[test]
fn v1_and_v2_samples_are_as_before_v3_on_every_platform() {
    let got = legacy_samples();
    assert_eq!(got.len(), LEGACY.len());
    let worst = got.iter().zip(&LEGACY).flat_map(|(g, w)| (0..3).map(move |k| g[k].abs_diff(w[k]))).max().unwrap_or(0);
    for (i, (g, w)) in got.iter().zip(&LEGACY).enumerate() {
        assert!((0..3).all(|k| g[k].abs_diff(w[k]) <= LSB), "sample {i}: {g:?}, captured {w:?} (worst {worst})");
    }
}

/// [`legacy_samples`] on this repository's main at 246cd25f.
#[rustfmt::skip]
const LEGACY: [[u16; 3]; 144] = [
    [240, 240, 240], [1222, 1222, 1222], [6573, 6573, 6573], [6543, 6543, 6543], [33991, 33991, 33991], [65528, 65528, 65528],
    [796, 355, 165], [6369, 3523, 1681], [28782, 19565, 13220], [0, 778, 1186], [0, 17361, 21361], [41680, 18315, 40925],
    [240, 240, 240], [1222, 1222, 1222], [6573, 6573, 6573], [6543, 6543, 6543], [33991, 33991, 33991], [65528, 65528, 65528],
    [796, 355, 165], [6369, 3523, 1681], [28782, 19565, 13220], [0, 778, 1186], [0, 17361, 21361], [41680, 18315, 40925],
    [239, 239, 239], [1481, 1481, 1481], [6059, 6059, 6059], [3652, 3652, 3652], [34870, 34870, 34870], [65535, 65535, 65535],
    [1167, 394, 117], [6891, 3280, 1158], [31090, 19136, 11081], [0, 1095, 1942], [0, 21765, 28211], [46481, 15351, 45914],
    [239, 239, 239], [1477, 1477, 1477], [6048, 6048, 6048], [3483, 3483, 3483], [34884, 34884, 34884], [65535, 65535, 65535],
    [1165, 393, 116], [6877, 3272, 1154], [31087, 19134, 11080], [0, 1092, 1938], [0, 22410, 28975], [46505, 15365, 45936],
    [272, 272, 272], [1384, 1384, 1384], [7098, 7098, 7098], [7293, 7293, 7293], [34521, 34521, 34521], [65535, 65535, 65535],
    [902, 403, 187], [6894, 3899, 1901], [29974, 20395, 13814], [0, 882, 1344], [0, 18117, 22284], [42788, 18920, 41935],
    [272, 272, 272], [1384, 1384, 1384], [7098, 7098, 7098], [7293, 7293, 7293], [34521, 34521, 34521], [65535, 65535, 65535],
    [902, 403, 187], [6894, 3899, 1901], [29974, 20395, 13814], [0, 882, 1344], [0, 18117, 22284], [42788, 18920, 41935],
    [42, 42, 42], [423, 423, 423], [5474, 5474, 5474], [1794, 1794, 1794], [35001, 35001, 35001], [65535, 65535, 65535],
    [179, 80, 37], [4707, 2342, 1085], [30140, 20513, 13901], [0, 214, 326], [0, 21758, 26622], [43921, 19470, 43047],
    [42, 42, 42], [421, 421, 421], [5451, 5451, 5451], [1435, 1435, 1435], [34965, 34965, 34965], [65535, 65535, 65535],
    [179, 80, 37], [4678, 2322, 1076], [30135, 20509, 13898], [0, 214, 326], [0, 23041, 28152], [43908, 19464, 43035],
    [477, 477, 477], [2433, 2433, 2433], [9924, 9924, 9924], [10170, 10170, 10170], [42194, 42194, 42194], [65533, 65533, 65533],
    [1800, 660, 167], [10387, 5624, 1736], [39549, 25689, 14987], [0, 1636, 2690], [0, 24143, 30331], [54940, 22090, 53814],
    [477, 477, 477], [2433, 2433, 2433], [9924, 9924, 9924], [10170, 10170, 10170], [42194, 42194, 42194], [65533, 65533, 65533],
    [1800, 660, 167], [10387, 5624, 1736], [39549, 25689, 14987], [0, 1636, 2690], [0, 24143, 30331], [54940, 22090, 53814],
    [650, 650, 650], [4289, 4289, 4289], [10185, 10185, 10185], [7026, 7026, 7026], [43008, 43008, 43008], [65535, 65535, 65535],
    [3136, 1123, 262], [12402, 6616, 1841], [41801, 25493, 12931], [0, 3187, 4855], [0, 29433, 38166], [59897, 19108, 59229],
    [650, 650, 650], [4281, 4281, 4281], [10164, 10164, 10164], [6606, 6606, 6606], [42970, 42970, 42970], [65535, 65535, 65535],
    [3136, 1123, 262], [12377, 6600, 1835], [41795, 25489, 12928], [0, 3186, 4854], [0, 30987, 40098], [59883, 19099, 59216],
];
