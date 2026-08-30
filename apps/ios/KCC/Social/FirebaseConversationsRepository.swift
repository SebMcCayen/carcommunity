import FirebaseAuth
import FirebaseCore
import FirebaseFirestore
import FirebaseFunctions
import Foundation

/// ``ConversationsRepository`` backed by member-readable Firestore listeners
/// plus the member-gated `dm-*` callables (europe-west1) — the iOS port of
/// Android's `FirebaseDmRepository.kt`.
///
/// The raw Firestore `DocumentSnapshot` → pure-model translation (with
/// `Timestamp`s extracted to epoch millis) lives here so ``DmMapper`` /
/// ``DmThreadLogic`` stay testable off-device. HttpsError codes (never
/// messages) are translated to the pure ``DmErrorCode`` and mapped by
/// ``DmErrorMapper``.
///
/// The inbox query is bounded newest-first (`lastMessageAt` descending,
/// capped at ``Dm/conversationsQueryLimit``), relying on the existing
/// `members` array-contains + `lastMessageAt` descending composite index
/// (firebase/firestore.indexes.json).
///
/// BLOCKING: a blocked pair's thread disappears for BOTH parties.
///  - The THREAD listener needs nothing here: firebase/firestore.rules denies
///    the messages subcollection outright for a blocked pair, and the
///    PERMISSION_DENIED branch below already renders that as an empty thread.
///  - The INBOX row carrying the `blockedPair` marker is dropped at the doc
///    mapping (see ``DmMapper/isHiddenByBlock(_:)``); the caller's
///    `blockVisibility` hidden set — the second, independent signal — is
///    applied by ``ConversationsCoordinator``.
///
/// NOTE Android additionally overlays each counterparty's CURRENT
/// `users/{uid}` profile onto the denormalized `memberProfiles` copy
/// (LiveProfiles hydration). iOS has no live-profile subsystem yet; rows
/// render the denormalized copy, and the hydration overlay ships with the
/// chat-hub slice.
final class FirebaseConversationsRepository: ConversationsRepository, @unchecked Sendable {
    private let firestore: Firestore
    private let functions: Functions

    private init(firestore: Firestore, functions: Functions) {
        self.firestore = firestore
        self.functions = functions
    }

    func observeConversations(uid: String) -> AsyncStream<DmConversationsState> {
        let query =
            firestore
            .collection(Self.conversations)
            .whereField(Self.members, arrayContains: uid)
            .order(by: Self.lastMessageAt, descending: true)
            .limit(to: Dm.conversationsQueryLimit)
        return AsyncStream { continuation in
            let registration = query.addSnapshotListener { snapshot, error in
                if let error {
                    // Firestore can deliver cached data ALONGSIDE an error;
                    // keep the usable inbox instead of flickering to Error on
                    // a transient failure (mirrors observeThread).
                    if let snapshot {
                        let cached = snapshot.documents.compactMap {
                            Self.conversation(from: $0, callerUid: uid)
                        }
                        continuation.yield(.loaded(DmMapper.sortConversations(cached)))
                        return
                    }
                    // No snapshot to fall back on — surface a retryable
                    // error, tagged with the bare Firestore status name (e.g.
                    // FAILED_PRECONDITION for a missing composite index) for
                    // diagnostics. Never the message, which embeds the query.
                    continuation.yield(
                        .error(code: FirebaseEventsRepository.firestoreStatusName(error))
                    )
                    return
                }
                let rows = (snapshot?.documents ?? []).compactMap {
                    Self.conversation(from: $0, callerUid: uid)
                }
                continuation.yield(.loaded(DmMapper.sortConversations(rows)))
            }
            let box = DmListenerBox(registration: registration)
            continuation.onTermination = { _ in
                box.registration.remove()
            }
        }
    }

    func observeThread(conversationId: String) -> AsyncStream<DmThreadState> {
        let query =
            firestore
            .collection(Self.conversations)
            .document(conversationId)
            .collection(Self.messages)
            .order(by: Self.createdAt, descending: true)
            .limit(to: Dm.messagesPageSize)
        return AsyncStream { continuation in
            // Tracks whether any state has reached the consumer yet: a
            // transient error must hold the LAST state, but before a first
            // emission there is no last state to hold, and returning would
            // leave the thread stuck on loading forever.
            let emitted = DmEmittedFlag()
            let registration = query.addSnapshotListener { snapshot, error in
                if let error {
                    // Cached data alongside an error: prefer it over
                    // collapsing the thread to empty.
                    if let snapshot {
                        let cached = snapshot.documents
                            .compactMap(Self.message(from:))
                            .reversed()
                        _ = emitted.getAndSet()
                        continuation.yield(.loaded(Array(cached)))
                        return
                    }
                    // A self-derived pairId whose conversation doc doesn't
                    // exist yet — or a BLOCKED pair — denies the messages
                    // listen with PERMISSION_DENIED. Surface it as an empty
                    // thread (the caller can send the first message; a
                    // blocked pair's send is then refused neutrally by the
                    // callable).
                    let nsError = error as NSError
                    if nsError.domain == FirestoreErrorDomain,
                        nsError.code == FirestoreErrorCode.permissionDenied.rawValue {
                        _ = emitted.getAndSet()
                        continuation.yield(.loaded([]))
                        return
                    }
                    // Any OTHER error (UNAVAILABLE, network, …) is transient:
                    // keep the last emitted state instead of misrendering it
                    // as "no messages" — the SDK retries and will deliver a
                    // fresh snapshot. But when NOTHING has been emitted yet,
                    // holding would strand the consumer on loading with no
                    // way out; degrade to the empty thread (the same reading
                    // as the not-yet-created conversation), which the next
                    // successful snapshot replaces.
                    if !emitted.getAndSet() {
                        continuation.yield(.loaded([]))
                    }
                    return
                }
                let messages = (snapshot?.documents ?? [])
                    .compactMap(Self.message(from:))
                    .reversed()
                _ = emitted.getAndSet()
                continuation.yield(.loaded(Array(messages)))
            }
            let box = DmListenerBox(registration: registration)
            continuation.onTermination = { _ in
                box.registration.remove()
            }
        }
    }

    func sendMessage(toUid: String, text: String, clientId: String?) async -> DmSendResult {
        // Only include the optional key when present, so the payload stays
        // byte-identical to the legacy shape for a nil (the strict backend
        // schema rejects a literal null on an optional field).
        var payload: [String: Any] = [
            "toUid": toUid,
            "text": text.trimmingCharacters(in: .whitespacesAndNewlines),
        ]
        if let clientId { payload["clientId"] = clientId }
        do {
            let result = try await functions.httpsCallable(Self.sendMessage).call(payload)
            return DmResponseParser.parseSendSuccess(result.data as? [String: Any])
        } catch {
            return .failed(DmErrorMapper.mapSend(Self.dmErrorCode(from: error)))
        }
    }

    func loadOlder(conversationId: String, before: String) async -> DmOlderResult {
        let payload: [String: Any] = ["conversationId": conversationId, "before": before]
        do {
            let result = try await functions.httpsCallable(Self.getMessages).call(payload)
            return .loaded(DmResponseParser.parseMessagesPage(result.data as? [String: Any]))
        } catch {
            // A failed older-page is a TRANSIENT error, not end-of-pagination:
            // report it as such so the coordinator keeps the "load older"
            // affordance for a retry instead of permanently ending the thread.
            return .failed
        }
    }

    func markRead(conversationId: String) async {
        // Best-effort: a not-found (never-created conversation) or transient
        // failure is swallowed — marking read is idempotent bookkeeping.
        _ = try? await functions.httpsCallable(Self.markRead)
            .call(["conversationId": conversationId])
    }

    // MARK: - Mapping

    /// Reads a stored conversation doc into the caller-oriented inbox row. A
    /// row hidden by the `blockedPair` marker is dropped HERE so it never
    /// reaches the UI (Android: `toConversation`).
    static func conversation(
        from document: DocumentSnapshot,
        callerUid: String
    ) -> DmConversation? {
        guard document.exists else { return nil }
        // Blank member ids are dropped, and a malformed document — one that
        // doesn't list the caller plus a non-empty counterparty — yields no
        // row at all: an inbox row with an empty counterparty uid would open
        // a dead thread. Only dm.sendMessage writes these docs (always
        // exactly two uids), so this is a guard against shape drift, and
        // dropping is the safe side (mirrors the rules' dmWellFormed
        // fail-closed reasoning).
        let members = (document.get(Self.members) as? [Any] ?? [])
            .compactMap { $0 as? String }
            .filter { !$0.isEmpty }
        guard members.contains(callerUid),
            members.contains(where: { $0 != callerUid })
        else { return nil }

        var memberProfiles: [String: DmUser] = [:]
        for (key, value) in document.get("memberProfiles") as? [String: Any] ?? [:] {
            guard let map = value as? [String: Any] else { continue }
            memberProfiles[key] = DmUser(
                uid: key,
                displayName: map["displayName"] as? String,
                avatarPath: map["avatarPath"] as? String
            )
        }

        var unread: [String: Int64] = [:]
        for (key, value) in document.get("unread") as? [String: Any] ?? [:] {
            unread[key] = (value as? NSNumber)?.int64Value ?? 0
        }

        let lastMessage = document.get("lastMessage") as? [String: Any]
        let doc = DmConversationDoc(
            members: members,
            memberProfiles: memberProfiles,
            lastMessageText: lastMessage?["text"] as? String,
            lastMessageSenderUid: lastMessage?["senderUid"] as? String,
            lastMessageAtMillis: (document.get(Self.lastMessageAt) as? Timestamp)
                .map { Int64(($0.dateValue().timeIntervalSince1970 * 1000).rounded()) },
            unread: unread,
            blockedPair: document.get("blockedPair") as? Bool ?? false
        )
        if DmMapper.isHiddenByBlock(doc) { return nil }
        return DmMapper.conversation(
            conversationId: document.documentID,
            doc: doc,
            callerUid: callerUid
        )
    }

    /// Reads a stored message doc into the pure model (Timestamp → millis +
    /// ISO; Android: `toMessage`).
    static func message(from document: DocumentSnapshot) -> DmMessage? {
        guard document.exists,
            let senderUid = document.get("senderUid") as? String
        else { return nil }
        let millis = (document.get(Self.createdAt) as? Timestamp)
            .map { Int64(($0.dateValue().timeIntervalSince1970 * 1000).rounded()) }
        return DmMessage(
            id: document.documentID,
            senderUid: senderUid,
            text: document.get("text") as? String ?? "",
            createdAtMillis: millis,
            createdAtIso: millis.map(millisToIso(_:)),
            // Echoed idempotency key: lets the live snapshot reconcile
            // against the sender's optimistic bubble (doc id == clientId).
            clientId: document.get("clientId") as? String,
            replyTo: DmResponseParser.parseReplyTo(document.get("replyTo"))
        )
    }

    /// Translates a raw callable failure into the pure, testable error code.
    static func dmErrorCode(from error: Error) -> DmErrorCode {
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

    // MARK: - Factory

    private static let conversations = "conversations"
    private static let messages = "messages"
    private static let members = "members"
    private static let createdAt = "createdAt"
    private static let lastMessageAt = "lastMessageAt"
    private static let sendMessage = "dm-sendMessage"
    private static let getMessages = "dm-getMessages"
    private static let markRead = "dm-markRead"

    private static let cachedLock = NSLock()
    nonisolated(unsafe) private static var cached: FirebaseConversationsRepository?

    /// Returns the process-wide repository when Firebase is configured for
    /// this build, or nil when GoogleService-Info.plist is absent. Honors the
    /// `FIREBASE_FIRESTORE_EMULATOR_HOST` / `FIREBASE_FUNCTIONS_EMULATOR_HOST`
    /// seams like the other repositories.
    static func createIfAvailable() -> ConversationsRepository? {
        guard FirebaseApp.app() != nil else { return nil }
        cachedLock.lock()
        defer { cachedLock.unlock() }
        if let cached { return cached }
        let firestore = Firestore.firestore()
        if let emulator = FirebaseEmulatorHost.parse(
            ProcessInfo.processInfo.environment["FIREBASE_FIRESTORE_EMULATOR_HOST"]
        ),
            firestore.settings.host != "\(emulator.host):\(emulator.port)" {
            firestore.useEmulator(withHost: emulator.host, port: emulator.port)
        }
        let functions = Functions.functions(region: KccFunctionsClient.region)
        if let emulator = FirebaseEmulatorHost.parse(
            ProcessInfo.processInfo.environment["FIREBASE_FUNCTIONS_EMULATOR_HOST"]
        ) {
            functions.useEmulator(withHost: emulator.host, port: emulator.port)
        }
        let repository = FirebaseConversationsRepository(
            firestore: firestore,
            functions: functions
        )
        cached = repository
        return repository
    }
}

/// ``BlockVisibilityRepository`` backed by a single owner-readable document
/// listener on `blockVisibility/{uid}` — the iOS port of Android's
/// `FirebaseBlockVisibilityRepository`. The signed-in uid is resolved from
/// FirebaseAuth at subscribe time, so the filter needs no uid threading.
final class FirebaseBlockVisibilityRepository: BlockVisibilityRepository, @unchecked Sendable {
    private let firestore: Firestore

    private init(firestore: Firestore) {
        self.firestore = firestore
    }

    func observeHiddenUids() -> AsyncStream<Set<String>> {
        guard let uid = Auth.auth().currentUser?.uid else {
            // Signed out: nothing to hide, and nothing to listen to. Emit
            // once so downstream combinations still produce a value instead
            // of stalling on a stream that never emits — then finish, so the
            // consumer's task doesn't idle on a stream that can never
            // produce another value.
            return AsyncStream { continuation in
                continuation.yield([])
                continuation.finish()
            }
        }
        let document = firestore.collection(Self.blockVisibility).document(uid)
        return AsyncStream { continuation in
            // Every consumer combines this stream with the inbox stream, so a
            // stream that never emits would leave the screen stuck on Loading
            // forever. A user with no blocks has NO blockVisibility document,
            // but Firestore still delivers a first (non-existent) snapshot,
            // so the happy paths all emit; the error paths must not go
            // silent either.
            let emitted = DmEmittedFlag()
            let registration = document.addSnapshotListener { snapshot, error in
                if error != nil && snapshot == nil {
                    // No usable snapshot. Once a set has been emitted, hold
                    // it: flipping to "hide nothing" on a transient failure
                    // would briefly render a blocked party's rows, and the
                    // SDK retries anyway. But if nothing has been emitted
                    // yet, degrade to the empty set — the screen renders
                    // unfiltered while the SERVER-side filters keep working.
                    if !emitted.getAndSet() {
                        continuation.yield([])
                    }
                    return
                }
                _ = emitted.getAndSet()
                let raw = snapshot?.get(Self.hiddenUids) as? [Any] ?? []
                continuation.yield(
                    Set(raw.compactMap { $0 as? String }.filter { !$0.isEmpty })
                )
            }
            let box = DmListenerBox(registration: registration)
            continuation.onTermination = { _ in
                box.registration.remove()
            }
        }
    }

    // MARK: - Factory

    private static let blockVisibility = "blockVisibility"
    private static let hiddenUids = "hiddenUids"

    private static let cachedLock = NSLock()
    nonisolated(unsafe) private static var cached: FirebaseBlockVisibilityRepository?

    /// Returns the live repository, or ``EmptyBlockVisibilityRepository``
    /// when Firebase is not configured. Never nil: a consumer always has a
    /// filter to combine with, and "hide nothing" is the safe config-less
    /// default (a config-less build has no rows to filter either).
    static func createOrEmpty() -> BlockVisibilityRepository {
        guard FirebaseApp.app() != nil else { return EmptyBlockVisibilityRepository() }
        cachedLock.lock()
        defer { cachedLock.unlock() }
        if let cached { return cached }
        let firestore = Firestore.firestore()
        if let emulator = FirebaseEmulatorHost.parse(
            ProcessInfo.processInfo.environment["FIREBASE_FIRESTORE_EMULATOR_HOST"]
        ),
            firestore.settings.host != "\(emulator.host):\(emulator.port)" {
            firestore.useEmulator(withHost: emulator.host, port: emulator.port)
        }
        let repository = FirebaseBlockVisibilityRepository(firestore: firestore)
        cached = repository
        return repository
    }
}

/// The config-less "hide nothing" fallback (Android:
/// `BlockVisibilityRepository.EMPTY`).
final class EmptyBlockVisibilityRepository: BlockVisibilityRepository, Sendable {
    func observeHiddenUids() -> AsyncStream<Set<String>> {
        // One-shot: yield the empty set and finish, so no task lingers on a
        // stream that will never produce another value.
        AsyncStream { continuation in
            continuation.yield([])
            continuation.finish()
        }
    }
}

/// `ListenerRegistration` is not Sendable, but the stream's `onTermination`
/// closure must be — all it does is remove the listener, which Firestore
/// documents as thread-safe, so the wrapper is sound (mirrors the events
/// repository's `ListenerBox`).
private struct DmListenerBox: @unchecked Sendable {
    let registration: ListenerRegistration
}

/// Tiny lock-guarded once-flag for the block-visibility listener's
/// "has anything been emitted yet" decision (the closure is invoked on
/// Firestore's queue, so a plain var would race).
private final class DmEmittedFlag: @unchecked Sendable {
    private let lock = NSLock()
    private var value = false

    /// Returns the previous value and sets the flag.
    func getAndSet() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        let previous = value
        value = true
        return previous
    }
}
