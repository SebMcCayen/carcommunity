import XCTest

@testable import KCC

/// Unit tests for the pure events-list orchestration: every repository
/// emission maps to the right ``EventsListUiState``, reload tears down and
/// re-subscribes (Android's `reloadKey++` semantics), and start is
/// idempotent. No Firebase — the repository is a scripted fake.
final class EventsCoordinatorTests: XCTestCase {

    // MARK: - fakes

    private final class FakeEventsRepository: EventsRepository, @unchecked Sendable {
        private let lock = NSLock()
        private var pending: [EventsListSnapshot] = []
        private var continuations: [UUID: AsyncStream<EventsListSnapshot>.Continuation] = [:]
        private(set) var subscribeCount = 0

        /// Snapshots replayed to each FUTURE subscription (the listener's
        /// initial snapshot). The stream then stays open, like a real
        /// listener.
        func script(_ snapshots: [EventsListSnapshot]) {
            lock.lock()
            pending = snapshots
            lock.unlock()
        }

        /// Pushes a snapshot to every LIVE subscription (a later listener
        /// update).
        func emit(_ snapshot: EventsListSnapshot) {
            lock.lock()
            let live = Array(continuations.values)
            lock.unlock()
            for continuation in live {
                continuation.yield(snapshot)
            }
        }

        func publishedEvents() -> AsyncStream<EventsListSnapshot> {
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
                // Drop the continuation when its consumer goes away (a reload
                // tears down the old subscription) so dead sinks do not
                // accumulate — same pattern as FirebaseEventsRepository /
                // FirebaseAuthRepository.
                continuation.onTermination = { [weak self] _ in
                    guard let self else { return }
                    self.lock.lock()
                    self.continuations[id] = nil
                    self.lock.unlock()
                }
            }
        }

        // MARK: unused by the list slice — inert conformance only. The
        // streams finish immediately so an accidental consumer can never
        // hang a test.

        func event(withId eventId: String) -> AsyncStream<EventSummary?> {
            AsyncStream { $0.finish() }
        }

        func eventDetail(eventId: String) -> AsyncStream<EventDetail?> {
            AsyncStream { $0.finish() }
        }

        func myRsvp(eventId: String, uid: String) -> AsyncStream<RsvpStatus?> {
            AsyncStream { $0.finish() }
        }

        func submitRsvp(eventId: String, uid: String, status: RsvpStatus) async throws {}

        func currentUserId() -> String? { nil }
    }

    // MARK: - fixtures

    private static func event(
        _ id: String,
        startsAt: Date? = Date(timeIntervalSince1970: 1_700_000_000),
        going: Int = 0
    ) -> EventSummary {
        EventSummary(
            id: id,
            title: "Event \(id)",
            summary: nil,
            startsAt: startsAt,
            endsAt: nil,
            approximateArea: nil,
            locationName: nil,
            latitude: nil,
            longitude: nil,
            isOfficial: false,
            status: .published,
            counts: RsvpCounts(going: going, maybe: 0, notGoing: 0)
        )
    }

    /// Polls until `predicate` holds, yielding to let the coordinator's
    /// subscription task drain the stream. Fails the test on timeout.
    @MainActor
    private func waitForState(
        of coordinator: EventsCoordinator,
        timeout: TimeInterval = 2,
        file: StaticString = #filePath,
        line: UInt = #line,
        until predicate: (EventsListUiState) -> Bool
    ) async {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if predicate(coordinator.state) { return }
            await Task.yield()
            try? await Task.sleep(nanoseconds: 1_000_000)
        }
        XCTFail(
            "Timed out waiting for state; last: \(coordinator.state)",
            file: file,
            line: line
        )
    }

    // MARK: - state mapping

    @MainActor
    func testInitialStateIsLoadingBeforeStart() {
        let coordinator = EventsCoordinator(repository: FakeEventsRepository())
        XCTAssertEqual(coordinator.state, .loading)
    }

    @MainActor
    func testLoadedSnapshotWithItemsBecomesLoaded() async {
        let repository = FakeEventsRepository()
        let events = [Self.event("a"), Self.event("b")]
        repository.script([.loaded(events)])
        let coordinator = EventsCoordinator(repository: repository)

        coordinator.start()
        await waitForState(of: coordinator) { $0 == .loaded(events) }
        XCTAssertEqual(repository.subscribeCount, 1)
    }

    @MainActor
    func testLoadedSnapshotWithNoItemsBecomesEmpty() async {
        let repository = FakeEventsRepository()
        repository.script([.loaded([])])
        let coordinator = EventsCoordinator(repository: repository)

        coordinator.start()
        await waitForState(of: coordinator) { $0 == .empty }
    }

    @MainActor
    func testListenerFailureCarriesTheBareStatusCode() async {
        let repository = FakeEventsRepository()
        repository.script([.failed(code: "FAILED_PRECONDITION")])
        let coordinator = EventsCoordinator(repository: repository)

        coordinator.start()
        await waitForState(of: coordinator) { $0 == .failed(code: "FAILED_PRECONDITION") }
    }

    @MainActor
    func testLaterSnapshotUpdatesALoadedList() async {
        let repository = FakeEventsRepository()
        let initial = [Self.event("a")]
        repository.script([.loaded(initial)])
        let coordinator = EventsCoordinator(repository: repository)

        coordinator.start()
        await waitForState(of: coordinator) { $0 == .loaded(initial) }

        let updated = [Self.event("a"), Self.event("b")]
        repository.emit(.loaded(updated))
        await waitForState(of: coordinator) { $0 == .loaded(updated) }
        XCTAssertEqual(repository.subscribeCount, 1)
    }

    // MARK: - start/reload semantics

    @MainActor
    func testStartIsIdempotent() async {
        let repository = FakeEventsRepository()
        let events = [Self.event("a")]
        repository.script([.loaded(events)])
        let coordinator = EventsCoordinator(repository: repository)

        coordinator.start()
        await waitForState(of: coordinator) { $0 == .loaded(events) }
        coordinator.start()

        // A second start must neither re-subscribe nor flash back to loading.
        XCTAssertEqual(repository.subscribeCount, 1)
        XCTAssertEqual(coordinator.state, .loaded(events))
    }

    @MainActor
    func testReloadReturnsToLoadingBeforeTheNewSnapshot() async {
        let repository = FakeEventsRepository()
        repository.script([.loaded([Self.event("a")])])
        let coordinator = EventsCoordinator(repository: repository)

        coordinator.start()
        await waitForState(of: coordinator) { $0 != .loading }

        // The re-subscribed stream emits nothing yet — reload must show
        // loading, not linger on the stale list.
        repository.script([])
        coordinator.reload()
        XCTAssertEqual(coordinator.state, .loading)
        XCTAssertEqual(repository.subscribeCount, 2)
    }

    @MainActor
    func testReloadRecoversFromAFailure() async {
        let repository = FakeEventsRepository()
        repository.script([.failed(code: "UNAVAILABLE")])
        let coordinator = EventsCoordinator(repository: repository)

        coordinator.start()
        await waitForState(of: coordinator) { $0 == .failed(code: "UNAVAILABLE") }

        let events = [Self.event("a")]
        repository.script([.loaded(events)])
        coordinator.reload()
        await waitForState(of: coordinator) { $0 == .loaded(events) }
        XCTAssertEqual(repository.subscribeCount, 2)
    }
}
