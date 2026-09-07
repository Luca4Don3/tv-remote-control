import { initRandomPool } from "../../lib/credentials";
import {
  clearCredential,
  loadCredential,
  saveCredential,
  validateCredential,
  type DebugCredential,
} from "../../lib/credentials";

const DEFAULTS = { host: "192.168.1.0", port: 47833, controllerId: "", pskHex: "" };

Page({
  data: {
    host: DEFAULTS.host,
    port: String(DEFAULTS.port),
    controllerId: "",
    pskHex: "",
    saved: false,
  },
  onLoad() {
    const saved = loadCredential();
    if (saved) {
      this.setData({
        host: saved.host,
        port: String(saved.port),
        controllerId: saved.controllerId,
        pskHex: saved.pskHex,
        saved: true,
      });
    }
  },
  onHostInput(e: WechatMiniprogram.Input) {
    this.setData({ host: e.detail.value });
  },
  onPortInput(e: WechatMiniprogram.Input) {
    this.setData({ port: e.detail.value });
  },
  onControllerIdInput(e: WechatMiniprogram.Input) {
    this.setData({ controllerId: e.detail.value.trim().toLowerCase() });
  },
  onPskInput(e: WechatMiniprogram.Input) {
    this.setData({ pskHex: e.detail.value.trim().toLowerCase() });
  },
  onClear() {
    clearCredential();
    this.setData({ ...DEFAULTS, port: String(DEFAULTS.port), saved: false });
  },
  onSave() {
    const { host, port, controllerId, pskHex } = this.data;
    const invalid = validateCredential({ host, port: Number(port), controllerId, pskHex });
    if (invalid) {
      wx.showToast({ title: invalid, icon: "none" });
      return;
    }
    const credential: DebugCredential = {
      host,
      port: Number(port),
      controllerId,
      pskHex,
      name: `${host}:${port}`,
    };
    saveCredential(credential);
    this.setData({ saved: true });
    wx.showToast({ title: "已保存", icon: "success" });
  },
  async onTest() {
    const { host, port, controllerId, pskHex } = this.data;
    const invalid = validateCredential({ host, port: Number(port), controllerId, pskHex });
    if (invalid) {
      wx.showToast({ title: invalid, icon: "none" });
      return;
    }
    saveCredential({ host, port: Number(port), controllerId, pskHex, name: `${host}:${port}` });
    this.setData({ saved: true });
    // 连接前预取随机池（wx.getRandomValues 异步，会话内同步消费）
    try {
      await initRandomPool();
      wx.navigateTo({ url: "/pages/remote/remote" });
    } catch (error) {
      wx.showToast({ title: (error as Error).message, icon: "none" });
    }
  },
});
