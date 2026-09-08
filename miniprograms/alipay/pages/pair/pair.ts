import { ensureRandomSource } from "../../lib/credentials";
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
  onHostInput(e: { detail: { value: string } }) {
    this.setData({ host: e.detail.value });
  },
  onPortInput(e: { detail: { value: string } }) {
    this.setData({ port: e.detail.value });
  },
  onControllerIdInput(e: { detail: { value: string } }) {
    this.setData({ controllerId: e.detail.value.trim().toLowerCase() });
  },
  onPskInput(e: { detail: { value: string } }) {
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
      my.showToast({ type: "none", content: invalid });
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
    my.showToast({ type: "success", content: "已保存" });
  },
  async onTest() {
    const { host, port, controllerId, pskHex } = this.data;
    const invalid = validateCredential({ host, port: Number(port), controllerId, pskHex });
    if (invalid) {
      my.showToast({ type: "none", content: invalid });
      return;
    }
    saveCredential({ host, port: Number(port), controllerId, pskHex, name: `${host}:${port}` });
    this.setData({ saved: true });
    // 连接前校验随机源（无 crypto.getRandomValues 的基础库直接拒绝，不降级）
    try {
      ensureRandomSource();
      my.navigateTo({ url: "/pages/remote/remote" });
    } catch (error) {
      my.showToast({ type: "none", content: (error as Error).message });
    }
  },
});
