@preconcurrency import FirebaseCore
@preconcurrency import FirebaseMessaging
@preconcurrency import FirebaseInstallations
@preconcurrency import FirebaseAuth
import CryptoKit
import Foundation
import Observation
import UIKit
@preconcurrency import UserNotifications

protocol PushTokenRepository: Sendable {
    func register(token: String, uid: String) async throws
    func unregister(token: String, uid: String) async throws
}

protocol PushTokenSource: Sendable {
    func register() async throws -> String
    func unregister() async throws
}

enum PushTokens {
    static func tokenId(_ token: String) -> String {
        SHA256.hash(data: Data(token.utf8)).map { String(format: "%02x", $0) }.joined()
    }
}

enum PushPayload {
    static func deepLink(from userInfo: [AnyHashable: Any]) -> NotificationDeepLink {
        let target = (userInfo["target"] as? String)
            .flatMap(NotificationDeepLinkTarget.init(rawValue:)) ?? .notifications
        let rawEntity = (userInfo["entityId"] as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return NotificationDeepLink(
            target: target,
            entityId: rawEntity.flatMap { $0.isEmpty ? nil : $0 }
        )
    }
}

final class FirebasePushTokenRepository: PushTokenRepository, @unchecked Sendable {
    private let functions: KccFunctionsClient
    private let appVersion: String?
    private let buildNumber: String?

    init(functions: KccFunctionsClient, bundle: Bundle = .main) {
        self.functions = functions
        appVersion = bundle.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String
        buildNumber = bundle.object(forInfoDictionaryKey: "CFBundleVersion") as? String
    }

    func register(token: String, uid: String) async throws {
        guard Auth.auth().currentUser?.uid == uid else { throw CancellationError() }
        var payload: [String: Any] = ["token": token, "platform": "ios"]
        if let appVersion, !appVersion.isEmpty { payload["appVersion"] = appVersion }
        if let buildNumber, !buildNumber.isEmpty { payload["buildNumber"] = buildNumber }
        _ = try await functions.call("notifications-registerPushToken", payload: payload)
    }

    func unregister(token: String, uid: String) async throws {
        guard Auth.auth().currentUser?.uid == uid else { throw CancellationError() }
        _ = try await functions.call(
            "notifications-unregisterPushToken",
            payload: ["tokenId": PushTokens.tokenId(token)]
        )
    }

    static func createIfAvailable() -> FirebasePushTokenRepository? {
        KccFunctionsClient.createIfAvailable().map { FirebasePushTokenRepository(functions: $0) }
    }
}

struct FirebaseMessagingTokenSource: PushTokenSource {
    init() {
        // Registration belongs to the signed-in session, never SDK auto-init.
        Messaging.messaging().isAutoInitEnabled = false
    }

    func register() async throws -> String {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            Messaging.messaging().register { error in
                if let error { continuation.resume(throwing: error) }
                else { continuation.resume() }
            }
        }
        return try await Installations.installations().installationID()
    }

    func unregister() async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            Messaging.messaging().unregister { error in
                if let error { continuation.resume(throwing: error) }
                else { continuation.resume() }
            }
        }
        // unregister alone retains the FID. Re-registering that same FID for B
        // would revive stale registrations under A, so rotate the installation.
        try await Installations.installations().delete()
    }
}

enum PushPermissionState: Equatable, Sendable {
    case notDetermined
    case denied
    case enabled

    init(_ status: UNAuthorizationStatus) {
        switch status {
        case .notDetermined: self = .notDetermined
        case .denied: self = .denied
        case .authorized, .provisional, .ephemeral: self = .enabled
        @unknown default: self = .denied
        }
    }
}

/// Process-scoped bridge between APNs/FCM callbacks and the signed-in shell.
/// A pending tap survives a cold launch until the shell consumes it.
@MainActor
@Observable
final class PushNotificationCoordinator {
    static let shared = PushNotificationCoordinator()

    private(set) var permissionState: PushPermissionState = .notDetermined
    private(set) var pendingDeepLink: NotificationDeepLink?
    private(set) var isRequestingPermission = false

    @ObservationIgnored private var repository: PushTokenRepository?
    @ObservationIgnored private var tokenSource: PushTokenSource?
    @ObservationIgnored private var signedInUid: String?
    @ObservationIgnored private var pendingRecipientUid: String?
    @ObservationIgnored private var lastToken: String?
    @ObservationIgnored private var registrationTask: Task<Void, Never>?

    @ObservationIgnored private var retryTask: Task<Void, Never>?
    @ObservationIgnored private let retryDelay: Duration
    @ObservationIgnored private let permissionReader: @MainActor () async -> PushPermissionState
    @ObservationIgnored private var generation = 0
    @ObservationIgnored private var hasBound = false
    @ObservationIgnored private var needsInvalidation = true
    @ObservationIgnored private var apnsRequestedGeneration: Int?

    init(retryDelay: Duration = .seconds(15),
         permissionReader: @escaping @MainActor () async -> PushPermissionState = {
        PushPermissionState(await UNUserNotificationCenter.current().notificationSettings().authorizationStatus)
    }) {
        self.retryDelay = retryDelay
        self.permissionReader = permissionReader
    }

    func configure(repository: PushTokenRepository?, tokenSource: PushTokenSource?) {
        self.repository = repository
        self.tokenSource = tokenSource
    }

    /// Called by the process-lived AuthSession, including nil and direct A→B.
    /// Close local presentation synchronously; serialize SDK work so an old
    /// cleanup can never invalidate a replacement account's new registration.
    func bind(uid: String?) {
        guard !hasBound || uid != signedInUid else { return }
        retryTask?.cancel()
        retryTask = nil
        let previousUid = signedInUid
        if hasBound || (pendingRecipientUid != nil && pendingRecipientUid != uid) {
            pendingDeepLink = nil
            pendingRecipientUid = nil
        }
        // Stop local delivery immediately, including when network cleanup
        // cannot run yet, and remove the departing account's displayed alerts.
        UIApplication.shared.unregisterForRemoteNotifications()
        if hasBound || uid == nil {
            UNUserNotificationCenter.current().removeAllDeliveredNotifications()
            UNUserNotificationCenter.current().removeAllPendingNotificationRequests()
        }
        hasBound = true
        signedInUid = uid
        generation += 1
        let expectedGeneration = generation
        let oldToken = lastToken
        lastToken = nil
        needsInvalidation = true
        enqueue {
            if let oldToken, let previousUid, let repository = self.repository {
                try? await repository.unregister(token: oldToken, uid: previousUid)
            }
            await self.registerCurrentToken(expectedGeneration: expectedGeneration)
        }
    }

    private func enqueue(_ operation: @escaping @MainActor () async -> Void) {
        let previous = registrationTask
        registrationTask = Task {
            await previous?.value
            await operation()
        }
    }

    func waitForPendingOperations() async { await registrationTask?.value }

    func requestPermission() async {
        guard !isRequestingPermission else { return }
        isRequestingPermission = true
        defer { isRequestingPermission = false }
        do {
            _ = try await UNUserNotificationCenter.current().requestAuthorization(
                options: [.alert, .badge, .sound]
            )
        } catch {
            // The refreshed platform state is authoritative; errors remain a
            // recoverable denied/not-determined UI state.
        }
        await refreshPermissionState()
        if permissionState == .enabled {
            // The permission sheet may complete after logout; only the
            // serialized, identity-fenced path may re-enable APNs.
            await refreshRegistration()
        }
    }

    func refreshPermissionState() async {
        permissionState = await permissionReader()
    }

    func receivedMessagingRegistration(_ identifier: String?) {
        // Only the FID returned by this session's completed register operation
        // is accepted. Late callbacks for a departing installation are inert.
        guard let identifier, identifier == lastToken, signedInUid != nil else { return }
        let expectedGeneration = generation
        enqueue { await self.registerBackend(identifier, expectedGeneration: expectedGeneration) }
    }

    func refreshRegistration() async {
        let expectedGeneration = generation
        enqueue { await self.registerCurrentToken(expectedGeneration: expectedGeneration) }
        await waitForPendingOperations()
    }

    func receive(userInfo: [AnyHashable: Any]) {
        receive(deepLink: PushPayload.deepLink(from: userInfo),
                recipientUID: userInfo["recipientUid"] as? String)
    }

    func receive(deepLink: NotificationDeepLink, recipientUID: String? = nil) {
        guard recipientUID == nil || signedInUid == nil || signedInUid == recipientUID else { return }
        // Existing backend payloads have no recipientUid; bind those to the
        // receiving session and retain cold-launch routing compatibility.
        pendingRecipientUid = recipientUID ?? signedInUid
        pendingDeepLink = deepLink
    }

    func shouldPresent(recipientUID: String?) -> Bool {
        shouldPresentForegroundNotification && (recipientUID == nil || recipientUID == signedInUid)
    }

    var shouldPresentForegroundNotification: Bool { signedInUid != nil && lastToken != nil }

    func consumePendingDeepLink() -> NotificationDeepLink? {
        defer { pendingDeepLink = nil; pendingRecipientUid = nil }
        guard signedInUid != nil, pendingRecipientUid == nil || pendingRecipientUid == signedInUid else { return nil }
        return pendingDeepLink
    }

    private func registerCurrentToken(expectedGeneration: Int) async {
        guard generation == expectedGeneration, let tokenSource else { return }
        if needsInvalidation {
            do { try await tokenSource.unregister() }
            catch { scheduleRetry(expectedGeneration: expectedGeneration); return }
            guard generation == expectedGeneration else { return }
            needsInvalidation = false
        }
        guard signedInUid != nil else { return }
        await refreshPermissionState()
        guard generation == expectedGeneration, permissionState == .enabled else { return }
        if apnsRequestedGeneration != expectedGeneration {
            apnsRequestedGeneration = expectedGeneration
            UIApplication.shared.registerForRemoteNotifications()
        }
        do {
            let token = try await tokenSource.register()
            guard generation == expectedGeneration else { return }
            lastToken = token
            await registerBackend(token, expectedGeneration: expectedGeneration)
        } catch { scheduleRetry(expectedGeneration: expectedGeneration) }
    }

    private func registerBackend(_ token: String, expectedGeneration: Int) async {
        guard generation == expectedGeneration, let uid = signedInUid, let repository else { return }
        do {
            try await repository.register(token: token, uid: uid)
            guard generation == expectedGeneration else { return }
            retryTask?.cancel()
            retryTask = nil
        } catch { scheduleRetry(expectedGeneration: expectedGeneration) }
    }

    private func scheduleRetry(expectedGeneration: Int) {
        guard generation == expectedGeneration, retryTask == nil else { return }
        retryTask = Task { [weak self, retryDelay] in
            do { try await Task.sleep(for: retryDelay) } catch { return }
            guard let self, self.generation == expectedGeneration else { return }
            self.retryTask = nil
            await self.refreshRegistration()
        }
    }

}

/// The foreground prompt is presented by the shell. The same prompt can
/// be answered from a local notification when the app is in the background.
@MainActor
enum LiveStationaryNotifications {
    nonisolated static let continueAction = "liveStationaryContinue"
    nonisolated private static let categoryID = "liveStationary"
    static weak var coordinator: LiveLocationCoordinator?

    static func category() -> UNNotificationCategory {
        let action = UNNotificationAction(
            identifier: continueAction,
            title: String(localized: "liveLocation.stillSharingAction"),
            options: []
        )
        return UNNotificationCategory(identifier: categoryID, actions: [action], intentIdentifiers: [], options: [])
    }

    nonisolated static func promptID(for request: UNNotificationRequest) -> UUID? {
        guard request.trigger == nil, request.content.categoryIdentifier == categoryID else { return nil }
        return UUID(uuidString: request.identifier)
    }

    static func update(previous: UUID?, current: UUID?) {
        if let previous { remove(previous) }
        guard let current else { return }
        let content = UNMutableNotificationContent()
        content.title = String(localized: "liveLocation.stationaryPromptTitle")
        content.body = String(localized: "liveLocation.stationaryPromptBody")
        content.categoryIdentifier = categoryID
        let request = UNNotificationRequest(identifier: current.uuidString, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request) { _ in
            Task { @MainActor in
                // Movement or a stop can clear the prompt while iOS queues it.
                if Self.coordinator?.stationaryPromptID != current { Self.remove(current) }
            }
        }
    }

    private static func remove(_ id: UUID) {
        let center = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: [id.uuidString])
        center.removeDeliveredNotifications(withIdentifiers: [id.uuidString])
    }

    static func beginStop() -> @MainActor () -> Void {
        let task = StopBackgroundTask()
        return { task.end() }
    }

    @MainActor
    private final class StopBackgroundTask {
        private var id = UIBackgroundTaskIdentifier.invalid

        init() {
            // The stop immediately releases the live location stream. Let the
            // callable finish if that was keeping the background process active.
            id = UIApplication.shared.beginBackgroundTask(withName: "Stop stationary Live session") { [weak self] in
                self?.end()
            }
        }

        func end() {
            guard id != .invalid else { return }
            UIApplication.shared.endBackgroundTask(id)
            id = .invalid
        }
    }
}

final class KCCAppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate,
    MessagingDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        let center = UNUserNotificationCenter.current()
        center.delegate = self
        center.setNotificationCategories([LiveStationaryNotifications.category()])
        if FirebaseApp.app() != nil { Messaging.messaging().delegate = self }
        return true
    }

    func applicationDidBecomeActive(_ application: UIApplication) {
        Task { @MainActor in await PushNotificationCoordinator.shared.refreshRegistration() }
    }

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken token: Data) {
        guard FirebaseApp.app() != nil else { return }
        Messaging.messaging().apnsToken = token
        Task { @MainActor in await PushNotificationCoordinator.shared.refreshRegistration() }
    }

    nonisolated func messaging(_ messaging: Messaging, didReceiveRegistration installationId: String?) {
        Task { @MainActor in
            PushNotificationCoordinator.shared.receivedMessagingRegistration(installationId)
        }
    }

    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        if let id = LiveStationaryNotifications.promptID(for: notification.request) {
            return await MainActor.run {
                LiveStationaryNotifications.coordinator?.stationaryPromptID == id ? [.list] : []
            }
        }
        let recipient = notification.request.content.userInfo["recipientUid"] as? String
        return await MainActor.run {
            PushNotificationCoordinator.shared.shouldPresent(recipientUID: recipient)
                ? [.banner, .badge, .sound] : []
        }
    }

    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse
    ) async {
        if let id = LiveStationaryNotifications.promptID(for: response.notification.request) {
            if response.actionIdentifier == LiveStationaryNotifications.continueAction {
                await MainActor.run {
                    LiveStationaryNotifications.coordinator?.confirmStillSharing(promptID: id)
                }
            }
            return
        }
        let userInfo = response.notification.request.content.userInfo
        let recipient = userInfo["recipientUid"] as? String
        let deepLink = PushPayload.deepLink(from: userInfo)
        await MainActor.run {
            PushNotificationCoordinator.shared.receive(deepLink: deepLink, recipientUID: recipient)
        }
    }
}
