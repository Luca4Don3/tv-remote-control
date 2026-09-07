/**
 * 调试通道消息信封与事件分类（与 Kotlin `ProtocolCodec`/agent 服务端逐字段对齐）：
 * - JSON：protocolVersion=1 / requestId（[A-Za-z0-9._:-]{1,128}）/ sessionId
 *   （空或标识符）/ sequence（严格递增） / type（[a-z][a-z0-9_]{0,63}）/ payload
 * - 加密信封计数（DirectionCipher counter，防重放）与命令序号（sequence，
 *   agent 端 KeyStateTracker/TextCommandDispatcher 严格递增校验）是两个独立计数
 * - client 的 ping 无应答（fire-and-forget）；key_event/text_* 等待 command_ack
 */

import type { IncomingFrame } from "./frame.js";
import { utf8Encode, utf8Decode } from "./text.js";
import { OPCODE_BINARY, OPCODE_PING, OPCODE_PONG, OPCODE_TEXT } from "./frame.js";

export const PROTOCOL_VERSION = 1;

export interface Envelope {
  protocolVersion: number;
  requestId: string;
  sessionId: string;
  sequence: bigint;
  type: string;
  payload: Record<string, unknown>;
}

const IDENTIFIER = /^[A-Za-z0-9._:-]{1,128}$/;
const TYPE = /^[a-z][a-z0-9_]{0,63}$/;

function assertValid(envelope: Envelope): void {
  if (envelope.protocolVersion !== PROTOCOL_VERSION) throw new Error("unsupported protocolVersion");
  if (!IDENTIFIER.test(envelope.requestId)) throw new Error("invalid requestId");
  if (envelope.sessionId !== "" && !IDENTIFIER.test(envelope.sessionId)) throw new Error("invalid sessionId");
  // sequence=0 仅心跳信封使用（对齐 Kotlin：fire-and-forget 探活无命令语义）；
  // 命令层（DebugWsSession）自行保证严格递增且从 1 起
  if (envelope.sequence < 0n) throw new Error("sequence must be >= 0");
  if (!TYPE.test(envelope.type)) throw new Error("invalid type");
}

export function encodeEnvelope(envelope: Envelope): Uint8Array {
  assertValid(envelope);
  const text = JSON.stringify({
    protocolVersion: envelope.protocolVersion,
    requestId: envelope.requestId,
    sessionId: envelope.sessionId,
    sequence: Number(envelope.sequence),
    type: envelope.type,
    payload: envelope.payload,
  });
  return utf8Encode(text);
}

/** 解析服务端信封。 */
export function decodeEnvelope(bytes: Uint8Array): Envelope {
  const raw = JSON.parse(utf8Decode(bytes)) as Record<string, unknown>;
  const envelope: Envelope = {
    protocolVersion: Number(raw.protocolVersion),
    requestId: String(raw.requestId),
    sessionId: String(raw.sessionId ?? ""),
    sequence: BigInt(raw.sequence as number),
    type: String(raw.type),
    payload: (raw.payload ?? {}) as Record<string, unknown>,
  };
  assertValid(envelope);
  return envelope;
}

/** WS 帧载荷 → 类型化事件（调用方据此应答/等待）。 */
export type ChannelEvent =
  | { kind: "framePing"; payload: Uint8Array }
  | { kind: "encryptedPing" }
  | { kind: "encryptedPong" }
  | { kind: "ack"; requestId: string; commandSequence: bigint; status: string; reason: string | null }
  | { kind: "other"; envelope: Envelope };

export function classifyEnvelope(envelope: Envelope): ChannelEvent {
  switch (envelope.type) {
    case "ping":
      return { kind: "encryptedPing" };
    case "pong":
      return { kind: "encryptedPong" };
    case "command_ack":
      return {
        kind: "ack",
        requestId: envelope.requestId,
        commandSequence: BigInt(envelope.payload.commandSequence as number),
        status: String(envelope.payload.status ?? ""),
        reason: (envelope.payload.reason as string) ?? null,
      };
    default:
      return { kind: "other", envelope };
  }
}

/** 帧载荷分类：帧级 ping 与加密信封消息分流入口。 */
export function classifyFrame(frame: IncomingFrame): ChannelEvent | { kind: "framePing"; payload: Uint8Array } {
  if (frame.opcode === OPCODE_PING) return { kind: "framePing", payload: frame.payload };
  if (frame.opcode === OPCODE_PONG) return { kind: "encryptedPong" };
  if (frame.opcode !== OPCODE_BINARY && frame.opcode !== OPCODE_TEXT) {
    return { kind: "other", envelope: { protocolVersion: PROTOCOL_VERSION, requestId: "", sessionId: "", sequence: 0n, type: "", payload: {} } };
  }
  return classifyEnvelope(decodeEnvelope(frame.payload));
}
