# TV Remote Control

当前版本：`0.1.2`。

Android TV 应用开发测试辅助工具：电视端 APK 提供安全的按键控制与画面/音频回传，Windows/macOS 原生控制端用于在开发调试中遥控电视、实时查看画面并采集允许的音频，帮助开发者验证和测试即将发布到电视上的 APK。

## 架构与安全边界

- APK 是默认且唯一的按键控制后端；控制协议使用长度前缀 TCP + TLS，不使用 WebSocket。
- 首次配对使用电视端 6 位码、双端 SAS 核对和电视本地确认；长期凭据按电视证书指纹存储在 Android Keystore、Windows DPAPI 或 macOS Keychain。
- 活动会话由有效认证消息滑动续期，45 秒心跳失联；未知、乱序和重放消息显式失败。
- MediaProjection 通过独立的证书 pin TLS 会话传输 H.264/AAC TVRM 包，每次观看都需要电视端授权；不绕过 DRM、HDCP 或应用音频捕获策略。
- MediaProjection 从 `1280×720/15 FPS/2 Mbps` 启动；连续 30 个普通视频包被拒或 API 29+ 达到 severe thermal 时，每次投影最多单向降至 `960×540/10 FPS/1 Mbps`。重建失败会终止媒体附件并显式上报。
- ADB/scrcpy 仅是用户明确启用的可选媒体增强，固定使用私有端口 5038；scrcpy 始终 `control=false`，不会替代 APK 按键控制。
- 不实现、不调用 SSH。minicap 在获得具体 `SDK + ABI + firmware` 实机证据前保持禁用。

## 当前实现状态

已实现：

- UDP 多设备发现、TCP+TLS、SAS 配对、证书 pin、凭据迁移、认证、心跳、能力与按键 ACK。
- DPAD、返回、主页、音量、静音和媒体键；DOWN/REPEAT/UP 携带递增 `repeatCount`。
- 独立 MediaProjection TLS/TVRM 链路、有界队列、H.264/AAC 桌面解码入口和无音频降级。
- Windows x64/ARM64/x86 与 macOS ARM64 产品构建；x86 不提供媒体。
- Windows `ADB 设置` 已接通独立 ABI/worker、保存路径/PATH/管理目录探针、私有 5038 server、设备选择、官方依赖安装和 scrcpy 媒体链路；默认关闭，失败不影响 APK 遥控且不会自动触发 MediaProjection。
- x64/ARM64 可在用户确认后安装或升级锁定的 Google Platform Tools；x86 仅允许用户选择 `adb.exe` 并完整探针，不包含安装入口、scrcpy server 或 ADB 媒体。

`UNVERIFIED`：Android encoder 实际重建、电视授权撤销与 60 分钟会话；Windows x64/ARM64/x86 的 ADB/scrcpy、无音频、断线两秒释放与安装故障回滚；Android 正式签名。交叉编译不视为真机结论。

## 开发验证

```text
cd windows-controller
ZIG_GLOBAL_CACHE_DIR="$PWD/.temp/zig-global-cache" \
ZIG_LOCAL_CACHE_DIR="$PWD/.temp/zig-local-cache" \
zig build test

cd ../android-agent
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease
```

正式 Android 发布使用仓库外签名材料，并执行 `scripts/package-android-release.sh`。桌面本地打包使用 `scripts/package-local.sh`。两个入口都要求 `TVRC_SECURITY_AUDIT_SCANNER=<absolute_scanner_path>`，且离线审计 exit 0 后才会构建；依赖、ZIP、APK 和 checksum 均先写入项目 `.temp/`，全部门禁通过后才进入 `.artifacts/`。版本只从根目录 `VERSION` 读取，格式为 `major.minor.patch` 或 `major.minor-rcN`。

## 致谢

本项目受益于以下开源项目与工具，在此致以诚挚感谢：

| 项目 | 版本 | 协议 | 用途 |
|---|---|---|---|
| [Mbed TLS](https://github.com/Mbed-TLS/mbedtls) | 3.6.7 | Apache-2.0 | Windows 控制端 TLS 1.2/1.3 客户端 |
| [scrcpy](https://github.com/Genymobile/scrcpy) | 4.1 | Apache-2.0 | 可选 ADB 视频/音频后端（`control=false`） |
| [Android SDK Platform Tools](https://developer.android.com/tools/releases/platform-tools) | 37.0.1 | Android SDK License | 可选 ADB 工具，用户确认后安装 |
| [Gradle](https://gradle.org) | 9.6.1 | Apache-2.0 | Android 构建 |
| [Zig](https://ziglang.org) | 0.16 | MIT | Windows 控制端开发语言与工具链 |
| [Kotlin](https://kotlinlang.org) | 随 Gradle 解析 | Apache-2.0 | Android 端开发语言 |
| [Eclipse Temurin](https://adoptium.net) | 17 | GPL-2.0 with Classpath Exception | 构建工具链 JDK |
| [JUnit](https://junit.org) | 4.13.2 | EPL-1.0 | Android 单元测试 |
| macOS 系统框架（AppKit / AVFoundation / VideoToolbox / Metal） | — | Apple 许可 | macOS 控制端 |
| Android 平台框架（AndroidX 与系统 API） | — | Apache-2.0 | APK 运行环境 |

依赖锁定、校验和与许可摘要见 `dependencies.lock.json` 和 `THIRD_PARTY_NOTICES.md`。发布包必须附带各源码归档的完整许可文本。

## 开源协议

本项目源码采用 [Apache License 2.0](LICENSE) 发布，与所依赖的 Mbed TLS、scrcpy、Gradle、Kotlin 等 Apache 生态项目保持一致。第三方组件的许可信息见 `THIRD_PARTY_NOTICES.md`；项目自身的构建产物在打包时也会携带对应组件的完整许可文本。
