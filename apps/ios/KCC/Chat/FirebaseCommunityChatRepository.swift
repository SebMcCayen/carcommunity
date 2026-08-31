import FirebaseAuth
import FirebaseCore
import FirebaseFirestore
import FirebaseFunctions
import Foundation

/// ``CommunityChatRepository`` backed by the member-readable
/// `communityChat/global/messages` listener plus the member-gated
/// `communityChat-*` / `chatchannels-*` callables (europe-west1) — the iOS port
/// of Android's `FirebaseCommunityChatRepository`.
///
/// The newest window is a bounded (createdAt DESCENDING, limit
/// ``channelMessagesPageSize``) live listener, block-filtered against the
/// caller's `blockVisibility/{uid}.hiddenUids` mirror; older pages come from
/// `communityChat-list` (server-side block-filtered). The client never writes the
/// message tree — `communityChat-post` stamps the authorship and denormalized
/// sender profile.
///
/// Construction is guarded (``createIfAvailable()`` returns nil without Firebase
/// config), mirroring ``FirebaseEventsRepository`` and Android's
/// `createIfAvailable`.
final class FirebaseCommunityChatRepository: CommunityChatRepository, @unchecked Sendable {
    private let firestore: Firestore
    private let functions: Functions

    private init(firestore: Firestore, functions: Functions) {
        self.firestore = firestore
        self.functions = functions
    }

    func observeMessages() -> AsyncStream<ChannelMessagesState> {
        let query = firestore
            .collection(Self.communityCollection)
            .document(Self.channelId)
            .collection(Self.messagesCollection)
            .order(by: Self.createdAtField, descending: true)
            .limit(to: channelMessagesPageSize)
        let blockDoc = currentUserId().map {
            firestore.collection(ChatFirestore.blockVisibilityCollection).document($0)
        }
        return ChannelMessagesListener.stream(messagesQuery: query, blockVisibilityDocument: blockDoc)
    }

    func loadOlder(before: String) async -> ChannelOlderResult {
        do {
            let data = try await functions.httpsCallable(Self.listCallable).call(["before": before]).data
            return .loaded(ChannelResponseParser.parseMessagesPage(data))
        } catch {
            // Transient callable failure — NOT end-of-pagination; the caller
            // offers a retry.
            return .failed
        }
    }

    func post(
        text: String,
        mentionedUids: [String],
        clientId: String?,
        replyToMessageId: String?
    ) async -> ChannelSendResult {
        // `mentionedUids` is optional in the contract, so omit it entirely when
        // empty; dedup + cap here as well as in any composer so no client state
        // reaches the server's one hard reject (> MAX_MESSAGE_MENTIONS).
        var payload: [String: Any] = ["text": text.trimmingCharacters(in: .whitespacesAndNewlines)]
        var seen = Set<String>()
        let uids = mentionedUids.filter { seen.insert($0).inserted }.prefix(channelMaxMessageMentions)
        if !uids.isEmpty { payload["mentionedUids"] = Array(uids) }
        if let clientId { payload["clientId"] = clientId }
        if let replyToMessageId { payload["replyToMessageId"] = replyToMessageId }
        do {
            let data = try await functions.httpsCallable(Self.postCallable).call(payload).data
            return ChannelResponseParser.parsePostSuccess(data)
        } catch {
            return .failed(ChannelErrorMapper.mapSend(ChatFirestore.channelErrorCode(from: error)))
        }
    }

    func markRead() async {
        // Best-effort idempotent bookkeeping; a transient failure is swallowed.
        _ = try? await functions.httpsCallable(Self.markReadCallable).call([String: Any]())
    }

    func report(messageId: String, reason: ChatReportReason) async -> ChannelReportResult {
        // `channel: "community"` fixes the scope ("global") server-side; convoyId
        // is deliberately omitted (the backend rejects it for the community
        // channel).
        let payload: [String: Any] = [
            "channel": "community",
            "messageId": messageId,
            "reason": reason.wire,
        ]
        do {
            _ = try await functions.httpsCallable(Self.reportCallable).call(payload)
            return .reported
        } catch {
            // Every failure collapses to one neutral outcome — the reporter is
            // never told which.
            return .failed
        }
    }

    func currentUserId() -> String? {
        Auth.auth().currentUser?.uid
    }

    // MARK: - Constants

    private static let communityCollection = "communityChat"
    private static let channelId = "global"
    private static let messagesCollection = "messages"
    private static let createdAtField = "createdAt"
    private static let postCallable = "communityChat-post"
    private static let listCallable = "communityChat-list"
    private static let markReadCallable = "communityChat-markRead"
    /// Shared community+convoy report callable; the payload's `channel` field
    /// selects which. NOT a `communityChat-*` export.
    private static let reportCallable = "chatchannels-reportMessage"

    // MARK: - Factory

    private static let cachedLock = NSLock()
    nonisolated(unsafe) private static var cached: FirebaseCommunityChatRepository?

    /// Returns the process-wide repository when Firebase is configured, or nil
    /// when GoogleService-Info.plist is absent (CI / config-less builds). Points
    /// the SDKs at the emulator when `FIREBASE_FIRESTORE_EMULATOR_HOST` /
    /// `FIREBASE_FUNCTIONS_EMULATOR_HOST` are set — the same seams
    /// ``FirebaseEventsRepository`` / ``KccFunctionsClient`` use.
    static func createIfAvailable() -> CommunityChatRepository? {
        guard FirebaseApp.app() != nil else { return nil }
        cachedLock.lock()
        defer { cachedLock.unlock() }
        if let cached { return cached }
        let firestore = Firestore.firestore()
        if let emulator = FirebaseEmulatorHost.parse(
            ProcessInfo.processInfo.environment["FIREBASE_FIRESTORE_EMULATOR_HOST"]
        ) {
            firestore.useEmulator(withHost: emulator.host, port: emulator.port)
        }
        let functions = Functions.functions(region: ChatFirestore.functionsRegion)
        if let emulator = FirebaseEmulatorHost.parse(
            ProcessInfo.processInfo.environment["FIREBASE_FUNCTIONS_EMULATOR_HOST"]
        ) {
            functions.useEmulator(withHost: emulator.host, port: emulator.port)
        }
        let repository = FirebaseCommunityChatRepository(firestore: firestore, functions: functions)
        cached = repository
        return repository
    }
}
