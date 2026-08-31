import Foundation

/// Chat-channels domain — the COMMUNITY app-wide chat + per-CONVOY chats. The
/// iOS port of Android's `chatchannels/ChatChannels.kt` (pure Kotlin domain).
///
/// The backend (europe-west1 callables `communityChat-*` / `convoyChat-*`, plus
/// the member-readable Firestore trees `communityChat/global/messages` and
/// `convoyChats/{convoyId}/messages`) is the sole source of truth; the client
/// NEVER writes the message trees (firebase/firestore.rules deny all client
/// writes — the callables are the only writer). Both channels share ONE
/// denormalized message shape: `{ senderUid, text, createdAt, senderDisplayName,
/// senderAvatarPath, mentionedUids }` — the sender's safe profile is stamped on
/// each message so a channel renders with no per-message profile lookup
/// (channels have no bounded member set to key a profile map on, unlike a DM).
///
/// Everything here is pure Swift (no Firebase / SwiftUI types) so the
/// mapping/parsing/merge/unread logic is unit-testable with fakes — the same
/// split ``Events`` uses.
///
/// NOTE ON MODERATION: unlike the separate EVENT-chat surface
/// (`events/{id}/messages`, contracts/schemas/event-chat.schema.json), which
/// carries a client-visible `visible → auto_hidden → removed` moderation state
/// machine, the community/convoy channels have NO moderation-state field on the
/// message. Their moderation is entirely server-side — an admin HARD-DELETES a
/// message (`chatchannels-adminDeleteMessage`) so it simply disappears from both
/// the live listener and the pages — so there is nothing to decode or filter
/// client-side. The only client-side gating is BLOCKING (``ChatBlockVisibility``)
/// and the reporting affordance (``ChatReportReason``). Mirrors Android's
/// `ChannelMessage`, which likewise decodes no moderation field.

// MARK: - Constants

/// Backend `CHAT_MESSAGE_MAX_LENGTH` — community + convoy share the 1..2000 cap.
/// Android: `CHANNEL_MESSAGE_MAX_LENGTH`.
let channelMessageMaxLength = 2000

/// Backend `CHAT_MESSAGES_PAGE_SIZE` — the newest-first live window and each
/// older page. Android: `CHANNEL_MESSAGES_PAGE_SIZE`.
let channelMessagesPageSize = 30

/// Server-side hard reject for `communityChat-post` (`MAX_MESSAGE_MENTIONS`);
/// the client dedups/caps too so no local state reaches the reject. Android:
/// `MAX_MESSAGE_MENTIONS`.
let channelMaxMessageMentions = 10

// MARK: - Delivery state

/// Delivery state of a rendered channel message. Server-sourced messages (the
/// live listener, paginated pages) are always ``sent``; only the caller's OWN
/// optimistic bubble — shown instantly on tap before the `*-post` round-trip
/// resolves — carries ``sending`` or ``failed``, reconciled away by
/// ``ChannelMessage/clientId`` when the real document arrives. Android:
/// `ChannelDeliveryState`.
enum ChannelDeliveryState: Equatable, Sendable {
    case sent
    case sending
    case failed
}

// MARK: - Reply snapshot

/// Denormalized snapshot of the message an inline reply is quoting
/// (WhatsApp-style quote, not a thread), mirroring the backend `ChatReplyTo`
/// the server writes onto the replying message. Renders the quote header with
/// no extra lookup and survives the parent TTL-expiring. Gated behind the
/// ``ChatFeatureFlags/chatRepliesEnabled`` flag (OFF by default). Android:
/// `ChannelReplyTo`.
struct ChannelReplyTo: Equatable, Sendable {
    let messageId: String
    let senderUid: String
    let senderDisplayName: String?
    let textPreview: String
}

// MARK: - Message

/// One rendered channel message. ``createdAtIso`` is the pagination cursor for
/// older pages (the `before` argument the `*-list` callables expect). The
/// denormalized sender profile lets the channel render the author's name/avatar
/// without a lookup. Android: `ChannelMessage`.
struct ChannelMessage: Equatable, Sendable, Identifiable {
    let id: String
    let senderUid: String
    let text: String
    let senderDisplayName: String?
    let senderAvatarPath: String?
    /// The message instant, or nil when the doc carried no parseable
    /// `createdAt` (the message is kept but sorts last and never counts as
    /// unread — see ``ChannelThread``).
    let createdAt: Date?
    /// The raw ISO-8601 `createdAt` (callable pages) — the pagination cursor.
    /// Nil for live-listener docs (which carry a Firestore `Timestamp`); the
    /// repository fills it from the timestamp so older-page paging still has a
    /// cursor.
    let createdAtIso: String?
    /// Server-accepted @mention set (`[]` for no-mention, pre-mention, and
    /// every convoy message — `convoyChat-post` accepts no mentions).
    let mentionedUids: [String]
    /// The sender's optimistic idempotency key; the backend uses it verbatim as
    /// the doc ``id``, so it is only the local join key that reconciles the
    /// caller's own pending bubble against the arriving snapshot. NOT an
    /// identity signal — use ``senderUid``. Nil on legacy messages.
    let clientId: String?
    let deliveryState: ChannelDeliveryState
    /// Why an optimistic send failed — set only on a ``ChannelDeliveryState/failed``
    /// bubble, nil otherwise.
    let sendError: ChannelSendError?
    /// The server-built snapshot of the replied-to message, present only on a
    /// resolved reply (an ordinary message carries none).
    let replyTo: ChannelReplyTo?

    init(
        id: String,
        senderUid: String,
        text: String,
        senderDisplayName: String? = nil,
        senderAvatarPath: String? = nil,
        createdAt: Date? = nil,
        createdAtIso: String? = nil,
        mentionedUids: [String] = [],
        clientId: String? = nil,
        deliveryState: ChannelDeliveryState = .sent,
        sendError: ChannelSendError? = nil,
        replyTo: ChannelReplyTo? = nil
    ) {
        self.id = id
        self.senderUid = senderUid
        self.text = text
        self.senderDisplayName = senderDisplayName
        self.senderAvatarPath = senderAvatarPath
        self.createdAt = createdAt
        self.createdAtIso = createdAtIso
        self.mentionedUids = mentionedUids
        self.clientId = clientId
        self.deliveryState = deliveryState
        self.sendError = sendError
        self.replyTo = replyTo
    }
}

// MARK: - Live stream state

/// UI-facing state of a live channel message stream (the newest window). Like
/// the Events list stream there is deliberately no `Error` case: a transient
/// listener failure is retried by the Firestore SDK and an empty/denied channel
/// simply renders the empty state; the coordinator surfaces send/paging
/// failures separately. Android: `ChannelMessagesState`.
enum ChannelMessagesState: Equatable, Sendable {
    case loading
    case loaded([ChannelMessage])
}

// MARK: - Error codes

/// Canonical callable error codes we branch on, decoupled from Firebase's
/// `FunctionsErrorCode` so the mapping is testable on plain values. Any code we
/// don't special-case collapses to ``other``. Android: `ChannelErrorCode`.
enum ChannelErrorCode: Equatable, Sendable {
    case unauthenticated
    case permissionDenied
    case invalidArgument
    case failedPrecondition
    case notFound
    case other
}

/// A user-facing send-failure category. ``cannotDeliver`` is deliberately
/// neutral: the backend returns `failed-precondition` (profile missing /
/// still-invited convoy member) and `not-found` (convoy gone / outsider
/// probing) without a client-facing discriminator, so both collapse to one
/// neutral message. Android: `ChannelSendError`.
enum ChannelSendError: Equatable, Sendable {
    case signedOut
    case notMember
    case invalid
    case cannotDeliver
    case generic

    /// Whether re-sending the SAME message could plausibly succeed. Only
    /// ``generic`` (transient/network/unknown) is retryable; the rest are
    /// terminal for this message. Android: `ChannelSendError.isRetryable`.
    var isRetryable: Bool { self == .generic }
}

/// Pure code → send-error mapping. Branch on the code, never the message.
/// Android: `ChannelErrorMapper`.
enum ChannelErrorMapper {
    static func mapSend(_ code: ChannelErrorCode) -> ChannelSendError {
        switch code {
        case .unauthenticated: return .signedOut
        case .permissionDenied: return .notMember
        case .invalidArgument: return .invalid
        // profile-missing / still-invited convoy member / convoy-not-found all
        // collapse to a single neutral message (never reveal which).
        case .failedPrecondition, .notFound: return .cannotDeliver
        case .other: return .generic
        }
    }
}

// MARK: - Send / page / report results

/// Outcome of a `*-post` callable. Android: `ChannelSendResult`.
enum ChannelSendResult: Equatable, Sendable {
    /// ``mentionedUids`` is the ACCEPTED mention set the server echoes back,
    /// which may be SMALLER than what was sent (silent drops past the cap or of
    /// members who deleted/blocked between picking and posting).
    case sent(messageId: String, mentionedUids: [String])
    case failed(ChannelSendError)
}

/// A page of older messages from a `*-list` callable (newest-first within the
/// page). Android: `ChannelMessagesPage`.
struct ChannelMessagesPage: Equatable, Sendable {
    let messages: [ChannelMessage]
    let nextBefore: String?
    let hasMore: Bool
}

/// Outcome of an older-page load. ``loaded`` carries the backend's own
/// `hasMore`; ``failed`` is a transient callable error that must NOT be
/// conflated with end-of-pagination (the caller offers retry instead of
/// permanently ending paging). Android: `ChannelOlderResult`.
enum ChannelOlderResult: Equatable, Sendable {
    case loaded(ChannelMessagesPage)
    case failed
}

/// Outcome of a `chatchannels-reportMessage` call. Deliberately binary: the
/// report queue is fire-and-forget, so the caller only needs "reached the
/// backend" vs "didn't"; the specific reason (dedupe, rate-limit, …) never
/// surfaces to the reporter. Android: `ChannelReportResult`.
enum ChannelReportResult: Equatable, Sendable {
    case reported
    case failed
}

/// The report reasons accepted by `chatchannels-reportMessage`
/// (contracts/functions.json `chatchannels.reportMessage`,
/// `CHAT_MESSAGE_REPORT_REASONS`). The raw value IS the backend wire string and
/// the localization suffix (`chat.reportReason.<wire>`). Android:
/// `ChatReportReason`.
enum ChatReportReason: String, Equatable, Sendable, CaseIterable {
    case harassment
    case hateOrAbuse = "hate_or_abuse"
    case spam
    case unsafeDriving = "unsafe_driving"
    case privacy
    case other

    /// The backend wire string (identical to the raw value; kept as an explicit
    /// accessor so call sites read like Android's `reason.wire`).
    var wire: String { rawValue }
}
