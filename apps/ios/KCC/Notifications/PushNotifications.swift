@preconcurrency import FirebaseCore
@preconcurrency import FirebaseMessaging
import CryptoKit
import Foundation
import Observation
import UIKit
@preconcurrency import UserNotifications

protocol PushTokenRepository: Sendable {
    func register(token: String) async throws
    func unregister(token: String) async throws
}

protocol PushTokenSource: Sendable {
    func register() async
    func unregister() async
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

    func register(token: String) async throws {
        var payload: [String: Any] = ["token": token, "platform": "ios"]
        if let appVersion, !appVersion.isEmpty { payload["appVersion"] = appVersion }
        if let buildNumber, !buildNumber.isEmpty { payload["buildNumber"] = buildNumber }
        _ = try await functions.call("notifications-registerPushToken", payload: payload)
    }

    func unregister(token: String) async throws {
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
    func register() async {
        await withCheckedContinuation { continuation in
            Messaging.messaging().register { _ in continuation.resume() }
        }
    }

    func unregister() async {
        await withCheckedContinuation { continuation in
            Messaging.messaging().unregister { _ in continuation.resume() }
        }
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
    @ObservationIgnored private var lastToken: String?
    @ObservationIgnored private var registrationTask: Task<Void, Never>?

    private init() {}

    func configure(repository: PushTokenRepository?, tokenSource: PushTokenSource?) {
        self.repository = repository
        self.tokenSource = tokenSource
    }

    func bind(uid: String?) async {
        if uid == nil {
            signedInUid = nil
            pendingDeepLink = nil
            registrationTask?.cancel()
            // Try the authenticated cleanup first (it can still succeed while
            // Firebase is publishing the auth transition), then invalidate the
            // FCM registration locally. The latter is the security backstop:
            // background APNs alerts for the previous member stop, and the
            // backend prunes the dead token on its next send.
            if let lastToken, let repository {
                try? await repository.unregister(token: lastToken)
            }
            await tokenSource?.unregister()
            lastToken = nil
            await refreshPermissionState()
            return
        }
        guard uid != signedInUid else {
            await refreshPermissionState()
            return
        }
        signedInUid = uid
        await refreshPermissionState()
        await registerCurrentTokenIfPermitted()
    }

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
            UIApplication.shared.registerForRemoteNotifications()
            await registerCurrentTokenIfPermitted()
        }
    }

    func refreshPermissionState() async {
        let settings = await UNUserNotificationCenter.current().notificationSettings()
        permissionState = PushPermissionState(settings.authorizationStatus)
    }

    func receivedMessagingRegistration(_ identifier: String?) {
        guard let identifier, !identifier.isEmpty else { return }
        lastToken = identifier
        register(identifier)
    }

    func refreshRegistration() async {
        await registerCurrentTokenIfPermitted()
    }

    func receive(userInfo: [AnyHashable: Any]) {
        pendingDeepLink = PushPayload.deepLink(from: userInfo)
    }

    func receive(deepLink: NotificationDeepLink) {
        pendingDeepLink = deepLink
    }

    var shouldPresentForegroundNotification: Bool { signedInUid != nil }

    func consumePendingDeepLink() -> NotificationDeepLink? {
        defer { pendingDeepLink = nil }
        return pendingDeepLink
    }

    private func registerCurrentTokenIfPermitted() async {
        guard permissionState == .enabled, signedInUid != nil, let tokenSource else { return }
        UIApplication.shared.registerForRemoteNotifications()
        await tokenSource.register()
    }

    private func register(_ token: String) {
        guard signedInUid != nil, let repository else { return }
        registrationTask?.cancel()
        registrationTask = Task {
            do { try await repository.register(token: token) } catch { return }
        }
    }
}

final class KCCAppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate,
    MessagingDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        if FirebaseApp.app() != nil { Messaging.messaging().delegate = self }
        return true
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
        await MainActor.run {
            PushNotificationCoordinator.shared.shouldPresentForegroundNotification
                ? [.banner, .badge, .sound] : []
        }
    }

    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse
    ) async {
        let deepLink = PushPayload.deepLink(from: response.notification.request.content.userInfo)
        await MainActor.run {
            PushNotificationCoordinator.shared.receive(deepLink: deepLink)
        }
    }
}
