import AppKit
import CoreVideo
import Foundation
import SwiftUI

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
        let name = Array("macOS Controller".utf8)
        let result = name.withUnsafeBufferPointer { bytes -> Int32 in
            configuration.controller_name = bytes.baseAddress
            configuration.controller_name_len = UInt32(bytes.count)
            return tvrc_create(&configuration, &created)
        }
        guard result == coreOK, created != nil else {
            // configureMacOSCredentialCallbacks 已 passRetained 把 store 强引用传给 C 回调上下文；
            // failable initializer 失败路径不会调用 deinit，必须手动 release 平衡引用避免泄漏。
            // 成功路径由 deinit 中的 passUnretained().release() 负责，此处不变。
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
        // 释放 configureMacOSCredentialCallbacks 中 passRetained 传给 C 回调上下文的强引用；
        // 必须先于 tvrc_destroy 之后执行，确保回调生命周期内 store 存活
        Unmanaged<KeychainCredentialStore>.passUnretained(credentialStore).release()
    }
}

private struct PressState {
    let downRequestID: UInt64
    let repeatTask: Task<Void, Never>
}

private final class SendablePixelBuffer: @unchecked Sendable {
    let value: CVPixelBuffer
    init(_ value: CVPixelBuffer) { self.value = value }
}

@MainActor
final class ControllerModel: ObservableObject {
    @Published var status = "核心正在启动"
    @Published var target = ""
    @Published var pairingCode = ""
    @Published var sas = "尚未配对"
    @Published var connected = false
    @Published var busy = false
    @Published var mediaAvailable = false
    @Published var mediaActive = false
    @Published var keyCapabilities: [UInt32: Bool] = [:]
    @Published private(set) var controlAvailable = true

    let videoView: MetalVideoView?
    private let core: CoreHandle?
    private nonisolated let mediaPipeline: NativeMediaPipeline
    private var eventTask: Task<Void, Never>?
    private var mediaTask: Task<Void, Never>?
    private var nextRequestID: UInt64 = 1
    private var presses: [UInt32: PressState] = [:]
    private var pendingOperation: PendingOperation?
    private var operationTimeoutTask: Task<Void, Never>?

    init() {
        let createdVideoView = try? MetalVideoView.make()
        videoView = createdVideoView
        mediaPipeline = NativeMediaPipeline { pixelBuffer in
            let transferable = SendablePixelBuffer(pixelBuffer)
            Task { @MainActor in createdVideoView?.display(transferable.value) }
        }
        core = CoreHandle()
        guard let core, let handle = core.raw, tvrc_start(handle) == coreOK else {
            status = "核心启动失败；所有操作保持禁用"
            controlAvailable = false
            return
        }
        status = "可刷新发现电视，或输入电视 IP"
        eventTask = Task.detached(priority: .utility) { [weak self, core] in
            await self?.pollEvents(core)
        }
        // 视频视图创建失败时（Metal 不可用）不启动媒体拉取，避免解码空转浪费 CPU/GPU
        if createdVideoView != nil {
            mediaTask = Task.detached(priority: .utility) { [weak self, core] in
                await self?.pollMedia(core)
            }
        }
    }

    deinit {
        eventTask?.cancel()
        mediaTask?.cancel()
        operationTimeoutTask?.cancel()
        presses.values.forEach { $0.repeatTask.cancel() }
    }

    func discover() {
        guard controlAvailable, let handle = core?.raw, !busy else { return }
        let requestID = allocateRequestID()
        if tvrc_discover(handle, requestID) == coreOK {
            beginOperation(requestID: requestID, kind: .discovery)
            status = "正在通过局域网发现电视…"
        }
    }

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

    func toggleMedia() {
        guard controlAvailable, let handle = core?.raw, connected, mediaAvailable, videoView != nil else { return }
        let requestID = allocateRequestID()
        let result = mediaActive ? tvrc_media_stop(handle, requestID) : tvrc_media_start(handle, requestID)
        if result == coreOK {
            status = mediaActive ? "正在停止画面与音频…" : "正在请求电视端屏幕共享授权…"
        }
    }

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
            // KEY_UP 发送失败可能造成电视端卡键；断线/断开时 cancelAllPresses(sendUp: true) 会兜底补发 UP
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

    private nonisolated func pollEvents(_ core: CoreHandle) async {
        guard let corePointer = core.raw else { return }
        // tvrc_poll_event 在 payload 容量不足时返回 bufferTooSmall 且不消费事件，可安全扩容后重试
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
                    // 必须严格大于事件 payload 的缓冲区（required == 当前容量时 C 库仍报 bufferTooSmall），
                    // 用 required + 1 且指数增长避免死循环；min 保持 maxEventPayloadBytes 上限保护
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

    private nonisolated func pollMedia(_ core: CoreHandle) async {
        guard let handle = core.raw else { return }
        var payload = [UInt8](repeating: 0, count: 4 * 1024 * 1024)
        while !Task.isCancelled {
            var packet = tvrc_media_packet()
            tvrc_media_packet_init(&packet)
            let result = payload.withUnsafeMutableBufferPointer {
                tvrc_media_read(handle, &packet, $0.baseAddress, UInt32($0.count))
            }
            if result == coreOK {
                let data = Data(payload.prefix(Int(packet.payload_len)))
                do {
                    try mediaPipeline.consume(
                        track: packet.track,
                        flags: packet.flags,
                        configurationID: packet.codec_config_id,
                        presentationTimeUs: packet.presentation_time_us,
                        payload: data
                    )
                } catch {
                    if packet.track == 2 {
                        mediaPipeline.audio.reset()
                        await setStatus("系统音频不可用；继续显示静音画面")
                    } else {
                        mediaPipeline.video.reset()
                        await setStatus("视频解码失败；基础遥控保持可用")
                    }
                }
            } else {
                try? await Task.sleep(for: .milliseconds(result == coreNotFound ? 10 : 100))
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
        case UInt32(TVRC_EVENT_MEDIA_STATE_CHANGED.rawValue):
            applyMediaState(object, text: text)
        case UInt32(TVRC_EVENT_REQUEST_COMPLETE.rawValue):
            if finishOperation(requestID: event.request_id, event: .requestComplete) { status = text }
        case UInt32(TVRC_EVENT_ERROR.rawValue):
            if finishOperation(requestID: event.request_id, event: .error) { status = text }
        default:
            status = "核心返回未知事件；连接已保持保守状态"
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
        mediaAvailable = CapabilitySupport(rawValue: object?["mediaTransport"] as? String ?? "")?.enablesMedia == true
    }

    // 状态字段精确匹配 JSON 中的 state/status，避免 "disconnected" 误命中 "connected" 子串；
    // 状态字段缺失或未知时保持现有连接状态不变，不误判
    private func applyState(_ object: [String: Any]?, text: String) {
        let state = (object?["state"] as? String) ?? (object?["status"] as? String)
        switch state {
        case "connected":
            connected = true
        case "pairing", "connecting", "discovering":
            break
        case "idle", "stopped", "disconnected":
            connected = false
            mediaActive = false
            cancelAllPresses(sendUp: false)
            mediaPipeline.reset()
            videoView?.clear()
        default:
            break
        }
        status = text
    }

    private func applyMediaState(_ object: [String: Any]?, text: String) {
        let state = (object?["state"] as? String) ?? (object?["status"] as? String)
        switch state {
        case "streaming", "video_only", "waiting_tv_authorization":
            mediaActive = true
        case "stopped", "failed", "unsupported":
            mediaActive = false
            mediaPipeline.reset()
            videoView?.clear()
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

    // 事件 payload 保护上限：超过即视为 ABI 违约并停止轮询。
    private let maxEventPayloadBytes = 1 << 20

    private func setStatus(_ value: String) { status = value }
}

extension ControllerModel: @unchecked Sendable {}

struct MetalPreview: NSViewRepresentable {
    let view: MetalVideoView?

    func makeNSView(context: Context) -> NSView {
        view ?? NSTextField(labelWithString: "Metal 不可用")
    }

    func updateNSView(_ nsView: NSView, context: Context) {}
}

struct RemoteButton: View {
    @ObservedObject var model: ControllerModel
    let title: String
    let key: UInt32

    var body: some View {
        Button(title) {}
            .buttonStyle(.bordered)
            .disabled(!model.controlAvailable || !model.connected || model.keyCapabilities[key] != true)
            // 用 onLongPressGesture(minimumDuration: 0) 的 pressing 回调保证松手必发 UP，
            // 避免 DragGesture 在按钮外结束或窗口失焦时丢失 onEnded 导致卡键
            .onLongPressGesture(
                minimumDuration: 0,
                maximumDistance: .infinity,
                pressing: { isPressing in
                    if isPressing { model.beginPress(key: key) } else { model.endPress(key: key) }
                },
                perform: {}
            )
    }
}

struct ContentView: View {
    @StateObject private var model = ControllerModel()

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(model.status).foregroundStyle(.secondary).lineLimit(2)
            HStack {
                TextField("电视 IP", text: $model.target).frame(width: 220)
                TextField("6 位配对码", text: $model.pairingCode).frame(width: 120)
                Button("刷新", action: model.discover).disabled(!model.controlAvailable || model.busy || model.connected)
                Button("配对", action: model.pair).disabled(!model.controlAvailable || model.busy || model.connected)
                Button("连接", action: model.connect).disabled(!model.controlAvailable || model.busy || model.connected)
                Button("断开", action: model.disconnect).disabled(!model.controlAvailable || !model.connected || model.busy)
                Button(model.mediaActive ? "停止画面" : "画面", action: model.toggleMedia)
                    .disabled(!model.controlAvailable || !model.connected || !model.mediaAvailable)
            }
            Text("SAS：\(model.sas)")
            HStack(alignment: .top, spacing: 24) {
                ZStack {
                    Color.black
                    MetalPreview(view: model.videoView)
                }.frame(minWidth: 600, minHeight: 400)
                VStack(spacing: 8) {
                    RemoteButton(model: model, title: "上", key: UInt32(TVRC_KEY_DPAD_UP.rawValue))
                    HStack { RemoteButton(model: model, title: "左", key: UInt32(TVRC_KEY_DPAD_LEFT.rawValue)); RemoteButton(model: model, title: "确定", key: UInt32(TVRC_KEY_DPAD_CENTER.rawValue)); RemoteButton(model: model, title: "右", key: UInt32(TVRC_KEY_DPAD_RIGHT.rawValue)) }
                    RemoteButton(model: model, title: "下", key: UInt32(TVRC_KEY_DPAD_DOWN.rawValue))
                    HStack { RemoteButton(model: model, title: "返回", key: UInt32(TVRC_KEY_BACK.rawValue)); RemoteButton(model: model, title: "主页", key: UInt32(TVRC_KEY_HOME.rawValue)) }
                    HStack { RemoteButton(model: model, title: "音量+", key: UInt32(TVRC_KEY_VOLUME_UP.rawValue)); RemoteButton(model: model, title: "音量-", key: UInt32(TVRC_KEY_VOLUME_DOWN.rawValue)); RemoteButton(model: model, title: "静音", key: UInt32(TVRC_KEY_VOLUME_MUTE.rawValue)) }
                    HStack { RemoteButton(model: model, title: "播放/暂停", key: UInt32(TVRC_KEY_MEDIA_PLAY_PAUSE.rawValue)); RemoteButton(model: model, title: "停止", key: UInt32(TVRC_KEY_MEDIA_STOP.rawValue)) }
                    HStack { RemoteButton(model: model, title: "上一首", key: UInt32(TVRC_KEY_MEDIA_PREVIOUS.rawValue)); RemoteButton(model: model, title: "下一首", key: UInt32(TVRC_KEY_MEDIA_NEXT.rawValue)) }
                }.frame(width: 300)
            }
        }
        .padding(20)
        .frame(minWidth: 980, minHeight: 600)
    }
}

@main
struct TVRemoteApp: App {
    var body: some Scene {
        WindowGroup("TV Remote Control") { ContentView() }
            .defaultSize(width: 1180, height: 760)
    }
}
