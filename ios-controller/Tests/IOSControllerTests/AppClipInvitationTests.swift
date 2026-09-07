import XCTest
@testable import IOSController

final class AppClipInvitationTests: XCTestCase {
    private let token = String(repeating: "ab", count: 32) // 64 hex

    func testParsesSchemeFormat() throws {
        let invitation = try XCTUnwrap(
            AppClipInvitation.parse("tvrc://pair?host=192.168.1.50&port=47832&token=\(token)&ttl=60")
        )
        XCTAssertEqual(invitation.host, "192.168.1.50")
        XCTAssertEqual(invitation.port, 47832)
        XCTAssertEqual(invitation.token, token)
        XCTAssertEqual(invitation.ttlSeconds, 60)
    }

    func testParsesClipLinkFormat() throws {
        let invitation = try XCTUnwrap(
            AppClipInvitation.parse("https://clip.example.com/pair?host=tv.local&port=47832&token=\(token)")
        )
        XCTAssertEqual(invitation.host, "tv.local")
        XCTAssertEqual(invitation.ttlSeconds, 120, "ttl 缺省 120s")
    }

    func testRejectsInvalidInput() {
        XCTAssertNil(AppClipInvitation.parse("https://clip.example.com/other?host=x&port=1&token=\(token)"))
        XCTAssertNil(AppClipInvitation.parse("tvrc://pair?port=47832&token=\(token)"))
        XCTAssertNil(AppClipInvitation.parse("tvrc://pair?host=1.2.3.4&token=\(token)"))
        XCTAssertNil(AppClipInvitation.parse("tvrc://pair?host=1.2.3.4&port=47832&token=short"))
        XCTAssertNil(AppClipInvitation.parse("tvrc://pair?host=1.2.3.4&port=47832&token=\(String(repeating: "g", count: 64))"))
        XCTAssertNil(AppClipInvitation.parse("tvrc://pair?host=1.2.3.4&port=0&token=\(token)"))
        XCTAssertNil(AppClipInvitation.parse("tvrc://pair?host=1.2.3.4&port=47832&token=\(token)&ttl=99999"))
        XCTAssertNil(AppClipInvitation.parse("not a url"))
    }
}
