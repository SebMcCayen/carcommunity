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

    func testMalformedListCannotProveMembershipIsExhaustive() {
        let snapshot = ConvoyManagementParser.parseList([
            "convoys": ["bad"], "pendingInvites": [], "isExhaustive": true
        ])
        XCTAssertFalse(snapshot.isExhaustive)
        XCTAssertFalse(snapshot.canJoinAnotherConvoy)
        XCTAssertFalse(ConvoyManagementParser.parseList(nil).isExhaustive)
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

    func testParsesOnlyCleanLivePositionUids() throws {
        var payload = convoy(id: "convoy", viewer: "accepted")
        payload["livePositionUids"] = ["owner", " ", NSNull(), "member"]
        let item = try XCTUnwrap(ConvoyManagementParser.parseItem(payload))
        XCTAssertEqual(item.livePositionUids, ["owner", "member"])
    }

    func testAwarenessPlannerSeparatesOnScreenAndOffScreenMembers() {
        let now = Date(timeIntervalSince1970: 2_000_000_000)
        let visible = ConvoyMemberPosition(
            uid: "visible", latitude: 57.49, longitude: 12.08, updatedAt: now
        )
        let east = ConvoyMemberPosition(
            uid: "east", latitude: 57.49, longitude: 12.18, updatedAt: now
        )
        let camera = MapCameraSnapshot.of(
            latitude: 57.49, longitude: 12.08, zoom: 14, bearing: 0, pitch: 45
        )
        let plan = ConvoyArrowPlanner.plan(
            members: [visible, east], camera: camera,
            viewportWidth: 400, viewportHeight: 800, edgeInset: 40, now: now,
            project: { $0.uid == "visible" ? MapScreenPoint(x: 200, y: 400) : nil }
        )
        XCTAssertEqual(plan.onScreen.map(\.member.uid), ["visible"])
        XCTAssertEqual(plan.offScreen.map(\.member.uid), ["east"])
        XCTAssertEqual(plan.offScreen[0].point.x, 360, accuracy: 0.01)
        // An eastbound great-circle bearing at this latitude is a fraction
        // north of 90°, so the edge intersection sits just above centre.
        XCTAssertEqual(plan.offScreen[0].point.y, 400, accuracy: 0.25)
    }

    func testAwarenessPlannerDropsStaleAndCapsMergedArrows() {
        let now = Date(timeIntervalSince1970: 2_000_000_000)
        let stale = ConvoyMemberPosition(
            uid: "stale", latitude: 58, longitude: 12,
            updatedAt: now.addingTimeInterval(-ConvoyArrowPlanner.staleAfter - 1)
        )
        let fresh = (0..<8).map { index in
            let angle = Double(index) * 45 * .pi / 180
            return ConvoyMemberPosition(
                uid: "member-\(index)",
                latitude: 57.49 + cos(angle) * 0.1,
                longitude: 12.08 + sin(angle) * 0.1,
                updatedAt: now
            )
        }
        let camera = MapCameraSnapshot.of(
            latitude: 57.49, longitude: 12.08, zoom: 12, bearing: 0, pitch: 45
        )
        let plan = ConvoyArrowPlanner.plan(
            members: [stale] + fresh, camera: camera,
            viewportWidth: 400, viewportHeight: 800, edgeInset: 40, now: now,
            project: { _ in nil }
        )
        XCTAssertEqual(plan.offScreen.count, ConvoyArrowPlanner.maximumArrows)
        XCTAssertFalse(plan.offScreen.contains { $0.member.uid == "stale" })
        XCTAssertEqual(plan.offScreen.reduce(0) { $0 + $1.extraCount + 1 }, fresh.count)
    }

    @MainActor
    func testAwarenessFocusResetsOnlyWhenActiveConvoyIdentityChanges() throws {
        var firstPayload = convoy(id: "first", viewer: "accepted")
        firstPayload["livePositionUids"] = ["owner"]
        let first = try XCTUnwrap(ConvoyManagementParser.parseItem(firstPayload))
        let coordinator = ConvoyAwarenessCoordinator()

        coordinator.sync(convoy: first, repository: nil, currentUid: "owner")
        coordinator.focusMode = .convoy

        firstPayload["livePositionUids"] = ["owner", "friend"]
        let refreshedFirst = try XCTUnwrap(ConvoyManagementParser.parseItem(firstPayload))
        coordinator.sync(convoy: refreshedFirst, repository: nil, currentUid: "owner")
        XCTAssertEqual(coordinator.focusMode, .convoy)

        let second = try XCTUnwrap(ConvoyManagementParser.parseItem(
            convoy(id: "second", viewer: "accepted")
        ))
        coordinator.sync(convoy: second, repository: nil, currentUid: "owner")
        XCTAssertEqual(coordinator.focusMode, .me)
    }

    @MainActor
    func testAwarenessFitDropsPositionsAsTheyBecomeStale() {
        let now = Date(timeIntervalSince1970: 2_000_000_000)
        let coordinator = ConvoyAwarenessCoordinator()
        coordinator.focusMode = .convoy
        coordinator.setPositionsForTest([
            "me": ConvoyMemberPosition(
                uid: "me", latitude: 57, longitude: 12, updatedAt: now
            ),
            "friend": ConvoyMemberPosition(
                uid: "friend", latitude: 58, longitude: 13, updatedAt: now
            )
        ], ownUid: "me")

        XCTAssertNotNil(coordinator.fitPoints(now: now))
        XCTAssertEqual(
            coordinator.ownPoint(now: now),
            MapPoint(longitude: 12, latitude: 57)
        )
        XCTAssertNil(coordinator.fitPoints(
            now: now.addingTimeInterval(ConvoyArrowPlanner.staleAfter + 1)
        ))
        XCTAssertNil(coordinator.ownPoint(
            now: now.addingTimeInterval(ConvoyArrowPlanner.staleAfter + 1)
        ))
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
