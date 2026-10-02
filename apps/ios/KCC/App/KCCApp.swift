import SwiftUI

@main
struct KCCApp: App {
    private let session: AuthSession
    private let signInCoordinator: SignInCoordinator
    private let accessSession: AppAccessSession
    private let restrictedPrivacyCoordinator: LiveLocationCoordinator

    init() {
        // Null-safe: a checkout without the gitignored GoogleService-Info.plist
        // (CI, a fresh clone) still builds and renders, with Firebase-backed
        // features standing down — the iOS mirror of Android's
        // `createIfAvailable` pattern.
        FirebaseBootstrap.configureIfAvailable()

        let repository = FirebaseAuthRepository.createIfAvailable()
        let diagnosticsReporter = FirebaseDiagnosticsReporter.createIfAvailable()
            ?? NoopDiagnosticsReporter()
        let diagnosticsEnvironment = DiagnosticsEnvironment.current()
        session = AuthSession(repository: repository)
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
                restrictedPrivacyCoordinator: restrictedPrivacyCoordinator
            )
        }
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
}
