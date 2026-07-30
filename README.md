# TV Remote Control

个人使用的 Android TV 远程控制工具，包含：

- `windows-controller/`：Zig 实现的 Windows 本地控制端。
- `android-agent/`：最低支持 Android 4.4（API 19）的电视端 APK。

基础遥控与媒体后端独立选择：

1. APK 负责不依赖 ADB 的发现、配对、能力上报与基础遥控。
2. Android 5.0 及以上且用户已授权 ADB：优先使用 scrcpy 画面。
3. Android 5.0 及以上且 ADB 不可用：由用户授权 `MediaProjection`。
4. Android 4.4 且 ADB/minicap 实机验证成功：使用 minicap JPEG 流。
5. 无法提供画面时明确显示不支持，但不影响已经可用的基础遥控。

项目当前不绕过 DRM、系统签名权限或应用音频捕获策略。

## 开发验证

```text
cd windows-controller
zig build test

cd ../android-agent
gradle test assembleDebug
```

Android 工程需要 JDK 17、Android SDK 和对应平台工具。Windows 控制端的 SDL、FFmpeg、scrcpy server 与 minicap 集成在后续媒体里程碑中固定版本并记录许可证。

当前已完成基础后端选择、ADB 依赖决策、Windows 原生界面壳、Android 能力探针、前台服务、配对/会话核心和基础命令执行器。scrcpy、MediaProjection 编码传输、minicap 实机适配及网络监听必须在目标电视接入后继续验证，当前不会伪装成已支持。
