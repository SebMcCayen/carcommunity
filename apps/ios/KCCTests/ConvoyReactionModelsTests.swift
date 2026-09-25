import XCTest
@testable import KCC

final class ConvoyReactionModelsTests: XCTestCase {
    func testKindsAndCooldownWindowsMatchBackendContract() {
        XCTAssertEqual(ConvoyReactionKind.police.rawValue, "police")
        XCTAssertEqual(ConvoyReactionKind.hello.rawValue, "hello")
        XCTAssertEqual(ConvoyReactionKind.followMe.rawValue, "follow_me")
        XCTAssertEqual(ConvoyReactionKind.police.cooldownMilliseconds, 60_000)
        XCTAssertEqual(ConvoyReactionKind.hello.cooldownMilliseconds, 15_000)
        XCTAssertEqual(ConvoyReactionKind.followMe.cooldownMilliseconds, 30_000)
        XCTAssertNil(ConvoyReactionKind(rawValue: "wave"))
    }

    func testPayloadUsesCallableWireShapeAndIdempotencyKey() {
        XCTAssertEqual(
            ConvoyReactionWire.sendPayload(
                convoyId: "convoy-1",
                kind: .followMe,
                clientId: "client-1"
            ),
            [
                "convoyId": "convoy-1",
                "kind": "follow_me",
                "clientId": "client-1",
            ]
        )
    }

    func testCooldownsAreIndependentAndElapseAtBoundary() {
        let state = ConvoyReactionCooldownState().recordingSend(
            .police,
            atMilliseconds: 1_000
        )
        XCTAssertEqual(
            state.remainingMilliseconds(for: .police, nowMilliseconds: 1_000),
            60_000
        )
        XCTAssertFalse(state.isReady(.police, nowMilliseconds: 60_999))
        XCTAssertTrue(state.isReady(.police, nowMilliseconds: 61_000))
        XCTAssertTrue(state.isReady(.hello, nowMilliseconds: 1_000))
        XCTAssertTrue(state.isReady(.followMe, nowMilliseconds: 1_000))
    }

    func testServerCooldownOverridesLocalEstimateAndNonpositiveClears() {
        let state = ConvoyReactionCooldownState()
            .recordingSend(.police, atMilliseconds: 1_000)
            .applyingServerCooldown(
                .police,
                retryAfterMilliseconds: 5_000,
                nowMilliseconds: 2_000
            )
        XCTAssertEqual(
            state.remainingMilliseconds(for: .police, nowMilliseconds: 2_000),
            5_000
        )
        XCTAssertTrue(state.isReady(.police, nowMilliseconds: 7_000))
        XCTAssertTrue(
            state.applyingServerCooldown(
                .police,
                retryAfterMilliseconds: 0,
                nowMilliseconds: 2_000
            ).isReady(.police, nowMilliseconds: 2_000)
        )
    }

    func testRetryAfterParsingRejectsMissingAndNegativeValues() {
        XCTAssertEqual(
            ConvoyReactionWire.retryAfterMilliseconds(from: ["retryAfterMs": 4_200]),
            4_200
        )
        XCTAssertEqual(
            ConvoyReactionWire.retryAfterMilliseconds(from: ["retryAfterMs": -1]),
            0
        )
        XCTAssertEqual(ConvoyReactionWire.retryAfterMilliseconds(from: nil), 0)
    }
}
