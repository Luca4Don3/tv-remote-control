//! WebSocket 帧层（RFC 6455 服务端最小子集）。
//!
//! 仅支持 agent 调试通道所需：文本/二进制帧、分片续帧、ping/pong、close。
//! 服务端不掩码出向；入向必须带客户端掩码（协议要求）。

use std::io::Write;

pub const OPCODE_CONT: u8 = 0x0;
pub const OPCODE_TEXT: u8 = 0x1;
pub const OPCODE_BINARY: u8 = 0x2;
pub const OPCODE_CLOSE: u8 = 0x8;
pub const OPCODE_PING: u8 = 0x9;
pub const OPCODE_PONG: u8 = 0xA;

pub const MAX_WS_PAYLOAD: usize = 64 * 1024;

#[derive(Debug, Clone, PartialEq, thiserror::Error)]
pub enum WsError {
    #[error("io error: {0}")]
    Io(String),
    #[error("invalid ws frame: {0}")]
    Protocol(String),
    #[error("payload exceeds {MAX_WS_PAYLOAD}")]
    TooLarge,
    #[error("connection closed by peer")]
    Closed,
}

impl From<std::io::Error> for WsError {
    fn from(e: std::io::Error) -> Self {
        WsError::Io(e.to_string())
    }
}

/// 已组装的入向消息。
#[derive(Debug, Clone, PartialEq)]
pub enum WsMessage {
    Text(String),
    Binary(Vec<u8>),
    Ping(Vec<u8>),
    Pong(Vec<u8>),
    Close(Option<u16>),
}

/// 增量解码器：喂 TCP 字节片段，产出完整消息（自动处理分片）。
#[derive(Debug)]
pub struct WsDecoder {
    buf: Vec<u8>,
    fragments: Vec<u8>,
    fragment_opcode: Option<u8>,
    /// RFC 6455 掩码方向：服务端视角入向帧（来自客户端）必须掩码；
    /// 客户端视角入向帧（来自服务端）不得掩码。
    require_masked: bool,
}

impl Default for WsDecoder {
    fn default() -> Self {
        WsDecoder::server()
    }
}

impl WsDecoder {
    /// 服务端视角：入向（客户端→服务端）帧必须掩码。
    pub fn new() -> Self {
        Self::server()
    }

    pub fn server() -> Self {
        WsDecoder {
            buf: Vec::new(),
            fragments: Vec::new(),
            fragment_opcode: None,
            require_masked: true,
        }
    }

    /// 客户端视角：入向（服务端→客户端）帧不得掩码。
    pub fn client() -> Self {
        WsDecoder {
            buf: Vec::new(),
            fragments: Vec::new(),
            fragment_opcode: None,
            require_masked: false,
        }
    }

    /// 喂数据；返回完成的消息（一次最多返回一条）。
    ///
    /// 完整帧到齐前不消费缓存；消费非最终分片后继续检查已缓冲数据。
    /// 同一输入可能含多条完整消息——调用方需以空输入继续排空
    /// （FFI `WsCodec::push` 已内建该循环）。
    pub fn push(&mut self, chunk: &[u8]) -> Result<Option<WsMessage>, WsError> {
        self.buf.extend_from_slice(chunk);
        loop {
            let before = self.buf.len();
            let message = self.try_decode()?;
            if message.is_some() || self.buf.len() == before {
                return Ok(message);
            }
            // 消费了非最终分片但还没产生消息，继续检查已缓存数据。
        }
    }

    fn try_decode(&mut self) -> Result<Option<WsMessage>, WsError> {
        // 先只读字节计算整帧边界；帧头/扩展长度/掩码/载荷未全部到齐前不得消费缓存。
        if self.buf.len() < 2 {
            return Ok(None);
        }
        let first = self.buf[0];
        let second = self.buf[1];
        let fin = first & 0x80 != 0;
        if first & 0x70 != 0 {
            return Err(WsError::Protocol("reserved bits set".into()));
        }
        let opcode = first & 0x0F;
        let masked = second & 0x80 != 0;
        if masked != self.require_masked {
            return Err(WsError::Protocol(if self.require_masked {
                "client frames must be masked".into()
            } else {
                "server frames must not be masked".into()
            }));
        }
        let (len, mask_start) = match second & 0x7F {
            126 => {
                if self.buf.len() < 4 {
                    return Ok(None);
                }
                (u16::from_be_bytes([self.buf[2], self.buf[3]]) as u64, 4)
            }
            127 => {
                if self.buf.len() < 10 {
                    return Ok(None);
                }
                let mut bytes = [0u8; 8];
                bytes.copy_from_slice(&self.buf[2..10]);
                (u64::from_be_bytes(bytes), 10)
            }
            n => (u64::from(n), 2),
        };
        // 声明超限无需等待载荷到达即可拒绝。
        if len > MAX_WS_PAYLOAD as u64 {
            return Err(WsError::TooLarge);
        }
        // 仅掩码帧携带 4B 掩码键；未掩码帧（服务端出向）没有。
        let payload_start = mask_start + if masked { 4 } else { 0 };
        let frame_end = payload_start + len as usize;
        if self.buf.len() < frame_end {
            return Ok(None);
        }

        let mut payload = self.buf[payload_start..frame_end].to_vec();
        if masked {
            let mask = &self.buf[mask_start..payload_start];
            for (index, byte) in payload.iter_mut().enumerate() {
                *byte ^= mask[index % 4];
            }
        }
        self.buf.drain(..frame_end);

        match opcode {
            OPCODE_CONT => {
                let Some(frag_opcode) = self.fragment_opcode else {
                    return Err(WsError::Protocol("continuation without start".into()));
                };
                // 先检查累计长度，再追加。
                if payload.len() > MAX_WS_PAYLOAD.saturating_sub(self.fragments.len()) {
                    return Err(WsError::TooLarge);
                }
                self.fragments.extend_from_slice(&payload);
                if fin {
                    let data = std::mem::take(&mut self.fragments);
                    self.fragment_opcode = None;
                    return self.assemble(frag_opcode, data);
                }
                Ok(None)
            }
            OPCODE_TEXT | OPCODE_BINARY => {
                if fin {
                    return self.assemble(opcode, payload);
                }
                if self.fragment_opcode.is_some() {
                    return Err(WsError::Protocol("new fragment started mid-message".into()));
                }
                self.fragment_opcode = Some(opcode);
                self.fragments = payload;
                Ok(None)
            }
            OPCODE_PING => Ok(Some(WsMessage::Ping(payload))),
            OPCODE_PONG => Ok(Some(WsMessage::Pong(payload))),
            OPCODE_CLOSE => Err(WsError::Closed), // close 帧同时终结解码
            _ => Err(WsError::Protocol(format!("unknown opcode {opcode}"))),
        }
    }

    fn assemble(&self, opcode: u8, data: Vec<u8>) -> Result<Option<WsMessage>, WsError> {
        Ok(Some(match opcode {
            OPCODE_TEXT => {
                let text = String::from_utf8(data)
                    .map_err(|_| WsError::Protocol("invalid utf-8 in text frame".into()))?;
                WsMessage::Text(text)
            }
            OPCODE_BINARY => WsMessage::Binary(data),
            _ => unreachable!(),
        }))
    }
}

/// 客户端出向帧编码（RFC 6455 要求客户端→服务端帧必须掩码，掩码键由调用方提供）。
pub fn encode_client_frame(opcode: u8, payload: &[u8], mask: &[u8; 4]) -> Result<Vec<u8>, WsError> {
    if payload.len() > MAX_WS_PAYLOAD {
        return Err(WsError::TooLarge);
    }
    let mut out = Vec::with_capacity(payload.len() + 14);
    out.push(0x80 | opcode);
    let len = payload.len();
    if len < 126 {
        out.push(0x80 | len as u8);
    } else if len <= u16::MAX as usize {
        out.push(0x80 | 126);
        out.extend_from_slice(&(len as u16).to_be_bytes());
    } else {
        out.push(0x80 | 127);
        out.extend_from_slice(&(len as u64).to_be_bytes());
    }
    out.extend_from_slice(mask);
    out.extend(payload.iter().enumerate().map(|(i, b)| b ^ mask[i % 4]));
    Ok(out)
}

/// 服务端出向帧编码（不掩码）。
pub fn encode_frame(opcode: u8, payload: &[u8]) -> Result<Vec<u8>, WsError> {
    if payload.len() > MAX_WS_PAYLOAD {
        return Err(WsError::TooLarge);
    }
    let mut out = Vec::with_capacity(payload.len() + 10);
    out.push(0x80 | opcode);
    let len = payload.len();
    if len < 126 {
        out.push(len as u8);
    } else if len <= u16::MAX as usize {
        out.push(126);
        out.extend_from_slice(&(len as u16).to_be_bytes());
    } else {
        out.push(127);
        out.extend_from_slice(&(len as u64).to_be_bytes());
    }
    out.extend_from_slice(payload);
    Ok(out)
}

/// 服务端握手应答（Sec-WebSocket-Accept = base64(SHA1(key + GUID)) 由调用方计算）。
pub fn handshake_response(accept_key_b64: &str) -> String {
    format!(
        "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: {accept_key_b64}\r\n\r\n"
    )
}

/// 写出一条完整文本消息（单帧 FIN）。
pub fn write_text<W: Write>(out: &mut W, text: &str) -> Result<(), WsError> {
    let frame = encode_frame(OPCODE_TEXT, text.as_bytes())?;
    out.write_all(&frame)?;
    out.flush()?;
    Ok(())
}

/// 写出 pong。
pub fn write_pong<W: Write>(out: &mut W, payload: &[u8]) -> Result<(), WsError> {
    let frame = encode_frame(OPCODE_PONG, payload)?;
    out.write_all(&frame)?;
    out.flush()?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 手工构造掩码客户端帧。
    fn mask_frame(opcode: u8, payload: &[u8], fin: bool) -> Vec<u8> {
        let mut out = Vec::new();
        out.push((if fin { 0x80 } else { 0 }) | opcode);
        let mask = [0x11, 0x22, 0x33, 0x44];
        let len = payload.len();
        if len < 126 {
            out.push(0x80 | len as u8);
        } else if len <= u16::MAX as usize {
            out.push(0x80 | 126);
            out.extend_from_slice(&(len as u16).to_be_bytes());
        } else {
            out.push(0x80 | 127);
            out.extend_from_slice(&(len as u64).to_be_bytes());
        }
        out.extend_from_slice(&mask);
        out.extend(payload.iter().enumerate().map(|(i, b)| b ^ mask[i % 4]));
        out
    }

    #[test]
    fn text_roundtrip() {
        let frame = mask_frame(OPCODE_TEXT, b"hello", true);
        let mut dec = WsDecoder::new();
        let msg = dec.push(&frame).unwrap().unwrap();
        assert_eq!(msg, WsMessage::Text("hello".into()));
    }

    #[test]
    fn fragmented_message() {
        let p1 = mask_frame(OPCODE_TEXT, b"he", false);
        let p2 = mask_frame(OPCODE_CONT, b"llo", true);
        let mut dec = WsDecoder::new();
        assert!(dec.push(&p1).unwrap().is_none());
        let msg = dec.push(&p2).unwrap().unwrap();
        assert_eq!(msg, WsMessage::Text("hello".into()));
    }

    #[test]
    fn extended_length() {
        let payload = vec![0xABu8; 300];
        let frame = mask_frame(OPCODE_BINARY, &payload, true);
        let mut dec = WsDecoder::new();
        let msg = dec.push(&frame).unwrap().unwrap();
        assert_eq!(msg, WsMessage::Binary(payload));
    }

    #[test]
    fn unmasked_rejected() {
        let mut out = vec![0x81, 0x05];
        out.extend_from_slice(b"hello");
        let mut dec = WsDecoder::new();
        assert!(dec.push(&out).is_err());
    }

    /// 客户端视角：解析服务端出向帧（不掩码）；掩码帧反而必须被拒。
    #[test]
    fn client_view_accepts_unmasked_rejects_masked() {
        // 服务端出向：不掩码 binary 帧
        let mut frame = vec![0x82, 0x05];
        frame.extend_from_slice(b"hello");
        let mut dec = WsDecoder::client();
        let msg = dec.push(&frame).unwrap().expect("complete frame");
        assert_eq!(msg, WsMessage::Binary(b"hello".to_vec()));

        // 客户端掩码帧从客户端视角必须被拒（方向反转）
        let mask = [0x11, 0x22, 0x33, 0x44];
        let masked = encode_client_frame(1, b"world", &mask).unwrap();
        let mut dec2 = WsDecoder::client();
        assert!(dec2.push(&masked).is_err());
    }

    #[test]
    fn ping_decoded() {
        let frame = mask_frame(OPCODE_PING, b"x", true);
        let mut dec = WsDecoder::new();
        assert_eq!(dec.push(&frame).unwrap().unwrap(), WsMessage::Ping(vec![b'x']));
    }

    #[test]
    fn client_masked_frame_roundtrip() {
        let mask = [0xAB, 0xCD, 0xEF, 0x12];
        let frame = encode_client_frame(OPCODE_TEXT, b"masked", &mask).unwrap();
        // 服务端解码器必须能解客户端掩码帧（互操作锚点）
        let mut dec = WsDecoder::new();
        let msg = dec.push(&frame).unwrap().unwrap();
        assert_eq!(msg, WsMessage::Text("masked".into()));
        // 掩码位必须置位
        assert_ne!(frame[1] & 0x80, 0);
        // 扩展长度也带掩码
        let big = encode_client_frame(OPCODE_BINARY, &vec![0u8; 300], &mask).unwrap();
        let mut dec2 = WsDecoder::new();
        let msg2 = dec2.push(&big).unwrap().unwrap();
        assert_eq!(msg2, WsMessage::Binary(vec![0u8; 300]));
    }

    #[test]
    fn server_frame_encoding() {
        let frame = encode_frame(OPCODE_TEXT, b"ok").unwrap();
        assert_eq!(frame, vec![0x81, 0x02, b'o', b'k']);
        let big = encode_frame(OPCODE_BINARY, &vec![0u8; 126]).unwrap();
        assert_eq!(&big[..4], &[0x82, 126, 0x00, 0x7E]);
    }

    /// 帧头与载荷分两次到达：解码器必须保留解析状态。
    #[test]
    fn client_frame_can_arrive_in_two_reads() {
        let frame = encode_frame(OPCODE_BINARY, b"hello").unwrap();
        let mut decoder = WsDecoder::client();
        assert_eq!(decoder.push(&frame[..2]).unwrap(), None);
        assert_eq!(
            decoder.push(&frame[2..]).unwrap(),
            Some(WsMessage::Binary(b"hello".to_vec()))
        );
    }

    /// 掩码帧在任意 TCP 切分位置都必须可解。
    #[test]
    fn masked_frame_survives_arbitrary_tcp_splits() {
        let frame = mask_frame(OPCODE_BINARY, b"hello", true);
        for split in 0..=frame.len() {
            let mut dec = WsDecoder::new();
            let first = dec.push(&frame[..split]).unwrap();
            let msg = if first.is_some() { first } else { dec.push(&frame[split..]).unwrap() };
            assert_eq!(msg, Some(WsMessage::Binary(b"hello".to_vec())), "split at {split}");
        }
    }

    /// 扩展长度帧（126）在任意切分位置都必须可解。
    #[test]
    fn extended_length_survives_arbitrary_tcp_splits() {
        let payload = vec![0x7Eu8; 300];
        let frame = mask_frame(OPCODE_BINARY, &payload, true);
        for split in 0..=frame.len() {
            let mut dec = WsDecoder::new();
            let first = dec.push(&frame[..split]).unwrap();
            let msg = if first.is_some() { first } else { dec.push(&frame[split..]).unwrap() };
            assert_eq!(msg, Some(WsMessage::Binary(payload.clone())), "split at {split}");
        }
    }

    /// 客户端视角（服务端出向、不掩码）同样支持任意切分。
    #[test]
    fn client_view_frame_survives_arbitrary_tcp_splits() {
        let frame = encode_frame(OPCODE_BINARY, b"hello").unwrap();
        for split in 0..=frame.len() {
            let mut dec = WsDecoder::client();
            let first = dec.push(&frame[..split]).unwrap();
            let msg = if first.is_some() { first } else { dec.push(&frame[split..]).unwrap() };
            assert_eq!(msg, Some(WsMessage::Binary(b"hello".to_vec())), "split at {split}");
        }
    }

    /// 长度边界：125/126 与 65535/65536。
    #[test]
    fn length_boundaries_125_126_65535_65536() {
        for size in [125usize, 126, 65535, 65536] {
            let payload = vec![0x5Au8; size];
            let frame = mask_frame(OPCODE_BINARY, &payload, true);
            let mut dec = WsDecoder::new();
            let msg = dec.push(&frame).unwrap().expect("complete frame");
            assert_eq!(msg, WsMessage::Binary(payload), "size {size}");
        }
    }

    /// 声明长度超限时，无需等待载荷到达即可拒绝。
    #[test]
    fn declared_oversized_length_rejected_before_payload() {
        // 服务端视角：掩码帧 127 扩展长度声明 MAX+1，仅提供 10 字节帧头。
        let mut header = vec![0x82, 0x80 | 127];
        header.extend_from_slice(&(MAX_WS_PAYLOAD as u64 + 1).to_be_bytes());
        let mut server = WsDecoder::new();
        assert!(matches!(server.push(&header), Err(WsError::TooLarge)));

        // 客户端视角：不掩码帧同样提前拒绝。
        let mut header = vec![0x82, 127];
        header.extend_from_slice(&(MAX_WS_PAYLOAD as u64 + 1).to_be_bytes());
        let mut client = WsDecoder::client();
        assert!(matches!(client.push(&header), Err(WsError::TooLarge)));
    }

    /// 同一输入含多条完整消息：一次返回一条，空输入继续排空。
    #[test]
    fn multiple_complete_messages_require_drain() {
        let mut chunk = mask_frame(OPCODE_TEXT, b"one", true);
        chunk.extend_from_slice(&mask_frame(OPCODE_BINARY, b"two", true));
        let mut dec = WsDecoder::new();
        assert_eq!(dec.push(&chunk).unwrap(), Some(WsMessage::Text("one".into())));
        assert_eq!(dec.push(&[]).unwrap(), Some(WsMessage::Binary(b"two".to_vec())));
        assert_eq!(dec.push(&[]).unwrap(), None);
    }

    /// 起始分片与续帧同批到达：消费非最终分片后继续检查缓存。
    #[test]
    fn fragmented_start_and_continuation_in_one_chunk() {
        let mut chunk = mask_frame(OPCODE_TEXT, b"he", false);
        chunk.extend_from_slice(&mask_frame(OPCODE_CONT, b"llo", true));
        let mut dec = WsDecoder::new();
        assert_eq!(dec.push(&chunk).unwrap(), Some(WsMessage::Text("hello".into())));
    }

    /// 分片之间穿插控制帧：控制帧立即返回，续帧留在缓存按序消费。
    #[test]
    fn ping_interleaved_between_fragments() {
        let mut chunk = mask_frame(OPCODE_TEXT, b"he", false);
        chunk.extend_from_slice(&mask_frame(OPCODE_PING, b"p", true));
        chunk.extend_from_slice(&mask_frame(OPCODE_CONT, b"llo", true));
        let mut dec = WsDecoder::new();
        assert_eq!(dec.push(&chunk).unwrap(), Some(WsMessage::Ping(vec![b'p'])));
        assert_eq!(dec.push(&[]).unwrap(), Some(WsMessage::Text("hello".into())));
    }

    /// 完整消息后接半帧：半帧不消费，补齐后得到第二条。
    #[test]
    fn complete_message_followed_by_partial_frame() {
        let first = mask_frame(OPCODE_TEXT, b"one", true);
        let second = mask_frame(OPCODE_BINARY, b"two", true);
        let mut chunk = first;
        chunk.extend_from_slice(&second[..3]);
        let mut dec = WsDecoder::new();
        assert_eq!(dec.push(&chunk).unwrap(), Some(WsMessage::Text("one".into())));
        assert_eq!(dec.push(&[]).unwrap(), None);
        assert_eq!(dec.push(&second[3..]).unwrap(), Some(WsMessage::Binary(b"two".to_vec())));
    }

    /// 续帧累计长度超限：追加前拒绝。
    #[test]
    fn fragment_cumulative_limit_rejected_before_append() {
        let mut dec = WsDecoder::new();
        let big = mask_frame(OPCODE_BINARY, &vec![0u8; MAX_WS_PAYLOAD - 1], false);
        assert_eq!(dec.push(&big).unwrap(), None);
        let extra = mask_frame(OPCODE_CONT, &[0u8; 2], true);
        assert!(matches!(dec.push(&extra), Err(WsError::TooLarge)));
    }

    /// Close 帧终结解码。
    #[test]
    fn close_frame_terminates_decoding() {
        let frame = mask_frame(OPCODE_CLOSE, b"", true);
        let mut dec = WsDecoder::new();
        assert!(matches!(dec.push(&frame), Err(WsError::Closed)));
    }
}

