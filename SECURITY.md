# Security Policy

## Supported versions

Security fixes are applied to the latest released version. Older releases may be asked to upgrade before a fix is provided.

## Reporting a vulnerability

Please use [GitHub private vulnerability reporting](https://github.com/Luca4Don3/tv-remote-control/security/advisories/new). Do not open a public issue for suspected credential exposure, authentication bypass, remote control abuse, or sensitive-data leakage.

Include the affected version, platform, reproducible steps, impact, and the smallest safe proof of concept. Do not include real credentials, pairing codes, device identifiers, public addresses, logs, or captures containing personal data.

The project will acknowledge a report when it has been reviewed and will coordinate remediation and disclosure through the private advisory. There is currently no bug-bounty program.

## WebSocket 调试通道（明文，仅限开发调试，仅 debug 构建）

端口 47833 的 WebSocket 通道是**明文**传输（不使用 TLS），其安全完全依赖应用层端到端加密。
**该通道仅在 debug 构建启用**（`BuildConfig.DEBUG` 门禁）——生产 APK 不包含该监听端口：
正式遥控链路为 TCP+TLS 47832，WS 不承载任何正式流量：

- 密钥仅从**已配对**控制端凭据派生（HKDF-SHA256，salt = 双方随机数），未配对设备无法派生有效密钥；
  凭据查询在每次握手时进行——控制端被撤销后新连接立即拒绝；

- 密钥仅从**已配对**控制端凭据派生（HKDF-SHA256，salt = 双方随机数），未配对设备无法派生有效密钥；
- 每条消息 AES-256-GCM 加密，AAD 绑定 8 字节序号计数器；`ReplayWindow` 拒绝重复与过旧序号；
- 仅开放遥控消息白名单（按键/文本/心跳/能力查询），不支持配对、认证协商与媒体；
- 该通道不替代 TLS 主链路，不承载任何长期凭据传输；凭据（`secret`）从不通过该通道发送。

边界约束：

- 明文传输暴露消息长度与时序元数据；受限网络内的被动观察者无法获得内容，但可确认存在调试会话；
- 主通道（TCP+TLS 47832）仍是唯一受支持的正式遥控路径；WS 通道仅供开发调试（例如小程序开发版），
  生产环境使用应在文档与 UI 中显式提示降级边界。

实现边界说明：WS 升级握手不校验 `Host`/`Origin` 头（浏览器侧伪造 Origin 无法获得内容，所有遥控消息均需配对派生密钥的 AES-GCM 密文；明文握手仅暴露连接建立事件）。未验证项：WS 通道在弱网与高频输入下的抗重放窗口行为（`UNVERIFIED`，待真机证据）。
