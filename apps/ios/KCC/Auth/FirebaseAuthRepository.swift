import FirebaseAuth
import FirebaseCore
import Foundation

/// ``AuthRepository`` backed by Firebase Auth — the iOS port of Android's
/// `FirebaseAuthRepository.kt`.
///
/// The Firebase SDK owns token persistence and refresh internally (backed by
/// Keychain) — tokens are never manually stored or logged
/// (docs/auth-mobile-requirements.md).
///
/// Construction is guarded: ``createIfAvailable()`` returns nil when Firebase
/// is not configured in this build (no GoogleService-Info.plist), so the app
/// renders an unauthenticated shell instead of crashing.
///
/// A single instance is cached: the auth-state listener registered in `init`
/// lives for the process lifetime by design.
final class FirebaseAuthRepository: AuthRepository, @unchecked Sendable {
    private let auth: Auth
    private let lock = NSLock()
    private var state: AuthState
    private var continuations: [UUID: AsyncStream<AuthState>.Continuation] = [:]

    private init(auth: Auth) {
        self.auth = auth
        self.state = Self.toAuthState(auth.currentUser)
        auth.addStateDidChangeListener { [weak self] _, user in
            self?.update(Self.toAuthState(user))
        }
    }

    var authState: AuthState {
        lock.lock()
        defer { lock.unlock() }
        return state
    }

    func authStateUpdates() -> AsyncStream<AuthState> {
        AsyncStream { continuation in
            let id = UUID()
            lock.lock()
            continuations[id] = continuation
            let current = state
            lock.unlock()
            continuation.yield(current)
            continuation.onTermination = { [weak self] _ in
                guard let self else { return }
                self.lock.lock()
                self.continuations[id] = nil
                self.lock.unlock()
            }
        }
    }

    private func update(_ newState: AuthState) {
        lock.lock()
        state = newState
        let sinks = Array(continuations.values)
        lock.unlock()
        for sink in sinks {
            sink.yield(newState)
        }
    }

    func signIn(with payload: AppleIDTokenPayload) async throws {
        let credential = OAuthProvider.appleCredential(
            withIDToken: payload.identityToken,
            rawNonce: payload.rawNonce,
            fullName: payload.fullName
        )
        do {
            try await auth.signIn(with: credential)
        } catch {
            // Preserve the concrete Firebase error as `underlying` and lift
            // its stable, PII-safe AuthErrorCode name into the diagnostic
            // code, letting the pure coordinator report the real Firebase
            // status without importing Firebase types. Never logs the message.
            let code = AuthErrorCode(rawValue: (error as NSError).code)
            throw SignInFailedError(
                underlying: error,
                diagnosticCode: code.map { String(describing: $0) }
            )
        }
    }

    func signOut() throws {
        try auth.signOut()
    }

    private static func toAuthState(_ user: User?) -> AuthState {
        guard let user else { return .signedOut }
        return .signedIn(uid: user.uid, displayName: user.displayName)
    }

    // MARK: - Factory

    private static let cachedLock = NSLock()
    nonisolated(unsafe) private static var cached: FirebaseAuthRepository?

    /// Returns the process-wide repository when Firebase is configured for
    /// this build, or nil when GoogleService-Info.plist is absent (CI, local
    /// validation builds — see apps/ios/README.md).
    ///
    /// When the `FIREBASE_AUTH_EMULATOR_HOST` environment variable is set
    /// (e.g. `127.0.0.1:9099`, matching firebase.json's auth emulator port),
    /// the Auth SDK is pointed at the emulator before first use — the only
    /// way to exercise Apple sign-in without a paid Apple Developer
    /// membership (ADR-002).
    static func createIfAvailable() -> AuthRepository? {
        guard FirebaseApp.app() != nil else { return nil }
        cachedLock.lock()
        defer { cachedLock.unlock() }
        if let cached { return cached }
        let auth = Auth.auth()
        if let emulator = ProcessInfo.processInfo.environment["FIREBASE_AUTH_EMULATOR_HOST"] {
            let parts = emulator.split(separator: ":")
            if parts.count == 2, let port = Int(parts[1]) {
                auth.useEmulator(withHost: String(parts[0]), port: port)
            }
        }
        let repository = FirebaseAuthRepository(auth: auth)
        cached = repository
        return repository
    }
}
