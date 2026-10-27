//! [`RcloneRemote`]: any rclone remote, through rclone's own C library (`librclone.so`, loaded at run
//! time; `apps/lightcraft-android/scripts/build-librclone.sh` builds it).
//!
//! The library's C API (rclone v1.75.2, `librclone/librclone.go`): `RcloneInitialize()`,
//! `RcloneFinalize()`, `RcloneRPC(char* method, char* input) -> {char* Output; int Status}` (Status is
//! an HTTP status, 200 = ok; Output a JSON object, `{"error": …}` on failure) and `RcloneFreeString`.
//! Everything here is rclone's remote control API: `operations/list`, `operations/copyfile`.
//!
//! The library is process-wide (one Go runtime, one config file): the first [`RcloneRemote::open`]
//! loads and initialises it, and every later one in the process shares it, pointing its config path
//! at its own file. Apps use one cloud library at a time.

#![allow(unsafe_code)]

use std::ffi::{CStr, CString, c_char, c_int};
use std::path::{Path, PathBuf};
use std::sync::{Mutex, OnceLock};

use serde_json::{Value, json};

use crate::{Error, Remote, RemoteEntry, Result};

#[repr(C)]
struct RpcResult {
    output: *mut c_char,
    status: c_int,
}

type RpcFn = unsafe extern "C" fn(*const c_char, *const c_char) -> RpcResult;
type FreeFn = unsafe extern "C" fn(*mut c_char);

struct Lib {
    rpc: RpcFn,
    free: FreeFn,
    /// Kept loaded for good: a Go runtime cannot be unloaded.
    _lib: libloading::Library,
}

// (plain function pointers into a library that is never unloaded; rclone's RPC is thread-safe)
unsafe impl Send for Lib {}
unsafe impl Sync for Lib {}

static LIB: OnceLock<Lib> = OnceLock::new();
static LOAD: Mutex<()> = Mutex::new(());

/// Load `librclone.so` (from `path`, else by name: Android finds it among the app's libraries) once.
/// `work_dir` becomes the Go runtime's `TMPDIR` (and `HOME` when it has none, as on Android): set before the
/// library is loaded, because the Go runtime reads the environment once, when it starts.
fn lib(path: Option<&Path>, work_dir: Option<&Path>) -> Result<&'static Lib> {
    if let Some(l) = LIB.get() {
        return Ok(l);
    }
    let _guard = LOAD.lock().unwrap_or_else(|e| e.into_inner());
    if let Some(l) = LIB.get() {
        return Ok(l);
    }
    if let Some(dir) = work_dir {
        let _ = std::fs::create_dir_all(dir);
        // (nothing else in this process reads the environment concurrently while the library is first loaded)
        unsafe {
            std::env::set_var("TMPDIR", dir);
            if std::env::var_os("HOME").is_none_or(|h| h.is_empty()) {
                std::env::set_var("HOME", dir);
            }
        }
    }
    let name: PathBuf = path.map(Path::to_path_buf).unwrap_or_else(|| PathBuf::from("librclone.so"));
    let loaded = unsafe {
        let lib = libloading::Library::new(&name).map_err(|e| Error::Remote(format!("librclone.so not found ({}): {e}", name.display())))?;
        let missing = |what: &str, e: libloading::Error| Error::Remote(format!("{} has no {what}: {e}", name.display()));
        let init: libloading::Symbol<unsafe extern "C" fn()> = lib.get(b"RcloneInitialize\0").map_err(|e| missing("RcloneInitialize", e))?;
        let rpc: libloading::Symbol<RpcFn> = lib.get(b"RcloneRPC\0").map_err(|e| missing("RcloneRPC", e))?;
        let free: libloading::Symbol<FreeFn> = lib.get(b"RcloneFreeString\0").map_err(|e| missing("RcloneFreeString", e))?;
        let (rpc, free) = (*rpc, *free);
        init();
        Lib { rpc, free, _lib: lib }
    };
    Ok(LIB.get_or_init(|| loaded))
}

impl Lib {
    /// One RPC call: the parsed output, or the error rclone reported.
    fn call(&self, method: &str, input: &Value) -> Result<Value> {
        let c = |s: &str| CString::new(s).map_err(|_| Error::Invalid(format!("NUL in {method} request")));
        let (m, i) = (c(method)?, c(&input.to_string())?);
        let (text, status) = unsafe {
            let r = (self.rpc)(m.as_ptr(), i.as_ptr());
            let text = if r.output.is_null() { String::new() } else { CStr::from_ptr(r.output).to_string_lossy().into_owned() };
            if !r.output.is_null() {
                (self.free)(r.output);
            }
            (text, r.status)
        };
        let out: Value = serde_json::from_str(&text).unwrap_or(Value::Null);
        if status == 200 {
            return Ok(out);
        }
        let msg = out.get("error").and_then(Value::as_str).map(str::to_string).unwrap_or_else(|| format!("{method}: status {status}: {text}"));
        let lower = msg.to_lowercase();
        if status == 404 || lower.contains("not found") || lower.contains("doesn't exist") || lower.contains("does not exist") {
            Err(Error::NotFound(msg))
        } else {
            Err(Error::Remote(msg))
        }
    }
}

/// An rclone remote, or a folder of one: `fs` is what rclone calls the "fs" string (`phone:LightCraft Cloud`,
/// `phone:` for a remote whose root is the library, an absolute local folder).
pub struct RcloneRemote {
    lib: &'static Lib,
    fs: String,
}

impl RcloneRemote {
    /// Load librclone (from `librclone`, else the system's search path) and use the rclone config at
    /// `config` for the remote named in `fs`. `work_dir`: a folder rclone may use for temporary files (only
    /// honoured by the first call in the process; see [`lib`]).
    pub fn open(librclone: Option<&Path>, config: &Path, fs: &str, work_dir: Option<&Path>) -> Result<Self> {
        let lib = lib(librclone, work_dir)?;
        let config = std::path::absolute(config).map_err(|e| Error::io(config.display(), e))?;
        if !config.is_file() {
            return Err(Error::Invalid(format!("rclone config {} not found", config.display())));
        }
        lib.call("config/setpath", &json!({"path": config.to_string_lossy()}))?;
        Ok(RcloneRemote { lib, fs: fs.to_string() })
    }

    /// Whether `librclone` (or the system's) can be loaded, without needing a config.
    pub fn available(librclone: Option<&Path>) -> bool {
        lib(librclone, None).is_ok()
    }

    fn local(path: &Path) -> Result<(String, String)> {
        let abs = std::path::absolute(path).map_err(|e| Error::io(path.display(), e))?;
        let name = abs.file_name().map(|n| n.to_string_lossy().into_owned()).ok_or_else(|| Error::Invalid(format!("{} names no file", abs.display())))?;
        let dir = abs.parent().map(|d| d.to_string_lossy().into_owned()).unwrap_or_else(|| "/".into());
        Ok((dir, name))
    }
}

impl Remote for RcloneRemote {
    fn list(&self, dir: &str, recursive: bool) -> Result<Vec<RemoteEntry>> {
        let out = self.lib.call("operations/list", &json!({"fs": self.fs, "remote": dir, "opt": {"recurse": recursive, "noMimeType": true, "noModTime": true}}))?;
        let list = out.get("list").and_then(Value::as_array).cloned().unwrap_or_default();
        // (rclone's `Path` is relative to the fs, so it starts with the listed folder)
        let prefix = format!("{}/", dir.trim_matches('/'));
        Ok(list
            .iter()
            .filter_map(|e| {
                let full = e.get("Path")?.as_str()?;
                let path = if dir.is_empty() { full } else { full.strip_prefix(&prefix).unwrap_or(full) };
                let is_dir = e.get("IsDir").and_then(Value::as_bool).unwrap_or(false);
                let size = if is_dir { 0 } else { e.get("Size").and_then(Value::as_i64).map_or(0, |s| s.max(0) as u64) };
                Some(RemoteEntry { path: path.to_string(), size, is_dir })
            })
            .collect())
    }

    fn get(&self, path: &str, local: &Path) -> Result<()> {
        let (dir, name) = Self::local(local)?;
        self.lib.call("operations/copyfile", &json!({"srcFs": self.fs, "srcRemote": path, "dstFs": dir, "dstRemote": name})).map(|_| ())
    }

    fn put(&self, local: &Path, path: &str) -> Result<()> {
        let (dir, name) = Self::local(local)?;
        self.lib.call("operations/copyfile", &json!({"srcFs": dir, "srcRemote": name, "dstFs": self.fs, "dstRemote": path})).map(|_| ())
    }
}
