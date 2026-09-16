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
            ConvoyManagementErrorMapper.mapInvite(
                KccFunctionsError(code: .failedPrecondition)
            ),
            .unresolvedPrecondition
        )
        XCTAssertEqual(
            ConvoyManagementErrorMapper.mapInvite(KccFunctionsError(
                code: .failedPrecondition, reason: .noValidConvoyInvitees
            )),
            .noInvitees
        )
    }

    func testInviteParserRequiresConvoyAndCountArrays() {
        let payload = convoy(id: "convoy", viewer: "accepted")

        XCTAssertEqual(
            ConvoyManagementParser.parseInvite([
                "convoy": payload,
                "invited": [["uid": "one"], ["uid": "two"]],
                "skipped": [["uid": "three"]]
            ]),
            .completed(ConvoyInviteResult(
                convoy: ConvoyManagementParser.parseItem(payload)!,
                invitedCount: 2,
                skippedCount: 1
            ))
        )
        XCTAssertEqual(
            ConvoyManagementParser.parseInvite(["invited": [], "skipped": []]),
            .failed(.generic)
        )
        XCTAssertEqual(
            ConvoyManagementParser.parseInvite([
                "convoy": ["convoyId": "missing-status"],
                "invited": [],
                "skipped": []
            ]),
            .failed(.generic)
        )
        XCTAssertEqual(
            ConvoyManagementParser.parseInvite([
                "convoy": payload,
                "invited": "not-an-array",
                "skipped": []
            ]),
            .failed(.generic)
        )
    }

    func testParsesEndedSummaryAndBuildsRecap() {
        var payload = convoy(id: "ended", status: "ended", viewer: "accepted")
        payload["summary"] = [
            "durationSeconds": 3_725,
            "participantUids": ["owner", "missing-member"],
            "participantCount": 1,
            "distanceMeters": 1_234.5
        ]

        let item = ConvoyManagementParser.parseList([
            "convoys": [payload], "pendingInvites": []
        ]).convoys[0]

        XCTAssertEqual(
            item.summary,
            ConvoySummaryStats(
                durationSeconds: 3_725,
                participantUids: ["owner", "missing-member"],
                participantCount: 2,
                distanceMeters: 1_234.5
            )
        )
        XCTAssertEqual(item.recap?.participants.map(\.uid), ["owner", "missing-member"])
        XCTAssertNil(item.recap?.participants.last?.displayName)
        XCTAssertEqual(item.recap?.participantCount, 2)
    }

    func testProfileHydrationRefreshesNameWithoutChangingMembership() {
        let item = ConvoyManagementParser.parseItem(
            convoy(id: "convoy", viewer: "accepted")
        )!

        let hydrated = ConvoyProfileHydration.apply(item, names: ["owner": "Current name"])

        XCTAssertEqual(hydrated.members.first?.displayName, "Current name")
        XCTAssertEqual(hydrated.members.first?.inviteStatus, item.members.first?.inviteStatus)
        XCTAssertEqual(hydrated.viewer, item.viewer)
    }

    func testListHonorsExplicitIncompleteMembershipScan() {
        let payload = convoy(id: "active", viewer: "invited")
        let parsed = ConvoyManagementParser.parseList([
            "convoys": [payload], "pendingInvites": [payload], "isExhaustive": false
        ])

        XCTAssertFalse(parsed.isExhaustive)
        XCTAssertFalse(parsed.canJoinAnotherConvoy)
    }

    func testMemberViewerNeverReceivesOwnerRole() {
        let payload = convoy(id: "member", viewer: "accepted", viewerRole: "member")
        let item = ConvoyManagementParser.parseItem(payload)

        XCTAssertEqual(item?.viewer?.role, .member)
        XCTAssertFalse(item?.viewerIsOwner ?? true)
    }

    func testInviteSelectionHonorsRemainingConvoyCapacity() {
        let members = (0..<24).map {
            ConvoyMember(
                uid: "member-\($0)",
                displayName: nil,
                role: $0 == 0 ? .owner : .member,
                inviteStatus: .accepted
            )
        }
        let convoy = ConvoyItem(
            convoyId: "convoy",
            title: nil,
            status: .active,
            members: members,
            viewer: ConvoyViewer(inviteStatus: .accepted, role: .owner),
            createdAt: nil
        )

        XCTAssertEqual(ConvoyBarLogic.maximumInviteSelection(for: convoy), 1)
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
        viewer: String,
        viewerRole: String = "owner"
    ) -> [String: Any] {
        [
            "convoyId": id,
            "status": status,
            "viewer": ["role": viewerRole, "inviteStatus": viewer],
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
