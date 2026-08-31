import Foundation

/// UI-facing state of the live inbox (conversation list) — Android's
/// `DmConversationsState`. Rows here are RAW (blocked-pair-marker rows are
/// already dropped by the repository); the caller's `blockVisibility` hidden
/// set is applied on top by ``ConversationsCoordinator``.
enum DmConversationsState: Equatable, Sendable {
    case loading
    /// The inbox listener failed with no cached data to fall back on. `code`
    /// is the Firestore error code name when known (e.g. "FAILED_PRECONDITION"
    /// for a missing composite index, "UNAVAILABLE" when offline) — carried
    /// only for diagnostics; the UI shows the same retryable message.
    case error(code: String?)
    case loaded([DmConversation])
}

extension DmConversationsState {
    /// True when ANY conversation in a loaded inbox has unread messages for
    /// the caller — the aggregate "the Friends tab has something new" boolean
    /// behind the future chat-bubble/tab dots. Loading/Error are not-unread:
    /// a dot is a positive claim, so an inbox that has not loaded (or failed)
    /// shows none rather than a false one (Android: `anyUnread()`).
    var anyUnread: Bool {
        guard case .loaded(let conversations) = self else { return false }
        return conversations.contains { $0.unreadCount > 0 }
    }
}

/// UI-facing state of a live message thread (the newest window) — Android's
/// `DmThreadState`. There is deliberately no error case: for a self-derived
/// `pairId` the only realistic listener failure is "the conversation doc
/// doesn't exist yet" (the messages read rule `get()`s the parent), which is
/// not an error — it's an empty thread the caller can start. A blocked pair
/// is ALSO denied by the rules and renders the same way, deliberately
/// neutral. Genuine transient failures are retried by the Firestore SDK.
enum DmThreadState: Equatable, Sendable {
    case loading
    case loaded([DmMessage])
}

/// Direct-messaging access — the iOS port of Android's `DmRepository`.
///
/// Reads are live Firestore listeners (rules grant member reads of
/// `conversations` + `.../messages`); sending, marking-read, and older-page
/// pagination go through the member-gated `dm-*` callables (europe-west1).
/// Firebase-free protocol so the coordinators/screens are unit-testable with
/// fakes.
protocol ConversationsRepository: AnyObject, Sendable {
    /// Live inbox for `uid`, newest-first, bounded to
    /// ``Dm/conversationsQueryLimit``. Loading until the first snapshot.
    /// Rows whose conversation document carries the `blockedPair` marker are
    /// dropped before they ever reach the caller.
    func observeConversations(uid: String) -> AsyncStream<DmConversationsState>

    /// Live newest-window of a thread (``Dm/messagesPageSize``),
    /// chronological. Empty until the first message exists — including the
    /// not-yet-created conversation the rules deny (PERMISSION_DENIED reads
    /// as an empty, not-yet-started thread).
    func observeThread(conversationId: String) -> AsyncStream<DmThreadState>

    /// `dm-sendMessage` — sends to `toUid`, creating the conversation on the
    /// first message. `clientId` is the send idempotency key: it is used
    /// verbatim as the message document id, so a resend of the SAME clientId
    /// (an optimistic retry) is exactly-once server-side and the delivered
    /// document reconciles against the local optimistic bubble by that key.
    func sendMessage(toUid: String, text: String, clientId: String?) async -> DmSendResult

    /// `dm-getMessages` — an older page before the `before` ISO cursor (page
    /// size 30). Returns ``DmOlderResult/failed`` on a transient callable
    /// failure so the caller can distinguish it from a genuine
    /// end-of-pagination and offer a retry.
    func loadOlder(conversationId: String, before: String) async -> DmOlderResult

    /// `dm-markRead` — clears the caller's unread counter for the
    /// conversation. Idempotent and best-effort (failures are swallowed).
    func markRead(conversationId: String) async
}

/// The caller's mutually-hidden uid set — the client half of block
/// invisibility (Android: `BlockVisibilityRepository`). One owner-readable
/// document listener on `blockVisibility/{uid}` covers every filtered
/// surface: `hiddenUids` is the server-maintained UNION of "uids this user
/// blocked" and "uids that blocked this user", so a single containment check
/// covers both directions.
protocol BlockVisibilityRepository: AnyObject, Sendable {
    /// Emits the current hidden set; the empty set when signed out, when the
    /// document does not exist (no blocks), or when the read is terminally
    /// denied (the server-side filters keep working regardless).
    func observeHiddenUids() -> AsyncStream<Set<String>>
}

/// Pure filter applying the hidden set to a message/row window (Android:
/// `BlockVisibility.filterHiddenAuthors`). An item whose author is nil is
/// KEPT: a malformed document is a rendering problem, not a block-evasion
/// route (none of these collections accept client writes).
enum BlockVisibility {
    static func filterHiddenAuthors<T>(
        _ items: [T],
        hidden: Set<String>,
        authorUidOf: (T) -> String?
    ) -> [T] {
        guard !hidden.isEmpty else { return items }
        return items.filter { item in
            guard let author = authorUidOf(item) else { return true }
            return !hidden.contains(author)
        }
    }
}
