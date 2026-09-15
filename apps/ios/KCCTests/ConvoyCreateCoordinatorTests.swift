import XCTest

@testable import KCC

final class ConvoyCreateCoordinatorTests: XCTestCase {
    private final class FakeRepository: ConvoyCreateRepository, @unchecked Sendable {
        private let lock = NSLock()
        var listResult: ConvoyCreateListResult = .loaded(
            .init(hasActiveConvoy: false, isExhaustive: true)
        )
        var listResults: [ConvoyCreateListResult] = []
        var createResult: ConvoyCreateResult = .created(
            .init(convoyId: "c1", invited: ["friend-1"], skippedCount: 0)
        )
        private(set) var createCalls: [(invitees: [String], vehicleId: String?)] = []

        func list() async -> ConvoyCreateListResult {
            lock.withLock {
                guard !listResults.isEmpty else { return listResult }
                return listResults.removeFirst()
            }
        }

        func create(inviteeUids: [String], vehicleId: String?) async -> ConvoyCreateResult {
            lock.withLock {
                createCalls.append((inviteeUids, vehicleId))
                return createResult
            }
        }
    }

    @MainActor
    func testLoadPublishesActiveConvoyPreflight() async {
        let repository = FakeRepository()
        repository.listResult = .loaded(.init(hasActiveConvoy: true, isExhaustive: true))
        let coordinator = ConvoyCreateCoordinator(repository: repository)

        await coordinator.load()

        XCTAssertEqual(coordinator.availability, .ready(hasActiveConvoy: true))
    }

    @MainActor
    func testLoadBlocksCreationWhenActiveMembershipScanIsTruncated() async {
        let repository = FakeRepository()
        repository.listResult = .loaded(.init(hasActiveConvoy: false, isExhaustive: false))
        let coordinator = ConvoyCreateCoordinator(repository: repository)

        await coordinator.load()
        await coordinator.create(inviteeUids: ["friend-1"], vehicleId: nil)

        XCTAssertEqual(coordinator.availability, .failed(.generic))
        XCTAssertEqual(coordinator.createState, .failed(.generic))
        XCTAssertTrue(repository.createCalls.isEmpty)
    }

    @MainActor
    func testCreateTrimsDeduplicatesAndForwardsVehicle() async {
        let repository = FakeRepository()
        let coordinator = ConvoyCreateCoordinator(repository: repository)
        await coordinator.load()

        await coordinator.create(
            inviteeUids: [" friend-1 ", "friend-1", " ", "friend-2"],
            vehicleId: " vehicle-7 "
        )

        XCTAssertEqual(repository.createCalls.count, 1)
        XCTAssertEqual(repository.createCalls.first?.invitees, ["friend-1", "friend-2"])
        XCTAssertEqual(repository.createCalls.first?.vehicleId, "vehicle-7")
        XCTAssertEqual(coordinator.createState, repository.createResult.asCreateState)
    }

    @MainActor
    func testBlankInviteesFailLocallyWithoutCallingBackend() async {
        let repository = FakeRepository()
        let coordinator = ConvoyCreateCoordinator(repository: repository)
        await coordinator.load()

        await coordinator.create(inviteeUids: ["", "  "], vehicleId: nil)

        XCTAssertEqual(coordinator.createState, .failed(.noInvitees))
        XCTAssertTrue(repository.createCalls.isEmpty)
    }

    @MainActor
    func testActiveConvoyFailsLocallyWithoutCallingBackend() async {
        let repository = FakeRepository()
        repository.listResult = .loaded(.init(hasActiveConvoy: true, isExhaustive: true))
        let coordinator = ConvoyCreateCoordinator(repository: repository)
        await coordinator.load()

        await coordinator.create(inviteeUids: ["friend-1"], vehicleId: nil)

        XCTAssertEqual(coordinator.createState, .failed(.alreadyInConvoy))
        XCTAssertTrue(repository.createCalls.isEmpty)
    }

    @MainActor
    func testCreatedStatePreventsASecondSubmission() async {
        let repository = FakeRepository()
        let coordinator = ConvoyCreateCoordinator(repository: repository)
        await coordinator.load()

        await coordinator.create(inviteeUids: ["friend-1"], vehicleId: nil)
        await coordinator.create(inviteeUids: ["friend-2"], vehicleId: nil)

        XCTAssertEqual(repository.createCalls.count, 1)
    }

    @MainActor
    func testFailedPreconditionRefreshesAndReportsAnActiveConvoyRace() async {
        let repository = FakeRepository()
        repository.createResult = .failed(.unresolvedPrecondition)
        repository.listResults = [
            .loaded(.init(hasActiveConvoy: false, isExhaustive: true)),
            .loaded(.init(hasActiveConvoy: true, isExhaustive: true))
        ]
        let coordinator = ConvoyCreateCoordinator(repository: repository)
        await coordinator.load()

        await coordinator.create(inviteeUids: ["friend-1"], vehicleId: nil)

        XCTAssertEqual(coordinator.availability, .ready(hasActiveConvoy: true))
        XCTAssertEqual(coordinator.createState, .failed(.alreadyInConvoy))
    }

    @MainActor
    func testFailedPreconditionWithoutActiveConvoyReportsNoValidInvitees() async {
        let repository = FakeRepository()
        repository.createResult = .failed(.unresolvedPrecondition)
        let coordinator = ConvoyCreateCoordinator(repository: repository)
        await coordinator.load()

        await coordinator.create(inviteeUids: ["friend-1"], vehicleId: nil)

        XCTAssertEqual(coordinator.createState, .failed(.noInvitees))
    }

    @MainActor
    func testFailedPreconditionWithTruncatedListUsesGenericError() async {
        let repository = FakeRepository()
        repository.createResult = .failed(.unresolvedPrecondition)
        repository.listResults = [
            .loaded(.init(hasActiveConvoy: false, isExhaustive: true)),
            .loaded(.init(hasActiveConvoy: false, isExhaustive: false))
        ]
        let coordinator = ConvoyCreateCoordinator(repository: repository)
        await coordinator.load()

        await coordinator.create(inviteeUids: ["friend-1"], vehicleId: nil)

        XCTAssertEqual(coordinator.createState, .failed(.generic))
    }

    @MainActor
    func testConfiglessCoordinatorIsUnavailable() async {
        let coordinator = ConvoyCreateCoordinator(repository: nil)

        XCTAssertEqual(coordinator.availability, .unavailable)
        await coordinator.load()
        XCTAssertEqual(coordinator.availability, .unavailable)
    }
}

private extension ConvoyCreateResult {
    var asCreateState: ConvoyCreateState {
        switch self {
        case .created(let created): .created(created)
        case .failed(let error): .failed(error)
        }
    }
}
