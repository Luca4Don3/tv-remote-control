import XCTest
@testable import TVRemoteCoreLogic

final class CoreLogicTests: XCTestCase {
    func testOperationTimeoutsMatchDesktopContract() {
        XCTAssertEqual(PendingOperationKind.discovery.timeout, .seconds(5))
        XCTAssertEqual(PendingOperationKind.connection.timeout, .seconds(35))
        XCTAssertEqual(PendingOperationKind.pairing.timeout, .seconds(130))
        XCTAssertEqual(PendingOperationKind.disconnection.timeout, .seconds(10))
    }

    func testOnlyMatchingDeclaredTerminalEventCompletesOperation() {
        let operation = PendingOperation(requestID: 42, kind: .connection)
        XCTAssertTrue(operation.accepts(requestID: 42, event: .requestComplete))
        XCTAssertTrue(operation.accepts(requestID: 42, event: .error))
        XCTAssertFalse(operation.accepts(requestID: 41, event: .requestComplete))
        XCTAssertFalse(operation.accepts(requestID: 42, event: .commandAck))
        let acknowledged = PendingOperation(requestID: 7, kind: .discovery, acceptsCommandAck: true)
        XCTAssertTrue(acknowledged.accepts(requestID: 7, event: .commandAck))
    }

    func testCapabilityMappingsAreCentralizedAndConservative() {
        XCTAssertTrue(CapabilitySupport.supported.enablesControl)
        XCTAssertTrue(CapabilitySupport.bestEffort.enablesControl)
        XCTAssertFalse(CapabilitySupport.permissionRequired.enablesControl)
        XCTAssertTrue(CapabilitySupport.permissionRequired.enablesMedia)
        XCTAssertNil(CapabilitySupport(rawValue: "UNKNOWN"))
    }

    func testPollErrorsBackOffToTwoSecondsAndReset() {
        var backoff = EventPollBackoff()
        XCTAssertEqual([100, 200, 400, 800, 1_600, 2_000, 2_000],
                       (0..<7).map { _ in backoff.nextDelayMilliseconds() })
        backoff.reset()
        XCTAssertEqual(100, backoff.nextDelayMilliseconds())
    }

    func testPayloadLimitAllowsExactlyOneMiBWithSentinelCapacity() {
        XCTAssertEqual(.resize((1 << 20) + 1), eventPayloadDecision(
            required: 1 << 20,
            currentCapacity: 1 << 19
        ))
        XCTAssertEqual(.abiViolation, eventPayloadDecision(
            required: (1 << 20) + 1,
            currentCapacity: 1 << 20
        ))
    }
}
