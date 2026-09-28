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

    func updateProfile(uid: String, profile: ValidatedProfile) async throws {
        var update: [String: Any] = [
            "displayName": profile.displayName,
            "bio": profile.bio,
            "updatedAt": FieldValue.serverTimestamp(),
        ]
        update["facebook"] = profile.facebook.map { $0 as Any } ?? FieldValue.delete()
        update["instagram"] = profile.instagram.map { $0 as Any } ?? FieldValue.delete()
        update["youtube"] = profile.youtube.map { $0 as Any } ?? FieldValue.delete()
        try await firestore.collection(Self.usersCollection).document(uid).updateData(update)
    }

    func uploadAvatar(uid: String, jpegData: Data) async throws {
        guard !uid.isEmpty, jpegData.count <= Self.avatarMaxBytes else {
            throw AvatarUploadError.invalidInput
        }
        let path = "profileImages/\(uid)/\(UUID().uuidString.lowercased()).jpg"
        let metadata = StorageMetadata()
        metadata.contentType = "image/jpeg"
        _ = try await storage.reference(withPath: path).putDataAsync(jpegData, metadata: metadata)

        let document = firestore.collection(Self.usersCollection).document(uid)
        do {
            // The transaction returns the path it replaced. Concurrent uploads
            // therefore form a chain (old -> A -> B): the winner that replaces
            // each path owns cleaning it up, and no upload can delete a newer
            // avatar selected by another request.
            let result = try await firestore.runTransaction { transaction, errorPointer in
                do {
                    let snapshot = try transaction.getDocument(document)
                    let previousPath = snapshot.get("avatarPath") as? String
                    transaction.updateData([
                        "avatarPath": path,
                        "updatedAt": FieldValue.serverTimestamp(),
                    ], forDocument: document)
                    return previousPath ?? NSNull()
                } catch let error as NSError {
                    errorPointer?.pointee = error
                    return nil
                }
            }
            if let previousPath = result as? String,
               Self.isOwnedAvatarPath(previousPath, uid: uid), previousPath != path {
                // Best effort: the profile commit must remain successful even
                // when deleting an obsolete image is temporarily unavailable.
                try? await storage.reference(withPath: previousPath).delete()
            }
        } catch {
            // A transaction completion can be ambiguous after a network loss.
            // Re-read before cleanup: delete this unique upload only when it is
            // definitely not the selected avatar. If the read also fails, keep
            // the object rather than risk deleting the live profile image.
            if let snapshot = try? await document.getDocument(),
               snapshot.get("avatarPath") as? String != path {
                try? await storage.reference(withPath: path).delete()
            }
            throw error
        }
    }

    // MARK: - Factory

    private static let usersCollection = "users"
    private static let avatarMaxBytes = 5 * 1024 * 1024

    private static func isOwnedAvatarPath(_ path: String, uid: String) -> Bool {
        let prefix = "profileImages/\(uid)/"
        guard path.hasPrefix(prefix) else { return false }
        let objectName = path.dropFirst(prefix.count)
        return !objectName.isEmpty && !objectName.contains("/")
    }

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

private enum AvatarUploadError: Error { case invalidInput }
