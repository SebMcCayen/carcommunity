import XCTest
@testable import KCC

final class EventManagementTests: XCTestCase {
    private final class FakeRepository: EventsRepository, @unchecked Sendable {
        var createdId = "event-new"
        var createError: Error?
        var createCalls: [EventFormInput] = []
        var updateCalls: [(String, EventFormInput)] = []

        func publishedEvents() -> AsyncStream<EventsListSnapshot> { AsyncStream { $0.finish() } }
        func event(withId eventId: String) -> AsyncStream<EventSummary?> { AsyncStream { $0.finish() } }
        func eventDetail(eventId: String) -> AsyncStream<EventDetail?> { AsyncStream { $0.finish() } }
        func myRsvp(eventId: String, uid: String) -> AsyncStream<RsvpStatus?> { AsyncStream { $0.finish() } }
        func submitRsvp(eventId: String, uid: String, status: RsvpStatus) async throws {}
        func currentUserId() -> String? { "viewer" }

        func createEvent(_ input: EventFormInput) async throws -> String {
            createCalls.append(input)
            if let createError { throw createError }
            return createdId
        }

        func updateEvent(eventId: String, input: EventFormInput) async throws {
            updateCalls.append((eventId, input))
        }
    }

    private final class FakeSubscriptionRepository: SubscriptionStateRepository,
        @unchecked Sendable
    {
        let value: StoredSubscription?
        init(_ value: StoredSubscription?) { self.value = value }
        func subscription(uid: String) -> AsyncStream<StoredSubscription?> {
            AsyncStream { continuation in
                continuation.yield(value)
                continuation.finish()
            }
        }
    }

    private let date = Date(timeIntervalSince1970: 1_800_000_000)

    private func input(
        title: String = " Cars & Coffee ",
        latitude: Double? = 57.48,
        longitude: Double? = 12.07
    ) -> EventFormInput {
        EventFormInput(
            title: title,
            startsAt: date,
            description: "  Welcome  ",
            address: "  Main Street 1  ",
            latitude: latitude,
            longitude: longitude,
            publicSiteEnabled: true
        )
    }

    func testCreatePayloadUsesStrictCallableShape() {
        let payload = Events.createPayload(input())

        XCTAssertEqual(payload["title"] as? String, "Cars & Coffee")
        XCTAssertEqual(payload["startsAt"] as? String, "2027-01-15T08:00:00Z")
        XCTAssertEqual(payload["publishNow"] as? Bool, true)
        XCTAssertEqual(payload["description"] as? String, "Welcome")
        XCTAssertEqual(payload["address"] as? String, "Main Street 1")
        XCTAssertEqual(payload["latitude"] as? Double, 57.48)
        XCTAssertEqual(payload["longitude"] as? Double, 12.07)
        XCTAssertEqual(payload["publicSiteEnabled"] as? Bool, true)
        XCTAssertNil(payload["isOfficial"])
    }

    func testUpdatePayloadExplicitlyClearsOptionalFields() {
        var value = input(latitude: nil, longitude: nil)
        value.description = "  "
        value.address = ""

        let payload = Events.updatePayload(eventId: "event-1", input: value)

        XCTAssertTrue(payload["description"] is NSNull)
        XCTAssertTrue(payload["address"] is NSNull)
        XCTAssertTrue(payload["latitude"] is NSNull)
        XCTAssertTrue(payload["longitude"] is NSNull)
        XCTAssertNil(payload["publicSiteEnabled"])
    }

    func testValidationRejectsHalfCoordinateAndOutOfRangePoint() {
        XCTAssertFalse(Events.valid(input(latitude: 57, longitude: nil)))
        XCTAssertFalse(Events.valid(input(latitude: 91, longitude: 12)))
        XCTAssertTrue(Events.valid(input(latitude: nil, longitude: nil)))
    }

    func testCreatorAndCheckInGatesMatchBackendLifecycle() {
        let start = Date(timeIntervalSince1970: 10_000)
        let event = EventSummary(
            id: "e1", title: "Meet", summary: nil, startsAt: start, endsAt: nil,
            approximateArea: nil, locationName: nil, latitude: 57, longitude: 12,
            isOfficial: false, status: .published, counts: .empty, createdByUserId: "viewer"
        )
        XCTAssertTrue(Events.canManage(event, uid: "viewer"))
        XCTAssertFalse(Events.canManage(event, uid: "other"))
        XCTAssertTrue(Events.canCheckIn(event, now: start.addingTimeInterval(-30 * 60)))
        XCTAssertTrue(Events.canCheckIn(event, now: start.addingTimeInterval(4.5 * 60 * 60)))
        XCTAssertFalse(Events.canCheckIn(event, now: start.addingTimeInterval(4.5 * 60 * 60 + 1)))
    }

    func testDwellUsesEarliestSessionOrPersistedAnchor() {
        let session = Date(timeIntervalSince1970: 1_000)
        let persisted = Date(timeIntervalSince1970: 1_030)

        XCTAssertEqual(
            Events.checkInAnchor(sessionFirstFixAt: session, recordCreatedAt: persisted),
            session
        )
        XCTAssertEqual(
            Events.checkInAnchor(sessionFirstFixAt: nil, recordCreatedAt: persisted),
            persisted
        )
        XCTAssertNil(Events.checkInAnchor(sessionFirstFixAt: nil, recordCreatedAt: nil))
    }

    func testDwellCountdownClampsAndCompletesAtTenMinutes() {
        let anchor = Date(timeIntervalSince1970: 1_000)

        XCTAssertEqual(Events.checkInRemaining(from: anchor, now: anchor.addingTimeInterval(-5)), 600)
        XCTAssertEqual(Events.checkInRemaining(from: anchor, now: anchor.addingTimeInterval(599)), 1)
        XCTAssertEqual(Events.checkInRemaining(from: anchor, now: anchor.addingTimeInterval(600)), 0)
        XCTAssertEqual(Events.checkInProgress(from: anchor, now: anchor.addingTimeInterval(300)), 0.5)
        XCTAssertEqual(Events.checkInProgress(from: anchor, now: anchor.addingTimeInterval(900)), 1)
    }

    @MainActor
    func testCreateCoordinatorPublishesBackendId() async {
        let repository = FakeRepository()
        let coordinator = EventFormCoordinator(repository: repository, mode: .create)

        coordinator.submit(input())
        await waitFor { coordinator.state == .created(eventId: "event-new") }

        XCTAssertEqual(repository.createCalls.count, 1)
    }

    @MainActor
    func testCreateCoordinatorPreservesRateLimitReason() async {
        let repository = FakeRepository()
        repository.createError = CreateEventError(reason: .rateLimited)
        let coordinator = EventFormCoordinator(repository: repository, mode: .create)

        coordinator.submit(input())
        await waitFor { coordinator.state == .failedCreate(.rateLimited) }
    }

    @MainActor
    func testEventsCoordinatorUsesAuthoritativePaidTierForCheckIn() async {
        let paid = StoredSubscription(
            tier: "plus", status: "active", entitlement: "member_monthly", userId: "viewer"
        )
        let coordinator = EventsCoordinator(
            repository: FakeRepository(),
            subscriptionRepository: FakeSubscriptionRepository(paid)
        )

        coordinator.start()
        await waitFor { coordinator.isPaidSubscriber }
    }

    @MainActor
    func testMissingSubscriptionFailsClosed() async {
        let coordinator = EventsCoordinator(
            repository: FakeRepository(),
            subscriptionRepository: FakeSubscriptionRepository(nil)
        )

        coordinator.start()
        for _ in 0..<10 { await Task.yield() }
        XCTAssertFalse(coordinator.isPaidSubscriber)
    }

    @MainActor
    private func waitFor(
        timeout: TimeInterval = 1,
        _ predicate: () -> Bool
    ) async {
        let deadline = Date().addingTimeInterval(timeout)
        while !predicate(), Date() < deadline { await Task.yield() }
        XCTAssertTrue(predicate())
    }
}
