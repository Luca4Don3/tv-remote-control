import { test } from "node:test";
import assert from "node:assert/strict";
import {
  DebugWsSession,
  SessionClosedError,
  type WsTransport,
} from "../src/session.js";
import {
  DirectionCipher,
  deriveSessionKeys,
  randomBytes,
} from "../src/crypto.js";
import { ReplayWindow } from "../src/replay.js";
import { decodeEnvelope, encodeEnvelope, type Envelope } from "../src/envelope.js";
import { readCounterBigEndian } from "../src/frame.js";

/** 模拟 agent 服务端（WsDebugChannel.debugHandshake + 命令循环的最小行为复刻）。 */
class FakeAgent {
  readonly serverCipher: DirectionCipher | null = null;
  private clientCipher: DirectionCipher | null = null;
  private replay = new ReplayWindow(64);
  private outCipher: DirectionCipher | null = null;
  private serverCounter = 0n;
  readonly received: Envelope[] = [];
  constructor(
    private readonly psk: Uint8Array,
    private readonly sendToClient: (data: Uint8Array | string) => void,
  ) {}

  onFromClient(data: Uint8Array | string): void {
    if (typeof data === "string") {
      this.onHello(data);
      return;
    }
    const counter = readCounterBigEndian(data.slice(0, 8));
    if (!this.replay.checkAndAccept(counter)) {
      throw new Error(`agent rejected replayed counter ${counter}`);
    }
    const plaintext = this.clientCipher!.open(data, counter);
    const envelope = decodeEnvelope(plaintext);
    this.received.push(envelope);
    if (envelope.type === "ping") return; // fire-and-forget，不回 ack
    if (envelope.type === "command_ack_test") return;
    const ack: Envelope = {
      protocolVersion: 1,
      requestId: envelope.requestId,
      sessionId: envelope.sessionId,
      sequence: envelope.sequence,
      type: "command_ack",
      payload: {
        commandSequence: Number(envelope.sequence),
        status: envelope.type === "reject_me" ? "REJECTED" : "SUCCESS",
        reason: envelope.type === "reject_me" ? "test rejection" : null,
      },
    };
    const cipher = this.outCipher!;
    this.sendToClient(cipher.seal(encodeEnvelope(ack)).envelope);
  }

  private onHello(text: string): void {
    const hello = decodeEnvelope(new TextEncoder().encode(text));
    assert.equal(hello.type, "ws_hello");
    assert.equal(hello.sessionId, "");
    const clientRandom = new Uint8Array(
      (hello.payload.clientRandom as string).match(/.{2}/g)!.map((h) => parseInt(h, 16)),
    );
    const serverRandom = randomBytes(32);
    const keys = deriveSessionKeys(this.psk, clientRandom, serverRandom);
    this.clientCipher = new DirectionCipher(keys.clientToServer);
    this.outCipher = new DirectionCipher(keys.serverToClient);
    const ack: Envelope = {
      protocolVersion: 1,
      requestId: hello.requestId,
      sessionId: hello.payload.controllerId as string,
      sequence: hello.sequence + 1n,
      type: "ws_hello_ack",
      payload: {
        serverRandom: Array.from(serverRandom, (b) => b.toString(16).padStart(2, "0")).join(""),
        authenticated: true,
      },
    };
    this.sendToClient(new TextDecoder().decode(encodeEnvelope(ack)));
  }
}

/** 内存双向管道 transport（客户端视角）。 */
class PipeTransport implements WsTransport {
  private binaryCbs: ((data: Uint8Array) => void)[] = [];
  private textCbs: ((data: string) => void)[] = [];
  private closeCbs: (() => void)[] = [];
  private errorCbs: ((m: string) => void)[] = [];
  closed = false;

  constructor(private readonly agent: FakeAgent) {}

  sendBinary(data: Uint8Array): void {
    if (this.closed) throw new Error("transport closed");
    this.agent.onFromClient(data);
  }
  sendText(data: string): void {
    if (this.closed) throw new Error("transport closed");
    this.agent.onFromClient(data);
  }
  onBinary(cb: (data: Uint8Array) => void): void {
    this.binaryCbs.push(cb);
  }
  onText(cb: (data: string) => void): void {
    this.textCbs.push(cb);
  }
  onClose(cb: () => void): void {
    this.closeCbs.push(cb);
  }
  onError(cb: (message: string) => void): void {
    this.errorCbs.push(cb);
  }
  close(): void {
    this.closed = true;
    for (const cb of this.closeCbs) cb();
  }

  deliverFromAgent(data: Uint8Array | string): void {
    if (typeof data === "string") for (const cb of this.textCbs) cb(data);
    else for (const cb of this.binaryCbs) cb(data);
  }
  emitError(message: string): void {
    for (const cb of this.errorCbs) cb(message);
  }
  emitClose(): void {
    for (const cb of this.closeCbs) cb();
  }
}

async function setup(): Promise<{
  session: DebugWsSession;
  transport: PipeTransport;
  agent: FakeAgent;
  psk: Uint8Array;
}> {
  const psk = randomBytes(32);
  let agentRef: FakeAgent | null = null;
  let transportRef: PipeTransport | null = null;
  const agent = new FakeAgent(psk, (data) => transportRef!.deliverFromAgent(data));
  agentRef = agent;
  const transport = new PipeTransport(agent);
  transportRef = transport;
  const session = new DebugWsSession(transport, "ab".repeat(16), psk);
  await session.hello();
  return { session, transport, agent: agentRef, psk };
}

test("hello handshake derives keys and enables encrypted commands", async (t) => {
  const { session, agent } = await setup();
  t.after(() => session.close());
  const ack = await session.sendCommand("key_event", { key: "DPAD_UP", state: "DOWN", repeatCount: 0 });
  assert.equal(ack.status, "SUCCESS");
  assert.equal(ack.commandSequence, 1n);
  assert.equal(agent.received.length, 1);
  assert.equal(agent.received[0]!.type, "key_event");
  assert.equal(agent.received[0]!.sessionId, "ab".repeat(16));
});

test("command sequence increments strictly across commands", async (t) => {
  const { session, agent } = await setup();
  t.after(() => session.close());
  await session.sendCommand("key_event", { key: "A", state: "DOWN", repeatCount: 0 });
  await session.sendCommand("key_event", { key: "B", state: "UP", repeatCount: 0 });
  const seqs = agent.received.map((e) => e.sequence);
  assert.deepEqual(seqs, [1n, 2n]);
});

test("heartbeat ping is sent encrypted and gets no ack", async (t) => {
  const { session, agent } = await setup();
  t.after(() => session.close());
  // 直接触发一次心跳（对齐 15s fire-and-forget 语义）
  (session as unknown as { sendHeartbeat(): void }).sendHeartbeat();
  const pings = agent.received.filter((e) => e.type === "ping");
  assert.equal(pings.length, 1);
  assert.equal(pings[0]!.sessionId, "ab".repeat(16));
  assert.equal(pings[0]!.sequence, 0n);
});

test("replayed inbound cipher frame is rejected and closes session", async (t) => {
  const { session, transport } = await setup();
  t.after(() => session.close());
  const original = transport.deliverFromAgent.bind(transport);
  let lastBinary: Uint8Array | null = null;
  transport.deliverFromAgent = (d) => {
    if (d instanceof Uint8Array) lastBinary = d;
    original(d);
  };
  const ack = await session.sendCommand("key_event", { key: "A", state: "DOWN", repeatCount: 0 });
  assert.equal(ack.status, "SUCCESS");
  // 原样重发同一加密帧：counter 已见 → 客户端防重放拒收并断连
  original(lastBinary!);
  assert.equal(session.isClosed, true);
  assert.equal(transport.closed, true);
});

test("undecryptable inbound frame closes session", async (t) => {
  const { session, transport } = await setup();
  t.after(() => session.close());
  transport.deliverFromAgent(new Uint8Array(24));
  assert.equal(session.isClosed, true);
  assert.equal(transport.closed, true);
});

test("transport close fails pending commands", async (t) => {
  const { session, transport } = await setup();
  t.after(() => session.close());
  transport.deliverFromAgent = () => {};
  const promise = session.sendCommand("key_event", { key: "A", state: "DOWN", repeatCount: 0 });
  transport.emitClose();
  await assert.rejects(promise, SessionClosedError);
});

test("transport error fails pending commands", async (t) => {
  const { session, transport } = await setup();
  t.after(() => session.close());
  transport.deliverFromAgent = () => {};
  const promise = session.sendCommand("key_event", { key: "A", state: "DOWN", repeatCount: 0 });
  transport.emitError("boom");
  await assert.rejects(promise, /transport error/);
});

test("command ack timeout surfaces after deadline", async (t) => {
  const psk = randomBytes(32);
  let transportRef!: PipeTransport;
  const agent = new FakeAgent(psk, (data) => transportRef.deliverFromAgent(data));
  transportRef = new PipeTransport(agent);
  const transport = transportRef;
  const session = new DebugWsSession(transport, "ab".repeat(16), psk);
  t.after(() => session.close());
  await session.hello();
  // 吞掉 agent 回包（模拟丢包）：临时替换 deliver
  transport.deliverFromAgent = () => {};
  await assert.rejects(
    session.sendCommand("key_event", { key: "A", state: "DOWN", repeatCount: 0 }, 50),
    /ack timeout/,
  );
});
