import Foundation

public enum PendingOperationKind: Sendable, Equatable {
    case discovery
    case connection
    case pairing
    case disconnection

    public var timeout: Duration {
        switch self {
        case .discovery: .seconds(5)
        case .connection: .seconds(35)
        case .pairing: .seconds(130)
        case .disconnection: .seconds(10)
        }
    }

    public var timeoutDescription: String {
        switch self {
        case .discovery: "发现电视超时"
        case .connection: "连接电视超时"
        case .pairing: "电视配对超时"
        case .disconnection: "断开连接超时"
        }
    }
}

public enum OperationTerminalEvent: Sendable {
    case requestComplete
    case error
    case commandAck
}

public struct PendingOperation: Sendable, Equatable {
    public let requestID: UInt64
    public let kind: PendingOperationKind
    public let acceptsCommandAck: Bool

    public init(requestID: UInt64, kind: PendingOperationKind, acceptsCommandAck: Bool = false) {
        precondition(requestID != 0)
        self.requestID = requestID
        self.kind = kind
        self.acceptsCommandAck = acceptsCommandAck
    }

    public func accepts(requestID candidate: UInt64, event: OperationTerminalEvent) -> Bool {
        guard requestID == candidate else { return false }
        return switch event {
        case .requestComplete, .error: true
        case .commandAck: acceptsCommandAck
        }
    }
}

public enum CapabilitySupport: String, Sendable {
    case supported = "SUPPORTED"
    case bestEffort = "BEST_EFFORT"
    case permissionRequired = "PERMISSION_REQUIRED"
    case unsupported = "UNSUPPORTED"

    public var enablesControl: Bool { self == .supported || self == .bestEffort }
    public var enablesMedia: Bool { self == .supported || self == .permissionRequired }
}

public enum CapabilityName: String, CaseIterable, Sendable {
    case dpadUp = "DPAD_UP"
    case dpadDown = "DPAD_DOWN"
    case dpadLeft = "DPAD_LEFT"
    case dpadRight = "DPAD_RIGHT"
    case dpadCenter = "DPAD_CENTER"
    case back = "BACK"
    case home = "HOME"
    case menu = "MENU"
    case volumeUp = "VOLUME_UP"
    case volumeDown = "VOLUME_DOWN"
    case volumeMute = "VOLUME_MUTE"
    case channelUp = "CHANNEL_UP"
    case channelDown = "CHANNEL_DOWN"
    case mediaPlayPause = "MEDIA_PLAY_PAUSE"
    case mediaStop = "MEDIA_STOP"
    case mediaNext = "MEDIA_NEXT"
    case mediaPrevious = "MEDIA_PREVIOUS"
    case power = "POWER"
}

public struct EventPollBackoff: Sendable, Equatable {
    private var currentMilliseconds = 100

    public init() {}

    public mutating func nextDelayMilliseconds() -> Int {
        defer { currentMilliseconds = min(currentMilliseconds * 2, 2_000) }
        return currentMilliseconds
    }

    public mutating func reset() { currentMilliseconds = 100 }
}

public enum EventPayloadDecision: Sendable, Equatable {
    case resize(Int)
    case abiViolation
}

public func eventPayloadDecision(
    required: Int,
    currentCapacity: Int,
    maximumPayloadBytes: Int = 1 << 20
) -> EventPayloadDecision {
    guard required >= 0, currentCapacity > 0, maximumPayloadBytes > 0,
          required <= maximumPayloadBytes else { return .abiViolation }
    return .resize(min(max(required + 1, currentCapacity * 2), maximumPayloadBytes + 1))
}
