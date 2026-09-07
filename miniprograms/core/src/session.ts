/**
 * 调试通道会话状态机（传输无关：WS 数据帧的收发由注入的 WsTransport 承担）。
 * 与 Kotlin `WsDebugClient` 逐字段对齐：
 * - 明文 TEXT 帧 ws_hello（sessionId 空、sequence=1）→ ws_hello_ack（serverRandom 64-hex）
 * - 双向密钥派生；命令用 BINARY 加密帧，sessionId=controllerId，命令序号严格递增
 * - ack 按 requestId 路由；服务端加密 ping（type=ping）静默忽略
 * - 心跳 15s fire-and-forget（agent 对 client 的 ping 不回 ack）
 * - 帧级 ping/pong 由底层 WS 栈（wx/浏览器）自动处理，会话层不感知
 */

import { DirectionCipher, deriveSessionKeys } from "./crypto.js";
import { randomBytes } from "./random.js";
import { readCounterBigEndian } from "./frame.js";
import { utf8Encode, utf8Decode } from "./text.js";
import { ReplayWindow } from "./replay.js";
import {
  decodeEnvelope,
  encodeEnvelope,
  PROTOCOL_VERSION,
  type Envelope,
} from "./envelope.js";

/** 底层 WS 适配（wx SocketTask / 支付宝 my.connectSocket / 测试用内存管道）。 */
export interface WsTransport {
  sendBinary(data: Uint8Array): void;
  sendText(data: string): void;
  onBinary(cb: (data: Uint8Array) => void): void;
  onText(cb: (data: string) => void): void;
  onClose(cb: () => void): void;
  onError(cb: (message: string) => void): void;
  close(): void;
}

export interface CommandAck {
  requestId: string;
  commandSequence: bigint;
  status: string;
  reason: string | null;
}

export class SessionClosedError extends Error {
  constructor(message = "session closed") {
    super(message);
  }
}

const HEARTBEAT_INTERVAL_MS = 15_000;
const ACK_TIMEOUT_MS = 45_000;

export class DebugWsSession {
  private clientCipher: DirectionCipher | null = null;
  private serverCipher: DirectionCipher | null = null;
  private outboundSequence = 0n;
  private readonly replay = new ReplayWindow(64);
  private readonly pendingAcks = new Map<string, (ack: CommandAck) => void>();
  private readonly pendingErrors = new Map<string, (err: Error) => void>();
  private readonly pendingTimers = new Map<string, ReturnType<typeof setTimeout>>();
  private heartbeatTimer: ReturnType<typeof setInterval> | null = null;
  private closed = false;
  private requestCounter = 0n;

  constructor(
    private readonly transport: WsTransport,
    private readonly controllerId: string,
    private readonly secret: Uint8Array,
  ) {
    if (!/^[0-9a-f]{32}$/.test(controllerId)) {
      throw new Error("controllerId must be 32 hex chars");
    }
    transport.onBinary((data) => this.onCipherFrame(data));
    transport.onText((data) => this.onTextFrame(data));
    transport.onClose(() => this.failPending(new SessionClosedError()));
    transport.onError((message) => this.failPending(new Error(`transport error: ${message}`)));
  }

  /** 明文握手 → ws_hello_ack → 派生双向密钥（失败/超时抛错）。 */
  async hello(): Promise<void> {
    const clientRandom = randomBytes(32);
    const requestId = this.nextRequestId();
    const hello: Envelope = {
      protocolVersion: PROTOCOL_VERSION,
      requestId,
      sessionId: "",
      sequence: 1n,
      type: "ws_hello",
      payload: {
        controllerId: this.controllerId,
        clientRandom: toHex(clientRandom),
      },
    };
    const reply = await this.expectAck(requestId, () => this.transport.sendText(
      utf8Decode(encodeEnvelope(hello)),
    ));
    if (reply.status !== "ws_hello_ack") throw new Error(`unexpected handshake reply: ${reply.status}`);
    const serverRandom = fromHex(reply.reason ?? "");
    const keys = deriveSessionKeys(this.secret, clientRandom, serverRandom);
    this.clientCipher = new DirectionCipher(keys.clientToServer);
    this.serverCipher = new DirectionCipher(keys.serverToClient);
    this.heartbeatTimer = setInterval(() => this.sendHeartbeat(), HEARTBEAT_INTERVAL_MS);
  }

  /** 发送命令并等待 command_ack（严格递增序号；默认 45s 超时对齐 Kotlin READ_TIMEOUT）。 */
  async sendCommand(
    type: string,
    payload: Record<string, unknown>,
    timeoutMs = ACK_TIMEOUT_MS,
  ): Promise<CommandAck> {
    const cipher = this.requireClientCipher();
    const requestId = this.nextRequestId();
    const envelope: Envelope = {
      protocolVersion: PROTOCOL_VERSION,
      requestId,
      sessionId: this.controllerId,
      sequence: ++this.outboundSequence,
      type,
      payload,
    };
    return this.expectAck(requestId, () => {
      this.transport.sendBinary(cipher.seal(encodeEnvelope(envelope)).envelope);
    }, timeoutMs);
  }

  sendKeyEvent(key: string, state: string, repeatCount = 0): Promise<CommandAck> {
    return this.sendCommand("key_event", { key, state, repeatCount });
  }

  sendText(text: string, draft = false): Promise<CommandAck> {
    return this.sendCommand(draft ? "text_draft" : "text_commit", { text });
  }

  /** 15s 加密保活 ping（fire-and-forget：agent 不回 ack）。 */
  private sendHeartbeat(): void {
    const cipher = this.clientCipher ?? null;
    if (!cipher || this.closed) return;
    try {
      const ping: Envelope = {
        protocolVersion: PROTOCOL_VERSION,
        requestId: this.nextRequestId(),
        sessionId: this.controllerId,
        sequence: 0n,
        type: "ping",
        payload: {},
      };
      this.transport.sendBinary(cipher.seal(encodeEnvelope(ping)).envelope);
    } catch {
      // 心跳写失败交由下一次命令的失败/超时暴露
    }
  }

  private requireClientCipher(): DirectionCipher {
    if (!this.clientCipher) throw new Error("not ready: call hello() first");
    return this.clientCipher;
  }

  /** 解密入向 BINARY 帧：ack 路由到等待方；服务端 ping 静默忽略。 */
  private onCipherFrame(payload: Uint8Array): void {
    const server = this.serverCipher;
    if (!server) return;
    try {
      const counter = readCounterBigEndian(payload.slice(0, 8));
      // 客户端侧防重放（对齐 Kotlin WsDebugClient 读循环）：重复/过旧 counter 拒收并断连
      if (!this.replay.checkAndAccept(counter)) {
        this.failPending(new Error("replayed debug message"));
        this.transport.close();
        return;
      }
      const plaintext = server.open(payload, counter);
      const reply = decodeEnvelope(plaintext);
      if (reply.type === "command_ack") {
        this.resolveAck(reply.requestId, {
          requestId: reply.requestId,
          commandSequence: BigInt(reply.payload.commandSequence as number),
          status: String(reply.payload.status ?? ""),
          reason: (reply.payload.reason as string) ?? null,
        });
      }
      // type=ping：agent 的加密探活，无应答语义——忽略
    } catch {
      // 解密失败（伪造/乱序破坏）按协议应断连：终结会话并通知底层关闭
      this.failPending(new Error("failed to decrypt inbound frame"));
      this.transport.close();
    }
  }

  /** 明文 TEXT 帧：仅握手 ack 使用；requestId 不匹配则忽略。 */
  private onTextFrame(data: string): void {
    try {
      const reply = decodeEnvelope(utf8Encode(data));
      if (reply.type === "ws_hello_ack") {
        this.resolveAck(reply.requestId, {
          requestId: reply.requestId,
          commandSequence: reply.sequence,
          status: "ws_hello_ack",
          reason: String(reply.payload.serverRandom ?? ""),
        });
      }
    } catch {
      // 非握手期的明文帧按无效处理
    }
  }

  private nextRequestId(): string {
    return `c-${Date.now().toString(36)}-${(++this.requestCounter).toString(36)}`;
  }

  private expectAck(
    requestId: string,
    write: () => void,
    timeoutMs = ACK_TIMEOUT_MS,
  ): Promise<CommandAck> {
    return new Promise<CommandAck>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pendingTimers.delete(requestId);
        this.pendingAcks.delete(requestId);
        this.pendingErrors.delete(requestId);
        reject(new Error(`ack timeout: ${requestId}`));
      }, timeoutMs);
      this.pendingTimers.set(requestId, timer);
      this.pendingAcks.set(requestId, (ack) => {
        clearTimeout(timer);
        resolve(ack);
      });
      this.pendingErrors.set(requestId, (err) => {
        clearTimeout(timer);
        reject(err);
      });
      write();
    });
  }

  private resolveAck(requestId: string, ack: CommandAck): void {
    const resolve = this.pendingAcks.get(requestId);
    const reject = this.pendingErrors.get(requestId);
    if (resolve && reject) {
      const timer = this.pendingTimers.get(requestId);
      if (timer) clearTimeout(timer);
      this.pendingTimers.delete(requestId);
      this.pendingAcks.delete(requestId);
      this.pendingErrors.delete(requestId);
      resolve(ack);
    }
  }

  private failPending(err: Error): void {
    this.closed = true;
    if (this.heartbeatTimer) clearInterval(this.heartbeatTimer);
    for (const timer of this.pendingTimers.values()) clearTimeout(timer);
    for (const reject of this.pendingErrors.values()) reject(err);
    this.pendingTimers.clear();
    this.pendingAcks.clear();
    this.pendingErrors.clear();
  }

  close(): void {
    this.closed = true;
    if (this.heartbeatTimer) clearInterval(this.heartbeatTimer);
    for (const timer of this.pendingTimers.values()) clearTimeout(timer);
    this.pendingTimers.clear();
    this.pendingAcks.clear();
    this.pendingErrors.clear();
  }

  get isClosed(): boolean {
    return this.closed;
  }
}

function toHex(bytes: Uint8Array): string {
  return Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
}

function fromHex(hex: string): Uint8Array {
  if (hex.length % 2 !== 0 || !/^[0-9a-f]*$/.test(hex)) throw new Error("invalid hex");
  return new Uint8Array(hex.match(/.{2}/g)!.map((h) => parseInt(h, 16)));
}
