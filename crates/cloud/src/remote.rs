//! Where the library lives: any rclone remote ([`crate::RcloneRemote`]) or a plain folder ([`DirRemote`]).

use std::path::{Path, PathBuf};

use crate::{Error, Result};

/// One file or folder of a listing.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RemoteEntry {
    /// Relative to the listed folder, `/`-separated.
    pub path: String,
    pub size: u64,
    pub is_dir: bool,
}

/// Storage the library's files are read from and (the inbox only) written to. Paths are relative to
/// the remote's root, `/`-separated, without a leading slash. Methods may be called from several
/// threads at once.
pub trait Remote: Send + Sync {
    /// The entries of folder `dir` (`""` = the root), below it too when `recursive`. A folder that is
    /// not there is [`Error::NotFound`].
    fn list(&self, dir: &str, recursive: bool) -> Result<Vec<RemoteEntry>>;
    /// Copy the remote file `path` to the local file `local` (replacing it; the caller makes the
    /// write atomic by passing a temporary name).
    fn get(&self, path: &str, local: &Path) -> Result<()>;
    /// Copy the local file to `path` on the remote (creating folders as needed).
    fn put(&self, local: &Path, path: &str) -> Result<()>;
    /// The entry at `path`, if there is one.
    fn stat(&self, path: &str) -> Result<Option<RemoteEntry>> {
        let (dir, name) = path.rsplit_once('/').unwrap_or(("", path));
        match self.list(dir, false) {
            Ok(v) => Ok(v.into_iter().find(|e| e.path == name)),
            Err(Error::NotFound(_)) => Ok(None),
            Err(e) => Err(e),
        }
    }
}

/// A folder on this device standing in for the remote (tests, a synced folder).
#[derive(Debug, Clone)]
pub struct DirRemote {
    root: PathBuf,
}

impl DirRemote {
    pub fn new(root: impl Into<PathBuf>) -> Self {
        DirRemote { root: root.into() }
    }

    pub fn root(&self) -> &Path {
        &self.root
    }

    fn at(&self, path: &str) -> Result<PathBuf> {
        let mut p = self.root.clone();
        for part in path.split('/').filter(|c| !c.is_empty()) {
            if part == ".." || part == "." {
                return Err(Error::Invalid(format!("bad remote path {path}")));
            }
            p.push(part);
        }
        Ok(p)
    }
}

fn walk(base: &Path, dir: &Path, recursive: bool, out: &mut Vec<RemoteEntry>) -> std::io::Result<()> {
    for e in std::fs::read_dir(dir)? {
        let e = e?;
        let md = e.metadata()?;
        let rel = e.path().strip_prefix(base).map(|p| p.to_string_lossy().replace('\\', "/")).unwrap_or_default();
        out.push(RemoteEntry { path: rel, size: if md.is_dir() { 0 } else { md.len() }, is_dir: md.is_dir() });
        if md.is_dir() && recursive {
            walk(base, &e.path(), true, out)?;
        }
    }
    Ok(())
}

impl Remote for DirRemote {
    fn list(&self, dir: &str, recursive: bool) -> Result<Vec<RemoteEntry>> {
        let at = self.at(dir)?;
        if !at.is_dir() {
            return Err(Error::NotFound(dir.to_string()));
        }
        let mut out = Vec::new();
        walk(&at, &at, recursive, &mut out).map_err(|e| Error::io(dir, e))?;
        out.sort_by(|a, b| a.path.cmp(&b.path));
        Ok(out)
    }

    fn get(&self, path: &str, local: &Path) -> Result<()> {
        let from = self.at(path)?;
        if !from.is_file() {
            return Err(Error::NotFound(path.to_string()));
        }
        std::fs::copy(&from, local).map(|_| ()).map_err(|e| Error::io(path, e))
    }

    fn put(&self, local: &Path, path: &str) -> Result<()> {
        let to = self.at(path)?;
        if let Some(dir) = to.parent() {
            std::fs::create_dir_all(dir).map_err(|e| Error::io(path, e))?;
        }
        std::fs::copy(local, &to).map(|_| ()).map_err(|e| Error::io(path, e))
    }
}
