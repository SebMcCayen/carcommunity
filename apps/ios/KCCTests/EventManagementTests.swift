import XCTest
@testable import KCC

final class EventManagementTests: XCTestCase {
    private final class FakeRepository: EventsRepository, @unchecked Sendable {
        var createdId = "event-new"
        var createError: Error?
        var createCalls: [EventFormInput] = []
        var updateCalls: [(String, EventFormInput)] = []
        var eventValue: EventSummary?
        var attendeeDelay: Duration = .zero
        var attendeeResult: EventAttendeesResult = .loaded([])
        var cancelCalls = 0
        var checkInCalls = 0
        var checkInResult: EventCheckInResult = .recorded

        func publishedEvents() -> AsyncStream<EventsListSnapshot> { AsyncStream { $0.finish() } }
        func event(withId eventId: String) -> AsyncStream<EventSummary?> {
            AsyncStream { continuation in
                if let eventValue { continuation.yield(eventValue) }
                continuation.finish()
            }
        }
        func eventDetail(eventId: String) -> AsyncStream<EventPrivateDetailSnapshot> { AsyncStream { $0.finish() } }
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

        func attendees(eventId: String) async -> EventAttendeesResult {
            try? await Task.sleep(for: attendeeDelay)
            return attendeeResult
        }

        func cancelEvent(eventId: String) async throws { cancelCalls += 1 }

        func checkIn(eventId: String, fix: EventCheckInFix) async throws -> EventCheckInResult {
            checkInCalls += 1
            return checkInResult
        }
    }

    private final class FakeSubscriptionRepository: SubscriptionStateRepository,
        @unchecked Sendable
    {
        private var value: StoredSubscription?
        private var continuations: [AsyncStream<StoredSubscription?>.Continuation] = []
        init(_ value: StoredSubscription?) { self.value = value }
        func subscription(uid: String) -> AsyncStream<StoredSubscription?> {
            AsyncStream { continuation in
                continuation.yield(value)
                continuations.append(continuation)
            }
        }
        func emit(_ value: StoredSubscription?) {
            self.value = value
            continuations.forEach { $0.yield(value) }
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

    func testFormLimitsCountUtf16ForEmojiAndZwjSequences() {
        let suffixes = ["🚗", "👨‍👩‍👧‍👦"]
        for suffix in suffixes {
            let titleAtLimit = utf16Boundary(limit: Events.titleMax, suffix: suffix)
            var titleInput = input(title: titleAtLimit, latitude: nil, longitude: nil)
            titleInput.description = nil
            titleInput.address = nil
            XCTAssertTrue(Events.valid(titleInput), "title boundary for \(suffix)")
            titleInput.title += "a"
            XCTAssertFalse(Events.valid(titleInput), "title overflow for \(suffix)")

            var descriptionInput = input(latitude: nil, longitude: nil)
            descriptionInput.description = utf16Boundary(
                limit: Events.descriptionMax, suffix: suffix
            )
            XCTAssertTrue(Events.valid(descriptionInput), "description boundary for \(suffix)")
            descriptionInput.description! += "a"
            XCTAssertFalse(Events.valid(descriptionInput), "description overflow for \(suffix)")

            var addressInput = input(latitude: nil, longitude: nil)
            addressInput.address = utf16Boundary(limit: Events.addressMax, suffix: suffix)
            XCTAssertTrue(Events.valid(addressInput), "address boundary for \(suffix)")
            addressInput.address! += "a"
            XCTAssertFalse(Events.valid(addressInput), "address overflow for \(suffix)")
        }
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
    func testOpenDetailTracksPaidEntitlementLiveAndFailsClosed() async {
        let repository = FakeRepository()
        repository.eventValue = positionedEvent()
        let subscriptions = FakeSubscriptionRepository(nil)
        let provider = StubLocationProvider(authorization: .whileInUse)
        let coordinator = EventDetailCoordinator(
            repository: repository,
            eventId: "e1",
            locationProvider: provider,
            subscriptionRepository: subscriptions
        )

        coordinator.start()
        await waitFor { if case .loaded = coordinator.state { true } else { false } }
        XCTAssertFalse(coordinator.isPaidSubscriber)

        subscriptions.emit(activeSubscription(tier: "plus"))
        await waitFor { coordinator.isPaidSubscriber }

        subscriptions.emit(nil)
        await waitFor { !coordinator.isPaidSubscriber }
    }

    @MainActor
    func testManagementOperationsDoNotCancelEachOther() async {
        let repository = FakeRepository()
        repository.eventValue = positionedEvent(createdByUserId: "viewer")
        repository.attendeeDelay = .milliseconds(50)
        repository.attendeeResult = .loaded([
            EventAttendee(id: "member", displayName: "Member", avatarPath: nil, status: .going)
        ])
        let coordinator = EventDetailCoordinator(repository: repository, eventId: "e1")

        coordinator.start()
        await waitFor { coordinator.canManage }
        coordinator.loadAttendees()
        coordinator.cancelEvent()

        await waitFor { coordinator.manageState == .deleted }
        await waitFor {
            if case .loaded = coordinator.attendeesState { true } else { false }
        }
        XCTAssertEqual(repository.cancelCalls, 1)
    }

    @MainActor
    func testCheckInWaitsForPermissionAndResumesAfterGrant() async {
        let repository = FakeRepository()
        repository.eventValue = positionedEvent()
        let subscriptions = FakeSubscriptionRepository(activeSubscription(tier: "plus"))
        let provider = StubLocationProvider(authorization: .notDetermined)
        provider.scriptRequestOutcome(.whileInUse)
        let permission = LocationPermissionCoordinator(provider: provider)
        let coordinator = EventDetailCoordinator(
            repository: repository,
            eventId: "e1",
            locationProvider: provider,
            subscriptionRepository: subscriptions
        )

        permission.start()
        coordinator.start()
        await waitFor { coordinator.isPaidSubscriber }
        await waitFor { if case .loaded = coordinator.state { true } else { false } }
        XCTAssertTrue(coordinator.canCheckIn(at: Date()))
        coordinator.requestCheckIn(using: permission)
        XCTAssertTrue(coordinator.checkInPermissionPending)
        XCTAssertEqual(permission.state, .rationale)
        XCTAssertEqual(repository.checkInCalls, 0)

        permission.proceedFromRationale()
        await waitFor { permission.state == .granted }
        coordinator.resumeCheckInAfterPermissionGrant()
        await waitFor { provider.activeFixStreamCount == 1 }
        provider.emitFix(LocationFix.of(
            latitude: 57.48,
            longitude: 12.07,
            timestamp: Date(),
            accuracyMeters: 5,
            isSimulatedBySoftware: false
        )!)
        await waitFor { repository.checkInCalls == 1 }
        XCTAssertFalse(coordinator.checkInPermissionPending)
    }

    @MainActor
    func testCheckInAvailabilityUsesTimelineDateAcrossOpeningBoundary() async {
        let repository = FakeRepository()
        let start = Date(timeIntervalSince1970: 2_000_000)
        repository.eventValue = positionedEvent(startsAt: start)
        let subscriptions = FakeSubscriptionRepository(activeSubscription(tier: "supporter"))
        let provider = StubLocationProvider(authorization: .whileInUse)
        let coordinator = EventDetailCoordinator(
            repository: repository,
            eventId: "e1",
            locationProvider: provider,
            subscriptionRepository: subscriptions
        )

        coordinator.start()
        await waitFor { coordinator.isPaidSubscriber }
        await waitFor { if case .loaded = coordinator.state { true } else { false } }
        XCTAssertFalse(coordinator.canCheckIn(at: start.addingTimeInterval(-30 * 60 - 1)))
        XCTAssertTrue(coordinator.canCheckIn(at: start.addingTimeInterval(-30 * 60)))
    }

    func testCheckInRefreshUsesOnlyOpeningAndClosingBoundariesOutsideDwell() {
        let start = Date(timeIntervalSince1970: 2_000_000)
        let event = positionedEvent(startsAt: start)
        let opensAt = start.addingTimeInterval(-Events.checkInWindowBefore)
        let closesAt = start
            .addingTimeInterval(Events.defaultEventDuration + Events.checkInWindowAfter)

        XCTAssertEqual(
            refreshCadence(event: event, now: opensAt.addingTimeInterval(-86_400)),
            .boundary(opensAt)
        )
        XCTAssertEqual(
            refreshCadence(event: event, now: opensAt),
            .boundary(closesAt.addingTimeInterval(0.001))
        )
        XCTAssertEqual(
            refreshCadence(event: event, now: closesAt.addingTimeInterval(0.002)),
            .none
        )
    }

    func testCheckInRefreshTicksEachSecondOnlyForVisibleIncompleteDwell() {
        let now = Date(timeIntervalSince1970: 2_000_000)
        let event = positionedEvent(startsAt: now)

        XCTAssertEqual(
            refreshCadence(
                event: event, pending: true,
                anchor: now.addingTimeInterval(-Events.requiredCheckInDwell + 1), now: now
            ),
            .everySecond
        )
        XCTAssertNotEqual(
            refreshCadence(
                event: event, pending: true,
                anchor: now.addingTimeInterval(-Events.requiredCheckInDwell), now: now
            ),
            .everySecond
        )
        XCTAssertEqual(
            refreshCadence(event: event, paid: false, pending: true, anchor: now, now: now),
            .none
        )
        XCTAssertEqual(
            refreshCadence(event: event, pending: true, verified: true, anchor: now, now: now),
            .none
        )
    }

    private func activeSubscription(tier: String) -> StoredSubscription {
        StoredSubscription(
            tier: tier, status: "active", entitlement: "member_monthly", userId: "viewer"
        )
    }

    private func positionedEvent(
        startsAt: Date = Date().addingTimeInterval(60),
        createdByUserId: String? = nil
    ) -> EventSummary {
        EventSummary(
            id: "e1", title: "Meet", summary: nil, startsAt: startsAt, endsAt: nil,
            approximateArea: nil, locationName: nil, latitude: 57.48, longitude: 12.07,
            isOfficial: false, status: .published, counts: .empty,
            createdByUserId: createdByUserId
        )
    }

    private func utf16Boundary(limit: Int, suffix: String) -> String {
        XCTAssertLessThanOrEqual(suffix.utf16.count, limit)
        let value = String(repeating: "a", count: limit - suffix.utf16.count) + suffix
        XCTAssertEqual(value.utf16.count, limit)
        return value
    }

    private func refreshCadence(
        event: EventSummary,
        paid: Bool = true,
        pending: Bool = false,
        verified: Bool = false,
        anchor: Date? = nil,
        now: Date
    ) -> EventCheckInRefreshCadence {
        Events.checkInRefreshCadence(
            event: event,
            isPaidSubscriber: paid,
            hasLocationProvider: true,
            isPending: pending,
            isVerified: verified,
            anchor: anchor,
            now: now
        )
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
