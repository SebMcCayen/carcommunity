import XCTest

@testable import KCC

final class EventChatCoordinatorTests: XCTestCase {
    private enum TestError: Error { case failed }

    private final class FakeRepository: EventChatRepository, @unchecked Sendable {
        private let lock = NSLock()
        private var pending: [EventChatMessagesState] = []
        private var continuations: [UUID: AsyncStream<EventChatMessagesState>.Continuation] = [:]
        private var shouldFailPost = false
        private var shouldFailReport = false
        private(set) var subscribeCount = 0
        private(set) var posts: [(String, String)] = []
        private(set) var reports: [(String, String, ChatReportReason)] = []

        func script(_ states: [EventChatMessagesState]) {
            lock.withLock { pending = states }
        }

        func emit(_ state: EventChatMessagesState) {
            let live = lock.withLock { Array(continuations.values) }
            live.forEach { $0.yield(state) }
        }

        func failPost(_ value: Bool) { lock.withLock { shouldFailPost = value } }
        func failReport(_ value: Bool) { lock.withLock { shouldFailReport = value } }

        func messages(eventId: String) -> AsyncStream<EventChatMessagesState> {
            let snapshots = lock.withLock { () -> [EventChatMessagesState] in
                subscribeCount += 1
                return pending
            }
            return AsyncStream { continuation in
                snapshots.forEach { continuation.yield($0) }
                let id = UUID()
                self.lock.withLock { self.continuations[id] = continuation }
                continuation.onTermination = { [weak self] _ in
                    self?.lock.withLock { self?.continuations[id] = nil }
                }
            }
        }

        func enabled() -> AsyncStream<Bool> {
            AsyncStream { continuation in
                continuation.yield(true)
                continuation.finish()
            }
        }

        private func recordPost(eventId: String, message: String) -> Bool {
            lock.withLock {
                posts.append((eventId, message))
                return shouldFailPost
            }
        }

        func post(eventId: String, message: String) async throws {
            if recordPost(eventId: eventId, message: message) { throw TestError.failed }
        }

        private func recordReport(
            eventId: String,
            messageId: String,
            reason: ChatReportReason
        ) -> Bool {
            lock.withLock {
                reports.append((eventId, messageId, reason))
                return shouldFailReport
            }
        }

        func report(eventId: String, messageId: String, reason: ChatReportReason) async throws {
            if recordReport(eventId: eventId, messageId: messageId, reason: reason) {
                throw TestError.failed
            }
        }

        func currentUserId() -> String? { "me" }
    }

    private func message(
        id: String = "m1",
        author: String = "other",
        state: EventChatModerationState = .visible
    ) -> EventChatMessage {
        EventChatMessage(
            id: id,
            authorUserId: author,
            authorDisplayName: "Driver",
            message: "Hello",
            moderationState: state,
            createdAt: Date(timeIntervalSince1970: 100)
        )
    }

    @MainActor
    private func waitUntil(
        timeout: TimeInterval = 2,
        file: StaticString = #filePath,
        line: UInt = #line,
        _ predicate: () -> Bool
    ) async {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if predicate() { return }
            await Task.yield()
            try? await Task.sleep(nanoseconds: 1_000_000)
        }
        XCTFail("Timed out waiting for condition", file: file, line: line)
    }

    func testParticipationRequiresPublishedAndGoingOrMaybe() {
        XCTAssertTrue(EventChat.canParticipate(passesMemberGate: true, eventStatus: .published, rsvp: .going))
        XCTAssertTrue(EventChat.canParticipate(passesMemberGate: true, eventStatus: .published, rsvp: .maybe))
        XCTAssertFalse(EventChat.canParticipate(passesMemberGate: false, eventStatus: .published, rsvp: .going))
        XCTAssertFalse(EventChat.canParticipate(passesMemberGate: true, eventStatus: .completed, rsvp: .going))
        XCTAssertFalse(EventChat.canParticipate(passesMemberGate: true, eventStatus: .published, rsvp: .notGoing))
    }

    func testSendabilityTrimsAndEnforcesBackendLimit() {
        XCTAssertFalse(EventChat.isSendable("  \n"))
        XCTAssertTrue(EventChat.isSendable(" hello "))
        XCTAssertTrue(EventChat.isSendable(String(repeating: "a", count: 1_000)))
        XCTAssertFalse(EventChat.isSendable(String(repeating: "a", count: 1_001)))
    }

    func testHiddenAuthorsAreFiltered() {
        let visible = message(id: "a", author: "visible")
        let hidden = message(id: "b", author: "hidden")
        XCTAssertEqual(EventChat.filterHidden([visible, hidden], hiddenUserIds: ["hidden"]), [visible])
    }

    @MainActor
    func testLiveStatesAndReload() async {
        let repository = FakeRepository()
        let row = message()
        repository.script([.loaded([row])])
        let coordinator = EventChatCoordinator(repository: repository, eventId: "e1", currentUserId: "me")

        coordinator.start()
        await waitUntil { coordinator.messagesState == .loaded([row]) }
        coordinator.start()
        XCTAssertEqual(repository.subscribeCount, 1)

        repository.script([.failed])
        coordinator.reload()
        await waitUntil { coordinator.messagesState == .failed }
        XCTAssertEqual(repository.subscribeCount, 2)
    }

    @MainActor
    func testAccessLossClearsMessagesAndResubscribeStartsFresh() async {
        let repository = FakeRepository()
        let row = message()
        repository.script([.loaded([row])])
        let coordinator = EventChatCoordinator(repository: repository, eventId: "e1", currentUserId: "me")
        coordinator.setAccess(true)
        await waitUntil { coordinator.messagesState == .loaded([row]) }

        coordinator.setAccess(false)
        XCTAssertEqual(coordinator.messagesState, .loaded([]))

        repository.script([.loaded([])])
        coordinator.setAccess(true)
        await waitUntil { coordinator.messagesState == .loaded([]) }
        XCTAssertEqual(repository.subscribeCount, 2)
    }

    @MainActor
    func testSendTrimsAndKeepsFailureRetryable() async {
        let repository = FakeRepository()
        let coordinator = EventChatCoordinator(repository: repository, eventId: "e1", currentUserId: "me")
        let sent = await coordinator.send(" hello ")
        XCTAssertTrue(sent)
        XCTAssertEqual(repository.posts.first?.0, "e1")
        XCTAssertEqual(repository.posts.first?.1, "hello")
        XCTAssertEqual(coordinator.sendState, .idle)

        repository.failPost(true)
        let retried = await coordinator.send("again")
        XCTAssertFalse(retried)
        XCTAssertEqual(coordinator.sendState, .failed)
        coordinator.resetSendFailure()
        XCTAssertEqual(coordinator.sendState, .idle)
    }

    @MainActor
    func testReportRejectsOwnAndModeratedMessages() async {
        let repository = FakeRepository()
        let coordinator = EventChatCoordinator(repository: repository, eventId: "e1", currentUserId: "me")
        await coordinator.report(message(author: "me"), reason: .spam)
        await coordinator.report(message(state: .removed), reason: .spam)
        await coordinator.report(message(state: .autoHidden), reason: .spam)
        XCTAssertTrue(repository.reports.isEmpty)

        await coordinator.report(message(), reason: .harassment)
        XCTAssertEqual(repository.reports.count, 1)
        XCTAssertEqual(coordinator.reportState, .done)
    }
}
