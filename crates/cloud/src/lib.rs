//! Client of a LightCraft cloud library: the files `lc-cloud` (the PC publisher) puts on an rclone remote
//! (see its FORMAT.md): thumbnails for the grid, smart previews to edit, edits sent back to the inbox.
//!
//! App-agnostic: the app gives a [`Remote`] (usually [`RcloneRemote`], rclone's own library loaded at
//! run time) and its engine as an [`Engine`] (a command id and JSON parameters, like the control protocol),
//! and calls [`CloudLibrary`]. Nothing here keeps the engine locked while the network is busy.

mod error;
mod library;
mod manifest;
mod rclone;
mod remote;
mod util;

pub use error::{Error, Result};
pub use library::{CloudLibrary, DEFAULT_CACHE_CAP, PhotoState, PreviewState, PushSummary, RefreshSummary, State, Status, valid_device};
pub use manifest::{FORMAT, Item};
pub use rclone::RcloneRemote;
pub use remote::{DirRemote, Remote, RemoteEntry};

/// The app's engine, as the cloud client drives it. Commands used: `library.addRemote`,
/// `photo.sidecarPaths`, `photo.readMetadataFromFile`, `library.smartPreviewsLocation`.
pub trait Engine {
    fn call(&mut self, command: &str, params: serde_json::Value) -> std::result::Result<serde_json::Value, String>;
}
