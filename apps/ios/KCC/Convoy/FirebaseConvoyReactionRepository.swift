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

    func reactions(
        convoyId: String,
        since: Date
    ) -> AsyncStream<ConvoyReactionEvent> {
        let query = firestore
            .collection(Self.convoyChatsCollection)
            .document(convoyId)
            .collection(Self.reactionsCollection)
            .whereField(Self.createdAtField, isGreaterThan: Timestamp(date: since))
            .order(by: Self.createdAtField, descending: false)

        return AsyncStream { continuation in
            let registration = query.addSnapshotListener { snapshot, error in
                guard error == nil, let snapshot else { return }
                for change in snapshot.documentChanges where change.type == .added {
                    guard !change.document.metadata.hasPendingWrites,
                          let event = Self.event(from: change.document)
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
