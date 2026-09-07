/**
 * 滑动窗口防重放（与 Kotlin `ReplayWindow` / Rust `replay.rs` 语义逐条对齐）：
 * 高水位 + 窗口位图；窗口内未见过的延迟序号接受，重复/过旧/跨窗口拒绝。
 */
export class ReplayWindow {
  private highWatermark = 0n;
  private window = 0n;

  constructor(private readonly windowBits = 64) {
    if (windowBits < 1 || windowBits > 64) throw new Error("window must be 1..=64");
  }

  private mask(): bigint {
    return this.windowBits >= 64 ? ~0n & ((1n << 64n) - 1n) : (1n << BigInt(this.windowBits)) - 1n;
  }

  /** 返回 true 表示新序号被接受；重复/过旧/0 返回 false。 */
  checkAndAccept(sequence: bigint): boolean {
    if (sequence <= 0n) return false;
    if (this.highWatermark === 0n) {
      this.highWatermark = sequence;
      this.window = 0n;
      return true;
    }
    if (sequence > this.highWatermark) {
      const advance = sequence - this.highWatermark;
      this.window =
        advance >= BigInt(this.windowBits) ? 0n : (this.window << advance) & this.mask();
      if (advance <= BigInt(this.windowBits)) {
        this.window |= 1n << (advance - 1n);
      }
      this.highWatermark = sequence;
      return true;
    }
    const delta = this.highWatermark - sequence;
    if (delta === 0n || delta > BigInt(this.windowBits)) return false;
    const bit = 1n << (delta - 1n);
    if ((this.window & bit) !== 0n) return false;
    this.window |= bit;
    return true;
  }

  highWatermarkValue(): bigint {
    return this.highWatermark;
  }
}
