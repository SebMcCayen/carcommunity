import XCTest

@testable import KCC

/// Unit tests for the pure notification-settings orchestration: reading the
/// owner's preferences, recomputing + saving a toggle, and surfacing the write
/// failure code — plus the model's tolerant preference decoding. No Firebase —
/// the repository is a scripted fake.
final class NotificationSettingsCoordinatorTests: XCTestCase {

    // MARK: - fake

    private final class FakeSettingsRepository: NotificationSettingsRepository, @unchecked Sendable {
        private let lock = NSLock()
        private var pending: [NotificationSettingsSnapshot] = []
        private var continuations: [UUID: AsyncStream<NotificationSettingsSnapshot>.Continuation] = [:]
        private(set) var saved: [NotificationPreferences] = []
        var saveError: Error?

        func script(_ snapshots: [NotificationSettingsSnapshot]) {
            lock.lock(); pending = snapshots; lock.unlock()
        }

        func emit(_ snapshot: NotificationSettingsSnapshot) {
            lock.lock()
            let live = Array(continuations.values)
            lock.unlock()
            for continuation in live { continuation.yield(snapshot) }
        }

        func preferences(uid: String) -> AsyncStream<NotificationSettingsSnapshot> {
            lock.lock()
            let snapshots = pending
            lock.unlock()
            return AsyncStream { continuation in
                for snapshot in snapshots { continuation.yield(snapshot) }
                let id = UUID()
                self.lock.lock(); self.continuations[id] = continuation; self.lock.unlock()
                continuation.onTermination = { [weak self] _ in
                    guard let self else { return }
                    self.lock.lock(); self.continuations[id] = nil; self.lock.unlock()
                }
            }
        }

        func savePreferences(uid: String, preferences: NotificationPreferences) async throws {
            lock.lock(); saved.append(preferences); lock.unlock()
            if let saveError { throw saveError }
        }
    }

    @MainActor
    private func waitFor(
        _ coordinator: NotificationSettingsCoordinator,
        timeout: TimeInterval = 2,
        file: StaticString = #filePath,
        line: UInt = #line,
        until predicate: (NotificationSettingsCoordinator) -> Bool
    ) async {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if predicate(coordinator) { return }
            await Task.yield()
            try? await Task.sleep(nanoseconds: 1_000_000)
        }
        XCTFail("Timed out; last state: \(coordinator.state)", file: file, line: line)
    }

    // MARK: - state

    @MainActor
    func testUnavailableWhenNoRepository() {
        let coordinator = NotificationSettingsCoordinator(repository: nil, uid: "me-uid")
        XCTAssertEqual(coordinator.state, .unavailable)
    }

    @MainActor
    func testUnavailableWhenNoUid() {
        let coordinator = NotificationSettingsCoordinator(
            repository: FakeSettingsRepository(), uid: nil
        )
        XCTAssertEqual(coordinator.state, .unavailable)
    }

    @MainActor
    func testInitialStateIsLoading() {
        let coordinator = NotificationSettingsCoordinator(
            repository: FakeSettingsRepository(), uid: "me-uid"
        )
        XCTAssertEqual(coordinator.state, .loading)
    }

    @MainActor
    func testLoadsStoredPreferences() async {
        let repository = FakeSettingsRepository()
        let stored = NotificationPreferences([.directMessage: CategoryPreference(inApp: false, push: true)])
        repository.script([.loaded(stored)])
        let coordinator = NotificationSettingsCoordinator(repository: repository, uid: "me-uid")

        coordinator.start()
        await waitFor(coordinator) { $0.state == .loaded(stored) }
        guard case .loaded(let prefs) = coordinator.state else { return XCTFail("not loaded") }
        XCTAssertFalse(prefs.effective(.directMessage).inApp)
        XCTAssertTrue(prefs.effective(.directMessage).push)
        // An unset category still reads enabled.
        XCTAssertTrue(prefs.effective(.convoyChat).inApp)
    }

    // MARK: - toggle → save

    @MainActor
    func testToggleRecomputesAndSaves() async {
        let repository = FakeSettingsRepository()
        repository.script([.loaded(.allEnabled)])
        let coordinator = NotificationSettingsCoordinator(repository: repository, uid: "me-uid")

        coordinator.start()
        await waitFor(coordinator) { if case .loaded = $0.state { return true } else { return false } }

        await coordinator.toggle(.directMessage, channel: .push, enabled: false)
        XCTAssertEqual(coordinator.saveStatus, .saved)
        XCTAssertEqual(repository.saved.count, 1)
        // The persisted map carries the single opt-out.
        XCTAssertEqual(repository.saved.first?.toFirestoreMap()["direct_message"], ["inApp": true, "push": false])
    }

    @MainActor
    func testToggleNoOpWhenNotLoaded() async {
        let repository = FakeSettingsRepository()
        let coordinator = NotificationSettingsCoordinator(repository: repository, uid: "me-uid")
        // Still loading (no snapshot scripted).
        await coordinator.toggle(.directMessage, channel: .push, enabled: false)
        XCTAssertTrue(repository.saved.isEmpty)
        XCTAssertEqual(coordinator.saveStatus, .idle)
    }

    @MainActor
    func testTogglingEssentialCategoryDoesNotSave() async {
        let repository = FakeSettingsRepository()
        repository.script([.loaded(.allEnabled)])
        let coordinator = NotificationSettingsCoordinator(repository: repository, uid: "me-uid")

        coordinator.start()
        await waitFor(coordinator) { if case .loaded = $0.state { return true } else { return false } }

        // Essential categories can never be disabled → withToggle is a no-op →
        // no round trip.
        await coordinator.toggle(.accountWarning, channel: .inApp, enabled: false)
        XCTAssertTrue(repository.saved.isEmpty)
        XCTAssertEqual(coordinator.saveStatus, .idle)
    }

    @MainActor
    func testSaveFailureCarriesTheStatusCode() async {
        let repository = FakeSettingsRepository()
        repository.script([.loaded(.allEnabled)])
        repository.saveError = NotificationSettingsWriteError(code: "PERMISSION_DENIED")
        let coordinator = NotificationSettingsCoordinator(repository: repository, uid: "me-uid")

        coordinator.start()
        await waitFor(coordinator) { if case .loaded = $0.state { return true } else { return false } }

        await coordinator.toggle(.directMessage, channel: .inApp, enabled: false)
        XCTAssertEqual(coordinator.saveStatus, .failed(code: "PERMISSION_DENIED"))
    }

    @MainActor
    func testSaveUnknownFailureCarriesNilCode() async {
        struct Boom: Error {}
        let repository = FakeSettingsRepository()
        repository.script([.loaded(.allEnabled)])
        repository.saveError = Boom()
        let coordinator = NotificationSettingsCoordinator(repository: repository, uid: "me-uid")

        coordinator.start()
        await waitFor(coordinator) { if case .loaded = $0.state { return true } else { return false } }

        await coordinator.toggle(.wave, channel: .push, enabled: false)
        XCTAssertEqual(coordinator.saveStatus, .failed(code: nil))
    }

    @MainActor
    func testResetClearsFailedStatus() async {
        let repository = FakeSettingsRepository()
        repository.script([.loaded(.allEnabled)])
        repository.saveError = NotificationSettingsWriteError(code: "UNAVAILABLE")
        let coordinator = NotificationSettingsCoordinator(repository: repository, uid: "me-uid")

        coordinator.start()
        await waitFor(coordinator) { if case .loaded = $0.state { return true } else { return false } }

        await coordinator.toggle(.wave, channel: .push, enabled: false)
        XCTAssertEqual(coordinator.saveStatus, .failed(code: "UNAVAILABLE"))
        coordinator.resetSaveStatus()
        XCTAssertEqual(coordinator.saveStatus, .idle)
    }

    // MARK: - model decoding

    func testPreferencesFromFirestoreTolerantDecoding() {
        let prefs = NotificationPreferences.fromFirestore([
            "direct_message": ["inApp": false, "push": true],
            "convoy_chat": ["push": false],  // missing inApp defaults enabled
            "unknown_category": ["inApp": false],  // dropped
            "malformed": "not a map",  // dropped
        ])
        XCTAssertEqual(prefs.effective(.directMessage), CategoryPreference(inApp: false, push: true))
        XCTAssertEqual(prefs.effective(.convoyChat), CategoryPreference(inApp: true, push: false))
        // Unknown/malformed entries never appear; an unset category is enabled.
        XCTAssertEqual(prefs.effective(.friendRequest), CategoryPreference(inApp: true, push: true))
    }

    func testPreferencesFromFirestoreNilIsAllEnabled() {
        XCTAssertEqual(NotificationPreferences.fromFirestore(nil), .allEnabled)
    }

    func testEssentialCategoriesAlwaysEffectiveEnabledAndNotPersisted() {
        // Even if a stored map somehow carried an essential opt-out, effective
        // reads it fully enabled and toFirestoreMap never persists it.
        let prefs = NotificationPreferences([.accountWarning: CategoryPreference(inApp: false, push: false)])
        XCTAssertEqual(prefs.effective(.accountWarning), CategoryPreference(inApp: true, push: true))
        XCTAssertNil(prefs.toFirestoreMap()["account_warning"])
    }
}
