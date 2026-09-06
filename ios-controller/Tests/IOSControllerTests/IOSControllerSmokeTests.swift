import XCTest
import Foundation
import tvremote_coreFFI
import TvRemoteCoreZig
@testable import IOSController

/// iOS 运行级冒烟（iOS 模拟器真跑，CI 门禁）。
/// 此前 iOS 只有 typecheck + 链接门禁；本套件把「Swift 层在真实执行时行为正确」
/// 变成 CI 每次必验的证据：Zig 核心 ABI 调用、Rust FFI 加密/帧编码、
/// Keychain 存储、WS JSON 转义。UI/网络行为仍属 UNVERIFIED（无真机）。
final class IOSControllerSmokeTests: XCTestCase {

    // MARK: - Zig 协议核心（aarch64-ios-simulator 静态库真跑）

    func testZigCoreConfigInitAbiVersion() {
        var configuration = tvrc_config()
        tvrc_config_init(&configuration)
        XCTAssertEqual(configuration.abi_version, UInt32(1), "Zig 核心 tvrc_config_init 必须回填 ABI v1")
        XCTAssertEqual(Int32(TVRC_OK.rawValue), 0, "TVRC_OK 必须为 0")
    }

    func testZigCoreEventInitAbiVersion() {
        var event = tvrc_event()
        tvrc_event_init(&event)
        XCTAssertEqual(event.abi_version, UInt32(1), "Zig 核心 tvrc_event_init 必须回填 ABI v1")
    }

    func testZigCoreHandleCreation() {
        // 完整链路：Keychain 回调注入 + tvrc_create 真实创建核心句柄
        guard CoreHandle() != nil else {
            XCTFail("tvrc_create 必须在模拟器上成功（Keychain 可用）")
            return
        }
    }

    // MARK: - Rust 核心（uniffi Swift 绑定真跑）

    func testRustWsCodecMaskedFrameLayout() throws {
        let codec = WsCodec()
        let payload = Data("mask check".utf8)
        let frame = try codec.encodeClient(opcode: 1, payload: payload)
        // RFC 6455 客户端帧：FIN+文本(0x81)，掩码位必须置 1，掩码 4B + XOR 载荷
        XCTAssertEqual(frame[0], 0x81, "FIN + TEXT opcode")
        let maskedBit = frame[1] & 0x80
        XCTAssertEqual(maskedBit, 0x80, "客户端帧必须带掩码位（服务端硬校验）")
        let payloadLen = Int(frame[1] & 0x7F)
        XCTAssertEqual(payloadLen, payload.count)
        let maskKey = Array(frame[2 ..< 6])
        let maskedPayload = Array(frame[6 ..< 6 + payload.count])
        let unmasked = maskedPayload.enumerated().map { $0.element ^ maskKey[$0.offset % 4] }
        XCTAssertEqual(Data(unmasked), payload, "掩码 XOR 必须可还原原文")
    }

    func testRustSessionCryptoSealOpenRoundtrip() throws {
        let clientRandom = Self.randomBytes(32)
        let serverRandom = Self.randomBytes(32)
        let psk = Self.randomBytes(32)
        let client = try SessionCrypto(
            psk: psk, clientRandom: clientRandom, serverRandom: serverRandom,
            isClient: true, replayWindowBits: 64
        )
        let server = try SessionCrypto(
            psk: psk, clientRandom: clientRandom, serverRandom: serverRandom,
            isClient: false, replayWindowBits: 64
        )
        let plaintext = Data("tvrc rust ffi roundtrip".utf8)
        let sealed = try client.seal(isClient: true, plaintext: plaintext, aad: Data())
        // 加密信封 counter 从 1 起（0 为防重放哨兵）；前 8B 大端（与 Kotlin readCounter 同构）
        let counter: UInt64 = sealed.prefix(8).reduce(0) { ($0 << 8) | UInt64($1) }
        XCTAssertEqual(counter, UInt64(1), "加密信封 counter 必须从 1 开始")
        let opened = try server.open(isClient: false, ciphertext: sealed, counter: counter, aad: Data())
        XCTAssertEqual(opened, plaintext)
        // 同一信封 counter 再次校验必须被防重放窗口拒绝
        XCTAssertFalse(server.checkSequence(sequence: counter), "重放 counter 必须被拒绝")
        XCTAssertTrue(server.checkSequence(sequence: counter + 1))
    }

    // MARK: - Keychain（模拟器运行级）

    func testKeychainCredentialRoundtrip() throws {
        let store = KeychainCredentialStore()
        let credentialID = Data("xctest-credential-\(UUID().uuidString)".utf8)
        let value = Self.randomBytes(32)
        try store.putBlob(credentialID: credentialID, value: value)
        let restored = try store.getBlob(credentialID: credentialID)
        XCTAssertEqual(restored, value, "Keychain 写读必须一致（模拟器）")
        try store.removeBlob(credentialID: credentialID)
        XCTAssertNil(try store.getBlob(credentialID: credentialID), "移除后读取返回空")
    }

    // MARK: - WS JSON 转义（对齐 Kotlin StrictJson.appendQuoted）

    func testWsJsonEscaping() {
        XCTAssertEqual(WsJsonEscaping.escape("plain"), "plain")
        XCTAssertEqual(WsJsonEscaping.escape("a\"b"), "a\\\"b")
        XCTAssertEqual(WsJsonEscaping.escape("a\\b"), "a\\\\b")
        XCTAssertEqual(WsJsonEscaping.escape("line\nbreak"), "line\\nbreak")
        XCTAssertEqual(WsJsonEscaping.escape("tab\there"), "tab\\there")
        XCTAssertEqual(WsJsonEscaping.escape("\u{01}ctl"), "\\u0001ctl")
        // 中文按 UTF-8 原样保留（StrictJson 语义）
        XCTAssertEqual(WsJsonEscaping.escape("中文"), "中文")
        // 转义结果必须是合法 JSON 字符串值
        let original = "q\"s\\t\nu\u{08}v\u{0C}w\r\tx"
        let data = Data("{\"text\":\"\(WsJsonEscaping.escape(original))\"}".utf8)
        let object = try? JSONSerialization.jsonObject(with: data) as? [String: String]
        XCTAssertEqual(object?["text"], original, "转义后必须能被标准 JSON 解码还原")
    }

    private static func randomBytes(_ count: Int) -> Data {
        var bytes = [UInt8](repeating: 0, count: count)
        _ = SecRandomCopyBytes(kSecRandomDefault, count, &bytes)
        return Data(bytes)
    }
}
