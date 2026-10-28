//! The LightCraft develop pipeline (CPU reference implementation).
//!
//! Input: a scene-referred, linear Rec.2020 source image (already EXIF-oriented) at any resolution
//! (full size or a proxy), plus [`DevelopSettings`]. Output: a display-encoded sRGB image at the
//! requested size, and its histogram.
//!
//! Stage order (see `docs/pipeline.md`):
//! 1. geometry — user orientation, lens corrections (distortion, CA, vignetting), perspective, crop +
//!    straighten, flips; one resample at output resolution; then defringe
//! 2. scene-linear — white balance, exposure, dehaze, local tone (highlights/shadows), texture,
//!    clarity, local adjustments (masks)
//! 3. tone map — contrast / whites / blacks filmic curve on luminance, highlight desaturation
//! 4. colour — vibrance, saturation, colour mixer, colour grading, B&W (OkLCh)
//! 5. display — gamut map to the output space (sRGB unless [`RenderRequest::space`] says otherwise), encode, tone curves (parametric + point), vignette, grain
//!
//! Spatial parameters are specified relative to the image's long edge, so a 400 px preview and a
//! 60 MP export look alike.
//!
//! Exposure is a gain, so the spatial stages run on the un-exposed image and the per-pixel stage
//! applies it (filters on log luminance are shift-equivariant: identical result). With
//! [`render_cached`] each stage's output is reused while its inputs are unchanged ([`StageCache`]):
//! dragging a tone, colour or exposure slider re-runs only the per-pixel stage.
#![forbid(unsafe_code)]
#![deny(clippy::unwrap_used, clippy::expect_used, clippy::panic, clippy::unimplemented, clippy::todo, clippy::unreachable)]

pub mod auto;
pub mod blemish;
pub mod colorops;
pub mod cull;
pub mod curve;
pub mod dust;
pub mod finish;
pub mod geometry;
pub mod local;
pub mod local_tone;
pub mod lut;
pub mod masks;
pub mod optics;
pub mod output;
pub mod profiles;
pub mod redeye;
pub mod spots;
pub mod tone;
pub mod transform;
pub mod upright;
pub mod visualize;

pub use curve::{PARAM_AMOUNTS, PARAM_LUT, ParametricCurve};
pub use output::{DeepImage, DeepSamples, DisplaySpace, OutputDepth, OutputSpace, OutputTrc, Proof, ProofLut, proof_profile, register_proof_profile};
pub use visualize::{MaskView, Overlay};

use lightcraft_develop::{DevelopSettings, MaskShape, Treatment};
use lightcraft_geom::Point;
use std::any::Any;
use std::borrow::Cow;
use std::sync::{Arc, Mutex};

use lightcraft_raster::{Histogram, Plane, Rgb32f, Rgba8, par_rows};

pub use tone::ToneMap;

/// A raw source's own colour model, for white balance: like Lightroom, a white balance
/// re-evaluates the camera's colour matrices at the chosen white (camera-space white balance, DNG
/// spec ch. 6) instead of adapting the as-shot rendering ([`local::wb_matrix_for`]).
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize)]
pub struct CameraColor {
    /// The file's colour tags (its profile look left out: it is applied at load).
    pub tags: lightcraft_raw::ColorData,
    /// The white the source pixels were developed for.
    pub developed_for: lightcraft_color::Xy,
}

/// Facts about the source the settings are interpreted against.
#[derive(Clone, Debug, PartialEq)]
pub struct SourceInfo {
    /// Lens corrections embedded in the file (DNG opcodes), relative to the EXIF-oriented source.
    pub lens: Option<lightcraft_develop::EmbeddedLens>,
    /// Raw sources use absolute Kelvin white balance; rendered sources use a relative scale around
    /// their as-shot white.
    pub raw: bool,
    pub as_shot_temp: f64,
    pub as_shot_tint: f64,
    /// No measured camera illuminant: WB adjustments are relative to the camera's rendered look.
    pub relative_wb: bool,
    /// The Kelvin scale comes from matrices measured for the camera model, not from the file: before those were
    /// used for white balance, this source was edited on the relative scale (6500 K / 0 = as shot), so settings
    /// without a [`lightcraft_develop::WbScale`] are read that way ([`local::legacy_to_kelvin`]).
    pub legacy_relative_wb: bool,
    /// The camera's colour model (raw files with a colour matrix); `None`: white balance adapts
    /// the developed pixels (Bradford, linear Rec.2020).
    pub camera_color: Option<Arc<CameraColor>>,
    pub camera_tone: Option<tone::CameraTone>,
    /// Segmentation mattes stored in the file (DNG semantic masks): AI masks use them.
    pub mattes: Option<Arc<masks::Mattes>>,
    /// The raw's own local tone mapping (DNG `ProfileGainTableMap`), rendered only when the
    /// photo's Profile option asks for it ([`local_tone`]).
    pub local_tone: Option<Arc<local_tone::LocalTone>>,
    /// Long edge (px) of the original photo, when the source handed to the renderer is a smaller
    /// copy of it (the app's thumbnail and preview levels); `None`: the source is the original.
    /// Noise reduction sizes its radii in the original's pixels ([`Plan::detail_scale`]), so a
    /// preview looks like the full-size render scaled down (issue #960).
    pub native_long: Option<usize>,
}

impl Default for SourceInfo {
    fn default() -> Self {
        Self {
            raw: false,
            as_shot_temp: 6500.0,
            as_shot_tint: 0.0,
            lens: None,
            relative_wb: false,
            legacy_relative_wb: false,
            camera_color: None,
            camera_tone: None,
            mattes: None,
            local_tone: None,
            native_long: None,
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Default)]
pub enum Quality {
    /// Interactive drags: skips the most expensive refinements.
    Draft,
    #[default]
    Full,
}

#[derive(Clone, Copy, Debug)]
pub struct RenderRequest {
    /// Output must fit in `max_w × max_h` (aspect preserved).
    pub max_w: usize,
    pub max_h: usize,
    pub quality: Quality,
    /// Show the crop frame's content only (true) or the whole uncropped image (crop tool active).
    pub apply_crop: bool,
    /// A diagnostic view drawn over the result (e.g. Point Color's "visualize range").
    pub overlay: Overlay,
    /// Colour space of the result (exports; previews stay sRGB).
    pub space: OutputSpace,
    /// Sample format: 8-bit only, or also a 16-bit / linear float [`Rendered::deep`] (exports).
    pub depth: OutputDepth,
    /// Soft proofing (a proof against an output-device profile renders on the CPU; see [`Proof`]).
    pub proof: Option<Proof>,
    /// Render only this window of the output that `max_w × max_h` describes (a zoomed view):
    /// the result is that window's pixels, as in the whole render. See [`PixelWindow`].
    pub window: Option<PixelWindow>,
    /// Previews for a monitor with a display profile: render 8-bit into its primaries instead of
    /// `space` (see [`DisplaySpace`]; ignored by deep renders).
    pub display: Option<DisplaySpace>,
}

/// A window of the (virtual) full output, in its pixels: what a zoomed view needs, rendered
/// without materializing the whole frame. The output frame is the one `max_w × max_h` fits, after
/// crop, straighten and flips, as drawn. Out-of-range windows are clamped into it. Spatial stages
/// (clarity, dehaze, sharpening…) read their neighbours, so ask for a margin around what is kept.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct PixelWindow {
    pub x: usize,
    pub y: usize,
    pub w: usize,
    pub h: usize,
}

impl PixelWindow {
    /// This window inside a `full_w × full_h` frame: at least one pixel, wholly inside.
    pub fn clamped(self, full_w: usize, full_h: usize) -> PixelWindow {
        let (fw, fh) = (full_w.max(1), full_h.max(1));
        let x = self.x.min(fw - 1);
        let y = self.y.min(fh - 1);
        PixelWindow { x, y, w: self.w.clamp(1, fw - x), h: self.h.clamp(1, fh - y) }
    }
}

impl RenderRequest {
    pub fn fit(max_w: usize, max_h: usize) -> Self {
        Self {
            max_w,
            max_h,
            quality: Quality::Full,
            apply_crop: true,
            overlay: Overlay::None,
            space: OutputSpace::Srgb,
            depth: OutputDepth::U8,
            proof: None,
            window: None,
            display: None,
        }
    }
}

pub struct Rendered {
    /// The result, 8-bit (for deep renders: `deep` reduced to 8 bits, display-encoded).
    pub image: Rgba8,
    pub histogram: Histogram,
    /// The high-bit-depth result when [`RenderRequest::depth`] asks for one.
    pub deep: Option<DeepImage>,
}

/// Everything the per-pixel stage needs, precomputed at output resolution.
///
/// The image and planes are computed *before exposure* (so they can be reused while exposure is
/// dragged): the per-pixel stage multiplies the image by `gain` and shifts log planes by `ev`.
/// Spatial filters on log luminance are shift-equivariant, so this equals filtering after exposure.
pub(crate) struct Prepared {
    pub img: Arc<Rgb32f>,
    /// Log2 luminance relative to middle grey (before exposure: add `ev`).
    pub log_l: Arc<Plane>,
    pub base: Arc<Plane>,
    pub clarity_blur: Option<Arc<Plane>>,
    pub texture_blur: Option<Arc<Plane>>,
    pub dark: Option<Arc<Plane>>,
    /// Blurred chromaticity (`rgb / Y`) for local Moiré / Noise.
    pub chroma_blur: Option<Arc<Rgb32f>>,
    /// Airlight of `dark` (before exposure).
    pub air: f32,
    pub masks: Vec<masks::Evaluated>,
    /// Output pixels per unit of the source long edge.
    pub px_per_long: f64,
}

/// Output size for a source of `src_w × src_h` under `s`, fitting `max_w × max_h`.
pub fn output_size(src_w: usize, src_h: usize, s: &DevelopSettings, req: &RenderRequest) -> (usize, usize) {
    geometry::Frame::new(src_w, src_h, s, req.apply_crop).fit(req.max_w, req.max_h)
}

/// Size of the cropped output of a `src_w × src_h` source under `s` at the source's own resolution
/// (what a full-size export produces).
pub fn native_output_size(src_w: usize, src_h: usize, s: &DevelopSettings) -> (f64, f64) {
    geometry::Frame::new(src_w, src_h, s, true).native_size()
}

/// The geometric frame `render` uses for `src` (lens data, automatic CA estimate included).
pub fn frame_for(src: &Rgb32f, info: &SourceInfo, s: &DevelopSettings, apply_crop: bool) -> geometry::Frame {
    let mut frame = geometry::Frame::with_lens(src.width, src.height, s, apply_crop, info.lens.as_ref());
    if s.optics.remove_ca && s.section_enabled("optics") {
        frame.add_lateral_ca(optics::estimate_lateral_ca(src));
    }
    frame
}

/// Intermediate results of recent renders of one view, reused by [`render_cached`].
///
/// Each stage is keyed by exactly the inputs it depends on: the resampled source by the source
/// buffer, framing and output size; the white-balanced, retouched, denoised image by those plus
/// white balance, spots and noise reduction; each spatial plane by that plus its own radius. So a
/// tone or colour slider drag (or exposure) re-runs only the per-pixel stage, a clarity drag skips
/// resampling and noise reduction, and so on. Holds the last few output sizes (a draft-size and a
/// full-size render of the same view both stay warm).
pub struct StageCache {
    entries: Mutex<Vec<CacheEntry>>,
    capacity: usize,
    /// Another renderer's per-view state (the GPU renderer keeps its device-resident stages here).
    ext: Mutex<Option<Arc<dyn Any + Send + Sync>>>,
}

impl Default for StageCache {
    fn default() -> Self {
        StageCache { entries: Mutex::new(Vec::new()), capacity: 2, ext: Mutex::new(None) }
    }
}

impl StageCache {
    /// Drop everything (e.g. when memory is needed).
    pub fn clear(&self) {
        self.lock().clear();
        *self.ext.lock().unwrap_or_else(|e| e.into_inner()) = None;
    }

    /// Per-view state of type `T` kept alongside this cache (created on first use). Lets another
    /// renderer of the same view (e.g. `lightcraft-gpu`) keep its own stage cache with this one.
    pub fn extension<T: Any + Send + Sync + Default>(&self) -> Arc<T> {
        let mut g = self.ext.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(e) = g.as_ref()
            && let Ok(t) = e.clone().downcast::<T>()
        {
            return t;
        }
        let t = Arc::new(T::default());
        *g = Some(t.clone());
        t
    }

    /// The per-view state of type `T` if there is one (unlike [`Self::extension`], never creates it).
    pub fn peek_extension<T: Any + Send + Sync>(&self) -> Option<Arc<T>> {
        self.ext.lock().unwrap_or_else(|e| e.into_inner()).as_ref().and_then(|e| e.clone().downcast::<T>().ok())
    }

    /// Bytes of the intermediate images held (each buffer counted once; the sources they were
    /// made from belong to their owner and are not counted).
    pub fn bytes(&self) -> usize {
        fn size<T>(i: &lightcraft_raster::Image<T>) -> usize {
            i.data.len() * std::mem::size_of::<T>()
        }
        let mut seen: Vec<usize> = Vec::new();
        let mut total = 0;
        let mut add = |p: usize, n: usize| {
            if !seen.contains(&p) {
                seen.push(p);
                total += n;
            }
        };
        for e in self.lock().iter() {
            add(Arc::as_ptr(&e.sampled) as usize, size(&e.sampled));
            if let Some((_, l)) = &e.lin {
                add(Arc::as_ptr(l) as usize, size(l));
            }
            let pl = &e.planes;
            let planes = pl.log_l.iter().chain(pl.base.iter().map(|x| &x.1)).chain(pl.clarity.iter().map(|x| &x.1));
            for p in planes.chain(pl.texture.iter().map(|x| &x.1)).chain(pl.dark.iter().map(|x| &x.1)) {
                add(Arc::as_ptr(p) as usize, size(p));
            }
        }
        total
    }

    /// Number of cached output sizes.
    pub fn len(&self) -> usize {
        self.lock().len()
    }

    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, Vec<CacheEntry>> {
        self.entries.lock().unwrap_or_else(|e| e.into_inner())
    }

    fn get(&self, src: &Arc<Rgb32f>, geo: u64) -> Option<CacheEntry> {
        self.lock().iter().find(|e| e.geo == geo && Arc::ptr_eq(&e.src, src)).cloned()
    }

    fn put(&self, e: CacheEntry) {
        let mut v = self.lock();
        v.retain(|o| !(o.geo == e.geo && Arc::ptr_eq(&o.src, &e.src)));
        v.push(e);
        while v.len() > self.capacity {
            v.remove(0);
        }
    }
}

#[derive(Clone)]
struct CacheEntry {
    src: Arc<Rgb32f>,
    geo: u64,
    sampled: Arc<Rgb32f>,
    lin: Option<(u64, Arc<Rgb32f>)>,
    planes: local::Planes,
}

fn hash_of(parts: impl std::hash::Hash) -> u64 {
    use std::hash::{BuildHasher, BuildHasherDefault, DefaultHasher};
    BuildHasherDefault::<DefaultHasher>::default().hash_one(parts)
}

/// What a render resolves to before any pixel work: the effective settings (profile and Upright
/// applied), the geometric frame, the output size and the stage-cache keys. Shared by the CPU
/// renderer and the GPU renderer (`lightcraft-gpu`), so both key their caches identically.
pub struct Plan<'a> {
    pub settings: Cow<'a, DevelopSettings>,
    pub frame: geometry::Frame,
    pub w: usize,
    pub h: usize,
    /// Output pixels per unit of the oriented source's long edge.
    pub px_per_long: f64,
    /// Long edge of the source buffer (px).
    pub src_long: usize,
    /// Output pixels per pixel of the original photo ([`SourceInfo::native_long`], else the source
    /// buffer), at most 1: the scale of noise reduction, whose radii are in the original's pixels.
    pub detail_scale: f32,
    /// Key of the resampled source (together with the source buffer's identity).
    pub geo: u64,
    /// Key of the white-balanced, retouched, denoised image (and of the planes computed from it).
    pub lin_key: u64,
    /// [`Self::lin_key`] without the noise-reduction settings: draft renders reuse the smooth
    /// planes of an image that differs from this one only in its noise reduction.
    pub pre_nr_key: u64,
    /// Red eye / pet eye corrections with their detected pupils, in output pixels.
    pub eyes: Vec<redeye::EyeK>,
    /// The whole frame's dehaze airlight, for a windowed render (which can't see the whole frame).
    pub fixed_air: Option<f32>,
    /// A windowed render works on a larger window when spots reach into it (see
    /// [`spots::window_for_reads`]); this is the requested window inside the rendered one, which
    /// the result is cut to.
    pub keep: Option<PixelWindow>,
    /// The source's segmentation mattes ([`SourceInfo::mattes`]).
    pub mattes: Option<Arc<masks::Mattes>>,
}

/// Long edge of the small render a windowed render estimates the whole frame's airlight from.
const AIRLIGHT_PROXY_EDGE: usize = 384;

/// Resolve `s` against `src` for `req` (see [`Plan`]).
pub fn plan<'a>(src: &Rgb32f, info: &SourceInfo, s: &'a DevelopSettings, req: &RenderRequest) -> Plan<'a> {
    let mut settings: Cow<'a, DevelopSettings> = match profiles::effective(s) {
        Cow::Borrowed(b) => upright::resolve(src, info, b),
        Cow::Owned(o) => Cow::Owned(upright::resolve(src, info, &o).into_owned()),
    };
    visualize::adjust_settings(req.overlay, &mut settings);
    // ranked luminance ranges select by rank in the whole photo: give them its table
    let ranked = |sh: &MaskShape| matches!(sh, MaskShape::LuminanceRange { ranked: true, .. });
    if settings.masks.iter().any(|m| m.components.iter().any(|c| ranked(&c.shape))) {
        let table = luminance_ranks(src, info, &settings);
        for c in settings.to_mut().masks.iter_mut().flat_map(|m| m.components.iter_mut()) {
            if let MaskShape::LuminanceRange { ranked: true, ranks, .. } = &mut c.shape {
                *ranks = Some(table.clone());
            }
        }
    }
    // a window must not choose a spot's source from the pixels it happens to hold: fix them
    // from a fixed-size render of the whole frame first
    if req.window.is_some() && settings.spots.iter().any(|sp| sp.source_offset.is_none()) {
        let picked: Vec<Option<lightcraft_geom::Point>> =
            settings.spots.iter().map(|sp| sp.source_offset.or_else(|| spots::pick_source(src, info, &settings, sp, None))).collect();
        for (sp, o) in settings.to_mut().spots.iter_mut().zip(picked) {
            sp.source_offset = o;
        }
    }
    let s = &*settings;
    let frame = frame_for(src, info, s, req.apply_crop);
    let (full_w, full_h) = frame.fit(req.max_w, req.max_h);
    // sizes and scale are those of the whole output; a window only narrows what is drawn
    let px_per_long = frame.px_per_long(full_w);
    let mut fixed_air = None;
    let mut keep = None;
    let (frame, w, h) = match req.window {
        Some(win) => {
            let win = win.clamped(full_w, full_h);
            if s.section_enabled("effects") && (s.effects.dehaze != 0.0 || s.masks.iter().any(|m| m.adjust.dehaze != 0.0)) {
                let k = (AIRLIGHT_PROXY_EDGE as f64 / full_w.max(full_h) as f64).min(1.0);
                let (pw, ph) = (((full_w as f64 * k).round() as usize).max(1), ((full_h as f64 * k).round() as usize).max(1));
                fixed_air = Some(local::frame_airlight(src, info, s, &frame, pw, ph));
            }
            // (spots grow the rendered window; only 8-bit renders are cut back to the request)
            let work = if req.depth == OutputDepth::U8 { spots::window_for_reads(s, &frame, full_w, full_h, px_per_long, win) } else { win };
            if work != win {
                keep = Some(PixelWindow { x: win.x - work.x, y: win.y - work.y, ..win });
            }
            (frame.window(full_w, full_h, work), work.w, work.h)
        }
        None => (frame, full_w, full_h),
    };
    let src_long = src.width.max(src.height);
    // (an original reported smaller than the source is the source)
    let native_long = info.native_long.unwrap_or(0).max(src_long).max(1);
    let detail_scale = local::detail_scale(px_per_long, native_long);
    let geo = hash_of((format!("{frame:?}"), w, h));
    let (wb_t, wb_tint) = local::effective_wb(info, s);
    let eyes = redeye::resolve(src, &s.red_eye, s.orientation, &frame, w, h, px_per_long);
    let d = &s.detail;
    let pre_nr_key = hash_of((
        geo,
        [wb_t, wb_tint, info.as_shot_temp, info.as_shot_tint].map(f64::to_bits),
        format!("{:?}", s.spots),
        format!("{eyes:?}"),
        // defringe runs in this stage
        format!("{:?}", s.optics),
        // and the local tone mapping
        local_tone::enabled(info, s),
        src_long,
    ));
    let lin_key = hash_of((
        pre_nr_key,
        [d.nr_luminance, d.nr_detail, d.nr_color, d.nr_color_detail, d.nr_color_smoothness].map(f64::to_bits),
        detail_scale.to_bits(),
    ));
    Plan { settings, frame, w, h, px_per_long, src_long, detail_scale, geo, lin_key, pre_nr_key, eyes, fixed_air, keep, mattes: info.mattes.clone() }
}

/// The last few [`luminance_ranks`] tables, by source and white balance.
static RANK_TABLES: Mutex<Vec<(u64, Arc<Vec<f32>>)>> = Mutex::new(Vec::new());

/// The table ranked luminance ranges select with ([`masks::luminance_ranks`]): from a small copy
/// of the whole of `src`, white-balanced for `s` (the scene-linear stage's luminance, before
/// exposure). Kept for the last few sources.
pub fn luminance_ranks(src: &Rgb32f, info: &SourceInfo, s: &DevelopSettings) -> Arc<Vec<f32>> {
    let (t, tint) = local::effective_wb(info, s);
    // a source is known by its size and a scatter of its pixels
    let n = src.data.len();
    let probe: Vec<u32> = (0..64).filter_map(|i| src.data.get(i * n / 64)).flat_map(|c| c.map(f32::to_bits)).collect();
    let key = hash_of((src.width, src.height, probe, [t, tint, info.as_shot_temp, info.as_shot_tint].map(f64::to_bits), info.raw, info.relative_wb));
    if let Some(hit) = RANK_TABLES.lock().ok().and_then(|c| c.iter().find(|(k, _)| *k == key).map(|(_, t)| t.clone())) {
        return hit;
    }
    let mut small = masks::rank_copy(src);
    local::white_balance(&mut small, info, s);
    let table = Arc::new(masks::luminance_ranks(&small));
    if let Ok(mut c) = RANK_TABLES.lock() {
        c.retain(|(k, _)| *k != key);
        c.push((key, table.clone()));
        let extra = c.len().saturating_sub(4);
        c.drain(..extra);
    }
    table
}

/// Whether the scene-linear stage needs work only the CPU does (defringe, spot removal).
pub fn lin_needs_cpu(s: &DevelopSettings, info: &SourceInfo) -> bool {
    let o = &s.optics;
    let defringe = s.section_enabled("optics") && (o.defringe_purple_amount > 0.0 || o.defringe_green_amount > 0.0);
    defringe || !s.spots.is_empty() || local_tone::enabled(info, s)
}

/// The white-balanced, defringed, retouched image (before noise reduction): the CPU part of the
/// scene-linear stage, in place.
pub fn lin_cpu(img: &mut Rgb32f, info: &SourceInfo, p: &Plan<'_>) {
    let s = &*p.settings;
    // (before the white balance: the gain's input is the as-shot development, as in the file)
    local_tone::apply(img, info, p);
    local::white_balance(img, info, s);
    optics::defringe(img, s, p.px_per_long / optics::DEFRINGE_REF_LONG);
    spots::apply(img, &s.spots, &p.frame, p.px_per_long);
    redeye::apply(img, &p.eyes);
}

/// The settings a render of `s` for `req` uses: the HDR edit for an [`OutputDepth::F32Hdr`]
/// render, otherwise its SDR rendition (see [`DevelopSettings::sdr_rendition`]). Every renderer
/// (CPU and GPU) goes through this, so an SDR render of an HDR edit looks the same everywhere.
pub fn settings_for<'a>(s: &'a DevelopSettings, req: &RenderRequest) -> std::borrow::Cow<'a, DevelopSettings> {
    if req.depth == OutputDepth::F32Hdr { std::borrow::Cow::Borrowed(s) } else { s.sdr_rendition() }
}

/// Long edge (pixels) of the small HDR render [`hdr_histogram`] measures.
pub const HDR_HISTOGRAM_EDGE: usize = 384;

/// Long edge of the render that gives a slider drag its live HDR histogram (drafts have none: a
/// second render per tick would slow them down), run beside the drafts by the UI.
pub const HDR_HISTOGRAM_DRAG_EDGE: usize = 192;

/// The histogram of an HDR edit's HDR rendition for a preview request `req`: a small float HDR
/// render of the same framing ([`HDR_HISTOGRAM_EDGE`]), binned with SDR on the left half and the
/// whole HDR range ([`Hdr::MAX_EV_LIMIT`](lightcraft_develop::Hdr::MAX_EV_LIMIT) stops above SDR
/// white, whatever the headroom limit, so the scale stays put) on the right ([`Histogram::of_hdr`]). A preview of
/// an HDR edit shows its SDR rendition; this says how far its highlights go. `None` for SDR edits,
/// high-bit-depth, windowed and proofed requests (their histogram stays the image's).
pub fn hdr_histogram(src: &Rgb32f, info: &SourceInfo, s: &DevelopSettings, req: &RenderRequest) -> Option<Histogram> {
    // a drag's drafts keep their own histogram: a second render per tick would slow them down
    if !s.hdr.enabled || req.depth != OutputDepth::U8 || req.window.is_some() || req.proof.is_some() || req.quality == Quality::Draft {
        return None;
    }
    let k = (HDR_HISTOGRAM_EDGE as f64 / req.max_w.max(req.max_h).max(1) as f64).min(1.0);
    let size = |v: usize| ((v as f64 * k).round() as usize).max(1);
    let hreq = RenderRequest {
        max_w: size(req.max_w),
        max_h: size(req.max_h),
        depth: OutputDepth::F32Hdr,
        overlay: Overlay::None,
        proof: None,
        window: None,
        display: None,
        ..*req
    };
    let deep = render(src, info, s, &hreq).deep?;
    let DeepSamples::F32(v) = &deep.samples else { return None };
    Some(Histogram::of_hdr(v.as_chunks::<3>().0, lightcraft_develop::Hdr::MAX_EV_LIMIT as f32))
}

/// Render `src` with settings `s`.
pub fn render(src: &Rgb32f, info: &SourceInfo, s: &DevelopSettings, req: &RenderRequest) -> Rendered {
    render_impl(Src::Borrowed(src), info, s, req, None)
}

/// [`render`], reusing (and refreshing) the intermediate results in `cache`. The output is
/// identical to [`render`]'s.
pub fn render_cached(src: &Arc<Rgb32f>, info: &SourceInfo, s: &DevelopSettings, req: &RenderRequest, cache: &StageCache) -> Rendered {
    render_impl(Src::Shared(src), info, s, req, Some(cache))
}

enum Src<'a> {
    Borrowed(&'a Rgb32f),
    Shared(&'a Arc<Rgb32f>),
}

fn render_impl(src: Src<'_>, info: &SourceInfo, s: &DevelopSettings, req: &RenderRequest, cache: Option<&StageCache>) -> Rendered {
    // sections switched off with their eye render as if at their defaults (issue #316)
    let effective = s.effective();
    let edit: &DevelopSettings = &effective;
    let rendition = settings_for(edit, req);
    let s: &DevelopSettings = &rendition;
    // `Instant::now()` panics on wasm32-unknown-unknown: only read the clock when profiling.
    let lap = |what: &str, t: &mut Option<std::time::Instant>| {
        if let Some(t) = t {
            eprintln!("  {what}: {:.1} ms", t.elapsed().as_secs_f64() * 1e3);
            *t = std::time::Instant::now();
        }
    };
    let mut t = profiling().then(std::time::Instant::now);
    let src_img: &Rgb32f = match &src {
        Src::Borrowed(r) => r,
        Src::Shared(a) => a,
    };
    let plan = plan(src_img, info, s, req);
    let Plan { ref frame, w, h, px_per_long, detail_scale, geo, lin_key, .. } = plan;
    let s = &*plan.settings;

    let shared = match (&src, cache) {
        (Src::Shared(a), Some(c)) => Some((*a, c)),
        _ => None,
    };
    let cached = shared.and_then(|(a, c)| c.get(a, geo));
    let sampled = match &cached {
        Some(e) => e.sampled.clone(),
        None => Arc::new(frame.sample(src_img, w, h)),
    };
    lap("sample", &mut t);

    let lin = match cached.as_ref().and_then(|e| e.lin.clone()).filter(|(k, _)| *k == lin_key) {
        Some((_, img)) => img,
        None => {
            // Without a cache the resampled buffer is ours: work on it in place.
            let mut img = if shared.is_some() { (*sampled).clone() } else { Arc::unwrap_or_clone(sampled.clone()) };
            lin_cpu(&mut img, info, &plan);
            local::denoise(&mut img, s, detail_scale);
            Arc::new(img)
        }
    };
    lap("wb/spots/nr", &mut t);
    let mut planes = match cached.map(|e| e.planes) {
        Some(p) if p.key == lin_key => p,
        // A draft while noise reduction changes (a slider drag): the wide, edge-aware planes of the
        // image before the change stand in (noise reduction barely moves a blur that wide); the
        // per-pixel log luminance and the fine texture band are redone. Keyed so a full-quality
        // render never takes them.
        Some(p) if req.quality == Quality::Draft && p.pre_nr == plan.pre_nr_key => p.for_other_nr(),
        _ => local::Planes { key: lin_key, pre_nr: plan.pre_nr_key, ..Default::default() },
    };
    let mut prep = local::prepare(lin.clone(), s, frame, px_per_long, req.quality, &mut planes, plan.mattes.as_deref());
    if let Some(a) = plan.fixed_air {
        prep.air = a;
    }
    lap("prepare", &mut t);
    if let Some((a, c)) = shared {
        c.put(CacheEntry { src: a.clone(), geo, sampled, lin: Some((lin_key, lin)), planes });
    }
    if req.depth != OutputDepth::U8 {
        let deep = finish::finish_deep(&prep, s, frame, info, req.space, req.depth, req.proof);
        let image = deep.to_rgba8();
        let histogram = Histogram::of_srgb8(&image);
        lap("finish (deep)", &mut t);
        return Rendered { image, histogram, deep: Some(deep) };
    }
    let image = finish::finish(&prep, s, frame, info, req.space, req.display.as_ref(), req.proof);
    lap("finish", &mut t);
    let cut = |i: &Rgba8| match plan.keep {
        Some(k) => i.crop(k.x, k.y, k.w, k.h),
        None => i.clone(),
    };
    let histogram = match plan.keep {
        Some(_) => Histogram::of_srgb8(&cut(&image)),
        None => Histogram::of_srgb8(&image),
    };
    lap("histogram", &mut t);
    let mut image = image;
    let mask = overlay_alpha(req.overlay, &plan, &prep);
    let masks = overlay_alphas_all(req.overlay, &plan, &prep);
    visualize::apply(&mut image, req.overlay, &plan, mask.as_ref(), masks.as_deref());
    let mut image = if plan.keep.is_some() { cut(&image) } else { image };
    if req.overlay == Overlay::HdrRange && edit.hdr.enabled {
        // the HDR rendition of the same request says how far above SDR white each pixel goes
        let hreq = RenderRequest { depth: OutputDepth::F32Hdr, overlay: Overlay::None, proof: None, ..*req };
        if let Some(hdr) = render(src_img, info, edit, &hreq).deep {
            visualize::hdr_range(&mut image, &hdr, req.space.luma());
        }
        lap("visualize hdr", &mut t);
    }
    Rendered { image, histogram, deep: None }
}

/// The exposed scene colours a range mask's picker takes at normalized image point `a` (3 × 3
/// pixels round it) or over the area with corners `a` and `b`, from a render fitting `req`. `None`
/// when the point (or the whole area) is outside the image.
fn picked_colours(src: &Rgb32f, info: &SourceInfo, s: &DevelopSettings, req: &RenderRequest, a: Point, b: Option<Point>) -> Option<Vec<[f32; 3]>> {
    // Pick from the same settings as the render: disabled panels contribute nothing, and an
    // SDR preview of an HDR edit includes the SDR rendition's exposure offset.
    let effective = s.effective();
    let rendition = settings_for(&effective, req);
    let plan = plan(src, info, &rendition, req);
    let mut img = plan.frame.sample(src, plan.w, plan.h);
    lin_cpu(&mut img, info, &plan);
    let to_out = plan.frame.norm_to_out(plan.w, plan.h);
    let (pa, pb) = (to_out.apply(a), to_out.apply(b.unwrap_or(a)));
    let (w, h) = (plan.w as i64, plan.h as i64);
    let (x0, y0) = (pa.x.min(pb.x).floor() as i64, pa.y.min(pb.y).floor() as i64);
    let (x1, y1) = (pa.x.max(pb.x).floor() as i64, pa.y.max(pb.y).floor() as i64);
    if b.is_none() && (x0 < 0 || y0 < 0 || x0 >= w || y0 >= h) {
        return None;
    }
    // a point: the 3 × 3 pixels round it
    let grow = i64::from(b.is_none());
    let (x0, y0, x1, y1) = ((x0 - grow).max(0), (y0 - grow).max(0), (x1 + grow).min(w - 1), (y1 + grow).min(h - 1));
    if x0 > x1 || y0 > y1 {
        return None;
    }
    let gain = (plan.settings.light.exposure as f32).exp2();
    Some((y0..=y1).flat_map(|y| (x0..=x1).map(move |x| (x, y))).map(|(x, y)| img.data[y as usize * plan.w + x as usize].map(|v| v * gain)).collect())
}

/// The value `q` (0..1) of the way through sorted `v`.
fn quantile(v: &mut [f64], q: f64) -> f64 {
    v.sort_by(f64::total_cmp);
    let i = ((v.len().saturating_sub(1)) as f64 * q).round() as usize;
    v.get(i).copied().unwrap_or_default()
}

/// OkLab of an exposed scene colour as a colour range compares it: toned down
/// ([`masks::tonemap_for_select`]) unless it selects `hue_first`.
fn range_lab(c: [f32; 3], hue_first: bool) -> [f32; 3] {
    lightcraft_color::perceptual::oklab_from_2020(if hue_first { c } else { masks::tonemap_for_select(c) })
}

/// The colour a colour-range mask would sample at normalized image point `p` (OkLab of the
/// exposed scene colours, as [`MaskShape::ColorRange`](lightcraft_develop::MaskShape) compares
/// them, `hue_first` or not), averaged over 3 × 3 pixels of a render fitting `req`. `None`
/// outside the image.
pub fn color_range_sample(src: &Rgb32f, info: &SourceInfo, s: &DevelopSettings, req: &RenderRequest, p: Point, hue_first: bool) -> Option<[f64; 3]> {
    let px = picked_colours(src, info, s, req, p, None)?;
    let mut acc = [0f64; 3];
    for c in &px {
        let lab = range_lab(*c, hue_first);
        for k in 0..3 {
            acc[k] += lab[k] as f64;
        }
    }
    Some(acc.map(|v| v / px.len() as f64))
}

/// The colours a colour-range mask takes from the area with normalized corners `a` and `b`: the
/// OkLab box they span (each of L, a, b from its 5th to its 95th percentile, so a stray pixel
/// doesn't widen it). `None` when the area is outside the image.
pub fn color_range_area(
    src: &Rgb32f,
    info: &SourceInfo,
    s: &DevelopSettings,
    req: &RenderRequest,
    a: Point,
    b: Point,
    hue_first: bool,
) -> Option<[[f64; 3]; 2]> {
    let px = picked_colours(src, info, s, req, a, Some(b))?;
    let labs: Vec<[f32; 3]> = px.iter().map(|c| range_lab(*c, hue_first)).collect();
    let mut lo = [0f64; 3];
    let mut hi = [0f64; 3];
    for k in 0..3 {
        let mut v: Vec<f64> = labs.iter().map(|l| f64::from(l[k])).collect();
        lo[k] = quantile(&mut v, 0.05);
        hi[k] = quantile(&mut v, 0.95);
    }
    Some([lo, hi])
}

/// The lightness (0..1, as [`masks::lightness_from_range`] shows a luminance range) a luminance
/// range mask's picker takes at normalized point `a` (3 × 3 pixels averaged), or over the area
/// with corners `a` and `b` (its darkest and lightest: the 5th and 95th percentiles). `ranked`:
/// ranks in the photo instead ([`luminance_ranks`]).
pub fn luminance_sample(
    src: &Rgb32f,
    info: &SourceInfo,
    s: &DevelopSettings,
    req: &RenderRequest,
    a: Point,
    b: Option<Point>,
    ranked: bool,
) -> Option<(f64, Option<f64>)> {
    let px = picked_colours(src, info, s, req, a, b)?;
    let table = ranked.then(|| luminance_ranks(src, info, s));
    // (the picked colours are exposed; ranks are taken before exposure)
    let ev = s.light.exposure as f32;
    let mut l: Vec<f64> = px
        .iter()
        .map(|c| match &table {
            Some(t) => f64::from(masks::rank_of(t, local::log_lum(*c) - ev)),
            None => masks::lightness_from_range(f64::from((local::log_lum(*c) + 8.0) / 12.0)),
        })
        .collect();
    if b.is_none() {
        return Some((l.iter().sum::<f64>() / l.len() as f64, None));
    }
    Some((quantile(&mut l, 0.05), Some(quantile(&mut l, 0.95))))
}

/// The alpha plane a mask overlay shows: the one the render evaluated, or (for a hidden mask) a
/// fresh evaluation.
fn overlay_alpha(o: Overlay, plan: &Plan<'_>, prep: &Prepared) -> Option<Plane> {
    let m = o.mask(&plan.settings)?;
    if let Some(e) = prep.masks.iter().find(|e| e.id == m.id) {
        return Some(e.alpha.clone());
    }
    let ev = plan.settings.light.exposure as f32;
    Some(masks::evaluate_one(m, &plan.frame, plan.w, plan.h, &prep.img, &prep.log_l, ev, plan.mattes.as_deref()))
}

/// The alpha planes an `Overlay::AllMasks` shows: one per visible mask that has components,
/// reusing the render's own evaluation where available (a fresh evaluation otherwise).
fn overlay_alphas_all(o: Overlay, plan: &Plan<'_>, prep: &Prepared) -> Option<Vec<Plane>> {
    if !matches!(o, Overlay::AllMasks { .. }) {
        return None;
    }
    let ev = plan.settings.light.exposure as f32;
    let mut out = Vec::new();
    for m in plan.settings.masks.iter().filter(|m| m.visible && !m.components.is_empty()) {
        if let Some(e) = prep.masks.iter().find(|e| e.id == m.id) {
            out.push(e.alpha.clone());
        } else {
            out.push(masks::evaluate_one(m, &plan.frame, plan.w, plan.h, &prep.img, &prep.log_l, ev, plan.mattes.as_deref()));
        }
    }
    Some(out)
}

/// Convenience: render a before/after pair side by side is up to the UI; this renders "before"
/// (default look, keeping the crop so framing matches, under the photo's own rendering process).
pub fn before_settings(s: &DevelopSettings) -> DevelopSettings {
    let mut b = DevelopSettings { crop: s.crop, orientation: s.orientation, process: s.process, ..DevelopSettings::default() };
    b.wb = lightcraft_develop::WhiteBalance { mode: lightcraft_develop::WbMode::AsShot, ..b.wb };
    b
}

pub(crate) fn is_bw(s: &DevelopSettings) -> bool {
    s.treatment == Treatment::Bw || s.profile.id == "lc.mono" || s.profile.id.starts_with("lc.bw.")
}

/// `LIGHTCRAFT_PROFILE` is set: print per-stage timings to stderr.
pub fn profiling() -> bool {
    static ON: std::sync::OnceLock<bool> = std::sync::OnceLock::new();
    *ON.get_or_init(|| std::env::var_os("LIGHTCRAFT_PROFILE").is_some())
}

/// Run `f`, printing its duration under `LIGHTCRAFT_PROFILE`.
pub(crate) fn timed<R>(what: &str, f: impl FnOnce() -> R) -> R {
    if !profiling() {
        return f();
    }
    let t = std::time::Instant::now();
    let r = f();
    eprintln!("    {what}: {:.1} ms", t.elapsed().as_secs_f64() * 1e3);
    r
}

pub(crate) fn for_rows<T: Send>(data: &mut [T], w: usize, f: impl Fn(usize, &mut [T]) + Sync + Send) {
    par_rows(data, w, f)
}

#[cfg(test)]
mod tests;
#[cfg(test)]
mod tests_geometry;
#[cfg(test)]
mod tests_hdr;
#[cfg(test)]
mod tests_local;
#[cfg(test)]
mod tests_nr_scale;
#[cfg(test)]
mod tests_tone;
#[cfg(test)]
mod tests_window;
