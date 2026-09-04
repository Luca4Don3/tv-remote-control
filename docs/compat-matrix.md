# API 19~36 兼容矩阵

电视端 agent（`android-agent`）承诺从 Android 4.4（API 19）到最新（API 36）的完整支持。
本文件记录按版本分叉的实现点与能力降级语义；`UNVERIFIED` 项在获得对应真机证据前
不视为已验证结论（沿用项目安全文档惯例）。

## 能力降级总表

| 能力 | API 19-20 | API 21-22 | API 23-25 | API 26-28 | API 29-35 | API 36 |
|---|---|---|---|---|---|---|
| TLS | TLS1.2 + CBC 回退 | 同左 | 同左 | 同左 | TLS1.2/1.3 + AEAD | 同左 |
| 凭据存储 | RSA PKCS1 包裹 | 同左 | RSA OAEP | 同左 | 同左 | 同左 |
| 按键遥控（BACK/HOME/CENTER） | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| DPAD 方向焦点 | ❌ | BEST_EFFORT | ✅ | ✅ | ✅ | ✅ |
| 文本注入（text_commit/draft） | ❌ UNSUPPORTED | ✅ | ✅ | ✅ | ✅ | ✅ |
| 扫码配对 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| WS 调试通道 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| MediaProjection 媒体 | ❌ UNSUPPORTED | ✅ | ✅ | ✅ | ✅ | ✅ |
| 音频采集（playbackAudio） | ❌ | ❌ | ❌ | ❌ | ✅ | ✅ |
| 编码器异步 API | legacy drain 线程 | 同左 | 异步 Callback | 同左 | 同左 | 同左 |
| 音量/静音 | setStreamMute（废弃） | 同左 | ADJUST_TOGGLE_MUTE | 同左 | 同左 | 同左 |

## 实现点分叉明细

| API | 版本 | 位置 | 守卫 |
|---|---|---|---|
| `KeyGenParameterSpec` / OAEP | 23 | `KeystoreCredentialStore.kt`、`TlsIdentityStore.kt` | ✅ `SDK>=23` else `KeyPairGeneratorSpec` + PKCS1 |
| `MediaProjectionManager` | 21 | `MainActivity`、`ProjectionService`、`CapabilityDetector` | ✅ |
| `MediaCodec.setCallback` / `KEY_PRIORITY` | 23 | `ProjectionCapture.kt` | ✅ else legacy drain 线程 + `LegacyEncoderReaper` |
| `AudioPlaybackCaptureConfiguration` | 29 | `PlaybackAudioCapture.kt` | ✅ `SDK>=29` |
| `PowerManager.OnThermalStatusChangedListener` | 29 | `ProjectionCapture.kt` | ✅ |
| `ACTION_SET_TEXT` | 21 | `TvAccessibilityService.performText` | ✅ `SDK>=21` else UNSUPPORTED |
| `focusSearch` / `FOCUS_*` | 22 | `TvAccessibilityService.kt` | ✅ `<22 return false` |
| `NotificationChannel` | 26 | `AgentService`、`ProjectionService` | ✅ |
| `startForegroundService` / `stopForeground(REMOVE)` | 26/24 | `AgentService` | ✅ |
| `PendingIntent.FLAG_IMMUTABLE` | 23 | `AgentService`、`ProjectionService` | ✅ |
| `checkSelfPermission` / `requestPermissions` | 23/33 | `MainActivity` | ✅ 仅 `SDK>=29/33` 路径 |
| `Context.getParcelableExtra(Class)` | 33 | `ProjectionService.kt` | ✅ else 废弃版 |
| TLS 1.3 / ChaCha20 | 运行时探测 | `TlsPolicy.probe()` | ✅ 探测式 + CBC 回退 |
| Manifest `foregroundServiceType` | 34 语义 | `AndroidManifest.xml` | 老系统忽略 |
| `dataExtractionRules` | 31 | `AndroidManifest.xml` | 老系统忽略 |

## 本分支新增能力的兼容说明

- **文本注入**：`ACTION_SET_TEXT` 为 API 21+；API 19-20 由能力协商上报 `textInput=UNSUPPORTED`，
  服务端不拦截（返回 `command_ack UNSUPPORTED`），控制端按能力位隐藏输入 UI。
- **扫码配对**：QR 渲染用 zxing core（纯 Java，无 Android 依赖），API 19 可用；
  `NetworkInterface.isSiteLocalAddress` 全版本可用。
- **WS 调试通道**：`ServerSocket`/`javax.crypto` 全版本可用；`AES/GCM/NoPadding` 在
  API 19+ 均受支持（SunJCE/BouncyCastle 兜底由系统默认 provider 提供，19 起 GCM 可用）。
  注意：明文通道安全边界（应用层加密替代 TLS）已在 `SECURITY.md` 说明。
- **Rust core（:controller 使用）**：`.so` 以 API 21 平台头编译，仅供 minSdk 24 的
  手机控制端模块加载；agent 保持纯 Kotlin 实现不引入 .so，API 19 支持不受影响。

## UNVERIFIED（真机待验证）

- API 19/21/23/26/29 真机上的 TLS 握手与配对全流程
- API 19 上 `AES/GCM/NoPadding` 各厂商 provider 差异
- API 21-22 上 `MediaProjection` 编码器重建行为
- API 19 无障碍 `ACTION_CLICK` 焦点查找在第三方 launcher 的兼容性

这些项不因模拟器或交叉编译结论而改变状态；真机验证证据落档后才可移出本表。
