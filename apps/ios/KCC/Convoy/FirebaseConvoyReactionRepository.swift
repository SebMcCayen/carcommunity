import FirebaseCore
import FirebaseFirestore
import FirebaseFunctions
import Foundation

/// Firebase-backed transient convoy reactions. Creation is guarded so a
/// config-less build omits the feature without touching Firebase singletons.
final class FirebaseConvoyReactionRepository: ConvoyReactionRepository, @unchecked Sendable {
    private let firestore: Firestore
    private let functions: Functions

    private init(firestore: Firestore, functions: Functions) {
        self.firestore = firestore
        self.functions = functions
    }

    func send(
        convoyId: String,
        kind: ConvoyReactionKind,
        clientId: String
    ) async -> ConvoyReactionSendResult {
        do {
            _ = try await functions.httpsCallable(Self.sendReactionCallable).call(
                ConvoyReactionWire.sendPayload(
                    convoyId: convoyId,
                    kind: kind,
                    clientId: clientId
                )
            )
            return .sent
        } catch {
            return Self.sendResult(from: error)
        }
    }

    func reactions(convoyId: String) -> AsyncStream<ConvoyReactionEvent> {
        let query = firestore
            .collection(Self.convoyChatsCollection)
            .document(convoyId)
            .collection(Self.reactionsCollection)
            .order(by: Self.createdAtField, descending: false)

        return AsyncStream { continuation in
            // Firestore reports every document in the initial snapshot as an
            // `.added` change. Baseline through the first server-backed
            // snapshot, including any cache snapshot that precedes it, so
            // existing reactions are never replayed as fresh. Keeping seen ids
            // for the listener lifetime also protects target reconnects from
            // re-emitting an older document.
            let gate = ConvoyReactionSnapshotGate()
            let registration = query.addSnapshotListener { snapshot, error in
                guard error == nil, let snapshot else { return }
                let addedDocuments = snapshot.documentChanges
                    .filter { $0.type == .added }
                    .map(\.document)
                let acceptedIds = gate.acceptedDocumentIds(
                    allDocumentIds: snapshot.documents.map(\.documentID),
                    addedDocumentIds: addedDocuments.map(\.documentID),
                    isFromCache: snapshot.metadata.isFromCache
                )
                guard !acceptedIds.isEmpty else { return }
                for document in addedDocuments where acceptedIds.contains(document.documentID) {
                    guard !document.metadata.hasPendingWrites,
                          let event = Self.event(from: document)
                    else { continue }
                    continuation.yield(event)
                }
            }
            let box = ConvoyReactionListenerBox(registration: registration)
            continuation.onTermination = { _ in box.registration.remove() }
        }
    }

    static func sendResult(from error: Error) -> ConvoyReactionSendResult {
        let nsError = error as NSError
        guard nsError.domain == FunctionsErrorDomain,
              let code = FunctionsErrorCode(rawValue: nsError.code),
              code == .resourceExhausted
        else { return .failed }
        return .rateLimited(retryAfterMilliseconds: ConvoyReactionWire.retryAfterMilliseconds(
            from: nsError.userInfo[FunctionsErrorDetailsKey]
        ))
    }

    static func event(from document: QueryDocumentSnapshot) -> ConvoyReactionEvent? {
        let data = document.data()
        guard let kindRaw = data["kind"] as? String,
              let kind = ConvoyReactionKind(rawValue: kindRaw),
              let senderUid = data["senderUid"] as? String,
              !senderUid.isEmpty,
              let createdAt = data[Self.createdAtField] as? Timestamp
        else { return nil }
        let rawName = data["senderDisplayName"] as? String
        let senderName = rawName?.trimmingCharacters(in: .whitespacesAndNewlines)
        return ConvoyReactionEvent(
            id: document.documentID,
            kind: kind,
            senderUid: senderUid,
            senderName: senderName?.isEmpty == false ? senderName : nil,
            createdAt: createdAt.dateValue()
        )
    }

    private static let sendReactionCallable = "convoy-sendReaction"
    private static let convoyChatsCollection = "convoyChats"
    private static let reactionsCollection = "reactions"
    private static let createdAtField = "createdAt"

    private static let cachedLock = NSLock()
    nonisolated(unsafe) private static var cached: FirebaseConvoyReactionRepository?

    static func createIfAvailable() -> ConvoyReactionRepository? {
        guard FirebaseApp.app() != nil else { return nil }
        cachedLock.lock()
        defer { cachedLock.unlock() }
        if let cached { return cached }

        let firestore = Firestore.firestore()
        if let emulator = FirebaseEmulatorHost.parse(
            ProcessInfo.processInfo.environment["FIREBASE_FIRESTORE_EMULATOR_HOST"]
        ), firestore.settings.host != "\(emulator.host):\(emulator.port)" {
            firestore.useEmulator(withHost: emulator.host, port: emulator.port)
        }
        let functions = Functions.functions(region: KccFunctionsClient.region)
        if let emulator = FirebaseEmulatorHost.parse(
            ProcessInfo.processInfo.environment["FIREBASE_FUNCTIONS_EMULATOR_HOST"]
        ) {
            functions.useEmulator(withHost: emulator.host, port: emulator.port)
        }
        let repository = FirebaseConvoyReactionRepository(
            firestore: firestore,
            functions: functions
        )
        cached = repository
        return repository
    }
}

private struct ConvoyReactionListenerBox: @unchecked Sendable {
    let registration: ListenerRegistration
}

/// Thread-safe freshness gate for Firestore snapshot listeners.
///
/// A cache snapshot is not a sufficient baseline because the following server
/// snapshot can contain older documents absent from cache and describe them as
/// added. Listening begins only after a complete server snapshot establishes
/// the baseline. IDs remain remembered across later target reconnects.
final class ConvoyReactionSnapshotGate: @unchecked Sendable {
    private let lock = NSLock()
    private var hasServerBaseline = false
    private var seenDocumentIds: Set<String> = []

    func acceptedDocumentIds(
        allDocumentIds: [String],
        addedDocumentIds: [String],
        isFromCache: Bool
    ) -> Set<String> {
        lock.withLock {
            if !hasServerBaseline {
                seenDocumentIds.formUnion(allDocumentIds)
                guard !isFromCache else { return [] }
                hasServerBaseline = true
                return []
            }

            let unseenIds = Set(addedDocumentIds).subtracting(seenDocumentIds)
            seenDocumentIds.formUnion(allDocumentIds)
            return unseenIds
        }
    }
}
