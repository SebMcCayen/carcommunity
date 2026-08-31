import XCTest

@testable import KCC

/// Unit tests for the pure inbox orchestration: every repository emission maps
/// to the right ``NotificationsInboxUiState`` (including the config-less
/// `unavailable`), the unread count derives from the loaded items, and the
/// mark-read callables track ``MarkReadStatus`` with the contract failure code.
/// No Firebase — the repository is a scripted fake.
final class NotificationsInboxCoordinatorTests: XCTestCase {

    // MARK: - fake

    private final class FakeNotificationsRepository: NotificationsRepository, @unchecked Sendable {
        private let lock = NSLock()
        private var pending: [NotificationsSnapshot] = []
        private var continuations: [UUID: AsyncStream<NotificationsSnapshot>.Continuation] = [:]
        private(set) var subscribeCount = 0
        private(set) var markReadIds: [String] = []
        private(set) var markAllReadCount = 0
        private(set) var markSeenCount = 0
        /// When set, the next mark-read / mark-all-read throws it.
        var markError: Error?

        func script(_ snapshots: [NotificationsSnapshot]) {
            lock.lock(); pending = snapshots; lock.unlock()
        }

        func emit(_ snapshot: NotificationsSnapshot) {
            lock.lock()
            let live = Array(continuations.values)
            lock.unlock()
            for continuation in live { continuation.yield(snapshot) }
        }

        func notifications(uid: String) -> AsyncStream<NotificationsSnapshot> {
            lock.lock()
            subscribeCount += 1
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

        func unread(uid: String) -> AsyncStream<Bool> {
            AsyncStream { $0.finish() }
        }

        func markRead(notificationId: String) async throws {
            lock.lock(); markReadIds.append(notificationId); lock.unlock()
            if let markError { throw markError }
        }

        func markAllRead() async throws {
            lock.lock(); markAllReadCount += 1; lock.unlock()
            if let markError { throw markError }
        }

        func markSeen() async throws {
            lock.lock(); markSeenCount += 1; lock.unlock()
        }

        func currentUserId() -> String? { "me-uid" }
    }

    // MARK: - fixtures

    private static func item(_ id: String, read: Bool = false) -> AppNotification {
        AppNotification(
            id: id,
            category: .systemNotice,
            title: "Title \(id)",
            isRead: read,
            createdAt: Date(timeIntervalSince1970: 1_700_000_000)
        )
    }

    @MainActor
    private func waitFor(
        _ coordinator: NotificationsInboxCoordinator,
        timeout: TimeInterval = 2,
        file: StaticString = #filePath,
        line: UInt = #line,
        until predicate: (NotificationsInboxCoordinator) -> Bool
    ) async {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if predicate(coordinator) { return }
            await Task.yield()
            try? await Task.sleep(nanoseconds: 1_000_000)
        }
        XCTFail("Timed out; last state: \(coordinator.state)", file: file, line: line)
    }

    // MARK: - state mapping

    @MainActor
    func testUnavailableWhenNoRepository() {
        let coordinator = NotificationsInboxCoordinator(repository: nil, uid: "me-uid")
        XCTAssertEqual(coordinator.state, .unavailable)
        coordinator.start()  // no crash / no-op
        XCTAssertEqual(coordinator.state, .unavailable)
    }

    @MainActor
    func testUnavailableWhenNoUid() {
        let coordinator = NotificationsInboxCoordinator(
            repository: FakeNotificationsRepository(), uid: nil
        )
        XCTAssertEqual(coordinator.state, .unavailable)
    }

    @MainActor
    func testInitialStateIsLoadingBeforeStart() {
        let coordinator = NotificationsInboxCoordinator(
            repository: FakeNotificationsRepository(), uid: "me-uid"
        )
        XCTAssertEqual(coordinator.state, .loading)
    }

    @MainActor
    func testLoadedSnapshotWithItemsBecomesLoaded() async {
        let repository = FakeNotificationsRepository()
        let items = [Self.item("a"), Self.item("b")]
        repository.script([.loaded(items)])
        let coordinator = NotificationsInboxCoordinator(repository: repository, uid: "me-uid")

        coordinator.start()
        await waitFor(coordinator) { $0.state == .loaded(items) }
        XCTAssertEqual(repository.subscribeCount, 1)
    }

    @MainActor
    func testLoadedSnapshotWithNoItemsBecomesEmpty() async {
        let repository = FakeNotificationsRepository()
        repository.script([.loaded([])])
        let coordinator = NotificationsInboxCoordinator(repository: repository, uid: "me-uid")

        coordinator.start()
        await waitFor(coordinator) { $0.state == .empty }
    }

    @MainActor
    func testListenerFailureCarriesTheBareStatusCode() async {
        let repository = FakeNotificationsRepository()
        repository.script([.failed(code: "PERMISSION_DENIED")])
        let coordinator = NotificationsInboxCoordinator(repository: repository, uid: "me-uid")

        coordinator.start()
        await waitFor(coordinator) { $0.state == .failed(code: "PERMISSION_DENIED") }
    }

    @MainActor
    func testStartStampsSeenMarker() async {
        let repository = FakeNotificationsRepository()
        repository.script([.loaded([Self.item("a")])])
        let coordinator = NotificationsInboxCoordinator(repository: repository, uid: "me-uid")

        coordinator.start()
        await waitFor(coordinator) { _ in repository.markSeenCount == 1 }
    }

    @MainActor
    func testStartIsIdempotent() async {
        let repository = FakeNotificationsRepository()
        let items = [Self.item("a")]
        repository.script([.loaded(items)])
        let coordinator = NotificationsInboxCoordinator(repository: repository, uid: "me-uid")

        coordinator.start()
        await waitFor(coordinator) { $0.state == .loaded(items) }
        coordinator.start()
        XCTAssertEqual(repository.subscribeCount, 1)
        XCTAssertEqual(coordinator.state, .loaded(items))
    }

    @MainActor
    func testReloadReturnsToLoadingThenResubscribes() async {
        let repository = FakeNotificationsRepository()
        repository.script([.loaded([Self.item("a")])])
        let coordinator = NotificationsInboxCoordinator(repository: repository, uid: "me-uid")

        coordinator.start()
        await waitFor(coordinator) { $0.state != .loading }

        repository.script([])
        coordinator.reload()
        XCTAssertEqual(coordinator.state, .loading)
        XCTAssertEqual(repository.subscribeCount, 2)
    }

    // MARK: - unread count

    @MainActor
    func testUnreadCountDerivesFromLoadedItems() async {
        let repository = FakeNotificationsRepository()
        repository.script([.loaded([Self.item("a", read: false), Self.item("b", read: true)])])
        let coordinator = NotificationsInboxCoordinator(repository: repository, uid: "me-uid")

        coordinator.start()
        await waitFor(coordinator) { $0.unreadCount == 1 }
    }

    @MainActor
    func testUnreadCountZeroWhenNotLoaded() {
        let coordinator = NotificationsInboxCoordinator(repository: nil, uid: "me-uid")
        XCTAssertEqual(coordinator.unreadCount, 0)
    }

    // MARK: - mark read

    @MainActor
    func testMarkReadCallsRepositoryAndReturnsToIdle() async {
        let repository = FakeNotificationsRepository()
        let coordinator = NotificationsInboxCoordinator(repository: repository, uid: "me-uid")

        await coordinator.markRead(notificationId: "n1")
        XCTAssertEqual(repository.markReadIds, ["n1"])
        XCTAssertEqual(coordinator.markReadStatus, .idle)
    }

    @MainActor
    func testMarkAllReadCallsRepository() async {
        let repository = FakeNotificationsRepository()
        let coordinator = NotificationsInboxCoordinator(repository: repository, uid: "me-uid")

        await coordinator.markAllRead()
        XCTAssertEqual(repository.markAllReadCount, 1)
        XCTAssertEqual(coordinator.markReadStatus, .idle)
    }

    @MainActor
    func testMarkReadFailureCarriesTheContractCode() async {
        let repository = FakeNotificationsRepository()
        repository.markError = KccFunctionsError(code: .unavailable)
        let coordinator = NotificationsInboxCoordinator(repository: repository, uid: "me-uid")

        await coordinator.markRead(notificationId: "n1")
        XCTAssertEqual(coordinator.markReadStatus, .failed(code: .unavailable))
    }

    @MainActor
    func testMarkReadUnknownErrorCarriesNilCode() async {
        struct Boom: Error {}
        let repository = FakeNotificationsRepository()
        repository.markError = Boom()
        let coordinator = NotificationsInboxCoordinator(repository: repository, uid: "me-uid")

        await coordinator.markAllRead()
        XCTAssertEqual(coordinator.markReadStatus, .failed(code: nil))
    }

    @MainActor
    func testResetClearsFailedStatus() async {
        let repository = FakeNotificationsRepository()
        repository.markError = KccFunctionsError(code: .internalError)
        let coordinator = NotificationsInboxCoordinator(repository: repository, uid: "me-uid")

        await coordinator.markRead(notificationId: "n1")
        XCTAssertEqual(coordinator.markReadStatus, .failed(code: .internalError))
        coordinator.resetMarkReadStatus()
        XCTAssertEqual(coordinator.markReadStatus, .idle)
    }

    @MainActor
    func testMarkReadNoOpWhenUnavailable() async {
        let coordinator = NotificationsInboxCoordinator(repository: nil, uid: "me-uid")
        await coordinator.markRead(notificationId: "n1")
        XCTAssertEqual(coordinator.markReadStatus, .idle)
    }
}
