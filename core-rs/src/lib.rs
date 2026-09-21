//! TV Remote core: session crypto, replay protection, and WebSocket codec.
//!
//! 仅保留活跃通信路径所需原语；JSON 信封编解码由各端自持
//! （Kotlin 有 `StrictJson`，Apple/小程序各自实现）。

pub mod crypto;
pub mod replay;
pub mod ffi;
pub mod ws;

pub use crypto::{CryptoError, DirectionCipher, SessionKeys};

uniffi::setup_scaffolding!("tvremote_core");
