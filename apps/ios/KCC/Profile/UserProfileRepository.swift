import Foundation

/// Own-profile read boundary — the iOS port of Android's
/// `profile/ProfileRepository.kt`, covering live reads and owner edits.
/// Firebase-free protocol so ``ProfileCoordinator`` and the screen are
/// unit-testable with fakes.
///
/// Reads are a direct Firestore snapshot listener on `users/{uid}` —
/// readable by any authenticated user (firebase/firestore.rules
/// `users/{userId}`), so the owner reading their own document is always
/// permitted.
protocol UserProfileRepository: AnyObject, Sendable {
    /// The live `users/{uid}` profile. Each call returns a fresh stream
    /// backed by its own snapshot listener; terminating the stream (dropping
    /// the iteration) detaches the listener.
    func profileUpdates(uid: String) -> AsyncStream<UserProfileSnapshot>

    /// Resolves a Cloud Storage avatar path (profileImages/{uid}/{imageId})
    /// to a download URL for rendering — the iOS analog of Android's
    /// `resolveStorageDownloadUrl`. Returns nil on any failure (offline,
    /// object deleted, rules): the avatar then keeps its placeholder, exactly
    /// like Coil rendering nothing on Android, and nothing PII-bearing is
    /// carried out of the failure.
    func avatarDownloadURL(for avatarPath: String) async -> URL?

    /// Owner-only public profile edit. This is a protocol requirement (rather
    /// than an extension-only convenience) so calls through the repository
    /// existential dispatch to Firebase and to test fakes.
    func updateProfile(uid: String, profile: ValidatedProfile) async throws

    /// Uploads a sanitized JPEG and atomically makes its owner-scoped Storage
    /// path the profile avatar.
    func uploadAvatar(uid: String, jpegData: Data) async throws
}

extension UserProfileRepository {
    /// Owner-only public profile edit. Fakes that only exercise reads can keep
    /// the default unavailable implementation.
    func updateProfile(uid: String, profile: ValidatedProfile) async throws {
        throw ProfileRepositoryUnavailableError()
    }

    /// Uploads a sanitized JPEG and persists its owner-scoped Storage path.
    func uploadAvatar(uid: String, jpegData: Data) async throws {
        throw ProfileRepositoryUnavailableError()
    }
}

private struct ProfileRepositoryUnavailableError: Error {}
