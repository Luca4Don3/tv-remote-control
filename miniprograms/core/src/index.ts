/**
 * TV Remote Control —— 小程序共享协议核心（TS）。
 *
 * 与 agent 端（Kotlin `protocol-core`）及 Rust `core-rs` 的黄金向量保持一致：
 * - RFC 6455 WebSocket 帧编解码（客户端出向强制掩码；服务端入向不掩码）
 * - HKDF-SHA256 派生双向密钥（salt = clientRandom||serverRandom，IKM = psk，
 *   info = "tvremote c2s key" / "tvremote s2c key"，各 32B）
 * - AES-256-GCM 加密信封：counter(8B 大端) || ciphertext||tag(16B)；nonce = 4B 零前缀
 *   + 8B 大端 counter；counter 与 extraAad 并入加密明文体（认证绑定）
 * - 加密计数器从 1 起（0 为防重放哨兵）；命令序号严格递增
 */
export * from "./frame.js";
export * from "./crypto.js";
export * from "./envelope.js";
export * from "./replay.js";
