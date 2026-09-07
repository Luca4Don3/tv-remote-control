/**
 * 会话加密：HKDF-SHA256 派生双向密钥 + AES-256-GCM（纯 JS，经 @noble/*）。
 *
 * 算法与 Kotlin `DebugSessionCrypto` / Rust `crypto.rs` 逐条对齐：
 * - salt = client_random || server_random；IKM = psk（32B）
 * - info = "tvremote c2s key" / "tvremote s2c key"，各出 32B
 * - nonce = 12B：4B 零前缀 + 8B 大端 counter
 * - 密文布局：counter(8B) || ciphertext || tag(16B)
 * - counter 与 extraAad 并入加密明文体（认证绑定；counter 从 1 起，0 为防重放哨兵）
 */

import { hkdf } from "@noble/hashes/hkdf";
import { sha256 } from "@noble/hashes/sha256";
import { gcm } from "@noble/ciphers/aes";
import { readCounterBigEndian, writeCounterBigEndian } from "./frame.js";
import { utf8Encode } from "./text.js";

export const SECRET_BYTES = 32;
export const RANDOM_BYTES = 32;
export const COUNTER_BYTES = 8;
export const GCM_TAG_BYTES = 16;

export interface SessionKeys {
  clientToServer: Uint8Array;
  serverToClient: Uint8Array;
}

/** 双向密钥派生（与 Kotlin/Rust 双端黄金向量一致）。 */
export function deriveSessionKeys(
  psk: Uint8Array,
  clientRandom: Uint8Array,
  serverRandom: Uint8Array,
): SessionKeys {
  if (psk.length !== SECRET_BYTES) throw new Error("psk must be 32 bytes");
  if (clientRandom.length !== RANDOM_BYTES || serverRandom.length !== RANDOM_BYTES) {
    throw new Error("randoms must be 32 bytes");
  }
  const salt = new Uint8Array(clientRandom.length + serverRandom.length);
  salt.set(clientRandom, 0);
  salt.set(serverRandom, clientRandom.length);
  return {
    clientToServer: hkdf(sha256, psk, salt, utf8Encode("tvremote c2s key"), SECRET_BYTES),
    serverToClient: hkdf(sha256, psk, salt, utf8Encode("tvremote s2c key"), SECRET_BYTES),
  };
}

/** RFC 5869 HKDF-SHA256（与 Kotlin DebugSessionCrypto.hkdfSha256 同实现语义）。 */
export function hkdfSha256(salt: Uint8Array, ikm: Uint8Array, info: Uint8Array, outLength: number): Uint8Array {
  return hkdf(sha256, ikm, salt, info, outLength);
}

/** nonce = 12B：4B 零前缀 + 8B 大端 counter。 */
export function nonceFor(counter: bigint): Uint8Array {
  const nonce = new Uint8Array(12);
  nonce.set(writeCounterBigEndian(counter, COUNTER_BYTES), 4);
  return nonce;
}

export interface SealResult {
  /** counter(8B) || ciphertext || tag(16B) */
  envelope: Uint8Array;
  counter: bigint;
}

/** 单方向加密封装：发送计数器内部递增；counter 与 extraAad 并入加密明文体。 */
export class DirectionCipher {
  private readonly key: Uint8Array;
  private sendCounter = 1n;

  constructor(key: Uint8Array) {
    if (key.length !== SECRET_BYTES) throw new Error("session key must be 32 bytes");
    this.key = key;
  }

  seal(plaintext: Uint8Array, extraAad: Uint8Array = new Uint8Array(0)): SealResult {
    if (this.sendCounter === 0xfffffffffffffffn) throw new Error("nonce exhausted");
    const counter = this.sendCounter;
    this.sendCounter += 1n;
    const body = new Uint8Array(COUNTER_BYTES + extraAad.length + plaintext.length);
    body.set(writeCounterBigEndian(counter, COUNTER_BYTES), 0);
    body.set(extraAad, COUNTER_BYTES);
    body.set(plaintext, COUNTER_BYTES + extraAad.length);
    const cipher = gcm(this.key, nonceFor(counter));
    const encrypted = cipher.encrypt(body);
    const envelope = new Uint8Array(COUNTER_BYTES + encrypted.length);
    envelope.set(writeCounterBigEndian(counter, COUNTER_BYTES), 0);
    envelope.set(encrypted, COUNTER_BYTES);
    return { envelope, counter };
  }

  /** 解密（对端计数器由调用方经防重放窗口校验后传入；内部校验加密体中的 counter/AAD）。 */
  open(envelope: Uint8Array, counter: bigint, extraAad: Uint8Array = new Uint8Array(0)): Uint8Array {
    if (envelope.length <= COUNTER_BYTES + GCM_TAG_BYTES) {
      throw new Error("ciphertext too short");
    }
    const embedded = readCounterBigEndian(envelope.slice(0, COUNTER_BYTES));
    if (embedded !== counter) throw new Error("counter mismatch");
    const cipher = gcm(this.key, nonceFor(counter));
    const body = cipher.decrypt(envelope.slice(COUNTER_BYTES));
    if (body.length < COUNTER_BYTES + extraAad.length) {
      throw new Error("decrypted body too short");
    }
    const expected = readCounterBigEndian(body.slice(0, COUNTER_BYTES));
    if (expected !== counter) throw new Error("inner counter mismatch");
    for (let i = 0; i < extraAad.length; i += 1) {
      if (body[COUNTER_BYTES + i] !== extraAad[i]) throw new Error("extra aad mismatch");
    }
    return body.slice(COUNTER_BYTES + extraAad.length);
  }
}

export { randomBytes, setRandomSource } from "./random.js";
