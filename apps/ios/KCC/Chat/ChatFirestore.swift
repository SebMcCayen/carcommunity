import FirebaseFirestore
import FirebaseFunctions
import Foundation

/// Firebase-side plumbing shared by the two channel repositories: message
/// decoding from a live `DocumentSnapshot`, the callable error → ``ChannelErrorCode``
/// mapping, the owner-only `blockVisibility/{uid}.hiddenUids` listener, and the
/// combined messages+blocking live stream. Kept in one place so the community
/// and convoy repos stay thin and identical where Android's do (they share
/// `ChannelCallables.kt`).

/// `ListenerRegistration` is not Sendable, but the stream's `onTermination`
/// closure must be — all it does is remove listeners, which Firestore documents
/// as thread-safe. Same wrapper as ``FirebaseEventsRepository``'s `ListenerBox`.
struct ChatListenerBox: @unchecked Sendable {
    let registrations: [ListenerRegistration]
    func removeAll() { registrations.forEach { $0.remove() } }
}

enum ChatFirestore {
    static let functionsRegion = "europe-west1"
    static let hiddenUidsField = "hiddenUids"
    static let blockVisibilityCollection = "blockVisibility"

    /// Decodes one live-listener channel message document. Tolerant, mirroring
    /// Android's `DocumentSnapshot.toChannelMessage()`: a missing/blank `id` or
    /// `senderUid` drops the doc; `text` degrades to `""`; the profile fields
    /// stay nil; the `createdAt` `Timestamp` becomes a `Date` (and its ISO
    /// spelling, so older-page paging still has a cursor). Reads `replyTo` and
    /// `mentionedUids` with the same pure parsers as the callable path.
    static func message(from document: DocumentSnapshot) -> ChannelMessage? {
        guard document.exists else { return nil }
        guard let senderUid = document.get("senderUid") as? String, !senderUid.isEmpty else { return nil }
        let id = document.documentID
        guard !id.isEmpty else { return nil }
        let date = (document.get("createdAt") as? Timestamp)?.dateValue()
        return ChannelMessage(
            id: id,
            senderUid: senderUid,
            text: document.get("text") as? String ?? "",
            senderDisplayName: document.get("senderDisplayName") as? String,
            senderAvatarPath: document.get("senderAvatarPath") as? String,
            createdAt: date,
            createdAtIso: date.map(ChannelTime.isoString),
            mentionedUids: ChannelResponseParser.parseMentionedUids(document.get("mentionedUids")),
            clientId: (document.get("clientId") as? String).flatMap { $0.isEmpty ? nil : $0 },
            replyTo: ChannelResponseParser.parseReplyTo(document.get("replyTo"))
        )
    }

    /// Maps a callable SDK failure onto the pure ``ChannelErrorCode`` — the PII-safe
    /// seam: only the code crosses, never the SDK message (which can embed the
    /// request payload, uids, and project id). Android:
    /// `Throwable.toChannelErrorCode()`.
    static func channelErrorCode(from error: Error) -> ChannelErrorCode {
        let nsError = error as NSError
        guard nsError.domain == FunctionsErrorDomain,
            let code = FunctionsErrorCode(rawValue: nsError.code)
        else { return .other }
        switch code {
        case .unauthenticated: return .unauthenticated
        case .permissionDenied: return .permissionDenied
        case .invalidArgument: return .invalidArgument
        case .failedPrecondition: return .failedPrecondition
        case .notFound: return .notFound
        default: return .other
        }
    }

    /// Whether a Firestore listener error is a terminal `PERMISSION_DENIED`
    /// (membership lost / blocked / removed) — which clears the channel to empty
    /// even over a cached snapshot, per Android's listener handling.
    static func isPermissionDenied(_ error: Error) -> Bool {
        let nsError = error as NSError
        return nsError.domain == FirestoreErrorDomain
            && nsError.code == FirestoreErrorCode.Code.permissionDenied.rawValue
    }
}

/// Combines a bounded newest-first messages listener with the caller's
/// `blockVisibility/{uid}.hiddenUids` listener into ONE
/// `AsyncStream<ChannelMessagesState>`, emitting the block-filtered,
/// chronological window whenever either updates — the iOS shape of Android's
/// `combine(observeRawMessages(), blockVisibility.observeHiddenUids())`.
///
/// Listener error handling mirrors Android exactly:
/// - `PERMISSION_DENIED`: hard-clear to `.loaded([])` and emit it immediately,
///   even over a cached snapshot or on a first load with none yet — denied
///   history is never shown, and the coordinator's initial loading ends with
///   an empty (not a stuck) state.
/// - transient error WITH a cached snapshot: emit the cached messages.
/// - transient error with NO cached data: emit nothing (stay in the
///   coordinator's initial loading) so offline never misrenders as "no
///   messages".
final class ChannelMessagesListener: @unchecked Sendable {
    private let lock = NSLock()
    private let continuation: AsyncStream<ChannelMessagesState>.Continuation
    /// The latest RAW newest-first window, or nil until the first usable
    /// snapshot (so the stream stays silent until there is something real).
    private var rawNewestFirst: [ChannelMessage]?
    private var hidden: Set<String> = []

    private init(continuation: AsyncStream<ChannelMessagesState>.Continuation) {
        self.continuation = continuation
    }

    /// Builds the combined stream. `messagesQuery` is the bounded newest-first
    /// query; `blockVisibilityDocument` is `blockVisibility/{uid}` (nil when
    /// signed out — no filtering then).
    static func stream(
        messagesQuery: Query,
        blockVisibilityDocument: DocumentReference?
    ) -> AsyncStream<ChannelMessagesState> {
        AsyncStream { continuation in
            let combiner = ChannelMessagesListener(continuation: continuation)
            let messages = messagesQuery.addSnapshotListener { snapshot, error in
                combiner.onMessages(snapshot: snapshot, error: error)
            }
            var registrations = [messages]
            if let blockVisibilityDocument {
                let blocks = blockVisibilityDocument.addSnapshotListener { snapshot, error in
                    combiner.onHidden(snapshot: snapshot, error: error)
                }
                registrations.append(blocks)
            }
            let box = ChatListenerBox(registrations: registrations)
            continuation.onTermination = { _ in box.removeAll() }
        }
    }

    private func onMessages(snapshot: QuerySnapshot?, error: Error?) {
        lock.lock()
        defer { lock.unlock() }
        if let error {
            if ChatFirestore.isPermissionDenied(error) {
                rawNewestFirst = []  // hard clear, terminal
                emitLocked()
                return
            }
            // Transient: use the cached snapshot if present, else stay silent.
            guard let snapshot else { return }
            rawNewestFirst = snapshot.documents.compactMap(ChatFirestore.message(from:))
            emitLocked()
            return
        }
        rawNewestFirst = (snapshot?.documents ?? []).compactMap(ChatFirestore.message(from:))
        emitLocked()
    }

    private func onHidden(snapshot: DocumentSnapshot?, error: Error?) {
        lock.lock()
        defer { lock.unlock() }
        if error != nil && snapshot == nil {
            // Keep the last-known hidden set rather than momentarily clearing it.
            return
        }
        let uids = (snapshot?.get(ChatFirestore.hiddenUidsField) as? [Any])?
            .compactMap { $0 as? String } ?? []
        hidden = Set(uids)
        // Re-filter the window we already hold against the fresh set.
        if rawNewestFirst != nil { emitLocked() }
    }

    /// Emits the filtered, oldest-first window. Caller holds `lock`.
    private func emitLocked() {
        guard let rawNewestFirst else { return }
        let filtered = ChatBlockVisibility.filterHiddenAuthors(rawNewestFirst, hidden: hidden)
        continuation.yield(.loaded(Array(filtered.reversed())))
    }
}
