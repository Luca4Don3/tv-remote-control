//! 会话密码学：HKDF-SHA256 派生 + AES-256-GCM 信封加密。
//!
//! 密钥分离：每个方向（client→server / server→client）独立密钥；
//! nonce 为 12B：8B 大端计数器 + 4B 零前缀；AAD 参与认证。

use aes_gcm::aead::consts::U12;
use aes_gcm::aead::generic_array::GenericArray;
use aes_gcm::aead::{Aead, KeyInit, Payload};
use aes_gcm::{Aes256Gcm, Key};
use hkdf::Hkdf;
use rand::RngCore;
use sha2::Sha256;
use subtle::ConstantTimeEq;
use thiserror::Error;
use zeroize::Zeroize;

/// RFC 5869 HKDF-SHA256：extract + expand。
pub fn hkdf_sha256(salt: &[u8], ikm: &[u8], info: &[u8], okm: &mut [u8]) {
    let hk = Hkdf::<Sha256>::new(if salt.is_empty() { None } else { Some(salt) }, ikm);
    hk.expand(info, okm).expect("hkdf expand overflow");
}

/// 双向会话密钥集（各 32B）。
#[derive(Debug, Clone)]
pub struct SessionKeys {
    pub client_to_server: [u8; 32],
    pub server_to_client: [u8; 32],
}

impl SessionKeys {
    /// 从 PSK（配对协商的 32B 共享密钥）与双方随机数派生。
    pub fn derive(
        psk: &[u8; 32],
        client_random: &[u8; 32],
        server_random: &[u8; 32],
    ) -> SessionKeys {
        let hk = Hkdf::<Sha256>::new(
            Some(&[client_random.as_slice(), server_random.as_slice()].concat()),
            psk,
        );
        let mut c2s = [0u8; 32];
        let mut s2c = [0u8; 32];
        hk.expand(b"tvremote c2s key", &mut c2s).expect("hmac length");
        hk.expand(b"tvremote s2c key", &mut s2c).expect("hmac length");
        SessionKeys {
            client_to_server: c2s,
            server_to_client: s2c,
        }
    }
}

#[derive(Debug, Error)]
pub enum CryptoError {
    #[error("payload too large: {0}")]
    TooLarge(usize),
    #[error("authentication failed")]
    AuthFailed,
    #[error("nonce exhausted")]
    NonceExhausted,
    #[error("crypto error: {0}")]
    Other(String),
}

/// 单方向加密器：持有密钥与发送计数器。
pub struct DirectionCipher {
    cipher: Aes256Gcm,
    send_counter: u64,
}

impl DirectionCipher {
    pub fn new(key: [u8; 32]) -> Self {
        DirectionCipher {
            cipher: Aes256Gcm::new(Key::<Aes256Gcm>::from_slice(&key)),
            // 加密信封 counter 从 1 开始：0 在防重放窗口中为“未收到任何消息”哨兵值
            send_counter: 1,
        }
    }

    /// 12B nonce：4B 零前缀 + 8B 大端计数器。
    fn nonce(counter: u64) -> GenericArray<u8, U12> {
        let mut n = [0u8; 12];
        n[4..].copy_from_slice(&counter.to_be_bytes());
        *GenericArray::from_slice(&n)
    }

    /// 加密：counter 与 extra_aad 并入加密明文体（认证绑定；旧平台 Cipher.updateAAD
    /// 在 API 19 Dalvik/BC 不可用，故不使用 AAD 通道）。返回 counter 头 || ciphertext || tag。
    pub fn seal(&mut self, plaintext: &[u8], extra_aad: &[u8]) -> Result<Vec<u8>, CryptoError> {
        let counter = self.send_counter;
        self.send_counter = self
            .send_counter
            .checked_add(1)
            .ok_or(CryptoError::NonceExhausted)?;
        let mut body = Vec::with_capacity(8 + extra_aad.len() + plaintext.len());
        body.extend_from_slice(&counter.to_be_bytes());
        body.extend_from_slice(extra_aad);
        body.extend_from_slice(plaintext);
        let nonce = Self::nonce(counter);
        let encrypted = self
            .cipher
            .encrypt(&nonce, Payload { msg: &body, aad: &[] })
            .map_err(|_| CryptoError::AuthFailed)?;
        let mut out = Vec::with_capacity(8 + encrypted.len());
        out.extend_from_slice(&counter.to_be_bytes());
        out.extend_from_slice(&encrypted);
        Ok(out)
    }

    /// 解密（计数器由调用方按对端序号维护；内部校验加密体中的 counter/AAD）。
    pub fn open(
        &self,
        ciphertext: &[u8],
        counter: u64,
        extra_aad: &[u8],
    ) -> Result<Vec<u8>, CryptoError> {
        if ciphertext.len() <= 8 {
            return Err(CryptoError::Other("ciphertext too short".into()));
        }
        let nonce = Self::nonce(counter);
        let body = self
            .cipher
            .decrypt(&nonce, Payload { msg: &ciphertext[8..], aad: &[] })
            .map_err(|_| CryptoError::AuthFailed)?;
        if body.len() < 8 + extra_aad.len() {
            return Err(CryptoError::Other("decrypted body too short".into()));
        }
        let mut embedded = [0u8; 8];
        embedded.copy_from_slice(&body[..8]);
        if u64::from_be_bytes(embedded) != counter {
            return Err(CryptoError::Other("inner counter mismatch".into()));
        }
        if body[8..8 + extra_aad.len()] != *extra_aad {
            return Err(CryptoError::Other("extra aad mismatch".into()));
        }
        Ok(body[8 + extra_aad.len()..].to_vec())
    }
}

/// 常数时间比较（等长才可能相等）。
pub fn ct_eq(a: &[u8], b: &[u8]) -> bool {
    a.len() == b.len() && bool::from(a.ct_eq(b))
}

/// 加密随机字节。
pub fn random_bytes(buf: &mut [u8]) {
    rand::rngs::OsRng.fill_bytes(buf);
}

/// 内存清零（密钥生命周期结束时调用）。
pub fn zeroize_key(key: &mut [u8]) {
    key.zeroize();
}

#[cfg(test)]
mod tests {
    use super::*;
    use hex_literal::hex;

    #[test]
    fn hkdf_rfc5869_test_case_1() {
        // RFC 5869 A.1: SHA-256 基础向量
        let ikm = hex!("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b");
        let salt = hex!("000102030405060708090a0b0c");
        let info = hex!("f0f1f2f3f4f5f6f7f8f9");
        let mut okm = [0u8; 42];
        hkdf_sha256(&salt, &ikm, &info, &mut okm);
        let expect = hex!(
            "3cb25f25faacd57a90434f64d0362f2a
             2d2d0a90cf1a5a4c5db02d56ecc4c5bf
             34007208d5b887185865"
        );
        assert_eq!(okm, expect);
    }

    #[test]
    fn seal_open_roundtrip() {
        let mut a = DirectionCipher::new([7u8; 32]);
        let b = DirectionCipher::new([7u8; 32]);
        let ct = a.seal(b"hello", b"aad1").unwrap();
        let pt = b.open(&ct, 1, b"aad1").unwrap();
        assert_eq!(pt, b"hello");
    }

    #[test]
    fn aad_mismatch_fails() {
        let mut a = DirectionCipher::new([1u8; 32]);
        let b = DirectionCipher::new([1u8; 32]);
        let ct = a.seal(b"x", b"aad1").unwrap();
        assert!(b.open(&ct, 0, b"aad2").is_err());
    }

    #[test]
    fn replay_with_wrong_counter_fails() {
        let mut a = DirectionCipher::new([3u8; 32]);
        let b = DirectionCipher::new([3u8; 32]);
        let ct = a.seal(b"m", b"").unwrap();
        assert!(b.open(&ct, 5, b"").is_err());
    }

    #[test]
    fn ct_eq_edge() {
        assert!(ct_eq(&[], &[]));
        assert!(!ct_eq(&[1], &[2]));
        assert!(!ct_eq(&[1], &[1, 2]));
    }
}
