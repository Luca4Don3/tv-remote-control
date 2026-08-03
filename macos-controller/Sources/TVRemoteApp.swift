import AppKit
import CoreVideo
import Foundation
import SwiftUI

private enum CoreResult {
    static let ok: Int32 = 0
    static let bufferTooSmall: Int32 = 4
    static let notFound: Int32 = 6
}

private enum CoreEvent {
    static let state: UInt32 = 1
    static let device: UInt32 = 2
    static let sas: UInt32 = 3
    static let capabilities: UInt32 = 4
    static let commandAck: UInt32 = 5
    static let complete: UInt32 = 6
    static let error: UInt32 = 7
    static let mediaState: UInt32 = 8
}

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
        guard result == CoreResult.ok, created != nil else {
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

    let videoView: MetalVideoView?
    private let core: CoreHandle?
    private nonisolated let mediaPipeline: NativeMediaPipeline
    private var eventTask: Task<Void, Never>?
    private var mediaTask: Task<Void, Never>?
    private var nextRequestID: UInt64 = 1
    private var presses: [UInt32: PressState] = [:]

    init() {
        let createdVideoView = try? MetalVideoView.make()
        videoView = createdVideoView
        mediaPipeline = NativeMediaPipeline { pixelBuffer in
            let transferable = SendablePixelBuffer(pixelBuffer)
            Task { @MainActor in createdVideoView?.display(transferable.value) }
        }
        core = CoreHandle()
        guard let core, let handle = core.raw, tvrc_start(handle) == CoreResult.ok else {
            status = "核心启动失败；所有操作保持禁用"
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
        presses.values.forEach { $0.repeatTask.cancel() }
    }

    func discover() {
        guard let handle = core?.raw, !busy else { return }
        let requestID = allocateRequestID()
        if tvrc_discover(handle, requestID) == CoreResult.ok {
            busy = true
            status = "正在通过局域网发现电视…"
        }
    }

    func pair() {
        guard !busy, let handle = core?.raw, setTarget(handle), pairingCode.count == 6,
              pairingCode.allSatisfy(\.isNumber) else {
            if !busy { status = "请输入电视 IP 和 6 位配对码" }
            return
        }
        let code = Array(pairingCode.utf8)
        let result = code.withUnsafeBufferPointer {
            tvrc_pair_submit(handle, allocateRequestID(), $0.baseAddress, UInt32($0.count))
        }
        if result == CoreResult.ok {
            busy = true
            pairingCode = ""
            status = "正在建立首次配对 TLS 连接…"
        }
    }

    func connect() {
        guard !busy, let handle = core?.raw else { return }
        let address = Array(target.utf8)
        let result = address.withUnsafeBufferPointer {
            tvrc_connect(handle, allocateRequestID(), $0.baseAddress, UInt32($0.count))
        }
        if result == CoreResult.ok {
            busy = true
            status = "正在校验证书 pin 并认证…"
        }
    }

    func disconnect() {
        guard let handle = core?.raw else { return }
        cancelAllPresses(sendUp: true)
        if tvrc_disconnect(handle, allocateRequestID()) == CoreResult.ok { busy = true }
    }

    func toggleMedia() {
        guard let handle = core?.raw, connected, mediaAvailable, videoView != nil else { return }
        let requestID = allocateRequestID()
        let result = mediaActive ? tvrc_media_stop(handle, requestID) : tvrc_media_start(handle, requestID)
        if result == CoreResult.ok {
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
        guard connected, keyCapabilities[key] == true, let handle = core?.raw else { return nil }
        let requestID = allocateRequestID()
        guard tvrc_send_key(handle, requestID, key, state) == CoreResult.ok else { return nil }
        return requestID
    }

    private func setTarget(_ handle: UnsafeMutableRawPointer) -> Bool {
        let address = Array(target.utf8)
        return address.withUnsafeBufferPointer {
            tvrc_target_set(handle, $0.baseAddress, UInt32($0.count)) == CoreResult.ok
        }
    }

    private func allocateRequestID() -> UInt64 {
        defer { nextRequestID = nextRequestID == UInt64.max ? 1 : nextRequestID + 1 }
        return nextRequestID
    }

    private nonisolated func pollEvents(_ core: CoreHandle) async {
        guard let corePointer = core.raw else { return }
        // tvrc_poll_event 在 payload 容量不足时返回 bufferTooSmall 且不消费事件，可安全扩容后重试
        var payload = [UInt8](repeating: 0, count: 1024)
        while !Task.isCancelled {
            var event = tvrc_event()
            tvrc_event_init(&event)
            let result = payload.withUnsafeMutableBufferPointer {
                tvrc_poll_event(corePointer, &event, $0.baseAddress, UInt32($0.count))
            }
            if result == CoreResult.ok {
                let data = Data(payload.prefix(Int(event.payload_len)))
                await self.handle(event: event, data: data)
            } else if result == CoreResult.bufferTooSmall {
                let required = Int(event.payload_len)
                if required <= maxEventPayloadBytes {
                    // 必须严格大于事件 payload 的缓冲区（required == 当前容量时 C 库仍报 bufferTooSmall），
                    // 用 required + 1 且指数增长避免死循环；min 保持 maxEventPayloadBytes 上限保护
                    payload = [UInt8](
                        repeating: 0,
                        count: min(max(required + 1, payload.count * 2), maxEventPayloadBytes)
                    )
                } else {
                    // 超出保护上限：记录错误并退避，避免 busy-loop；事件由环形队列自然淘汰
                    NSLog("pollEvents: 事件 payload 超限（%u 字节），已丢弃", event.payload_len)
                    try? await Task.sleep(for: .milliseconds(200))
                }
            } else {
                try? await Task.sleep(for: .milliseconds(result == CoreResult.notFound ? 50 : 200))
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
            if result == CoreResult.ok {
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
                try? await Task.sleep(for: .milliseconds(result == CoreResult.notFound ? 10 : 100))
            }
        }
    }

    private func handle(event: tvrc_event, data: Data) {
        let text = String(decoding: data, as: UTF8.self)
        let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        switch event.event_type {
        case CoreEvent.device:
            if let address = object?["sourceAddress"] as? String { target = address }
            status = "已发现电视：\(target)"
        case CoreEvent.sas:
            sas = object?["sas"] as? String ?? "核对码解析失败"
            status = "请与电视画面核对 SAS，并在电视端确认"
        case CoreEvent.capabilities:
            applyCapabilities(object)
        case CoreEvent.state:
            applyState(object, text: text)
        case CoreEvent.commandAck:
            // 协议保证（windows-controller/src/abi.zig）：command_ack 仅由 sendKey 请求产生（成功时无 complete/error），
            // 而 sendKey 从不设置 busy；设置 busy 的 discover/pair/connect/disconnect 请求均保证发 request_complete
            // 或 error_event 来复位 busy，因此此处不重置 busy，避免破坏正常事件流
            if event.status != CoreResult.ok,
               let entry = presses.first(where: { $0.value.downRequestID == event.request_id }) {
                entry.value.repeatTask.cancel()
                presses.removeValue(forKey: entry.key)
            }
            status = text
        case CoreEvent.mediaState:
            applyMediaState(object, text: text)
        case CoreEvent.complete, CoreEvent.error:
            busy = false
            status = text
        default:
            status = "核心返回未知事件；连接已保持保守状态"
        }
    }

    private func applyCapabilities(_ object: [String: Any]?) {
        // 按键枚举与 windows-controller/include/tv_remote_core.h 的 enum tvrc_key 及
        // Android 端 LogicalKey 一一对应（MENU=7, CHANNEL_UP=11, CHANNEL_DOWN=12, POWER=17）
        let names: [(UInt32, String)] = [
            (0, "DPAD_UP"), (1, "DPAD_DOWN"), (2, "DPAD_LEFT"), (3, "DPAD_RIGHT"),
            (4, "DPAD_CENTER"), (5, "BACK"), (6, "HOME"), (7, "MENU"),
            (8, "VOLUME_UP"), (9, "VOLUME_DOWN"), (10, "VOLUME_MUTE"),
            (11, "CHANNEL_UP"), (12, "CHANNEL_DOWN"), (13, "MEDIA_PLAY_PAUSE"),
            (14, "MEDIA_STOP"), (15, "MEDIA_NEXT"), (16, "MEDIA_PREVIOUS"), (17, "POWER"),
        ]
        let support = object?["keySupport"] as? [String: String] ?? [:]
        keyCapabilities = Dictionary(uniqueKeysWithValues: names.map { key, name in
            (key, support[name] == "SUPPORTED" || support[name] == "BEST_EFFORT")
        })
        let media = object?["mediaTransport"] as? String
        mediaAvailable = media == "SUPPORTED" || media == "PERMISSION_REQUIRED"
    }

    // 状态字段精确匹配 JSON 中的 state/status，避免 "disconnected" 误命中 "connected" 子串；
    // 状态字段缺失或未知时保持现有连接状态不变，不误判
    private func applyState(_ object: [String: Any]?, text: String) {
        let state = (object?["state"] as? String) ?? (object?["status"] as? String)
        switch state {
        case "connected":
            connected = true
            busy = false
        case "pairing", "connecting", "discovering":
            busy = true
        case "idle", "stopped", "disconnected":
            connected = false
            busy = false
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

    // 事件 payload 保护上限：超过则记录错误并丢弃（环形队列会自然淘汰该事件）
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
            .disabled(!model.connected || model.keyCapabilities[key] != true)
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
                Button("刷新", action: model.discover).disabled(model.busy || model.connected)
                Button("配对", action: model.pair).disabled(model.busy || model.connected)
                Button("连接", action: model.connect).disabled(model.busy || model.connected)
                Button("断开", action: model.disconnect).disabled(!model.connected)
                Button(model.mediaActive ? "停止画面" : "画面", action: model.toggleMedia)
                    .disabled(!model.connected || !model.mediaAvailable)
            }
            Text("SAS：\(model.sas)")
            HStack(alignment: .top, spacing: 24) {
                ZStack {
                    Color.black
                    MetalPreview(view: model.videoView)
                }.frame(minWidth: 600, minHeight: 400)
                VStack(spacing: 8) {
                    RemoteButton(model: model, title: "上", key: 0)
                    HStack { RemoteButton(model: model, title: "左", key: 2); RemoteButton(model: model, title: "确定", key: 4); RemoteButton(model: model, title: "右", key: 3) }
                    RemoteButton(model: model, title: "下", key: 1)
                    HStack { RemoteButton(model: model, title: "返回", key: 5); RemoteButton(model: model, title: "主页", key: 6) }
                    HStack { RemoteButton(model: model, title: "音量+", key: 8); RemoteButton(model: model, title: "音量-", key: 9); RemoteButton(model: model, title: "静音", key: 10) }
                    HStack { RemoteButton(model: model, title: "播放/暂停", key: 13); RemoteButton(model: model, title: "停止", key: 14) }
                    HStack { RemoteButton(model: model, title: "上一首", key: 16); RemoteButton(model: model, title: "下一首", key: 15) }
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
