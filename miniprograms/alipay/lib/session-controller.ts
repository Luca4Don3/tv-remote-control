/**
 * 调试会话管理：连接 + 握手 + 心跳 + 命令发送（带 UI 状态回调）。
 * 断开自动关闭会话与底层 socket；按键统一 state="PRESS"（对齐 Kotlin 主 App UI）。
 */
import { DebugWsSession, type CommandAck } from "@tvrc/miniprogram-core";
import { AlipaySocketTransport } from "./socket-transport";
import { hexToBytes, type DebugCredential } from "./credentials";

export type ConnState =
  | { kind: "idle" }
  | { kind: "connecting" }
  | { kind: "connected" }
  | { kind: "error"; message: string };

export class DebugSessionController {
  private session: DebugWsSession | null = null;
  private transport: AlipaySocketTransport | null = null;
  private stateCbs: ((state: ConnState) => void)[] = [];

  onState(cb: (state: ConnState) => void): void {
    this.stateCbs.push(cb);
  }

  private emit(state: ConnState): void {
    for (const cb of this.stateCbs) cb(state);
  }

  async connect(credential: DebugCredential): Promise<void> {
    this.disconnect();
    this.emit({ kind: "connecting" });
    // hello（明文握手 + 密钥派生）成功即视为通路可用
    try {
      const transport = await AlipaySocketTransport.connect(
        `ws://${credential.host}:${credential.port}`,
      );
      const session = new DebugWsSession(
        transport,
        credential.controllerId,
        hexToBytes(credential.pskHex),
      );
      await session.hello();
      this.session = session;
      this.transport = transport;
      this.emit({ kind: "connected" });
    } catch (error) {
      this.transport?.close();
      this.transport = null;
      this.emit({ kind: "error", message: `连接失败：${(error as Error).message}` });
    }
  }

  private async requireSession(): Promise<DebugWsSession> {
    const session = this.session;
    if (!session) throw new Error("未连接");
    return session;
  }

  async pressKey(key: string): Promise<CommandAck> {
    return (await this.requireSession()).sendKeyEvent(key, "PRESS");
  }

  async sendText(text: string, draft: boolean): Promise<CommandAck> {
    return (await this.requireSession()).sendText(text, draft);
  }

  disconnect(): void {
    this.session?.close();
    this.transport?.close();
    this.session = null;
    this.transport = null;
    this.emit({ kind: "idle" });
  }

  get isConnected(): boolean {
    return this.session !== null;
  }
}
