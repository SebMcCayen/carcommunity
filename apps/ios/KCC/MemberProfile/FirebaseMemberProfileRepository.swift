import FirebaseCore
import FirebaseFirestore
import FirebaseStorage
import Foundation

final class FirebaseMemberProfileRepository: MemberProfileRepository, @unchecked Sendable {
    private let firestore: Firestore
    private let storage: Storage

    private init(firestore: Firestore, storage: Storage) {
        self.firestore = firestore
        self.storage = storage
    }

    func load(targetUid: String) async -> MemberProfileResult {
        let uid = targetUid.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !uid.isEmpty else { return .notFound }
        do {
            let document = try await firestore.collection("users").document(uid).getDocument()
            guard document.exists, var map = document.data() else { return .notFound }
            map["createdAt"] = (document.get("createdAt") as? Timestamp)?.dateValue()
            guard let profile = MemberProfile.fromMap(uid: uid, map: map) else { return .notFound }

            async let vehicles = loadVehicles(uid: uid)
            async let badges = loadBadges(uid: uid)
            async let points = loadPoints(uid: uid)
            return await .loaded(MemberProfileContent(
                profile: profile,
                vehicles: vehicles,
                badges: badges,
                pointsBalance: points
            ))
        } catch is CancellationError {
            return .failed
        } catch {
            return .failed
        }
    }

    func imageDownloadURL(for path: String) async -> URL? {
        try? await storage.reference(withPath: path).downloadURL()
    }

    private func loadVehicles(uid: String) async -> [Vehicle] {
        guard let snapshot = try? await firestore.collection("vehicles")
            .whereField("userId", isEqualTo: uid).getDocuments()
        else { return [] }
        return Garage.sortedForList(snapshot.documents.compactMap {
            Vehicle.fromMap(id: $0.documentID, map: $0.data())
        })
    }

    private func loadBadges(uid: String) async -> PublicBadges {
        do {
            let snapshot = try await firestore.collection("users").document(uid)
                .collection("badges").getDocuments()
            return .available(Badges.sortedForList(snapshot.documents.map { document in
                var map = document.data()
                map["awardedAt"] = (document.get("awardedAt") as? Timestamp)?.dateValue()
                return Badge.fromMap(id: document.documentID, map: map)
            }))
        } catch let error as NSError {
            return error.code == FirestoreErrorCode.permissionDenied.rawValue ? .unavailable : .failed
        }
    }

    private func loadPoints(uid: String) async -> Int64 {
        guard let snapshot = try? await firestore.collection("pointsLedger").document(uid).getDocument(),
              snapshot.exists
        else { return 0 }
        return (snapshot.get("balance") as? NSNumber)?.int64Value ?? 0
    }

    private static let lock = NSLock()
    nonisolated(unsafe) private static var cached: FirebaseMemberProfileRepository?

    static func createIfAvailable() -> MemberProfileRepository? {
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
        let storage = Storage.storage()
        if let emulator = FirebaseEmulatorHost.parse(
            ProcessInfo.processInfo.environment["FIREBASE_STORAGE_EMULATOR_HOST"]
        ) {
            storage.useEmulator(withHost: emulator.host, port: emulator.port)
        }
        let value = FirebaseMemberProfileRepository(firestore: firestore, storage: storage)
        cached = value
        return value
    }
}
