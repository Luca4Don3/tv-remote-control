/**
 * 环境无关的随机源：默认用 WebCrypto（Node/浏览器/小程序新基础库均可），
 * 不存在时（旧基础库）通过 setRandomSource 注入（微信可用 wx.getRandomValues 预取池）。
 */
let source: (length: number) => Uint8Array | null = defaultSource;

function defaultSource(length: number): Uint8Array | null {
  const g = (globalThis as { crypto?: { getRandomValues(b: Uint8Array): Uint8Array } }).crypto;
  if (!g?.getRandomValues) return null;
  return g.getRandomValues(new Uint8Array(length));
}

/** 设置同步随机源（返回 null 表示源不可用，将抛错）。 */
export function setRandomSource(fn: (length: number) => Uint8Array | null): void {
  source = fn;
}

export function randomBytes(length: number): Uint8Array {
  const bytes = source(length);
  if (!bytes || bytes.length !== length) throw new Error("no random source available");
  return bytes;
}
