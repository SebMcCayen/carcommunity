import XCTest

@testable import KCC

/// Unit tests for the pure garage orchestration: every repository emission
/// maps to the right ``GarageUiState``, the config-less/no-session wirings
/// settle on unavailable, the add flow tracks ``VehicleSaveStatus`` and
/// returns the minted vehicle id, and cover photos resolve to URLs exactly
/// once per path. No Firebase — the repository is a scripted fake (same
/// conventions as EventsCoordinatorTests / ProfileCoordinatorTests).
final class GarageCoordinatorTests: XCTestCase {

    // MARK: - fakes

    private final class FakeVehiclesRepository: VehiclesRepository, @unchecked Sendable {
        private let lock = NSLock()
        private var pending: [GarageSnapshot] = []
        private var continuations: [UUID: AsyncStream<GarageSnapshot>.Continuation] = [:]
        private var addResult: Result<String, Error> = .success("vehicle-new")
        private var imageURLs: [String: URL] = [:]
        /// When set, addVehicle suspends until it is resumed — for pinning
        /// the re-entrancy guard while a save is in flight.
        private var addGate: CheckedContinuation<Void, Never>?
        private var addGateArmed = false
        /// True when release raced ahead of the gated call parking itself —
        /// the next park then resumes immediately instead of hanging.
        private var addGateReleased = false
        private(set) var subscribeCount = 0
        private(set) var observedUids: [String] = []
        private(set) var addCount = 0
        private(set) var imageResolveCount = 0

        /// Snapshots replayed to each FUTURE subscription (the listener's
        /// initial snapshot). The stream then stays open, like a real
        /// listener.
        func script(_ snapshots: [GarageSnapshot]) {
            lock.lock()
            pending = snapshots
            lock.unlock()
        }

        /// Pushes a snapshot to every LIVE subscription (a later listener
        /// update).
        func emit(_ snapshot: GarageSnapshot) {
            lock.lock()
            let live = Array(continuations.values)
            lock.unlock()
            for continuation in live {
                continuation.yield(snapshot)
            }
        }

        func scriptAddResult(_ result: Result<String, Error>) {
            lock.lock()
            addResult = result
            lock.unlock()
        }

        /// Arms the gate: the NEXT addVehicle call suspends until
        /// ``releaseAddGate()``.
        func holdNextAdd() {
            lock.lock()
            addGateArmed = true
            lock.unlock()
        }

        func releaseAddGate() {
            lock.lock()
            let gate = addGate
            addGate = nil
            if gate == nil { addGateReleased = true }
            lock.unlock()
            gate?.resume()
        }

        /// Registers the URL a given image path resolves to; an unregistered
        /// path resolves to nil (the real repository's failure posture).
        func scriptImageURL(_ url: URL, for path: String) {
            lock.lock()
            imageURLs[path] = url
            lock.unlock()
        }

        func vehicles(uid: String) -> AsyncStream<GarageSnapshot> {
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

        func addVehicle(_ input: VehicleInput) async throws -> String {
            // NSLock is unavailable directly in async contexts; hop through
            // synchronous helpers so no critical section ever suspends.
            let (gated, result) = recordAdd()
            if gated {
                await withCheckedContinuation { continuation in
                    parkOrResume(continuation)
                }
            }
            return try result.get()
        }

        private func recordAdd() -> (gated: Bool, result: Result<String, Error>) {
            lock.lock()
            defer { lock.unlock() }
            addCount += 1
            let gated = addGateArmed
            addGateArmed = false
            return (gated, addResult)
        }

        private func parkOrResume(_ continuation: CheckedContinuation<Void, Never>) {
            lock.lock()
            if addGateReleased {
                addGateReleased = false
                lock.unlock()
                continuation.resume()
            } else {
                addGate = continuation
                lock.unlock()
            }
        }

        func imageDownloadURL(for imagePath: String) async -> URL? {
            resolveSynchronized(imagePath)
        }

        private func resolveSynchronized(_ imagePath: String) -> URL? {
            lock.lock()
            defer { lock.unlock() }
            imageResolveCount += 1
            return imageURLs[imagePath]
        }
    }

    // MARK: - fixtures

    private static let uid = "uid-1"

    private static func vehicle(
        _ id: String,
        make: String = "Volvo",
        model: String = "240",
        imagePath: String? = nil
    ) -> Vehicle {
        Vehicle(
            id: id,
            make: make,
            model: model,
            makeId: "volvo",
            modelId: "240",
            modelYear: 1988,
            powertrain: .petrol,
            engineDescription: nil,
            modifications: nil,
            registrationPlate: nil,
            imagePath: imagePath,
            photoPaths: imagePath.map { [$0] } ?? [],
            isMainCar: false
        )
    }

    private static let input = VehicleInput(
        makeId: "volvo",
        modelId: "240",
        modelYear: 1988,
        powertrain: .petrol,
        engineDescription: nil,
        modifications: nil,
        registrationPlate: nil
    )

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
        let coordinator = GarageCoordinator(
            repository: FakeVehiclesRepository(), uid: Self.uid
        )
        XCTAssertEqual(coordinator.state, .loading)
    }

    @MainActor
    func testNilRepositorySettlesOnUnavailable() {
        let coordinator = GarageCoordinator(repository: nil, uid: Self.uid)
        XCTAssertEqual(coordinator.state, .unavailable)
        coordinator.start()
        coordinator.reload()
        XCTAssertEqual(coordinator.state, .unavailable)
    }

    @MainActor
    func testNilUidSettlesOnUnavailable() {
        let repository = FakeVehiclesRepository()
        let coordinator = GarageCoordinator(repository: repository, uid: nil)
        XCTAssertEqual(coordinator.state, .unavailable)
        coordinator.start()
        XCTAssertEqual(repository.subscribeCount, 0)
    }

    @MainActor
    func testLoadedSnapshotWithVehiclesBecomesLoaded() async {
        let repository = FakeVehiclesRepository()
        let vehicles = [Self.vehicle("a"), Self.vehicle("b")]
        repository.script([.loaded(vehicles)])
        let coordinator = GarageCoordinator(repository: repository, uid: Self.uid)

        coordinator.start()
        await wait { coordinator.state == .loaded(vehicles) }
        XCTAssertEqual(repository.subscribeCount, 1)
        XCTAssertEqual(repository.observedUids, [Self.uid])
    }

    @MainActor
    func testLoadedSnapshotWithNoVehiclesBecomesEmpty() async {
        let repository = FakeVehiclesRepository()
        repository.script([.loaded([])])
        let coordinator = GarageCoordinator(repository: repository, uid: Self.uid)

        coordinator.start()
        await wait { coordinator.state == .empty }
    }

    @MainActor
    func testListenerFailureCarriesTheBareStatusCode() async {
        let repository = FakeVehiclesRepository()
        repository.script([.failed(code: "PERMISSION_DENIED")])
        let coordinator = GarageCoordinator(repository: repository, uid: Self.uid)

        coordinator.start()
        await wait { coordinator.state == .failed(code: "PERMISSION_DENIED") }
    }

    @MainActor
    func testLaterSnapshotUpdatesTheList() async {
        let repository = FakeVehiclesRepository()
        let initial = [Self.vehicle("a")]
        repository.script([.loaded(initial)])
        let coordinator = GarageCoordinator(repository: repository, uid: Self.uid)

        coordinator.start()
        await wait { coordinator.state == .loaded(initial) }

        let updated = [Self.vehicle("a"), Self.vehicle("b")]
        repository.emit(.loaded(updated))
        await wait { coordinator.state == .loaded(updated) }
        XCTAssertEqual(repository.subscribeCount, 1)
    }

    // MARK: - start/reload semantics

    @MainActor
    func testStartIsIdempotent() async {
        let repository = FakeVehiclesRepository()
        let vehicles = [Self.vehicle("a")]
        repository.script([.loaded(vehicles)])
        let coordinator = GarageCoordinator(repository: repository, uid: Self.uid)

        coordinator.start()
        await wait { coordinator.state == .loaded(vehicles) }
        coordinator.start()

        // A second start must neither re-subscribe nor flash back to loading.
        XCTAssertEqual(repository.subscribeCount, 1)
        XCTAssertEqual(coordinator.state, .loaded(vehicles))
    }

    @MainActor
    func testReloadReturnsToLoadingAndResubscribes() async {
        let repository = FakeVehiclesRepository()
        repository.script([.failed(code: "UNAVAILABLE")])
        let coordinator = GarageCoordinator(repository: repository, uid: Self.uid)

        coordinator.start()
        await wait { coordinator.state == .failed(code: "UNAVAILABLE") }

        // The re-subscribed stream emits nothing yet — reload must show
        // loading, not linger on the stale failure.
        repository.script([])
        coordinator.reload()
        XCTAssertEqual(coordinator.state, .loading)
        XCTAssertEqual(repository.subscribeCount, 2)
    }

    // MARK: - add flow

    @MainActor
    func testAddVehicleSuccessTracksStatusAndReturnsTheMintedId() async {
        let repository = FakeVehiclesRepository()
        repository.scriptAddResult(.success("vehicle-42"))
        let coordinator = GarageCoordinator(repository: repository, uid: Self.uid)

        let vehicleId = await coordinator.addVehicle(Self.input)

        XCTAssertEqual(vehicleId, "vehicle-42")
        XCTAssertEqual(coordinator.saveStatus, .saved)
        XCTAssertEqual(repository.addCount, 1)
    }

    @MainActor
    func testAddVehicleFailureBecomesFailedAndReturnsNil() async {
        let repository = FakeVehiclesRepository()
        repository.scriptAddResult(.failure(KccFunctionsError(code: .failedPrecondition)))
        let coordinator = GarageCoordinator(repository: repository, uid: Self.uid)

        let vehicleId = await coordinator.addVehicle(Self.input)

        XCTAssertNil(vehicleId)
        XCTAssertEqual(coordinator.saveStatus, .failed)
    }

    @MainActor
    func testAddVehicleIsBlockedWhileAnotherSaveIsInFlight() async {
        let repository = FakeVehiclesRepository()
        repository.holdNextAdd()
        let coordinator = GarageCoordinator(repository: repository, uid: Self.uid)

        let first = Task { await coordinator.addVehicle(Self.input) }
        await wait { coordinator.saveStatus == .saving }

        // Re-entrant while saving: refused without touching the repository.
        let second = await coordinator.addVehicle(Self.input)
        XCTAssertNil(second)
        XCTAssertEqual(repository.addCount, 1)

        repository.releaseAddGate()
        let firstId = await first.value
        XCTAssertEqual(firstId, "vehicle-new")
        XCTAssertEqual(coordinator.saveStatus, .saved)
    }

    @MainActor
    func testAddVehicleWithoutRepositoryFails() async {
        let coordinator = GarageCoordinator(repository: nil, uid: nil)
        let vehicleId = await coordinator.addVehicle(Self.input)
        XCTAssertNil(vehicleId)
        XCTAssertEqual(coordinator.saveStatus, .failed)
    }

    @MainActor
    func testResetSaveStatusReturnsToIdle() async {
        let repository = FakeVehiclesRepository()
        repository.scriptAddResult(.failure(KccFunctionsError(code: .unavailable)))
        let coordinator = GarageCoordinator(repository: repository, uid: Self.uid)

        await coordinator.addVehicle(Self.input)
        XCTAssertEqual(coordinator.saveStatus, .failed)

        coordinator.resetSaveStatus()
        XCTAssertEqual(coordinator.saveStatus, .idle)
    }

    // MARK: - cover photo resolution

    @MainActor
    func testCoverPhotoResolvesToAURLOncePerPath() async {
        let repository = FakeVehiclesRepository()
        let path = "vehicleImages/uid-1/vehicle-a/cover.jpg"
        let url = URL(string: "https://example.test/cover.jpg")!
        repository.scriptImageURL(url, for: path)
        let vehicles = [Self.vehicle("a", imagePath: path)]
        repository.script([.loaded(vehicles)])
        let coordinator = GarageCoordinator(repository: repository, uid: Self.uid)

        coordinator.start()
        await wait { coordinator.imageURLs[path] == url }

        // A later snapshot with the SAME path must not re-pay the round-trip.
        repository.emit(.loaded(vehicles))
        await wait { coordinator.state == .loaded(vehicles) }
        await Task.yield()
        XCTAssertEqual(repository.imageResolveCount, 1)
    }

    @MainActor
    func testFailedPhotoResolutionKeepsThePlaceholder() async {
        let repository = FakeVehiclesRepository()
        let path = "vehicleImages/uid-1/vehicle-a/cover.jpg"
        // No scripted URL: resolution returns nil (the real repository's
        // failure posture).
        let vehicles = [Self.vehicle("a", imagePath: path)]
        repository.script([.loaded(vehicles)])
        let coordinator = GarageCoordinator(repository: repository, uid: Self.uid)

        coordinator.start()
        await wait { repository.imageResolveCount >= 1 }
        await wait { coordinator.state == .loaded(vehicles) }
        XCTAssertNil(coordinator.imageURLs[path])
    }
}
