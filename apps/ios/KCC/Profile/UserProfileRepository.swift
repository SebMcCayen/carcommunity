import Foundation

/// Own-profile read boundary — the iOS port of Android's
/// `profile/ProfileRepository.kt`, restricted to the read-only slice.
/// Firebase-free protocol so ``ProfileCoordinator`` and the screen are
/// unit-testable with fakes.
///
/// Reads are a direct Firestore snapshot listener on `users/{uid}` —
/// readable by any authenticated user (firebase/firestore.rules
/// `users/{userId}`), so the owner reading their own document is always
/// permitted. The owner edit writes (`updateProfile`, `updateAvatarPath` on
/// Android) are deliberately absent — they arrive with the profile-edit
/// slice.
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
