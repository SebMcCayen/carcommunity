import Foundation

/// One emission of the owner rides listener.
///
/// The iOS port of Android's `DrivesState` minus `Loading`: a repository
/// stream only ever emits SETTLED results (a snapshot or a failure), and the
/// coordinator supplies the loading state before the first emission — the
/// same split as ``EventsListSnapshot`` / ``UserProfileSnapshot``.
enum DrivesSnapshot: Equatable, Sendable {
    /// The listener failed. `code` is the bare Firestore status name when
    /// one was available (`PERMISSION_DENIED` for an undeployed rule,
    /// `UNAVAILABLE` when offline, …) — a stable, PII-safe diagnosis; never
    /// exception text, which can embed the failing query and the project id
    /// (the same rule as Android's `DrivesState.Error(code)`).
    case failed(code: String?)
    /// A fresh snapshot of the owner's saved drives, already list-sorted
    /// (``SavedDrives/sortedForList(_:)``).
    case loaded([SavedDrive])
}

/// Saved-drives access — the iOS port of Android's `DrivesRepository.kt`,
/// restricted to the HISTORY read slice. Firebase-free protocol so the
/// coordinator and panel are unit-testable with fakes.
///
/// The list is an owner Firestore read: `rides` documents with
/// `userId == uid`, which is exactly what the rules allow
/// (firebase/firestore.rules `rides/{rideId}`: owner read WITHOUT the member
/// gate, so drives saved during a previous membership stay listable). ALL
/// writes go through the drives.* callables — `drives-save` computes stats
/// server-side and `drives-delete` removes the Storage files with the doc —
/// and both arrive with the recording slice, not here.
protocol DrivesRepository: AnyObject, Sendable {
    /// The owner's saved drives, list-sorted. Each call returns a fresh
    /// stream backed by its own snapshot listener; terminating the stream
    /// (dropping the iteration) detaches the listener.
    func drives(uid: String) -> AsyncStream<DrivesSnapshot>

    /// Resolves a Cloud Storage image path (the ride's denormalized
    /// `carImagePath` — a vehicle cover photo) to a download URL for
    /// rendering — the same lazy path→URL split as the profile avatar and
    /// the profile avatar's `avatarDownloadURL`. Returns nil on any failure (offline, object
    /// deleted, rules): the card then keeps its placeholder, because a
    /// missing picture is cosmetic, never an error state.
    func imageDownloadURL(for imagePath: String) async -> URL?
}
