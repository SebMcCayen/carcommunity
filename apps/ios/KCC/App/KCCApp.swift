import FirebaseCore
import SwiftUI

@main
struct KCCApp: App {
    @UIApplicationDelegateAdaptor(KCCAppDelegate.self) private var appDelegate
    private let session: AuthSession
    private let signInCoordinator: SignInCoordinator
    private let accessSession: AppAccessSession
    private let restrictedPrivacyCoordinator: LiveLocationCoordinator
    private let diagnostics: IOSDiagnosticsComposition
    private let crashTelemetry: any CrashTelemetry

    init() {
        // Null-safe: a checkout without the gitignored GoogleService-Info.plist
        // (CI, a fresh clone) still builds and renders, with Firebase-backed
        // features standing down — the iOS mirror of Android's
        // `createIfAvailable` pattern.
        FirebaseBootstrap.configureIfAvailable()
        PushNotificationCoordinator.shared.configure(
            repository: FirebasePushTokenRepository.createIfAvailable(),
            tokenSource: FirebaseApp.app() == nil ? nil : FirebaseMessagingTokenSource()
        )

        let repository = FirebaseAuthRepository.createIfAvailable()
        let diagnosticsReporter = FirebaseDiagnosticsReporter.createIfAvailable()
            ?? NoopDiagnosticsReporter()
        let diagnosticsEnvironment = DiagnosticsEnvironment.current()
        crashTelemetry = FirebaseCrashTelemetry.install(environment: diagnosticsEnvironment)
        diagnostics = IOSDiagnosticsComposition(environment: diagnosticsEnvironment)
        session = AuthSession(observing: repository, onIdentityChange: { uid in
            PushNotificationCoordinator.shared.bind(uid: uid)
        })
        accessSession = AppAccessSession(
            accessRepository: FirebaseAccountAccessRepository.createIfAvailable(),
            flagsRepository: FirebaseFeatureFlagsRepository.createIfAvailable()
        )
        // A restricted account must retain the authenticated privacy escape
        // hatch even though the feature shell is removed. This coordinator
        // never requests location: it only observes the user's current live
        // session and can invoke the always-allowed hide command.
        restrictedPrivacyCoordinator = LiveLocationCoordinator(
            repository: FirebaseLiveLocationRepository.createIfAvailable(),
            provider: StubLocationProvider(),
            canShare: false
        )
        signInCoordinator = SignInCoordinator(
            tokenProvider: repository == nil
                ? UnavailableTokenProvider()
                : Self.makeTokenProvider(),
            repository: repository ?? UnavailableAuthRepository(),
            failureReporter: DiagnosticsSignInFailureReporter(
                reporter: diagnosticsReporter,
                environment: diagnosticsEnvironment
            )
        )
    }

    var body: some Scene {
        WindowGroup {
            RootView(
                session: session,
                signInCoordinator: signInCoordinator,
                accessSession: accessSession,
                restrictedPrivacyCoordinator: restrictedPrivacyCoordinator,
                diagnostics: diagnostics
            )
            .preferredColorScheme(configlessUITestColorScheme)
            .modifier(ConfiglessUITestDynamicType())
        }
    }

    /// Only config-less DEBUG builds accept this appearance fixture. Standard
    /// UIKit launch defaults do not reliably override SwiftUI's appearance.
    private var configlessUITestColorScheme: ColorScheme? {
        #if DEBUG
        let arguments = ProcessInfo.processInfo.arguments
        guard FirebaseApp.app() == nil,
              let index = arguments.firstIndex(of: "-KCCUITestTheme"),
              arguments.indices.contains(index + 1)
        else { return nil }
        switch arguments[index + 1] {
        case "Dark": return .dark
        case "Light": return .light
        default: return nil
        }
        #else
        return nil
        #endif
    }

    /// The production provider drives the real Apple flow. In DEBUG builds
    /// pointed at the Firebase Auth emulator (FIREBASE_AUTH_EMULATOR_HOST
    /// set), a fake-token provider exercises the same exchange path without
    /// the Sign in with Apple entitlement (see ADR-002).
    private static func makeTokenProvider() -> AppleIDTokenProvider {
        #if DEBUG
        if ProcessInfo.processInfo.environment["FIREBASE_AUTH_EMULATOR_HOST"] != nil {
            return EmulatorAppleIDTokenProvider(subject: "emulator-dev-user")
        }
        #endif
        return AppleSignInProvider()
    }
}

/// Match the existing config-less appearance fixture: UIKit launch-default
/// caching must not animate a previous test's text size into the next launch.
/// Release and Firebase-configured apps keep the user's real system text size.
private struct ConfiglessUITestDynamicType: ViewModifier {
    @ViewBuilder func body(content: Content) -> some View {
        #if DEBUG
        if let size = ConfiglessUITestSizing.override(
            arguments: ProcessInfo.processInfo.arguments,
            firebaseConfigured: FirebaseApp.app() != nil
        ) {
            content.dynamicTypeSize(size)
        } else { content }
        #else
        content
        #endif
    }
}

#if DEBUG
enum ConfiglessUITestSizing {
    static func override(arguments: [String], firebaseConfigured: Bool) -> DynamicTypeSize? {
        guard !firebaseConfigured,
              let index = arguments.firstIndex(of: "-KCCUITestTextSize"),
              arguments.indices.contains(index + 1)
        else { return nil }
        switch arguments[index + 1] {
        case "Large": return .large
        case "AccessibilityXXXL": return .accessibility5
        default: return nil
        }
    }
}
#endif

/// Stand-in provider for config-less builds: fails immediately with
/// ``SignInUnavailableError`` instead of launching the Apple sheet against a
/// Firebase that does not exist — the coordinator then shows the
/// platform-unsupported notice (Android's `SignInUnavailableException` path).
private struct UnavailableTokenProvider: AppleIDTokenProvider {
    func fetchAppleIDToken() async throws -> AppleIDTokenPayload {
        throw SignInUnavailableError()
    }
}

/// Stand-in repository for config-less builds: every sign-in attempt resolves
/// to ``SignInFailure/unavailable`` via the provider path, and no session
/// ever exists. Keeps the coordinator constructible without optionals
/// spreading through the UI.
private final class UnavailableAuthRepository: AuthRepository {
    var authState: AuthState { .unavailable }

    func authStateUpdates() -> AsyncStream<AuthState> {
        AsyncStream { continuation in
            continuation.yield(.unavailable)
            continuation.finish()
        }
    }

    func signIn(with payload: AppleIDTokenPayload) async throws {
        throw SignInUnavailableError()
    }

    func signOut() throws {}

    func signOut(ifCurrentUidIs expectedUid: String) throws {}
}
