//! Small helpers: hashes, UTC times, atomic file writes, bounded parallelism.

use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use sha2::{Digest, Sha256};

use crate::{Error, Result};

pub fn sha256_hex(bytes: &[u8]) -> String {
    Sha256::digest(bytes).iter().map(|b| format!("{b:02x}")).collect()
}

/// The manifest's `sidecarVersion` of a sidecar: the first 16 hex of its SHA-256.
pub fn sidecar_version(bytes: &[u8]) -> String {
    sha256_hex(bytes)[..16].to_string()
}

fn secs(t: SystemTime) -> i64 {
    match t.duration_since(UNIX_EPOCH) {
        Ok(d) => d.as_secs() as i64,
        Err(e) => -(e.duration().as_secs() as i64),
    }
}

/// (year, month, day, hour, minute, second) in UTC.
fn civil(t: SystemTime) -> (i64, u32, u32, u32, u32, u32) {
    let s = secs(t);
    let (days, rem) = (s.div_euclid(86_400), s.rem_euclid(86_400));
    // days since 1970-01-01 -> civil date (Howard Hinnant's algorithm)
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let m = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
    let y = yoe + era * 400 + i64::from(m <= 2);
    (y, m, d, (rem / 3600) as u32, (rem % 3600 / 60) as u32, (rem % 60) as u32)
}

/// `YYYYmmddTHHMMSSZ`, the inbox's stamp.
pub fn utc_stamp(t: SystemTime) -> String {
    let (y, mo, d, h, mi, s) = civil(t);
    format!("{y:04}{mo:02}{d:02}T{h:02}{mi:02}{s:02}Z")
}

/// `YYYY-mm-ddTHH:MM:SSZ`.
pub fn iso(t: SystemTime) -> String {
    let (y, mo, d, h, mi, s) = civil(t);
    format!("{y:04}-{mo:02}-{d:02}T{h:02}:{mi:02}:{s:02}Z")
}

pub fn parse_stamp(s: &str) -> Option<SystemTime> {
    let b = s.as_bytes();
    if b.len() != 16 || b[8] != b'T' || b[15] != b'Z' {
        return None;
    }
    let n = |r: std::ops::Range<usize>| s.get(r)?.parse::<i64>().ok();
    let (y, mo, d, h, mi, sec) = (n(0..4)?, n(4..6)?, n(6..8)?, n(9..11)?, n(11..13)?, n(13..15)?);
    // civil -> days
    let y2 = if mo <= 2 { y - 1 } else { y };
    let era = y2.div_euclid(400);
    let yoe = y2.rem_euclid(400);
    let mp = if mo > 2 { mo - 3 } else { mo + 9 };
    let doy = (153 * mp + 2) / 5 + d - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    let days = era * 146_097 + doe - 719_468;
    let t = days * 86_400 + h * 3600 + mi * 60 + sec;
    Some(if t >= 0 { UNIX_EPOCH + Duration::from_secs(t as u64) } else { UNIX_EPOCH })
}

pub fn millis(t: SystemTime) -> u64 {
    t.duration_since(UNIX_EPOCH).map_or(0, |d| d.as_millis() as u64)
}

static COUNTER: AtomicUsize = AtomicUsize::new(0);

/// A name for a temporary file next to `dest` (same folder, so the rename is atomic).
pub fn temp_beside(dest: &Path) -> PathBuf {
    let n = COUNTER.fetch_add(1, Ordering::Relaxed);
    let name = dest.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
    dest.with_file_name(format!(".{name}.part-{}-{n}", std::process::id()))
}

/// Write `bytes` to `dest` atomically (temporary file, fsync, rename).
pub fn write_atomic(dest: &Path, bytes: &[u8]) -> Result<()> {
    use std::io::Write;
    if let Some(dir) = dest.parent() {
        std::fs::create_dir_all(dir).map_err(|e| Error::io(dir.display(), e))?;
    }
    let tmp = temp_beside(dest);
    let r = (|| {
        let mut f = std::fs::File::create(&tmp)?;
        f.write_all(bytes)?;
        f.sync_all()?;
        std::fs::rename(&tmp, dest)
    })();
    r.map_err(|e| {
        let _ = std::fs::remove_file(&tmp);
        Error::io(dest.display(), e)
    })
}

/// Run `f` over `items` on up to `jobs` threads; results in item order.
pub fn parallel<T: Sync, R: Send>(items: &[T], jobs: usize, f: impl Fn(&T) -> R + Sync) -> Vec<R> {
    let next = AtomicUsize::new(0);
    let results: std::sync::Mutex<Vec<(usize, R)>> = std::sync::Mutex::new(Vec::with_capacity(items.len()));
    std::thread::scope(|s| {
        for _ in 0..jobs.max(1).min(items.len()) {
            s.spawn(|| {
                loop {
                    let i = next.fetch_add(1, Ordering::Relaxed);
                    let Some(item) = items.get(i) else { break };
                    let r = f(item);
                    results.lock().unwrap_or_else(|e| e.into_inner()).push((i, r));
                }
            });
        }
    });
    let mut v = results.into_inner().unwrap_or_else(|e| e.into_inner());
    v.sort_by_key(|(i, _)| *i);
    v.into_iter().map(|(_, r)| r).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn stamps_round_trip() {
        let t = UNIX_EPOCH + Duration::from_secs(1_793_000_000);
        let s = utc_stamp(t);
        assert_eq!(parse_stamp(&s), Some(t));
        assert_eq!(utc_stamp(UNIX_EPOCH), "19700101T000000Z");
        assert_eq!(iso(UNIX_EPOCH + Duration::from_secs(951_782_400)), "2000-02-29T00:00:00Z");
    }

    #[test]
    fn parallel_keeps_order() {
        let v: Vec<u32> = (0..50).collect();
        assert_eq!(parallel(&v, 6, |x| x * 2), v.iter().map(|x| x * 2).collect::<Vec<_>>());
        assert!(parallel::<u32, u32>(&[], 6, |x| *x).is_empty());
    }
}
