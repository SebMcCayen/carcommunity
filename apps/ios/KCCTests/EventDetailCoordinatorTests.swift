import XCTest

@testable import KCC

/// Unit tests for the pure detail + RSVP orchestration — the iOS counterpart
/// of Android's `RsvpCoordinatorTest` plus the detail wiring `EventsRoute`
/// derives: teaser loading/loaded/failed, the member-gated detail
/// subscription, the observed-RSVP selection, and the write's
/// saving/idle/failed lifecycle with PII-safe code mapping. No Firebase —
/// the repository is a scripted fake.
final class EventDetailCoordinatorTests: XCTestCase {

    // MARK: - fakes

    /// One scriptable listener: replays `pending` to each new subscription
    /// (the listener's initial snapshot), then stays open for `emit` (a later
    /// listener update) — the same shape as the list tests' fake.
    private final class StreamScript<Value: Sendable>: @unchecked Sendable {
        private let lock = NSLock()
        private var pending: [Value] = []
        private var continuations: [UUID: AsyncStream<Value>.Continuation] = [:]
        private(set) var subscribeCount = 0

        func script(_ values: [Value]) {
            lock.lock()
            pending = values
            lock.unlock()
        }

        func emit(_ value: Value) {
            lock.lock()
            let live = Array(continuations.values)
            lock.unlock()
            for continuation in live {
                continuation.yield(value)
            }
        }

        func stream() -> AsyncStream<Value> {
            lock.lock()
            subscribeCount += 1
            let values = pending
            lock.unlock()
            return AsyncStream { continuation in
                for value in values {
                    continuation.yield(value)
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
    }

    private final class FakeEventsRepository: EventsRepository, @unchecked Sendable {
        private let lock = NSLock()

        let teaser = StreamScript<EventSummary?>()
        let detail = StreamScript<EventDetail?>()
        let rsvp = StreamScript<RsvpStatus?>()

        private var uid: String?
        private var rsvpError: Error?
        private var holdRsvp = false
        private var heldWrites: [CheckedContinuation<Void, Never>] = []
        private var recorded: [(eventId: String, uid: String, status: RsvpStatus)] = []

        init(uid: String? = "uid-1") {
            self.uid = uid
        }

        var submitted: [(eventId: String, uid: String, status: RsvpStatus)] {
            lock.lock()
            defer { lock.unlock() }
            return recorded
        }

        /// Scripts the next writes to fail with `error` (nil = succeed).
        func failRsvp(with error: Error?) {
            lock.lock()
            rsvpError = error
            lock.unlock()
        }

        /// When held, submitRsvp suspends until ``releaseHeldWrites()`` — so
        /// a test can observe the saving state deterministically.
        func holdRsvpWrites(_ hold: Bool) {
            lock.lock()
            holdRsvp = hold
            lock.unlock()
        }

        func releaseHeldWrites() {
            lock.lock()
            let held = heldWrites
            heldWrites = []
            lock.unlock()
            for continuation in held {
                continuation.resume()
            }
        }

        // MARK: EventsRepository

        /// Unused by the detail slice — finishes immediately so an
        /// accidental consumer can never hang a test.
        func publishedEvents() -> AsyncStream<EventsListSnapshot> {
            AsyncStream { $0.finish() }
        }

        func event(withId eventId: String) -> AsyncStream<EventSummary?> {
            teaser.stream()
        }

        func eventDetail(eventId: String) -> AsyncStream<EventDetail?> {
            detail.stream()
        }

        func myRsvp(eventId: String, uid: String) -> AsyncStream<RsvpStatus?> {
            rsvp.stream()
        }

        /// Synchronous record + script snapshot (NSLock cannot be taken from
        /// an async context under Swift 6).
        private func recordWrite(
            _ eventId: String, _ uid: String, _ status: RsvpStatus
        ) -> (hold: Bool, error: Error?) {
            lock.lock()
            defer { lock.unlock() }
            recorded.append((eventId, uid, status))
            return (holdRsvp, rsvpError)
        }

        func submitRsvp(eventId: String, uid: String, status: RsvpStatus) async throws {
            let script = recordWrite(eventId, uid, status)
            if script.hold {
                await withCheckedContinuation { continuation in
                    lock.lock()
                    heldWrites.append(continuation)
                    lock.unlock()
                }
            }
            if let error = script.error { throw error }
        }

        func currentUserId() -> String? {
            lock.lock()
            defer { lock.unlock() }
            return uid
        }
    }

    // MARK: - fixtures

    private static func event(
        _ id: String = "e1",
        status: EventStatus = .published,
        going: Int = 0
    ) -> EventSummary {
        EventSummary(
            id: id,
            title: "Event \(id)",
            summary: nil,
            startsAt: Date(timeIntervalSince1970: 1_700_000_000),
            endsAt: nil,
            approximateArea: nil,
            locationName: nil,
            latitude: nil,
            longitude: nil,
            isOfficial: false,
            status: status,
            counts: RsvpCounts(going: going, maybe: 0, notGoing: 0)
        )
    }

    @MainActor
    private func makeCoordinator(
        repository: FakeEventsRepository,
        passesMemberGate: Bool = true
    ) -> EventDetailCoordinator {
        EventDetailCoordinator(
            repository: repository,
            eventId: "e1",
            passesMemberGate: passesMemberGate
        )
    }

    /// Polls until `predicate` holds, yielding to let the coordinator's
    /// subscription tasks drain their streams. Fails the test on timeout.
    @MainActor
    private func waitFor(
        timeout: TimeInterval = 2,
        file: StaticString = #filePath,
        line: UInt = #line,
        _ describe: @autoclosure () -> String = "condition",
        until predicate: () -> Bool
    ) async {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if predicate() { return }
            await Task.yield()
            try? await Task.sleep(nanoseconds: 1_000_000)
        }
        XCTFail("Timed out waiting for \(describe())", file: file, line: line)
    }

    // MARK: - teaser states

    @MainActor
    func testInitialStateIsLoadingBeforeStart() {
        let coordinator = makeCoordinator(repository: FakeEventsRepository())
        XCTAssertEqual(coordinator.state, .loading)
        XCTAssertEqual(coordinator.rsvpState, .idle)
    }

    @MainActor
    func testLoadedTeaserBecomesLoaded() async {
        let repository = FakeEventsRepository()
        let event = Self.event()
        repository.teaser.script([event])
        let coordinator = makeCoordinator(repository: repository)

        coordinator.start()
        await waitFor { coordinator.state == .loaded(event) }
    }

    @MainActor
    func testSettledNilTeaserBecomesFailed() async {
        let repository = FakeEventsRepository()
        repository.teaser.script([nil])
        let coordinator = makeCoordinator(repository: repository)

        coordinator.start()
        await waitFor { coordinator.state == .failed }
    }

    @MainActor
    func testReloadRecoversFromAFailure() async {
        let repository = FakeEventsRepository()
        repository.teaser.script([nil])
        let coordinator = makeCoordinator(repository: repository)

        coordinator.start()
        await waitFor { coordinator.state == .failed }

        let event = Self.event()
        repository.teaser.script([event])
        coordinator.reload()
        XCTAssertEqual(coordinator.state, .loading)
        await waitFor { coordinator.state == .loaded(event) }
        XCTAssertEqual(repository.teaser.subscribeCount, 2)
    }

    @MainActor
    func testLaterTeaserSnapshotUpdatesInPlace() async {
        let repository = FakeEventsRepository()
        let published = Self.event(going: 1)
        repository.teaser.script([published])
        let coordinator = makeCoordinator(repository: repository)

        coordinator.start()
        await waitFor { coordinator.state == .loaded(published) }

        // A live rsvpCounts bump (the events-onRsvpWrite trigger) must land
        // without a reload.
        let updated = Self.event(going: 2)
        repository.teaser.emit(updated)
        await waitFor { coordinator.state == .loaded(updated) }
        XCTAssertEqual(repository.teaser.subscribeCount, 1)
    }

    @MainActor
    func testStartIsIdempotent() async {
        let repository = FakeEventsRepository()
        repository.teaser.script([Self.event()])
        let coordinator = makeCoordinator(repository: repository)

        coordinator.start()
        await waitFor { coordinator.state != .loading }
        coordinator.start()

        XCTAssertEqual(repository.teaser.subscribeCount, 1)
    }

    // MARK: - member-gated detail

    @MainActor
    func testDetailEmissionPopulatesDetailForGatePasser() async {
        let repository = FakeEventsRepository()
        repository.teaser.script([Self.event()])
        let detail = EventDetail(description: "Bring your car.", address: "Storgatan 1")
        repository.detail.script([detail])
        let coordinator = makeCoordinator(repository: repository)

        coordinator.start()
        await waitFor { coordinator.detail == detail }
        XCTAssertTrue(coordinator.canSeeDetails)
    }

    @MainActor
    func testNonGatePasserNeverSubscribesTheDetailRead() async {
        let repository = FakeEventsRepository()
        repository.teaser.script([Self.event()])
        let coordinator = makeCoordinator(repository: repository, passesMemberGate: false)

        coordinator.start()
        await waitFor { coordinator.state != .loading }

        // Android: a non-member's detail flow is settled-empty, never a read
        // that only errors.
        XCTAssertEqual(repository.detail.subscribeCount, 0)
        XCTAssertNil(coordinator.detail)
        XCTAssertFalse(coordinator.canSeeDetails)
        XCTAssertFalse(coordinator.canRsvp)
    }

    @MainActor
    func testDetailIsNotSubscribedForANonPublishedEvent() async {
        // The rules gate details/private on the parent being published, so a
        // completed/cancelled event must not attach a read that can only be
        // denied.
        for status in [EventStatus.cancelled, .completed] {
            let repository = FakeEventsRepository()
            repository.teaser.script([Self.event(status: status)])
            let coordinator = makeCoordinator(repository: repository)

            coordinator.start()
            await waitFor { coordinator.state != .loading }
            XCTAssertEqual(repository.detail.subscribeCount, 0, "no detail read for \(status)")
        }
    }

    @MainActor
    func testDeniedDetailStaysNilWithoutFailingTheScreen() async {
        let repository = FakeEventsRepository()
        let event = Self.event()
        repository.teaser.script([event])
        repository.detail.script([nil])
        let coordinator = makeCoordinator(repository: repository)

        coordinator.start()
        await waitFor { coordinator.state == .loaded(event) }
        XCTAssertNil(coordinator.detail)
    }

    // MARK: - lifecycle gates

    @MainActor
    func testCanRsvpRequiresAPublishedEvent() async {
        for status in [EventStatus.cancelled, .completed, .draft] {
            let repository = FakeEventsRepository()
            repository.teaser.script([Self.event(status: status)])
            let coordinator = makeCoordinator(repository: repository)

            coordinator.start()
            await waitFor { coordinator.state != .loading }
            XCTAssertFalse(coordinator.canRsvp, "canRsvp must be false for \(status)")
            XCTAssertFalse(coordinator.canSeeDetails, "canSeeDetails must be false for \(status)")
        }
    }

    @MainActor
    func testNoSessionHidesRsvpAndIgnoresSubmit() async {
        let repository = FakeEventsRepository(uid: nil)
        repository.teaser.script([Self.event()])
        let coordinator = makeCoordinator(repository: repository)

        coordinator.start()
        await waitFor { coordinator.state != .loading }

        XCTAssertFalse(coordinator.canRsvp)
        XCTAssertEqual(repository.rsvp.subscribeCount, 0)
        coordinator.submitRsvp(.going)
        await Task.yield()
        XCTAssertTrue(repository.submitted.isEmpty)
        XCTAssertEqual(coordinator.rsvpState, .idle)
    }

    // MARK: - observed RSVP selection

    @MainActor
    func testMyRsvpFollowsTheObservedDocument() async {
        let repository = FakeEventsRepository()
        repository.teaser.script([Self.event()])
        repository.rsvp.script([nil])
        let coordinator = makeCoordinator(repository: repository)

        coordinator.start()
        await waitFor { coordinator.state != .loading }
        XCTAssertNil(coordinator.myRsvp)

        // The listener confirms the answer — the selection is driven by the
        // OBSERVED document, never applied optimistically by the write.
        repository.rsvp.emit(.maybe)
        await waitFor { coordinator.myRsvp == .maybe }
        repository.rsvp.emit(.notGoing)
        await waitFor { coordinator.myRsvp == .notGoing }
    }

    // MARK: - RSVP write lifecycle

    @MainActor
    func testSubmitWritesTheExactAnswerAndReturnsToIdle() async {
        let repository = FakeEventsRepository()
        repository.teaser.script([Self.event()])
        let coordinator = makeCoordinator(repository: repository)

        coordinator.start()
        await waitFor { coordinator.state != .loading }

        coordinator.submitRsvp(.notGoing)
        await waitFor { coordinator.rsvpState == .idle && !repository.submitted.isEmpty }

        XCTAssertEqual(repository.submitted.count, 1)
        XCTAssertEqual(repository.submitted[0].eventId, "e1")
        XCTAssertEqual(repository.submitted[0].uid, "uid-1")
        XCTAssertEqual(repository.submitted[0].status, .notGoing)
    }

    @MainActor
    func testSubmitIsSavingWhileInFlightAndCoalescesTaps() async {
        let repository = FakeEventsRepository()
        repository.teaser.script([Self.event()])
        repository.holdRsvpWrites(true)
        let coordinator = makeCoordinator(repository: repository)

        coordinator.start()
        await waitFor { coordinator.state != .loading }

        coordinator.submitRsvp(.going)
        XCTAssertEqual(coordinator.rsvpState, .saving)

        // A tap while saving is ignored — Android's `if (Saving) return`.
        coordinator.submitRsvp(.maybe)
        await waitFor { !repository.submitted.isEmpty }
        XCTAssertEqual(repository.submitted.count, 1)

        repository.releaseHeldWrites()
        await waitFor { coordinator.rsvpState == .idle }
    }

    @MainActor
    func testWriteFailureCarriesThePiiSafeStatusCode() async {
        let repository = FakeEventsRepository()
        repository.teaser.script([Self.event()])
        repository.failRsvp(with: RsvpWriteError(code: "PERMISSION_DENIED"))
        let coordinator = makeCoordinator(repository: repository)

        coordinator.start()
        await waitFor { coordinator.state != .loading }

        coordinator.submitRsvp(.going)
        await waitFor { coordinator.rsvpState == .failed(code: "PERMISSION_DENIED") }
    }

    @MainActor
    func testNonRepositoryFailureDegradesToACodelessFailure() async {
        struct Unexpected: Error {}
        let repository = FakeEventsRepository()
        repository.teaser.script([Self.event()])
        repository.failRsvp(with: Unexpected())
        let coordinator = makeCoordinator(repository: repository)

        coordinator.start()
        await waitFor { coordinator.state != .loading }

        coordinator.submitRsvp(.going)
        await waitFor { coordinator.rsvpState == .failed(code: nil) }
    }

    @MainActor
    func testResetClearsOnlyAFailure() async {
        let repository = FakeEventsRepository()
        repository.teaser.script([Self.event()])
        repository.failRsvp(with: RsvpWriteError(code: "UNAVAILABLE"))
        let coordinator = makeCoordinator(repository: repository)

        coordinator.start()
        await waitFor { coordinator.state != .loading }

        coordinator.submitRsvp(.going)
        await waitFor { coordinator.rsvpState == .failed(code: "UNAVAILABLE") }

        coordinator.resetRsvpFailure()
        XCTAssertEqual(coordinator.rsvpState, .idle)

        // Idle stays idle — reset is a no-op unless failed.
        coordinator.resetRsvpFailure()
        XCTAssertEqual(coordinator.rsvpState, .idle)
    }

    @MainActor
    func testReloadCancelsAnInFlightWriteAndUnlocksTheButtons() async {
        let repository = FakeEventsRepository()
        repository.teaser.script([Self.event()])
        repository.holdRsvpWrites(true)
        let coordinator = makeCoordinator(repository: repository)

        coordinator.start()
        await waitFor { coordinator.state != .loading }

        coordinator.submitRsvp(.going)
        XCTAssertEqual(coordinator.rsvpState, .saving)

        // A reload is a fresh page — the buttons must not stay locked behind
        // the stale write, and the stale task must not mutate state when it
        // eventually settles.
        coordinator.reload()
        XCTAssertEqual(coordinator.rsvpState, .idle)

        repository.failRsvp(with: RsvpWriteError(code: "UNAVAILABLE"))
        repository.releaseHeldWrites()
        // Give the cancelled task a chance to (incorrectly) write state.
        for _ in 0..<20 {
            await Task.yield()
        }
        XCTAssertEqual(coordinator.rsvpState, .idle)
    }

    @MainActor
    func testFailureRecoversOnTheNextSubmit() async {
        let repository = FakeEventsRepository()
        repository.teaser.script([Self.event()])
        repository.failRsvp(with: RsvpWriteError(code: "UNAVAILABLE"))
        let coordinator = makeCoordinator(repository: repository)

        coordinator.start()
        await waitFor { coordinator.state != .loading }

        coordinator.submitRsvp(.going)
        await waitFor { coordinator.rsvpState == .failed(code: "UNAVAILABLE") }

        repository.failRsvp(with: nil)
        coordinator.submitRsvp(.going)
        await waitFor { coordinator.rsvpState == .idle && repository.submitted.count == 2 }
    }
}
