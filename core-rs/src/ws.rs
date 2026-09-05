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
#[derive(Debug, Default)]
pub struct WsDecoder {
    buf: Vec<u8>,
    fragments: Vec<u8>,
    fragment_opcode: Option<u8>,
}

impl WsDecoder {
    pub fn new() -> Self {
        WsDecoder::default()
    }

    /// 喂数据；返回完成的消息（一次最多一条）。
    pub fn push(&mut self, chunk: &[u8]) -> Result<Option<WsMessage>, WsError> {
        self.buf.extend_from_slice(chunk);
        self.try_decode()
    }

    fn try_decode(&mut self) -> Result<Option<WsMessage>, WsError> {
        let header = self.read_n(2)?;
        let Some(header) = header else { return Ok(None) };
        let fin = header[0] & 0x80 != 0;
        let rsv = header[0] & 0x70;
        if rsv != 0 {
            return Err(WsError::Protocol("reserved bits set".into()));
        }
        let opcode = header[0] & 0x0F;
        let masked = header[1] & 0x80 != 0;
        if !masked {
            return Err(WsError::Protocol("client frames must be masked".into()));
        }
        let mut len = (header[1] & 0x7F) as usize;
        if len == 126 {
            let Some(ext) = self.read_n(2)? else { return Ok(None) };
            len = u16::from_be_bytes([ext[0], ext[1]]) as usize;
        } else if len == 127 {
            let Some(ext) = self.read_n(8)? else { return Ok(None) };
            let v = u64::from_be_bytes(ext.try_into().unwrap());
            if v > MAX_WS_PAYLOAD as u64 {
                return Err(WsError::TooLarge);
            }
            len = v as usize;
        }
        if len > MAX_WS_PAYLOAD {
            return Err(WsError::TooLarge);
        }
        let Some(mask) = self.read_n(4)? else { return Ok(None) };
        let Some(masked_payload) = self.read_n(len)? else { return Ok(None) };
        let payload: Vec<u8> = masked_payload
            .iter()
            .enumerate()
            .map(|(i, b)| b ^ mask[i % 4])
            .collect();

        match opcode {
            OPCODE_CONT => {
                let Some(first) = self.fragment_opcode else {
                    return Err(WsError::Protocol("continuation without start".into()));
                };
                self.fragments.extend_from_slice(&payload);
                if self.fragments.len() > MAX_WS_PAYLOAD {
                    return Err(WsError::TooLarge);
                }
                if fin {
                    let data = std::mem::take(&mut self.fragments);
                    self.fragment_opcode = None;
                    return self.assemble(first, data);
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

    fn read_n(&mut self, n: usize) -> Result<Option<Vec<u8>>, WsError> {
        if self.buf.len() < n {
            return Ok(None);
        }
        Ok(Some(self.buf.drain(..n).collect()))
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
        if payload.len() < 126 {
            out.push(0x80 | payload.len() as u8);
        } else {
            out.push(0x80 | 126);
            out.extend_from_slice(&(payload.len() as u16).to_be_bytes());
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
}
