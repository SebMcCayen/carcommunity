import Foundation

/// One emission of the notification-inbox listener — the iOS port of Android's
/// `NotificationsState`, minus `Loading`: a repository stream only ever emits
/// SETTLED results (a snapshot or a failure), and the coordinator supplies the
/// loading state before the first emission (the same split
/// ``EventsListSnapshot`` gets).
enum NotificationsSnapshot: Equatable, Sendable {
    /// The listener failed. `code` is the bare Firestore status name when one
    /// was available (`PERMISSION_DENIED`, `UNAVAILABLE`, …) — a stable,
    /// PII-safe diagnosis, never exception text (the same rule as
    /// ``EventsListSnapshot/failed(code:)``).
    case failed(code: String?)
    /// A fresh snapshot of the inbox, already sorted newest-first
    /// (``Notifications/sortedForInbox(_:)``).
    case loaded([AppNotification])
}

/// In-app notification operations — the iOS port of Android's
/// `NotificationsRepository`, restricted to the in-app inbox slice (items
/// listener + read-state). Firebase-free protocol so the coordinator and
/// screens are unit-testable with fakes.
///
/// The inbox is an owner-only Firestore read (notifications/{uid}/items —
/// firebase/firestore.rules); ALL item writes are backend-only, so read-state
/// changes go through the `notifications-markRead` / `notifications-markAllRead`
/// callables and the seen-marker through `notifications-markSeen` (the actual
/// hyphenated callable identifiers ``FirebaseNotificationsRepository`` calls;
/// `notifications.markRead` etc. is the contract's dotted documentation
/// grouping, not the wire name).
///
/// The Notifications red DOT is separate from per-item read state: it is a
/// last-SEEN marker (``unread(uid:)`` / ``markSeen()``) that mirrors community
/// chat's last-read marker, so opening the inbox clears the dot without marking
/// every row read.
///
/// Push DELIVERY and token registration are OUT OF SCOPE (they need the paid
/// Apple account + console setup); this protocol never touches APNs.
protocol NotificationsRepository: AnyObject, Sendable {
    /// The caller's inbox, newest first, bounded to
    /// ``Notifications/inboxQueryLimit``. Each call returns a fresh stream
    /// backed by its own listener; terminating the stream detaches the
    /// listener.
    func notifications(uid: String) -> AsyncStream<NotificationsSnapshot>

    /// Live "has unseen" flag for `uid`: true while the newest notification is
    /// newer than the caller's last-seen marker
    /// (`userPrivate/{uid}.notificationsLastSeenAt`). Emits false once
    /// ``markSeen()`` runs (or while the inbox is empty). Drives the
    /// Notifications red dot — Android's `observeUnread`. Exposed here for the
    /// future shell wiring; not consumed by the inbox screen itself.
    func unread(uid: String) -> AsyncStream<Bool>

    /// Marks one notification read (`notifications-markRead`). Idempotent.
    /// - Throws: ``KccFunctionsError`` with the contract error code.
    func markRead(notificationId: String) async throws

    /// Marks every unread notification read (`notifications-markAllRead`).
    /// - Throws: ``KccFunctionsError`` with the contract error code.
    func markAllRead() async throws

    /// Stamps the caller's last-seen marker so the red dot clears
    /// (`notifications-markSeen`). Idempotent, best-effort — DELIBERATELY not
    /// the same as ``markAllRead()``: this clears the dot without flipping any
    /// item's read flag, so per-row unread styling survives opening the inbox.
    /// - Throws: ``KccFunctionsError`` with the contract error code.
    func markSeen() async throws

    /// The signed-in user's uid, or nil with no session — the same repository
    /// seam ``EventsRepository/currentUserId()`` provides (the shell passes no
    /// identity into the feature).
    func currentUserId() -> String?
}
