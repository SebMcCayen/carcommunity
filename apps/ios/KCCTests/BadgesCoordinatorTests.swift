import XCTest

@testable import KCC

/// Unit tests for the pure badge-wall orchestration: every repository emission
/// maps to the right ``BadgesUiState``, the config-less/no-session wirings
/// settle on unavailable, and the earned-badges snapshot and the
/// `getMyProgress` counters MERGE into one wall regardless of arrival order —
/// a progress failure only drops the bars, never the wall. No Firebase — the
/// repository is a scripted fake (same conventions as GarageCoordinatorTests).
final class BadgesCoordinatorTests: XCTestCase {

    // MARK: - fake

    private final class FakeBadgesRepository: BadgesRepository, @unchecked Sendable {
        private let lock = NSLock()
        private var pending: [BadgesSnapshot] = []
        private var continuations: [UUID: AsyncStream<BadgesSnapshot>.Continuation] = [:]
        private var progressResult: BadgeCounters?
        private var uid: String? = "uid-1"
        /// When armed, fetchMyProgress suspends until ``releaseProgress()`` —
        /// for pinning the "counters arrive after the wall" re-merge.
        private var progressGate: CheckedContinuation<Void, Never>?
        private var progressGateArmed = false
        private var progressGateReleased = false
        private(set) var subscribeCount = 0
        private(set) var progressFetchCount = 0

        func scriptBadges(_ snapshots: [BadgesSnapshot]) {
            lock.lock()
            pending = snapshots
            lock.unlock()
        }

        func emitBadges(_ snapshot: BadgesSnapshot) {
            lock.lock()
            let live = Array(continuations.values)
            lock.unlock()
            for continuation in live {
                continuation.yield(snapshot)
            }
        }

        func scriptProgress(_ counters: BadgeCounters?) {
            lock.lock()
            progressResult = counters
            lock.unlock()
        }

        func setUid(_ value: String?) {
            lock.lock()
            uid = value
            lock.unlock()
        }

        func holdProgress() {
            lock.lock()
            progressGateArmed = true
            lock.unlock()
        }

        func releaseProgress() {
            lock.lock()
            let gate = progressGate
            progressGate = nil
            if gate == nil { progressGateReleased = true }
            lock.unlock()
            gate?.resume()
        }

        func observeBadges(uid: String) -> AsyncStream<BadgesSnapshot> {
            lock.lock()
            subscribeCount += 1
            let snapshots = pending
            lock.unlock()
            return AsyncStream { continuation in
                for snapshot in snapshots {
                    continuation.yield(snapshot)
                }
                let id = UUID()
                self.lock.lock()
                self.continuations[id] = continuation
                self.lock.unlock()
                continuation.onTermination = { [weak self] _ in
                    guard let self else { return }
                    self.lock.lock()
                    self.continuations[id] = nil
                    self.lock.unlock()
                }
            }
        }

        func fetchMyProgress() async -> BadgeCounters? {
            // NSLock is unavailable directly in async contexts; hop through
            // synchronous helpers so no critical section ever suspends (same
            // pattern as GarageCoordinatorTests' fake).
            let armed = beginFetch()
            if armed {
                await withCheckedContinuation { continuation in
                    parkOrResumeProgress(continuation)
                }
            }
            return readProgressResult()
        }

        /// Counts the fetch and consumes the gate arming, synchronously.
        private func beginFetch() -> Bool {
            lock.lock()
            defer { lock.unlock() }
            progressFetchCount += 1
            let armed = progressGateArmed
            progressGateArmed = false
            return armed
        }

        /// Parks the continuation, or resumes immediately when a release
        /// already raced ahead of the park.
        private func parkOrResumeProgress(_ continuation: CheckedContinuation<Void, Never>) {
            lock.lock()
            if progressGateReleased {
                progressGateReleased = false
                lock.unlock()
                continuation.resume()
            } else {
                progressGate = continuation
                lock.unlock()
            }
        }

        private func readProgressResult() -> BadgeCounters? {
            lock.lock()
            defer { lock.unlock() }
            return progressResult
        }

        func currentUserId() -> String? {
            lock.lock()
            defer { lock.unlock() }
            return uid
        }
    }

    // MARK: - helpers

    @MainActor
    private func wait(
        timeout: TimeInterval = 2,
        file: StaticString = #filePath,
        line: UInt = #line,
        until predicate: () -> Bool
    ) async {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if predicate() { return }
            await Task.yield()
            try? await Task.sleep(nanoseconds: 1_000_000)
        }
        XCTFail("Timed out waiting for condition", file: file, line: line)
    }

    /// The kronjagare ladder's fraction in the current loaded/empty state, or
    /// nil — the merge probe (a bar appears only once counters land).
    private func kronjagareFraction(_ state: BadgesUiState) -> Double? {
        let showcase: BadgeShowcase
        switch state {
        case .loaded(let value), .empty(let value): showcase = value
        default: return nil
        }
        return showcase.ladders.first { $0.ladder.id == .kronjagare }?.fractionToNext
    }

    // MARK: - state mapping

    @MainActor
    func testInitialStateIsLoadingBeforeStart() {
        let coordinator = BadgesCoordinator(repository: FakeBadgesRepository(), uid: "uid-1")
        XCTAssertEqual(coordinator.state, .loading)
    }

    @MainActor
    func testNilRepositorySettlesOnUnavailable() {
        let coordinator = BadgesCoordinator(repository: nil, uid: "uid-1")
        XCTAssertEqual(coordinator.state, .unavailable)
        coordinator.start()
        XCTAssertEqual(coordinator.state, .unavailable)
    }

    @MainActor
    func testNilUidSettlesOnUnavailable() {
        let repository = FakeBadgesRepository()
        repository.setUid(nil)
        let coordinator = BadgesCoordinator(repository: repository)
        XCTAssertEqual(coordinator.state, .unavailable)
    }

    @MainActor
    func testUidResolvedFromRepositoryWhenNotPassed() {
        let repository = FakeBadgesRepository()
        repository.setUid("session-uid")
        let coordinator = BadgesCoordinator(repository: repository)
        XCTAssertEqual(coordinator.state, .loading)
    }

    @MainActor
    func testLoadedSnapshotWithBadgesBecomesLoaded() async {
        let repository = FakeBadgesRepository()
        repository.scriptProgress(nil)
        repository.scriptBadges([.loaded([Badge(key: "first_event", fallbackName: "First", awardedAt: Date())])])
        let coordinator = BadgesCoordinator(repository: repository, uid: "uid-1")
        coordinator.start()
        await wait {
            if case .loaded(let showcase) = coordinator.state { return showcase.earnedCount == 1 }
            return false
        }
    }

    @MainActor
    func testLoadedSnapshotWithNoBadgesBecomesEmptyCarryingFullWall() async {
        let repository = FakeBadgesRepository()
        repository.scriptProgress(nil)
        repository.scriptBadges([.loaded([])])
        let coordinator = BadgesCoordinator(repository: repository, uid: "uid-1")
        coordinator.start()
        await wait {
            if case .empty(let showcase) = coordinator.state {
                // The full locked catalog is still present in the empty state.
                return showcase.ladders.count == badgeLadders.count && showcase.earnedCount == 0
            }
            return false
        }
    }

    @MainActor
    func testUnknownOnlyBadgesSettleOnEmpty() async {
        let repository = FakeBadgesRepository()
        repository.scriptProgress(nil)
        repository.scriptBadges([.loaded([Badge(key: "retired_badge", fallbackName: "x", awardedAt: nil)])])
        let coordinator = BadgesCoordinator(repository: repository, uid: "uid-1")
        coordinator.start()
        await wait {
            if case .empty = coordinator.state { return true }
            return false
        }
    }

    @MainActor
    func testListenerFailureCarriesTheBareStatusCode() async {
        let repository = FakeBadgesRepository()
        repository.scriptProgress(nil)
        repository.scriptBadges([.failed(code: "PERMISSION_DENIED")])
        let coordinator = BadgesCoordinator(repository: repository, uid: "uid-1")
        coordinator.start()
        await wait { coordinator.state == .failed(code: "PERMISSION_DENIED") }
    }

    // MARK: - merge

    @MainActor
    func testCountersMergeWhenTheyResolveBeforeTheSnapshot() async {
        let repository = FakeBadgesRepository()
        // Counters known; badges arrive as a live emission afterwards.
        repository.scriptProgress(BadgeCounters(crownsCollected: 25)) // next silver@50 → 0.5
        let coordinator = BadgesCoordinator(repository: repository, uid: "uid-1")
        coordinator.start()
        // Give the progress task time to resolve, then emit the earned snapshot.
        await wait { repository.progressFetchCount == 1 }
        repository.emitBadges(.loaded([Badge(key: "kronjagare_brons", fallbackName: nil, awardedAt: nil)]))
        await wait {
            guard let fraction = self.kronjagareFraction(coordinator.state) else { return false }
            return abs(fraction - 0.5) < 0.0001
        }
    }

    @MainActor
    func testCountersRemergeWhenTheyResolveAfterTheSnapshot() async {
        let repository = FakeBadgesRepository()
        repository.holdProgress()
        repository.scriptProgress(BadgeCounters(crownsCollected: 25))
        repository.scriptBadges([.loaded([Badge(key: "kronjagare_brons", fallbackName: nil, awardedAt: nil)])])
        let coordinator = BadgesCoordinator(repository: repository, uid: "uid-1")
        coordinator.start()
        // The wall loads first WITHOUT a bar (counters still gated).
        await wait {
            if case .loaded = coordinator.state { return self.kronjagareFraction(coordinator.state) == nil }
            return false
        }
        // Counters land → the same wall re-merges with the bar.
        repository.releaseProgress()
        await wait {
            guard let fraction = self.kronjagareFraction(coordinator.state) else { return false }
            return abs(fraction - 0.5) < 0.0001
        }
    }

    @MainActor
    func testProgressFailureLeavesWallWithoutBars() async {
        let repository = FakeBadgesRepository()
        repository.scriptProgress(nil) // callable failed / unavailable
        repository.scriptBadges([.loaded([Badge(key: "kronjagare_brons", fallbackName: nil, awardedAt: nil)])])
        let coordinator = BadgesCoordinator(repository: repository, uid: "uid-1")
        coordinator.start()
        await wait {
            if case .loaded = coordinator.state { return true }
            return false
        }
        // The wall is fully usable; the ladder just has no bar.
        XCTAssertNil(kronjagareFraction(coordinator.state))
    }

    // MARK: - lifecycle

    @MainActor
    func testStartIsIdempotent() async {
        let repository = FakeBadgesRepository()
        repository.scriptProgress(nil)
        repository.scriptBadges([.loaded([Badge(key: "first_event", fallbackName: "First", awardedAt: nil)])])
        let coordinator = BadgesCoordinator(repository: repository, uid: "uid-1")
        coordinator.start()
        await wait {
            if case .loaded = coordinator.state { return true }
            return false
        }
        coordinator.start()
        XCTAssertEqual(repository.subscribeCount, 1)
    }

    @MainActor
    func testReloadReturnsToLoadingAndResubscribes() async {
        let repository = FakeBadgesRepository()
        repository.scriptProgress(nil)
        repository.scriptBadges([.failed(code: "UNAVAILABLE")])
        let coordinator = BadgesCoordinator(repository: repository, uid: "uid-1")
        coordinator.start()
        await wait { coordinator.state == .failed(code: "UNAVAILABLE") }
        coordinator.reload()
        // The listener re-attaches synchronously on reload.
        XCTAssertEqual(repository.subscribeCount, 2)
        // The fresh progress fetch is dispatched on a task; await it.
        await wait { repository.progressFetchCount == 2 }
    }
}
