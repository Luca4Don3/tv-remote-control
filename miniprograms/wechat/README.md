# TV 遥控微信小程序（开发调试版）

连接 Debug 构建 Android agent 的 WS 调试通道（明文 `ws://` + PSK 端到端加密 + 防重放），
提供 D-pad / 媒体键遥控与文本输入。

## 使用前提

1. Android agent 为 **Debug 构建**（Release 构建不开放调试端口）。
2. 手机与 agent 同一局域网。
3. 已获得凭据四元组：agent IP、调试端口（默认 47833）、`controllerId`（32-hex）、`pskHex`（64-hex，
   由 P3 的 agent 导出界面提供，或从配对记录手动录入）。

## 微信开发者工具导入步骤

1. 打开微信开发者工具 →「导入项目」→ 选择本目录（`miniprograms/wechat/`）。
   测试号可用默认 `touristappid`（project.config.json 已占位）。
2. 安装依赖并构建 npm：
   ```bash
   npm install
   ```
   然后在开发者工具「工具 → 构建 npm」生成 `miniprogram_npm/`（引入 `@tvrc/miniprogram-core`）。
3. 「详情 → 本地设置」勾选 **「不校验合法域名、web-view（业务域名）、TLS 版本以及 HTTPS 证书」**
   （调试通道为局域网明文 WS）。
4. 编译运行：配对页录入凭据 →「连接并遥控」。

## 类型检查

```bash
npm run typecheck   # tsc --noEmit（依赖 miniprogram-api-typings）
```

## 安全边界

- 明文 WS 仅在局域网与 Debug 构建场景使用；加密为应用层端到端
  （HKDF-SHA256 派生双向密钥 + AES-256-GCM + 滑动窗口防重放），与 agent 端逐字段对齐。
- 凭据明文存于 `wx storage`（开发调试工具属性）；生产主链路（TLS + 证书指纹）不适用本模块。
