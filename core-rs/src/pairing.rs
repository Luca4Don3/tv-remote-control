//! 配对会话：双方交换 32B 随机数并从 PSK 派生双向会话密钥。
//!
//! 后续扩展：PIN 码派生、SAS 短认证串、challenge/respawns。

use crate::crypto::{random_bytes, SessionKeys};

/// 角色：发起方（controller）或接受方（TV）。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PairingRole {
    Client,
    Server,
}

/// 配对会话状态机（双方各持一端）。
pub struct PairingSession {
    pub role: PairingRole,
    /// 32B 本端随机数（对端需要它派生同一组密钥）。
    pub local_random: [u8; 32],
    /// 对端随机数（握手完成后填充）。
    pub remote_random: Option<[u8; 32]>,
    psk: [u8; 32],
}

impl PairingSession {
    /// 以预共享密钥（配对 PIN 派生或直接 PSK）初始化。
    pub fn new(role: PairingRole, psk: [u8; 32]) -> Self {
        let mut local_random = [0u8; 32];
        random_bytes(&mut local_random);
        PairingSession { role, local_random, remote_random: None, psk }
    }

    /// 本端随机数（发送给对端）。
    pub fn local_random(&self) -> &[u8; 32] {
        &self.local_random
    }

    /// 收到对端随机数，完成握手并派生会话密钥。
    pub fn finish(&mut self, remote_random: [u8; 32]) -> SessionKeys {
        self.remote_random = Some(remote_random);
        let (client_random, server_random) = match self.role {
            PairingRole::Client => (&self.local_random, &remote_random),
            PairingRole::Server => (&remote_random, &self.local_random),
        };
        SessionKeys::derive(&self.psk, client_random, server_random)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn both_sides_derive_same_keys() {
        let psk = [42u8; 32];
        let mut client = PairingSession::new(PairingRole::Client, psk);
        let mut server = PairingSession::new(PairingRole::Server, psk);
        let client_keys = client.finish(*server.local_random());
        let server_keys = server.finish(*client.local_random());
        assert_eq!(client_keys.client_to_server, server_keys.client_to_server);
        assert_eq!(client_keys.server_to_client, server_keys.server_to_client);
        // 不同 PSK 不得派生出相同密钥
        let mut other = PairingSession::new(PairingRole::Client, [43u8; 32]);
        let other_keys = other.finish(*server.local_random());
        assert_ne!(other_keys.client_to_server, server_keys.client_to_server);
    }

    #[test]
    fn local_randoms_differ() {
        let psk = [1u8; 32];
        let a = PairingSession::new(PairingRole::Client, psk);
        let b = PairingSession::new(PairingRole::Server, psk);
        assert_ne!(a.local_random(), b.local_random());
    }
}
