import FirebaseCore
import FirebaseFirestore
import FirebaseStorage
import Foundation

/// ``DrivesRepository`` backed by Cloud Firestore + Cloud Storage — the iOS
/// port of Android's `FirebaseDrivesRepository.kt` read path (the
/// `drives-save` / `drives-delete` callables arrive with the recording
/// slice).
///
/// The list is an owner equality query (`userId == uid`) on the `rides`
/// collection, exactly Android's read path — no `order(by:)`, so no
/// composite index is needed, and the newest-first sort happens client-side
/// (``SavedDrives/sortedForList(_:)``), like Android's `sortedForList`.
/// Listener failures surface as ``DrivesSnapshot/failed(code:)`` carrying
/// the bare Firestore status name, never as a silently empty history.
///
/// Route GPS data lives in member-gated Cloud Storage
/// (`rideRoutes/{uid}/{rideId}/`) and is deliberately NOT read here — the
/// History card renders from the ride document alone; only the denormalized
/// `carImagePath` (a vehicle cover photo, readable like the garage's) is
/// resolved to a download URL.
///
/// Construction is guarded (``createIfAvailable()`` returns nil without
/// Firebase config), mirroring the other repositories and Android's
/// `createIfAvailable`.
final class FirebaseDrivesRepository: DrivesRepository, @unchecked Sendable {
    private let firestore: Firestore
    private let storage: Storage

    private init(firestore: Firestore, storage: Storage) {
        self.firestore = firestore
        self.storage = storage
    }

    func drives(uid: String) -> AsyncStream<DrivesSnapshot> {
        let query =
            firestore
            .collection(Self.ridesCollection)
            .whereField(Self.userIdField, isEqualTo: uid)
        return AsyncStream { continuation in
            let registration = query.addSnapshotListener { snapshot, error in
                if let error {
                    // Bare status name only (never the exception text, which
                    // embeds the failing query and the project id) — see
                    // DrivesSnapshot.failed and FirebaseEventsRepository.
                    continuation.yield(
                        .failed(code: FirebaseEventsRepository.firestoreStatusName(error))
                    )
                    return
                }
                let drives = (snapshot?.documents ?? []).compactMap {
                    SavedDrive.fromMap(id: $0.documentID, map: $0.data()) {
                        ($0 as? Timestamp)?.dateValue()
                    }
                }
                continuation.yield(.loaded(SavedDrives.sortedForList(drives)))
            }
            let box = ListenerBox(registration: registration)
            continuation.onTermination = { _ in
                box.registration.remove()
            }
        }
    }

    func imageDownloadURL(for imagePath: String) async -> URL? {
        try? await storage.reference(withPath: imagePath).downloadURL()
    }

    // MARK: - Factory

    private static let ridesCollection = "rides"
    private static let userIdField = "userId"

    private static let cachedLock = NSLock()
    nonisolated(unsafe) private static var cached: FirebaseDrivesRepository?

    /// Returns the process-wide repository when Firebase is configured for
    /// this build, or nil when GoogleService-Info.plist is absent (CI, local
    /// validation builds — see apps/ios/README.md).
    ///
    /// Emulator seams follow the shared `FIREBASE_*_EMULATOR_HOST`
    /// convention: `FIREBASE_FIRESTORE_EMULATOR_HOST` (8080) for the rides
    /// listener and `FIREBASE_STORAGE_EMULATOR_HOST` (9199) for car-photo
    /// URL resolution — ports per firebase.json. Firestore is a
    /// process-wide singleton shared with the other repositories, whose
    /// factories apply the same settings; the host check below makes the
    /// second application a no-op instead of mutating settings twice (same
    /// guard as `FirebaseUserProfileRepository`).
    static func createIfAvailable() -> DrivesRepository? {
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
        let storage = Storage.storage()
        if let emulator = FirebaseEmulatorHost.parse(
            ProcessInfo.processInfo.environment["FIREBASE_STORAGE_EMULATOR_HOST"]
        ) {
            storage.useEmulator(withHost: emulator.host, port: emulator.port)
        }
        let repository = FirebaseDrivesRepository(firestore: firestore, storage: storage)
        cached = repository
        return repository
    }
}

/// `ListenerRegistration` is not Sendable, but the stream's `onTermination`
/// closure must be — all it does is remove the listener, which Firestore
/// documents as thread-safe, so the wrapper is sound (same pattern as
/// `FirebaseEventsRepository`).
private struct ListenerBox: @unchecked Sendable {
    let registration: ListenerRegistration
}
