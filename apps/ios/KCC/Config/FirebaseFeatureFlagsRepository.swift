import FirebaseCore
import FirebaseFirestore
import Foundation

final class FirebaseFeatureFlagsRepository: FeatureFlagsRepository, @unchecked Sendable {
    private let document: DocumentReference
    private init(firestore: Firestore) {
        document = firestore.collection("config").document("featureFlags")
    }

    func fetch() async throws -> FeatureFlags {
        FeatureFlags.resolve(from: try await document.getDocument().data())
    }

    func updates() -> AsyncStream<FeatureFlags> {
        AsyncStream { continuation in
            let registration = document.addSnapshotListener { snapshot, error in
                // Listener failures emit nothing: the session retains its last
                // good snapshot and Firestore reconnects automatically.
                guard error == nil else { return }
                continuation.yield(.resolve(from: snapshot?.data()))
            }
            let box = FeatureFlagsListenerBox(registration: registration)
            continuation.onTermination = { _ in box.registration.remove() }
        }
    }

    private static let lock = NSLock()
    nonisolated(unsafe) private static var cached: FirebaseFeatureFlagsRepository?

    static func createIfAvailable() -> FeatureFlagsRepository? {
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
        let repository = FirebaseFeatureFlagsRepository(firestore: firestore)
        cached = repository
        return repository
    }
}

private struct FeatureFlagsListenerBox: @unchecked Sendable {
    let registration: ListenerRegistration
}
