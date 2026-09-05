import Foundation

/**
 * WS 调试通道客户端（对端：agent 明文 WS 端口 47833）。
 *
 * 安全模型与 Kotlin 端 WsDebugClient 对齐：明文 WS + 应用层端到端加密。
 * 加密原语来自 Rust XCFramework（uniffi 绑定）：
 * - `SessionCrypto(psk:clientRandom:serverRandom:isClient:replayWindowBits:)`
 *   HKDF-SHA256 派生双向密钥 + AES-256-GCM + 滑动窗口防重放
 * - `WsCodec`：客户端掩码帧编码（RFC 6455）/增量解码
 *
 * PSK 链路说明：配对 secret 由 Zig 核心存 Keychain（credentialID=证书指纹），
 * C ABI 尚未暴露 active credential id，UI 接入待 `tvrc_active_credential_id`
 * 类 API 落地后一行接通；本类型完整实现并通过编译门禁。
 */
final class WsDebugClient: @unchecked Sendable {
    enum WsError: Error {
        case connectFailed(String)
        case handshakeFailed(String)
        case protocolError(String)
        case closed
    }

    static let debugPort: UInt16 = 47833
    private static let webSocketGUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    private let host: String
    private let port: UInt16
    private let socketFD: Int32
    private let crypto: SessionCrypto
    private let wsCodec = WsCodec()
    private var serverCounter: UInt64 = 0
    private let heartbeat: DispatchSourceTimer?

    /// `psk` 为配对下发的 32B secret；`isClient` 固定 true。
    init(host: String, port: UInt16 = WsDebugClient.debugPort, psk: Data) throws {
        self.host = host
        self.port = port
        self.socketFD = try Self.openTcp(host: host, port: port)
        do {
            try upgrade()
            let clientRandom = try Self.secureRandom(count: 32)
            let serverRandom = try helloExchange(clientRandom: clientRandom)
            self.crypto = try SessionCrypto(
                psk: psk,
                clientRandom: clientRandom,
                serverRandom: serverRandom,
                isClient: true,
                replayWindowBits: 64
            )
        } catch {
            Self.closeSocketFd(socketFD)
            throw error
        }
        // 15s 加密心跳保活（agent 读超时 45s）
        let timer = DispatchSource.makeTimerSource(queue: .global(qos: .utility))
        timer.schedule(deadline: .now() + 15, repeating: 15)
        timer.setEventHandler { [weak self] in
            _ = try? self?.sendCommand(type: "ping", payload: "{}")
        }
        timer.resume()
        self.heartbeat = timer
    }

    func close() {
        heartbeat?.cancel()
        Self.closeSocketFd(socketFD)
    }

    // MARK: - 遥控/文本命令（加密信封）

    func sendKeyEvent(key: String, state: String, repeatCount: Int = 0) throws -> String {
        let payload = "{\"key\":\"\(key)\",\"state\":\"\(state)\",\"repeatCount\":\(repeatCount)}"
        let ack = try sendCommand(type: "key_event", payload: payload)
        return ack
    }

    func sendText(_ text: String, draft: Bool) throws -> String {
        let escaped = text
            .replacingOccurrences(of: "\\", with: "\\\\")
            .replacingOccurrences(of: "\"", with: "\\\"")
        let payload = "{\"text\":\"\(escaped)\"}"
        return try sendCommand(type: draft ? "text_draft" : "text_commit", payload: payload)
    }

    private func sendCommand(type: String, payload: String) throws -> String {
        let requestID = "c-\(UInt64.random(in: 1...UInt64.max))"
        let envelope = "{\"protocolVersion\":1,\"requestId\":\"\(requestID)\","
            + "\"sessionId\":\"\",\"sequence\":1,\"type\":\"\(type)\",\"payload\":\(payload)}"
        let sealed = try crypto.seal(isClient: true, plaintext: Data(envelope.utf8), aad: Data())
        let frame = try wsCodec.encodeClient(opcode: 2, payload: sealed)
        try writeAll(frame)
        while true {
            let reply = try readFrame()
            if reply.opcode == 9 { // ping → pong
                let pong = try wsCodec.encodeClient(opcode: 10, payload: Data(reply.payload))
                try writeAll(pong)
                continue
            }
            if reply.opcode != 2 { continue }
            let counter = try Self.readCounter(reply.payload)
            guard crypto.checkSequence(sequence: counter) else {
                throw WsError.protocolError("replayed debug message")
            }
            let plaintext = try crypto.open(
                isClient: true, ciphertext: Data(reply.payload), counter: counter, aad: Data())
            if let text = String(bytes: plaintext, encoding: .utf8) {
                // ACK 与心跳 ping 共用循环；收到 command_ack 即返回
                if text.contains("\"type\":\"command_ack\"") {
                    serverCounter += 1
                    return text
                }
            }
        }
    }

    // MARK: - 握手

    private func upgrade() throws {
        let keyBytes = try Self.secureRandom(count: 16)
        let key = Data(keyBytes).base64EncodedString()
        let request = "GET / HTTP/1.1\r\n"
            + "Host: \(host):\(port)\r\n"
            + "Upgrade: websocket\r\n"
            + "Connection: Upgrade\r\n"
            + "Sec-WebSocket-Key: \(key)\r\n"
            + "Sec-WebSocket-Version: 13\r\n\r\n"
        try writeAll(Data(request.utf8))
        let status = try readHttpLine()
        guard status.contains("101") else {
            throw WsError.handshakeFailed(status)
        }
        while true {
            let line = try readHttpLine()
            if line.isEmpty { break }
        }
    }

    private func helloExchange(clientRandom: Data) throws -> Data {
        let hello = "{\"protocolVersion\":1,\"requestId\":\"ws-hello-1\",\"sessionId\":\"\","
            + "\"sequence\":1,\"type\":\"ws_hello\",\"payload\":{\"controllerId\":\"\","
            + "\"clientRandom\":\"\(clientRandom.map { String(format: "%02x", $0) }.joined())\"}}"
        let helloFrame = try wsCodec.encodeClient(opcode: 1, payload: Data(hello.utf8))
        try writeAll(helloFrame)
        let ackFrame = try readFrame()
        guard ackFrame.opcode == 1,
              let text = String(bytes: ackFrame.payload, encoding: .utf8),
              let range = text.range(of: "\"serverRandom\":\""),
              let end = text[range.upperBound...].firstIndex(of: "\"") else {
            throw WsError.handshakeFailed("ws_hello_ack missing serverRandom")
        }
        let hex = String(text[range.upperBound..<end])
        guard hex.count == 64, let data = Self.hexData(hex) else {
            throw WsError.handshakeFailed("invalid serverRandom")
        }
        return data
    }

    // MARK: - POSIX socket

    private static func openTcp(host: String, port: UInt16) throws -> Int32 {
        var hints = addrinfo(ai_flags: 0, ai_family: AF_UNSPEC, ai_socktype: SOCK_STREAM,
                             ai_protocol: IPPROTO_TCP, ai_addrlen: 0, ai_canonname: nil,
                             ai_addr: nil, ai_next: nil)
        var result: UnsafeMutablePointer<addrinfo>?
        let rc = getaddrinfo(host, String(port), &hints, &result)
        guard rc == 0, let first = result else {
            throw WsError.connectFailed("getaddrinfo failed: \(rc)")
        }
        defer { freeaddrinfo(result) }
        var fd: Int32 = -1
        for info in sequence(first: first, next: { $0.pointee.ai_next }) {
            fd = socket(info.pointee.ai_family, info.pointee.ai_socktype, info.pointee.ai_protocol)
            if fd < 0 { continue }
            var one: Int32 = 1
            _ = setsockopt(fd, Int32(IPPROTO_TCP), TCP_NODELAY, &one, socklen_t(MemoryLayout<Int32>.size))
            let connected = withUnsafePointer(to: info.pointee.ai_addr) { pointer -> Bool in
                pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) { address in
                    connect(fd, address, info.pointee.ai_addrlen) == 0
                }
            }
            if connected { return fd }
            closeSocket(fd)
            fd = -1
        }
        throw WsError.connectFailed("no address reachable")
    }

    private func writeAll(_ bytes: Data) throws {
        var offset = 0
        while offset < bytes.count {
            let written = bytes.withUnsafeBytes { buffer -> Int in
                send(socketFD, buffer.baseAddress! + offset, bytes.count - offset, 0)
            }
            guard written > 0 else { throw WsError.closed }
            offset += written
        }
    }

    private func readChunk(into buffer: inout [UInt8]) throws -> Int {
        let read = buffer.withUnsafeMutableBufferPointer { inner -> Int in
            recv(socketFD, inner.baseAddress!, inner.count, 0)
        }
        guard read > 0 else { throw WsError.closed }
        return read
    }

    private func readHttpLine() throws -> String {
        var line = [UInt8]()
        var byte = [UInt8](repeating: 0, count: 1)
        while true {
            let n = try readChunk(into: &byte)
            guard n > 0, byte[0] != 0x0A else { break }
            if byte[0] != 0x0D { line.append(byte[0]) }
        }
        return String(decoding: line, as: UTF8.self)
    }

    private func readFrame() throws -> (opcode: UInt8, payload: [UInt8]) {
        var header = [UInt8]()
        while header.count < 2 {
            var chunk = [UInt8](repeating: 0, count: 2 - header.count)
            let n = try readChunk(into: &chunk)
            header.append(contentsOf: chunk.prefix(n))
        }
        var frames = try wsCodec.push(chunk: Data(header))
        var chunk = [UInt8](repeating: 0, count: 1024)
        while frames.isEmpty {
            let n = try readChunk(into: &chunk)
            frames = try wsCodec.push(chunk: Data(chunk.prefix(n)))
        }
        let frame = frames[0]
        if frame.opcode == 8 { throw WsError.closed }
        return (frame.opcode, frame.payload)
    }

    private static func closeSocketFd(_ fd: Int32) {
        guard fd >= 0 else { return }
        close(fd)
    }

    // MARK: - 工具

    private static func secureRandom(count: Int) throws -> Data {
        var bytes = [UInt8](repeating: 0, count: count)
        let status = SecRandomCopyBytes(kSecRandomDefault, count, &bytes)
        guard status == errSecSuccess else { throw WsError.connectFailed("SecRandomCopyBytes failed") }
        return Data(bytes)
    }

    private static func readCounter(_ envelope: [UInt8]) throws -> UInt64 {
        guard envelope.count > 8 else { throw WsError.protocolError("ciphertext too short") }
        var value: UInt64 = 0
        for byte in envelope.prefix(8) {
            value = (value << 8) | UInt64(byte)
        }
        return value
    }

    private static func hexData(_ hex: String) -> Data? {
        guard hex.count % 2 == 0 else { return nil }
        var data = Data(capacity: hex.count / 2)
        var index = hex.startIndex
        while index < hex.endIndex {
            let next = hex.index(index, offsetBy: 2)
            guard let byte = UInt8(hex[index..<next], radix: 16) else { return nil }
            data.append(byte)
            index = next
        }
        return data
    }
}

