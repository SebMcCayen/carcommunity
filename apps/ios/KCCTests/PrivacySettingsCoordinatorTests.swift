import XCTest

@testable import KCC

final class PrivacySettingsCoordinatorTests: XCTestCase {
    private final class FakeRepository: PrivacySettingsRepository, @unchecked Sendable {
        private let lock = NSLock()
        private var scripted: [PrivacySettingsSnapshot] = []
        private var continuations: [UUID: AsyncStream<PrivacySettingsSnapshot>.Continuation] = [:]
        private(set) var partnerWrites: [Bool] = []
        private(set) var leaderboardOptOutWrites: [Bool] = []
        private(set) var terminations = 0
        var writeError: Error?

        func script(_ snapshots: [PrivacySettingsSnapshot]) {
            lock.withLock { scripted = snapshots }
        }

        func emit(_ snapshot: PrivacySettingsSnapshot) {
            let live = lock.withLock { Array(continuations.values) }
            live.forEach { $0.yield(snapshot) }
        }

        func settings(uid: String) -> AsyncStream<PrivacySettingsSnapshot> {
            let initial = lock.withLock { scripted }
            return AsyncStream { continuation in
                initial.forEach { continuation.yield($0) }
                let id = UUID()
                self.lock.withLock { self.continuations[id] = continuation }
                continuation.onTermination = { [weak self] _ in
                    guard let self else { return }
                    self.lock.withLock {
                        self.continuations[id] = nil
                        self.terminations += 1
                    }
                }
            }
        }

        func setPartnerStatsOptIn(uid: String, optIn: Bool) async throws {
            lock.withLock { partnerWrites.append(optIn) }
            if let writeError { throw writeError }
        }

        func setLeaderboardOptOut(uid: String, optOut: Bool) async throws {
            lock.withLock { leaderboardOptOutWrites.append(optOut) }
            if let writeError { throw writeError }
        }
    }

    @MainActor
    private func wait(
        _ coordinator: PrivacySettingsCoordinator,
        until predicate: (PrivacySettingsCoordinator) -> Bool,
        file: StaticString = #filePath,
        line: UInt = #line
    ) async {
        let deadline = Date().addingTimeInterval(2)
        while Date() < deadline {
            if predicate(coordinator) { return }
            await Task.yield()
            try? await Task.sleep(nanoseconds: 1_000_000)
        }
        XCTFail("Timed out; state: \(coordinator.state)", file: file, line: line)
    }

    func testDecoderUsesOptOutDefaultsOnlyForMissingFields() {
        XCTAssertEqual(PrivacySettingsChoices.decode(nil), .contractDefaults)
        XCTAssertTrue(PrivacySettingsChoices.contractDefaults.partnerStatsOptIn)
        XCTAssertEqual(
            PrivacySettingsChoices.decode([
                "anonymousPartnerStatsOptIn": false,
                "leaderboardOptOut": true,
            ]),
            PrivacySettingsChoices(partnerStatsOptIn: false, leaderboardShown: false)
        )
        XCTAssertNil(PrivacySettingsChoices.decode(["leaderboardOptOut": "yes"]))
        XCTAssertNil(PrivacySettingsChoices.decode(["anonymousPartnerStatsOptIn": 1]))
    }

    func testOnlyServerConfirmedSnapshotBecomesAuthoritative() {
        let optedOut: [String: Any] = ["anonymousPartnerStatsOptIn": false]

        XCTAssertNil(PrivacySettingsSnapshot.authoritative(
            data: nil,
            isFromCache: true,
            hasPendingWrites: false
        ))
        XCTAssertNil(PrivacySettingsSnapshot.authoritative(
            data: optedOut,
            isFromCache: false,
            hasPendingWrites: true
        ))
        XCTAssertEqual(
            PrivacySettingsSnapshot.authoritative(
                data: optedOut,
                isFromCache: false,
                hasPendingWrites: false
            ),
            .loaded(PrivacySettingsChoices(partnerStatsOptIn: false, leaderboardShown: true))
        )
    }

    func testAuthoritativeMissingFieldsKeepBackendOptOutDefaultsButMalformedDataFails() {
        XCTAssertEqual(
            PrivacySettingsSnapshot.authoritative(
                data: nil,
                isFromCache: false,
                hasPendingWrites: false
            ),
            .loaded(.contractDefaults)
        )
        XCTAssertEqual(
            PrivacySettingsSnapshot.authoritative(
                data: ["anonymousPartnerStatsOptIn": "false"],
                isFromCache: false,
                hasPendingWrites: false
            ),
            .failed(code: nil)
        )
    }

    @MainActor
    func testUnavailableWithoutRepositoryOrUid() {
        XCTAssertEqual(
            PrivacySettingsCoordinator(repository: nil, uid: "me").state,
            .unavailable
        )
        XCTAssertEqual(
            PrivacySettingsCoordinator(repository: FakeRepository(), uid: nil).state,
            .unavailable
        )
    }

    @MainActor
    func testUnresolvedAndFailedReadsNeverEnableSaving() async {
        let repository = FakeRepository()
        let coordinator = PrivacySettingsCoordinator(repository: repository, uid: "me")
        XCTAssertFalse(coordinator.canEdit)
        await coordinator.savePartnerStats()
        XCTAssertTrue(repository.partnerWrites.isEmpty)

        repository.emit(.failed(code: "UNAVAILABLE"))
        coordinator.start()
        repository.emit(.failed(code: "UNAVAILABLE"))
        await wait(coordinator) { $0.state == .failed(code: "UNAVAILABLE") }
        XCTAssertFalse(coordinator.canEdit)
        await coordinator.saveLeaderboardVisibility()
        XCTAssertTrue(repository.leaderboardOptOutWrites.isEmpty)
    }

    @MainActor
    func testLoadsChoicesAndPersistsInverseLeaderboardFlag() async {
        let repository = FakeRepository()
        let choices = PrivacySettingsChoices(partnerStatsOptIn: false, leaderboardShown: true)
        repository.script([.loaded(choices)])
        let coordinator = PrivacySettingsCoordinator(repository: repository, uid: "me")

        coordinator.start()
        await wait(coordinator) { $0.state == .loaded(choices) }
        XCTAssertTrue(coordinator.canEdit)
        XCTAssertEqual(coordinator.draft, PrivacySettingsDraft(choices))

        coordinator.setPendingPartnerStatsOptIn(true)
        await coordinator.savePartnerStats()
        XCTAssertEqual(repository.partnerWrites, [true])
        XCTAssertEqual(coordinator.partnerSaveStatus, .saved)

        coordinator.setPendingLeaderboardShown(false)
        await coordinator.saveLeaderboardVisibility()
        XCTAssertEqual(repository.leaderboardOptOutWrites, [true])
        XCTAssertEqual(coordinator.leaderboardSaveStatus, .saved)
    }

    @MainActor
    func testListenerFailureAfterLoadLocksControlsWithoutOverwritingDraft() async {
        let repository = FakeRepository()
        let choices = PrivacySettingsChoices(partnerStatsOptIn: false, leaderboardShown: false)
        repository.script([.loaded(choices)])
        let coordinator = PrivacySettingsCoordinator(repository: repository, uid: "me")
        coordinator.start()
        await wait(coordinator) { $0.state == .loaded(choices) }
        coordinator.setPendingPartnerStatsOptIn(true)

        repository.emit(.failed(code: "UNAVAILABLE"))
        await wait(coordinator) { $0.state == .failed(code: "UNAVAILABLE") }
        XCTAssertFalse(coordinator.canEdit)
        XCTAssertEqual(coordinator.draft?.partnerStatsOptIn, true)
        await coordinator.savePartnerStats()
        XCTAssertTrue(repository.partnerWrites.isEmpty)
    }

    @MainActor
    func testLiveEmissionDoesNotOverwriteAnEditInProgress() async {
        let repository = FakeRepository()
        let initial = PrivacySettingsChoices(partnerStatsOptIn: true, leaderboardShown: true)
        repository.script([.loaded(initial)])
        let coordinator = PrivacySettingsCoordinator(repository: repository, uid: "me")
        coordinator.start()
        await wait(coordinator) { $0.state == .loaded(initial) }
        coordinator.setPendingPartnerStatsOptIn(false)

        let later = PrivacySettingsChoices(partnerStatsOptIn: true, leaderboardShown: false)
        repository.emit(.loaded(later))
        await wait(coordinator) { $0.state == .loaded(later) }
        XCTAssertEqual(coordinator.draft?.partnerStatsOptIn, false)
        XCTAssertEqual(coordinator.draft?.leaderboardShown, false)
    }

    @MainActor
    func testWriteFailureCarriesSafeStatusCode() async {
        let repository = FakeRepository()
        repository.script([.loaded(.contractDefaults)])
        repository.writeError = PrivacySettingsWriteError(code: "PERMISSION_DENIED")
        let coordinator = PrivacySettingsCoordinator(repository: repository, uid: "me")
        coordinator.start()
        await wait(coordinator) { $0.state == .loaded(.contractDefaults) }

        await coordinator.savePartnerStats()
        XCTAssertEqual(coordinator.partnerSaveStatus, .failed(code: "PERMISSION_DENIED"))
    }

    @MainActor
    func testReloadTerminatesOldListenerAndReseedsDraft() async {
        let repository = FakeRepository()
        repository.script([.loaded(.contractDefaults)])
        let coordinator = PrivacySettingsCoordinator(repository: repository, uid: "me")
        coordinator.start()
        await wait(coordinator) { $0.state == .loaded(.contractDefaults) }
        coordinator.setPendingPartnerStatsOptIn(false)

        let refreshed = PrivacySettingsChoices(partnerStatsOptIn: true, leaderboardShown: false)
        repository.script([.loaded(refreshed)])
        coordinator.reload()
        await wait(coordinator) { $0.state == .loaded(refreshed) }
        await wait(coordinator) { _ in repository.terminations == 1 }
        XCTAssertEqual(coordinator.draft, PrivacySettingsDraft(refreshed))
    }

    @MainActor
    func testStopDetachesListenerAndReentryUsesFreshServerChoices() async {
        let repository = FakeRepository()
        repository.script([.loaded(.contractDefaults)])
        let coordinator = PrivacySettingsCoordinator(repository: repository, uid: "me")
        coordinator.start()
        await wait(coordinator) { $0.state == .loaded(.contractDefaults) }

        coordinator.stop()
        await wait(coordinator) { _ in repository.terminations == 1 }
        XCTAssertEqual(coordinator.state, .loading)
        XCTAssertNil(coordinator.draft)

        let refreshed = PrivacySettingsChoices(partnerStatsOptIn: false, leaderboardShown: false)
        repository.script([.loaded(refreshed)])
        coordinator.start()
        await wait(coordinator) { $0.state == .loaded(refreshed) }
        XCTAssertEqual(coordinator.draft, PrivacySettingsDraft(refreshed))
    }
}
