//! Mask evaluation: each mask becomes an alpha plane at output resolution.
//!
//! Shapes are defined in normalized oriented-image coordinates; sizes are in "long-edge units".
//! Components combine with Add (max), Subtract (a·(1−c)) and Intersect (a·c).
//!
//! AI shapes (Sky, Subject, Background, People) use the segmentation [`Mattes`] the source carries
//! (DNG semantic masks, e.g. iPhone ProRAW's) when it has a matching one, else a heuristic.

use lightcraft_develop::{BrushStroke, LocalAdjustments, Mask, MaskOp, MaskShape, SegMask};
use lightcraft_geom::{Orientation, Point};
use lightcraft_raster::{Image, Plane, Rgb32f};

use crate::for_rows;
use crate::geometry::Frame;

/// What an embedded segmentation matte selects.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum MatteKind {
    Sky,
    /// A whole person (all people in the photo).
    Person,
    Skin,
    Hair,
    Teeth,
    Glasses,
}

impl MatteKind {
    /// A stable code for serialization (e.g. a smart preview's stored mattes); never renumber.
    pub fn code(self) -> u8 {
        match self {
            MatteKind::Sky => 0,
            MatteKind::Person => 1,
            MatteKind::Skin => 2,
            MatteKind::Hair => 3,
            MatteKind::Teeth => 4,
            MatteKind::Glasses => 5,
        }
    }

    /// [`MatteKind::code`]'s inverse (`None` for a code this build doesn't know).
    pub fn from_code(code: u8) -> Option<Self> {
        match code {
            0 => Some(MatteKind::Sky),
            1 => Some(MatteKind::Person),
            2 => Some(MatteKind::Skin),
            3 => Some(MatteKind::Hair),
            4 => Some(MatteKind::Teeth),
            5 => Some(MatteKind::Glasses),
            _ => None,
        }
    }
}

/// Segmentation mattes that came with the source: 0..255 alpha planes, each covering the whole
/// (EXIF-oriented) source image at its own resolution. Several mattes of one kind (e.g. one per
/// person) are combined with max.
#[derive(Clone, Default, PartialEq)]
pub struct Mattes {
    mattes: Vec<(MatteKind, Image<u8>)>,
}

impl std::fmt::Debug for Mattes {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_list().entries(self.mattes.iter().map(|(k, m)| (k, m.width, m.height))).finish()
    }
}

impl Mattes {
    /// Add a matte (empty ones are ignored).
    pub fn push(&mut self, kind: MatteKind, matte: Image<u8>) {
        if matte.width > 0 && matte.height > 0 && matte.data.len() == matte.width * matte.height {
            self.mattes.push((kind, matte));
        }
    }

    pub fn is_empty(&self) -> bool {
        self.mattes.is_empty()
    }

    /// Memory held (bytes).
    pub fn bytes(&self) -> usize {
        self.mattes.iter().map(|(_, m)| m.data.len()).sum()
    }

    /// The stored mattes, in insertion order (kind and plane), e.g. for storing them with a
    /// photo's proxy source.
    pub fn iter(&self) -> impl Iterator<Item = (MatteKind, &Image<u8>)> {
        self.mattes.iter().map(|(k, m)| (*k, m))
    }

    fn of(&self, kinds: &[MatteKind]) -> Vec<&Image<u8>> {
        self.mattes.iter().filter(|(k, _)| kinds.contains(k)).map(|(_, m)| m).collect()
    }

    /// The mattes a shape uses instead of its heuristic (empty: none fits).
    fn for_shape(&self, shape: &MaskShape) -> Vec<&Image<u8>> {
        use MatteKind::*;
        // the whole person: a person matte, else the union of the parts
        let person = || {
            let p = self.of(&[Person]);
            if p.is_empty() { self.of(&[Skin, Hair, Teeth, Glasses]) } else { p }
        };
        match shape {
            MaskShape::Sky => self.of(&[Sky]),
            MaskShape::Subject => person(),
            MaskShape::People { parts, .. } => {
                let wanted: Vec<MatteKind> = parts
                    .iter()
                    .filter_map(|p| {
                        let p = p.to_ascii_lowercase();
                        [("skin", Skin), ("hair", Hair), ("teeth", Teeth), ("glasses", Glasses)]
                            .into_iter()
                            .find(|(n, _)| p.contains(n))
                            .map(|(_, k)| k)
                    })
                    .collect();
                let whole =
                    parts.is_empty() || parts.iter().any(|p| matches!(p.to_ascii_lowercase().as_str(), "person" | "entire person" | "entireperson"));
                // parts without a matte (lips, clothes, …) keep the heuristic
                if whole { person() } else { self.of(&wanted) }
            }
            _ => Vec::new(),
        }
    }
}

pub struct Evaluated {
    /// The mask's id ([`Mask::id`]).
    pub id: u32,
    pub alpha: Plane,
    pub adjust: LocalAdjustments,
}

#[inline]
fn smooth(e0: f32, e1: f32, x: f32) -> f32 {
    let t = ((x - e0) / (e1 - e0)).clamp(0.0, 1.0);
    t * t * (3.0 - 2.0 * t)
}

/// The visible masks with components, evaluated in order (`mattes`: the source's, see [`Mattes`]).
#[allow(clippy::too_many_arguments)]
pub fn evaluate(masks: &[Mask], frame: &Frame, w: usize, h: usize, img: &Rgb32f, log_l: &Plane, ev: f32, mattes: Option<&Mattes>) -> Vec<Evaluated> {
    masks
        .iter()
        .filter(|m| m.visible && !m.components.is_empty())
        .map(|m| Evaluated { id: m.id, alpha: evaluate_one(m, frame, w, h, img, log_l, ev, mattes), adjust: m.adjust })
        .collect()
}

/// The alpha plane of mask `m` (whether visible or not): its components (the hidden ones left
/// out) combined, inverted and scaled by its amount.
#[allow(clippy::too_many_arguments)]
pub fn evaluate_one(m: &Mask, frame: &Frame, w: usize, h: usize, img: &Rgb32f, log_l: &Plane, ev: f32, mattes: Option<&Mattes>) -> Plane {
    let mut alpha = Plane::new(w, h);
    let mut first = true;
    for comp in m.components.iter().filter(|c| !c.hidden) {
        let mut c = shape_alpha(&comp.shape, frame, w, h, img, log_l, ev, mattes);
        if comp.invert {
            c.data.iter_mut().for_each(|v| *v = 1.0 - *v);
        }
        if first && comp.op != MaskOp::Intersect {
            alpha = c;
            first = false;
            continue;
        }
        for (a, c) in alpha.data.iter_mut().zip(&c.data) {
            *a = match comp.op {
                MaskOp::Add => a.max(*c),
                MaskOp::Subtract => *a * (1.0 - c),
                MaskOp::Intersect => *a * c,
            };
        }
        first = false;
    }
    if m.refine > 0.0 {
        // Refine Edges: the mask's edges follow the photo's (window up to ~4 % of the long edge,
        // the width of a soft brush edge or gradient)
        let k = (m.refine / 100.0).clamp(0.0, 1.0) as f32;
        let sigma = (0.04 * frame.output_long(w, h) as f32 * k).max(1.0);
        let refined = guided_cross(log_l, &alpha, sigma, 0.02);
        for (a, r) in alpha.data.iter_mut().zip(&refined.data) {
            *a += (r - *a) * k.sqrt();
        }
    }
    if m.invert {
        alpha.data.iter_mut().for_each(|v| *v = 1.0 - *v);
    }
    let amt = (m.adjust.amount / 100.0) as f32;
    if (amt - 1.0).abs() > 1e-6 {
        alpha.data.iter_mut().for_each(|v| *v *= amt);
    }
    alpha
}

/// Per-pixel positions in long-edge units for output pixel centres.
fn for_each_pos(frame: &Frame, w: usize, h: usize, out: &mut Plane, f: impl Fn(Point, usize) -> f32 + Sync + Send) {
    let m = frame.out_to_norm(w, h);
    let (ow, oh, l) = (frame.ow, frame.oh, frame.ow.max(frame.oh));
    for_rows(&mut out.data, w, |y, row| {
        for (x, v) in row.iter_mut().enumerate() {
            let n = m.apply(Point::new(x as f64 + 0.5, y as f64 + 0.5));
            *v = f(Point::new(n.x * ow / l, n.y * oh / l), y * w + x);
        }
    });
}

/// The alpha plane of one component shape. AI shapes use the source's matching `mattes`, if any.
#[allow(clippy::too_many_arguments)]
pub fn shape_alpha(shape: &MaskShape, frame: &Frame, w: usize, h: usize, img: &Rgb32f, log_l: &Plane, ev: f32, mattes: Option<&Mattes>) -> Plane {
    let fitting = mattes.map(|m| m.for_shape(shape)).unwrap_or_default();
    if !fitting.is_empty() {
        return matte_alpha(&fitting, frame, w, h);
    }
    let mut out = Plane::new(w, h);
    let to_long = |p: Point| frame.norm_to_long(p);
    // `img`/`log_l` are before exposure: range masks select on the exposed values.
    let gain = ev.exp2();
    match shape {
        MaskShape::Linear { start, end } => {
            let (a, b) = (to_long(*start), to_long(*end));
            let d = b - a;
            let len2 = d.dot(d).max(1e-12);
            for_each_pos(frame, w, h, &mut out, |p, _| {
                let t = ((p - a).dot(d) / len2) as f32;
                1.0 - smooth(0.0, 1.0, t)
            });
        }
        MaskShape::Radial { center, rx, ry, angle, feather, invert } => {
            let c = to_long(*center);
            let (s, co) = (-angle.to_radians()).sin_cos();
            let f = (*feather / 100.0).clamp(0.0, 1.0) as f32;
            let (rx, ry) = (rx.max(1e-6), ry.max(1e-6));
            let inv = *invert;
            for_each_pos(frame, w, h, &mut out, |p, _| {
                let d = p - c;
                let (x, y) = (d.x * co - d.y * s, d.x * s + d.y * co);
                let r = ((x / rx).powi(2) + (y / ry).powi(2)).sqrt() as f32;
                let a = 1.0 - smooth(1.0 - f.max(0.01), 1.0, r);
                if inv { 1.0 - a } else { a }
            });
        }
        MaskShape::Brush { strokes } => rasterize_brush(strokes, frame, w, h, img, log_l, &mut out),
        MaskShape::LuminanceRange { lo, hi, lo_feather, hi_feather, ranked, ranks, .. } => {
            let (lo, hi, lf, hf) = (*lo as f32, *hi as f32, (*lo_feather as f32).max(1e-3), (*hi_feather as f32).max(1e-3));
            let ranks = ranks.as_deref().filter(|r| *ranked && r.len() > 1);
            for (v, l) in out.data.iter_mut().zip(&log_l.data) {
                // a ranked range: the pixel's rank in the photo (exposure moves every pixel alike);
                // else log luminance on a 0..1 scale (−8..+4 EV)
                let y = match ranks {
                    Some(r) => rank_of(r, *l),
                    None => ((l + ev + 8.0) / 12.0).clamp(0.0, 1.0),
                };
                *v = smooth(lo - lf, lo, y) * (1.0 - smooth(hi, hi + hf, y));
            }
        }
        MaskShape::ColorRange { samples, refine, area, hue_first, .. } => {
            let sel = color_select(samples, *refine, area.as_ref(), *hue_first);
            for (v, c) in out.data.iter_mut().zip(&img.data) {
                let c = c.map(|v| v * gain);
                let lab = lightcraft_color::perceptual::oklab_from_2020(if sel.toned { tonemap_for_select(c) } else { c });
                let d = sel.boxes.iter().zip(&sel.l_weights).map(|(b, w)| color_box_distance(lab, b, *w)).fold(f32::MAX, f32::min);
                *v = 1.0 - smooth(sel.full, sel.tol, d);
                if !sel.toned {
                    *v *= hue_first_dark(lab[0]);
                }
            }
        }
        MaskShape::Sky => {
            // Classical sky heuristic until the segmenter lands (M12): bright, smooth, blue-ish or
            // unsaturated, and connected to the top of the frame.
            let m = frame.out_to_norm(w, h);
            for (i, v) in out.data.iter_mut().enumerate() {
                let (x, y) = (i % w, i / w);
                let n = m.apply(Point::new(x as f64 + 0.5, y as f64 + 0.5));
                let c = img.data[i].map(|v| v * gain);
                let l = log_l.data[i] + ev;
                let blue = (c[2] - c[0]).max(0.0) / (c[2] + 1e-4);
                let top = 1.0 - smooth(0.25, 0.7, n.y as f32);
                *v = top * smooth(-3.5, -1.0, l) * (0.4 + 0.6 * smooth(0.0, 0.3, blue).max(smooth(0.0, 1.5, l)));
            }
            smooth_plane(&mut out, 0.01 * frame_px(frame, w));
        }
        MaskShape::Object { seg, detail, edge, .. } | MaskShape::Prompt { seg, detail, edge, .. } if seg.is_some() || !detail.is_empty() => {
            // Edge: below 0 a steeper transition (up to 8× the logits), above 0 feathered (up to
            // 2 % of the long edge, so previews and exports match)
            let e = (*edge / 100.0).clamp(-1.0, 1.0) as f32;
            let gain = if e < 0.0 { 1.0 - 7.0 * e } else { 1.0 };
            sample_seg(seg.as_ref(), detail, gain, frame, w, h, &mut out);
            if e > 0.0 {
                smooth_plane(&mut out, e * 0.02 * frame_px(frame, w));
            }
        }
        // not computed yet (no clicks, or a build without the model): nothing selected
        MaskShape::Object { hint, .. } if hint.is_empty() => {}
        MaskShape::Prompt { .. } => {}
        MaskShape::Subject | MaskShape::Object { .. } | MaskShape::People { .. } => {
            // Saliency heuristic: centre-weighted local contrast (replaced by the segmenter in M12).
            let m = frame.out_to_norm(w, h);
            let blur = lightcraft_raster::blur::gaussian(log_l, 0.03 * frame_px(frame, w));
            for (i, v) in out.data.iter_mut().enumerate() {
                let (x, y) = (i % w, i / w);
                let n = m.apply(Point::new(x as f64 + 0.5, y as f64 + 0.5));
                let d = (((n.x - 0.5) / 0.35).powi(2) + ((n.y - 0.55) / 0.4).powi(2)).sqrt() as f32;
                let contrast = (log_l.data[i] - blur.data[i]).abs();
                *v = (1.0 - smooth(0.6, 1.0, d)) * (0.5 + 0.5 * smooth(0.05, 0.6, contrast));
            }
            smooth_plane(&mut out, 0.015 * frame_px(frame, w));
        }
        MaskShape::Background => {
            let mut s = shape_alpha(&MaskShape::Subject, frame, w, h, img, log_l, ev, mattes);
            s.data.iter_mut().for_each(|v| *v = 1.0 - *v);
            out = s;
        }
        MaskShape::DepthRange { .. } | MaskShape::Landscape { .. } => {}
    }
    out
}

/// A decoded [`SegMask`]: its logits and the normalized rectangle they cover.
struct SegGrid {
    logits: Vec<f32>,
    side: usize,
    r: [f64; 4],
}

impl SegGrid {
    fn new(seg: &SegMask) -> Option<SegGrid> {
        Some(SegGrid { logits: seg.logits()?, side: seg.side as usize, r: seg.bounds()? })
    }

    fn contains(&self, n: Point) -> bool {
        n.x >= self.r[0] && n.x <= self.r[2] && n.y >= self.r[1] && n.y <= self.r[3]
    }

    /// The bilinearly sampled logit at normalized image point `n` (cell centres at
    /// `(i + 0.5) / side` of the rectangle).
    fn logit(&self, n: Point) -> f32 {
        let side = self.side;
        let at = |x: usize, y: usize| self.logits.get(y.min(side - 1) * side + x.min(side - 1)).copied().unwrap_or(-16.0);
        let u = (n.x - self.r[0]) / (self.r[2] - self.r[0]);
        let v = (n.y - self.r[1]) / (self.r[3] - self.r[1]);
        let (fx, fy) = ((u * side as f64 - 0.5) as f32, (v * side as f64 - 0.5) as f32);
        let (fx, fy) = (fx.clamp(0.0, (side - 1) as f32), fy.clamp(0.0, (side - 1) as f32));
        let (x0, y0) = (fx.floor() as usize, fy.floor() as usize);
        let (tx, ty) = (fx - x0 as f32, fy - y0 as f32);
        let top = at(x0, y0) * (1.0 - tx) + at(x0 + 1, y0) * tx;
        let bot = at(x0, y0 + 1) * (1.0 - tx) + at(x0 + 1, y0 + 1) * tx;
        top * (1.0 - ty) + bot * ty
    }
}

/// Stored model segmentations at each output pixel: the zoomed-in `detail` passes inside
/// their rectangles (the highest of them where they overlap), the whole-photo `seg`
/// elsewhere; the logit (times `gain`: > 1 is a harder edge) through a sigmoid. Damaged
/// data selects nothing.
fn sample_seg(seg: Option<&SegMask>, detail: &[SegMask], gain: f32, frame: &Frame, w: usize, h: usize, out: &mut Plane) {
    let coarse = seg.and_then(SegGrid::new);
    // (capped: a hostile document can hold many)
    let patches: Vec<SegGrid> = detail.iter().take(lightcraft_develop::segmask::MAX_DETAIL).filter_map(SegGrid::new).collect();
    if coarse.is_none() && patches.is_empty() {
        return;
    }
    let m = frame.out_to_norm(w, h);
    for_rows(&mut out.data, w, |y, row| {
        for (x, v) in row.iter_mut().enumerate() {
            let n = m.apply(Point::new(x as f64 + 0.5, y as f64 + 0.5));
            let fine = patches.iter().filter(|p| p.contains(n)).map(|p| p.logit(n)).reduce(f32::max);
            let l = fine.or_else(|| coarse.as_ref().map(|c| c.logit(n))).unwrap_or(-16.0);
            *v = 1.0 / (1.0 + (-l * gain).exp());
        }
    });
}

fn frame_px(frame: &Frame, w: usize) -> f32 {
    frame.px_per_long(w) as f32
}

fn smooth_plane(p: &mut Plane, sigma: f32) {
    *p = lightcraft_raster::blur::gaussian(p, sigma.max(0.5));
}

/// The union (max) of `mattes` (0..255, over the EXIF-oriented source) at output resolution,
/// sampled like the image: user orientation, lens / perspective warp, crop and flips; area-averaged
/// first when the output is much smaller than the matte, bilinear otherwise.
fn matte_alpha(mattes: &[&Image<u8>], frame: &Frame, w: usize, h: usize) -> Plane {
    let mut out = Plane::new(w, h);
    let o2t = frame.out_to_oriented(w, h);
    let ppl = frame.px_per_long(w).max(1e-9);
    for m in mattes {
        let oriented =
            if frame.orient == Orientation::Normal { std::borrow::Cow::Borrowed(*m) } else { std::borrow::Cow::Owned(m.oriented(frame.orient)) };
        // matte px per output px
        let k = oriented.width as f64 / frame.ow * frame.ow.max(frame.oh) / ppl;
        let base = box_down(&oriented, if k >= 2.0 { (k.floor() as usize).min(64) } else { 1 });
        let (sx, sy) = ((base.width as f64 / frame.ow) as f32, (base.height as f64 / frame.oh) as f32);
        for_rows(&mut out.data, w, |y, row| {
            for (x, v) in row.iter_mut().enumerate() {
                let s = match &frame.warp {
                    Some(wp) => {
                        let Some((_, s)) = wp.frame(&o2t, x, y) else {
                            continue;
                        };
                        s
                    }
                    None => o2t.apply(Point::new(x as f64 + 0.5, y as f64 + 0.5)),
                };
                *v = v.max(bilinear(&base, s.x as f32 * sx, s.y as f32 * sy) / 255.0);
            }
        });
    }
    out
}

/// `m` area-averaged by `f × f` (0..255 values kept); `f == 1` converts only.
fn box_down(m: &Image<u8>, f: usize) -> Plane {
    let f = f.max(1);
    let (bw, bh) = (m.width.div_ceil(f).max(1), m.height.div_ceil(f).max(1));
    let mut out = Plane::new(bw, bh);
    for_rows(&mut out.data, bw, |by, row| {
        let rows = by * f..((by + 1) * f).min(m.height);
        for (bx, v) in row.iter_mut().enumerate() {
            let cols = bx * f..((bx + 1) * f).min(m.width);
            let (mut sum, mut n) = (0u32, 0u32);
            for y in rows.clone() {
                for x in cols.clone() {
                    sum += m.data.get(y * m.width + x).copied().unwrap_or(0) as u32;
                    n += 1;
                }
            }
            *v = sum as f32 / n.max(1) as f32;
        }
    });
    out
}

/// Bilinear sample at continuous pixel coordinates (pixel centres at +0.5), edges extended.
fn bilinear(p: &Plane, x: f32, y: f32) -> f32 {
    // (held a pixel outside, which reads the same edge: an infinite coordinate reads the edge
    // instead of a NaN weight and a neighbour index past isize::MAX)
    let (fx, fy) = (x.clamp(-1.0, p.width as f32 + 1.0) - 0.5, y.clamp(-1.0, p.height as f32 + 1.0) - 0.5);
    let (x0, y0) = (fx.floor(), fy.floor());
    let (tx, ty) = (fx - x0, fy - y0);
    let (x0, y0) = (x0 as isize, y0 as isize);
    let a = p.get_clamped(x0, y0) + (p.get_clamped(x0 + 1, y0) - p.get_clamped(x0, y0)) * tx;
    let b = p.get_clamped(x0, y0 + 1) + (p.get_clamped(x0 + 1, y0 + 1) - p.get_clamped(x0, y0 + 1)) * tx;
    a + (b - a) * ty
}

/// A rough display mapping for colour picking (so samples taken on screen match).
pub(crate) fn tonemap_for_select(c: [f32; 3]) -> [f32; 3] {
    c.map(|v| v / (1.0 + v))
}

/// Colour range: the OkLab distance (a, b at full weight, L at a quarter) at which a pixel leaves
/// the selection for a Refine amount of 0..100. A pixel is fully selected up to half of it and
/// fades out over the other half (CPU here, `mask.wgsl` kind 3; both take this value).
///
/// Issue #157: `0.04 + 0.16·refine` selected the whole photo. Through [`tonemap_for_select`] a
/// slightly teal mid-grey (sRGB 120,150,155) and a neutral light grey (200,198,195) are only ~0.063
/// apart — L 0.60 vs 0.72, a/b ≤ 0.02 — so at the default Refine 50 (full selection below 0.06)
/// the grey patch was 99 % selected, and at Refine 85 / 100 still most of it. A JND is ~0.01 on
/// this scale, so Refine 50 now holds a surface up to ~2 JND from its sample and drops everything
/// beyond ~4.5 (full to 0.0225, gone at 0.045); Refine 85 is gone at 0.066, past that grey patch;
/// Refine 100 reaches 0.075 and Refine 0 is the sample's own shade (full to 0.0075, gone at 0.015).
pub fn color_range_tolerance(refine: f64) -> f32 {
    0.015 + 0.06 * (refine / 100.0).clamp(0.0, 1.0) as f32
}

/// A colour range's samples as OkLab boxes (low and high corners): a sampled colour is a box of
/// no size, an area the box its colours span (CPU here, `mask.wgsl` kind 3; both take these).
pub fn color_range_boxes(samples: &[[f64; 3]], area: Option<&[[f64; 3]; 2]>) -> Vec<[[f32; 3]; 2]> {
    let f = |s: &[f64; 3]| s.map(|v| v as f32);
    samples.iter().map(|s| [f(s), f(s)]).chain(area.map(|[lo, hi]| [f(lo), f(hi)])).collect()
}

/// How a colour range selects (CPU here, `mask.wgsl` kind 3 alike): its boxes
/// ([`color_range_boxes`]) with each one's weight on lightness, the distance
/// ([`color_box_distance`]) up to which a pixel is fully selected and the one at which it has
/// left the selection, and whether pixels are compared toned down ([`tonemap_for_select`]).
pub struct ColorSelect {
    pub boxes: Vec<[[f32; 3]; 2]>,
    pub l_weights: Vec<f32>,
    pub full: f32,
    pub tol: f32,
    pub toned: bool,
}

/// [`ColorSelect`] for a colour range. By default: toned down, L at half weight, fully selected to
/// half of [`color_range_tolerance`]. `hue_first`, as the reference selects (fitted to its masks
/// over our test photos, 2026-10-12): the scene colour as it is, fading out from the sample itself
/// to 0.025 + 0.0008 × Refine, lightness weighed 1 / (5.92 + 92 × the sample's chroma) — a grey
/// sample takes greys up to ~0.36 lighter or darker (at Refine 50), a blue one only blues, of
/// nearly any lightness. Near black ([`hue_first_dark`]) its hue means nothing: the selection
/// fades out (the reference leaves the black of space out of a blue pick).
pub fn color_select(samples: &[[f64; 3]], refine: f64, area: Option<&[[f64; 3]; 2]>, hue_first: bool) -> ColorSelect {
    let boxes = color_range_boxes(samples, area);
    if !hue_first {
        let tol = color_range_tolerance(refine);
        return ColorSelect { l_weights: vec![0.5; boxes.len()], boxes, full: tol * 0.5, tol, toned: true };
    }
    let l_weights = boxes
        .iter()
        .map(|[lo, hi]| {
            let (a, b) = ((lo[1] + hi[1]) * 0.5, (lo[2] + hi[2]) * 0.5);
            1.0 / (5.92 + 92.0 * a.hypot(b))
        })
        .collect();
    let tol = 0.025 + 0.0008 * refine.clamp(0.0, 100.0) as f32;
    ColorSelect { boxes, l_weights, full: 0.0, tol, toned: false }
}

/// How much of a hue-first selection a pixel of OkLab lightness `l` keeps: none below 0.1 (about
/// sRGB 4/255), all from 0.2 (about 20/255) up (the reference selects greys from 32/255).
#[inline]
pub fn hue_first_dark(l: f32) -> f32 {
    smooth(0.1, 0.2, l)
}

/// How far OkLab colour `lab` is from box `b` (none inside it): a and b at full weight, L at
/// `l_weight` ([`ColorSelect`]).
#[inline]
pub fn color_box_distance(lab: [f32; 3], b: &[[f32; 3]; 2], l_weight: f32) -> f32 {
    let off = |k: usize| (b[0][k] - lab[k]).max(lab[k] - b[1][k]).max(0.0);
    (off(1).powi(2) + off(2).powi(2) + (l_weight * off(0)).powi(2)).sqrt()
}

/// Luminance range masks select on a 0..1 scale of log luminance (−8..+4 EV around middle grey,
/// exposure applied). Their picker and slider show lightness 0..1 instead: CIE L* / 100 of the
/// scene luminance, white at 1. Lightness 1 is the top of the scale (everything brighter included),
/// 0 its foot.
pub fn range_from_lightness(l: f64) -> f64 {
    if l >= 1.0 {
        return 1.0;
    }
    if l <= 0.0 {
        return 0.0;
    }
    let lstar = l * 100.0;
    let y = if lstar > 8.0 { ((lstar + 16.0) / 116.0).powi(3) } else { lstar / 903.3 };
    (((y / f64::from(crate::tone::GREY)).log2() + 8.0) / 12.0).clamp(0.0, 1.0)
}

/// The lightness (0..1) a luminance range value stands for: the inverse of [`range_from_lightness`].
pub fn lightness_from_range(v: f64) -> f64 {
    if v >= 1.0 {
        return 1.0;
    }
    if v <= 0.0 {
        return 0.0;
    }
    let y = f64::from(crate::tone::GREY) * (12.0 * v - 8.0).exp2();
    let lstar = if y > 0.008856 { 116.0 * y.cbrt() - 16.0 } else { 903.3 * y };
    (lstar / 100.0).min(1.0)
}

/// A luminance range picked at lightness `l` (a point) or over lightness `l`..`u` (an area): fully
/// selected from 0.1 below a point (an area's darkest) to 0.1 above it (its lightest), fading out
/// over 0.25 more on each side; as `(lo, hi, lo_feather, hi_feather)` on the range scale.
pub fn picked_luminance_range(l: f64, u: Option<f64>) -> (f64, f64, f64, f64) {
    let (lower, upper) = match u {
        Some(u) => (l.min(u), l.max(u)),
        None => (l - 0.1, l + 0.1),
    };
    let r = |v: f64| range_from_lightness(v.clamp(0.0, 1.0));
    let (lo, hi) = (r(lower), r(upper));
    (lo, hi, lo - r(lower - 0.25), r(upper + 0.25) - hi)
}

/// Steps of a ranked luminance range's table ([`luminance_ranks`]): the photo's luminance at
/// ranks 0, 1/64, … 1.
pub const RANK_STEPS: usize = 64;

/// Long edge (px) of the copy of the photo a ranked luminance range ranks against
/// ([`rank_copy`]): ranks come from the whole photo however much of it a render shows, and from a
/// small copy, so tiny specks don't count.
pub const RANK_EDGE: usize = 256;

/// A copy of `img` at most [`RANK_EDGE`] px long, each pixel the mean of up to 4 × 4 samples of
/// its cell.
pub fn rank_copy(img: &Rgb32f) -> Rgb32f {
    let (w, h) = (img.width, img.height);
    if w == 0 || h == 0 || img.data.len() < w * h {
        return Rgb32f::new(0, 0);
    }
    let k = (RANK_EDGE as f64 / w.max(h) as f64).min(1.0);
    let (sw, sh) = (((w as f64 * k).round() as usize).max(1), ((h as f64 * k).round() as usize).max(1));
    let mut out = Rgb32f::new(sw, sh);
    for (o, d) in out.data.iter_mut().enumerate() {
        let (x, y) = (o % sw, o / sw);
        let (x0, x1) = (x * w / sw, ((x + 1) * w / sw).max(x * w / sw + 1));
        let (y0, y1) = (y * h / sh, ((y + 1) * h / sh).max(y * h / sh + 1));
        let (xs, ys) = ((x1 - x0).min(4), (y1 - y0).min(4));
        let mut c = [0.0f32; 3];
        for j in 0..ys {
            let py = y0 + (2 * j + 1) * (y1 - y0) / (2 * ys);
            for i in 0..xs {
                let px = x0 + (2 * i + 1) * (x1 - x0) / (2 * xs);
                if let Some(p) = img.data.get(py * w + px) {
                    c = [c[0] + p[0], c[1] + p[1], c[2] + p[2]];
                }
            }
        }
        let n = (xs * ys) as f32;
        *d = c.map(|v| v / n);
    }
    out
}

/// The table a ranked luminance range selects with: the log luminance ([`crate::local::log_lum`])
/// of `img` (a [`rank_copy`]) at ranks 0, 1/[`RANK_STEPS`], … 1.
pub fn luminance_ranks(img: &Rgb32f) -> Vec<f32> {
    let mut l: Vec<f32> = img.data.iter().map(|c| crate::local::log_lum(*c)).collect();
    if l.is_empty() {
        return vec![0.0; RANK_STEPS + 1];
    }
    l.sort_by(f32::total_cmp);
    let last = l.len() - 1;
    (0..=RANK_STEPS).map(|i| l[i * last / RANK_STEPS]).collect()
}

/// The rank (0..1: the share of the photo darker) of log luminance `l` in table `ranks`
/// ([`luminance_ranks`]), linear between its steps (CPU here, `mask.wgsl` kind 2 alike).
#[inline]
pub fn rank_of(ranks: &[f32], l: f32) -> f32 {
    let n = ranks.len();
    match ranks.first() {
        Some(&first) if n > 1 && l > first => {}
        _ => return 0.0,
    }
    for i in 1..n {
        let (a, b) = (ranks[i - 1], ranks[i]);
        if b >= l {
            return ((i - 1) as f32 + (l - a) / (b - a).max(1e-12)) / (n - 1) as f32;
        }
    }
    1.0
}

/// A ranked luminance range picked at rank `r` (a point) or over ranks `r`..`u` (an area): fully
/// selected 0.1 either side of a point (an area: from its darkest to its lightest), fading out
/// over 0.25 more; a bound within 0.15 of an end goes to that end, unfeathered (so a dark pick
/// takes in everything darker). As `(lo, hi, lo_feather, hi_feather)`.
pub fn picked_ranked_range(r: f64, u: Option<f64>) -> (f64, f64, f64, f64) {
    let (lower, upper) = match u {
        Some(u) => (r.min(u), r.max(u)),
        None => (r - 0.1, r + 0.1),
    };
    let lo = if lower <= 0.15 { 0.0 } else { lower.min(1.0) };
    let hi = if upper >= 0.85 { 1.0 } else { upper.max(0.0) };
    (lo, hi, lo.min(0.25), (1.0 - hi).min(0.25))
}

/// A brush stroke resolved to output pixels: dab centres (spacing r/4 along the path), radius,
/// hard-core radius, flow and density (0..1), and whether it erases.
pub struct BrushDabs {
    pub dabs: Vec<Point>,
    pub r: f64,
    pub hard: f64,
    pub flow: f32,
    pub density: f32,
    pub erase: bool,
    /// Auto Mask: paint only pixels like the one under each dab, then snap to edges.
    pub auto: bool,
    /// The reference's brush ([`BrushStroke::spread`](lightcraft_develop::BrushStroke)): a pixel
    /// `d` from the nearest dab is covered by [`spread_cover`]`(d / r, fade)`, and the stroke takes
    /// `(1 - exp(-strength · cover)) / norm`.
    pub spread: bool,
    pub fade: (f32, f32),
    pub strength: f32,
    pub norm: f32,
    /// Pen pressure (0..1) of each dab, parallel to `dabs`; empty when the stroke has no pressure data (full pressure).
    pub pressure: Vec<f32>,
}

impl BrushDabs {
    /// Radius, hard core and flow of dab `i`, pen pressure applied ([`pressure_radius`], [`pressure_flow`]).
    pub fn dab(&self, i: usize) -> (f64, f64, f32) {
        match self.pressure.get(i) {
            Some(&p) => {
                let r = pressure_radius(self.r, p);
                (r, if self.r > 0.0 { self.hard * r / self.r } else { self.hard }, pressure_flow(self.flow, p))
            }
            None => (self.r, self.hard, self.flow),
        }
    }
}

/// The radius of a dab of a brush of radius `r` (pixels) painted at pen pressure `p` (0..1): from a tenth of the
/// brush at the lightest touch to the full brush at full pressure, never below half a pixel.
#[inline]
pub fn pressure_radius(r: f64, p: f32) -> f64 {
    let p = if p.is_nan() { 1.0 } else { f64::from(p.clamp(0.0, 1.0)) };
    (r * (0.1 + 0.9 * p)).max(0.5).min(r.max(0.5))
}

/// The flow (0..1) of a dab painted at pen pressure `p` (0..1): proportional to the pressure.
#[inline]
pub fn pressure_flow(flow: f32, p: f32) -> f32 {
    let p = if p.is_nan() { 1.0 } else { p.clamp(0.0, 1.0) };
    flow * p
}

/// The reference brush's build-up at flow 100: `1 - exp(-SPREAD_DENSITY)` of the way at the
/// stroke's core, normalized to full there (measured: flow 50 reaches 0.38, flow 17 0.07).
const SPREAD_DENSITY: f32 = 4.5;

/// [`BrushDabs::fade`] for feather `f` (0..1): the core (full cover, in radii) and the width of
/// the Gaussian edge beyond it, as the reference's brush paints a straight stroke (measured over
/// our test photos, 2026-10-13): feather 0 a hard edge at the radius (one pixel soft), 0.43 full
/// to 0.65 r then a 0.43 r Gaussian, 1 a Gaussian from the centre line (e^-u²).
pub fn spread_fade(f: f64, r: f64) -> (f32, f32) {
    let f = f.clamp(0.0, 1.0);
    let core = (1.0 - f * (0.68 + 0.32 * f)).max(0.0);
    (core as f32, f.max(1.0 / r.max(1.0)) as f32)
}

/// How far past the core [`spread_cover`] reaches (it's under 0.002 beyond), in Gaussian widths.
pub const SPREAD_REACH: f32 = 2.5;

/// A pixel's cover from a dab `u` radii away: full in the core, a Gaussian beyond it.
#[inline]
pub fn spread_cover(u: f32, fade: (f32, f32)) -> f32 {
    let t = ((u - fade.0) / fade.1).max(0.0);
    if t >= SPREAD_REACH { 0.0 } else { (-t * t).exp() }
}

/// [`BrushDabs::strength`] for flow `f` (0..1): how far one stroke builds up.
pub fn spread_strength(f: f64) -> f32 {
    let f = f.clamp(0.0, 1.0);
    (((4.28 * f).exp() - 1.0) / (4.28f64.exp() - 1.0)) as f32 * SPREAD_DENSITY
}

/// Pressures for the `added` dabs [`push_dabs`] just laid from a sample at pressure `from` to one at `to`: evenly
/// interpolated, the last one at `to`, as the dabs themselves are placed.
pub fn push_pressures(pressure: &mut Vec<f32>, added: usize, from: f32, to: f32) {
    for k in 1..=added {
        pressure.push(from + (to - from) * k as f32 / added as f32);
    }
}

/// Output pixels a brush sample must move from the last dab to count as movement: below this the
/// pointer is held still (the same screen position mapped again, up to float rounding).
const STATIONARY_PX: f64 = 1e-6;

/// Most dabs one stroke holds (32 MiB of centres). A segment is capped on its own, but a stroke of
/// many hostile segments (an agent, a crafted XMP or catalog) would otherwise add up without
/// bound; past this the rest of the stroke lays nothing (#1520).
pub const MAX_STROKE_DABS: usize = 2_000_000;

/// The dabs of stroke `s` in a `w × h` output.
pub fn brush_dabs(s: &BrushStroke, frame: &Frame, w: usize, h: usize) -> BrushDabs {
    let to_out = frame.norm_to_out(w, h);
    let ppl = frame.px_per_long(w);
    let r = (s.size * ppl).max(0.5);
    let hard = r * (1.0 - (s.feather / 100.0).clamp(0.0, 1.0));
    // Densify the path so dabs overlap (spacing r/4). Coverage builds up by passes, not by how
    // long the button is held: a sample where the pointer hasn't moved (a stationary hold records
    // one per frame) lays no dab, so waiting in place adds nothing (#518).
    let mut dabs: Vec<Point> = Vec::new();
    let mut pressure: Vec<f32> = Vec::new();
    let with_pressure = !s.pressure.is_empty();
    let mut prev = None;
    let mut prev_p = 1.0;
    for (i, p) in s.points.iter().enumerate() {
        let q = to_out.apply(*p);
        let before = dabs.len();
        if push_dabs(&mut dabs, prev, q, r) {
            let pq = s.pressure_at(i);
            if with_pressure {
                // the first dab of the stroke has nothing to interpolate from
                let from = if prev.is_some() { prev_p } else { pq };
                push_pressures(&mut pressure, dabs.len() - before, from, pq);
            }
            prev = Some(q);
            prev_p = pq;
        }
    }
    BrushDabs {
        dabs,
        r,
        hard,
        flow: (s.flow / 100.0).clamp(0.0, 1.0) as f32,
        density: (s.density / 100.0).clamp(0.0, 1.0) as f32,
        erase: s.erase,
        auto: s.auto_mask,
        spread: s.spread,
        fade: spread_fade(s.feather / 100.0, r),
        strength: spread_strength(s.flow / 100.0),
        norm: 1.0 - (-SPREAD_DENSITY).exp(),
        pressure,
    }
}

/// Distance between consecutive dabs of a brush of radius `r` (pixels): a quarter of the radius,
/// so neighbouring dabs overlap and a stroke reads as continuous however fast it was painted.
#[inline]
pub fn dab_spacing(r: f64) -> f64 {
    (r / 4.0).max(0.5)
}

/// Append the dabs of one path segment, from the previous sample `prev` (already stamped) to `q`,
/// for a brush of radius `r`: evenly spaced at most [`dab_spacing`] apart, ending on `q`. The
/// committed stroke ([`brush_dabs`]) and the live one the UI draws while the button is held place
/// their dabs with this, so they cover the same pixels (issue #517). A sample where the pointer
/// hasn't moved (a stationary hold records one per frame) or that is not a finite point lays
/// nothing, so holding the brush still adds no coverage (#518). Returns whether `q` was stamped;
/// callers move `prev` on only then. A stroke holding [`MAX_STROKE_DABS`] takes no more.
pub fn push_dabs(dabs: &mut Vec<Point>, prev: Option<Point>, q: Point, r: f64) -> bool {
    let mut emitted = dabs.len();
    push_dabs_counted(dabs, &mut emitted, prev, q, r)
}

/// Append one segment using a persistent count of all dabs emitted for this stroke, including
/// dabs no longer held in `dabs`. Incremental callers retain `emitted` when clearing their buffer;
/// it advances only by the number actually appended. Acceptance and spacing match [`push_dabs`].
pub fn push_dabs_counted(dabs: &mut Vec<Point>, emitted: &mut usize, prev: Option<Point>, q: Point, r: f64) -> bool {
    let room = MAX_STROKE_DABS.saturating_sub(*emitted);
    if room == 0 || !(q.x.is_finite() && q.y.is_finite()) {
        return false;
    }
    let before = dabs.len();
    if let Some(prev) = prev {
        let dist = prev.dist(q);
        if dist.is_nan() || dist <= STATIONARY_PX {
            return false;
        }
        let n = (dist / dab_spacing(r)).ceil();
        // a hostile or degenerate segment (huge) gets no in-between dabs rather than a huge
        // allocation: 1 M dabs is far more than any on-screen stroke needs
        let n = if n.is_finite() { n.clamp(0.0, 1_000_000.0) as usize } else { 0 };
        // the in-between dabs and `q` together fit what is left of the stroke's budget
        let n = n.min(room);
        for k in 1..n {
            dabs.push(prev.lerp(q, k as f64 / n as f64));
        }
    }
    dabs.push(q);
    *emitted += dabs.len() - before;
    true
}

/// How much one dab paints at distance `dd` from its centre: 1 inside the hard core `hard`,
/// smoothly falling to 0 at the radius `r`.
#[inline]
pub fn dab_alpha(dd: f64, r: f64, hard: f64) -> f32 {
    if dd > r {
        0.0
    } else if dd <= hard {
        1.0
    } else {
        1.0 - smooth(hard as f32, r as f32, dd as f32)
    }
}

/// A stroke's coverage `v` after another dab paints `a` there: flow accumulates up to density.
#[inline]
pub fn accumulate(v: f32, a: f32, flow: f32, density: f32) -> f32 {
    (v + a * flow * (1.0 - v)).min(density)
}

/// Auto Mask tolerances: a pixel takes a dab's paint fully up to half of these from the colour
/// under the dab centre, none beyond them (log2 luminance, chromaticity `rgb / Y`).
pub const AUTO_TOL_EV: f32 = 0.5;
pub const AUTO_TOL_CHROMA: f32 = 0.25;

/// Chromaticity `rgb / Y` (as colour noise reduction uses it).
#[inline]
pub fn chromaticity(c: [f32; 3]) -> [f32; 3] {
    let y = lightcraft_color::luminance_2020(c).max(1e-6);
    [c[0] / y, c[1] / y, c[2] / y]
}

/// Auto Mask: how much a pixel (log luminance `l`, chromaticity `ch`) is like the reference.
#[inline]
pub fn auto_similarity(l: f32, ch: [f32; 3], rl: f32, rch: [f32; 3]) -> f32 {
    let dl = (l - rl) / AUTO_TOL_EV;
    let dc = ((ch[0] - rch[0]).powi(2) + (ch[1] - rch[1]).powi(2) + (ch[2] - rch[2]).powi(2)).sqrt() / AUTO_TOL_CHROMA;
    1.0 - smooth(0.5, 1.0, (dl * dl + dc * dc).sqrt())
}

/// The output pixel a dab at `d` samples its reference colour from. The position is taken at f32
/// precision (like the GPU kernel): a dab on a pixel boundary must not fall on either side of it
/// by an f64 rounding error, which depends on where the window of a zoomed view starts.
#[inline]
pub fn dab_pixel(d: Point, w: usize, h: usize) -> usize {
    let x = ((d.x as f32).floor().max(0.0) as usize).min(w - 1);
    let y = ((d.y as f32).floor().max(0.0) as usize).min(h - 1);
    y * w + x
}

/// Guided-filter refinement of an Auto Mask stroke of radius `r` px: (sigma px, epsilon EV²). The
/// stroke keeps the larger of its own and the refined alpha.
pub fn auto_refine(r: f64) -> (f32, f32) {
    ((0.25 * r).max(1.0) as f32, 0.02)
}

/// Guided filter of `p` steered by `guide` (He et al.), clamped to 0..1: `p`'s edges snap to the
/// guide's.
pub fn guided_cross(guide: &Plane, p: &Plane, sigma: f32, eps: f32) -> Plane {
    use lightcraft_raster::blur::gaussian;
    let mi = gaussian(guide, sigma);
    let mp = gaussian(p, sigma);
    let cip = gaussian(&guide.zip_map(p, |a, b| a * b), sigma);
    let cii = gaussian(&guide.map(|a| a * a), sigma);
    let mut a = Plane::new(p.width, p.height);
    let mut b = Plane::new(p.width, p.height);
    for i in 0..p.data.len() {
        let var = (cii.data[i] - mi.data[i] * mi.data[i]).max(0.0);
        let k = (cip.data[i] - mi.data[i] * mp.data[i]) / (var + eps);
        a.data[i] = k;
        b.data[i] = mp.data[i] - k * mi.data[i];
    }
    let (ma, mb) = (gaussian(&a, sigma), gaussian(&b, sigma));
    let mut q = Plane::new(p.width, p.height);
    for i in 0..q.data.len() {
        q.data[i] = (ma.data[i] * guide.data[i] + mb.data[i]).clamp(0.0, 1.0);
    }
    q
}

/// Stamp brush strokes into `out` (max-combine; erase strokes subtract). Auto Mask strokes weight
/// each dab by [`auto_similarity`] to the pixel under its centre and are refined with
/// [`guided_cross`] on log luminance.
fn rasterize_brush(strokes: &[BrushStroke], frame: &Frame, w: usize, h: usize, img: &Rgb32f, log_l: &Plane, out: &mut Plane) {
    if w == 0 || h == 0 {
        return;
    }
    for s in strokes {
        let bd = brush_dabs(s, frame, w, h);
        let (dens, erase, auto, spread, fade, strength, norm) = (bd.density, bd.erase, bd.auto, bd.spread, bd.fade, bd.strength, bd.norm);
        let mut stroke_alpha = Plane::new(w, h);
        for (k, d) in bd.dabs.iter().enumerate() {
            let (r, hard, flow) = bd.dab(k);
            // the reference's brush reaches past the radius (up to the fade's end)
            let reach = if spread { r * f64::from(fade.0 + SPREAD_REACH * fade.1) } else { r };
            let refc = auto.then(|| {
                let j = dab_pixel(*d, w, h);
                (log_l.data[j], chromaticity(img.data[j]))
            });
            let (x0, x1) = (((d.x - reach).floor().max(0.0)) as usize, ((d.x + reach).ceil().max(0.0) as usize).min(w));
            let (y0, y1) = (((d.y - reach).floor().max(0.0)) as usize, ((d.y + reach).ceil().max(0.0) as usize).min(h));
            for y in y0..y1 {
                for x in x0..x1 {
                    let dd = Point::new(x as f64 + 0.5, y as f64 + 0.5).dist(*d);
                    if dd > reach {
                        continue;
                    }
                    let mut a = if spread { spread_cover((dd / r) as f32, fade) } else { dab_alpha(dd, r, hard) };
                    let i = y * w + x;
                    if let Some((rl, rch)) = refc {
                        a *= auto_similarity(log_l.data[i], chromaticity(img.data[i]), rl, rch);
                    }
                    let v = &mut stroke_alpha.data[i];
                    if spread {
                        // the stroke's coverage: its nearest dab's (built up below)
                        *v = v.max(a);
                    } else {
                        // flow accumulates within a stroke up to density
                        *v = accumulate(*v, a, flow, dens);
                    }
                }
            }
        }
        if spread {
            for v in &mut stroke_alpha.data {
                *v = ((1.0 - (-strength * *v).exp()) / norm).min(dens);
            }
        }
        if auto {
            let (sigma, eps) = auto_refine(bd.r);
            let q = guided_cross(log_l, &stroke_alpha, sigma, eps);
            // the refinement fills gaps along edges; it never takes paint away
            stroke_alpha = stroke_alpha.zip_map(&q, f32::max);
        }
        for (o, a) in out.data.iter_mut().zip(&stroke_alpha.data) {
            *o = if erase { *o * (1.0 - a) } else { o.max(*a) };
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use lightcraft_develop::{DevelopSettings, MaskComponent};

    fn frame(w: usize, h: usize) -> Frame {
        Frame::new(w, h, &DevelopSettings::default(), true)
    }

    #[test]
    fn linear_gradient_ramps() {
        let f = frame(100, 50);
        let img = Rgb32f::new(100, 50);
        let l = Plane::new(100, 50);
        let a = shape_alpha(&MaskShape::Linear { start: Point::new(0.0, 0.0), end: Point::new(1.0, 0.0) }, &f, 100, 50, &img, &l, 0.0, None);
        assert!(a.get(1, 25) > 0.99 && a.get(98, 25) < 0.01);
        assert!((a.get(50, 25) - 0.5).abs() < 0.05);
    }

    #[test]
    fn stored_segmentations_render_at_any_size_and_follow_the_crop() {
        // the left half selected, on a 16² logit grid
        let side = 16;
        let logits: Vec<f32> = (0..side * side).map(|i| if i % side < side / 2 { 12.0 } else { -12.0 }).collect();
        let seg = SegMask::from_logits(side, &logits);
        for shape in [
            MaskShape::Object { hint: vec![Point::new(0.25, 0.5)], exclude: vec![], seg: Some(seg.clone()), detail: vec![], edge: 0.0 },
            MaskShape::Prompt { text: "left".into(), seg: Some(seg.clone()), detail: vec![], edge: 0.0 },
        ] {
            for (w, h) in [(40, 20), (400, 200)] {
                let a = shape_alpha(&shape, &frame(w, h), w, h, &Rgb32f::new(w, h), &Plane::new(w, h), 0.0, None);
                assert!(a.get(1, h / 2) > 0.99 && a.get(w - 2, h / 2) < 0.01, "{w}×{h}");
                if w >= 400 {
                    let soft = (0..w).filter(|x| (0.05..0.95).contains(&a.get(*x, h / 2))).count();
                    assert!((2..30).contains(&soft), "an anti-aliased edge, not a ramp: {soft} px");
                }
            }
        }
        // cropped to the right half: nothing left selected
        let mut d = DevelopSettings::default();
        d.crop.geometry.rect = lightcraft_geom::Rect::from_center(Point::new(0.75, 0.5), 0.5, 1.0);
        let f = Frame::new(40, 20, &d, true);
        let shape = MaskShape::Prompt { text: "left".into(), seg: Some(seg), detail: vec![], edge: 0.0 };
        let a = shape_alpha(&shape, &f, 20, 20, &Rgb32f::new(20, 20), &Plane::new(20, 20), 0.0, None);
        assert!(a.data.iter().all(|v| *v < 0.05));
    }

    #[test]
    fn detail_patches_replace_the_coarse_mask_inside_their_rectangle() {
        // coarse: nothing; a patch over the right half selects its own left half (x 0.5..0.75)
        let coarse = SegMask::from_logits(4, &[-12.0; 16]);
        let side = 16;
        let l: Vec<f32> = (0..side * side).map(|i| if i % side < side / 2 { 12.0 } else { -12.0 }).collect();
        let patch = SegMask::from_logits_in(side, &l, [0.5, 0.0, 1.0, 1.0]);
        let shape = MaskShape::Object { hint: vec![Point::new(0.6, 0.5)], exclude: vec![], seg: Some(coarse), detail: vec![patch], edge: 0.0 };
        let (w, h) = (200, 100);
        let a = shape_alpha(&shape, &frame(w, h), w, h, &Rgb32f::new(w, h), &Plane::new(w, h), 0.0, None);
        assert!(a.get(20, 50) < 0.01, "outside the patch: the coarse mask");
        assert!(a.get(120, 50) > 0.99, "inside the patch, its selected half");
        assert!(a.get(190, 50) < 0.01, "inside the patch, its unselected half");
    }

    #[test]
    fn a_hostile_number_of_detail_patches_is_capped() {
        // built in memory (a deserialized document is capped already): only the first
        // MAX_DETAIL are decoded and sampled, so this needs 4 × 4 MB, not 20 000 × 4 MB
        let side = 1024;
        let l: Vec<f32> = vec![12.0; side * side];
        let patch = SegMask::from_logits_in(side, &l, [0.0, 0.0, 0.5, 1.0]);
        let shape = MaskShape::Object { hint: vec![Point::new(0.2, 0.5)], exclude: vec![], seg: None, detail: vec![patch; 20_000], edge: 0.0 };
        let (w, h) = (64, 32);
        let t = std::time::Instant::now();
        let a = shape_alpha(&shape, &frame(w, h), w, h, &Rgb32f::new(w, h), &Plane::new(w, h), 0.0, None);
        assert!(a.get(5, 16) > 0.99 && a.get(60, 16) < 0.01);
        assert!(t.elapsed() < std::time::Duration::from_secs(20), "{:?}", t.elapsed());
    }

    #[test]
    fn the_edge_setting_hardens_or_feathers_the_transition() {
        let side = 16;
        let l: Vec<f32> = (0..side * side).map(|i| ((i % side) as f32 - 7.5) * -0.6).collect(); // a gentle ramp
        let seg = SegMask::from_logits(side, &l);
        let (w, h) = (400, 40);
        let soft_px = |edge: f64| {
            let shape = MaskShape::Prompt { text: "x".into(), seg: Some(seg.clone()), detail: vec![], edge };
            let a = shape_alpha(&shape, &frame(w, h), w, h, &Rgb32f::new(w, h), &Plane::new(w, h), 0.0, None);
            assert!(a.get(2, h / 2) > 0.9 && a.get(w - 3, h / 2) < 0.1, "still the same selection at {edge}");
            (0..w).filter(|x| (0.1..0.9).contains(&a.get(*x, h / 2))).count()
        };
        let (hard, plain, soft) = (soft_px(-100.0), soft_px(0.0), soft_px(100.0));
        assert!(hard < plain && plain < soft, "transition widths {hard} < {plain} < {soft}");
    }

    /// The reference's brush (`spread`): a straight stroke's cross-section as it paints one
    /// (measured over our test photos with a 53.5 px brush): feather 1 a hard edge at the radius,
    /// 43 full to 0.75 r and gone at 1.5 r, 100 fading from the centre and still there past 1.5 r;
    /// flow 50 reaches 0.38 at the core, 17 0.07. Feather visibly widens the edge.
    #[test]
    fn the_reference_brush_spreads_its_feather() {
        let (w, h) = (400, 200);
        let r = 40.0;
        let across = |feather: f64, flow: f64| {
            let s = BrushStroke {
                points: vec![Point::new(0.1, 0.5), Point::new(0.9, 0.5)],
                size: r / w as f64,
                feather,
                flow,
                spread: true,
                ..Default::default()
            };
            let a = shape_alpha(&MaskShape::Brush { strokes: vec![s] }, &frame(w, h), w, h, &Rgb32f::new(w, h), &Plane::new(w, h), 0.0, None);
            move |u: f64| a.get(w / 2, (100.0 + u * r) as usize)
        };
        let hard = across(1.0, 100.0);
        assert!(hard(0.0) > 0.99 && hard(0.9) > 0.99 && hard(1.1) < 0.01, "{} {}", hard(0.9), hard(1.1));
        let mid = across(43.0, 100.0);
        assert!(
            mid(0.7) > 0.95 && (0.85..0.95).contains(&mid(1.0)) && (0.36..0.52).contains(&mid(1.27)) && mid(1.55) < 0.05,
            "{} {} {} {}",
            mid(0.7),
            mid(1.0),
            mid(1.27),
            mid(1.55)
        );
        let soft = across(100.0, 100.0);
        assert!(
            soft(0.0) > 0.98 && (0.55..0.8).contains(&soft(1.12)) && (0.2..0.4).contains(&soft(1.57)) && soft(1.94) > 0.08,
            "{} {} {}",
            soft(1.12),
            soft(1.57),
            soft(1.94)
        );
        // a wider edge as feather goes up (where the old brush barely changed)
        let edge = |f: &dyn Fn(f64) -> f32| (0..200).filter(|k| (0.1..0.9).contains(&f(*k as f64 / 100.0))).count();
        assert!(edge(&hard) < 8 && edge(&mid) > 30 && edge(&soft) > edge(&mid) + 15, "{} {} {}", edge(&hard), edge(&mid), edge(&soft));
        let (half, light) = (across(100.0, 50.0), across(100.0, 17.0));
        assert!((0.33..0.43).contains(&half(0.0)) && (0.05..0.1).contains(&light(0.0)), "{} {}", half(0.0), light(0.0));
    }

    #[test]
    fn ai_shapes_without_a_segmentation_select_nothing() {
        let f = frame(30, 30);
        let (img, l) = (Rgb32f::new(30, 30), Plane::new(30, 30));
        for shape in [
            MaskShape::Object { hint: vec![], exclude: vec![], seg: None, detail: vec![], edge: 0.0 },
            MaskShape::Prompt { text: "sky".into(), seg: None, detail: vec![], edge: 0.0 },
            MaskShape::Prompt { text: "sky".into(), seg: Some(SegMask { side: 4, data: "damaged!".into(), rect: None }), detail: vec![], edge: 0.0 },
        ] {
            assert!(shape_alpha(&shape, &f, 30, 30, &img, &l, 0.0, None).data.iter().all(|v| *v == 0.0));
        }
    }

    #[test]
    fn radial_inside_and_invert() {
        let f = frame(100, 100);
        let img = Rgb32f::new(100, 100);
        let l = Plane::new(100, 100);
        let shape = MaskShape::Radial { center: Point::new(0.5, 0.5), rx: 0.2, ry: 0.2, angle: 0.0, feather: 20.0, invert: false };
        let a = shape_alpha(&shape, &f, 100, 100, &img, &l, 0.0, None);
        assert!(a.get(50, 50) > 0.99 && a.get(5, 5) < 0.01);
        let m = Mask { components: vec![MaskComponent { hidden: false, name: None, op: MaskOp::Add, invert: true, shape }], ..Default::default() };
        let e = evaluate(&[m], &f, 100, 100, &img, &l, 0.0, None);
        assert!(e[0].alpha.get(50, 50) < 0.01);
    }

    /// Issue #517: dabs along a long segment (a fast stroke: two pointer samples far apart) are at
    /// most [`dab_spacing`] apart, painting dab by dab (the live stroke) or the whole path at once
    /// (the committed one).
    #[test]
    fn a_long_segment_gets_dabs_at_most_the_spacing_apart() {
        let r = 12.0;
        let pts = [Point::new(10.0, 20.0), Point::new(610.0, 260.0), Point::new(611.0, 260.0), Point::new(40.0, 400.0)];
        let mut live = Vec::new();
        let mut prev = None;
        for q in pts {
            if push_dabs(&mut live, prev, q, r) {
                prev = Some(q);
            }
        }
        assert!(live.len() > 3 * 600 / 12, "{} dabs", live.len());
        assert!(live.windows(2).all(|w| w[0].dist(w[1]) <= dab_spacing(r) + 1e-9));
        assert_eq!(live.first(), pts.first());
        assert_eq!(live.last(), pts.last());
        // the committed stroke places the same dabs (in output pixels; this frame maps 1:1 scaled)
        let (w, h) = (1000, 500);
        let f = frame(w, h);
        let stroke = BrushStroke { points: pts.map(|p| Point::new(p.x / w as f64, p.y / h as f64)).to_vec(), ..Default::default() };
        let committed = brush_dabs(&stroke, &f, w, h);
        let mut again = Vec::new();
        let mut prev = None;
        for p in &stroke.points {
            let q = f.norm_to_out(w, h).apply(*p);
            if push_dabs(&mut again, prev, q, committed.r) {
                prev = Some(q);
            }
        }
        assert_eq!(committed.dabs, again);
        assert!(committed.dabs.windows(2).all(|w| w[0].dist(w[1]) <= dab_spacing(committed.r) + 1e-9));
        // many huge segments stop at the stroke budget instead of adding up without bound
        let mut big = Vec::new();
        let mut at = Point::new(0.0, 0.0);
        for i in 0..5 {
            let q = Point::new(if i % 2 == 0 { 1e12 } else { 0.0 }, 0.0);
            if push_dabs(&mut big, Some(at), q, 1.0) {
                at = q;
            }
        }
        assert!(big.len() <= MAX_STROKE_DABS, "{} dabs", big.len());
        assert!(!push_dabs(&mut big, Some(at), Point::new(5.0, 5.0), 1.0), "a full stroke takes no more");
        // a hostile segment places no in-between dabs
        let mut v = Vec::new();
        assert!(!push_dabs(&mut v, Some(Point::new(f64::NAN, 0.0)), Point::new(1.0, 1.0), r));
        assert!(!push_dabs(&mut v, None, Point::new(f64::INFINITY, 1.0), r));
        push_dabs(&mut v, Some(Point::new(0.0, 0.0)), Point::new(1e300, 1.0), 0.0);
        assert!(v.len() <= 1_000_002, "{}", v.len());
    }

    /// A temporary output buffer must spend the same stroke budget as a retained one.
    #[test]
    fn incremental_dab_buffers_share_the_stroke_budget() {
        let points = [Point::new(20.0, 20.0), Point::new(80.0, 20.0), Point::new(200.0, 80.0), Point::new(21.0, 20.0)];
        let mut retained = Vec::new();
        let mut streamed = Vec::new();
        let mut retained_count = MAX_STROKE_DABS - 3;
        let mut streamed_count = retained_count;
        let mut prev = None;
        for q in points {
            let mut chunk = Vec::new();
            let accepted = push_dabs_counted(&mut retained, &mut retained_count, prev, q, 10.0);
            assert_eq!(accepted, push_dabs_counted(&mut chunk, &mut streamed_count, prev, q, 10.0));
            streamed.extend(chunk);
            if accepted {
                prev = Some(q);
            }
        }
        assert_eq!(retained, streamed);
        assert_eq!(retained.len(), 3);
        assert_eq!(retained_count, MAX_STROKE_DABS);
        assert_eq!(streamed_count, retained_count);
        assert_eq!(prev, Some(points[1]));
        assert!(!retained.contains(&points[2]));
        assert!(!retained.contains(&points[3]));
    }

    /// Pen pressure scales each dab's radius and flow; a stroke without pressure data paints as before, and hostile
    /// pressures (NaN, out of range, a short list) neither panic nor paint more than full pressure.
    #[test]
    fn pen_pressure_scales_dab_size_and_flow() {
        let (w, h) = (400, 100);
        let f = frame(w, h);
        let img = Rgb32f::new(w, h);
        let l = Plane::new(w, h);
        let line = vec![Point::new(0.1, 0.5), Point::new(0.9, 0.5)];
        let alpha = |pressure: Vec<f32>| {
            let s = BrushStroke { points: line.clone(), size: 0.05, feather: 0.0, flow: 100.0, pressure, ..Default::default() };
            shape_alpha(&MaskShape::Brush { strokes: vec![s] }, &f, w, h, &img, &l, 0.0, None)
        };
        let full = alpha(vec![]);
        assert_eq!(full.data, alpha(vec![1.0, 1.0]).data, "full pressure is no pressure");
        // light at the start, full at the end: thin and faint on the left, as wide as the brush on the right
        let ramp = alpha(vec![0.1, 1.0]);
        let width = |a: &Plane, x: usize| (0..h).filter(|y| a.get(x, *y) > 0.01).count();
        assert!(width(&ramp, 60) < width(&full, 60) / 2, "{} vs {}", width(&ramp, 60), width(&full, 60));
        assert!(width(&ramp, 350) + 2 >= width(&full, 350));
        assert!(ramp.get(60, 50) < full.get(60, 50) + 1e-6);
        let faint = alpha(vec![0.2]);
        assert!(faint.data.iter().zip(&full.data).all(|(a, b)| *a <= *b + 1e-6), "never more than full pressure");
        assert!(faint.get(200, 50) > 0.0 && faint.get(200, 50) < full.get(200, 50));
        for hostile in [vec![f32::NAN, f32::INFINITY], vec![-3.0], vec![0.5; 10_000]] {
            let a = alpha(hostile);
            assert!(a.data.iter().all(|v| v.is_finite() && (0.0..=1.0).contains(v)));
        }
        assert_eq!(pressure_radius(10.0, f32::NAN), 10.0);
        assert_eq!(pressure_radius(10.0, 0.0), 1.0);
        assert_eq!(pressure_flow(0.8, 2.0), 0.8);
    }

    #[test]
    fn brush_and_subtract() {
        let f = frame(200, 100);
        let img = Rgb32f::new(200, 100);
        let l = Plane::new(200, 100);
        let stroke = BrushStroke { points: vec![Point::new(0.1, 0.5), Point::new(0.9, 0.5)], size: 0.05, ..Default::default() };
        let erase = BrushStroke { points: vec![Point::new(0.5, 0.5)], size: 0.05, erase: true, feather: 0.0, ..Default::default() };
        let m = Mask {
            components: vec![MaskComponent {
                hidden: false,
                name: None,
                op: MaskOp::Add,
                invert: false,
                shape: MaskShape::Brush { strokes: vec![stroke, erase] },
            }],
            ..Default::default()
        };
        let e = evaluate(&[m], &f, 200, 100, &img, &l, 0.0, None);
        let a = &e[0].alpha;
        assert!(a.get(40, 50) > 0.9, "{}", a.get(40, 50));
        assert!(a.get(100, 50) < 0.05, "erased centre {}", a.get(100, 50));
        assert!(a.get(40, 5) < 0.01);
    }

    /// #518: holding the brush still adds no coverage. A press plus any number of stationary
    /// pointer samples paints exactly the single dab of the press, whatever the frame rate.
    #[test]
    fn holding_the_brush_still_adds_no_coverage() {
        let (w, h) = (200, 100);
        let f = frame(w, h);
        let img = Rgb32f::new(w, h);
        let l = Plane::new(w, h);
        let p = Point::new(0.5, 0.5);
        let spot = |samples: usize, flow: f64| {
            let s = BrushStroke { points: vec![p; samples], size: 0.1, feather: 80.0, flow, ..Default::default() };
            shape_alpha(&MaskShape::Brush { strokes: vec![s] }, &f, w, h, &img, &l, 0.0, None)
        };
        for flow in [100.0, 40.0] {
            let press = spot(1, flow);
            // the feathered edge is soft to begin with
            let edge = press.get(100 + 14, 50);
            assert!(edge > 0.05 && edge < 0.6, "flow {flow}: edge {edge}");
            for n in [2, 5, 60, 600] {
                let held = spot(n, flow);
                assert!(held.data == press.data, "flow {flow}: {n} stationary samples hardened the edge {edge} → {}", held.get(100 + 14, 50));
            }
        }
        let s = BrushStroke { points: vec![p; 60], size: 0.1, ..Default::default() };
        assert_eq!(brush_dabs(&s, &f, w, h).dabs.len(), 1, "one dab for the press");
        // a stroke that pauses partway lays the same dabs as one that doesn't
        let (a, b) = (Point::new(0.2, 0.5), Point::new(0.8, 0.5));
        let paused = BrushStroke { points: vec![a, p, p, p, p, b], flow: 30.0, ..Default::default() };
        let smooth = BrushStroke { points: vec![a, p, b], flow: 30.0, ..Default::default() };
        assert_eq!(brush_dabs(&paused, &f, w, h).dabs, brush_dabs(&smooth, &f, w, h).dabs);
    }

    /// Flow builds up by passes: going back over an area in the same stroke adds coverage.
    #[test]
    fn a_second_pass_builds_up_with_low_flow() {
        let (w, h) = (200, 100);
        let f = frame(w, h);
        let img = Rgb32f::new(w, h);
        let l = Plane::new(w, h);
        let (a, b) = (Point::new(0.2, 0.5), Point::new(0.8, 0.5));
        let paint = |points: Vec<Point>| {
            let s = BrushStroke { points, size: 0.05, feather: 50.0, flow: 20.0, ..Default::default() };
            shape_alpha(&MaskShape::Brush { strokes: vec![s] }, &f, w, h, &img, &l, 0.0, None)
        };
        let once = paint(vec![a, b]);
        let twice = paint(vec![a, b, a]);
        let thrice = paint(vec![a, b, a, b]);
        let (o, t, th) = (once.get(100, 50), twice.get(100, 50), thrice.get(100, 50));
        assert!(o > 0.05 && o < 0.99, "one pass {o}");
        assert!(t > o + 0.02 && th > t + 0.01, "passes build up: {o} → {t} → {th}");
    }

    #[test]
    fn auto_mask_stops_at_edges() {
        // dark left half, bright right half; a stroke along the boundary, centred on the dark side
        let (w, h) = (200, 100);
        let f = frame(w, h);
        let img = Rgb32f::from_fn(w, h, |x, _| if x < 100 { [0.03; 3] } else { [0.6, 0.5, 0.4] });
        let l = img.map(crate::local::log_lum);
        let stroke = |auto_mask| BrushStroke {
            points: vec![Point::new(0.47, 0.2), Point::new(0.47, 0.8)],
            size: 0.08,
            feather: 0.0,
            auto_mask,
            ..Default::default()
        };
        let comp = |auto| MaskShape::Brush { strokes: vec![stroke(auto)] };
        let plain = shape_alpha(&comp(false), &f, w, h, &img, &l, 0.0, None);
        let auto = shape_alpha(&comp(true), &f, w, h, &img, &l, 0.0, None);
        // without Auto Mask the brush spills over the edge; with it, it stays on the dark side
        assert!(plain.get(105, 50) > 0.9, "{}", plain.get(105, 50));
        assert!(auto.get(105, 50) < 0.05, "spill {}", auto.get(105, 50));
        assert!(auto.get(110, 50) < 0.02);
        assert!(auto.get(90, 50) > 0.9, "painted side {}", auto.get(90, 50));
        assert!(auto.get(99, 50) > 0.8, "up to the edge {}", auto.get(99, 50));
        // on a flat area Auto Mask paints like the plain brush
        let flat = Rgb32f::from_fn(w, h, |_, _| [0.2; 3]);
        let fl = flat.map(crate::local::log_lum);
        let a = shape_alpha(&comp(true), &f, w, h, &flat, &fl, 0.0, None);
        let b = shape_alpha(&comp(false), &f, w, h, &flat, &fl, 0.0, None);
        assert!((a.get(94, 50) - b.get(94, 50)).abs() < 0.02, "{} vs {}", a.get(94, 50), b.get(94, 50));
    }

    /// Refine Edges: a soft mask over a hard edge in the photo snaps to that edge.
    #[test]
    fn refine_edges_follows_the_photo() {
        let (w, h) = (120usize, 80usize);
        // left half dark, right half bright
        let img = Rgb32f::from_fn(w, h, |x, _| if x < 60 { [0.05; 3] } else { [0.6; 3] });
        let log_l = Plane::from_fn(w, h, |x, _| if x < 60 { (0.05f32).log2() } else { (0.6f32).log2() });
        let f = Frame::new(w, h, &Default::default(), true);
        let soft = Mask {
            components: vec![MaskComponent {
                hidden: false,
                name: None,
                op: MaskOp::Add,
                invert: false,
                shape: MaskShape::Linear { start: Point::new(0.75, 0.5), end: Point::new(0.25, 0.5) },
            }],
            ..Default::default()
        };
        let refined = Mask { refine: 100.0, ..soft.clone() };
        let step = |m: &Mask| {
            let a = evaluate_one(m, &f, w, h, &img, &log_l, 0.0, None);
            a.data[40 * w + 63] - a.data[40 * w + 56]
        };
        let (plain, sharp) = (step(&soft), step(&refined));
        assert!(sharp > plain * 1.5 && sharp > 0.1, "the edge in the mask follows the photo's: {plain} → {sharp}");
    }

    /// A hue-first colour range selects as the reference does (its masks measured over our test
    /// photos): from a mid grey, a grey 21 L* lighter about half and one 30 L* darker a little; from
    /// a deep blue, a lighter blue mostly and a pale one partly, a brown and near black not at all;
    /// Refine widens it.
    #[test]
    fn hue_first_selects_by_hue_as_the_reference_does() {
        use lightcraft_color::{REC2020, SRGB, transfer::decode_srgb8};
        let m = SRGB.to_space(&REC2020);
        let scene = |rgb: [u8; 3]| m.apply_f32(rgb.map(decode_srgb8));
        let patches: [[u8; 3]; 7] = [[120, 120, 120], [174, 174, 174], [47, 47, 47], [99, 107, 135], [142, 147, 173], [197, 164, 164], [2, 3, 6]];
        let (w, h) = (60 * patches.len(), 20);
        let img = Rgb32f::from_fn(w, h, |x, _| scene(patches[x / 60]));
        let log_l = img.map(crate::local::log_lum);
        let f = Frame::new(w, h, &Default::default(), true);
        let lab = |rgb: [u8; 3]| lightcraft_color::perceptual::oklab_from_2020(scene(rgb)).map(f64::from);
        let picked = |sample: [u8; 3], refine: f64| {
            let shape = MaskShape::ColorRange { samples: vec![lab(sample)], refine, area: None, picked: vec![], hue_first: true };
            let a = shape_alpha(&shape, &f, w, h, &img, &log_l, 0.0, None);
            (0..patches.len()).map(|k| a.get(k * 60 + 30, 10)).collect::<Vec<f32>>()
        };
        let grey = picked([120, 120, 120], 50.0);
        assert!(grey[0] > 0.99 && (0.45..0.65).contains(&grey[1]) && (0.12..0.32).contains(&grey[2]), "{grey:?}");
        let blue = picked([41, 47, 74], 50.0);
        assert!((0.65..0.85).contains(&blue[3]) && (0.33..0.53).contains(&blue[4]) && blue[5] < 0.01, "{blue:?}");
        assert!(blue[6] < 0.01, "near black isn't blue, however bluish its noise: {blue:?}");
        let (tight, wide) = (picked([41, 47, 74], 6.0), picked([41, 47, 74], 100.0));
        assert!(tight[4] < 0.01 && wide[4] > 0.65 && tight[3] < blue[3] && wide[3] > blue[3], "{tight:?} {wide:?}");
        // the old way (toned down, L at half weight) is far tighter
        let old = color_select(&[[0.5, 0.0, 0.0]], 50.0, None, false);
        assert!(old.tol < 0.05 && old.toned && old.l_weights == [0.5]);
    }

    /// Issue #157: a colour-range sample of one flat patch must not select a clearly different
    /// patch. The two patches are the ones from the report (sRGB 120,150,155 and 200,198,195),
    /// sampled the way `mask.sampleColor` does (3×3 mean of the selection-space OkLab).
    #[test]
    fn color_range_keeps_to_the_sampled_colour() {
        use lightcraft_color::{REC2020, SRGB, transfer::decode_srgb8};
        let (w, h) = (120usize, 40usize);
        let m = SRGB.to_space(&REC2020);
        let patch = |rgb: [u8; 3]| m.apply_f32(rgb.map(decode_srgb8));
        let (left, right) = (patch([120, 150, 155]), patch([200, 198, 195]));
        let img = Rgb32f::from_fn(w, h, |x, _| if x < 60 { left } else { right });
        let log_l = img.map(crate::local::log_lum);
        let f = Frame::new(w, h, &Default::default(), true);
        let mut sample = [0f64; 3];
        for y in 19..=21 {
            for x in 29..=31 {
                let lab = lightcraft_color::perceptual::oklab_from_2020(tonemap_for_select(img.data[y * w + x]));
                for k in 0..3 {
                    sample[k] += lab[k] as f64 / 9.0;
                }
            }
        }
        let alpha = |refine| {
            let shape = MaskShape::ColorRange { samples: vec![sample], refine, area: None, picked: vec![], hue_first: false };
            let a = shape_alpha(&shape, &f, w, h, &img, &log_l, 0.0, None);
            (a.get(30, 20), a.get(90, 20))
        };
        for refine in [0.0, 50.0, 85.0] {
            let (l, r) = alpha(refine);
            assert!(l > 0.95, "refine {refine}: sampled patch {l}");
            assert!(r < 0.05, "refine {refine}: other patch {r}");
        }
        // Refine 100 reaches furthest (the grey is ~0.063 away, the fade ends at 0.075), but the
        // other patch stays mostly out
        let (l, r) = alpha(100.0);
        assert!(l > 0.95 && r < 0.3, "refine 100: {l} vs {r}");
        // an area over both patches spans both colours, so it selects them both
        let lab = |c: [f32; 3]| lightcraft_color::perceptual::oklab_from_2020(tonemap_for_select(c)).map(f64::from);
        let (a, b) = (lab(left), lab(right));
        let area = [[a[0].min(b[0]), a[1].min(b[1]), a[2].min(b[2])], [a[0].max(b[0]), a[1].max(b[1]), a[2].max(b[2])]];
        let shape = MaskShape::ColorRange { samples: vec![], refine: 50.0, area: Some(area), picked: vec![], hue_first: false };
        let both = shape_alpha(&shape, &f, w, h, &img, &log_l, 0.0, None);
        assert!(both.get(30, 20) > 0.95 && both.get(90, 20) > 0.95, "{} {}", both.get(30, 20), both.get(90, 20));
    }

    /// A hidden component is left out of its mask: hiding the one that subtracts gives the whole
    /// radial back, hiding them all selects nothing.
    #[test]
    fn hidden_components_are_left_out() {
        let (w, h) = (64usize, 64usize);
        let img = Rgb32f::filled(w, h, [0.2, 0.2, 0.2]);
        let log_l = img.map(crate::local::log_lum);
        let f = Frame::new(w, h, &Default::default(), true);
        let radial = MaskShape::Radial { center: Point::new(0.5, 0.5), rx: 0.4, ry: 0.4, angle: 0.0, feather: 0.0, invert: false };
        let all = MaskShape::Linear { start: Point::new(0.5, 2.0), end: Point::new(0.5, 3.0) };
        let comp = |op, shape, hidden| MaskComponent { hidden, name: None, op, invert: false, shape };
        let mut m = Mask { components: vec![comp(MaskOp::Add, radial, false), comp(MaskOp::Subtract, all, false)], ..Default::default() };
        let centre = |m: &Mask| evaluate_one(m, &f, w, h, &img, &log_l, 0.0, None).get(32, 32);
        assert!(centre(&m) < 0.01, "subtracted: {}", centre(&m));
        m.components[1].hidden = true;
        assert!(centre(&m) > 0.99, "the subtraction hidden: {}", centre(&m));
        m.components[0].hidden = true;
        assert!(centre(&m) < 0.01, "all hidden: {}", centre(&m));
    }

    /// A ranked range picked at a rank: 0.1 either side, fading over 0.25 more, a bound within
    /// 0.15 of an end taken to it (the reference's readings, in hundredths: lo − feather, lo, hi,
    /// hi + feather).
    #[test]
    fn picked_ranked_ranges() {
        let shown = |(lo, hi, lf, hf): (f64, f64, f64, f64)| [lo - lf, lo, hi, hi + hf].map(|v| (v * 100.0).round() as i32);
        assert_eq!(shown(picked_ranked_range(0.01, None)), [0, 0, 11, 36]);
        assert_eq!(shown(picked_ranked_range(0.16, None)), [0, 0, 26, 51]);
        assert_eq!(shown(picked_ranked_range(0.32, None)), [0, 22, 42, 67]);
        assert_eq!(shown(picked_ranked_range(0.61, None)), [26, 51, 71, 96]);
        assert_eq!(shown(picked_ranked_range(0.74, None)), [39, 64, 84, 100]);
        assert_eq!(shown(picked_ranked_range(0.75, None)), [40, 65, 100, 100]);
        assert_eq!(shown(picked_ranked_range(0.89, None)), [54, 79, 100, 100]);
        // an area: from its darkest to its lightest
        assert_eq!(shown(picked_ranked_range(0.7, Some(0.3))), [5, 30, 70, 95]);
    }

    /// Ranks come from the whole photo: on a left-to-right ramp a pixel's rank is how far across
    /// it is, and a ranked range selects that share of the photo whatever the exposure.
    #[test]
    fn ranked_luminance_selects_a_share_of_the_photo() {
        let (w, h) = (400, 40);
        let img = Rgb32f::from_fn(w, h, |x, _| [((x as f32 + 0.5) / w as f32 * 8.0 - 9.0).exp2(); 3]);
        let ranks = luminance_ranks(&rank_copy(&img));
        assert_eq!(ranks.len(), RANK_STEPS + 1);
        assert!(ranks.windows(2).all(|p| p[0] <= p[1]));
        let l = img.map(crate::local::log_lum);
        for x in [40, 200, 330] {
            let r = rank_of(&ranks, l.data[x]);
            assert!((r - x as f32 / w as f32).abs() < 0.02, "{x}: {r}");
        }
        assert_eq!((rank_of(&ranks, -30.0), rank_of(&ranks, 30.0)), (0.0, 1.0));
        let shape = |ev: f32| {
            let s = MaskShape::LuminanceRange {
                lo: 0.4,
                hi: 0.6,
                lo_feather: 0.001,
                hi_feather: 0.001,
                picked: vec![],
                ranked: true,
                ranks: Some(std::sync::Arc::new(ranks.clone())),
            };
            shape_alpha(&s, &frame(w, h), w, h, &img, &l, ev, None)
        };
        let a = shape(0.0);
        let share = a.data.iter().sum::<f32>() / a.data.len() as f32;
        assert!((share - 0.2).abs() < 0.02, "{share}");
        assert!(a.data[h / 2 * w + w / 2] > 0.99 && a.data[h / 2 * w + w / 10] < 0.01);
        assert_eq!(shape(2.0), a, "exposure moves every pixel alike");
    }

    /// The luminance picker's lightness and the range scale map back and forth; a point picks 0.1
    /// either side of it and fades over 0.25 more; white is the top of the scale.
    #[test]
    fn picked_luminance_ranges() {
        for l in [0.05, 0.3, 0.46, 0.66, 0.9, 0.99] {
            let back = lightness_from_range(range_from_lightness(l));
            assert!((back - l).abs() < 1e-9, "{l} → {back}");
        }
        // middle grey (L* 49.5) sits at 8/12 of the scale; white is its top, black its foot
        assert!((range_from_lightness((116.0 * 0.18f64.cbrt() - 16.0) / 100.0) - 8.0 / 12.0).abs() < 1e-6, "middle grey");
        assert_eq!((range_from_lightness(1.0), lightness_from_range(1.0)), (1.0, 1.0));
        assert_eq!((range_from_lightness(0.0), lightness_from_range(0.0)), (0.0, 0.0));
        let (lo, hi, lf, hf) = picked_luminance_range(0.66, None);
        let l = |v: f64| (lightness_from_range(v) * 100.0).round();
        assert_eq!((l(lo - lf), l(lo), l(hi), l(hi + hf)), (31.0, 56.0, 76.0, 100.0));
        let (lo, hi, lf, hf) = picked_luminance_range(0.4, Some(1.0));
        assert_eq!((l(lo - lf), l(lo), l(hi), l(hi + hf)), (15.0, 40.0, 100.0, 100.0));
        assert_eq!(hi, 1.0, "white reaches the top of the scale");
    }

    /// A half-resolution matte selecting the left `frac` of a `w × h` source.
    fn left_matte(w: usize, h: usize, frac: f64) -> Image<u8> {
        Image::from_fn(w / 2, h / 2, |x, _| if (x as f64 + 0.5) < frac * (w / 2) as f64 { 255 } else { 0 })
    }

    /// The Sky mask follows the source's sky matte where it has one (here: the bottom-left of a
    /// uniformly bright blue photo, where the heuristic sees no sky, and not its top right, where
    /// it does), through user orientation, at any output size; without one it keeps the heuristic.
    #[test]
    fn sky_uses_the_sources_matte() {
        let (w, h) = (200, 100);
        let img = Rgb32f::filled(w, h, [0.5, 0.7, 1.4]);
        let l = img.map(crate::local::log_lum);
        let mut mattes = Mattes::default();
        mattes.push(MatteKind::Sky, left_matte(w, h, 0.5));
        let f = frame(w, h);
        let heuristic = shape_alpha(&MaskShape::Sky, &f, w, h, &img, &l, 0.0, None);
        let matte = shape_alpha(&MaskShape::Sky, &f, w, h, &img, &l, 0.0, Some(&mattes));
        assert!(heuristic.get(150, 5) > 0.9 && heuristic.get(25, 90) < 0.1, "{} {}", heuristic.get(150, 5), heuristic.get(25, 90));
        assert!(matte.get(150, 5) < 0.01 && matte.get(25, 90) > 0.99, "{} {}", matte.get(150, 5), matte.get(25, 90));
        // mattes of other kinds leave the heuristic alone
        let mut hair = Mattes::default();
        hair.push(MatteKind::Hair, left_matte(w, h, 0.5));
        assert_eq!(shape_alpha(&MaskShape::Sky, &f, w, h, &img, &l, 0.0, Some(&hair)), heuristic);
        // rotated 90° clockwise, the source's left half is the top half; a preview and a larger
        // render agree
        let s = lightcraft_develop::DevelopSettings { orientation: Orientation::Rotate90, ..Default::default() };
        let f = Frame::new(w, h, &s, true);
        for (ow, oh) in [(100, 200), (25, 50)] {
            let small = Rgb32f::filled(ow, oh, [0.5, 0.7, 1.4]);
            let a = shape_alpha(&MaskShape::Sky, &f, ow, oh, &small, &small.map(crate::local::log_lum), 0.0, Some(&mattes));
            let at = |x: f64, y: f64| a.get((x * ow as f64) as usize, (y * oh as f64) as usize);
            assert!(at(0.5, 0.2) > 0.99 && at(0.5, 0.8) < 0.01, "{ow}×{oh}: {} {}", at(0.5, 0.2), at(0.5, 0.8));
        }
    }

    /// Subject, Background and People use person mattes: the whole person is the union of the
    /// parts; a People part without a matte keeps the heuristic.
    #[test]
    fn people_and_subject_use_person_mattes() {
        let (w, h) = (200, 100);
        let img = Rgb32f::filled(w, h, [0.2; 3]);
        let l = img.map(crate::local::log_lum);
        let mut mattes = Mattes::default();
        mattes.push(MatteKind::Skin, left_matte(w, h, 0.25));
        let right: Image<u8> = Image::from_fn(w / 2, h / 2, |x, _| if x >= 75 { 255 } else { 0 });
        mattes.push(MatteKind::Hair, right);
        let f = frame(w, h);
        let eval = |shape: &MaskShape| shape_alpha(shape, &f, w, h, &img, &l, 0.0, Some(&mattes));
        let subject = eval(&MaskShape::Subject);
        assert!(subject.get(10, 50) > 0.99 && subject.get(190, 50) > 0.99 && subject.get(100, 50) < 0.01);
        let background = eval(&MaskShape::Background);
        assert!(background.get(100, 50) > 0.99 && background.get(10, 50) < 0.01);
        let hair = eval(&MaskShape::People { person: 0, parts: vec!["Hair".into()] });
        assert!(hair.get(190, 50) > 0.99 && hair.get(10, 50) < 0.01);
        let lips = MaskShape::People { person: 0, parts: vec!["Lips".into()] };
        assert_eq!(eval(&lips), shape_alpha(&lips, &f, w, h, &img, &l, 0.0, None));
    }
}
