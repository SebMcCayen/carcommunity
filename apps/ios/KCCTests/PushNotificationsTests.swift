import XCTest
@testable import KCC

final class PushNotificationsTests: XCTestCase {
    func testTokenIdIsLowercaseSHA256() {
        XCTAssertEqual(
            PushTokens.tokenId("abc"),
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        )
    }

    func testPayloadDecodesTargetAndTrimmedEntity() {
        XCTAssertEqual(
            PushPayload.deepLink(from: ["target": "dm", "entityId": " friend-1 "]),
            NotificationDeepLink(target: .dm, entityId: "friend-1")
        )
    }

    func testPayloadFallsBackToInboxAndDropsBlankEntity() {
        XCTAssertEqual(
            PushPayload.deepLink(from: ["target": "future_target", "entityId": "  "]),
            NotificationDeepLink(target: .notifications, entityId: nil)
        )
    }
    @MainActor
    func testAuthSessionOwnsBindingAfterShellDisappears() async {
        let source = FakePushSource()
        let repository = FakePushRepository()
        let coordinator = PushNotificationCoordinator(permissionReader: { .enabled })
        coordinator.configure(repository: repository, tokenSource: source)
        let auth = PushTestAuthRepository()
        let session = AuthSession(observing: auth, onIdentityChange: { coordinator.bind(uid: $0) },
                                  clearSavedPlaces: { _ in true })
        await coordinator.waitForPendingOperations()
        XCTAssertTrue(coordinator.shouldPresentForegroundNotification)
        XCTAssertTrue(session.signOut())
        for _ in 0..<100 {
            if session.state == .signedOut { break }
            await Task.yield()
        }
        await coordinator.waitForPendingOperations()
        XCTAssertEqual(session.state, .signedOut)
        XCTAssertFalse(coordinator.shouldPresentForegroundNotification)
        let invalidations = await source.invalidations
        XCTAssertEqual(invalidations, 2)
    }

    @MainActor
    func testExistingBackendPayloadWithoutRecipientStillPresentsAndRoutes() async {
        let coordinator = PushNotificationCoordinator(permissionReader: { .enabled })
        coordinator.configure(repository: FakePushRepository(), tokenSource: FakePushSource())
        coordinator.bind(uid: "A")
        await coordinator.waitForPendingOperations()
        XCTAssertTrue(coordinator.shouldPresent(recipientUID: nil))
        coordinator.receive(userInfo: ["target": "dm", "entityId": "friend"])
        XCTAssertEqual(coordinator.consumePendingDeepLink(),
                       NotificationDeepLink(target: .dm, entityId: "friend"))
    }

    @MainActor
    func testLogoutInvalidatesTokenAndClearsPresentationAndPendingLink() async {
        let source = FakePushSource()
        let repository = FakePushRepository()
        let coordinator = PushNotificationCoordinator(permissionReader: { .enabled })
        coordinator.configure(repository: repository, tokenSource: source)
        coordinator.bind(uid: "A")
        await coordinator.waitForPendingOperations()
        XCTAssertTrue(coordinator.shouldPresentForegroundNotification)
        coordinator.receive(deepLink: NotificationDeepLink(target: .dm, entityId: "friend"), recipientUID: "A")

        coordinator.bind(uid: nil)
        XCTAssertFalse(coordinator.shouldPresentForegroundNotification)
        XCTAssertNil(coordinator.pendingDeepLink)
        coordinator.receivedMessagingRegistration("fid-1")
        await coordinator.waitForPendingOperations()

        let invalidations = await source.invalidations
        let registrations = await repository.registrations
        let removals = await repository.removals
        XCTAssertEqual(invalidations, 2)
        XCTAssertEqual(registrations, ["A:fid-1"])
        XCTAssertEqual(removals, ["A:fid-1"])
    }

    @MainActor
    func testRapidAccountReplacementSerializesCleanupAndRejectsLateCallback() async {
        let source = FakePushSource()
        let repository = FakePushRepository()
        let coordinator = PushNotificationCoordinator(permissionReader: { .enabled })
        coordinator.configure(repository: repository, tokenSource: source)
        coordinator.bind(uid: "A")
        await coordinator.waitForPendingOperations()
        await source.pauseCleanup()
        coordinator.bind(uid: nil)
        for _ in 0..<100 {
            if await source.isPaused { break }
            try? await Task.sleep(for: .milliseconds(10))
        }
        coordinator.bind(uid: "B")
        coordinator.receivedMessagingRegistration("fid-1")
        await source.resumeCleanup()
        await coordinator.waitForPendingOperations()
        let registrations = await repository.registrations
        XCTAssertEqual(registrations, ["A:fid-1", "B:fid-3"])
        XCTAssertTrue(coordinator.shouldPresentForegroundNotification)
        XCTAssertFalse(coordinator.shouldPresent(recipientUID: "A"))
        XCTAssertTrue(coordinator.shouldPresent(recipientUID: "B"))
        coordinator.receive(deepLink: NotificationDeepLink(target: .dm, entityId: "old"), recipientUID: "A")
        XCTAssertNil(coordinator.pendingDeepLink)
    }

    @MainActor
    func testDirectReplacementRotatesInstallationAndSameUIDDoesNot() async {
        let source = FakePushSource()
        let repository = FakePushRepository()
        let coordinator = PushNotificationCoordinator(permissionReader: { .enabled })
        coordinator.configure(repository: repository, tokenSource: source)
        coordinator.receive(deepLink: NotificationDeepLink(target: .dm, entityId: "cold-launch"), recipientUID: "A")
        coordinator.bind(uid: "A")
        await coordinator.waitForPendingOperations()
        XCTAssertNotNil(coordinator.pendingDeepLink)
        coordinator.bind(uid: "A")
        await coordinator.waitForPendingOperations()
        coordinator.bind(uid: "B")
        await coordinator.waitForPendingOperations()
        let registrations = await repository.registrations
        let invalidations = await source.invalidations
        XCTAssertEqual(registrations, ["A:fid-1", "B:fid-2"])
        XCTAssertEqual(invalidations, 2)
        XCTAssertNil(coordinator.pendingDeepLink)
    }

    @MainActor
    func testTransientStartupFailureRetriesAfterNetworkRecovery() async {
        let source = FakePushSource()
        await source.failCleanup()
        let repository = FakePushRepository()
        let coordinator = PushNotificationCoordinator(retryDelay: .milliseconds(10), permissionReader: { .enabled })
        coordinator.configure(repository: repository, tokenSource: source)
        coordinator.bind(uid: "A")
        await coordinator.waitForPendingOperations()
        XCTAssertFalse(coordinator.shouldPresentForegroundNotification)
        await source.recoverCleanup()
        for _ in 0..<100 {
            if await repository.registrations.count == 1 { break }
            try? await Task.sleep(for: .milliseconds(10))
        }
        let registrations = await repository.registrations
        XCTAssertEqual(registrations, ["A:fid-1"])
        XCTAssertTrue(coordinator.shouldPresentForegroundNotification)
    }

    @MainActor
    func testFailedInvalidationCannotBeBypassedByAPNsRefresh() async {
        let source = FakePushSource()
        let repository = FakePushRepository()
        let coordinator = PushNotificationCoordinator(permissionReader: { .enabled })
        coordinator.configure(repository: repository, tokenSource: source)
        coordinator.bind(uid: "A")
        await coordinator.waitForPendingOperations()
        await source.failCleanup()
        coordinator.bind(uid: "B")
        await coordinator.waitForPendingOperations()
        await coordinator.refreshRegistration()
        let registrations = await repository.registrations
        XCTAssertEqual(registrations, ["A:fid-1"])
        XCTAssertFalse(coordinator.shouldPresentForegroundNotification)
    }

}


private actor FakePushRepository: PushTokenRepository {
    private(set) var registrations: [String] = []
    private(set) var removals: [String] = []
    func register(token: String, uid: String) async throws { registrations.append("\(uid):\(token)") }
    func unregister(token: String, uid: String) async throws { removals.append("\(uid):\(token)") }
}

private actor FakePushSource: PushTokenSource {
    private(set) var invalidations = 0
    private var shouldPause = false
    private var failure = false
    private var continuation: CheckedContinuation<Void, Never>?
    var isPaused: Bool { continuation != nil }
    func pauseCleanup() { shouldPause = true }
    func failCleanup() { failure = true }
    func recoverCleanup() { failure = false }
    func resumeCleanup() { shouldPause = false; continuation?.resume(); continuation = nil }
    func register() async throws -> String { "fid-\(invalidations)" }
    func unregister() async throws {
        if failure { throw CancellationError() }
        invalidations += 1
        if shouldPause {
            shouldPause = false
            await withCheckedContinuation { continuation = $0 }
        }
    }
}

private final class PushTestAuthRepository: AuthRepository, @unchecked Sendable {
    var authState: AuthState { .signedIn(uid: "A", displayName: nil) }
    private let stream: AsyncStream<AuthState>
    private let continuation: AsyncStream<AuthState>.Continuation
    init() {
        (stream, continuation) = AsyncStream.makeStream()
    }
    func authStateUpdates() -> AsyncStream<AuthState> { stream }
    func signIn(with payload: AppleIDTokenPayload) async throws {}
    func signOut() throws { continuation.yield(.signedOut) }
    func signOut(ifCurrentUidIs expectedUid: String) throws { try signOut() }
}
