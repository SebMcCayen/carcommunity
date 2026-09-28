import FirebaseCore
import FirebaseFirestore
import Foundation

final class FirebaseAccountAccessRepository: AccountAccessRepository, @unchecked Sendable {
    private let firestore: Firestore
    private init(firestore: Firestore) { self.firestore = firestore }

    func updates(uid: String) -> AsyncStream<AccountAccessSnapshot> {
        AsyncStream { continuation in
            let registration = firestore.collection("users").document(uid)
                .addSnapshotListener { snapshot, error in
                    if let error {
                        continuation.yield(.failed(
                            code: FirebaseEventsRepository.firestoreStatusName(error)
                        ))
                    } else {
                        continuation.yield(.loaded(snapshot?.data().map(AccountAccess.fromMap)))
                    }
                }
            let box = AccountAccessListenerBox(registration: registration)
            continuation.onTermination = { _ in box.registration.remove() }
        }
    }

    private static let lock = NSLock()
    nonisolated(unsafe) private static var cached: FirebaseAccountAccessRepository?

    static func createIfAvailable() -> AccountAccessRepository? {
        guard FirebaseApp.app() != nil else { return nil }
        lock.lock()
        defer { lock.unlock() }
        if let cached { return cached }
        let firestore = Firestore.firestore()
        if let emulator = FirebaseEmulatorHost.parse(
            ProcessInfo.processInfo.environment["FIREBASE_FIRESTORE_EMULATOR_HOST"]
        ), firestore.settings.host != "\(emulator.host):\(emulator.port)" {
            firestore.useEmulator(withHost: emulator.host, port: emulator.port)
        }
        let repository = FirebaseAccountAccessRepository(firestore: firestore)
        cached = repository
        return repository
    }
}

private struct AccountAccessListenerBox: @unchecked Sendable {
    let registration: ListenerRegistration
}
