import FirebaseAuth
import FirebaseCore
import FirebaseFirestore
import Foundation

final class FirebaseEventChatRepository: EventChatRepository, @unchecked Sendable {
    private let firestore: Firestore
    private let functions: KccFunctionsClient

    private init(firestore: Firestore, functions: KccFunctionsClient) {
        self.firestore = firestore
        self.functions = functions
    }

    func messages(eventId: String) -> AsyncStream<EventChatMessagesState> {
        let query = firestore
            .collection(Self.eventsCollection)
            .document(eventId)
            .collection(Self.messagesCollection)
            .order(by: Self.createdAtField, descending: true)
            .limit(to: eventChatWindowSize)
        let blockDocument = currentUserId().map {
            firestore.collection(ChatFirestore.blockVisibilityCollection).document($0)
        }
        return EventChatMessagesListener.stream(
            messagesQuery: query,
            blockVisibilityDocument: blockDocument
        )
    }

    func enabled() -> AsyncStream<Bool> {
        let document = firestore.collection(Self.configCollection).document(Self.featureFlagsDocument)
        return AsyncStream { continuation in
            // Android starts from FeatureFlags.DEFAULTS; chat's contract default is true.
            continuation.yield(true)
            let registration = document.addSnapshotListener { snapshot, error in
                if error != nil && snapshot == nil { return }
                continuation.yield(snapshot?.get(Self.chatFlag) as? Bool ?? true)
            }
            let box = EventChatRegistrationBox(registrations: [registration])
            continuation.onTermination = { _ in box.removeAll() }
        }
    }

    func post(eventId: String, message: String) async throws {
        _ = try await functions.call(
            Self.postCallable,
            payload: [
                "eventId": eventId,
                "message": message.trimmingCharacters(in: .whitespacesAndNewlines),
            ]
        )
    }

    func report(eventId: String, messageId: String, reason: ChatReportReason) async throws {
        _ = try await functions.call(
            Self.reportCallable,
            payload: [
                "eventId": eventId,
                "messageId": messageId,
                "reason": reason.wire,
            ]
        )
    }

    func currentUserId() -> String? { Auth.auth().currentUser?.uid }

    private static let eventsCollection = "events"
    private static let messagesCollection = "messages"
    private static let createdAtField = "createdAt"
    private static let configCollection = "config"
    private static let featureFlagsDocument = "featureFlags"
    private static let chatFlag = "chat"
    private static let postCallable = "events-postChatMessage"
    private static let reportCallable = "events-reportChatMessage"

    private static let cachedLock = NSLock()
    nonisolated(unsafe) private static var cached: FirebaseEventChatRepository?

    static func createIfAvailable() -> EventChatRepository? {
        guard FirebaseApp.app() != nil, let functions = KccFunctionsClient.createIfAvailable() else {
            return nil
        }
        cachedLock.lock()
        defer { cachedLock.unlock() }
        if let cached { return cached }
        let firestore = Firestore.firestore()
        if let emulator = FirebaseEmulatorHost.parse(
            ProcessInfo.processInfo.environment["FIREBASE_FIRESTORE_EMULATOR_HOST"]
        ), firestore.settings.host != "\(emulator.host):\(emulator.port)" {
            firestore.useEmulator(withHost: emulator.host, port: emulator.port)
        }
        let repository = FirebaseEventChatRepository(firestore: firestore, functions: functions)
        cached = repository
        return repository
    }
}

private struct EventChatRegistrationBox: @unchecked Sendable {
    let registrations: [ListenerRegistration]
    func removeAll() { registrations.forEach { $0.remove() } }
}

private final class EventChatMessagesListener: @unchecked Sendable {
    private let lock = NSLock()
    private let continuation: AsyncStream<EventChatMessagesState>.Continuation
    private var rawNewestFirst: [EventChatMessage]?
    private var hiddenUserIds: Set<String> = []
    /// Match Flow.combine: never emit a message window until the block mirror
    /// has produced its first value. This avoids a one-frame blocked-author leak.
    private var hiddenSettled: Bool

    private init(
        continuation: AsyncStream<EventChatMessagesState>.Continuation,
        hiddenSettled: Bool
    ) {
        self.continuation = continuation
        self.hiddenSettled = hiddenSettled
    }

    static func stream(
        messagesQuery: Query,
        blockVisibilityDocument: DocumentReference?
    ) -> AsyncStream<EventChatMessagesState> {
        AsyncStream { continuation in
            let listener = EventChatMessagesListener(
                continuation: continuation,
                hiddenSettled: blockVisibilityDocument == nil
            )
            let messages = messagesQuery.addSnapshotListener { snapshot, error in
                listener.onMessages(snapshot: snapshot, error: error)
            }
            var registrations = [messages]
            if let blockVisibilityDocument {
                registrations.append(
                    blockVisibilityDocument.addSnapshotListener { snapshot, error in
                        listener.onHidden(snapshot: snapshot, error: error)
                    }
                )
            }
            let box = EventChatRegistrationBox(registrations: registrations)
            continuation.onTermination = { _ in box.removeAll() }
        }
    }

    private func onMessages(snapshot: QuerySnapshot?, error: Error?) {
        lock.lock()
        defer { lock.unlock() }
        if error != nil {
            continuation.yield(.failed)
            return
        }
        rawNewestFirst = (snapshot?.documents ?? []).compactMap(Self.message(from:))
        if hiddenSettled { emitLocked() }
    }

    private func onHidden(snapshot: DocumentSnapshot?, error: Error?) {
        lock.lock()
        defer { lock.unlock() }
        if error != nil && snapshot == nil {
            // Same first-error fallback as FirebaseBlockVisibilityRepository:
            // settle empty so the screen does not remain loading forever.
            hiddenSettled = true
            if rawNewestFirst != nil { emitLocked() }
            return
        }
        let values = snapshot?.get(ChatFirestore.hiddenUidsField) as? [Any] ?? []
        hiddenUserIds = Set(values.compactMap { $0 as? String }.filter { !$0.isEmpty })
        hiddenSettled = true
        if rawNewestFirst != nil { emitLocked() }
    }

    private func emitLocked() {
        guard let rawNewestFirst else { return }
        let filtered = EventChat.filterHidden(rawNewestFirst, hiddenUserIds: hiddenUserIds)
        continuation.yield(.loaded(Array(filtered.reversed())))
    }

    private static func message(from document: QueryDocumentSnapshot) -> EventChatMessage? {
        guard let author = document.get("authorUserId") as? String, !author.isEmpty else { return nil }
        return EventChatMessage(
            id: document.documentID,
            authorUserId: author,
            authorDisplayName: document.get("authorDisplayName") as? String,
            message: document.get("message") as? String ?? "",
            moderationState: .fromWire(document.get("moderationState") as? String),
            createdAt: (document.get("createdAt") as? Timestamp)?.dateValue()
        )
    }
}
