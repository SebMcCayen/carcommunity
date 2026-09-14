import XCTest

@testable import KCC

final class ConvoyCreateModelsTests: XCTestCase {
    func testListDetectsOnlyNonEndedConvoysAsActive() {
        XCTAssertTrue(
            ConvoyCreateResponseParser.parseList([
                "convoys": [[
                    "convoyId": "c1",
                    "status": "active",
                    "viewer": ["inviteStatus": "accepted"]
                ]]
            ]).hasActiveConvoy
        )
        XCTAssertFalse(
            ConvoyCreateResponseParser.parseList([
                "convoys": [["convoyId": "c1", "status": "ended"]]
            ]).hasActiveConvoy
        )
    }

    func testListIgnoresMalformedRows() {
        let snapshot = ConvoyCreateResponseParser.parseList([
            "convoys": ["bad", NSNull(), ["status": "ended"]]
        ])

        XCTAssertFalse(snapshot.hasActiveConvoy)
    }

    func testPendingInviteDoesNotCountAsActiveParticipation() {
        let snapshot = ConvoyCreateResponseParser.parseList([
            "convoys": [[
                "convoyId": "c1",
                "status": "active",
                "viewer": ["inviteStatus": "invited"]
            ]]
        ])

        XCTAssertFalse(snapshot.hasActiveConvoy)
    }

    func testCreateParsesCreatedConvoyAndInviteOutcomes() {
        let result = ConvoyCreateResponseParser.parseCreate([
            "convoy": ["convoyId": " c-42 "],
            "invited": ["friend-1", "  friend-2  ", NSNull()],
            "skipped": [["uid": "blocked", "reason": "not_found"]]
        ])

        XCTAssertEqual(
            result,
            .created(
                ConvoyCreated(
                    convoyId: "c-42",
                    invited: ["friend-1", "friend-2"],
                    skippedCount: 1
                )
            )
        )
    }

    func testCreateRejectsAMalformedSuccessPayload() {
        XCTAssertEqual(
            ConvoyCreateResponseParser.parseCreate(["convoy": ["status": "active"]]),
            .failed(.generic)
        )
    }

    func testCallableErrorsMapToContractSafeUIErrors() {
        XCTAssertEqual(ConvoyCreateErrorMapper.mapCreate(.unauthenticated), .signedOut)
        XCTAssertEqual(ConvoyCreateErrorMapper.mapCreate(.permissionDenied), .notMember)
        XCTAssertEqual(ConvoyCreateErrorMapper.mapCreate(.invalidArgument), .invalid)
        XCTAssertEqual(
            ConvoyCreateErrorMapper.mapCreate(.failedPrecondition),
            .unresolvedPrecondition
        )
        XCTAssertEqual(ConvoyCreateErrorMapper.mapCreate(.internalError), .generic)
        XCTAssertEqual(ConvoyCreateErrorMapper.mapList(.unauthenticated), .signedOut)
        XCTAssertEqual(ConvoyCreateErrorMapper.mapList(.permissionDenied), .notMember)
        XCTAssertEqual(ConvoyCreateErrorMapper.mapList(.unavailable), .generic)
    }
}
