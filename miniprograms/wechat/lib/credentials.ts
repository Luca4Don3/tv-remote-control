/** 调试凭据本地存储（wx storage；PSK 为 64-hex，来源 P3 的 Android 导出界面或手动录入）。 */
import { setRandomSource } from "@tvrc/miniprogram-core";

/**
 * 注入随机源：旧基础库无全局 crypto，用 wx.getRandomValues 维护 256B 预取池
 * （clientRandom/掩码键等同步消费）。池耗尽时抛错（连接失败可见，不静默降级）。
 */
let pool = new Uint8Array(0);

function refill(length: number): Uint8Array | null {
  if (pool.length < length) return null;
  const out = pool.slice(0, length);
  pool = pool.slice(length);
  return out;
}

export function initRandomPool(): Promise<void> {
  return new Promise((resolve, reject) => {
    wx.getRandomValues({
      length: 256,
      success(res) {
        pool = new Uint8Array(res.randomValues as ArrayBuffer);
        setRandomSource(refill);
        resolve();
      },
      fail(err) {
        reject(new Error(`wx.getRandomValues 失败：${err.errMsg}`));
      },
    });
  });
}

export interface DebugCredential {
  host: string;
  port: number;
  controllerId: string;
  pskHex: string;
  name: string;
}

const KEY = "tvrc.debug.credential";

export function loadCredential(): DebugCredential | null {
  try {
    const raw = wx.getStorageSync(KEY) as DebugCredential | "";
    if (!raw || typeof raw !== "object") return null;
    return raw;
  } catch {
    return null;
  }
}

export function saveCredential(credential: DebugCredential): void {
  wx.setStorageSync(KEY, credential);
}

export function clearCredential(): void {
  try {
    wx.removeStorageSync(KEY);
  } catch {
    // 忽略
  }
}

export function validateCredential(c: Omit<DebugCredential, "name">): string | null {
  if (!/^\d{1,3}(\.\d{1,3}){3}$/.test(c.host)) return "IP 地址格式不正确";
  const port = Number(c.port);
  if (!Number.isInteger(port) || port <= 0 || port > 65535) return "端口不合法";
  if (!/^[0-9a-f]{32}$/.test(c.controllerId)) return "controllerId 须为 32 位十六进制";
  if (!/^[0-9a-f]{64}$/.test(c.pskHex)) return "PSK 须为 64 位十六进制";
  return null;
}

export function hexToBytes(hex: string): Uint8Array {
  return new Uint8Array(hex.match(/.{2}/g)!.map((h) => parseInt(h, 16)));
}
