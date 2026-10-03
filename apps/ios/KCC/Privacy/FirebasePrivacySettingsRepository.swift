import FirebaseCore
import FirebaseFirestore
import Foundation

/// Firestore implementation of ``PrivacySettingsRepository``. One listener
/// observes both fields on the one owner-private document, while targeted
/// `updateData` writes preserve every unrelated private field.
final class FirebasePrivacySettingsRepository: PrivacySettingsRepository, @unchecked Sendable {
    private let firestore: Firestore

    private init(firestore: Firestore) {
        self.firestore = firestore
    }

    func settings(uid: String) -> AsyncStream<PrivacySettingsSnapshot> {
        let document = firestore.collection(Self.collection).document(uid)
        return AsyncStream { continuation in
            let registration = document.addSnapshotListener(
                includeMetadataChanges: true
            ) { snapshot, error in
                if let error {
                    continuation.yield(
                        .failed(code: FirebaseEventsRepository.firestoreStatusName(error))
                    )
                    return
                }
                guard let snapshot else {
                    continuation.yield(.failed(code: nil))
                    return
                }
                guard let authoritative = PrivacySettingsSnapshot.authoritative(
                    data: snapshot.data(),
                    isFromCache: snapshot.metadata.isFromCache,
                    hasPendingWrites: snapshot.metadata.hasPendingWrites
                ) else { return }
                continuation.yield(authoritative)
            }
            let box = PrivacyListenerBox(registration: registration)
            continuation.onTermination = { _ in box.registration.remove() }
        }
    }

    func setPartnerStatsOptIn(uid: String, optIn: Bool) async throws {
        try await update(uid: uid, field: Self.partnerStatsField, value: optIn)
    }

    func setLeaderboardOptOut(uid: String, optOut: Bool) async throws {
        try await update(uid: uid, field: Self.leaderboardOptOutField, value: optOut)
    }

    private func update(uid: String, field: String, value: Bool) async throws {
        do {
            try await firestore.collection(Self.collection).document(uid).updateData([
                field: value,
                Self.updatedAtField: FieldValue.serverTimestamp(),
            ])
        } catch {
            throw PrivacySettingsWriteError(
                code: FirebaseEventsRepository.firestoreStatusName(error)
            )
        }
    }

    private static let collection = "userPrivate"
    private static let partnerStatsField = "anonymousPartnerStatsOptIn"
    private static let leaderboardOptOutField = "leaderboardOptOut"
    private static let updatedAtField = "updatedAt"

    private static let cachedLock = NSLock()
    nonisolated(unsafe) private static var cached: FirebasePrivacySettingsRepository?

    /// Config-less safe factory, with the same process-wide Firestore emulator
    /// seam as the rest of the iOS repositories.
    static func createIfAvailable() -> PrivacySettingsRepository? {
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
        let repository = FirebasePrivacySettingsRepository(firestore: firestore)
        cached = repository
        return repository
    }
}

private struct PrivacyListenerBox: @unchecked Sendable {
    let registration: ListenerRegistration
}
