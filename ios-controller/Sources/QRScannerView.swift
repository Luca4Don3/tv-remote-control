// swift-version 6; iOS 16+
// TV Remote Control —— 主 App 扫码配对（AVFoundation 二维码识别）。
// 邀请格式由 AppClipInvitation 解析（tvrc://pair 与 https clip 链接均支持）。
// 相机与权限为真机能力——模拟器无法运行，本视图代码以 typecheck/构建门禁为 CI 锚。

import AVFoundation
import SwiftUI

/// 相机扫码视图；识别到二维码后回调原文并自动结束。
struct QRScannerView: UIViewControllerRepresentable {
    let onCode: @MainActor (String) -> Void
    @Environment(\.dismiss) private var dismiss

    func makeUIViewController(context: Context) -> UIViewController {
        let controller = UIViewController()
        controller.view.backgroundColor = .black
        let session = AVCaptureSession()

        DispatchQueue.main.async {
            guard AVCaptureDevice.authorizationStatus(for: .video) == .authorized ||
                AVCaptureDevice.authorizationStatus(for: .video) == .notDetermined
            else {
                context.coordinator.postError("相机权限未授予，请在系统设置中允许")
                return
            }
            AVCaptureDevice.requestAccess(for: .video) { granted in
                guard granted else {
                    context.coordinator.postError("相机权限未授予，请在系统设置中允许")
                    return
                }
                guard let device = AVCaptureDevice.default(for: .video),
                      let input = try? AVCaptureDeviceInput(device: device),
                      session.canAddInput(input)
                else {
                    context.coordinator.postError("相机不可用")
                    return
                }
                let output = AVCaptureMetadataOutput()
                guard session.canAddOutput(output) else {
                    context.coordinator.postError("相机输出不可用")
                    return
                }
                session.addInput(input)
                session.addOutput(output)
                output.setMetadataObjectsDelegate(context.coordinator, queue: .main)
                output.metadataObjectTypes = [.qr]
                let preview = AVCaptureVideoPreviewLayer(session: session)
                preview.frame = controller.view.bounds
                preview.videoGravity = .resizeAspectFill
                controller.view.layer.addSublayer(preview)
                DispatchQueue.global(qos: .userInitiated).async { session.startRunning() }
            }
        }
        return controller
    }

    func updateUIViewController(_ controller: UIViewController, context: Context) {}

    func makeCoordinator() -> Coordinator {
        Coordinator(onCode: onCode) { [dismiss] message in
            Task { @MainActor in
                NotificationCenter.default.post(
                    name: .qrScannerError, object: nil, userInfo: ["message": message]
                )
                dismiss()
            }
        }
    }

    /// 元数据回调在 AVFoundation 队列（非主线程）——Coordinator 为 nonisolated，
    /// 回调经 DispatchQueue.main 跳回主线程执行（@MainActor 闭包）。
    final class Coordinator: NSObject, AVCaptureMetadataOutputObjectsDelegate {
        private let onCode: @MainActor (String) -> Void
        private let onError: @MainActor (String) -> Void
        private var handled = false

        init(onCode: @escaping @MainActor (String) -> Void, onError: @escaping @MainActor (String) -> Void) {
            self.onCode = onCode
            self.onError = onError
        }

        func postError(_ message: String) {
            DispatchQueue.main.async { self.onError(message) }
        }

        nonisolated func metadataOutput(
            _ output: AVCaptureMetadataOutput,
            didOutput metadataObjects: [AVMetadataObject],
            from connection: AVCaptureConnection
        ) {
            guard !handled,
                  let object = metadataObjects.first as? AVMetadataMachineReadableCodeObject,
                  object.type == .qr, let value = object.stringValue
            else { return }
            handled = true
            DispatchQueue.main.async { self.onCode(value) }
        }
    }
}

extension Notification.Name {
    static let qrScannerError = Notification.Name("tvrc.qrScannerError")
}

/// 扫码 sheet：识别成功 → 邀请解析 → host 预填（配对码仍取电视屏显 6 位码）。
@MainActor
struct QRScannerSheet: View {
    @Environment(\.dismiss) private var dismiss
    let onInvitation: (AppClipInvitation) -> Void
    @State private var errorMessage: String?

    var body: some View {
        QRScannerView { text in
            if let invitation = AppClipInvitation.parse(text) {
                onInvitation(invitation)
                dismiss()
            } else {
                errorMessage = "二维码不是有效的配对邀请"
            }
        }
        .ignoresSafeArea()
        .overlay(alignment: .top) {
            if let errorMessage {
                Text(errorMessage)
                    .padding(8)
                    .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 8))
                    .padding(.top, 60)
            }
        }
        .overlay(alignment: .bottom) {
            Text("对准电视上的配对二维码").font(.caption).foregroundStyle(.white)
                .padding(.bottom, 40)
        }
    }
}
