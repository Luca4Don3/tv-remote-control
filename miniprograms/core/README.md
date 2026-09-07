# @tvrc/miniprogram-core

TV 遥控调试通道协议核心（TypeScript），供微信/支付宝小程序（开发调试版）复用。

## 范围与边界

- 仅覆盖**开发调试通道**（WS + PSK 信封加密 + 防重放），与 Android agent 的
  `DebugSessionCrypto` / `WsDebugChannel` / `ReplayWindow` 逐字段对齐：
  - HKDF-SHA256（salt=`clientRandom || serverRandom`，IKM=psk，info 按方向区分）派生双向 32B 密钥
  - nonce = 4B 零前缀 + 8B 大端 counter；信封 = counter(8B) || ciphertext || tag(16B)；
    counter 从 1 起（0 为防重放哨兵），AAD（counter + extraAad）并入加密明文体
  - WS 帧编解码：服务端帧不掩码、客户端帧强制掩码（对齐 Rust `WsCodec` 双视角）
  - `ReplayWindow`：高水位 + 窗口位图，重复/过旧/0 拒绝
  - 心跳 fire-and-forget：agent 对 client 的 ping 不回 ack（ack=null）
  - 命令序号（envelope `sequence`）严格递增，与加密计数器独立
- **不包含**：生产 TLS 主链路、Kotlin/Rust/Swift 端实现、小程序 UI（应用层各端自建）。

## 使用

```ts
import { deriveSessionKeys, DirectionCipher, ReplayWindow } from "./src/index.ts";
```

## 测试

```bash
npm install
npm test        # tsx + node:test，含 RFC 5869 官方向量与双端一致性断言
npx tsc --noEmit
```
