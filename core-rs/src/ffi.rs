//! uniffi FFI 导出面：供 Android（Kotlin）与 Apple（Swift）绑定复用。
//!
//! 只导出平台无关原语：会话加密、防重放窗口、WS 帧编解码。
//! JSON 信封编解码由各端自持（Kotlin 有 StrictJson）。

use std::sync::Mutex;

use uniffi::Object;

use crate::crypto::{DirectionCipher, SessionKeys};
use crate::replay::ReplayGuard;
use crate::ws::{encode_frame, WsDecoder, MAX_WS_PAYLOAD};

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum FfiError {
    #[error("authentication failed")]
    AuthFailed,
    #[error("payload too large")]
    TooLarge,
    #[error("invalid input: {0}")]
    Invalid(String),
    #[error("connection closed")]
    Closed,
    #[error("internal error: {0}")]
    Internal(String),
}

impl From<crate::crypto::CryptoError> for FfiError {
    fn from(e: crate::crypto::CryptoError) -> Self {
        match e {
            crate::crypto::CryptoError::AuthFailed => FfiError::AuthFailed,
            crate::crypto::CryptoError::TooLarge(_) => FfiError::TooLarge,
            other => FfiError::Internal(other.to_string()),
        }
    }
}

impl From<crate::ws::WsError> for FfiError {
    fn from(e: crate::ws::WsError) -> Self {
        match e {
            crate::ws::WsError::Closed => FfiError::Closed,
            crate::ws::WsError::TooLarge => FfiError::TooLarge,
            crate::ws::WsError::Protocol(m) => FfiError::Invalid(m),
            crate::ws::WsError::Io(m) => FfiError::Internal(m),
        }
    }
}

/// 会话加密集合：两个方向的 cipher + 防重放窗口。
#[derive(Object)]
pub struct SessionCrypto {
    client_to_server: Mutex<DirectionCipher>,
    server_to_client: Mutex<DirectionCipher>,
    replay: Mutex<ReplayGuard>,
}

#[uniffi::export]
impl SessionCrypto {
    /// 从 PSK 与双方随机数派生；`is_client` 决定本端使用哪个方向。
    #[uniffi::constructor]
    pub fn new(
        psk: Vec<u8>,
        client_random: Vec<u8>,
        server_random: Vec<u8>,
        _is_client: bool,
        replay_window_bits: u8,
    ) -> Result<Self, FfiError> {
        let psk: [u8; 32] = psk
            .try_into()
            .map_err(|_| FfiError::Invalid("psk must be 32 bytes".into()))?;
        let client_random: [u8; 32] = client_random
            .try_into()
            .map_err(|_| FfiError::Invalid("client_random must be 32 bytes".into()))?;
        let server_random: [u8; 32] = server_random
            .try_into()
            .map_err(|_| FfiError::Invalid("server_random must be 32 bytes".into()))?;
        let keys = SessionKeys::derive(&psk, &client_random, &server_random);
        Ok(SessionCrypto {
            client_to_server: Mutex::new(DirectionCipher::new(keys.client_to_server)),
            server_to_client: Mutex::new(DirectionCipher::new(keys.server_to_client)),
            replay: Mutex::new(ReplayGuard::new(replay_window_bits as u32)),
        })
    }

    /// 加密本端发出消息（client 用 c2s，server 用 s2c），返回 ciphertext||tag。
    pub fn seal(&self, is_client: bool, plaintext: Vec<u8>, aad: Vec<u8>) -> Result<Vec<u8>, FfiError> {
        if plaintext.len() > MAX_WS_PAYLOAD {
            return Err(FfiError::TooLarge);
        }
        let mut cipher = if is_client {
            self.client_to_server.lock().unwrap()
        } else {
            self.server_to_client.lock().unwrap()
        };
        Ok(cipher.seal(&plaintext, &aad)?)
    }

    /// 解密对端消息；`counter` 为对端发送计数器，由防重放窗口预先校验。
    pub fn open(
        &self,
        is_client: bool,
        ciphertext: Vec<u8>,
        counter: u64,
        aad: Vec<u8>,
    ) -> Result<Vec<u8>, FfiError> {
        let cipher = if is_client {
            self.server_to_client.lock().unwrap()
        } else {
            self.client_to_server.lock().unwrap()
        };
        Ok(cipher.open(&ciphertext, counter, &aad)?)
    }

    /// 防重放检查：重复/过旧返回 false。
    pub fn check_sequence(&self, sequence: u64) -> bool {
        self.replay.lock().unwrap().check_and_accept(sequence)
    }
}

/// WS 帧增量解码器句柄。
#[derive(Object)]
pub struct WsCodec {
    decoder: Mutex<WsDecoder>,
}

/// 解码出的一条完整 WS 消息。
#[derive(Debug, Clone, uniffi::Record)]
pub struct WsFrame {
    /// RFC 6455 opcode：1=text 2=binary 8=close 9=ping 10=pong。
    pub opcode: u8,
    pub payload: Vec<u8>,
}

impl Default for WsCodec {
    fn default() -> Self {
        WsCodec { decoder: std::sync::Mutex::new(WsDecoder::new()) }
    }
}

#[uniffi::export]
impl WsCodec {
    #[uniffi::constructor]
    pub fn new() -> Self {
        WsCodec { decoder: Mutex::new(WsDecoder::new()) }
    }

    /// 喂入 TCP 字节片段，返回本次解出的完整消息（可能为空）。
    pub fn push(&self, chunk: Vec<u8>) -> Result<Vec<WsFrame>, FfiError> {
        let mut decoder = self.decoder.lock().unwrap();
        let mut frames = Vec::new();
        match decoder.push(&chunk) {
            Ok(Some(msg)) => frames.push(wire(msg)),
            Ok(None) => {}
            Err(crate::ws::WsError::Closed) => {
                frames.push(WsFrame { opcode: 8, payload: Vec::new() });
            }
            Err(e) => return Err(e.into()),
        }
        Ok(frames)
    }

    /// 编码服务端出向帧（不掩码）。
    pub fn encode(&self, opcode: u8, payload: Vec<u8>) -> Result<Vec<u8>, FfiError> {
        Ok(encode_frame(opcode, &payload)?)
    }

    /// 编码客户端出向帧（RFC 6455 强制掩码，掩码键随机生成）。
    pub fn encode_client(&self, opcode: u8, payload: Vec<u8>) -> Result<Vec<u8>, FfiError> {
        let mut mask = [0u8; 4];
        crate::crypto::random_bytes(&mut mask);
        Ok(crate::ws::encode_client_frame(opcode, &payload, &mask)?)
    }
}

fn wire(msg: crate::ws::WsMessage) -> WsFrame {
    use crate::ws::{OPCODE_BINARY, OPCODE_PING, OPCODE_PONG, OPCODE_TEXT};
    match msg {
        crate::ws::WsMessage::Text(t) => WsFrame { opcode: OPCODE_TEXT, payload: t.into_bytes() },
        crate::ws::WsMessage::Binary(b) => WsFrame { opcode: OPCODE_BINARY, payload: b },
        crate::ws::WsMessage::Ping(p) => WsFrame { opcode: OPCODE_PING, payload: p },
        crate::ws::WsMessage::Pong(p) => WsFrame { opcode: OPCODE_PONG, payload: p },
        crate::ws::WsMessage::Close(_) => WsFrame { opcode: 8, payload: Vec::new() },
    }
}
