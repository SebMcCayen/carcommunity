import FirebaseAuth
import FirebaseCore
import FirebaseFirestore
import Foundation

@MainActor
final class FirebaseLiveClubBadgeRepository: LiveClubBadgeRepository {
    private let firestore: Firestore
    private let viewerID: String

    private init(firestore: Firestore, viewerID: String) {
        self.firestore = firestore
        self.viewerID = viewerID
    }

    static func createIfAvailable(viewerID: String) -> FirebaseLiveClubBadgeRepository? {
        guard FirebaseApp.app() != nil, Auth.auth().currentUser?.uid == viewerID else { return nil }
        let firestore = Firestore.firestore()
        if let emulator = FirebaseEmulatorHost.parse(
            ProcessInfo.processInfo.environment["FIREBASE_FIRESTORE_EMULATOR_HOST"]
        ), firestore.settings.host != "\(emulator.host):\(emulator.port)" {
            firestore.useEmulator(withHost: emulator.host, port: emulator.port)
        }
        return Self(firestore: firestore, viewerID: viewerID)
    }

    func badgeUpdates(uid: String) -> AsyncStream<LiveClubBadge?> {
        let viewerID = viewerID
        return AsyncStream(bufferingPolicy: .bufferingNewest(1)) { continuation in
            let listener = firestore.collection("users").document(uid).addSnapshotListener { snapshot, error in
                let authorized = error == nil && Auth.auth().currentUser?.uid == viewerID
                continuation.yield(authorized ? LiveClubBadge.decode(snapshot?.data()?["clubBadge"]) : nil)
            }
            let box = LiveClubBadgeListenerBox(listener: listener)
            continuation.onTermination = { _ in box.listener.remove() }
        }
    }
    func appearanceUpdates(uid: String) -> AsyncStream<LiveDriverAppearance?> {
        let viewerID = viewerID
        return AsyncStream(bufferingPolicy: .bufferingNewest(1)) { continuation in
            let listener = firestore.collection("users").document(uid).addSnapshotListener { snapshot, error in
                let authorized = error == nil && Auth.auth().currentUser?.uid == viewerID
                continuation.yield(authorized ? LiveDriverAppearance.decode(snapshot?.data()) : nil)
            }
            let box = LiveClubBadgeListenerBox(listener: listener)
            continuation.onTermination = { _ in box.listener.remove() }
        }
    }

}

private final class LiveClubBadgeListenerBox: @unchecked Sendable {
    let listener: ListenerRegistration
    init(listener: ListenerRegistration) { self.listener = listener }
}
