import { DebugSessionController, type ConnState } from "../../lib/session-controller";
import { ensureRandomSource, loadCredential, validateCredential } from "../../lib/credentials";

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
        my.showToast({ type: "none", content: state.message });
      }
    });
    const credential = loadCredential();
    if (!credential) {
      my.showToast({ type: "none", content: "请先在配对页保存凭据" });
      setTimeout(() => my.navigateBack(), 1200);
      return;
    }
    const invalid = validateCredential(credential);
    if (invalid) {
      my.showToast({ type: "none", content: invalid });
      setTimeout(() => my.navigateBack(), 1200);
      return;
    }
    try {
      ensureRandomSource();
      void controller.connect(credential);
    } catch (error) {
      my.showToast({ type: "none", content: (error as Error).message });
    }
  },
  onUnload() {
    controller.disconnect();
  },
  async onKey(e: { currentTarget: { dataset: { key: string } } }) {
    const key = (e.currentTarget.dataset.key as string) ?? "";
    try {
      const ack = await controller.pressKey(key);
      if (ack.status !== "SUCCESS") {
        my.showToast({ type: "none", content: `失败：${ack.reason ?? ack.status}` });
      }
    } catch (error) {
      my.showToast({ type: "none", content: `已断开：${(error as Error).message}` });
    }
  },
  onDraftInput(e: { detail: { value: string } }) {
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
        my.showToast({ type: "success", content: "已发送" });
      } else {
        my.showToast({ type: "none", content: `失败：${ack.reason ?? ack.status}` });
      }
    } catch (error) {
      my.showToast({ type: "none", content: `已断开：${(error as Error).message}` });
    }
  },
  onDisconnect() {
    controller.disconnect();
    my.navigateBack();
  },
});
