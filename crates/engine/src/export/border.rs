//! Export borders: a plain frame around the exported photo ([`Border`]), drawn after the watermark.

use super::encoding::srgb8_in;
use super::{DeepImage, DeepSamples, Deserialize, OutputSpace, Rgba8, Serialize};

/// The shape a [`Border`] frames the photo to: its own (`original`), the same margin on every side
/// (`even`), or a fixed ratio, width to height.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub enum BorderRatio {
    #[default]
    #[serde(rename = "original")]
    Original,
    #[serde(rename = "even")]
    Even,
    #[serde(rename = "1:1")]
    R1x1,
    #[serde(rename = "2:3")]
    R2x3,
    #[serde(rename = "3:2")]
    R3x2,
    #[serde(rename = "4:5")]
    R4x5,
    #[serde(rename = "5:4")]
    R5x4,
    #[serde(rename = "9:16")]
    R9x16,
    #[serde(rename = "2:1")]
    R2x1,
}

impl BorderRatio {
    /// Width to height, or `None` (the photo's own, or an even margin).
    pub(super) fn ratio(self) -> Option<(f64, f64)> {
        match self {
            BorderRatio::Original | BorderRatio::Even => None,
            BorderRatio::R1x1 => Some((1.0, 1.0)),
            BorderRatio::R2x3 => Some((2.0, 3.0)),
            BorderRatio::R3x2 => Some((3.0, 2.0)),
            BorderRatio::R4x5 => Some((4.0, 5.0)),
            BorderRatio::R5x4 => Some((5.0, 4.0)),
            BorderRatio::R9x16 => Some((9.0, 16.0)),
            BorderRatio::R2x1 => Some((2.0, 1.0)),
        }
    }
}

/// A plain border around an exported photo. The photo keeps its size (sizes and dimensions are the
/// photo's own) and the file grows: with an even border by half the thickness (a percentage of the
/// long edge) on every side; otherwise the photo is first padded out to the ratio (its own for
/// `original`) and that frame then grows by the thickness.
#[derive(Clone, Copy, Debug, PartialEq, Serialize, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct Border {
    /// 0..[`BORDER_THICKNESS_MAX`], a percentage.
    pub thickness: f32,
    pub ratio: BorderRatio,
    /// sRGB colour.
    pub color: [u8; 3],
}

impl Default for Border {
    fn default() -> Self {
        Self { thickness: 10.0, ratio: BorderRatio::Original, color: [255; 3] }
    }
}

/// The largest thickness a border takes, a percentage.
pub const BORDER_THICKNESS_MAX: f32 = 100.0;

/// The keys of a `border` object ([`Border`], camelCase).
pub const BORDER_PARAMS: &[&str] = &["thickness", "ratio", "color"];

impl Border {
    /// The margins around a `w × h` photo in pixels: left, right, top, bottom.
    pub fn margins(&self, w: usize, h: usize) -> [usize; 4] {
        let t = if self.thickness.is_finite() { self.thickness.clamp(0.0, BORDER_THICKNESS_MAX) as f64 / 100.0 } else { 0.0 };
        let (wf, hf) = (w as f64, h as f64);
        if self.ratio == BorderRatio::Even {
            let m = (wf.max(hf) * t / 2.0).round().max(0.0) as usize;
            return [m; 4];
        }
        let (a, b) = self.ratio.ratio().unwrap_or((wf.max(1.0), hf.max(1.0)));
        let scale = (wf / a).max(hf / b);
        let side = |frame: f64, own: f64| ((frame * (1.0 + t) - own) / 2.0).round().max(0.0) as usize;
        let (x, y) = (side(a * scale, wf), side(b * scale, hf));
        [x, x, y, y]
    }

    /// The bordered size of a `w × h` photo.
    pub fn framed(&self, w: usize, h: usize) -> (usize, usize) {
        let [l, r, t, b] = self.margins(w, h);
        (w + l + r, h + t + b)
    }
}

/// `src` (`channels` values a pixel) inside margins `[left, right, top, bottom]` of `fill`.
fn bordered<T: Copy>(src: &[T], w: usize, h: usize, channels: usize, m: [usize; 4], fill: &[T]) -> Vec<T> {
    let [l, r, t, b] = m;
    let (bw, bh) = (w + l + r, h + t + b);
    let mut out: Vec<T> = Vec::with_capacity(bw * bh * channels);
    for _ in 0..bw * bh {
        out.extend_from_slice(fill);
    }
    for y in 0..h {
        let from = src.get(y * w * channels..(y + 1) * w * channels);
        let at = ((y + t) * bw + l) * channels;
        if let (Some(from), Some(to)) = (from, out.get_mut(at..at + w * channels)) {
            to.copy_from_slice(from);
        }
    }
    out
}

/// [`Border`] around an 8-bit image in `space`.
pub fn add_border(img: &Rgba8, b: &Border, space: OutputSpace) -> Rgba8 {
    let m = b.margins(img.width, img.height);
    if m == [0; 4] {
        return img.clone();
    }
    let c = srgb8_in(space, b.color);
    let (w, h) = b.framed(img.width, img.height);
    Rgba8 { width: w, height: h, data: bordered(&img.data, img.width, img.height, 1, m, &[[c[0], c[1], c[2], 255]]) }
}

/// [`Border`] around a high-bit-depth image (in its own space: 16-bit encoded, 32-bit linear).
pub fn add_border_deep(img: &DeepImage, b: &Border) -> DeepImage {
    let m = b.margins(img.width, img.height);
    if m == [0; 4] {
        return img.clone();
    }
    let c = srgb8_in(img.space, b.color);
    let samples = match &img.samples {
        DeepSamples::U16(v) => DeepSamples::U16(bordered(v, img.width, img.height, 3, m, &c.map(|x| x as u16 * 257))),
        DeepSamples::F32(v) => {
            let t = img.space.trc();
            DeepSamples::F32(bordered(v, img.width, img.height, 3, m, &c.map(|x| t.decode(x as f32 / 255.0))))
        }
    };
    let (w, h) = b.framed(img.width, img.height);
    DeepImage { width: w, height: h, space: img.space, samples }
}
