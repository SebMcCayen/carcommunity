import FirebaseCore
import FirebaseFirestore
import Foundation

/// Firestore owner-list + callable mutations, matching Android's
/// FirebaseBlockingRepository. This type never reads another member's block
/// collection or writes block documents directly.
final class FirebaseBlockingRepository: BlockingRepository, @unchecked Sendable {
    private let firestore: Firestore
    private let functions: KccFunctionsClient

    private init(firestore: Firestore, functions: KccFunctionsClient) {
        self.firestore = firestore
        self.functions = functions
    }

    func observeBlocked(uid: String) -> AsyncStream<BlockedUsersSnapshot> {
        let query = firestore.collection(Self.userBlocksCollection)
            .document(uid)
            .collection(Self.blockedCollection)
        return AsyncStream { continuation in
            let registration = query.addSnapshotListener { snapshot, error in
                if let error {
                    continuation.yield(
                        .failed(code: FirebaseEventsRepository.firestoreStatusName(error))
                    )
                    return
                }
                let users = (snapshot?.documents ?? []).compactMap(Self.blockedUser(from:))
                continuation.yield(.loaded(BlockedUsers.sortedForList(users)))
            }
            let box = BlockingListenerBox(registration: registration)
            continuation.onTermination = { _ in box.registration.remove() }
        }
    }

    func block(targetUserId: String) async throws {
        _ = try await functions.call(
            Self.blockCallable,
            payload: [Self.targetUserIdField: targetUserId]
        )
    }

    func unblock(targetUserId: String) async throws {
        _ = try await functions.call(
            Self.unblockCallable,
            payload: [Self.targetUserIdField: targetUserId]
        )
    }

    static func blockedUser(from document: DocumentSnapshot) -> BlockedUser? {
        guard document.exists else { return nil }
        var fields = document.data() ?? [:]
        fields["createdAt"] = (document.get("createdAt") as? Timestamp)?.dateValue()
        return BlockedUser.decode(documentId: document.documentID, fields: fields)
    }

    private static let userBlocksCollection = "userBlocks"
    private static let blockedCollection = "blocked"
    private static let targetUserIdField = "targetUserId"
    private static let blockCallable = "blocking-block"
    private static let unblockCallable = "blocking-unblock"

    private static let cachedLock = NSLock()
    nonisolated(unsafe) private static var cached: FirebaseBlockingRepository?

    static func createIfAvailable() -> BlockingRepository? {
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
        let repository = FirebaseBlockingRepository(firestore: firestore, functions: functions)
        cached = repository
        return repository
    }
}

private struct BlockingListenerBox: @unchecked Sendable {
    let registration: ListenerRegistration
}
