/// What went wrong, as a message the user can act on.
#[derive(Debug, Clone, thiserror::Error)]
pub enum Error {
    /// The remote has no such file or folder.
    #[error("not found on the remote: {0}")]
    NotFound(String),
    /// The remote could not be reached or refused (network, login, quota…).
    #[error("remote: {0}")]
    Remote(String),
    /// A local file could not be read or written.
    #[error("{0}")]
    Io(String),
    /// The engine refused a command.
    #[error("engine: {0}")]
    Engine(String),
    /// The remote's files are not what the format says (or are of a newer format).
    #[error("{0}")]
    Format(String),
    /// The caller's request makes no sense (unknown photo, bad device name…).
    #[error("{0}")]
    Invalid(String),
}

pub type Result<T> = std::result::Result<T, Error>;

impl Error {
    pub(crate) fn io(what: impl std::fmt::Display, e: std::io::Error) -> Self {
        Error::Io(format!("{what}: {e}"))
    }
}
