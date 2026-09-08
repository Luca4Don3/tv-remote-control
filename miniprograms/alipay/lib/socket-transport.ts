/**
 * 支付宝 SocketTask → WsTransport 适配。
 * 帧级 ping/pong 由底层 WS 栈自动处理，适配层只关心数据帧与关闭/错误。
 * my.connectSocket 以 multiple 模式同步返回 SocketTask。
 */
import type { WsTransport } from "@tvrc/miniprogram-core";

export class AlipaySocketTransport implements WsTransport {
  private readonly binaryCbs: ((data: Uint8Array) => void)[] = [];
  private readonly textCbs: ((data: string) => void)[] = [];
  private readonly closeCbs: (() => void)[] = [];
  private readonly errorCbs: ((message: string) => void)[] = [];
  private closed = false;

  private constructor(private readonly task: my.SocketTask) {
    task.onMessage((res) => {
      const data = res.data as unknown as string | ArrayBuffer;
      if (typeof data === "string") {
        for (const cb of this.textCbs) cb(data);
      } else {
        const bytes = new Uint8Array(data);
        for (const cb of this.binaryCbs) cb(bytes);
      }
    });
    task.onClose(() => {
      this.closed = true;
      for (const cb of this.closeCbs) cb();
    });
    task.onError((res) => {
      for (const cb of this.errorCbs) cb(`socket error ${res.error ?? ""}`);
    });
  }

  static connect(url: string): Promise<AlipaySocketTransport> {
    return new Promise((resolve) => {
      const task = my.connectSocket({ url, multiple: true });
      resolve(new AlipaySocketTransport(task));
    });
  }

  sendBinary(data: Uint8Array): void {
    if (this.closed) throw new Error("socket closed");
    this.task.send({ data: toBuffer(data) as unknown as string });
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
    this.task.close({ code: 1000 });
  }
}

function toBuffer(bytes: Uint8Array): ArrayBuffer {
  return bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer;
}
