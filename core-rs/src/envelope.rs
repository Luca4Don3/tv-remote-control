//! 协议信封：对齐 Kotlin `ProtocolCodec.kt`。
//!
//! 字段：protocolVersion(==1)、requestId、sessionId(可空)、sequence(>0)、type、payload(对象)。

use crate::json::{
    self, json_long, json_string, parse_object, require_long, require_object, require_string,
    JsonObject, JsonValue,
};
use std::fmt;

pub const VERSION: i64 = 1;
pub const MAX_REQUEST_ID_LENGTH: usize = 128;
pub const MAX_SESSION_ID_LENGTH: usize = 128;
pub const MAX_TYPE_LENGTH: usize = 64;

#[derive(Debug, Clone, PartialEq, thiserror::Error)]
pub enum ProtocolError {
    #[error("{0}")]
    Message(String),
    #[error(transparent)]
    Json(#[from] json::JsonError),
    #[error("frame exceeds 64 KiB")]
    TooLarge,
}

fn err(message: impl fmt::Display) -> ProtocolError {
    ProtocolError::Message(message.to_string())
}

fn is_valid_identifier(s: &str) -> bool {
    !s.is_empty()
        && s.len() <= MAX_REQUEST_ID_LENGTH.max(MAX_SESSION_ID_LENGTH)
        && s.chars()
            .all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | ':' | '-'))
}

fn is_valid_type(s: &str) -> bool {
    let mut chars = s.chars();
    match chars.next() {
        Some(c) if c.is_ascii_lowercase() => {}
        _ => return false,
    }
    s.len() <= MAX_TYPE_LENGTH
        && chars.all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '_')
}

#[derive(Debug, Clone, PartialEq)]
pub struct Envelope {
    pub request_id: String,
    pub session_id: String,
    pub sequence: i64,
    pub message_type: String,
    pub payload: JsonObject,
}

impl Envelope {
    pub fn new(
        request_id: &str,
        session_id: &str,
        sequence: i64,
        message_type: &str,
        payload: JsonObject,
    ) -> Result<Envelope, ProtocolError> {
        let envelope = Envelope {
            request_id: request_id.to_string(),
            session_id: session_id.to_string(),
            sequence,
            message_type: message_type.to_string(),
            payload,
        };
        envelope.validate()?;
        Ok(envelope)
    }

    fn validate(&self) -> Result<(), ProtocolError> {
        if !is_valid_identifier(&self.request_id) {
            return Err(err("invalid requestId"));
        }
        if !self.session_id.is_empty() && !is_valid_identifier(&self.session_id) {
            return Err(err("invalid sessionId"));
        }
        if self.sequence <= 0 {
            return Err(err("invalid sequence"));
        }
        if !is_valid_type(&self.message_type) {
            return Err(err("invalid message type"));
        }
        Ok(())
    }

    /// 从 JSON 字节解码（语义对齐 Kotlin `decode`）。
    pub fn decode(bytes: &[u8]) -> Result<Envelope, ProtocolError> {
        let root = parse_object(bytes)?;
        let version = require_long(&root, "protocolVersion")?;
        if version != VERSION {
            return Err(err("unsupported protocolVersion"));
        }
        let request_id = require_string(&root, "requestId", MAX_REQUEST_ID_LENGTH)?;
        if !is_valid_identifier(&request_id) {
            return Err(err("invalid requestId"));
        }
        let session_id = match root.get("sessionId") {
            None => return Err(err("field 'sessionId' must be a string")),
            Some(JsonValue::String(s)) => s.clone(),
            Some(_) => return Err(err("field 'sessionId' must be a string")),
        };
        if session_id.len() > MAX_SESSION_ID_LENGTH {
            return Err(err("field 'sessionId' is too long"));
        }
        if !session_id.is_empty() && !is_valid_identifier(&session_id) {
            return Err(err("invalid sessionId"));
        }
        let sequence = require_long(&root, "sequence")?;
        if sequence <= 0 {
            return Err(err("sequence must be in 1..Long.MAX_VALUE"));
        }
        let message_type = require_string(&root, "type", MAX_TYPE_LENGTH)?;
        if !is_valid_type(&message_type) {
            return Err(err("invalid message type"));
        }
        let payload = require_object(&root, "payload")?.clone();
        Ok(Envelope {
            request_id,
            session_id,
            sequence,
            message_type,
            payload,
        })
    }

    /// 编码为 JSON 字节（语义对齐 Kotlin `encode`）。
    pub fn encode(&self) -> Result<Vec<u8>, ProtocolError> {
        self.validate()?;
        let mut root = JsonObject::new();
        root.insert("protocolVersion", json_long(VERSION))
            .map_err(|e| err(e))?;
        root.insert("requestId", json_string(&self.request_id))
            .map_err(|e| err(e))?;
        root.insert("sessionId", json_string(&self.session_id))
            .map_err(|e| err(e))?;
        root.insert("sequence", json_long(self.sequence))
            .map_err(|e| err(e))?;
        root.insert("type", json_string(&self.message_type))
            .map_err(|e| err(e))?;
        root.insert("payload", JsonValue::Object(self.payload.clone()))
            .map_err(|e| err(e))?;
        let bytes = json::encode(&JsonValue::Object(root))?;
        if bytes.len() > crate::frame::MAX_FRAME_SIZE {
            return Err(ProtocolError::TooLarge);
        }
        Ok(bytes)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample(sequence: i64, message_type: &str) -> Envelope {
        Envelope::new("req-1", "", sequence, message_type, JsonObject::new()).unwrap()
    }

    #[test]
    fn roundtrip() {
        let env = sample(42, "key_event");
        let bytes = env.encode().unwrap();
        let decoded = Envelope::decode(&bytes).unwrap();
        assert_eq!(decoded, env);
    }

    #[test]
    fn wire_format_matches_kotlin_layout() {
        // 对齐 Kotlin encode 的字段顺序与取值
        let env = sample(7, "ping");
        let text = String::from_utf8(env.encode().unwrap()).unwrap();
        assert!(text.contains("\"protocolVersion\":1"));
        assert!(text.contains("\"requestId\":\"req-1\""));
        assert!(text.contains("\"sequence\":7"));
        assert!(text.contains("\"type\":\"ping\""));
        assert!(text.contains("\"payload\":{}"));
    }

    #[test]
    fn rejects_wrong_version() {
        let bytes = br#"{"protocolVersion":2,"requestId":"a","sessionId":"","sequence":1,"type":"ping","payload":{}}"#;
        assert!(matches!(
            Envelope::decode(bytes),
            Err(ProtocolError::Message(_))
        ));
    }

    #[test]
    fn rejects_zero_sequence() {
        assert!(Envelope::new("a", "", 0, "ping", JsonObject::new()).is_err());
        assert!(Envelope::new("a", "", -1, "ping", JsonObject::new()).is_err());
    }

    #[test]
    fn rejects_bad_identifiers() {
        assert!(Envelope::new("", "", 1, "ping", JsonObject::new()).is_err());
        assert!(Envelope::new("a", "x/y", 1, "ping", JsonObject::new()).is_err());
        assert!(Envelope::new("a", "", 1, "Ping", JsonObject::new()).is_err());
        assert!(Envelope::new("a", "", 1, "1ping", JsonObject::new()).is_err());
    }

    #[test]
    fn rejects_bad_json() {
        assert!(Envelope::decode(b"not json").is_err());
        assert!(Envelope::decode(b"[]").is_err());
        assert!(Envelope::decode(br#"{"protocolVersion":1}"#).is_err());
    }

    #[test]
    fn empty_session_id_allowed() {
        assert!(sample(1, "ping").session_id.is_empty());
    }
}
