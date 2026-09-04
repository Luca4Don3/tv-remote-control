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
            send_counter: 0,
        }
    }

    /// 12B nonce：4B 零前缀 + 8B 大端计数器。
    fn nonce(counter: u64) -> GenericArray<u8, U12> {
        let mut n = [0u8; 12];
        n[4..].copy_from_slice(&counter.to_be_bytes());
        *GenericArray::from_slice(&n)
    }

    /// 加密（AAD 参与认证）。返回 ciphertext || tag。
    pub fn seal(&mut self, plaintext: &[u8], aad: &[u8]) -> Result<Vec<u8>, CryptoError> {
        let nonce = Self::nonce(self.send_counter);
        self.send_counter = self
            .send_counter
            .checked_add(1)
            .ok_or(CryptoError::NonceExhausted)?;
        self.cipher
            .encrypt(&nonce, Payload { msg: plaintext, aad })
            .map_err(|_| CryptoError::AuthFailed)
    }

    /// 解密（计数器由调用方按对端序号维护）。
    pub fn open(
        &self,
        ciphertext: &[u8],
        counter: u64,
        aad: &[u8],
    ) -> Result<Vec<u8>, CryptoError> {
        let nonce = Self::nonce(counter);
        self.cipher
            .decrypt(&nonce, Payload { msg: ciphertext, aad })
            .map_err(|_| CryptoError::AuthFailed)
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
        let pt = b.open(&ct, 0, b"aad1").unwrap();
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
