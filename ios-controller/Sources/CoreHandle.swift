// swift-version 6; iOS 16+
// TV Remote Control —— Zig 协议核心句柄（主 App 与 App Clip 共享）。

import Foundation
import TvRemoteCoreZig

let coreOK = Int32(TVRC_OK.rawValue)
let coreBufferTooSmall = Int32(TVRC_BUFFER_TOO_SMALL.rawValue)
let coreNotFound = Int32(TVRC_NOT_FOUND.rawValue)

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
