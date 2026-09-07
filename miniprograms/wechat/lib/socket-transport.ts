/**
 * wx.SocketTask → WsTransport 适配。
 * 帧级 ping/pong 由微信底层 WS 栈自动处理，适配层只关心数据帧与关闭/错误。
 */
import type { WsTransport } from "@tvrc/miniprogram-core";

export class WxSocketTransport implements WsTransport {
  private readonly binaryCbs: ((data: Uint8Array) => void)[] = [];
  private readonly textCbs: ((data: string) => void)[] = [];
  private readonly closeCbs: (() => void)[] = [];
  private readonly errorCbs: ((message: string) => void)[] = [];
  private closed = false;

  constructor(private readonly task: WechatMiniprogram.SocketTask) {
    task.onMessage((res) => {
      if (typeof res.data === "string") {
        for (const cb of this.textCbs) cb(res.data);
      } else {
        const bytes = new Uint8Array(res.data as ArrayBuffer);
        for (const cb of this.binaryCbs) cb(bytes);
      }
    });
    task.onClose(() => {
      this.closed = true;
      for (const cb of this.closeCbs) cb();
    });
    task.onError((err) => {
      for (const cb of this.errorCbs) cb(err.errMsg ?? "socket error");
    });
  }

  sendBinary(data: Uint8Array): void {
    if (this.closed) throw new Error("socket closed");
    this.task.send({ data: toArrayBuffer(data) });
  }

  sendText(data: string): void {
    if (this.closed) throw new Error("socket closed");
    this.task.send({ data });
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
    if (this.closed) return;
    this.task.close({ code: 1000, reason: "client close" });
  }
}

function toArrayBuffer(bytes: Uint8Array): ArrayBuffer {
  return bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer;
}
