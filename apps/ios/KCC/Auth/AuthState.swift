/// Authenticated-session state as observed from Firebase Auth — the iOS port
/// of Android's `auth/AuthState.kt`.
///
/// The backend is always the source of truth for roles, entitlements, and
/// moderation status — this state only reflects whether a Firebase session
/// exists on the device (docs/auth-mobile-requirements.md).
enum AuthState: Equatable, Sendable {
    /// Firebase is not configured in this build (no GoogleService-Info.plist).
    /// Sign-in is unavailable; the app must not crash.
    case unavailable

    /// No Firebase session on the device.
    case signedOut

    /// A Firebase session exists. `uid` is the canonical identity key.
    case signedIn(uid: String, displayName: String?)
}
