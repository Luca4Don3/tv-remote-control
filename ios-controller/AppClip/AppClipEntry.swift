// swift-version 6; iOS 16+
// TV Remote Control —— App Clip 入口（轻 App：扫码唤起 → 配对 → 遥控）。
//
// 范围：TLS 配对 + 遥控（与主 App 共享 ControllerModel/CoreHandle/视图）；
// 不含 WS 调试通道（面向"扫即用"轻场景）。
// 唤起链接（第 2 期 AASA 域名确定后由 agent 端 QR 携带）：
//   https://<clip 域>/pair?host=..&port=..&token=..&ttl=..
// 邀请中的 host 预填到目标输入框；token 为扫码配对一次性凭据，
// Zig 核心的 token 配对通道为后续增强（当前仍需电视上显示的 6 位码）。

import SwiftUI

@main
struct AppClipMain: App {
    var body: some Scene {
        WindowGroup {
            AppClipRootView()
        }
    }
}

@MainActor
struct AppClipRootView: View {
    @StateObject private var model = ControllerModel()
    @State private var invitationBanner: String?

    var body: some View {
        VStack(spacing: 0) {
            // App Clip 规范惯例：顶部完整 App 获取横幅（第 2 期接 AppStoreOverlayView，
            // 需 App Store Connect 的 App Clip Card/ID）
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("TV 遥控轻 App").font(.headline)
                    Text("安装完整 App 以便长期使用").font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
            }
            .padding(12)
            .background(Color(.secondarySystemBackground))
            if let banner = invitationBanner {
                Text(banner).font(.caption).padding(6)
            }
            ContentView(model: model)
        }
        .onOpenURL { url in
            handleInvitation(url)
        }
        .onContinueUserActivity(NSUserActivityTypeBrowsingWeb) { activity in
            guard let url = activity.webpageURL else { return }
            handleInvitation(url)
        }
    }

    /// 邀请链接注入：host 预填（配对码仍取电视屏显 6 位码）。
    private func handleInvitation(_ url: URL) {
        guard let invitation = AppClipInvitation.parse(url) else {
            invitationBanner = "无效的配对邀请链接"
            return
        }
        model.target = invitation.host
        invitationBanner = "已识别电视 \(invitation.host)，请输入电视上显示的 6 位配对码"
    }
}
