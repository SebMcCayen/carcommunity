import Foundation
import Observation

/// UI-facing state of the own profile — the iOS port of Android's
/// `ProfileState`, with the config-less build modelled explicitly
/// (``unavailable``) so the screen can keep the auth display-name fallback.
enum ProfileUiState: Equatable, Sendable {
    /// Waiting for the first snapshot.
    case loading
    /// No repository (Firebase unconfigured in this build) or no signed-in
    /// uid — the profile document cannot be observed. The screen renders the
    /// auth display-name fallback, never a spinner (Android's
    /// `ProfileState.Unavailable`).
    case unavailable
    /// The snapshot resolved; `profile` is nil when the document does not
    /// exist yet.
    case loaded(UserProfile?)
    /// The listener failed. `code` is the bare Firestore status name when one
    /// was available — carried for diagnostics parity with
    /// ``EventsListUiState/failed(code:)``; the screen renders a generic
    /// message either way.
    case failed(code: String?)
}

/// Orchestrates the read-only own profile: subscribes the repository stream
/// for `users/{uid}`, folds its emissions into ``ProfileUiState``, and
/// lazily resolves the avatar path to a download URL for AsyncImage. Pure
/// Swift (no Firebase/SwiftUI types) so it is unit-testable with a fake
/// repository — the iOS counterpart of Android's profile wiring in
/// `AuthenticatedApp` (`observeProfile` + `rememberStorageImage`).
@MainActor
@Observable
final class ProfileCoordinator {
    private let repository: UserProfileRepository?
    private let uid: String?
    /// Live tasks. `nonisolated(unsafe)` so the nonisolated deinit can cancel
    /// them — every mutation happens on the main actor, and by the time
    /// deinit runs no other reference exists, so the unguarded access cannot
    /// race (same pattern as ``EventsCoordinator``).
    @ObservationIgnored
    nonisolated(unsafe) private var subscription: Task<Void, Never>?
    @ObservationIgnored
    nonisolated(unsafe) private var avatarResolution: Task<Void, Never>?

    private(set) var state: ProfileUiState
    /// The resolved avatar download URL, nil while unresolved / when the
    /// profile has no avatar / when resolution failed (placeholder renders).
    private(set) var avatarURL: URL?
    /// The avatarPath ``avatarURL`` was last resolved for, so an unchanged
    /// path across snapshots (every profile update re-emits the whole doc)
    /// never re-pays the download-URL round-trip — the concern Android's
    /// `StorageDownloadUrlCache` exists for, solved here per-coordinator
    /// because the own profile is a single image.
    private var resolvedAvatarPath: String?

    /// - Parameters:
    ///   - repository: nil when Firebase is not configured in this build.
    ///   - uid: the signed-in member's uid; nil when there is no session
    ///     (the screen should not be reachable then, but never crash).
    init(repository: UserProfileRepository?, uid: String?) {
        self.repository = repository
        self.uid = uid
        self.state = (repository == nil || uid == nil) ? .unavailable : .loading
    }

    deinit {
        subscription?.cancel()
        avatarResolution?.cancel()
    }

    /// Begins observing on first appearance. Idempotent: a second call (e.g.
    /// SwiftUI re-running `.task`) keeps the live subscription and its
    /// current state. No-op when unavailable.
    func start() {
        guard subscription == nil, let repository, let uid else { return }
        let stream = repository.profileUpdates(uid: uid)
        // `self` is captured weakly so the long-lived stream task never
        // retains the coordinator (see EventsCoordinator.subscribe).
        subscription = Task { [weak self] in
            for await snapshot in stream {
                guard !Task.isCancelled, let self else { return }
                self.apply(snapshot)
            }
        }
    }

    private func apply(_ snapshot: UserProfileSnapshot) {
        switch snapshot {
        case .failed(let code):
            // Keep any already-resolved avatarURL: a transient listener error
            // must not blank a picture that was fine a snapshot ago.
            state = .failed(code: code)
        case .loaded(let profile):
            state = .loaded(profile)
            resolveAvatarIfNeeded(profile?.avatarPath)
        }
    }

    private func resolveAvatarIfNeeded(_ path: String?) {
        guard path != resolvedAvatarPath else { return }
        resolvedAvatarPath = path
        avatarResolution?.cancel()
        avatarURL = nil
        guard let path, let repository else { return }
        avatarResolution = Task { [weak self] in
            let url = await repository.avatarDownloadURL(for: path)
            guard !Task.isCancelled, let self else { return }
            // Commit only if the path is still current — a stale resolution
            // that lands after the member changed avatars must lose.
            if self.resolvedAvatarPath == path {
                self.avatarURL = url
            }
        }
    }
}
