import { test } from "node:test";
import assert from "node:assert/strict";
import { hkdfSha256, deriveSessionKeys, DirectionCipher, randomBytes } from "../src/crypto.js";
import { encodeClientFrame, WsDecoder, OPCODE_BINARY, OPCODE_TEXT } from "../src/frame.js";
import { ReplayWindow } from "../src/replay.js";
import { encodeEnvelope, decodeEnvelope, classifyFrame, classifyEnvelope } from "../src/envelope.js";

function hex(bytes: Uint8Array): string {
  return Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
}
function unhex(text: string): Uint8Array {
  return new Uint8Array(text.match(/.{2}/g)!.map((h) => parseInt(h, 16)));
}

test("hkdfSha256 matches RFC 5869 test case 1", () => {
  const ikm = unhex("0b".repeat(22));
  const salt = unhex("000102030405060708090a0b0c");
  const info = unhex("f0f1f2f3f4f5f6f7f8f9");
  const okm = hkdfSha256(salt, ikm, info, 42);
  // RFC 5869 Appendix A.1
  assert.equal(
    hex(okm),
    "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
  );
});

test("hkdfSha256 matches RFC 5869 test case 3 (zero-length salt)", () => {
  const ikm = unhex("0b".repeat(22));
  const okm = hkdfSha256(new Uint8Array(0), ikm, new Uint8Array(0), 42);
  assert.equal(
    hex(okm),
    "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",
  );
});

test("deriveSessionKeys splits directional keys deterministically", () => {
  const psk = randomBytes(32);
  const clientRandom = randomBytes(32);
  const serverRandom = randomBytes(32);
  const keys = deriveSessionKeys(psk, clientRandom, serverRandom);
  // 两个方向密钥必须不同（HKDF info 不同），且同输入重复推导一致
  const again = deriveSessionKeys(psk, clientRandom, serverRandom);
  assert.deepEqual(again, keys);
  assert.notEqual(hex(keys.clientToServer), hex(keys.serverToClient));
  assert.equal(keys.clientToServer.length, 32);
  assert.equal(keys.serverToClient.length, 32);
});

test("DirectionCipher seal/open roundtrip with counter starting at 1", () => {
  const key = randomBytes(32);
  const cipher = new DirectionCipher(key);
  const plaintext = new TextEncoder().encode("tvrc ts vector");
  const { envelope, counter } = cipher.seal(plaintext);
  // counter 从 1 起（0 为防重放哨兵）
  assert.equal(counter, 1n);
  assert.equal(envelope[0], 0);
  assert.equal(envelope[7], 1);
  const opened = cipher.open(envelope, counter);
  assert.deepEqual(opened, plaintext);
  // GCM 本身无重放状态——重放拒绝由调用方 ReplayWindow 承担（语义见专项测试）
});

test("cross-cipher interop mirrors agent server semantics", () => {
  // 复刻 agent：serverCipher seal → client open（server counter 亦从 1 起）
  const psk = randomBytes(32);
  const clientRandom = randomBytes(32);
  const serverRandom = randomBytes(32);
  const keys = deriveSessionKeys(psk, clientRandom, serverRandom);
  const server = new DirectionCipher(keys.serverToClient);
  // client 解密服务端消息使用 server→client 方向密钥（对齐 Kotlin WsDebugClient.serverCipher）
  const client = new DirectionCipher(keys.serverToClient);

  const pingEnvelope = encodeEnvelope({
    protocolVersion: 1,
    requestId: "ws-ping-1",
    sessionId: "0".repeat(32),
    sequence: 2n,
    type: "ping",
    payload: {},
  });
  const { envelope } = server.seal(pingEnvelope);
  const counter = BigInt(envelope.slice(0, 8).reduce((acc, b) => (acc << 8n) | BigInt(b), 0n));
  assert.equal(counter, 1n);
  const opened = client.open(envelope, counter);
  const decoded = decodeEnvelope(opened);
  assert.equal(decoded.type, "ping");
});

test("WsDecoder accepts unmasked server frames and rejects masked ones", () => {
  // 服务端出向：不掩码 binary 帧
  const frame = new Uint8Array([0x82, 0x05, ...Buffer.from("hello")]);
  const decoded = new WsDecoder().push(frame);
  assert.ok(decoded);
  assert.equal(decoded.opcode, OPCODE_BINARY);
  assert.equal(new TextDecoder().decode(decoded.payload), "hello");

  // 客户端掩码帧从客户端视角必须被拒（方向反转，对齐 Rust client_view 测试）
  const mask = [0x11, 0x22, 0x33, 0x44];
  const payload = Buffer.from("world");
  const masked = new Uint8Array(6 + payload.length);
  masked[0] = 0x81;
  masked[1] = 0x80 | payload.length;
  masked.set(mask, 2);
  payload.forEach((b, i) => {
    masked[6 + i] = b ^ mask[i % 4];
  });
  assert.throws(() => new WsDecoder().push(masked));
});

test("encodeClientFrame is masked and decodable by server semantics", () => {
  const payload = new TextEncoder().encode("mask check");
  const frame = encodeClientFrame(OPCODE_TEXT, payload);
  assert.equal(frame[0], 0x81);
  assert.equal(frame[1] & 0x80, 0x80, "客户端帧必须带掩码位");
  const mask = frame.slice(2, 6);
  const masked = frame.slice(6, 6 + payload.length);
  const unmasked = new Uint8Array(payload.length);
  masked.forEach((b, i) => {
    unmasked[i] = b ^ mask[i % 4];
  });
  assert.deepEqual(unmasked, payload);
});

test("ReplayWindow semantics match Kotlin/Rust", () => {
  const window = new ReplayWindow(64);
  assert.equal(window.checkAndAccept(1n), true);
  assert.equal(window.checkAndAccept(2n), true);
  assert.equal(window.checkAndAccept(1n), false);
  assert.equal(window.checkAndAccept(0n), false);
  const sparse = new ReplayWindow(8);
  sparse.checkAndAccept(1n);
  sparse.checkAndAccept(4n);
  assert.equal(sparse.checkAndAccept(3n), true);
  assert.equal(sparse.checkAndAccept(3n), false);
});

test("envelope encode/decode roundtrip and classification", () => {
  const envelope = {
    protocolVersion: 1,
    requestId: "c-1",
    sessionId: "ab".repeat(16),
    sequence: 3n,
    type: "key_event",
    payload: { key: "DPAD_DOWN", state: "DOWN", repeatCount: 0 },
  };
  const bytes = encodeEnvelope(envelope);
  const decoded = decodeEnvelope(bytes);
  assert.deepEqual(decoded, envelope);
  const event = classifyEnvelope(decoded);
  assert.equal(event.kind, "other");
});

test("classifyFrame routes frame-level ping and encrypted ping separately", () => {
  const decoder = new WsDecoder();
  const routed = classifyFrame(decoder.push(new Uint8Array([0x89, 0x04, ...Buffer.from("tvrc")]))!);
  assert.equal(routed.kind, "framePing");
  const ackFrame = decodeEnvelope(
    encodeEnvelope({
      protocolVersion: 1,
      requestId: "c-9",
      sessionId: "",
      sequence: 1n,
      type: "command_ack",
      payload: { commandSequence: 1, status: "SUCCESS" },
    }),
  );
  assert.equal(classifyEnvelope(ackFrame).kind, "ack");
});
