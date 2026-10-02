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
        private var imageGate: CheckedContinuation<Void, Never>?
        private var imageGateArmed = false
        private var imageGateReleased = false
        private(set) var subscribeCount = 0
        private(set) var observedUids: [String] = []
        private(set) var addCount = 0
        private(set) var updates: [(String, VehicleInput)] = []
        private(set) var deletedIds: [String] = []
        private(set) var mainChanges: [(String, Bool)] = []
        private(set) var addedPhotos: [(String, String, Data)] = []
        private(set) var removedPhotos: [(String, String)] = []
        private(set) var reorderedPhotos: [(String, [String])] = []
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

        func updateVehicle(vehicleId: String, input: VehicleInput) async throws {
            record { updates.append((vehicleId, input)) }
        }

        func deleteVehicle(vehicleId: String) async throws {
            record { deletedIds.append(vehicleId) }
        }

        func setMainVehicle(vehicleId: String, isMain: Bool) async throws {
            record { mainChanges.append((vehicleId, isMain)) }
        }

        func addVehiclePhoto(uid: String, vehicleId: String, jpegData: Data) async throws {
            record { addedPhotos.append((uid, vehicleId, jpegData)) }
        }

        func removeVehiclePhoto(vehicleId: String, photoPath: String) async throws {
            record { removedPhotos.append((vehicleId, photoPath)) }
        }

        func reorderVehiclePhotos(vehicleId: String, orderedPaths: [String]) async throws {
            record { reorderedPhotos.append((vehicleId, orderedPaths)) }
        }

        private func record(_ mutation: () -> Void) {
            lock.lock()
            mutation()
            lock.unlock()
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
            let gated = beginImageResolve()
            if gated {
                await withCheckedContinuation { continuation in
                    parkOrResumeImage(continuation)
                }
            }
            return lookupImageURL(imagePath)
        }

        /// Counts the attempt and consumes the gate arming, synchronously.
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

        private func parkOrResumeImage(_ continuation: CheckedContinuation<Void, Never>) {
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

        /// Arms the gate: the NEXT imageDownloadURL call suspends until
        /// ``releaseImageGate()`` — for pinning in-flight-resolution edges.
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
    }

    private final class FakeSubscriptionStateRepository: SubscriptionStateRepository,
        @unchecked Sendable
    {
        private let lock = NSLock()
        private var initial: StoredSubscription?
        private var continuations: [UUID: AsyncStream<StoredSubscription?>.Continuation] = [:]
        private(set) var subscribeCount = 0
        private(set) var observedUids: [String] = []

        init(initial: StoredSubscription? = nil) {
            self.initial = initial
        }

        func subscription(uid: String) -> AsyncStream<StoredSubscription?> {
            lock.lock()
            subscribeCount += 1
            observedUids.append(uid)
            let first = initial
            lock.unlock()
            return AsyncStream { continuation in
                continuation.yield(first)
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

        func emit(_ snapshot: StoredSubscription?) {
            lock.lock()
            initial = snapshot
            let live = Array(continuations.values)
            lock.unlock()
            for continuation in live {
                continuation.yield(snapshot)
            }
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

    private static func storedSubscription(
        tier: String? = "plus",
        status: String = "active",
        entitlement: String = "member_monthly"
    ) -> StoredSubscription {
        StoredSubscription(
            tier: tier,
            status: status,
            entitlement: entitlement,
            userId: uid
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
    func testMissingSubscriptionDefaultsToCommunityAndSubscribesOnce() async {
        let vehicles = FakeVehiclesRepository()
        vehicles.script([.loaded([])])
        let subscriptions = FakeSubscriptionStateRepository(initial: nil)
        let coordinator = GarageCoordinator(
            repository: vehicles,
            subscriptionRepository: subscriptions,
            uid: Self.uid
        )

        coordinator.start()
        coordinator.start()
        await wait { subscriptions.subscribeCount == 1 }

        XCTAssertEqual(coordinator.effectiveSubscriptionTier, .community)
        XCTAssertEqual(coordinator.vehicleLimit, 2)
        XCTAssertEqual(subscriptions.observedUids, [Self.uid])
    }

    @MainActor
    func testLiveSubscriptionChangesUpdateGarageLimitWithoutFilteringCars() async {
        let existing = (1...6).map { Self.vehicle("v-\($0)") }
        let vehicles = FakeVehiclesRepository()
        vehicles.script([.loaded(existing)])
        let subscriptions = FakeSubscriptionStateRepository(
            initial: Self.storedSubscription(tier: "supporter")
        )
        let coordinator = GarageCoordinator(
            repository: vehicles,
            subscriptionRepository: subscriptions,
            uid: Self.uid
        )

        coordinator.start()
        await wait {
            coordinator.state == .loaded(existing) && coordinator.vehicleLimit == 10
        }

        subscriptions.emit(Self.storedSubscription(tier: "plus"))
        await wait { coordinator.vehicleLimit == 5 }

        XCTAssertEqual(coordinator.state, .loaded(existing))
        XCTAssertFalse(
            GarageAllowance.canAddVehicle(
                vehicleCount: existing.count,
                tier: coordinator.effectiveSubscriptionTier
            )
        )
    }

    @MainActor
    func testInactiveSubscriptionImmediatelyFallsBackToCommunity() async {
        let vehicles = FakeVehiclesRepository()
        vehicles.script([.loaded([])])
        let subscriptions = FakeSubscriptionStateRepository(
            initial: Self.storedSubscription(tier: "supporter")
        )
        let coordinator = GarageCoordinator(
            repository: vehicles,
            subscriptionRepository: subscriptions,
            uid: Self.uid
        )

        coordinator.start()
        await wait { coordinator.vehicleLimit == 10 }
        subscriptions.emit(Self.storedSubscription(tier: "supporter", status: "expired"))
        await wait { coordinator.vehicleLimit == 2 }

        XCTAssertEqual(coordinator.effectiveSubscriptionTier, .community)
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

    /// Re-opening the form mid-save (the sheet can be swipe-dismissed while
    /// a save is in flight) resets the status — but never OUT of saving,
    /// which would defeat the re-entrancy guard and let a second add start
    /// concurrently.
    @MainActor
    func testResetSaveStatusNeverInterruptsAnInFlightSave() async {
        let repository = FakeVehiclesRepository()
        repository.holdNextAdd()
        let coordinator = GarageCoordinator(repository: repository, uid: Self.uid)

        let inFlight = Task { await coordinator.addVehicle(Self.input) }
        await wait { coordinator.saveStatus == .saving }

        coordinator.resetSaveStatus()
        XCTAssertEqual(coordinator.saveStatus, .saving)

        let second = await coordinator.addVehicle(Self.input)
        XCTAssertNil(second)
        XCTAssertEqual(repository.addCount, 1)

        repository.releaseAddGate()
        _ = await inFlight.value
        XCTAssertEqual(coordinator.saveStatus, .saved)
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
    func testFailedPhotoResolutionKeepsThePlaceholderAndIsNotRetriedPerSnapshot() async {
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

        // The negative cache: a later snapshot must NOT re-attempt the
        // failed path — otherwise every listener emission would turn into a
        // Storage round-trip.
        repository.emit(.loaded(vehicles))
        await wait { coordinator.state == .loaded(vehicles) }
        await Task.yield()
        XCTAssertEqual(repository.imageResolveCount, 1)
    }

    @MainActor
    func testReloadWhileAResolutionIsInFlightDoesNotDuplicateIt() async {
        let repository = FakeVehiclesRepository()
        let path = "vehicleImages/uid-1/vehicle-a/cover.jpg"
        let url = URL(string: "https://example.test/cover.jpg")!
        repository.scriptImageURL(url, for: path)
        repository.holdNextImageResolve()
        let vehicles = [Self.vehicle("a", imagePath: path)]
        repository.script([.loaded(vehicles)])
        let coordinator = GarageCoordinator(repository: repository, uid: Self.uid)

        coordinator.start()
        await wait { repository.imageResolveCount == 1 }

        // Reload while the first resolution is still parked: the in-flight
        // path must stay in the attempted set, so the re-subscribed snapshot
        // must NOT start a second downloadURL() for the same path.
        coordinator.reload()
        await wait { coordinator.state == .loaded(vehicles) }
        await Task.yield()
        XCTAssertEqual(repository.imageResolveCount, 1)

        repository.releaseImageGate()
        await wait { coordinator.imageURLs[path] == url }
        XCTAssertEqual(repository.imageResolveCount, 1)
    }

    @MainActor
    func testReloadRetriesAFailedPhotoResolution() async {
        let repository = FakeVehiclesRepository()
        let path = "vehicleImages/uid-1/vehicle-a/cover.jpg"
        let vehicles = [Self.vehicle("a", imagePath: path)]
        repository.script([.loaded(vehicles)])
        let coordinator = GarageCoordinator(repository: repository, uid: Self.uid)

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

    // MARK: - owner management

    @MainActor
    func testUpdateUsesExistingIdAndTracksSaveSuccess() async {
        let repository = FakeVehiclesRepository()
        let coordinator = GarageCoordinator(repository: repository, uid: Self.uid)

        let result = await coordinator.saveVehicle(Self.input, editingVehicleId: "existing")

        XCTAssertEqual(result, "existing")
        XCTAssertEqual(coordinator.saveStatus, .saved)
        XCTAssertEqual(repository.updates.count, 1)
        XCTAssertEqual(repository.updates.first?.0, "existing")
        XCTAssertEqual(repository.addCount, 0)
    }

    @MainActor
    func testDeleteSetMainAndPhotoMutationsUseOwnerRepository() async {
        let repository = FakeVehiclesRepository()
        let coordinator = GarageCoordinator(repository: repository, uid: Self.uid)
        let bytes = Data([0xff, 0xd8, 0xff])

        let didSetMain = await coordinator.setMainVehicle("v1", isMain: true)
        let didAddPhoto = await coordinator.addPhoto(vehicleId: "v1", jpegData: bytes)
        let didRemovePhoto = await coordinator.removePhoto(vehicleId: "v1", photoPath: "p1")
        let didSetCover = await coordinator.setCover(
            vehicleId: "v1", photoPath: "p2", currentPaths: ["p1", "p2"]
        )
        let didDelete = await coordinator.deleteVehicle("v1")

        XCTAssertTrue(didSetMain)
        XCTAssertTrue(didAddPhoto)
        XCTAssertTrue(didRemovePhoto)
        XCTAssertTrue(didSetCover)
        XCTAssertTrue(didDelete)

        XCTAssertEqual(repository.mainChanges.first?.0, "v1")
        XCTAssertEqual(repository.mainChanges.first?.1, true)
        XCTAssertEqual(repository.addedPhotos.first?.0, Self.uid)
        XCTAssertEqual(repository.addedPhotos.first?.1, "v1")
        XCTAssertEqual(repository.addedPhotos.first?.2, bytes)
        XCTAssertEqual(repository.removedPhotos.first?.0, "v1")
        XCTAssertEqual(repository.removedPhotos.first?.1, "p1")
        XCTAssertEqual(repository.reorderedPhotos.first?.1, ["p2", "p1"])
        XCTAssertEqual(repository.deletedIds, ["v1"])
        XCTAssertEqual(coordinator.mutationStatus, .idle)
    }

    @MainActor
    func testPhotoMutationFailsClosedWithoutAuthenticatedUid() async {
        let repository = FakeVehiclesRepository()
        let coordinator = GarageCoordinator(repository: repository, uid: nil)

        let didAddPhoto = await coordinator.addPhoto(vehicleId: "v1", jpegData: Data([1]))
        XCTAssertFalse(didAddPhoto)
        XCTAssertEqual(coordinator.mutationStatus, .failed)
        XCTAssertTrue(repository.addedPhotos.isEmpty)
    }

    @MainActor
    func testAllGalleryPathsResolveRatherThanOnlyCover() async {
        let repository = FakeVehiclesRepository()
        let first = "vehicleImages/uid-1/a/first.jpg"
        let second = "vehicleImages/uid-1/a/second.jpg"
        repository.scriptImageURL(URL(string: "https://example.test/first.jpg")!, for: first)
        repository.scriptImageURL(URL(string: "https://example.test/second.jpg")!, for: second)
        let base = Self.vehicle("a", imagePath: first)
        let vehicle = Vehicle(
            id: base.id,
            make: base.make,
            model: base.model,
            makeId: base.makeId,
            modelId: base.modelId,
            modelYear: base.modelYear,
            powertrain: base.powertrain,
            engineDescription: base.engineDescription,
            modifications: base.modifications,
            registrationPlate: base.registrationPlate,
            imagePath: first,
            photoPaths: [first, second],
            isMainCar: false
        )
        repository.script([.loaded([vehicle])])
        let coordinator = GarageCoordinator(repository: repository, uid: Self.uid)

        coordinator.start()
        await wait { coordinator.imageURLs.count == 2 }

        XCTAssertEqual(repository.imageResolveCount, 2)
    }
}
