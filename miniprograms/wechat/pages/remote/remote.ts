import { DebugSessionController, type ConnState } from "../../lib/session-controller";
import { initRandomPool, loadCredential, validateCredential } from "../../lib/credentials";

const controller = new DebugSessionController();

const KEYS: { label: string; key: string }[] = [
  { label: "▲", key: "DPAD_UP" },
  { label: "◀", key: "DPAD_LEFT" },
  { label: "OK", key: "DPAD_CENTER" },
  { label: "▶", key: "DPAD_RIGHT" },
  { label: "▼", key: "DPAD_DOWN" },
  { label: "返回", key: "BACK" },
  { label: "主页", key: "HOME" },
  { label: "菜单", key: "MENU" },
  { label: "音量+", key: "VOLUME_UP" },
  { label: "静音", key: "VOLUME_MUTE" },
  { label: "音量-", key: "VOLUME_DOWN" },
  { label: "播放/暂停", key: "MEDIA_PLAY_PAUSE" },
];

Page({
  data: {
    keys: KEYS,
    state: "idle" as ConnState["kind"],
    stateText: "未连接",
    draft: "",
    draftTimer: null as unknown as ReturnType<typeof setTimeout>,
  },
  onLoad() {
    controller.onState((state) => {
      const text =
        state.kind === "connecting" ? "连接中…"
        : state.kind === "connected" ? "已连接"
        : state.kind === "error" ? state.message
        : "未连接";
      this.setData({ state: state.kind, stateText: text });
      if (state.kind === "error") {
        wx.showToast({ title: state.message, icon: "none" });
      }
    });
    const credential = loadCredential();
    if (!credential) {
      wx.showToast({ title: "请先在配对页保存凭据", icon: "none" });
      setTimeout(() => wx.navigateBack(), 1200);
      return;
    }
    const invalid = validateCredential(credential);
    if (invalid) {
      wx.showToast({ title: invalid, icon: "none" });
      setTimeout(() => wx.navigateBack(), 1200);
      return;
    }
    void initRandomPool().then(() => controller.connect(credential));
  },
  onUnload() {
    controller.disconnect();
  },
  async onKey(e: WechatMiniprogram.TouchEvent) {
    const key = (e.currentTarget.dataset.key as string) ?? "";
    try {
      const ack = await controller.pressKey(key);
      if (ack.status !== "SUCCESS") {
        wx.showToast({ title: `失败：${ack.reason ?? ack.status}`, icon: "none" });
      }
    } catch (error) {
      wx.showToast({ title: `已断开：${(error as Error).message}`, icon: "none" });
    }
  },
  onDraftInput(e: WechatMiniprogram.Input) {
    const text = e.detail.value;
    this.setData({ draft: text });
    // text_draft：输入停顿 600ms 发草稿（对齐 iOS 防抖输入语义）
    if (this.data.draftTimer) clearTimeout(this.data.draftTimer);
    const timer = setTimeout(() => {
      if (text.length > 0) void controller.sendText(text, true);
    }, 600);
    this.setData({ draftTimer: timer });
  },
  async onDraftCommit() {
    const text = this.data.draft;
    if (!text) return;
    try {
      const ack = await controller.sendText(text, false);
      if (ack.status === "SUCCESS") {
        this.setData({ draft: "" });
        wx.showToast({ title: "已发送", icon: "success" });
      } else {
        wx.showToast({ title: `失败：${ack.reason ?? ack.status}`, icon: "none" });
      }
    } catch (error) {
      wx.showToast({ title: `已断开：${(error as Error).message}`, icon: "none" });
    }
  },
  onDisconnect() {
    controller.disconnect();
    wx.navigateBack();
  },
});
