// swift-version 6; iOS 16+
// TV Remote Control —— iOS 手机控制端（SwiftUI 壳 + Zig 协议核心静态库）。
// 遥控按键/配对/认证走 Zig 核心（与 macOS/Windows 同一实现）；
// WS 调试通道与文本输入走 Rust XCFramework（见 WsDebugClient.swift）。

import SwiftUI
import Foundation

private let coreOK = Int32(TVRC_OK.rawValue)
private let coreBufferTooSmall = Int32(TVRC_BUFFER_TOO_SMALL.rawValue)
private let coreNotFound = Int32(TVRC_NOT_FOUND.rawValue)

final class CoreHandle: @unchecked Sendable {
    let raw: UnsafeMutableRawPointer?
    private let credentialStore = KeychainCredentialStore()

    init?() {
        var configuration = tvrc_config()
        tvrc_config_init(&configuration)
        configureMacOSCredentialCallbacks(&configuration, store: credentialStore)
        var created: UnsafeMutableRawPointer?
        let name = Array("iOS Controller".utf8)
        let result = name.withUnsafeBufferPointer { bytes -> Int32 in
            configuration.controller_name = bytes.baseAddress
            configuration.controller_name_len = UInt32(bytes.count)
            return tvrc_create(&configuration, &created)
        }
        guard result == coreOK, created != nil else {
            // passRetained 传给 C 回调上下文的强引用在 failable init 失败路径手动 release 平衡
            Unmanaged.passUnretained(credentialStore).release()
            return nil
        }
        raw = created
    }

    deinit {
        if let raw {
            _ = tvrc_stop(raw)
            tvrc_destroy(raw)
        }
        Unmanaged<KeychainCredentialStore>.passUnretained(credentialStore).release()
    }
}

private struct PressState {
    let downRequestID: UInt64
    let repeatTask: Task<Void, Never>
}

@MainActor
final class ControllerModel: ObservableObject {
    @Published var status = "核心正在启动"
    @Published var target = ""
    @Published var pairingCode = ""
    @Published var sas = "尚未配对"
    @Published var connected = false
    @Published var busy = false
    @Published var keyCapabilities: [UInt32: Bool] = [:]
    @Published private(set) var controlAvailable = true
    private let core: CoreHandle?
    private var eventTask: Task<Void, Never>?
    private var nextRequestID: UInt64 = 1
    private var presses: [UInt32: PressState] = [:]
    private var pendingOperation: PendingOperation?
    private var operationTimeoutTask: Task<Void, Never>?

    init() {
        core = CoreHandle()
        guard let core, let handle = core.raw, tvrc_start(handle) == coreOK else {
            status = "核心启动失败；所有操作保持禁用"
            controlAvailable = false
            return
        }
        status = "可输入电视 IP 与配对码"
        eventTask = Task.detached(priority: .utility) { [weak self, core] in
            await self?.pollEvents(core)
        }
    }

    deinit {
        eventTask?.cancel()
        operationTimeoutTask?.cancel()
        presses.values.forEach { $0.repeatTask.cancel() }
    }

    // MARK: - 连接操作（Zig 核心）

    func pair() {
        guard controlAvailable, !busy, let handle = core?.raw, setTarget(handle), pairingCode.count == 6,
              pairingCode.allSatisfy(\.isNumber) else {
            if !busy { status = "请输入电视 IP 和 6 位配对码" }
            return
        }
        let code = Array(pairingCode.utf8)
        let requestID = allocateRequestID()
        let result = code.withUnsafeBufferPointer {
            tvrc_pair_submit(handle, requestID, $0.baseAddress, UInt32($0.count))
        }
        if result == coreOK {
            beginOperation(requestID: requestID, kind: .pairing)
            pairingCode = ""
            status = "正在建立首次配对 TLS 连接…"
        }
    }

    func connect() {
        guard controlAvailable, !busy, let handle = core?.raw else { return }
        let requestID = allocateRequestID()
        let address = Array(target.utf8)
        let result = address.withUnsafeBufferPointer {
            tvrc_connect(handle, requestID, $0.baseAddress, UInt32($0.count))
        }
        if result == coreOK {
            beginOperation(requestID: requestID, kind: .connection)
            status = "正在校验证书 pin 并认证…"
        }
    }

    func disconnect() {
        guard controlAvailable, !busy, let handle = core?.raw else { return }
        cancelAllPresses(sendUp: true)
        let requestID = allocateRequestID()
        if tvrc_disconnect(handle, requestID) == coreOK {
            beginOperation(requestID: requestID, kind: .disconnection)
            status = "正在断开连接…"
        }
    }

    // MARK: - 按键

    func beginPress(key: UInt32) {
        guard presses[key] == nil, let downRequest = sendKey(key: key, state: UInt32(TVRC_KEY_DOWN.rawValue)) else { return }
        let repeatTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(400))
            while !Task.isCancelled {
                _ = self?.sendKey(key: key, state: UInt32(TVRC_KEY_REPEAT.rawValue))
                try? await Task.sleep(for: .milliseconds(120))
            }
        }
        presses[key] = PressState(downRequestID: downRequest, repeatTask: repeatTask)
    }

    func endPress(key: UInt32) {
        guard let state = presses.removeValue(forKey: key) else { return }
        state.repeatTask.cancel()
        if sendKey(key: key, state: UInt32(TVRC_KEY_UP.rawValue)) == nil {
            NSLog("endPress: KEY_UP 发送失败，按键 %u 可能卡键", key)
        }
    }

    private func sendKey(key: UInt32, state: UInt32) -> UInt64? {
        guard controlAvailable, connected, keyCapabilities[key] == true, let handle = core?.raw else { return nil }
        let requestID = allocateRequestID()
        guard tvrc_send_key(handle, requestID, key, state) == coreOK else { return nil }
        return requestID
    }

    private func setTarget(_ handle: UnsafeMutableRawPointer) -> Bool {
        let address = Array(target.utf8)
        return address.withUnsafeBufferPointer {
            tvrc_target_set(handle, $0.baseAddress, UInt32($0.count)) == coreOK
        }
    }

    private func allocateRequestID() -> UInt64 {
        defer { nextRequestID = nextRequestID == UInt64.max ? 1 : nextRequestID + 1 }
        return nextRequestID
    }

    private func beginOperation(requestID: UInt64, kind: PendingOperationKind) {
        operationTimeoutTask?.cancel()
        let operation = PendingOperation(requestID: requestID, kind: kind)
        pendingOperation = operation
        busy = true
        operationTimeoutTask = Task { [weak self] in
            do {
                try await Task.sleep(for: operation.kind.timeout)
            } catch {
                return
            }
            guard let self, self.pendingOperation == operation else { return }
            self.pendingOperation = nil
            self.busy = false
            self.status = "\(operation.kind.timeoutDescription)；控制已恢复，可重试"
        }
    }

    private func finishOperation(requestID: UInt64, event: OperationTerminalEvent) -> Bool {
        guard let operation = pendingOperation, operation.accepts(requestID: requestID, event: event) else {
            return false
        }
        operationTimeoutTask?.cancel()
        operationTimeoutTask = nil
        pendingOperation = nil
        busy = false
        return true
    }

    private func disableControlForABIError(payloadLength: UInt32) {
        operationTimeoutTask?.cancel()
        operationTimeoutTask = nil
        pendingOperation = nil
        busy = false
        connected = false
        controlAvailable = false
        cancelAllPresses(sendUp: false)
        status = "核心事件超过 1 MiB，违反 ABI；控制已禁用，请重启应用"
        NSLog("pollEvents: ABI 违约，事件 payload 为 %u 字节；已停止轮询", payloadLength)
    }

    // MARK: - 事件轮询

    private nonisolated func pollEvents(_ core: CoreHandle) async {
        guard let corePointer = core.raw else { return }
        var payload = [UInt8](repeating: 0, count: 1024)
        var ordinaryErrorBackoff = EventPollBackoff()
        while !Task.isCancelled {
            var event = tvrc_event()
            tvrc_event_init(&event)
            let result = payload.withUnsafeMutableBufferPointer {
                tvrc_poll_event(corePointer, &event, $0.baseAddress, UInt32($0.count))
            }
            if result == coreOK {
                ordinaryErrorBackoff.reset()
                let data = Data(payload.prefix(Int(event.payload_len)))
                await self.handle(event: event, data: data)
            } else if result == coreBufferTooSmall {
                let required = Int(event.payload_len)
                switch eventPayloadDecision(
                    required: required,
                    currentCapacity: payload.count,
                    maximumPayloadBytes: maxEventPayloadBytes
                ) {
                case .resize(let capacity):
                    payload = [UInt8](repeating: 0, count: capacity)
                case .abiViolation:
                    await disableControlForABIError(payloadLength: event.payload_len)
                    return
                }
            } else if result == coreNotFound {
                ordinaryErrorBackoff.reset()
                try? await Task.sleep(for: .milliseconds(50))
            } else {
                let delayMilliseconds = ordinaryErrorBackoff.nextDelayMilliseconds()
                NSLog("pollEvents: tvrc_poll_event 失败（%d），%d ms 后重试", result, delayMilliseconds)
                try? await Task.sleep(for: .milliseconds(delayMilliseconds))
            }
        }
    }

    private func handle(event: tvrc_event, data: Data) {
        let text = String(decoding: data, as: UTF8.self)
        let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        switch event.event_type {
        case UInt32(TVRC_EVENT_DEVICE_FOUND.rawValue):
            if let address = object?["sourceAddress"] as? String { target = address }
            status = "已发现电视：\(target)"
        case UInt32(TVRC_EVENT_PAIRING_SAS.rawValue):
            sas = object?["sas"] as? String ?? "核对码解析失败"
            status = "请与电视画面核对 SAS，并在电视端确认"
        case UInt32(TVRC_EVENT_CAPABILITIES_CHANGED.rawValue):
            applyCapabilities(object)
        case UInt32(TVRC_EVENT_STATE_CHANGED.rawValue):
            applyState(object, text: text)
        case UInt32(TVRC_EVENT_COMMAND_ACK.rawValue):
            _ = finishOperation(requestID: event.request_id, event: .commandAck)
            if event.status != coreOK,
               let entry = presses.first(where: { $0.value.downRequestID == event.request_id }) {
                entry.value.repeatTask.cancel()
                presses.removeValue(forKey: entry.key)
            }
            status = text
        case UInt32(TVRC_EVENT_REQUEST_COMPLETE.rawValue):
            if finishOperation(requestID: event.request_id, event: .requestComplete) { status = text }
        case UInt32(TVRC_EVENT_ERROR.rawValue):
            if finishOperation(requestID: event.request_id, event: .error) { status = text }
        default:
            break
        }
    }

    private func applyCapabilities(_ object: [String: Any]?) {
        let names: [(UInt32, CapabilityName)] = [
            (UInt32(TVRC_KEY_DPAD_UP.rawValue), .dpadUp),
            (UInt32(TVRC_KEY_DPAD_DOWN.rawValue), .dpadDown),
            (UInt32(TVRC_KEY_DPAD_LEFT.rawValue), .dpadLeft),
            (UInt32(TVRC_KEY_DPAD_RIGHT.rawValue), .dpadRight),
            (UInt32(TVRC_KEY_DPAD_CENTER.rawValue), .dpadCenter),
            (UInt32(TVRC_KEY_BACK.rawValue), .back),
            (UInt32(TVRC_KEY_HOME.rawValue), .home),
            (UInt32(TVRC_KEY_MENU.rawValue), .menu),
            (UInt32(TVRC_KEY_VOLUME_UP.rawValue), .volumeUp),
            (UInt32(TVRC_KEY_VOLUME_DOWN.rawValue), .volumeDown),
            (UInt32(TVRC_KEY_VOLUME_MUTE.rawValue), .volumeMute),
            (UInt32(TVRC_KEY_CHANNEL_UP.rawValue), .channelUp),
            (UInt32(TVRC_KEY_CHANNEL_DOWN.rawValue), .channelDown),
            (UInt32(TVRC_KEY_MEDIA_PLAY_PAUSE.rawValue), .mediaPlayPause),
            (UInt32(TVRC_KEY_MEDIA_STOP.rawValue), .mediaStop),
            (UInt32(TVRC_KEY_MEDIA_NEXT.rawValue), .mediaNext),
            (UInt32(TVRC_KEY_MEDIA_PREVIOUS.rawValue), .mediaPrevious),
            (UInt32(TVRC_KEY_POWER.rawValue), .power),
        ]
        let support = object?["keySupport"] as? [String: String] ?? [:]
        keyCapabilities = Dictionary(uniqueKeysWithValues: names.map { key, name in
            (key, CapabilitySupport(rawValue: support[name.rawValue] ?? "")?.enablesControl == true)
        })
    }

    private func applyState(_ object: [String: Any]?, text: String) {
        let state = (object?["state"] as? String) ?? (object?["status"] as? String)
        switch state {
        case "connected":
            connected = true
        case "pairing", "connecting", "discovering":
            break
        case "idle", "stopped", "disconnected":
            connected = false
            cancelAllPresses(sendUp: false)
        default:
            break
        }
        status = text
    }

    private func cancelAllPresses(sendUp: Bool) {
        let keys = Array(presses.keys)
        for key in keys {
            guard let state = presses.removeValue(forKey: key) else { continue }
            state.repeatTask.cancel()
            if sendUp { _ = sendKey(key: key, state: UInt32(TVRC_KEY_UP.rawValue)) }
        }
    }

    private let maxEventPayloadBytes = 1 << 20
}

extension ControllerModel: @unchecked Sendable {}

// MARK: - SwiftUI UI

@main
struct TVRemoteApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}

struct ContentView: View {
    @StateObject private var model = ControllerModel()

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
