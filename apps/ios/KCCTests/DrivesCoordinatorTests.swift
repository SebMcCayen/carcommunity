import XCTest

@testable import KCC

/// Unit tests for the pure drives-history orchestration: every repository
/// emission maps to the right ``DrivesUiState``, the config-less/no-session
/// wirings settle on unavailable, start/reload keep the events-port
/// semantics, and car photos resolve to URLs exactly once per path with a
/// negative cache for failed resolutions. No Firebase — the repository is a
/// scripted fake (same conventions as ``EventsCoordinatorTests`` /
/// ``ProfileCoordinatorTests``).
final class DrivesCoordinatorTests: XCTestCase {

    // MARK: - fakes

    private final class FakeDrivesRepository: DrivesRepository, @unchecked Sendable {
        private let lock = NSLock()
        private var pending: [DrivesSnapshot] = []
        private var continuations: [UUID: AsyncStream<DrivesSnapshot>.Continuation] = [:]
        private var imageURLs: [String: URL] = [:]
        /// When armed, the NEXT imageDownloadURL call suspends until
        /// released — for pinning in-flight-resolution edges.
        private var imageGate: CheckedContinuation<Void, Never>?
        private var imageGateArmed = false
        /// True when release raced ahead of the gated call parking itself —
        /// the next park then resumes immediately instead of hanging.
        private var imageGateReleased = false
        private(set) var subscribeCount = 0
        private(set) var observedUids: [String] = []
        private(set) var imageResolveCount = 0

        /// Snapshots replayed to each FUTURE subscription (the listener's
        /// initial snapshot). The stream then stays open, like a real
        /// listener.
        func script(_ snapshots: [DrivesSnapshot]) {
            lock.lock()
            pending = snapshots
            lock.unlock()
        }

        /// Pushes a snapshot to every LIVE subscription (a later listener
        /// update).
        func emit(_ snapshot: DrivesSnapshot) {
            lock.lock()
            let live = Array(continuations.values)
            lock.unlock()
            for continuation in live {
                continuation.yield(snapshot)
            }
        }

        /// Registers the URL a given image path resolves to; an unregistered
        /// path resolves to nil (the real repository's failure posture).
        func scriptImageURL(_ url: URL, for path: String) {
            lock.lock()
            imageURLs[path] = url
            lock.unlock()
        }

        func holdNextImageResolve() {
            lock.lock()
            imageGateArmed = true
            lock.unlock()
        }

        func releaseImageGate() {
            lock.lock()
            let gate = imageGate
            imageGate = nil
            if gate == nil { imageGateReleased = true }
            lock.unlock()
            gate?.resume()
        }

        func drives(uid: String) -> AsyncStream<DrivesSnapshot> {
            lock.lock()
            subscribeCount += 1
            observedUids.append(uid)
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

        func imageDownloadURL(for imagePath: String) async -> URL? {
            // NSLock is unavailable directly in async contexts; hop through
            // synchronous helpers so no critical section ever suspends.
            let gated = beginImageResolve()
            if gated {
                await withCheckedContinuation { continuation in
                    parkOrResume(continuation)
                }
            }
            return lookupImageURL(imagePath)
        }

        private func beginImageResolve() -> Bool {
            lock.lock()
            defer { lock.unlock() }
            imageResolveCount += 1
            let gated = imageGateArmed
            imageGateArmed = false
            return gated
        }

        private func lookupImageURL(_ imagePath: String) -> URL? {
            lock.lock()
            defer { lock.unlock() }
            return imageURLs[imagePath]
        }

        private func parkOrResume(_ continuation: CheckedContinuation<Void, Never>) {
            lock.lock()
            if imageGateReleased {
                imageGateReleased = false
                lock.unlock()
                continuation.resume()
            } else {
                imageGate = continuation
                lock.unlock()
            }
        }
    }

    // MARK: - fixtures

    private static let uid = "uid-1"

    private static func drive(_ id: String, carImagePath: String? = nil) -> SavedDrive {
        SavedDrive(
            id: id,
            title: "Drive \(id)",
            distanceMeters: 1_000,
            durationSeconds: 600,
            averageSpeedMetersPerSecond: nil,
            startedAt: nil,
            endedAt: nil,
            createdAt: Date(timeIntervalSince1970: 1_000),
            maxSpeedMetersPerSecond: nil,
            carImagePath: carImagePath,
            convoyMembers: []
        )
    }

    /// Polls until `predicate` holds, yielding to let the coordinator's
    /// tasks drain. Fails the test on timeout.
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

    // MARK: - state mapping

    @MainActor
    func testInitialStateIsLoadingBeforeStart() {
        let coordinator = DrivesCoordinator(
            repository: FakeDrivesRepository(), uid: Self.uid
        )
        XCTAssertEqual(coordinator.state, .loading)
    }

    @MainActor
    func testNilRepositorySettlesOnUnavailable() {
        let coordinator = DrivesCoordinator(repository: nil, uid: Self.uid)
        XCTAssertEqual(coordinator.state, .unavailable)
        coordinator.start()
        coordinator.reload()
        XCTAssertEqual(coordinator.state, .unavailable)
    }

    @MainActor
    func testNilUidSettlesOnUnavailable() {
        let repository = FakeDrivesRepository()
        let coordinator = DrivesCoordinator(repository: repository, uid: nil)
        XCTAssertEqual(coordinator.state, .unavailable)
        coordinator.start()
        XCTAssertEqual(repository.subscribeCount, 0)
    }

    @MainActor
    func testLoadedSnapshotWithDrivesBecomesLoaded() async {
        let repository = FakeDrivesRepository()
        let drives = [Self.drive("a"), Self.drive("b")]
        repository.script([.loaded(drives)])
        let coordinator = DrivesCoordinator(repository: repository, uid: Self.uid)

        coordinator.start()
        await wait { coordinator.state == .loaded(drives) }
        XCTAssertEqual(repository.subscribeCount, 1)
        XCTAssertEqual(repository.observedUids, [Self.uid])
    }

    @MainActor
    func testLoadedSnapshotWithNoDrivesBecomesEmpty() async {
        let repository = FakeDrivesRepository()
        repository.script([.loaded([])])
        let coordinator = DrivesCoordinator(repository: repository, uid: Self.uid)

        coordinator.start()
        await wait { coordinator.state == .empty }
    }

    @MainActor
    func testListenerFailureCarriesTheBareStatusCode() async {
        let repository = FakeDrivesRepository()
        repository.script([.failed(code: "PERMISSION_DENIED")])
        let coordinator = DrivesCoordinator(repository: repository, uid: Self.uid)

        coordinator.start()
        await wait { coordinator.state == .failed(code: "PERMISSION_DENIED") }
    }

    @MainActor
    func testLaterSnapshotUpdatesTheList() async {
        let repository = FakeDrivesRepository()
        let initial = [Self.drive("a")]
        repository.script([.loaded(initial)])
        let coordinator = DrivesCoordinator(repository: repository, uid: Self.uid)

        coordinator.start()
        await wait { coordinator.state == .loaded(initial) }

        // A delete reconciled by the listener, or a new save: the list just
        // follows the next emission.
        let updated = [Self.drive("a"), Self.drive("b")]
        repository.emit(.loaded(updated))
        await wait { coordinator.state == .loaded(updated) }
        XCTAssertEqual(repository.subscribeCount, 1)
    }

    // MARK: - start/reload semantics

    @MainActor
    func testStartIsIdempotent() async {
        let repository = FakeDrivesRepository()
        let drives = [Self.drive("a")]
        repository.script([.loaded(drives)])
        let coordinator = DrivesCoordinator(repository: repository, uid: Self.uid)

        coordinator.start()
        await wait { coordinator.state == .loaded(drives) }
        coordinator.start()

        // A second start must neither re-subscribe nor flash back to loading.
        XCTAssertEqual(repository.subscribeCount, 1)
        XCTAssertEqual(coordinator.state, .loaded(drives))
    }

    @MainActor
    func testReloadReturnsToLoadingAndResubscribes() async {
        let repository = FakeDrivesRepository()
        repository.script([.failed(code: "UNAVAILABLE")])
        let coordinator = DrivesCoordinator(repository: repository, uid: Self.uid)

        coordinator.start()
        await wait { coordinator.state == .failed(code: "UNAVAILABLE") }

        // The re-subscribed stream emits nothing yet — reload must show
        // loading, not linger on the stale failure.
        repository.script([])
        coordinator.reload()
        XCTAssertEqual(coordinator.state, .loading)
        XCTAssertEqual(repository.subscribeCount, 2)
    }

    // MARK: - car photo resolution

    @MainActor
    func testCarPhotoResolvesToAURLOncePerPath() async {
        let repository = FakeDrivesRepository()
        let path = "vehicleImages/uid-1/car-a/cover.jpg"
        let url = URL(string: "https://example.test/cover.jpg")!
        repository.scriptImageURL(url, for: path)
        let drives = [Self.drive("a", carImagePath: path)]
        repository.script([.loaded(drives)])
        let coordinator = DrivesCoordinator(repository: repository, uid: Self.uid)

        coordinator.start()
        await wait { coordinator.imageURLs[path] == url }

        // A later snapshot with the SAME path must not re-pay the round-trip.
        repository.emit(.loaded(drives))
        await wait { coordinator.state == .loaded(drives) }
        await Task.yield()
        XCTAssertEqual(repository.imageResolveCount, 1)
    }

    @MainActor
    func testSharedPathAcrossDrivesResolvesOnce() async {
        // Several drives in the same car share one carImagePath — one
        // Storage round-trip serves them all.
        let repository = FakeDrivesRepository()
        let path = "vehicleImages/uid-1/car-a/cover.jpg"
        let url = URL(string: "https://example.test/cover.jpg")!
        repository.scriptImageURL(url, for: path)
        let drives = [
            Self.drive("a", carImagePath: path),
            Self.drive("b", carImagePath: path),
        ]
        repository.script([.loaded(drives)])
        let coordinator = DrivesCoordinator(repository: repository, uid: Self.uid)

        coordinator.start()
        await wait { coordinator.imageURLs[path] == url }
        await Task.yield()
        XCTAssertEqual(repository.imageResolveCount, 1)
    }

    @MainActor
    func testFailedPhotoResolutionKeepsThePlaceholderAndIsNotRetriedPerSnapshot() async {
        let repository = FakeDrivesRepository()
        let path = "vehicleImages/uid-1/car-a/cover.jpg"
        // No scripted URL: resolution returns nil (the real repository's
        // failure posture).
        let drives = [Self.drive("a", carImagePath: path)]
        repository.script([.loaded(drives)])
        let coordinator = DrivesCoordinator(repository: repository, uid: Self.uid)

        coordinator.start()
        await wait { repository.imageResolveCount >= 1 }
        await wait { coordinator.state == .loaded(drives) }
        XCTAssertNil(coordinator.imageURLs[path])

        // The negative cache: a later snapshot must NOT re-attempt the
        // failed path — otherwise every listener emission would turn into a
        // Storage round-trip.
        repository.emit(.loaded(drives))
        await wait { coordinator.state == .loaded(drives) }
        await Task.yield()
        XCTAssertEqual(repository.imageResolveCount, 1)
    }

    @MainActor
    func testReloadWhileAResolutionIsInFlightDoesNotDuplicateIt() async {
        let repository = FakeDrivesRepository()
        let path = "vehicleImages/uid-1/car-a/cover.jpg"
        let url = URL(string: "https://example.test/cover.jpg")!
        repository.scriptImageURL(url, for: path)
        repository.holdNextImageResolve()
        let drives = [Self.drive("a", carImagePath: path)]
        repository.script([.loaded(drives)])
        let coordinator = DrivesCoordinator(repository: repository, uid: Self.uid)

        coordinator.start()
        await wait { repository.imageResolveCount == 1 }

        // Reload while the first resolution is still parked: the in-flight
        // path must stay in the attempted set, so the re-subscribed snapshot
        // must NOT start a second downloadURL() for the same path.
        coordinator.reload()
        await wait { coordinator.state == .loaded(drives) }
        await Task.yield()
        XCTAssertEqual(repository.imageResolveCount, 1)

        repository.releaseImageGate()
        await wait { coordinator.imageURLs[path] == url }
        XCTAssertEqual(repository.imageResolveCount, 1)
    }

    @MainActor
    func testReloadRetriesAFailedPhotoResolution() async {
        let repository = FakeDrivesRepository()
        let path = "vehicleImages/uid-1/car-a/cover.jpg"
        let drives = [Self.drive("a", carImagePath: path)]
        repository.script([.loaded(drives)])
        let coordinator = DrivesCoordinator(repository: repository, uid: Self.uid)

        coordinator.start()
        await wait { repository.imageResolveCount >= 1 }
        XCTAssertNil(coordinator.imageURLs[path])

        // The photo becomes reachable; the explicit retry affordance clears
        // the negative cache and resolves it.
        let url = URL(string: "https://example.test/cover.jpg")!
        repository.scriptImageURL(url, for: path)
        coordinator.reload()
        await wait { coordinator.imageURLs[path] == url }
        XCTAssertEqual(repository.imageResolveCount, 2)
    }
}
