import Foundation
import XCTest
@testable import KCC

@MainActor
final class ClubsTests: XCTestCase {
    private var clubData: [String: Any] {
        ["id": "test-club", "name": "Testklubben", "description": "En presentation av testklubben",
         "location": "Kungsbacka", "visibility": "open", "ownerUid": "owner", "memberCount": 2,
         "stats": ["driveCount": 3, "distanceMeters": 2500, "durationSeconds": 1800]]
    }
    func testClubDecoderRejectsArchivedAndUnsafeLogoAndInvalidStatistics() {
        var raw = clubData
        raw["logoUrl"] = "https://user:password@example.com/avatar.jpg"
        raw["stats"] = ["distanceMeters": Double.infinity, "durationSeconds": -5]
        let club = CommunityClub.decode(raw)
        XCTAssertNotNil(club)
        XCTAssertNil(club?.logoURL)
        XCTAssertEqual(club?.distanceMeters, 0)
        XCTAssertEqual(club?.durationSeconds, 0)
        raw["status"] = "archived"
        XCTAssertNil(CommunityClub.decode(raw))
    }
    func testMembershipMustMatchReturnedClubRoleAndJoinDate() {
        var raw: [String: Any] = ["club": clubData, "membership": ["clubId": "other", "role": "owner", "joinedAt": "2026-10-30T00:00:00Z"]]
        XCTAssertNil(CommunityClubState.decode(raw).club)
        raw["membership"] = ["clubId": "test-club", "role": "member", "joinedAt": "2026-10-30T00:00:00.000Z"]
        let state = CommunityClubState.decode(raw)
        XCTAssertEqual(state.club?.id, "test-club")
        XCTAssertFalse(state.canJoin(at: Date()))
        raw["membership"] = ["clubId": "test-club", "role": "admin", "joinedAt": "2026-10-30T00:00:00Z"]
        XCTAssertNil(CommunityClubState.decode(raw).club)
    }
    func testCooldownAndLegacyConflictPreventCreateOrJoin() {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        let future = ISO8601DateFormatter().string(from: now.addingTimeInterval(3600))
        XCTAssertFalse(CommunityClubState.decode(["joinAllowedAt": future]).canJoin(at: now))
        XCTAssertFalse(CommunityClubState.decode(["legacyConflict": true]).canJoin(at: now))
        XCTAssertTrue(CommunityClubState.decode([:]).canJoin(at: now))
    }
    func testCreateValidationUsesTrimmedUnicodeCodepointsAndContractBounds() {
        XCTAssertFalse(ClubForm().isValid)
        let valid = ClubForm(name: " Testklubb ", description: "En riktig klubbpresentation", location: " Kungsbacka ", closed: true)
        XCTAssertTrue(valid.isValid)
        XCTAssertEqual(valid.fields["name"], "Testklubb")
        XCTAssertEqual(valid.fields["location"], "Kungsbacka")
        XCTAssertEqual(valid.fields["visibility"], "closed")
        var invalid = valid
        invalid.name = String(repeating: "a", count: 61)
        XCTAssertFalse(invalid.isValid)
    }
    func testDirectoryIsGatedAndConfigRevocationClearsAccountState() async {
        let repository = ClubTestRepository(club: CommunityClub.decode(clubData)!)
        let coordinator = ClubsCoordinator(repository: repository)
        coordinator.start()
        await coordinator.reload()
        XCTAssertEqual(repository.reads, 0)
        repository.continuation.yield(true)
        await settle { coordinator.state != nil && !coordinator.loading }
        XCTAssertEqual(coordinator.clubs.count, 1)
        repository.continuation.yield(false)
        await settle { !coordinator.enabled }
        XCTAssertNil(coordinator.state)
        XCTAssertTrue(coordinator.clubs.isEmpty)
        let accepted = await coordinator.manage("join", fields: ["clubId": "test-club"])
        XCTAssertFalse(accepted)
        XCTAssertTrue(repository.operations.isEmpty)
        coordinator.stop()
    }
    func testAmbiguousMutationRetryRetainsIdempotencyKey() async {
        let repository = ClubTestRepository(club: CommunityClub.decode(clubData)!)
        let coordinator = ClubsCoordinator(repository: repository)
        coordinator.start()
        repository.continuation.yield(true)
        await settle { coordinator.state != nil && !coordinator.loading }
        repository.failNextMutation = true
        let first = await coordinator.manage("join", fields: ["clubId": "test-club"])
        XCTAssertFalse(first)
        let retry = await coordinator.manage("join", fields: ["clubId": "test-club"])
        XCTAssertTrue(retry)
        XCTAssertEqual(repository.operations.count, 2)
        XCTAssertEqual(repository.operations.first, repository.operations.last)
        coordinator.stop()
    }
    private func settle(_ condition: () -> Bool) async {
        let deadline = Date().addingTimeInterval(2)
        while !condition() && Date() < deadline { await Task.yield() }
        XCTAssertTrue(condition())
    }
}

@MainActor
private final class ClubTestRepository: ClubsRepository {
    let stream: AsyncStream<Bool>
    let continuation: AsyncStream<Bool>.Continuation
    let club: CommunityClub
    var reads = 0
    var operations: [String] = []
    var failNextMutation = false
    init(club: CommunityClub) {
        self.club = club
        (stream, continuation) = AsyncStream.makeStream(of: Bool.self)
    }
    func enabledUpdates() -> AsyncStream<Bool> { stream }
    func state() async throws -> CommunityClubState { reads += 1; return .decode([:]) }
    func directory(after cursor: String?) async throws -> ClubDirectoryPage {
        ClubDirectoryPage(clubs: [club], nextCursor: nil)
    }
    func get(id: String) async throws -> CommunityClub? { club }
    func manage(action: String, fields: [String: String], operationID: String) async throws {
        operations.append(operationID)
        if failNextMutation { failNextMutation = false; throw URLError(.networkConnectionLost) }
    }
}
