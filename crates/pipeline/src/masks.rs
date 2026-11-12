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
    // its coordinates on the photo before the warp, or (an old mask) on the corrected frame
    let frame = &*frame.for_mask(m.on_photo);
    let mut alpha = Plane::new(w, h);
    let mut first = true;
    for comp in m.components.iter().filter(|c| !c.hidden) {
        let mut c = shape_alpha(&comp.shape, frame, w, h, img, log_l, ev, mattes);
        if comp.invert {
            c.data.iter_mut().for_each(|v| *v = 1.0 - *v);
        }
        if first {
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
    let m = frame.out_to_mask(w, h);
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
            let m = frame.out_to_mask(w, h);
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
            sample_seg(seg.as_ref(), detail, gain, frame, w, h, log_l, &mut out);
            if e > 0.0 {
                smooth_plane(&mut out, e * 0.02 * frame_px(frame, w));
            }
        }
        // not computed yet (no clicks, or a build without the model): nothing selected
        MaskShape::Object { hint, .. } if hint.is_empty() => {}
        MaskShape::Prompt { .. } => {}
        MaskShape::Subject | MaskShape::Object { .. } | MaskShape::People { .. } => {
            // Saliency heuristic: centre-weighted local contrast (replaced by the segmenter in M12).
            let m = frame.out_to_mask(w, h);
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
pub struct SegGrid {
    logits: Vec<f32>,
    side: usize,
    r: [f64; 4],
}

impl SegGrid {
    pub fn new(seg: &SegMask) -> Option<SegGrid> {
        Some(SegGrid { logits: seg.logits()?, side: seg.side as usize, r: seg.bounds()? })
    }

    pub fn contains(&self, n: Point) -> bool {
        n.x >= self.r[0] && n.x <= self.r[2] && n.y >= self.r[1] && n.y <= self.r[3]
    }

    /// The bilinearly sampled logit at normalized image point `n` (cell centres at
    /// `(i + 0.5) / side` of the rectangle).
    pub fn logit(&self, n: Point) -> f32 {
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

    /// Output pixels per grid cell (the longer side of a cell: the grid is square over a
    /// rectangle).
    fn cell_px(&self, frame: &Frame, w: usize) -> f64 {
        let px = frame.px_per_long(w) / frame.ow.max(frame.oh).max(1e-9);
        ((self.r[2] - self.r[0]) * frame.ow).max((self.r[3] - self.r[1]) * frame.oh) * px / self.side.max(1) as f64
    }
}

/// [`sample_seg`]'s window, in grid cells (Gaussian sigma): the width of the band bilinear
/// enlargement leaves uncertain, about a cell on either side of the contour.
const SEG_WINDOW: f64 = 1.5;
/// [`sample_seg`]'s guided-filter regularization (EV² of log luminance): an edge of ~0.1 EV
/// contrast counts half.
const SEG_EPS: f32 = 0.01;

/// Stored model segmentations at each output pixel: the zoomed-in `detail` passes inside
/// their rectangles (the highest of them where they overlap), the whole-photo `seg`
/// elsewhere; the logit (times `gain`: > 1 is a harder edge) through a sigmoid. Damaged
/// data selects nothing.
///
/// Issue #3272: a model's grid is much coarser than an export (a 288² grid has ~20 px cells on
/// 24 MP), and its bilinear contour passes up to a cell from the photo's edge, so the mask spilled
/// onto the background (a bright outline under a raised Exposure). Where the grid is coarser than
/// the output, the logits are guided-filtered by the photo's log luminance ([`guided_cross_fast`]; a
/// window of [`SEG_WINDOW`] cells, so previews and exports draw the same edge): near the contour
/// the logit follows the photo's tones and its edge lands on the photo's. Filtering logits, not
/// coverage, makes the confident side win (a strip at 0.3 between 1 and 0 goes to 0, not 0.1),
/// and leaves a linear ramp of logits, where the photo has no edge, as it was. Pixels whose
/// window is all inside or all outside (|mean logit| ≥ 7, times `gain`) keep their sample exactly,
/// as do those where the photo has no edge to follow. Refine Edges
/// then works on the combined mask ([`evaluate_one`]) and finds these edges already in place.
#[allow(clippy::too_many_arguments)]
fn sample_seg(seg: Option<&SegMask>, detail: &[SegMask], gain: f32, frame: &Frame, w: usize, h: usize, log_l: &Plane, out: &mut Plane) {
    let coarse = seg.and_then(SegGrid::new);
    // (capped: a hostile document can hold many)
    let patches: Vec<SegGrid> = detail.iter().take(lightcraft_develop::segmask::MAX_DETAIL).filter_map(SegGrid::new).collect();
    if coarse.is_none() && patches.is_empty() {
        return;
    }
    let m = frame.out_to_mask(w, h);
    for_rows(&mut out.data, w, |y, row| {
        for (x, v) in row.iter_mut().enumerate() {
            let n = m.apply(Point::new(x as f64 + 0.5, y as f64 + 0.5));
            let fine = patches.iter().filter(|p| p.contains(n)).map(|p| p.logit(n)).reduce(f32::max);
            *v = fine.or_else(|| coarse.as_ref().map(|c| c.logit(n))).unwrap_or(-16.0) * gain;
        }
    });
    // (the coarsest grid sets the window: it leaves the widest uncertain band)
    let cell = coarse.iter().chain(&patches).map(|g| g.cell_px(frame, w)).fold(0.0, f64::max);
    if cell >= 1.0 {
        guided_cross_fast(log_l, out, (SEG_WINDOW * cell) as f32, SEG_EPS, |mean| 1.0 - smooth(3.0 * gain, 7.0 * gain, mean.abs()));
    }
    out.map_in_place(|l| 1.0 / (1.0 + (-l).exp()));
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
    // (a settings value built in code can skip the deserializer's cap: cost is pixels x samples)
    let samples = samples.get(..lightcraft_develop::MAX_COLOR_RANGE_SAMPLES).unwrap_or(samples);
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
    brush_dabs_within(s, frame, w, h, MAX_STROKE_DABS)
}

/// [`brush_dabs`] stopping at `max` dabs instead of [`MAX_STROKE_DABS`].
///
/// For a caller that discards a stroke past a threshold of its own: it builds to that threshold
/// rather than to the stroke budget and throws the difference away. The GPU path rejects a brush
/// mask past 4096 dabs in total and the Auto Mask spot window past 200,000, both far below the
/// two million a single stroke may otherwise construct first (#1520).
///
/// Ask for one more than the threshold to tell "at it" from "past it": a result shorter than
/// `max` is the whole stroke.
pub fn brush_dabs_within(s: &BrushStroke, frame: &Frame, w: usize, h: usize, max: usize) -> BrushDabs {
    let to_out = frame.mask_to_out(w, h);
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
    let mut emitted = 0usize;
    for (i, p) in s.points.iter().enumerate() {
        if emitted >= max {
            break;
        }
        // (a point the warp can't place lays no dab, rather than one at its unwarped place)
        let Some(q) = to_out(*p) else { continue };
        let before = dabs.len();
        if push_dabs_within(&mut dabs, &mut emitted, prev, q, r, max) {
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
    push_dabs_within(dabs, emitted, prev, q, r, MAX_STROKE_DABS)
}

/// [`push_dabs_counted`] against a caller's own budget rather than [`MAX_STROKE_DABS`].
///
/// A caller that will not look at more than `max` dabs should build no more than `max`: a length
/// check after the vector exists bounds nothing, it only throws away what was already paid for
/// (#1520).
pub fn push_dabs_within(dabs: &mut Vec<Point>, emitted: &mut usize, prev: Option<Point>, q: Point, r: f64, max: usize) -> bool {
    let room = max.min(MAX_STROKE_DABS).saturating_sub(*emitted);
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
        let n = if n.is_finite() && n <= 1_000_000.0 { n.max(0.0) as usize } else { 0 };
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

/// A guided filter's linear model at each pixel (`q = a · guide + b`, coefficients averaged over
/// the windows that hold the pixel), the window mean of the filtered plane and, when asked for,
/// how much of an edge the guide has there (`var / (var + eps)`, averaged like the coefficients:
/// 0 where the guide is flat and the model is only a blur).
struct Guided {
    a: Plane,
    b: Plane,
    mean: Plane,
    edge: Option<Plane>,
}

/// The guided filter (He, Sun and Tang) of `p` steered by `guide`, both the same size: Gaussian
/// windows of `sigma` px, `eps` the regularization (in guide units squared).
fn guided_coeffs(guide: &Plane, p: &Plane, sigma: f32, eps: f32, with_edge: bool) -> Guided {
    use lightcraft_raster::blur::gaussian;
    let mi = gaussian(guide, sigma);
    let mp = gaussian(p, sigma);
    let cip = gaussian(&guide.zip_map(p, |a, b| a * b), sigma);
    let cii = gaussian(&guide.map(|a| a * a), sigma);
    let mut a = Plane::new(p.width, p.height);
    let mut b = Plane::new(p.width, p.height);
    let mut edge = Plane::new(if with_edge { p.width } else { 0 }, if with_edge { p.height } else { 0 });
    for i in 0..p.data.len() {
        let var = (cii.data[i] - mi.data[i] * mi.data[i]).max(0.0);
        let k = (cip.data[i] - mi.data[i] * mp.data[i]) / (var + eps);
        a.data[i] = k;
        b.data[i] = mp.data[i] - k * mi.data[i];
        if let Some(e) = edge.data.get_mut(i) {
            *e = var / (var + eps);
        }
    }
    Guided { a: gaussian(&a, sigma), b: gaussian(&b, sigma), mean: mp, edge: with_edge.then(|| gaussian(&edge, sigma)) }
}

/// Guided filter of `p` steered by `guide` (He et al.), clamped to 0..1: `p`'s edges snap to the
/// guide's.
pub fn guided_cross(guide: &Plane, p: &Plane, sigma: f32, eps: f32) -> Plane {
    let g = guided_coeffs(guide, p, sigma, eps, false);
    let mut q = Plane::new(p.width, p.height);
    for i in 0..q.data.len() {
        q.data[i] = (g.a.data[i] * guide.data[i] + g.b.data[i]).clamp(0.0, 1.0);
    }
    q
}

/// [`guided_cross`] in its subsampled form (He and Sun, "Fast Guided Filter", 2015, as
/// [`crate::local::guided_fast`] is of the self-guided filter, at its step), in place and
/// unclamped, for a `p` that is smooth at the window's scale (a mask enlarged from a model's grid):
/// the linear model is fitted on both planes box-reduced to about 3 px of window, sampled
/// bilinearly and applied to the full-resolution guide, which is where the edge comes from. A
/// pixel moves to the filtered value by `weight` of its window mean of `p` times how much of an
/// edge the guide has there (0 keeps it exactly: where the photo is flat the filter could only
/// blur `p`). Extra memory: the reduced planes only (the coefficients are sampled per pixel, not
/// enlarged into planes).
fn guided_cross_fast(guide: &Plane, p: &mut Plane, sigma: f32, eps: f32, weight: impl Fn(f32) -> f32 + Sync + Send) {
    use lightcraft_raster::resample::{Filter, resize};
    let (w, h) = (p.width, p.height);
    if guide.width != w || guide.height != h || w == 0 || h == 0 || !sigma.is_finite() {
        return;
    }
    // (a window past the whole frame fits one model to it all)
    let sigma = sigma.clamp(0.5, w.max(h) as f32);
    let s = crate::local::guided_fast_step(sigma);
    let (lw, lh) = (w.div_ceil(s).max(1), h.div_ceil(s).max(1));
    let g = guided_coeffs(&resize(guide, lw, lh, Filter::Box), &resize(p, lw, lh, Filter::Box), sigma / s as f32, eps, true);
    let Some(edge) = &g.edge else {
        return;
    };
    // (the reduced planes span the same frame: their pixel per full pixel)
    let (kx, ky) = (lw as f32 / w as f32, lh as f32 / h as f32);
    for_rows(&mut p.data, w, |y, row| {
        let fy = (y as f32 + 0.5) * ky;
        for (x, (v, i)) in row.iter_mut().zip(guide.row(y)).enumerate() {
            let fx = (x as f32 + 0.5) * kx;
            let k = weight(bilinear(&g.mean, fx, fy));
            // (no edge in the photo: nothing to follow, keep the sample)
            let k = if k > 0.0 { k * smooth(0.05, 1.0, bilinear(edge, fx, fy)) } else { 0.0 };
            if k > 0.0 {
                let q = bilinear(&g.a, fx, fy) * i + bilinear(&g.b, fx, fy);
                *v += (q - *v) * k;
            }
        }
    });
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
mod tests;
