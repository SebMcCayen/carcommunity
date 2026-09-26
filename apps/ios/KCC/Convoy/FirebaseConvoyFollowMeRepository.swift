import FirebaseCore
import FirebaseFirestore
import FirebaseFunctions
import Foundation

final class FirebaseConvoyFollowMeRepository: ConvoyFollowMeRepository, @unchecked Sendable {
    private let firestore: Firestore
    private let functions: Functions

    private init(firestore: Firestore, functions: Functions) {
        self.firestore = firestore
        self.functions = functions
    }

    func setFollowMe(convoyId: String, active: Bool) async -> Bool? {
        do {
            let result = try await functions.httpsCallable(Self.callable).call([
                "convoyId": convoyId,
                "active": active
            ])
            return (result.data as? [String: Any])?["leading"] as? Bool ?? false
        } catch {
            return nil
        }
    }

    func writeTrail(convoyId: String, polyline: String) async -> Bool {
        do {
            try await document(convoyId).updateData([
                "polyline": polyline,
                "updatedAt": FieldValue.serverTimestamp()
            ])
            return true
        } catch {
            // Leadership may have changed between the local fix and this best-effort write.
            return false
        }
    }

    func states(convoyId: String) -> AsyncStream<ConvoyFollowMeState?> {
        AsyncStream { continuation in
            let registration = document(convoyId).addSnapshotListener { snapshot, error in
                guard error == nil, let snapshot, snapshot.exists else {
                    continuation.yield(nil)
                    return
                }
                continuation.yield(ConvoyFollowMeState(
                    leaderUid: snapshot.get("leaderUid") as? String,
                    polyline: snapshot.get("polyline") as? String,
                    updatedAt: (snapshot.get("updatedAt") as? Timestamp)?.dateValue()
                ))
            }
            let box = ConvoyFollowMeListenerBox(registration: registration)
            continuation.onTermination = { _ in box.registration.remove() }
        }
    }

    private func document(_ convoyId: String) -> DocumentReference {
        firestore.collection("convoys").document(convoyId)
            .collection("followMe").document("current")
    }

    private static let callable = "convoy-setFollowMe"
    private static let cachedLock = NSLock()
    nonisolated(unsafe) private static var cached: FirebaseConvoyFollowMeRepository?

    static func createIfAvailable() -> ConvoyFollowMeRepository? {
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
        let repository = FirebaseConvoyFollowMeRepository(
            firestore: firestore,
            functions: functions
        )
        cached = repository
        return repository
    }
}

private struct ConvoyFollowMeListenerBox: @unchecked Sendable {
    let registration: ListenerRegistration
}
