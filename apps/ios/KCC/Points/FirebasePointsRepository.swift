import FirebaseCore
import FirebaseFirestore
import Foundation

/// Owner points access backed by Firestore. Entry reads are bounded to the same
/// newest 100 rows as Android, keeping listener cost flat as the ledger grows.
final class FirebasePointsRepository: PointsRepository, @unchecked Sendable {
    private let firestore: Firestore

    private init(firestore: Firestore) {
        self.firestore = firestore
    }

    func observeBalance(uid: String) -> AsyncStream<Int64?> {
        let document = firestore.collection(Self.ledger).document(uid)
        return AsyncStream { continuation in
            let registration = document.addSnapshotListener { snapshot, error in
                // Keep the last known balance across transient failures rather
                // than briefly displaying zero.
                guard error == nil else { return }
                continuation.yield((snapshot?.get(Self.balanceField) as? NSNumber)?.int64Value)
            }
            let box = PointsListenerBox(registration: registration)
            continuation.onTermination = { _ in box.registration.remove() }
        }
    }

    func observeEntries(uid: String) -> AsyncStream<PointsEntriesSnapshot> {
        let query = firestore.collection(Self.ledger).document(uid)
            .collection(Self.entries)
            .order(by: Self.createdAtField, descending: true)
            .limit(to: Self.entryPageSize)
        return AsyncStream { continuation in
            let registration = query.addSnapshotListener { snapshot, error in
                if let error {
                    continuation.yield(.failed(
                        code: FirebaseEventsRepository.firestoreStatusName(error)
                    ))
                    return
                }
                let entries = (snapshot?.documents ?? []).compactMap { document in
                    var fields = document.data()
                    if let timestamp = fields[Self.createdAtField] as? Timestamp {
                        fields[Self.createdAtField] = timestamp.dateValue()
                    } else {
                        fields[Self.createdAtField] = nil
                    }
                    return PointsEntry.fromMap(id: document.documentID, map: fields)
                }
                continuation.yield(.loaded(Points.sortedForList(entries)))
            }
            let box = PointsListenerBox(registration: registration)
            continuation.onTermination = { _ in box.registration.remove() }
        }
    }

    private static let ledger = "pointsLedger"
    private static let entries = "entries"
    private static let balanceField = "balance"
    private static let createdAtField = "createdAt"
    private static let entryPageSize = 100

    private static let cachedLock = NSLock()
    nonisolated(unsafe) private static var cached: FirebasePointsRepository?

    static func createIfAvailable() -> PointsRepository? {
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
        let repository = FirebasePointsRepository(firestore: firestore)
        cached = repository
        return repository
    }
}

private struct PointsListenerBox: @unchecked Sendable {
    let registration: ListenerRegistration
}
