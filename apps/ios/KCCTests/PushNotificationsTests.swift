import XCTest
@testable import KCC

final class PushNotificationsTests: XCTestCase {
    func testTokenIdIsLowercaseSHA256() {
        XCTAssertEqual(
            PushTokens.tokenId("abc"),
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        )
    }

    func testPayloadDecodesTargetAndTrimmedEntity() {
        XCTAssertEqual(
            PushPayload.deepLink(from: ["target": "dm", "entityId": " friend-1 "]),
            NotificationDeepLink(target: .dm, entityId: "friend-1")
        )
    }

    func testPayloadFallsBackToInboxAndDropsBlankEntity() {
        XCTAssertEqual(
            PushPayload.deepLink(from: ["target": "future_target", "entityId": "  "]),
            NotificationDeepLink(target: .notifications, entityId: nil)
        )
    }
}
