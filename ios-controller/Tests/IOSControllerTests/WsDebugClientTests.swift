import XCTest
import Foundation
import CryptoKit
import tvremote_coreFFI
import TvRemoteCoreZig
@testable import IOSController

/// `WsDebugClient` 接收缓存、握手缓存、心跳序号与发送锁的运行级回归。
///
/// - 场景 1/2/6 直接调用模块内 `readFrame`/`helloExchange`，用 socketpair 与预置
///   `pending` 验证「不额外读 socket」「握手与命令共用缓存」「Close 立即失败」。
/// - 场景 3/4/5 使用本文件内的最小 WS 回环服务端（复用 Rust 绑定原语，行为对齐
///   agent 的 `WsDebugChannel`），驱动真实 `WsDebugClient` 完成握手与命令收发。
final class WsDebugClientTests: XCTestCase {

    // MARK: - 场景 1：pending 已有两条完整消息，传入无效 fd 也必须按序返回、不读 socket

    func testReadFrameConsumesPendingWithoutReadingSocket() throws {
        var codec = WsCodec.withRole(role: .client)
        var pending = [
            WsFrame(opcode: 2, payload: Data([1, 2, 3])),
            WsFrame(opcode: 2, payload: Data([4, 5, 6])),
        ]
        let first = try WsDebugClient.readFrame(fd: -1, wsCodec: &codec, pending: &pending)
        XCTAssertEqual(first.opcode, 2)
        XCTAssertEqual(first.payload, Data([1, 2, 3]))
        let second = try WsDebugClient.readFrame(fd: -1, wsCodec: &codec, pending: &pending)
        XCTAssertEqual(second.opcode, 2)
        XCTAssertEqual(second.payload, Data([4, 5, 6]))
        XCTAssertTrue(pending.isEmpty)
    }

    // MARK: - 场景 6：缓存中遇到 Close 立即返回关闭错误，不等待新数据

    func testReadFrameThrowsOnCloseInPending() {
        var codec = WsCodec.withRole(role: .client)
        var pending = [WsFrame(opcode: 8, payload: Data())]
        XCTAssertThrowsError(try WsDebugClient.readFrame(fd: -1, wsCodec: &codec, pending: &pending))
        XCTAssertTrue(pending.isEmpty)
    }

    // MARK: - 场景 2：握手缓存含 ACK 与后续消息，握手成功后后续消息仍可读

    func testHelloExchangeSharesPendingWithFollowingMessages() throws {
        let clientRandom = randomData(32)
        let serverRandom = randomData(32)

        var fds: [Int32] = [-1, -1]
        XCTAssertEqual(socketpair(AF_UNIX, SOCK_STREAM, 0, &fds), 0)
        defer { _ = close(fds[0]); _ = close(fds[1]) }
        // 有限读超时：任何意外 recv 都会快速失败而非永久阻塞。
        setTimeout(fd: fds[0], seconds: 2)

        var ws = WsCodec.withRole(role: .client)
        let ack = "{\"protocolVersion\":1,\"requestId\":\"ws-hello-1\",\"sessionId\":\"ab\","
            + "\"sequence\":2,\"type\":\"ws_hello_ack\",\"payload\":{\"serverRandom\":\"\(hexString(serverRandom))\","
            + "\"authenticated\":true}}"
        var pending = [
            WsFrame(opcode: 1, payload: Data(ack.utf8)),
            WsFrame(opcode: 2, payload: Data([9, 9, 9])),
        ]

        let returned = try WsDebugClient.helloExchange(
            fd: fds[0], controllerId: String(repeating: "ab", count: 16),
            clientRandom: clientRandom, wsCodec: &ws, pending: &pending)
        XCTAssertEqual(returned, serverRandom)

        // 握手消费 ACK 后，同批后续消息仍留在缓存中，无需新网络数据。
        let next = try WsDebugClient.readFrame(fd: fds[0], wsCodec: &ws, pending: &pending)
        XCTAssertEqual(next.opcode, 2)
        XCTAssertEqual(next.payload, Data([9, 9, 9]))
        XCTAssertTrue(pending.isEmpty)
    }

    // MARK: - 场景 3：回环服务端连发握手 ACK 与控制帧，随后处理命令

    func testLoopbackHandshakeControlFrameAndCommand() throws {
        let psk = randomData(32)
        let controllerId = String(repeating: "ab", count: 16)
        guard let agent = LoopbackAgent(psk: psk) else {
            return XCTFail("loopback agent failed to bind")
        }
        defer { agent.stop() }
        agent.start(controllerId: controllerId)

        let client = try runBounded("connect", timeout: 5, onLateDiscard: { $0.close() }) {
            try WsDebugClient(host: "127.0.0.1", controllerId: controllerId, psk: psk, port: agent.port)
        }
        defer { client.close() }

        XCTAssertTrue(agent.waitForHandshake(timeout: 5), "handshake must complete")
        // 服务端在 ACK 同批里发过加密 ping 与帧级 ping；命令读循环必须消费它们、
        // 对帧级 ping 回 Pong，并最终拿到命令 ACK。
        let ack = try runBounded("key_event", timeout: 5) {
            try client.sendKeyEvent(key: "DPAD_UP", state: "UP")
        }
        try assertCommandSuccess(ack)
        XCTAssertTrue(agent.waitForSequences(count: 1, timeout: 5))
        XCTAssertEqual(agent.receivedTypes, ["key_event"])
        XCTAssertTrue(agent.waitForPong(timeout: 5), "frame-level ping must be answered with a pong")
        XCTAssertEqual(agent.receivedPong, Data("hb-ping".utf8))
        XCTAssertNil(agent.serveError)
    }

    // MARK: - 场景 4：命令 → 心跳 → 命令，解密后序号 [1,2,3]，心跳不等 ACK

    func testCommandHeartbeatCommandSequences() throws {
        let psk = randomData(32)
        let controllerId = String(repeating: "cd", count: 16)
        guard let agent = LoopbackAgent(psk: psk) else {
            return XCTFail("loopback agent failed to bind")
        }
        defer { agent.stop() }
        agent.start(controllerId: controllerId)

        let client = try runBounded("connect", timeout: 5, onLateDiscard: { $0.close() }) {
            try WsDebugClient(host: "127.0.0.1", controllerId: controllerId, psk: psk, port: agent.port)
        }
        defer { client.close() }

        let ackDown = try runBounded("key_event-down", timeout: 5) {
            try client.sendKeyEvent(key: "A", state: "DOWN")
        }
        try assertCommandSuccess(ackDown)
        try runBounded("heartbeat", timeout: 5) {
            try client.sendHeartbeat() // fire-and-forget：只写不等 ACK
        }
        let ackUp = try runBounded("key_event-up", timeout: 5) {
            try client.sendKeyEvent(key: "A", state: "UP")
        }
        try assertCommandSuccess(ackUp)

        XCTAssertTrue(agent.waitForSequences(count: 3, timeout: 5))
        XCTAssertEqual(agent.receivedSequences, [1, 2, 3])
        XCTAssertEqual(agent.receivedTypes, ["key_event", "ping", "key_event"])
        XCTAssertNil(agent.serveError)
    }

    // MARK: - 场景 5：并发命令与心跳，按接收顺序序号严格递增、消息可解密

    func testConcurrentCommandAndHeartbeatKeepSequencesIncreasing() throws {
        let psk = randomData(32)
        let controllerId = String(repeating: "ef", count: 16)
        guard let agent = LoopbackAgent(psk: psk) else {
            return XCTFail("loopback agent failed to bind")
        }
        defer { agent.stop() }
        agent.start(controllerId: controllerId)

        let client = try runBounded("connect", timeout: 5, onLateDiscard: { $0.close() }) {
            try WsDebugClient(host: "127.0.0.1", controllerId: controllerId, psk: psk, port: agent.port)
        }
        defer { client.close() }

        let errors = LockedErrors()
        let iterations = 8
        let finished = expectation(description: "concurrent command/heartbeat")
        DispatchQueue.global().async {
            DispatchQueue.concurrentPerform(iterations: iterations) { index in
                do {
                    if index % 2 == 0 {
                        try client.sendHeartbeat()
                    } else {
                        _ = try client.sendKeyEvent(key: "DPAD_UP", state: "UP")
                    }
                } catch {
                    errors.append("\(error)")
                }
            }
            finished.fulfill()
        }
        wait(for: [finished], timeout: 10)
        XCTAssertTrue(errors.values.isEmpty, "concurrent sends must not fail: \(errors.values)")
        XCTAssertTrue(agent.waitForSequences(count: iterations, timeout: 10))

        let sequences = agent.receivedSequences
        XCTAssertEqual(sequences.count, iterations)
        XCTAssertEqual(Set(sequences).count, iterations, "sequences must be unique")
        XCTAssertEqual(sequences, sequences.sorted(), "sequences must arrive in increasing order")
        XCTAssertNil(agent.serveError)
    }
}

// MARK: - 测试辅助

private func randomData(_ count: Int) -> Data {
    var bytes = [UInt8](repeating: 0, count: count)
    _ = SecRandomCopyBytes(kSecRandomDefault, count, &bytes)
    return Data(bytes)
}

private func hexString(_ data: Data) -> String {
    data.map { String(format: "%02x", $0) }.joined()
}

private func dataFromHex(_ hex: String) -> Data? {
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

private final class LockedErrors: @unchecked Sendable {
    private let lock = NSLock()
    private var storage: [String] = []
    func append(_ message: String) {
        lock.lock(); storage.append(message); lock.unlock()
    }
    var values: [String] {
        lock.lock(); defer { lock.unlock() }
        return storage
    }
}

private enum TestFailure: Error {
    case timeout(String)
}

/// 跨线程传递 `Result`，并在超时放弃时就地清理迟到结果（`@unchecked Sendable`，内部加锁）。
private final class BoundedBox<T>: @unchecked Sendable {
    private let lock = NSLock()
    private let onLateDiscard: ((T) -> Void)?
    private var storage: Result<T, Error>?
    private var abandoned = false

    init(onLateDiscard: ((T) -> Void)?) {
        self.onLateDiscard = onLateDiscard
    }

    /// 后台完成：若已被放弃则立即丢弃（清理）；否则保存结果供等待方读取。
    func finish(_ result: Result<T, Error>) {
        lock.lock()
        if abandoned {
            lock.unlock()
            if let value = try? result.get() { onLateDiscard?(value) }
            return
        }
        storage = result
        lock.unlock()
    }

    /// 超时放弃：若后台已完成，返回结果供调用方清理。
    func abandon() -> T? {
        lock.lock(); defer { lock.unlock() }
        abandoned = true
        guard let result = storage, let value = try? result.get() else { return nil }
        return value
    }

    var result: Result<T, Error> {
        lock.lock(); defer { lock.unlock() }
        return storage!
    }
}

/// 在后台队列执行可能阻塞的客户端调用，并以有限时间等待；超时抛错而非挂起测试。
/// 超时时若结果迟到（如连接稍后成功），通过 `onLateDiscard` 清理，避免资源泄漏。
private func runBounded<T>(
    _ description: String,
    timeout: TimeInterval,
    onLateDiscard: ((T) -> Void)? = nil,
    _ body: @escaping () throws -> T
) throws -> T {
    let semaphore = DispatchSemaphore(value: 0)
    let box = BoundedBox<T>(onLateDiscard: onLateDiscard)
    DispatchQueue.global().async {
        box.finish(Result { try body() })
        semaphore.signal()
    }
    guard semaphore.wait(timeout: .now() + timeout) == .success else {
        if let late = box.abandon() { onLateDiscard?(late) }
        // 有限等待后台退出；若后台在放弃之后才完成，finish 会自行清理。
        _ = semaphore.wait(timeout: .now() + 1.0)
        throw TestFailure.timeout(description)
    }
    return try box.result.get()
}

/// 解析 command_ack 文本并断言 `payload.status == "SUCCESS"`（`sendKeyEvent` 返回 String）。
private func assertCommandSuccess(_ ack: String, file: StaticString = #filePath, line: UInt = #line) throws {
    let object = try XCTUnwrap(
        JSONSerialization.jsonObject(with: Data(ack.utf8)) as? [String: Any],
        file: file, line: line)
    let payload = try XCTUnwrap(object["payload"] as? [String: Any], file: file, line: line)
    XCTAssertEqual(payload["status"] as? String, "SUCCESS", file: file, line: line)
}

/// 给 fd 设置有限读超时，防止测试意外阻塞。
private func setTimeout(fd: Int32, seconds: Int) {
    var tv = timeval(tv_sec: seconds, tv_usec: 0)
    _ = setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))
}

/// 测试用最小 WS 回环服务端：HTTP 升级 → 明文 ws_hello → 加密命令循环。
/// 仅使用 Rust 绑定原语，行为对齐 agent 的 `WsDebugChannel`（掩码入向、不掩码出向、
/// counter 从 1 起、命令 ack、client ping 不回 ack）。
private final class LoopbackAgent {
    private let listenFD: Int32
    private let psk: Data
    private let queue = DispatchQueue(label: "tvrc.loopback.agent")
    private let stateLock = NSLock()

    private var clientFD: Int32 = -1
    private var stopped = false
    private var handshakeCompleted = false
    private var pongReceived = false
    private var receivedPongStorage: Data?
    private var serveErrorStorage: String?
    private var receivedSequencesStorage: [UInt64] = []
    private var receivedTypesStorage: [String] = []

    let port: UInt16

    init?(psk: Data) {
        self.psk = psk
        let fd = socket(AF_INET, SOCK_STREAM, 0)
        guard fd >= 0 else { return nil }
        var yes: Int32 = 1
        _ = setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &yes, socklen_t(MemoryLayout<Int32>.size))

        var addr = sockaddr_in()
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = 0
        addr.sin_addr.s_addr = inet_addr("127.0.0.1")
        let bindResult = withUnsafeMutablePointer(to: &addr) { pointer -> Int32 in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) { sockaddrPointer in
                bind(fd, sockaddrPointer, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard bindResult == 0, listen(fd, 1) == 0 else { _ = close(fd); return nil }

        var bound = sockaddr_in()
        var length = socklen_t(MemoryLayout<sockaddr_in>.size)
        let nameResult = withUnsafeMutablePointer(to: &bound) { pointer -> Int32 in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) { sockaddrPointer in
                getsockname(fd, sockaddrPointer, &length)
            }
        }
        guard nameResult == 0 else { _ = close(fd); return nil }
        self.listenFD = fd
        self.port = UInt16(bigEndian: bound.sin_port)
    }

    var receivedSequences: [UInt64] {
        stateLock.lock(); defer { stateLock.unlock() }
        return receivedSequencesStorage
    }

    var receivedTypes: [String] {
        stateLock.lock(); defer { stateLock.unlock() }
        return receivedTypesStorage
    }

    var receivedPong: Data? {
        stateLock.lock(); defer { stateLock.unlock() }
        return receivedPongStorage
    }

    var serveError: String? {
        stateLock.lock(); defer { stateLock.unlock() }
        return serveErrorStorage
    }

    func start(controllerId: String) {
        queue.async { [weak self] in
            guard let self = self else { return }
            let fd = accept(self.listenFD, nil, nil)
            guard fd >= 0 else { return }
            self.stateLock.lock(); self.clientFD = fd; self.stateLock.unlock()
            self.serve(fd: fd, controllerId: controllerId)
        }
    }

    func stop() {
        stateLock.lock()
        stopped = true
        let fd = clientFD
        stateLock.unlock()
        if fd >= 0 { _ = close(fd) }
        _ = close(listenFD)
    }

    func waitForHandshake(timeout: TimeInterval) -> Bool {
        waitUntil(timeout: timeout) { self.handshakeCompleted }
    }

    func waitForSequences(count: Int, timeout: TimeInterval) -> Bool {
        waitUntil(timeout: timeout) { self.receivedSequencesStorage.count >= count }
    }

    func waitForPong(timeout: TimeInterval) -> Bool {
        waitUntil(timeout: timeout) { self.pongReceived }
    }

    private func waitUntil(timeout: TimeInterval, _ predicate: () -> Bool) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            stateLock.lock()
            let satisfied = predicate()
            stateLock.unlock()
            if satisfied { return true }
            Thread.sleep(forTimeInterval: 0.01)
        }
        stateLock.lock()
        let satisfied = predicate()
        stateLock.unlock()
        return satisfied
    }

    private func serve(fd: Int32, controllerId: String) {
        do {
            try httpUpgrade(fd: fd)
            var codec = WsCodec.withRole(role: .server)
            var pending: [WsFrame] = []
            let hello = try nextFrame(fd: fd, codec: &codec, pending: &pending)
            guard hello.opcode == 1,
                  let helloObject = try JSONSerialization.jsonObject(with: hello.payload) as? [String: Any],
                  let payload = helloObject["payload"] as? [String: Any],
                  let clientRandomHex = payload["clientRandom"] as? String,
                  let clientRandom = dataFromHex(clientRandomHex) else {
                throw LoopbackError.protocolViolation
            }

            let serverRandom = randomData(32)
            let crypto = try SessionCrypto(
                psk: psk, clientRandom: clientRandom, serverRandom: serverRandom,
                isClient: false, replayWindowBits: 64)

            // 握手 ACK 同批再发：加密 ping（服务端探活）+ 帧级 Ping（opcode 9）。
            // 帧级 Ping 必须被客户端回 Pong，从而可观测同批余帧确实被消费、未丢失。
            let ack = "{\"protocolVersion\":1,\"requestId\":\"ws-hello-1\",\"sessionId\":\"\(controllerId)\","
                + "\"sequence\":2,\"type\":\"ws_hello_ack\",\"payload\":{\"serverRandom\":\"\(hexString(serverRandom))\","
                + "\"authenticated\":true}}"
            let ackFrame = try codec.encode(opcode: 1, payload: Data(ack.utf8))
            let serverPing = "{\"protocolVersion\":1,\"requestId\":\"ws-ping-1\",\"sessionId\":\"\(controllerId)\","
                + "\"sequence\":1,\"type\":\"ping\",\"payload\":{}}"
            let sealedPing = try crypto.seal(isClient: false, plaintext: Data(serverPing.utf8), aad: Data())
            let encryptedPingFrame = try codec.encode(opcode: 2, payload: sealedPing)
            let framePing = try codec.encode(opcode: 9, payload: Data("hb-ping".utf8))
            var batch = ackFrame
            batch.append(encryptedPingFrame)
            batch.append(framePing)
            try sendAll(fd: fd, batch)

            stateLock.lock(); handshakeCompleted = true; stateLock.unlock()

            while true {
                let frame = try nextFrame(fd: fd, codec: &codec, pending: &pending)
                if frame.opcode == 9 {
                    try sendAll(fd: fd, try codec.encode(opcode: 10, payload: frame.payload))
                    continue
                }
                if frame.opcode == 10 {
                    stateLock.lock()
                    receivedPongStorage = frame.payload
                    pongReceived = true
                    stateLock.unlock()
                    continue
                }
                if frame.opcode == 8 { break }
                guard frame.opcode == 2 else { continue }

                let counter = readCounter(frame.payload)
                guard crypto.checkSequence(sequence: counter) else {
                    throw LoopbackError.protocolViolation
                }
                let plaintext = try crypto.open(
                    isClient: false, ciphertext: frame.payload, counter: counter, aad: Data())
                guard let envelope = try JSONSerialization.jsonObject(with: plaintext) as? [String: Any] else {
                    throw LoopbackError.protocolViolation
                }
                let sequence = UInt64(envelope["sequence"] as? Int ?? 0)
                let type = envelope["type"] as? String ?? ""
                stateLock.lock()
                receivedSequencesStorage.append(sequence)
                receivedTypesStorage.append(type)
                stateLock.unlock()

                if type == "ping" { continue }
                let requestId = envelope["requestId"] as? String ?? "r"
                let ack = "{\"protocolVersion\":1,\"requestId\":\"\(requestId)\",\"sessionId\":\"\(controllerId)\","
                    + "\"sequence\":\(sequence + 1),\"type\":\"command_ack\","
                    + "\"payload\":{\"commandSequence\":\(sequence),\"status\":\"SUCCESS\"}}"
                let sealed = try crypto.seal(isClient: false, plaintext: Data(ack.utf8), aad: Data())
                try sendAll(fd: fd, try codec.encode(opcode: 2, payload: sealed))
            }
        } catch LoopbackError.connectionClosed {
            // 正常关闭（客户端/测试侧 close）：不视为失败。
        } catch {
            stateLock.lock()
            if !stopped { serveErrorStorage = "\(error)" }
            stateLock.unlock()
            // 异常退出：解除对端阻塞，避免客户端读循环长时间挂起。
            _ = shutdown(fd, SHUT_RDWR)
        }
    }

    private func httpUpgrade(fd: Int32) throws {
        var request = [UInt8]()
        var byte = [UInt8](repeating: 0, count: 1)
        while !(request.count >= 4 && Array(request.suffix(4)) == [13, 10, 13, 10]) {
            let n = recv(fd, &byte, 1, 0)
            if n > 0 {
                request.append(byte[0])
                continue
            }
            if n == 0 { throw LoopbackError.connectionClosed }
            if errno == EINTR { continue }
            throw LoopbackError.ioError("http recv failed: errno=\(errno)")
        }
        let text = String(decoding: request, as: UTF8.self)
        guard let keyLine = text.components(separatedBy: "\r\n").first(where: {
            $0.lowercased().hasPrefix("sec-websocket-key:")
        }), let key = keyLine.split(separator: ":", maxSplits: 1).last else {
            throw LoopbackError.protocolViolation
        }
        let keyValue = String(key).trimmingCharacters(in: .whitespaces)
        let accept = Data(Insecure.SHA1.hash(data: Data((keyValue + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").utf8))).base64EncodedString()
        let response = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n"
            + "Connection: Upgrade\r\nSec-WebSocket-Accept: \(accept)\r\n\r\n"
        try sendAll(fd: fd, Data(response.utf8))
    }

    private func nextFrame(fd: Int32, codec: inout WsCodec, pending: inout [WsFrame]) throws -> WsFrame {
        var buffer = [UInt8](repeating: 0, count: 4096)
        while pending.isEmpty {
            let n = recv(fd, &buffer, buffer.count, 0)
            if n > 0 {
                pending.append(contentsOf: try codec.push(chunk: Data(buffer.prefix(n))))
                continue
            }
            if n == 0 { throw LoopbackError.connectionClosed }
            if errno == EINTR { continue }
            throw LoopbackError.ioError("recv failed: errno=\(errno)")
        }
        return pending.removeFirst()
    }

    private func sendAll(fd: Int32, _ data: Data) throws {
        var offset = 0
        while offset < data.count {
            let written = data.withUnsafeBytes { raw -> Int in
                send(fd, raw.baseAddress! + offset, data.count - offset, 0)
            }
            if written > 0 {
                offset += written
                continue
            }
            if written == 0 { throw LoopbackError.ioError("send returned 0") }
            if errno == EINTR { continue }
            throw LoopbackError.ioError("send failed: errno=\(errno)")
        }
    }

    private func readCounter(_ data: Data) -> UInt64 {
        var value: UInt64 = 0
        for byte in data.prefix(8) {
            value = (value << 8) | UInt64(byte)
        }
        return value
    }

    private enum LoopbackError: Error {
        case connectionClosed
        case protocolViolation
        case ioError(String)
    }
}
