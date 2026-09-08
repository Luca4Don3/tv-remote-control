# TV 遥控支付宝小程序（开发调试版）

连接 Debug 构建 Android agent 的 WS 调试通道（明文 `ws://` + PSK 端到端加密 + 防重放），
与微信端共享协议核心 `@tvrc/miniprogram-core`。

## 与微信端的差异

- `my.connectSocket({ url, multiple: true })` 同步返回 SocketTask（适配 `AlipaySocketTransport`）。
- Toast/存储/navigate 均为 `my.*` 形态；页面文件为 `.axml`/`.acss`。
- **随机源**：支付宝基础库无 `my.getRandomValues`，依赖运行环境提供
  `globalThis.crypto.getRandomValues`（core 默认源）。进入遥控页前做 `ensureRandomSource()`
  校验，**不满足直接拒绝连接**（clientRandom 参与 HKDF 盐，不做 `Math.random` 之类的不安全降级）。

## 使用前提（同微信端）

1. Android agent 为 Debug 构建（Release 不开放调试端口 47833）。
2. 手机与 agent 同一局域网。
3. 已获得凭据：agent IP、端口、`controllerId`（32-hex）、`pskHex`（64-hex，
   来自 agent 端「查看调试凭据（仅开发环境）」导出）。

## 支付宝开发者工具导入步骤

1. 「打开项目」选择本目录；appid 占位为测试位（在项目配置中替换为自己的调试 appid）。
2. 安装依赖并在 IDE 内构建 npm（引入 `@tvrc/miniprogram-core`）。
3. 在 IDE「详情/设置」中允许不校验合法域名（局域网明文 WS，仅调试）。
4. 编译运行：配对页录入凭据 →「连接并遥控」。

## 类型检查

```bash
npm run typecheck   # tsc --noEmit（依赖 @mini-types/alipay）
```

## 安全边界

同微信端 README：明文 WS 仅限局域网与 Debug 构建场景；加密为应用层端到端
（HKDF-SHA256 + AES-256-GCM + 滑动窗口防重放）；凭据明文存于 `my storage`（开发调试属性）。
