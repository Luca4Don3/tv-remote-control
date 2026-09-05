//! 严格 JSON：与 Kotlin `StrictJson.kt` 语义逐条对齐。
//!
//! 限制：文档 ≤64 KiB、字符串 ≤4096 字符、嵌套 ≤12、容器 ≤256、值总数 ≤2048；
//! 拒绝重复字段、前导零、非法转义与孤立代理对。

use std::fmt;

pub const MAX_DOCUMENT_BYTES: usize = 64 * 1024;
pub const MAX_STRING_LENGTH: usize = 4 * 1024;
pub const MAX_DEPTH: usize = 12;
const MAX_CONTAINER_ENTRIES: usize = 256;
const MAX_VALUES: usize = 2_048;

#[derive(Debug, Clone, PartialEq)]
pub enum JsonValue {
    Object(JsonObject),
    Array(Vec<JsonValue>),
    String(String),
    Number(String),
    Bool(bool),
    Null,
}

/// 保序对象；构造入口拒绝重复字段。
#[derive(Debug, Clone, Default, PartialEq)]
pub struct JsonObject {
    fields: Vec<(String, JsonValue)>,
}

impl JsonObject {
    pub fn new() -> Self {
        JsonObject { fields: Vec::new() }
    }

    pub fn insert(&mut self, name: &str, value: JsonValue) -> Result<(), JsonError> {
        if self.fields.iter().any(|(k, _)| k == name) {
            return Err(JsonError::Message(format!("duplicate object field: {name}")));
        }
        self.fields.push((name.to_string(), value));
        if self.fields.len() > MAX_CONTAINER_ENTRIES {
            return Err(JsonError::Message("JSON object has too many fields".into()));
        }
        Ok(())
    }

    pub fn get(&self, name: &str) -> Option<&JsonValue> {
        self.fields.iter().find(|(k, _)| k == name).map(|(_, v)| v)
    }

    pub fn iter(&self) -> impl Iterator<Item = &(String, JsonValue)> {
        self.fields.iter()
    }

    pub fn len(&self) -> usize {
        self.fields.len()
    }

    pub fn is_empty(&self) -> bool {
        self.fields.is_empty()
    }
}

#[derive(Debug, Clone, PartialEq, thiserror::Error)]
pub enum JsonError {
    #[error("{0}")]
    Message(String),
}

/// 取字段（严格类型）。
impl JsonValue {
    pub fn as_object(&self) -> Option<&JsonObject> {
        match self {
            JsonValue::Object(o) => Some(o),
            _ => None,
        }
    }

    pub fn as_str(&self) -> Option<&str> {
        match self {
            JsonValue::String(s) => Some(s),
            _ => None,
        }
    }

    pub fn as_number(&self) -> Option<&str> {
        match self {
            JsonValue::Number(n) => Some(n),
            _ => None,
        }
    }
}

/// 解析 UTF-8 字节流为根对象。
pub fn parse_object(bytes: &[u8]) -> Result<JsonObject, JsonError> {
    if bytes.len() > MAX_DOCUMENT_BYTES {
        return Err(JsonError::Message("JSON document exceeds 64 KiB".into()));
    }
    let source = std::str::from_utf8(bytes)
        .map_err(|_| JsonError::Message("JSON document is not valid UTF-8".into()))?;
    let mut parser = Parser::new(source);
    parser.parse_root_object()
}

/// 序列化为紧凑 JSON 字节（转义规则对齐 Kotlin 端）。
pub fn encode(value: &JsonValue) -> Result<Vec<u8>, JsonError> {
    let mut out = String::new();
    append_value(&mut out, value)?;
    Ok(out.into_bytes())
}

fn append_value(out: &mut String, value: &JsonValue) -> Result<(), JsonError> {
    match value {
        JsonValue::Object(o) => {
            out.push('{');
            for (index, (key, child)) in o.iter().enumerate() {
                if index != 0 {
                    out.push(',');
                }
                append_quoted(out, key)?;
                out.push(':');
                append_value(out, child)?;
            }
            out.push('}');
        }
        JsonValue::Array(values) => {
            out.push('[');
            for (index, child) in values.iter().enumerate() {
                if index != 0 {
                    out.push(',');
                }
                append_value(out, child)?;
            }
            out.push(']');
        }
        JsonValue::String(s) => append_quoted(out, s)?,
        JsonValue::Number(raw) => {
            if !is_valid_number(raw) {
                return Err(JsonError::Message("invalid JSON number".into()));
            }
            out.push_str(raw);
        }
        JsonValue::Bool(b) => out.push_str(if *b { "true" } else { "false" }),
        JsonValue::Null => out.push_str("null"),
    }
    Ok(())
}

fn append_quoted(out: &mut String, value: &str) -> Result<(), JsonError> {
    if value.chars().count() > MAX_STRING_LENGTH {
        return Err(JsonError::Message("JSON string is too long".into()));
    }
    out.push('"');
    for c in value.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\u{0008}' => out.push_str("\\b"),
            '\u{000C}' => out.push_str("\\f"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if (c as u32) < 0x20 => {
                out.push_str(&format!("\\u{:04x}", c as u32));
            }
            c => out.push(c),
        }
    }
    out.push('"');
    Ok(())
}

struct Parser<'a> {
    source: &'a str,
    bytes: &'a [u8],
    offset: usize,
    value_count: usize,
}

impl<'a> Parser<'a> {
    fn new(source: &'a str) -> Self {
        Parser { source, bytes: source.as_bytes(), offset: 0, value_count: 0 }
    }

    fn err(&self, message: &str) -> JsonError {
        JsonError::Message(format!("{} at offset {}", message, self.offset))
    }

    fn parse_root_object(&mut self) -> Result<JsonObject, JsonError> {
        self.skip_whitespace();
        let value = self.parse_value(0)?;
        let obj = value
            .as_object()
            .ok_or_else(|| self.err("root value must be an object"))?
            .clone();
        self.skip_whitespace();
        if self.offset != self.bytes.len() {
            return Err(self.err("trailing JSON data"));
        }
        Ok(obj)
    }

    fn parse_value(&mut self, depth: usize) -> Result<JsonValue, JsonError> {
        if depth > MAX_DEPTH {
            return Err(self.err("JSON nesting is too deep"));
        }
        self.value_count += 1;
        if self.value_count > MAX_VALUES {
            return Err(self.err("JSON document has too many values"));
        }
        self.skip_whitespace();
        if self.offset >= self.bytes.len() {
            return Err(self.err("unexpected end of JSON"));
        }
        match self.bytes[self.offset] {
            b'{' => self.parse_object(depth + 1),
            b'[' => self.parse_array(depth + 1),
            b'"' => Ok(JsonValue::String(self.parse_string()?)),
            b't' => self.parse_literal("true").map(|_| JsonValue::Bool(true)),
            b'f' => self.parse_literal("false").map(|_| JsonValue::Bool(false)),
            b'n' => self.parse_literal("null").map(|_| JsonValue::Null),
            b'-' | b'0'..=b'9' => self.parse_number(),
            _ => Err(self.err("unexpected JSON token")),
        }
    }

    fn parse_object(&mut self, depth: usize) -> Result<JsonValue, JsonError> {
        self.expect(b'{')?;
        self.skip_whitespace();
        let mut fields = JsonObject::new();
        if self.consume(b'}') {
            return Ok(JsonValue::Object(fields));
        }
        loop {
            self.skip_whitespace();
            if self.offset >= self.bytes.len() || self.bytes[self.offset] != b'"' {
                return Err(self.err("object key must be a string"));
            }
            let name = self.parse_string()?;
            self.skip_whitespace();
            self.expect(b':')?;
            let value = self.parse_value(depth)?;
            fields.insert(&name, value).map_err(|e| self.err(&e.to_string()))?;
            self.skip_whitespace();
            if self.consume(b'}') {
                break;
            }
            self.expect(b',')?;
        }
        Ok(JsonValue::Object(fields))
    }

    fn parse_array(&mut self, depth: usize) -> Result<JsonValue, JsonError> {
        self.expect(b'[')?;
        self.skip_whitespace();
        let mut values = Vec::new();
        if self.consume(b']') {
            return Ok(JsonValue::Array(values));
        }
        loop {
            let value = self.parse_value(depth)?;
            values.push(value);
            if values.len() > MAX_CONTAINER_ENTRIES {
                return Err(self.err("JSON array has too many entries"));
            }
            self.skip_whitespace();
            if self.consume(b']') {
                break;
            }
            self.expect(b',')?;
        }
        Ok(JsonValue::Array(values))
    }

    fn parse_string(&mut self) -> Result<String, JsonError> {
        self.expect(b'"')?;
        let mut result = String::new();
        while self.offset < self.bytes.len() {
            let c = self.source[self.offset..].chars().next().unwrap();
            self.offset += c.len_utf8();
            match c {
                '"' => {
                    if result.chars().count() > MAX_STRING_LENGTH {
                        return Err(self.err("JSON string is too long"));
                    }
                    validate_surrogates(&result).map_err(|e| self.err(&e))?;
                    return Ok(result);
                }
                '\\' => result.push(self.parse_escape()?),
                c if (c as u32) < 0x20 => {
                    return Err(self.err("unescaped control character in string"));
                }
                c => result.push(c),
            }
            if result.chars().count() > MAX_STRING_LENGTH {
                return Err(self.err("JSON string is too long"));
            }
        }
        Err(self.err("unterminated JSON string"))
    }

    fn parse_escape(&mut self) -> Result<char, JsonError> {
        if self.offset >= self.bytes.len() {
            return Err(self.err("unterminated JSON escape"));
        }
        let escaped = self.bytes[self.offset];
        self.offset += 1;
        Ok(match escaped {
            b'"' => '"',
            b'\\' => '\\',
            b'/' => '/',
            b'b' => '\u{0008}',
            b'f' => '\u{000C}',
            b'n' => '\n',
            b'r' => '\r',
            b't' => '\t',
            b'u' => {
                if self.offset + 4 > self.bytes.len() {
                    return Err(self.err("short Unicode escape"));
                }
                let hex_str = &self.source[self.offset..self.offset + 4];
                let code = u32::from_str_radix(hex_str, 16)
                    .map_err(|_| self.err("invalid Unicode escape"))?;
                self.offset += 4;
                char::from_u32(code).ok_or_else(|| self.err("invalid Unicode escape"))?
            }
            _ => return Err(self.err("invalid JSON escape")),
        })
    }

    fn parse_number(&mut self) -> Result<JsonValue, JsonError> {
        let start = self.offset;
        if self.consume(b'-') && self.offset >= self.bytes.len() {
            return Err(self.err("invalid JSON number"));
        }
        if self.consume(b'0') {
            if self.offset < self.bytes.len() && self.bytes[self.offset].is_ascii_digit() {
                return Err(self.err("leading zero in JSON number"));
            }
        } else if self.offset < self.bytes.len() && (b'1'..=b'9').contains(&self.bytes[self.offset])
        {
            while self.offset < self.bytes.len() && self.bytes[self.offset].is_ascii_digit() {
                self.offset += 1;
            }
        } else {
            return Err(self.err("invalid JSON number"));
        }
        if self.consume(b'.') {
            let fraction_start = self.offset;
            while self.offset < self.bytes.len() && self.bytes[self.offset].is_ascii_digit() {
                self.offset += 1;
            }
            if fraction_start == self.offset {
                return Err(self.err("invalid JSON fraction"));
            }
        }
        if self.offset < self.bytes.len() && (self.bytes[self.offset] == b'e' || self.bytes[self.offset] == b'E')
        {
            self.offset += 1;
            if self.offset < self.bytes.len()
                && (self.bytes[self.offset] == b'+' || self.bytes[self.offset] == b'-')
            {
                self.offset += 1;
            }
            let exponent_start = self.offset;
            while self.offset < self.bytes.len() && self.bytes[self.offset].is_ascii_digit() {
                self.offset += 1;
            }
            if exponent_start == self.offset {
                return Err(self.err("invalid JSON exponent"));
            }
        }
        let raw = &self.source[start..self.offset];
        if !is_valid_number(raw) {
            return Err(self.err("invalid JSON number"));
        }
        Ok(JsonValue::Number(raw.to_string()))
    }

    fn parse_literal(&mut self, text: &str) -> Result<(), JsonError> {
        if !self.source[self.offset..].starts_with(text) {
            return Err(self.err("invalid JSON literal"));
        }
        self.offset += text.len();
        Ok(())
    }

    fn skip_whitespace(&mut self) {
        while self.offset < self.bytes.len()
            && matches!(self.bytes[self.offset], b' ' | b'\t' | b'\n' | b'\r')
        {
            self.offset += 1;
        }
    }

    fn expect(&mut self, c: u8) -> Result<(), JsonError> {
        if !self.consume(c) {
            return Err(self.err(&format!("expected '{}'", c as char)));
        }
        Ok(())
    }

    fn consume(&mut self, c: u8) -> bool {
        if self.offset < self.bytes.len() && self.bytes[self.offset] == c {
            self.offset += 1;
            true
        } else {
            false
        }
    }
}

/// 孤立代理对拒绝（Rust 的 char 已无代理对，仅 `\uXXXX` 转义出口已挡）。
fn validate_surrogates(_value: &str) -> Result<(), String> {
    Ok(())
}

/// 与 Kotlin `NUMBER_PATTERN` 对齐：`-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?`
fn is_valid_number(raw: &str) -> bool {
    let b = raw.as_bytes();
    let mut i = 0;
    if i < b.len() && b[i] == b'-' {
        i += 1;
    }
    if i >= b.len() {
        return false;
    }
    match b[i] {
        b'0' => i += 1,
        b'1'..=b'9' => {
            while i < b.len() && b[i].is_ascii_digit() {
                i += 1;
            }
        }
        _ => return false,
    }
    if i < b.len() && b[i] == b'.' {
        i += 1;
        let start = i;
        while i < b.len() && b[i].is_ascii_digit() {
            i += 1;
        }
        if start == i {
            return false;
        }
    }
    if i < b.len() && (b[i] == b'e' || b[i] == b'E') {
        i += 1;
        if i < b.len() && (b[i] == b'+' || b[i] == b'-') {
            i += 1;
        }
        let start = i;
        while i < b.len() && b[i].is_ascii_digit() {
            i += 1;
        }
        if start == i {
            return false;
        }
    }
    i == b.len()
}

/// 字段读取辅助（对齐 Kotlin 扩展函数）。
pub fn require_string(obj: &JsonObject, name: &str, max_len: usize) -> Result<String, JsonError> {
    let v = obj
        .get(name)
        .and_then(|v| v.as_str())
        .ok_or_else(|| JsonError::Message(format!("field '{name}' must be a string")))?;
    if v.chars().count() > max_len {
        return Err(JsonError::Message(format!("field '{name}' is too long")));
    }
    Ok(v.to_string())
}

pub fn optional_string(obj: &JsonObject, name: &str, max_len: usize) -> Result<Option<String>, JsonError> {
    match obj.get(name) {
        None => Ok(None),
        Some(JsonValue::String(s)) => {
            if s.chars().count() > max_len {
                Err(JsonError::Message(format!("field '{name}' is too long")))
            } else {
                Ok(Some(s.clone()))
            }
        }
        Some(_) => Err(JsonError::Message(format!("field '{name}' must be a string"))),
    }
}

pub fn require_long(obj: &JsonObject, name: &str) -> Result<i64, JsonError> {
    let source = obj
        .get(name)
        .and_then(|v| v.as_number())
        .ok_or_else(|| JsonError::Message(format!("field '{name}' must be an integer")))?;
    if source.contains('.') || source.to_ascii_lowercase().contains('e') {
        return Err(JsonError::Message(format!("field '{name}' must be an integer")));
    }
    source
        .parse::<i64>()
        .map_err(|_| JsonError::Message(format!("field '{name}' is outside the signed 64-bit range")))
}

pub fn require_object<'a>(obj: &'a JsonObject, name: &str) -> Result<&'a JsonObject, JsonError> {
    obj.get(name)
        .and_then(|v| v.as_object())
        .ok_or_else(|| JsonError::Message(format!("field '{name}' must be an object")))
}

/// 构造辅助。
pub fn json_string(value: &str) -> JsonValue {
    JsonValue::String(value.to_string())
}

pub fn json_long(value: i64) -> JsonValue {
    JsonValue::Number(value.to_string())
}

pub fn json_bool(value: bool) -> JsonValue {
    JsonValue::Bool(value)
}

impl fmt::Debug for Parser<'_> {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("Parser").field("offset", &self.offset).finish()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrip_object() {
        let mut obj = JsonObject::new();
        obj.insert("a", json_long(1)).unwrap();
        obj.insert("b", json_string("hello")).unwrap();
        let bytes = encode(&JsonValue::Object(obj.clone())).unwrap();
        let parsed = parse_object(&bytes).unwrap();
        assert_eq!(parsed, obj);
    }

    #[test]
    fn rejects_duplicate_fields() {
        let err = parse_object(br#"{"a":1,"a":2}"#);
        assert!(err.is_err());
    }

    #[test]
    fn rejects_leading_zero() {
        assert!(parse_object(br#"{"n":007}"#).is_err());
        assert!(parse_object(br#"{"n":01}"#).is_err());
    }

    #[test]
    fn rejects_trailing_data() {
        assert!(parse_object(br#"{"a":1} x"#).is_err());
    }

    #[test]
    fn rejects_deep_nesting() {
        let depth = 20;
        let mut s = String::new();
        for _ in 0..depth {
            s.push('[');
        }
        for _ in 0..depth {
            s.push(']');
        }
        assert!(parse_object(s.as_bytes()).is_err());
    }

    #[test]
    fn accepts_valid_number_forms() {
        assert!(parse_object(br#"{"a":0}"#).is_ok());
        assert!(parse_object(br#"{"a":-1.5e10}"#).is_ok());
        assert!(parse_object(br#"{"a":1E+3}"#).is_ok());
    }

    #[test]
    fn string_escapes() {
        let parsed = parse_object(r#"{"s":"a\"b\\c\nd"}"#.as_bytes()).unwrap();
        assert_eq!(parsed.get("s").unwrap().as_str(), Some("a\"b\\c\nd"));
        let out = encode(&JsonValue::Object(parsed)).unwrap();
        let reparsed = parse_object(&out).unwrap();
        assert_eq!(reparsed.get("s").unwrap().as_str(), Some("a\"b\\c\nd"));
    }

    #[test]
    fn rejects_invalid_escape() {
        assert!(parse_object(br#"{"s":"\q"}"#).is_err());
    }

    #[test]
    fn control_char_rejected_unescaped() {
        assert!(parse_object(b"{\"s\":\"\x01\"}").is_err());
    }
}
