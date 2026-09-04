//! 序号防重放：滑动窗口 + 严格单调升级。
//!
//! WS 调试通道的明文安全模型中，重放防护是应用层核心职责：
//! 窗口内未见过的序号记为"已见"，窗口外的必须严格大于已见最大值。

/// 滑动窗口防重放（窗口 64 位图 + 单调高水位线）。
pub struct ReplayGuard {
    /// 已见的最高序号（初始 0 表示未收到任何消息）。
    high_watermark: u64,
    /// 位图：bit i 表示 high_watermark - i 是否已见（i ∈ 1..=64）。
    window: u64,
    window_bits: u32,
}

impl ReplayGuard {
    pub fn new(window_bits: u32) -> Self {
        assert!((1..=64).contains(&window_bits), "window must be 1..=64");
        ReplayGuard { high_watermark: 0, window: 0, window_bits }
    }

    /// 校验并登记序号。重复或过旧返回 false。
    ///
    /// 语义：`high_watermark` 本身视为已见；`window` bit p 表示
    /// 序号 `high_watermark - (p+1)` 已见（p ∈ 0..window_bits）。
    pub fn check_and_accept(&mut self, sequence: u64) -> bool {
        if sequence == 0 {
            return false;
        }
        if self.high_watermark == 0 {
            self.high_watermark = sequence;
            self.window = 0;
            return true;
        }
        if sequence > self.high_watermark {
            let advance = sequence - self.high_watermark;
            self.window = if advance >= self.window_bits as u64 {
                0
            } else {
                (self.window << advance) & self.mask()
            };
            if advance <= self.window_bits as u64 {
                self.window |= 1u64 << (advance - 1);
            }
            self.high_watermark = sequence;
            return true;
        }
        let delta = self.high_watermark - sequence;
        if delta == 0 || delta > self.window_bits as u64 {
            return false;
        }
        let bit = 1u64 << (delta - 1);
        if self.window & bit != 0 {
            return false;
        }
        self.window |= bit;
        true
    }

    fn mask(&self) -> u64 {
        if self.window_bits >= 64 {
            u64::MAX
        } else {
            (1u64 << self.window_bits) - 1
        }
    }

    pub fn high_watermark(&self) -> u64 {
        self.high_watermark
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn accepts_in_order() {
        let mut g = ReplayGuard::new(64);
        assert!(g.check_and_accept(1));
        assert!(g.check_and_accept(2));
        assert!(g.check_and_accept(3));
    }

    #[test]
    fn rejects_replay() {
        let mut g = ReplayGuard::new(64);
        assert!(g.check_and_accept(1));
        assert!(g.check_and_accept(2));
        assert!(!g.check_and_accept(1));
        assert!(!g.check_and_accept(2));
    }

    #[test]
    fn rejects_zero_and_out_of_window() {
        let mut g = ReplayGuard::new(8);
        assert!(!g.check_and_accept(0));
        assert!(g.check_and_accept(100));
        assert!(!g.check_and_accept(90)); // 低于高水位超过窗口宽度
    }

    #[test]
    fn window_slides() {
        let mut g = ReplayGuard::new(4);
        // 顺序 1,2,4,5,6（跳过 3）
        for s in [1u64, 2, 4, 5, 6] {
            assert!(g.check_and_accept(s));
        }
        // 3 在窗口内且未见过 → 延迟到达应接受
        assert!(g.check_and_accept(3));
        // 之后 3 再来一次 → 拒绝（已见）
        assert!(!g.check_and_accept(3));
        // 高水位继续推进，旧序号滑出窗口
        for s in 7..=20 {
            assert!(g.check_and_accept(s));
        }
        assert!(!g.check_and_accept(3)); // 远低于高水位
    }

    #[test]
    fn gap_advance_clamps_to_window() {
        let mut g = ReplayGuard::new(4);
        assert!(g.check_and_accept(1));
        // 直接跳到 100：窗口被截断重置
        assert!(g.check_and_accept(100));
        assert!(!g.check_and_accept(2)); // 已被截断丢弃
    }
}
