import XCTest

@testable import KCC

final class ConvoyManagementModelsTests: XCTestCase {
    func testListSeparatesPendingInvitesFromOwnedAndAcceptedConvoys() {
        let data: [String: Any] = [
            "convoys": [convoy(id: "mine", viewer: "accepted"), convoy(id: "invite", viewer: "invited")],
            "pendingInvites": [convoy(id: "invite", viewer: "invited")]
        ]

        let snapshot = ConvoyManagementParser.parseList(data)

        XCTAssertEqual(snapshot.pendingInvites.map(\.convoyId), ["invite"])
        XCTAssertEqual(snapshot.myConvoys.map(\.convoyId), ["mine"])
        XCTAssertTrue(snapshot.hasActiveConvoy)
        XCTAssertFalse(snapshot.canJoinAnotherConvoy)
    }

    func testPendingInviteAloneAllowsAccept() {
        let invite = convoy(id: "invite", viewer: "invited")
        let snapshot = ConvoyManagementParser.parseList([
            "convoys": [invite],
            "pendingInvites": [invite]
        ])

        XCTAssertFalse(snapshot.hasActiveConvoy)
        XCTAssertTrue(snapshot.canJoinAnotherConvoy)
        XCTAssertEqual(snapshot.pendingInvites.first?.ownerName, "Owner")
    }

    func testCappedListBlocksJoiningWhenNoActiveMembershipWasFound() {
        let ended = convoy(id: "ended", status: "ended", viewer: "accepted")
        let snapshot = ConvoyManagementParser.parseList([
            "convoys": Array(repeating: ended, count: ConvoyManagementParser.listLimit),
            "pendingInvites": []
        ])

        XCTAssertFalse(snapshot.isExhaustive)
        XCTAssertFalse(snapshot.hasActiveConvoy)
        XCTAssertFalse(snapshot.canJoinAnotherConvoy)
    }

    func testParserDropsMalformedRowsAndRejectsMalformedMutation() {
        let snapshot = ConvoyManagementParser.parseList([
            "convoys": ["bad", ["convoyId": "missing-status"]],
            "pendingInvites": [NSNull()]
        ])

        XCTAssertTrue(snapshot.convoys.isEmpty)
        XCTAssertTrue(snapshot.pendingInvites.isEmpty)
        XCTAssertEqual(ConvoyManagementParser.parseRespond(["convoy": [:]]), .failed(.generic))
    }

    func testRespondErrorsUseCallableSpecificMapping() {
        XCTAssertEqual(ConvoyManagementErrorMapper.mapRespond(.notFound), .notFound)
        XCTAssertEqual(
            ConvoyManagementErrorMapper.mapRespond(.failedPrecondition),
            .unresolvedPrecondition
        )
        XCTAssertEqual(ConvoyManagementErrorMapper.mapList(.permissionDenied), .notMember)
        XCTAssertEqual(
            ConvoyManagementErrorMapper.mapInvite(.failedPrecondition),
            .unresolvedPrecondition
        )
    }

    func testBarSelectsActiveConvoyAndDerivesExitChoices() {
        let forming = ConvoyManagementParser.parseList([
            "convoys": [convoy(id: "forming", status: "forming", viewer: "accepted")],
            "pendingInvites": []
        ]).convoys[0]
        let active = ConvoyManagementParser.parseList([
            "convoys": [convoy(id: "active", viewer: "accepted")],
            "pendingInvites": []
        ]).convoys[0]
        let snapshot = ConvoyManagementSnapshot(
            convoys: [forming, active], pendingInvites: [], isExhaustive: true
        )

        XCTAssertEqual(ConvoyBarLogic.activeConvoy(in: snapshot)?.convoyId, "active")
        XCTAssertEqual(
            ConvoyBarLogic.exitChoice(viewerIsOwner: true, acceptedMemberCount: 3),
            .leaveOrEnd
        )
        XCTAssertEqual(
            ConvoyBarLogic.exitChoice(viewerIsOwner: true, acceptedMemberCount: 2),
            .endOnly
        )
        XCTAssertEqual(
            ConvoyBarLogic.exitChoice(viewerIsOwner: false, acceptedMemberCount: 2),
            .leaveEndsConvoy
        )
    }

    func testParsesViewerRoleAndLifecycleResults() {
        let payload = convoy(id: "convoy", viewer: "accepted")
        let snapshot = ConvoyManagementParser.parseList([
            "convoys": [payload], "pendingInvites": []
        ])
        XCTAssertTrue(snapshot.convoys[0].viewerIsOwner)
        XCTAssertEqual(
            ConvoyManagementParser.parseLifecycle(
                ["convoy": payload], action: .end
            ),
            .updated(snapshot.convoys[0])
        )
        XCTAssertEqual(
            ConvoyManagementParser.parseLifecycle(
                ["outcome": "left_and_ended", "newLeaderUid": NSNull()], action: .leave
            ),
            .left(ConvoyLeaveResult(outcome: .leftAndEnded, newLeaderUid: nil))
        )
    }

    private func convoy(
        id: String,
        status: String = "active",
        viewer: String
    ) -> [String: Any] {
        [
            "convoyId": id,
            "status": status,
            "viewer": ["role": "owner", "inviteStatus": viewer],
            "members": [[
                "uid": "owner",
                "role": "owner",
                "inviteStatus": "accepted",
                "displayName": "Owner"
            ]],
            "createdAt": "2026-09-15T07:00:00.000Z"
        ]
    }
}
