import FirebaseAuth
import FirebaseCore
import FirebaseFirestore
import FirebaseFunctions
import Foundation

/// ``ConvoyChatRepository`` backed by the member-readable
/// `convoyChats/{convoyId}/messages` listener plus the member-gated `convoy-list`
/// / `convoyChat-*` / `chatchannels-*` callables (europe-west1) — the iOS port of
/// Android's `FirebaseConvoyChatRepository`.
///
/// The convoy list comes from `convoy-list`, projected client-side to
/// accepted-member convoys (``ConvoyChatMapper``). Each convoy's newest window is
/// a bounded live listener block-filtered against the caller's mutual-hidden set;
/// older pages come from `convoyChat-list`. The client never writes the message
/// tree — `convoyChat-post` stamps authorship and the denormalized profile.
///
/// Construction is guarded (``createIfAvailable()`` returns nil without config).
final class FirebaseConvoyChatRepository: ConvoyChatRepository, @unchecked Sendable {
    private let firestore: Firestore
    private let functions: Functions

    private init(firestore: Firestore, functions: Functions) {
        self.firestore = firestore
        self.functions = functions
    }

    func listConvoys() async -> ConvoyListState {
        do {
            let data = try await functions.httpsCallable(Self.convoyListCallable).call([String: Any]()).data
            return .loaded(ConvoyChatMapper.chatEligibleConvoys(data))
        } catch {
            // Transient callable failure — retryable.
            return .error
        }
    }

    func observeMessages(convoyId: String) -> AsyncStream<ChannelMessagesState> {
        let query = firestore
            .collection(Self.convoyChatsCollection)
            .document(convoyId)
            .collection(Self.messagesCollection)
            .order(by: Self.createdAtField, descending: true)
            .limit(to: channelMessagesPageSize)
        let blockDoc = currentUserId().map {
            firestore.collection(ChatFirestore.blockVisibilityCollection).document($0)
        }
        return ChannelMessagesListener.stream(messagesQuery: query, blockVisibilityDocument: blockDoc)
    }

    func loadOlder(convoyId: String, before: String) async -> ChannelOlderResult {
        do {
            let data = try await functions.httpsCallable(Self.listCallable)
                .call(["convoyId": convoyId, "before": before]).data
            return .loaded(ChannelResponseParser.parseMessagesPage(data))
        } catch {
            return .failed
        }
    }

    func post(
        convoyId: String,
        text: String,
        clientId: String?,
        replyToMessageId: String?
    ) async -> ChannelSendResult {
        // Convoy chat accepts no @mentions (the field exists only so both
        // channels share one message shape).
        var payload: [String: Any] = [
            "convoyId": convoyId,
            "text": text.trimmingCharacters(in: .whitespacesAndNewlines),
        ]
        if let clientId { payload["clientId"] = clientId }
        if let replyToMessageId { payload["replyToMessageId"] = replyToMessageId }
        do {
            let data = try await functions.httpsCallable(Self.postCallable).call(payload).data
            return ChannelResponseParser.parsePostSuccess(data)
        } catch {
            return .failed(ChannelErrorMapper.mapSend(ChatFirestore.channelErrorCode(from: error)))
        }
    }

    func markRead(convoyId: String) async {
        _ = try? await functions.httpsCallable(Self.markReadCallable).call(["convoyId": convoyId])
    }

    func report(convoyId: String, messageId: String, reason: ChatReportReason) async -> ChannelReportResult {
        // `channel: "convoy"` REQUIRES convoyId (the backend rejects it missing);
        // the pair mirrors the convoy read gate.
        let payload: [String: Any] = [
            "channel": "convoy",
            "convoyId": convoyId,
            "messageId": messageId,
            "reason": reason.wire,
        ]
        do {
            _ = try await functions.httpsCallable(Self.reportCallable).call(payload)
            return .reported
        } catch {
            return .failed
        }
    }

    func currentUserId() -> String? {
        Auth.auth().currentUser?.uid
    }

    // MARK: - Constants

    private static let convoyChatsCollection = "convoyChats"
    private static let messagesCollection = "messages"
    private static let createdAtField = "createdAt"
    private static let convoyListCallable = "convoy-list"
    private static let postCallable = "convoyChat-post"
    private static let listCallable = "convoyChat-list"
    private static let markReadCallable = "convoyChat-markRead"
    private static let reportCallable = "chatchannels-reportMessage"

    // MARK: - Factory

    private static let cachedLock = NSLock()
    nonisolated(unsafe) private static var cached: FirebaseConvoyChatRepository?

    /// Returns the process-wide repository when Firebase is configured, or nil in
    /// a config-less build. Emulator seams as in ``FirebaseCommunityChatRepository``.
    static func createIfAvailable() -> ConvoyChatRepository? {
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
        let repository = FirebaseConvoyChatRepository(firestore: firestore, functions: functions)
        cached = repository
        return repository
    }
}
