// swift-version 6; iOS 16+
// TV Remote Control —— 遥控视图（主 App 与 App Clip 共享）。

import SwiftUI
import TvRemoteCoreZig

@MainActor
struct ContentView: View {
    @StateObject private var model: ControllerModel
    init(model: ControllerModel? = nil) {
        _model = model.map { StateObject(wrappedValue: $0) } ?? StateObject(wrappedValue: ControllerModel())
    }

    var body: some View {
        VStack(spacing: 12) {
            Text(model.status)
                .font(.footnote)
                .multilineTextAlignment(.center)
                .padding(.horizontal)
            HStack(spacing: 8) {
                TextField("电视 IP", text: $model.target)
                    .textFieldStyle(.roundedBorder)
                    .frame(maxWidth: 180)
                TextField("6 位配对码", text: $model.pairingCode)
                    .textFieldStyle(.roundedBorder)
                    .frame(maxWidth: 110)
                    .keyboardType(.numberPad)
            }
            .padding(.horizontal)
            HStack(spacing: 10) {
                Button("配对") { model.pair() }.disabled(model.busy || !model.controlAvailable)
                Button("连接") { model.connect() }.disabled(model.busy || !model.controlAvailable)
                Button("断开") { model.disconnect() }.disabled(model.busy || !model.controlAvailable)
            }
            Text("安全核对码：\(model.sas)")
                .font(.footnote)
                .foregroundStyle(.secondary)
            RemoteKeypad(model: model)
        }
        .padding()
    }
}

@MainActor
struct RemoteKeypad: View {
    @ObservedObject var model: ControllerModel

    var body: some View {
        ScrollView {
            VStack(spacing: 10) {
                keyRow([("▲", TVRC_KEY_DPAD_UP.rawValue)])
                HStack(spacing: 24) {
                    key("◀", TVRC_KEY_DPAD_LEFT.rawValue)
                    key("OK", TVRC_KEY_DPAD_CENTER.rawValue)
                    key("▶", TVRC_KEY_DPAD_RIGHT.rawValue)
                }
                keyRow([("▼", TVRC_KEY_DPAD_DOWN.rawValue)])
                HStack(spacing: 12) {
                    key("返回", TVRC_KEY_BACK.rawValue)
                    key("主页", TVRC_KEY_HOME.rawValue)
                }
                HStack(spacing: 10) {
                    key("音量+", TVRC_KEY_VOLUME_UP.rawValue)
                    key("静音", TVRC_KEY_VOLUME_MUTE.rawValue)
                    key("音量-", TVRC_KEY_VOLUME_DOWN.rawValue)
                }
                HStack(spacing: 10) {
                    key("播放/暂停", TVRC_KEY_MEDIA_PLAY_PAUSE.rawValue)
                    key("停止", TVRC_KEY_MEDIA_STOP.rawValue)
                }
            }
            .padding(.horizontal)
        }
    }

    private func keyRow(_ items: [(String, UInt32)]) -> some View {
        HStack(spacing: 24) {
            ForEach(items, id: \.1) { item in key(item.0, item.1) }
        }
    }

    private func key(_ label: String, _ keyID: UInt32) -> some View {
        Button {
            // PRESS 型短按：DOWN 后立即由 endPress 补 UP；核心侧状态机处理完整周期
        } label: {
            Text(label)
                .frame(minWidth: 64, minHeight: 40)
        }
        .buttonStyle(.bordered)
        .simultaneousGesture(
            LongPressGesture(minimumDuration: 0)
                .sequenced(before: DragGesture(minimumDistance: 0))
                .onChanged { value in
                    if case .second(true, nil) = value {
                        model.beginPress(key: keyID)
                    }
                }
                .onEnded { _ in
                    model.endPress(key: keyID)
                }
        )
        .disabled(model.keyCapabilities[keyID] != true)
    }
}
