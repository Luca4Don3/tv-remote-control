/**
 * RFC 6455 WebSocket 帧编解码（客户端视角）。
 *
 * - 出向（客户端→服务端）：强制掩码（4B 随机掩码键 + XOR 载荷）
 * - 入向（服务端→客户端）：不掩码；掩码帧必须拒绝（与 Rust WsDecoder::client /
 *   Kotlin WsFrameCodec.read(expectMasked=false) 语义一致——server frames must not be masked）
 * - 流式：喂入任意分段字节，产出完整消息（与 Rust WsDecoder/Kotlin readFully 缓冲语义一致）
 */

export const OPCODE_CONTINUATION = 0x0;
export const OPCODE_TEXT = 0x1;
export const OPCODE_BINARY = 0x2;
export const OPCODE_CLOSE = 0x8;
export const OPCODE_PING = 0x9;
export const OPCODE_PONG = 0xa;
export const MAX_WS_PAYLOAD = 64 * 1024;

export class WsProtocolError extends Error {}

export interface IncomingFrame {
  opcode: number;
  payload: Uint8Array;
}

function randomMaskKey(): Uint8Array {
  const key = new Uint8Array(4);
  crypto.getRandomValues(key);
  return key;
}

/** 编码客户端出向帧（RFC 6455 强制掩码；单帧 FIN）。 */
export function encodeClientFrame(opcode: number, payload: Uint8Array): Uint8Array {
  if (payload.length > MAX_WS_PAYLOAD) throw new WsProtocolError("payload exceeds 64 KiB");
  const mask = randomMaskKey();
  const header = new Uint8Array(2);
  header[0] = 0x80 | opcode;
  if (payload.length < 126) {
    header[1] = 0x80 | payload.length;
  } else if (payload.length <= 0xffff) {
    header[1] = 0x80 | 126;
  } else {
    header[1] = 0x80 | 127;
  }
  const lengthBytes =
    payload.length < 126
      ? new Uint8Array(0)
      : payload.length <= 0xffff
        ? new Uint8Array([(payload.length >>> 8) & 0xff, payload.length & 0xff])
        : new Uint8Array(8).map((_, i) => (payload.length >>> ((7 - i) * 8)) & 0xff);
  const out = new Uint8Array(header.length + lengthBytes.length + 4 + payload.length);
  let offset = 0;
  out.set(header, offset);
  offset += header.length;
  out.set(lengthBytes, offset);
  offset += lengthBytes.length;
  out.set(mask, offset);
  offset += 4;
  for (let i = 0; i < payload.length; i += 1) {
    out[offset + i] = payload[i] ^ mask[i % 4];
  }
  return out;
}

/** 流式解码器：喂入 TCP 分段，产出完整入向消息（客户端视角：入向帧不得掩码）。 */
export class WsDecoder {
  private buf = new Uint8Array(0);
  private fragments: number[] = [];
  private fragmentOpcode: number | null = null;

  /** 喂数据；返回完整消息（一次最多一条），数据不足返回 null。 */
  push(chunk: Uint8Array): IncomingFrame | null {
    const merged = new Uint8Array(this.buf.length + chunk.length);
    merged.set(this.buf, 0);
    merged.set(chunk, this.buf.length);
    this.buf = merged;
    return this.tryDecode();
  }

  private readN(n: number): Uint8Array | null {
    if (this.buf.length < n) return null;
    const out = this.buf.slice(0, n);
    this.buf = this.buf.slice(n);
    return out;
  }

  private tryDecode(): IncomingFrame | null {
    const header = this.readN(2);
    if (!header) return null;
    const fin = (header[0] & 0x80) !== 0;
    if ((header[0] & 0x70) !== 0) throw new WsProtocolError("reserved bits set");
    const opcode = header[0] & 0x0f;
    const masked = (header[1] & 0x80) !== 0;
    if (masked) throw new WsProtocolError("server frames must not be masked");
    let length = header[1] & 0x7f;
    if (length === 126) {
      const ext = this.readN(2);
      if (!ext) return null;
      length = (ext[0] << 8) | ext[1];
    } else if (length === 127) {
      const ext = this.readN(8);
      if (!ext) return null;
      let value = 0n;
      for (const byte of ext) value = (value << 8n) | BigInt(byte);
      if (value > BigInt(MAX_WS_PAYLOAD)) throw new WsProtocolError("payload exceeds 64 KiB");
      length = Number(value);
    }
    if (length > MAX_WS_PAYLOAD) throw new WsProtocolError("payload exceeds 64 KiB");
    // 仅掩码帧携带掩码键；未掩码帧（服务端出向）没有
    const payload = this.readN(length);
    if (!payload) return null;

    if (opcode === OPCODE_CONTINUATION) {
      if (this.fragmentOpcode === null) throw new WsProtocolError("continuation without start");
      this.fragments.push(...payload);
      if (this.fragments.length > MAX_WS_PAYLOAD) throw new WsProtocolError("payload exceeds 64 KiB");
      if (fin) {
        const data = new Uint8Array(this.fragments);
        const first = this.fragmentOpcode;
        this.fragments = [];
        this.fragmentOpcode = null;
        return { opcode: first, payload: data };
      }
      return null;
    }
    if (opcode === OPCODE_TEXT || opcode === OPCODE_BINARY) {
      if (fin) return { opcode, payload };
      if (this.fragmentOpcode !== null) throw new WsProtocolError("new fragment started mid-message");
      this.fragmentOpcode = opcode;
      this.fragments.push(...payload);
      return null;
    }
    if (opcode === OPCODE_PING) return { opcode: OPCODE_PING, payload };
    if (opcode === OPCODE_PONG) return { opcode: OPCODE_PONG, payload };
    if (opcode === OPCODE_CLOSE) throw new WsProtocolError("connection closed by peer");
    throw new WsProtocolError(`unknown opcode ${opcode}`);
  }
}

/** 大端 8B 计数器读写（加密信封前缀与 nonce 共用）。 */
export function writeCounterBigEndian(counter: bigint | number, length = 8): Uint8Array {
  const out = new Uint8Array(length);
  let value = BigInt(counter);
  for (let i = length - 1; i >= 0; i -= 1) {
    out[i] = Number(value & 0xffn);
    value >>= 8n;
  }
  return out;
}

export function readCounterBigEndian(bytes: Uint8Array): bigint {
  let value = 0n;
  for (const byte of bytes) value = (value << 8n) | BigInt(byte);
  return value;
}
