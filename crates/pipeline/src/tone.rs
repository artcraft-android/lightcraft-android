//! The global tone map: scene luminance → display-linear luminance.
//!
//! Built in the log domain around middle grey (0.18): contrast scales log-exposure about grey,
//! whites move the shoulder (white point), blacks move the toe. The shoulder is an extended
//! Reinhard curve so highlights roll off smoothly instead of clipping.
//!
//! Rendered (display-referred) sources such as JPEGs use [`ToneMap::jpeg`] instead: [`JpegTone`]'s
//! curve, identity at neutral settings (an unedited JPEG renders exactly as the file). With a camera
//! curve or [`JpegTone`], Contrast is a per-channel shift in scene EV before the tone map
//! ([`contrast_ev`], applied in `finish`) and Whites/Blacks are adjustments in a gamma-2.2 perceptual
//! domain with a short shoulder above 0.95.

pub const GREY: f32 = 0.18;
/// The tone LUT spans `LUT_MIN_EV..LUT_MAX_EV` around grey in `LUT_N` steps.
pub const LUT_MIN_EV: f32 = -14.0;
pub const LUT_MAX_EV: f32 = 10.0;
pub const LUT_N: usize = 4096;
/// Nodes of a camera chroma curve, evenly spaced over display luminance 0..=1.
pub const CHROMA_N: usize = 8;
const NO_CHROMA: [f32; CHROMA_N] = [1.0; CHROMA_N];

/// A file-local camera look, fitted independently of the scene-linear colour transform.
/// Knots are scene/display-linear luminance pairs. Keeping this in the finish stage preserves
/// RAW exposure and highlight headroom; it is never baked into the decoded sensor pixels.
/// `chroma` scales colourfulness by display luminance after the curve (a camera's per-channel
/// curve saturates shadows and bleaches highlights toward white, which a luminance curve can't).
#[derive(Clone, Copy, Debug, PartialEq, serde::Serialize)]
pub struct CameraTone {
    knots: [[f32; 2]; 32],
    chroma: [f32; CHROMA_N],
}

impl<'de> serde::Deserialize<'de> for CameraTone {
    fn deserialize<D: serde::Deserializer<'de>>(d: D) -> Result<Self, D::Error> {
        #[derive(serde::Deserialize)]
        struct Wire {
            knots: [[f32; 2]; 32],
            // absent in smart previews written before the chroma curve existed
            #[serde(default)]
            chroma: Option<[f32; CHROMA_N]>,
        }
        let w = Wire::deserialize(d)?;
        let tone = Self::new(w.knots).ok_or_else(|| serde::de::Error::custom("invalid camera tone curve"))?;
        match w.chroma {
            Some(c) => tone.with_chroma(c).ok_or_else(|| serde::de::Error::custom("invalid camera chroma curve")),
            None => Ok(tone),
        }
    }
}

impl CameraTone {
    pub fn new(knots: [[f32; 2]; 32]) -> Option<Self> {
        let mut previous = [0.0, 0.0];
        for p in knots {
            if !p.iter().all(|v| v.is_finite()) || p[0] <= previous[0] || p[1] < previous[1] || p[1] >= 1.0 {
                return None;
            }
            previous = p;
        }
        Some(Self { knots, chroma: NO_CHROMA })
    }

    /// The curve with chroma scales at display luminance 0, 1/7 … 1 (each finite, 0..=4).
    pub fn with_chroma(self, chroma: [f32; CHROMA_N]) -> Option<Self> {
        chroma.iter().all(|k| k.is_finite() && (0.0..=4.0).contains(k)).then_some(Self { chroma, ..self })
    }

    pub fn chroma(&self) -> &[f32; CHROMA_N] {
        &self.chroma
    }

    pub fn apply(&self, y: f32) -> f32 {
        if !y.is_finite() || y <= 0.0 {
            return 0.0;
        }
        let mut previous = [0.0, 0.0];
        for p in self.knots {
            if y <= p[0] {
                let t = (y - previous[0]) / (p[0] - previous[0]);
                return previous[1] + t * (p[1] - previous[1]);
            }
            previous = p;
        }
        let a = self.knots[30];
        let b = self.knots[31];
        // Extend beyond observed (unclipped) highlights with a continuous, bounded shoulder.
        let slope = ((b[1] - a[1]) / (b[0] - a[0])).clamp(0.1, 16.0);
        1.0 - (1.0 - b[1]) * (-(y - b[0]) * slope / (1.0 - b[1]).max(0.01)).exp()
    }
}

/// The scene scale rendered (non-raw) photos are developed on, as a raw is. A decoded JPEG's
/// display-linear luminance is expanded onto it ([`JpegTone::to_scene`]: display grey stays at
/// [`GREY`], display white lands about 3.5 EV above), the develop works there (white balance,
/// exposure, the local tools), and [`ToneMap::jpeg`] brings the result back with the forward curve,
/// so a photo with no edits comes out unchanged. The curve is a smooth shoulder,
/// `y = ((1 + k)·u / (u + k))^q` with `u = (S / scale)^p`: exposure then rolls highlights off into
/// white instead of clipping them, and white balance moves tones as far as the rendered photo's
/// own curve allows. Its three numbers were fitted (issue 7) to how far edits move tones in
/// Lightroom's output, measured on our own test chart; no curve or table is taken from it. Above
/// [`JpegTone::TOP_Y`] the inverse goes on as a gentler power law: the shoulder's own inverse
/// climbs so steeply into white that Exposure −2 left white at 225 where Lightroom shows 198.
pub struct JpegTone;

impl JpegTone {
    const P: f32 = 2.1634;
    const K: f32 = 0.0322;
    const Q: f32 = 0.6142;
    /// Display-linear level (8-bit 243) above which the inverse is `S = S(TOP_Y)·(y / TOP_Y)^TOP_SLOPE`.
    const TOP_Y: f32 = 0.8963;
    const TOP_SLOPE: f32 = 4.49;

    /// The shape's three numbers (`p`, `k`, `q`), its scale, and the top's level, scene value and
    /// slope, for the GPU.
    pub fn params() -> [f32; 7] {
        [Self::P, Self::K, Self::Q, Self::scale(), Self::TOP_Y, Self::top_scene(), Self::TOP_SLOPE]
    }

    /// The shoulder's scene unit (`u = 1`).
    pub fn scale() -> f32 {
        GREY / Self::unit_scene(GREY)
    }

    /// The scene value of display white.
    pub fn white() -> f32 {
        Self::to_scene(1.0)
    }

    fn top_scene() -> f32 {
        Self::unit_scene(Self::TOP_Y) * Self::scale()
    }

    /// The inverse on a white = 1 scale.
    fn unit_scene(y: f32) -> f32 {
        let r = y.clamp(0.0, 1.0).powf(1.0 / Self::Q);
        let u = Self::K * r / (1.0 + Self::K - r).max(1e-6);
        u.powf(1.0 / Self::P)
    }

    /// Display-linear luminance (0..=1) → scene luminance.
    pub fn to_scene(y: f32) -> f32 {
        if y.is_nan() || y <= 0.0 {
            0.0
        } else if y <= Self::TOP_Y {
            Self::unit_scene(y) * Self::scale()
        } else {
            Self::top_scene() * (y.min(1.0) / Self::TOP_Y).powf(Self::TOP_SLOPE)
        }
    }

    /// Scene luminance → display-linear luminance (white and beyond: 1).
    pub fn to_display(s: f32) -> f32 {
        if s.is_nan() || s <= 0.0 {
            return 0.0;
        }
        let top = Self::top_scene();
        if s > top {
            return (Self::TOP_Y * (s / top).powf(1.0 / Self::TOP_SLOPE)).min(1.0);
        }
        let u = (s / Self::scale()).min(1.0).powf(Self::P);
        ((1.0 + Self::K) * u / (u + Self::K)).powf(Self::Q)
    }

    /// A display-linear Rec.2020 pixel on the scene scale: each of its channels in the [`wide`] space
    /// through the curve's inverse (the finish tones them back the same way), so a grey stays grey.
    pub fn expand(c: [f32; 3]) -> [f32; 3] {
        let w = wide();
        w.from_wide(w.to_wide(c).map(Self::to_scene))
    }
}

/// Linear Rec.2020 ↔ the wide RGB space a rendered photo is toned in channel by channel: ProPhoto's
/// primaries with Rec.2020's white (Bradford). A tone curve on each of these channels, as raw
/// developers apply theirs, fades bright colours towards white yet keeps a saturated colour
/// saturated, where a luminance curve can do only one of the two; on a grey it is the luminance
/// curve. Chosen (issue 7) because it halves the colour error of Temp, Exposure and Contrast against
/// Lightroom's output on our test chart (Rec.2020 or sRGB primaries do worse).
pub struct Wide {
    to: [[f32; 3]; 3],
    from: [[f32; 3]; 3],
}

impl Wide {
    pub fn to_wide(&self, c: [f32; 3]) -> [f32; 3] {
        mul3(&self.to, c)
    }

    pub fn from_wide(&self, c: [f32; 3]) -> [f32; 3] {
        mul3(&self.from, c)
    }

    /// Both matrices (Rec.2020 → wide, wide → Rec.2020), row-major, for the GPU.
    pub fn words(&self) -> [f32; 18] {
        let mut out = [0.0; 18];
        for (o, v) in out.iter_mut().zip(self.to.iter().chain(&self.from).flatten()) {
            *o = *v;
        }
        out
    }
}

pub fn wide() -> &'static Wide {
    static WIDE: std::sync::OnceLock<Wide> = std::sync::OnceLock::new();
    WIDE.get_or_init(|| {
        use lightcraft_color::{PROPHOTO, REC2020};
        Wide { to: REC2020.to_space(&PROPHOTO).to_f32(), from: PROPHOTO.to_space(&REC2020).to_f32() }
    })
}

fn mul3(m: &[[f32; 3]; 3], c: [f32; 3]) -> [f32; 3] {
    [
        m[0][0] * c[0] + m[0][1] * c[1] + m[0][2] * c[2],
        m[1][0] * c[0] + m[1][1] * c[1] + m[1][2] * c[2],
        m[2][0] * c[0] + m[2][1] * c[1] + m[2][2] * c[2],
    ]
}

#[derive(Clone, Debug)]
pub struct ToneMap {
    lut: Vec<f32>,
    chroma: [f32; CHROMA_N],
}

impl ToneMap {
    /// A camera curve with Whites and Blacks on top (Contrast is per channel: [`contrast_ev`]).
    pub fn camera(curve: &CameraTone, whites: f64, blacks: f64) -> ToneMap {
        let adjustment = Self::display(whites, blacks);
        let neutral = whites == 0.0 && blacks == 0.0;
        let lut = (0..LUT_N)
            .map(|i| {
                let ev = LUT_MIN_EV + (LUT_MAX_EV - LUT_MIN_EV) * i as f32 / (LUT_N - 1) as f32;
                let y = curve.apply(GREY * 2f32.powf(ev));
                if neutral { y } else { adjustment.apply(y) }
            })
            .collect();
        ToneMap { lut, chroma: curve.chroma }
    }
    /// `contrast`, `whites`, `blacks` in −100..100 (Lightroom slider units).
    pub fn new(contrast: f64, whites: f64, blacks: f64) -> ToneMap {
        let c = (contrast / 100.0) as f32;
        let slope = if c >= 0.0 { 1.0 + 0.55 * c } else { 1.0 + 0.4 * c };
        // White point: scene luminance (after contrast) that maps to display 1.0.
        let white_ev = 2.9 - 1.6 * (whites as f32 / 100.0);
        let wl = GREY * 2f32.powf(white_ev);
        let pre = 1.0 + GREY / wl; // keep grey near grey
        let b = (blacks / 100.0) as f32;
        let lut = (0..LUT_N)
            .map(|i| {
                let ev = LUT_MIN_EV + (LUT_MAX_EV - LUT_MIN_EV) * i as f32 / (LUT_N - 1) as f32;
                let y = GREY * 2f32.powf(ev * slope) * pre;
                // extended Reinhard with white point wl: y(1 + y/wl²)/(1 + y)
                let mut o = y * (1.0 + y / (wl * wl)) / (1.0 + y);
                o = o.min(1.0);
                // Toe: blacks < 0 crushes, > 0 lifts.
                if b < 0.0 {
                    // Smooth max(0, o − k) (a soft knee), renormalized so 1 stays 1.
                    let k = -b * 0.035;
                    let e = 0.004;
                    let soft = |v: f32| ((v - k) + ((v - k) * (v - k) + e * e).sqrt()) * 0.5;
                    o = (soft(o) - soft(0.0)) / (soft(1.0) - soft(0.0));
                } else if b > 0.0 {
                    let k = b * 0.03;
                    o = k + (1.0 - k) * o;
                }
                o.clamp(0.0, 1.0)
            })
            .collect();
        ToneMap { lut, chroma: NO_CHROMA }
    }

    /// Tone map for rendered (non-raw) sources developed on [`JpegTone`]'s scene scale: its curve,
    /// with Whites and Blacks on top (identity with neutral sliders; Contrast: [`contrast_ev`]).
    pub fn jpeg(whites: f64, blacks: f64) -> ToneMap {
        let adjustment = Self::display(whites, blacks);
        let neutral = whites == 0.0 && blacks == 0.0;
        let lut = (0..LUT_N)
            .map(|i| {
                let ev = LUT_MIN_EV + (LUT_MAX_EV - LUT_MIN_EV) * i as f32 / (LUT_N - 1) as f32;
                let y = JpegTone::to_display(GREY * 2f32.powf(ev));
                if neutral { y } else { adjustment.apply(y) }
            })
            .collect();
        ToneMap { lut, chroma: NO_CHROMA }
    }

    /// Whites and Blacks on a display-linear value: identity at neutral settings.
    pub fn display(whites: f64, blacks: f64) -> ToneMap {
        let w = (whites / 100.0) as f32;
        let b = (blacks / 100.0) as f32;
        let lut = (0..LUT_N)
            .map(|i| {
                let ev = LUT_MIN_EV + (LUT_MAX_EV - LUT_MIN_EV) * i as f32 / (LUT_N - 1) as f32;
                let y = GREY * 2f32.powf(ev);
                let mut p = y.powf(1.0 / 2.2);
                // Blacks and Whites (issue 7), shapes of our own whose few numbers were fitted to how far
                // Lightroom's sliders move the greys of our test chart. Blacks −: the black point moves up
                // (a soft toe) and the rest stretches back to white; +: the shadows lift, black stays black.
                if b < 0.0 {
                    let bp = 0.2078 * (-b).powf(2.2152);
                    let soft = |v: f32| 0.5 * (v + (v * v + 0.0004).sqrt()) - 0.01;
                    p = (soft((p - bp) / (1.0 - bp)) / soft(1.0)).max(0.0);
                } else if b > 0.0 {
                    let q = p.min(1.0);
                    p += 0.535 * b * q * (1.0 - q) * (1.0 - q);
                }
                // Whites +: the mid-tones and highlights brighten towards white; −: the upper tones dim
                if w > 0.0 {
                    p *= 1.0 + 0.4469 * w * smooth(0.0209, 0.4361, p);
                } else if w < 0.0 {
                    p *= 1.0 + 0.0982 * w * smooth(0.2, 0.6, p);
                }
                let mut o = p.max(0.0).powf(2.2);
                // short shoulder: slope 1 at 0.95, reaching 1.0 at 1.05
                if o > 0.95 {
                    let d = (o - 0.95).min(0.1);
                    o = 0.95 + d - d * d / 0.2;
                }
                o.clamp(0.0, 1.0)
            })
            .collect();
        ToneMap { lut, chroma: NO_CHROMA }
    }

    /// The table (`LUT_N` entries, see [`ToneMap::apply`]).
    pub fn lut(&self) -> &[f32] {
        &self.lut
    }

    /// The chroma curve (`CHROMA_N` entries, see [`ToneMap::chroma_scale`]).
    pub fn chroma_lut(&self) -> &[f32] {
        &self.chroma
    }

    /// Chroma scale at display luminance `o` (exactly 1 everywhere unless a camera look sets it).
    #[inline]
    pub fn chroma_scale(&self, o: f32) -> f32 {
        if !o.is_finite() {
            return 1.0;
        }
        let f = o.clamp(0.0, 1.0) * (CHROMA_N - 1) as f32;
        let i = (f as usize).min(CHROMA_N - 2);
        let t = f - i as f32;
        let (a, b) = (self.chroma.get(i).copied().unwrap_or(1.0), self.chroma.get(i + 1).copied().unwrap_or(1.0));
        if a == b { a } else { a + (b - a) * t }
    }

    /// Scene luminance → display-linear luminance.
    #[inline]
    pub fn apply(&self, y: f32) -> f32 {
        if y <= 0.0 {
            return 0.0;
        }
        let ev = (y / GREY).log2();
        let f = ((ev - LUT_MIN_EV) / (LUT_MAX_EV - LUT_MIN_EV)).clamp(0.0, 1.0) * (LUT_N - 1) as f32;
        let i = (f as usize).min(LUT_N - 2);
        let t = f - i as f32;
        let v = self.lut[i] + (self.lut[i + 1] - self.lut[i]) * t;
        if ev < LUT_MIN_EV { v * (y / (GREY * 2f32.powf(LUT_MIN_EV))) } else { v }
    }
}

fn smooth(e0: f32, e1: f32, x: f32) -> f32 {
    let t = ((x - e0) / (e1 - e0)).clamp(0.0, 1.0);
    t * t * (3.0 - 2.0 * t)
}

/// Contrast (`c` in −1..=1) as a shift in scene EV, applied to each channel before the tone map, so
/// that raising it also spreads a colour's channels apart and lowering it draws them together, as
/// Lightroom's does. A soft S around a pivot ~0.6 EV above grey that reaches further into the shadows
/// than into the highlights: a shape of our own whose numbers were fitted (issue 7) to how far
/// Lightroom's Contrast moves the greys of our test chart. Raised, it crushes the shadows much harder
/// than it lifts the highlights; lowered, it flattens both ends. Its slope stays above −1, so the
/// curve never folds back.
pub fn contrast_ev(c: f32, ev: f32) -> f32 {
    if c == 0.0 {
        return 0.0;
    }
    // pivot, slope at the pivot, the furthest shift below and above it
    let (pivot, k, below, above) = if c > 0.0 { (0.654, 0.658, 3.137, 0.664) } else { (0.615, 0.439, 1.167, 0.399) };
    let x = ev - pivot;
    let reach = if x < 0.0 { below } else { above };
    c * reach * (k * x / reach).tanh()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn knots() -> [[f32; 2]; 32] {
        std::array::from_fn(|i| {
            let x = 0.004 * 1.18f32.powi(i as i32);
            [x, 1.0 - (-2.0 * x).exp()]
        })
    }

    #[test]
    fn chroma_curve_is_identity_unless_set_and_survives_serde() {
        let plain = CameraTone::new(knots()).unwrap();
        let map = ToneMap::camera(&plain, 0.0, 0.0);
        assert!([0.0, 0.3, 0.77, 1.0, 2.0].iter().all(|o| map.chroma_scale(*o) == 1.0));
        assert!([0.0, 0.5, 1.0].iter().all(|o| ToneMap::new(0.0, 0.0, 0.0).chroma_scale(*o) == 1.0));
        // smart previews written before the chroma curve existed still load (identity)
        let old = serde_json::json!({ "knots": knots() });
        assert_eq!(serde_json::from_value::<CameraTone>(old).unwrap(), plain);
        let tone = plain.with_chroma([1.4, 1.3, 1.1, 1.0, 0.7, 0.4, 0.25, 0.2]).unwrap();
        let back: CameraTone = serde_json::from_value(serde_json::to_value(tone).unwrap()).unwrap();
        assert_eq!(back, tone);
        let map = ToneMap::camera(&tone, 0.0, 0.0);
        assert!((map.chroma_scale(0.0) - 1.4).abs() < 1e-6 && (map.chroma_scale(1.0) - 0.2).abs() < 1e-6);
        assert!((map.chroma_scale(0.5 / 7.0) - 1.35).abs() < 1e-5, "interpolates between nodes");
        assert_eq!(map.chroma_scale(f32::NAN), 1.0);
        // hostile values are rejected, also when deserialized
        assert!(plain.with_chroma([f32::NAN; CHROMA_N]).is_none());
        assert!(plain.with_chroma([-1.0; CHROMA_N]).is_none());
        let bad = serde_json::json!({ "knots": knots(), "chroma": [9.0, 1, 1, 1, 1, 1, 1, 1] });
        assert!(serde_json::from_value::<CameraTone>(bad).is_err());
    }

    #[test]
    fn camera_curve_preserves_black_and_extends_headroom() {
        let knots = std::array::from_fn(|i| {
            let x = 0.005 * 1.15f32.powi(i as i32);
            [x, 1.0 - (-3.0 * x).exp()]
        });
        let curve = CameraTone::new(knots).unwrap();
        let base = ToneMap::camera(&curve, 0.0, 0.0);
        assert_eq!(base.apply(0.0), 0.0);
        assert!(base.apply(0.1) < base.apply(0.2));
        let mut previous = 0.0;
        for i in 0..2000 {
            let y = 1e-6 * 1.01f32.powi(i);
            let v = base.apply(y);
            assert!((0.0..=1.0).contains(&v));
            assert!(v >= previous - 1e-6);
            previous = v;
        }
        assert!((base.apply(0.1) - curve.apply(0.1)).abs() < 0.001);
        assert!(ToneMap::camera(&curve, 50.0, 0.0).apply(0.3) > base.apply(0.3));
        let mut invalid = knots;
        invalid[1][0] = invalid[0][0];
        assert!(CameraTone::new(invalid).is_none());
        assert!(serde_json::from_value::<CameraTone>(serde_json::json!({"knots": invalid})).is_err());
    }

    #[test]
    fn monotone_and_bounded() {
        for (c, w, b) in [(0.0, 0.0, 0.0), (100.0, 100.0, -100.0), (-100.0, -100.0, 100.0), (50.0, -30.0, -40.0)] {
            let t = ToneMap::new(c, w, b);
            let mut prev = -1.0;
            for i in 0..2000 {
                let y = 1e-5 * 1.012f32.powi(i);
                let o = t.apply(y);
                assert!((0.0..=1.0).contains(&o));
                assert!(o >= prev - 1e-6, "{c} {w} {b} at {y}: {o} < {prev}");
                prev = o;
            }
        }
    }

    #[test]
    fn display_identity_and_monotone() {
        let t = ToneMap::display(0.0, 0.0);
        for i in 1..=95 {
            let y = i as f32 / 100.0;
            assert!((t.apply(y) - y).abs() < 2e-3, "{y} -> {}", t.apply(y));
        }
        for (w, b) in [(100.0, -100.0), (-100.0, 100.0), (-40.0, 30.0)] {
            let t = ToneMap::jpeg(w, b);
            let mut prev = -1.0;
            for i in 0..1000 {
                let o = t.apply(i as f32 / 500.0);
                assert!(o >= prev - 1e-5, "{w} {b}");
                prev = o;
            }
        }
        // Contrast pivots a little above grey: + darkens grey and brightens what's above the pivot,
        // − the reverse, and neither folds the curve back
        let white = (1.0 / GREY).log2();
        assert!(contrast_ev(0.6, 0.0) < 0.0 && contrast_ev(0.6, white) > 0.0);
        assert!(contrast_ev(-0.6, 0.0) > 0.0 && contrast_ev(-0.6, white) < 0.0);
        for c in [-1.0, 1.0] {
            let mut prev = f32::MIN;
            for i in 0..2000 {
                let ev = -14.0 + i as f32 * 0.012;
                let out = ev + contrast_ev(c, ev);
                assert!(out > prev, "{c} at {ev}");
                prev = out;
            }
        }
    }

    #[test]
    fn grey_stays_near_grey_and_highlights_roll_off() {
        let t = ToneMap::new(0.0, 0.0, 0.0);
        let g = t.apply(0.18);
        assert!((0.15..0.24).contains(&g), "{g}");
        assert!(t.apply(1.0) < 0.95 && t.apply(1.0) > 0.6);
        assert!(t.apply(8.0) > 0.97);
    }

    #[test]
    fn sliders_move_the_right_way() {
        let base = ToneMap::new(0.0, 0.0, 0.0);
        let contrast = ToneMap::new(60.0, 0.0, 0.0);
        assert!(contrast.apply(0.05) < base.apply(0.05));
        assert!(contrast.apply(0.8) > base.apply(0.8));
        assert!(ToneMap::new(0.0, 60.0, 0.0).apply(0.8) > base.apply(0.8));
        assert!(ToneMap::new(0.0, 0.0, -60.0).apply(0.01) < base.apply(0.01));
        assert!(ToneMap::new(0.0, 0.0, 60.0).apply(0.01) > base.apply(0.01));
    }
}
