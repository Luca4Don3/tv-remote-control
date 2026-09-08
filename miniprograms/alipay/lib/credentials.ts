/** 调试凭据本地存储（my storage；PSK 为 64-hex，来自 agent 导出界面 P3）。 */
import { setRandomSource } from "@tvrc/miniprogram-core";

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
    const raw = my.getStorageSync({ key: KEY }).data as DebugCredential | "";
    if (!raw || typeof raw !== "object") return null;
    return raw;
  } catch {
    return null;
  }
}

export function saveCredential(credential: DebugCredential): void {
  my.setStorageSync({ key: KEY, data: credential });
}

export function clearCredential(): void {
  try {
    my.removeStorageSync({ key: KEY });
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

/**
 * 随机源校验：支付宝小程序基础库未提供 my.getRandomValues；
 * core 默认源（globalThis.crypto.getRandomValues）可用则直接使用，
 * 否则拒绝连接（不做 Math.random 之类的不安全降级——clientRandom 参与
 * HKDF 盐，会话密钥安全性不可依赖可预测盐）。
 */
export function ensureRandomSource(): void {
  const g = (globalThis as { crypto?: { getRandomValues(b: Uint8Array): Uint8Array } }).crypto;
  if (!g?.getRandomValues) {
    // clientRandom 参与 HKDF 盐；无可信随机源时不做 Math.random 之类的不安全降级
    throw new Error("当前支付宝基础库未提供安全随机源（crypto.getRandomValues），无法建立加密会话");
  }
}
