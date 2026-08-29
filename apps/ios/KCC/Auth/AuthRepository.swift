import Foundation

/// Payload produced by an Apple sign-in flow, ready for the Firebase
/// credential exchange (docs/auth-mobile-requirements.md — iOS requirements).
struct AppleIDTokenPayload: Sendable {
    /// The Apple identity token (JWT), UTF-8 decoded.
    let identityToken: String
    /// The un-hashed nonce whose SHA-256 was sent in the authorization request.
    let rawNonce: String
    /// The user's name, only ever provided by Apple on FIRST sign-in.
    let fullName: PersonNameComponents?
}

/// Authentication session boundary — the iOS port of Android's
/// `auth/AuthRepository.kt`.
///
/// Implementations wrap Firebase Auth; the protocol stays Firebase-free so
/// sign-in orchestration can be unit-tested with fakes.
protocol AuthRepository: AnyObject, Sendable {
    /// Current session state.
    var authState: AuthState { get }

    /// Session-state updates. Each call returns a fresh stream that yields the
    /// current state immediately and then every change.
    func authStateUpdates() -> AsyncStream<AuthState>

    /// Exchanges an Apple identity token for a Firebase session
    /// (`OAuthProvider.appleCredential` → `signIn(with:)`).
    ///
    /// - Throws: when Firebase rejects the credential.
    func signIn(with payload: AppleIDTokenPayload) async throws

    /// Clears the Firebase session. Safe to call when already signed out.
    func signOut() throws
}

/// Fetches an Apple identity token — the iOS analog of Android's
/// `GoogleIdTokenProvider`. The production implementation drives
/// `ASAuthorizationController`; tests and the emulator path substitute fakes.
protocol AppleIDTokenProvider: Sendable {
    /// Runs the Apple sign-in UI flow and returns the token payload.
    ///
    /// - Throws: ``SignInCancelledError`` when the USER dismissed the sheet,
    ///   ``SignInUnavailableError`` when the flow cannot run in this build,
    ///   or any other error for real failures.
    func fetchAppleIDToken() async throws -> AppleIDTokenPayload
}

/// The user dismissed the sign-in sheet. Not a fault.
struct SignInCancelledError: Error {}

/// Sign-in cannot run in this build (no Firebase config / no entitlement).
struct SignInUnavailableError: Error {}

/// Carrier for a stable, PII-safe diagnostic code (e.g. a Firebase
/// `AuthErrorCode` name) — the iOS analog of Android's `SignInDiagnosticInfo`.
/// Never carries messages, tokens, or any PII.
protocol SignInDiagnosticInfo {
    var diagnosticCode: String? { get }
}

/// Thrown by the repository when the Firebase credential exchange fails,
/// preserving the underlying error and lifting its stable error-code name
/// into ``diagnosticCode`` so the pure coordinator can report it without
/// importing Firebase types.
struct SignInFailedError: Error, SignInDiagnosticInfo {
    let underlying: Error
    let diagnosticCode: String?
}
