import Foundation

/// One emission of the notification-preferences listener — the iOS port of
/// Android's `NotificationSettingsState`, minus `Loading`: a repository stream
/// only ever emits a SETTLED result, and the coordinator supplies the loading
/// state before the first emission.
///
/// There is no `failed` case: an absent `userPrivate/{uid}` document or a
/// transient read error degrades to ``NotificationPreferences/allEnabled``
/// (the enabled default), exactly as Android's `observePreferences` does —
/// missing preferences mean "nothing opted out", not an error to surface.
enum NotificationSettingsSnapshot: Equatable, Sendable {
    case loaded(NotificationPreferences)
}

/// A preferences-write failure carrying ONLY the bare Firestore status name
/// (`PERMISSION_DENIED`, `UNAVAILABLE`, …) when one was available — the same
/// PII-safe diagnosis rule as ``RsvpWriteError``: a status name is the whole
/// diagnosis; exception text (which can embed the document path and the
/// project id) is never carried. Pure Swift so the coordinator branches on the
/// CODE with no Firebase dependency.
struct NotificationSettingsWriteError: Error, Equatable, Sendable {
    /// The bare Firestore status name, or nil when the failure carried none.
    let code: String?
}

/// Owner-scoped notification-preferences access — the iOS port of Android's
/// `NotificationSettingsRepository`. Firebase-free protocol for testability.
///
/// Preferences are a DIRECT rules-validated write to
/// `userPrivate/{uid}.notificationPreferences` — no callable (unlike the inbox
/// read-state, which is backend-write-only).
protocol NotificationSettingsRepository: AnyObject, Sendable {
    /// The caller's preferences. Each call returns a fresh stream backed by its
    /// own listener; terminating the stream detaches the listener.
    func preferences(uid: String) -> AsyncStream<NotificationSettingsSnapshot>

    /// Persists the caller's preferences (`notificationPreferences` +
    /// `updatedAt: serverTimestamp`).
    /// - Throws: ``NotificationSettingsWriteError`` with the PII-safe status
    ///   name.
    func savePreferences(uid: String, preferences: NotificationPreferences) async throws
}
