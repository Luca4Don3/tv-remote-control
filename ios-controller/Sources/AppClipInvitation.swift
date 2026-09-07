// swift-version 6; iOS 16+
// TV Remote Control —— 配对邀请链接解析（主 App 扫码与 App Clip 唤起共用）。
//
// 支持两种格式（agent 端 QR 内容，参数按出现顺序自由组合）：
// 1. `tvrc://pair?host=..&port=..&token=..&ttl=..`（Android 扫码兼容，现行格式）
// 2. `https://<clip 域>/pair?host=..&port=..&token=..&ttl=..`（App Clip 唤起链接；
//    域名在第 2 期确定——解析只校验 host 路径与参数，不限定具体域名）
//
// host/port/token 为必填；ttl（秒）可选，缺省 120。

import Foundation

struct AppClipInvitation: Equatable {
    let host: String
    let port: UInt16
    let token: String
    let ttlSeconds: UInt32

    /// 解析失败返回 nil（调用方展示"无效二维码"而非静默失败）。
    static func parse(_ url: URL) -> AppClipInvitation? {
        guard let components = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return nil }
        let isScheme = components.scheme == "tvrc" && components.host == "pair"
        let isClipPath = components.scheme == "https" && components.path == "/pair"
        guard isScheme || isClipPath else { return nil }
        let query = components.queryItems ?? []

        func value(_ name: String) -> String? {
            query.first { $0.name == name }?.value
        }

        // host：IPv4/IPv6/主机名；不允许空与内嵌空白
        guard let host = value("host"), !host.isEmpty, host.allSatisfy({ !$0.isWhitespace }) else { return nil }
        // port：1..65535
        guard let portRaw = value("port"), let port = UInt16(portRaw), port > 0 else { return nil }
        // token：64 hex（与 agent PairingManager.QR_TOKEN_HEX_LENGTH 对齐）
        guard let token = value("token"), token.count == 64,
              token.allSatisfy({ ($0 >= "0" && $0 <= "9") || ($0 >= "a" && $0 <= "f") }) else { return nil }
        // ttl：可选，1..3600 秒
        var ttl: UInt32 = 120
        if let ttlRaw = value("ttl") {
            guard let parsed = UInt32(ttlRaw), (1...3_600).contains(parsed) else { return nil }
            ttl = parsed
        }
        return AppClipInvitation(host: host, port: port, token: token, ttlSeconds: ttl)
    }

    /// 从扫描文本解析（先按 URL 处理；非 URL 即刻返回 nil）。
    static func parse(_ text: String) -> AppClipInvitation? {
        guard let url = URL(string: text), url.scheme != nil else { return nil }
        return parse(url)
    }
}
