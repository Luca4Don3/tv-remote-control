//! 帧层：4 字节大端 u32 长度前缀 + 载荷。
//! 与 Kotlin `FrameCodec.kt` 语义对齐：非空、≤ 64 KiB。

use std::io::{Read, Write};

pub const MAGIC: [u8; 4] = *b"TVRC";
pub const MAX_FRAME_SIZE: usize = 64 * 1024;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Frame {
    pub payload: Vec<u8>,
}

impl Frame {
    pub fn new(payload: Vec<u8>) -> Result<Self, FrameError> {
        if payload.is_empty() {
            return Err(FrameError::Empty);
        }
        if payload.len() > MAX_FRAME_SIZE {
            return Err(FrameError::TooLarge(payload.len()));
        }
        Ok(Frame { payload })
    }
}

#[derive(Debug, thiserror::Error)]
pub enum FrameError {
    #[error("empty frame is not valid")]
    Empty,
    #[error("frame exceeds 64 KiB: {0}")]
    TooLarge(usize),
}

#[derive(Debug, thiserror::Error)]
pub enum FrameDecodeError {
    #[error("io error: {0}")]
    Io(#[from] std::io::Error),
    #[error("frame exceeds 64 KiB")]
    TooLarge,
    #[error("connection closed during a frame")]
    Eof,
    #[error("empty frame is not valid")]
    Empty,
}

impl From<FrameError> for FrameDecodeError {
    fn from(e: FrameError) -> Self {
        match e {
            FrameError::Empty => FrameDecodeError::Empty,
            FrameError::TooLarge(_) => FrameDecodeError::TooLarge,
        }
    }
}

/// 流式解码器：喂任意长度字节片段，产出完整帧。
#[derive(Debug, Default)]
pub struct FrameDecoder {
    buf: Vec<u8>,
}

impl FrameDecoder {
    pub fn new() -> Self {
        FrameDecoder { buf: Vec::new() }
    }

    /// 返回 `Ok(None)` 表示数据不足，继续喂；`Ok(Some(frame))` 表示一帧完成。
    pub fn push(&mut self, chunk: &[u8]) -> Result<Option<Frame>, FrameDecodeError> {
        self.buf.extend_from_slice(chunk);
        if self.buf.len() < 4 {
            return Ok(None);
        }
        let len = u32::from_be_bytes([self.buf[0], self.buf[1], self.buf[2], self.buf[3]]) as usize;
        if len > MAX_FRAME_SIZE {
            return Err(FrameDecodeError::TooLarge);
        }
        if self.buf.len() < 4 + len {
            return Ok(None);
        }
        let payload = self.buf[4..4 + len].to_vec();
        self.buf.drain(..4 + len);
        Ok(Some(Frame { payload }))
    }
}

/// 编码一帧到任意 `Write`（对齐 Kotlin `FrameCodec.write`）。
pub fn write_frame<W: Write>(out: &mut W, payload: &[u8]) -> Result<(), FrameDecodeError> {
    let frame = Frame::new(payload.to_vec())?;
    out.write_all(&(frame.payload.len() as u32).to_be_bytes())?;
    out.write_all(&frame.payload)?;
    out.flush()?;
    Ok(())
}

/// 从 `Read` 读取一帧（阻塞直到帧完成或 EOF）。
pub fn read_frame<R: Read>(input: &mut R) -> Result<Option<Frame>, FrameDecodeError> {
    let mut header = [0u8; 4];
    match input.read_exact(&mut header) {
        Ok(()) => {}
        Err(e) if e.kind() == std::io::ErrorKind::UnexpectedEof => return Ok(None),
        Err(e) => return Err(FrameDecodeError::Io(e)),
    }
    let len = u32::from_be_bytes(header) as usize;
    if len > MAX_FRAME_SIZE {
        return Err(FrameDecodeError::TooLarge);
    }
    let mut payload = vec![0u8; len];
    input.read_exact(&mut payload).map_err(FrameDecodeError::Io)?;
    Ok(Some(Frame { payload }))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;

    #[test]
    fn roundtrip() {
        let mut buf = Vec::new();
        let payload = b"hello world";
        write_frame(&mut buf, payload).unwrap();
        assert_eq!(buf.len(), 4 + payload.len());
        let mut cur = Cursor::new(&buf);
        let frame = read_frame(&mut cur).unwrap().unwrap();
        assert_eq!(frame.payload, payload);
        // 流结束后再读返回 None
        assert!(read_frame(&mut cur).unwrap().is_none());
    }

    #[test]
    fn decoder_streaming_chunks() {
        let mut buf = Vec::new();
        write_frame(&mut buf, b"abc").unwrap();
        let mut dec = FrameDecoder::new();
        assert!(dec.push(&buf[..3]).unwrap().is_none());
        assert!(dec.push(&buf[3..6]).unwrap().is_none());
        let f = dec.push(&buf[6..]).unwrap().unwrap();
        assert_eq!(f.payload, b"abc");
    }

    #[test]
    fn rejects_oversize() {
        let mut buf = Vec::new();
        buf.extend_from_slice(&(MAX_FRAME_SIZE as u32 + 1).to_be_bytes());
        let mut cur = Cursor::new(&buf);
        assert!(read_frame(&mut cur).is_err());
    }

    #[test]
    fn empty_payload_rejected() {
        assert!(Frame::new(Vec::new()).is_err());
    }
}
