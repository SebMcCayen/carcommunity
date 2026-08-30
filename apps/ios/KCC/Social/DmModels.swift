import Foundation

/// Direct-messaging domain (1:1 friend DMs) — the iOS port of Android's
/// `dm/Dm.kt`. The backend (europe-west1 callables `dm-sendMessage` /
/// `dm-getMessages` / `dm-markRead`, plus member-readable Firestore
/// `conversations/{pairId}` + `.../messages`) is the source of truth; the
/// client never writes the tree. Everything here is pure Swift so the
/// mapping/parsing/merge logic is unit-testable without Firebase.
///
/// Contract highlights (functions/src/dm):
///  - `pairId` = the two participant UIDs sorted and joined with `__`
///    (``dmPairId(_:_:)``) — order-independent, so both friends resolve the
///    SAME canonical document and the client can derive the conversation id
///    for any friend locally, without a lookup.
///  - conversation docs carry denormalized `memberProfiles`, a per-member
///    `unread` map, and a `lastMessage` preview, so the live inbox listener
///    renders fully client-side.
///  - messages page newest-first, 30 per page; `dm-getMessages` takes an ISO
///    `before` cursor for older pages.
enum Dm {
    /// Backend DM_MESSAGE_MAX_LENGTH (DMs get 2000, vs event chat's 1000).
    static let messageMaxLength = 2000

    /// Backend DM_MESSAGES_PAGE_SIZE (newest-first window).
    static let messagesPageSize = 30

    /// Upper bound on the live inbox listener (newest-first by
    /// `lastMessageAt`), so it never syncs/holds the full conversation set.
    static let conversationsQueryLimit = 50
}

extension Optional where Wrapped == String {
    /// The trimmed value when it contains any non-whitespace, else nil —
    /// the shared "is there a usable display name" rule for every Social
    /// surface, so a whitespace-only name can never render as a blank label,
    /// row, or title instead of the unknown-member fallback.
    var trimmedNonBlank: String? {
        guard let self else { return nil }
        let trimmed = self.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}

/// A conversation participant, as surfaced to the caller's UI.
struct DmUser: Equatable, Sendable {
    let uid: String
    let displayName: String?
    let avatarPath: String?
}

/// Delivery state of a rendered DM. Server-sourced messages are always
/// ``sent``; only the caller's own OPTIMISTIC bubble carries ``sending`` or
/// ``failed``, and it is reconciled away (by clientId) the moment the real
/// document arrives from the listener.
enum DmDeliveryState: Equatable, Sendable {
    case sent
    case sending
    case failed
}

/// Denormalized snapshot of the message an inline reply is quoting (server
/// -built and authoritative; Android: `DmReplyTo`). Parsed for rendering
/// parity — the compose-a-reply entry point ships with the chat-hub slice
/// (it is gated on the `chatReplies` flag there too).
struct DmReplyTo: Equatable, Sendable {
    let messageId: String
    let senderUid: String
    let senderDisplayName: String?
    let textPreview: String
}

/// A user-facing send failure category. ``cannotDeliver`` is deliberately
/// neutral: the backend returns `failed-precondition` for BOTH "not friends"
/// and "blocked" with no discriminator, and privacy parity forbids revealing
/// a block — so both collapse to one neutral message.
enum DmSendError: Equatable, Sendable {
    case signedOut
    case notMember
    case invalid
    case cannotDeliver
    case generic

    /// Whether re-sending the SAME message could plausibly succeed. Only
    /// ``generic`` (a transient/network/unknown failure) is retryable; the
    /// rest are terminal for this message, so the UI shows the reason WITHOUT
    /// a pointless "tap to retry" that would just fail the same way.
    var isRetryable: Bool { self == .generic }
}

/// A single rendered DM. `createdAtIso` is the pagination cursor for older
/// pages.
///
/// `clientId` is the idempotency key the SENDER attached on the optimistic
/// path; it is persisted as both the `clientId` field and the doc id, so it
/// is visible to BOTH participants. Locally it is ONLY the join key that
/// reconciles the caller's own pending optimistic bubble against the
/// arriving snapshot — never a security/identity signal (use `senderUid` for
/// attribution).
struct DmMessage: Equatable, Sendable, Identifiable {
    let id: String
    let senderUid: String
    let text: String
    let createdAtMillis: Int64?
    let createdAtIso: String?
    var clientId: String?
    var deliveryState: DmDeliveryState
    /// Why an optimistic send failed; set only on a ``DmDeliveryState/failed``
    /// bubble. Drives the specific failure reason under the bubble and
    /// whether a retry is offered (``DmSendError/isRetryable``).
    var sendError: DmSendError?
    /// The server-built snapshot of the message this DM inline-replies to,
    /// present only on a reply whose parent was resolved.
    var replyTo: DmReplyTo?

    init(
        id: String,
        senderUid: String,
        text: String,
        createdAtMillis: Int64?,
        createdAtIso: String?,
        clientId: String? = nil,
        deliveryState: DmDeliveryState = .sent,
        sendError: DmSendError? = nil,
        replyTo: DmReplyTo? = nil
    ) {
        self.id = id
        self.senderUid = senderUid
        self.text = text
        self.createdAtMillis = createdAtMillis
        self.createdAtIso = createdAtIso
        self.clientId = clientId
        self.deliveryState = deliveryState
        self.sendError = sendError
        self.replyTo = replyTo
    }
}

/// Denormalized last-message preview shown on an inbox row.
struct DmMessagePreview: Equatable, Sendable {
    let text: String
    let senderUid: String
    let createdAtMillis: Int64?
}

/// One inbox row: the other participant, the last-message preview, and my
/// unread count.
struct DmConversation: Equatable, Sendable, Identifiable {
    let conversationId: String
    let otherUser: DmUser
    let lastMessage: DmMessagePreview?
    let unreadCount: Int
    let lastMessageAtMillis: Int64?

    var id: String { conversationId }
}

/// A raw conversation document, with Firebase types already extracted to
/// plain Swift (timestamps → epoch millis) by the Firebase repository. Kept
/// separate from ``DmConversation`` so the caller-oriented projection
/// (``DmMapper/conversation(conversationId:doc:callerUid:)``) stays a pure,
/// testable function.
struct DmConversationDoc: Equatable, Sendable {
    let members: [String]
    let memberProfiles: [String: DmUser]
    let lastMessageText: String?
    let lastMessageSenderUid: String?
    let lastMessageAtMillis: Int64?
    let unread: [String: Int64]
    /// Backend marker: this pair is blocked, so the `blocking-onBlockWrite`
    /// trigger has blanked the document's `lastMessage` preview
    /// (functions/src/dm/blockedConversation.ts). Defaults false, so a
    /// document written before the marker existed reads as visible rather
    /// than vanishing.
    var blockedPair: Bool = false
}

/// The canonical HttpsError codes the DM slice branches on, decoupled from
/// the Firebase SDK so the mapping is testable with plain values (Android:
/// `DmErrorCode`).
enum DmErrorCode: Equatable, Sendable {
    case unauthenticated
    case permissionDenied
    case invalidArgument
    case failedPrecondition
    case notFound
    case other
}

/// Outcome of `dm-sendMessage`.
enum DmSendResult: Equatable, Sendable {
    case sent(conversationId: String, messageId: String)
    case failed(DmSendError)
}

/// A page of older messages from `dm-getMessages` (newest-first within the
/// page).
struct DmMessagesPage: Equatable, Sendable {
    let messages: [DmMessage]
    let nextBefore: String?
    let hasMore: Bool
}

/// Outcome of an older-page load. A ``loaded(_:)`` page carries the
/// backend's own `hasMore` (a genuine end-of-pagination signal), whereas
/// ``failed`` means the callable itself errored — a transient failure that
/// must NOT be conflated with "no more messages", so the caller can offer a
/// retry instead of permanently ending pagination.
enum DmOlderResult: Equatable, Sendable {
    case loaded(DmMessagesPage)
    case failed
}

/// Canonical, order-independent conversation id for a pair of users: the two
/// UIDs sorted lexicographically by UTF-16 code unit — the SAME ordering
/// JavaScript's default `Array.sort` (backend `dmPairId`) and Kotlin/Java
/// `String.compareTo` (Android `dmPairId`) apply — and joined with `__`, so
/// `dmPairId(a, b) == dmPairId(b, a)` and all three platforms derive the
/// byte-identical document id. Swift's own `String <` is Unicode-canonical
/// and would disagree on some inputs, hence the explicit code-unit compare.
func dmPairId(_ a: String, _ b: String) -> String {
    let ordered = utf16LexicographicallyOrdered(a, b) ? [a, b] : [b, a]
    return ordered.joined(separator: "__")
}

/// True when `a` sorts before-or-equal `b` by UTF-16 code units.
private func utf16LexicographicallyOrdered(_ a: String, _ b: String) -> Bool {
    var lhs = a.utf16.makeIterator()
    var rhs = b.utf16.makeIterator()
    while true {
        switch (lhs.next(), rhs.next()) {
        case (nil, _): return true
        case (_, nil): return false
        case (let l?, let r?):
            if l != r { return l < r }
        }
    }
}

/// Pure code → send-error mapping. Branch on the HttpsError code, never the
/// message (Android: `DmErrorMapper`).
enum DmErrorMapper {
    static func mapSend(_ code: DmErrorCode) -> DmSendError {
        switch code {
        case .unauthenticated: return .signedOut
        case .permissionDenied: return .notMember
        case .invalidArgument: return .invalid
        // Both NOT_FRIENDS and NOT_DELIVERABLE (blocked) arrive as
        // failed-precondition with no discriminator; collapse to a single
        // neutral message so a block is never revealed.
        case .failedPrecondition: return .cannotDeliver
        case .notFound, .other: return .generic
        }
    }
}

/// Pure projection of conversation/message docs into caller-oriented models
/// (Android: `DmMapper`).
enum DmMapper {
    /// The other participant's uid (the first member that isn't the caller).
    static func otherMember(_ members: [String], callerUid: String) -> String? {
        members.first { $0 != callerUid }
    }

    /// The caller's own unread count, clamped to a non-negative Int. Clamp in
    /// Int64 space before narrowing: a large backend value would otherwise
    /// overflow and wrap negative, hiding the badge.
    static func unread(for callerUid: String, in unread: [String: Int64]) -> Int {
        let raw = unread[callerUid] ?? 0
        return Int(min(max(raw, 0), Int64(Int32.max)))
    }

    /// Projects a raw ``DmConversationDoc`` into the caller's inbox row: the
    /// OTHER member's denormalized profile, the caller's own unread count,
    /// and the last-message preview.
    static func conversation(
        conversationId: String,
        doc: DmConversationDoc,
        callerUid: String
    ) -> DmConversation {
        let otherUid = otherMember(doc.members, callerUid: callerUid) ?? ""
        let otherProfile = doc.memberProfiles[otherUid] ?? DmUser(
            uid: otherUid, displayName: nil, avatarPath: nil
        )
        let preview = doc.lastMessageSenderUid.map { sender in
            DmMessagePreview(
                text: doc.lastMessageText ?? "",
                senderUid: sender,
                createdAtMillis: doc.lastMessageAtMillis
            )
        }
        return DmConversation(
            conversationId: conversationId,
            otherUser: DmUser(
                uid: otherUid,
                displayName: otherProfile.displayName,
                avatarPath: otherProfile.avatarPath
            ),
            lastMessage: preview,
            unreadCount: unread(for: callerUid, in: doc.unread),
            lastMessageAtMillis: doc.lastMessageAtMillis
        )
    }

    /// True when this inbox row must not be rendered at all because the pair
    /// is blocked — a hidden DM thread is hidden for BOTH parties. This is
    /// the SECOND of two independent signals the inbox uses, mirroring
    /// `dm.listConversations` on the server: the caller's `blockVisibility`
    /// hidden set is trigger-maintained (briefly behind a fresh block, and
    /// capped), and this marker — written by the same trigger onto the
    /// conversation document itself — covers both gaps.
    static func isHiddenByBlock(_ doc: DmConversationDoc) -> Bool {
        doc.blockedPair
    }

    /// Defensively re-sorts inbox rows newest-first. The server query already
    /// orders by `lastMessageAt`; this client-side pass just guards against
    /// ordering drift after the cached/merged mapping.
    static func sortConversations(_ conversations: [DmConversation]) -> [DmConversation] {
        conversations.enumerated().sorted { a, b in
            let lhs = a.element.lastMessageAtMillis ?? Int64.min
            let rhs = b.element.lastMessageAtMillis ?? Int64.min
            if lhs != rhs { return lhs > rhs }
            return a.offset < b.offset
        }.map(\.element)
    }
}

/// Pure message-thread helpers: merge of the live window with paged older
/// messages and the caller's optimistic bubbles (Android: `DmThread`).
enum DmThreadLogic {
    /// Whether a draft is within 1...``Dm/messageMaxLength`` after trimming.
    ///
    /// Measured in UTF-16 CODE UNITS, not extended grapheme clusters: the
    /// backend's schema (JS `string.length`) and Android (Kotlin
    /// `String.length`) both count UTF-16 units, so a grapheme count would
    /// let e.g. 2000 emoji pass client validation only to be rejected by
    /// `dm-sendMessage` as invalid-argument.
    static func isSendable(_ text: String) -> Bool {
        let length = text.trimmingCharacters(in: .whitespacesAndNewlines).utf16.count
        return length >= 1 && length <= Dm.messageMaxLength
    }

    /// Merges the live newest-window with accumulated older pages into a
    /// single chronological (oldest-first) list, de-duplicated by id. Later
    /// duplicates win, so a message that appears in both the live window and
    /// an older page keeps its live copy.
    static func merge(older: [DmMessage], live: [DmMessage]) -> [DmMessage] {
        var byId: [String: DmMessage] = [:]
        byId.reserveCapacity(older.count + live.count)
        for message in older { byId[message.id] = message }
        for message in live { byId[message.id] = message }
        return sortChronologically(Array(byId.values))
    }

    /// Merges the server-sourced messages with the caller's still-pending
    /// optimistic bubbles for display. A pending bubble whose id (its
    /// clientId) has ALREADY arrived in the server set is dropped: the
    /// delivered document — whose doc id equals that clientId — supersedes
    /// it, so an optimistic send and its snapshot render as exactly ONE
    /// message, never two.
    static func mergeWithPending(
        older: [DmMessage],
        live: [DmMessage],
        pending: [DmMessage]
    ) -> [DmMessage] {
        let real = merge(older: older, live: live)
        guard !pending.isEmpty else { return real }
        let realIds = Set(real.map(\.id))
        let stillPending = pending.filter { !realIds.contains($0.id) }
        guard !stillPending.isEmpty else { return real }
        return sortChronologically(real + stillPending)
    }

    /// The pagination cursor for the next older page: the earliest message's
    /// ISO createdAt.
    static func oldestCursor(_ messages: [DmMessage]) -> String? {
        messages.min { a, b in
            (a.createdAtMillis ?? Int64.max) < (b.createdAtMillis ?? Int64.max)
        }?.createdAtIso
    }

    /// Oldest-first, missing-timestamp-last, id as the tiebreak — the same
    /// total ordering Android applies (`compareBy(createdAtMillis, id)`).
    private static func sortChronologically(_ messages: [DmMessage]) -> [DmMessage] {
        messages.sorted { a, b in
            let lhs = a.createdAtMillis ?? Int64.max
            let rhs = b.createdAtMillis ?? Int64.max
            if lhs != rhs { return lhs < rhs }
            return a.id < b.id
        }
    }
}

/// Pure parsing of the `dm-*` callable response payloads (plain
/// dictionaries/arrays as the Functions SDK deserializes JSON). Missing or
/// blank required fields drop the row rather than crash. Callable responses
/// carry ISO-8601 timestamp strings; the live Firestore listeners (which
/// carry `Timestamp`s) are parsed in the Firebase repository.
enum DmResponseParser {
    /// Maps a `dm-sendMessage` success payload. Missing ids fail the send.
    static func parseSendSuccess(_ data: [String: Any]?) -> DmSendResult {
        guard let conversationId = data?["conversationId"] as? String, !conversationId.isEmpty,
            let messageId = data?["messageId"] as? String, !messageId.isEmpty
        else { return .failed(.generic) }
        return .sent(conversationId: conversationId, messageId: messageId)
    }

    /// Maps a `dm-getMessages` success payload into an older-page.
    static func parseMessagesPage(_ data: [String: Any]?) -> DmMessagesPage {
        let raw = data?["messages"] as? [Any] ?? []
        let nextBefore = (data?["nextBefore"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        return DmMessagesPage(
            messages: raw.compactMap(parseMessage(_:)),
            nextBefore: nextBefore,
            hasMore: data?["hasMore"] as? Bool ?? false
        )
    }

    /// Parses one message row from a callable payload (ISO createdAt).
    static func parseMessage(_ raw: Any) -> DmMessage? {
        guard let map = raw as? [String: Any],
            let id = map["id"] as? String, !id.isEmpty,
            let senderUid = map["senderUid"] as? String
        else { return nil }
        let iso = map["createdAt"] as? String
        return DmMessage(
            id: id,
            senderUid: senderUid,
            text: map["text"] as? String ?? "",
            createdAtMillis: iso.flatMap(isoToMillis(_:)),
            createdAtIso: iso,
            clientId: (map["clientId"] as? String).flatMap { $0.isEmpty ? nil : $0 },
            replyTo: parseReplyTo(map["replyTo"])
        )
    }

    /// Reads a stored/echoed `replyTo` map into ``DmReplyTo``, defensively
    /// coalescing missing or non-string fields (an ordinary message → nil).
    /// A snapshot with no usable messageId or senderUid is dropped rather
    /// than rendered as a half-quote.
    static func parseReplyTo(_ raw: Any?) -> DmReplyTo? {
        guard let map = raw as? [String: Any],
            let messageId = map["messageId"] as? String, !messageId.isEmpty,
            let senderUid = map["senderUid"] as? String, !senderUid.isEmpty
        else { return nil }
        return DmReplyTo(
            messageId: messageId,
            senderUid: senderUid,
            senderDisplayName: map["senderDisplayName"] as? String,
            textPreview: map["textPreview"] as? String ?? ""
        )
    }
}

/// Best-effort ISO-8601 → epoch-millis for callable message rows (used only
/// for chronological ordering; a parse failure just sorts the message last).
func isoToMillis(_ iso: String) -> Int64? {
    // ISO8601DateFormatter parses whole-second timestamps; the backend's
    // `toISOString()` carries fractional seconds, so try that variant first.
    if let date = DmIsoFormat.withFractional.date(from: iso)
        ?? DmIsoFormat.wholeSeconds.date(from: iso) {
        return Int64((date.timeIntervalSince1970 * 1000).rounded())
    }
    return nil
}

/// Epoch-millis → ISO-8601 (UTC, `Z`, millisecond precision) — the cursor
/// format `dm-getMessages` expects (JS `Date.toISOString()`).
func millisToIso(_ millis: Int64) -> String {
    DmIsoFormat.withFractional.string(
        from: Date(timeIntervalSince1970: Double(millis) / 1000)
    )
}

/// Shared ISO-8601 formatters. `ISO8601DateFormatter` is documented
/// thread-safe (unlike `DateFormatter`), so sharing the two configured
/// instances is sound — `nonisolated(unsafe)` records exactly that judgment
/// for the Swift 6 checker, which cannot see the documentation guarantee.
private enum DmIsoFormat {
    nonisolated(unsafe) static let withFractional: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter
    }()

    nonisolated(unsafe) static let wholeSeconds: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        return formatter
    }()
}
