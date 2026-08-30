import FirebaseCore
import FirebaseFirestore
import FirebaseStorage
import Foundation

/// ``UserProfileRepository`` backed by Cloud Firestore + Cloud Storage — the
/// iOS port of Android's `FirebaseProfileRepository.kt` read path.
///
/// The profile is a live snapshot listener on `users/{uid}` (readable by any
/// authenticated user — firebase/firestore.rules). Listener failures surface
/// as ``UserProfileSnapshot/failed(code:)`` carrying the bare Firestore
/// status name, never as a silently missing profile — the same PII posture
/// as ``FirebaseEventsRepository``.
///
/// Avatar paths resolve to download URLs through Cloud Storage
/// (`getDownloadUrl`), mirroring Android's `resolveStorageDownloadUrl`; a
/// resolution failure degrades to nil (placeholder) rather than an error
/// state, because a missing picture is cosmetic.
///
/// Construction is guarded (``createIfAvailable()`` returns nil without
/// Firebase config), mirroring `FirebaseAuthRepository` and Android's
/// `createIfAvailable`.
final class FirebaseUserProfileRepository: UserProfileRepository, @unchecked Sendable {
    private let firestore: Firestore
    private let storage: Storage

    private init(firestore: Firestore, storage: Storage) {
        self.firestore = firestore
        self.storage = storage
    }

    func profileUpdates(uid: String) -> AsyncStream<UserProfileSnapshot> {
        let document = firestore.collection(Self.usersCollection).document(uid)
        return AsyncStream { continuation in
            let registration = document.addSnapshotListener { snapshot, error in
                if let error {
                    // Bare status name only (never the exception text, which
                    // embeds the failing document path and the project id) —
                    // see EventsListSnapshot.failed.
                    continuation.yield(
                        .failed(code: FirebaseEventsRepository.firestoreStatusName(error))
                    )
                    return
                }
                guard let snapshot, snapshot.exists, let data = snapshot.data() else {
                    // Document not provisioned yet — a settled "no profile",
                    // not an error (Android's Loaded(null)).
                    continuation.yield(.loaded(nil))
                    return
                }
                continuation.yield(.loaded(UserProfile.fromMap(data)))
            }
            let box = ListenerBox(registration: registration)
            continuation.onTermination = { _ in
                box.registration.remove()
            }
        }
    }

    func avatarDownloadURL(for avatarPath: String) async -> URL? {
        try? await storage.reference(withPath: avatarPath).downloadURL()
    }

    // MARK: - Factory

    private static let usersCollection = "users"

    private static let cachedLock = NSLock()
    nonisolated(unsafe) private static var cached: FirebaseUserProfileRepository?

    /// Returns the process-wide repository when Firebase is configured for
    /// this build, or nil when GoogleService-Info.plist is absent (CI, local
    /// validation builds — see apps/ios/README.md).
    ///
    /// Emulator seams follow the shared `FIREBASE_*_EMULATOR_HOST`
    /// convention: `FIREBASE_FIRESTORE_EMULATOR_HOST` (8080) for the profile
    /// listener and `FIREBASE_STORAGE_EMULATOR_HOST` (9199) for avatar URL
    /// resolution — ports per firebase.json. Firestore is a process-wide
    /// singleton shared with `FirebaseEventsRepository`, whose factory
    /// applies the same emulator settings; the host check below makes the
    /// second application a no-op instead of mutating settings twice.
    static func createIfAvailable() -> UserProfileRepository? {
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
        let repository = FirebaseUserProfileRepository(firestore: firestore, storage: storage)
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
