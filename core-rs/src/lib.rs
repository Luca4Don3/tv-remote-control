//! TV Remote core protocol: framing, session crypto, pairing.
//!
//! 帧格式：4 字节大端 u32 长度前缀 + 载荷（对齐 Kotlin `FrameCodec.kt`）。
//! 所有消息均为 JSON 控制消息（UTF-8）或二进制媒体包。

pub mod crypto;
pub mod envelope;
pub mod json;
pub mod frame;
pub mod pairing;

pub use frame::{write_frame, read_frame, Frame, FrameDecoder, FrameError, FrameDecodeError, MAGIC, MAX_FRAME_SIZE};
pub use crypto::{CryptoError, DirectionCipher, SessionKeys};
pub use envelope::{Envelope, ProtocolError, VERSION};
