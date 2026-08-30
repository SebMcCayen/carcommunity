import Foundation
import Observation

/// UI-facing state of the drives-history list — the iOS port of Android's
/// `DrivesState`, with the empty case lifted into the state itself (Android
/// derives it in `DrivesListScreen` from `Loaded(emptyList())`) and the
/// config-less build modelled explicitly (``unavailable``), the same shape
/// as `GarageUiState` / ``EventsListUiState``.
enum DrivesUiState: Equatable, Sendable {
    /// Waiting for the first snapshot (initial entry, or after a reload).
    case loading
    /// No repository (Firebase unconfigured in this build) or no signed-in
    /// uid — the history cannot be observed.
    case unavailable
    /// The first snapshot arrived and holds no saved drives.
    case empty
    /// The owner's saved drives, newest first.
    case loaded([SavedDrive])
    /// The listener failed. `code` is the bare Firestore status name when
    /// one was available — carried for diagnostics parity with Android's
    /// `DrivesState.Error`; the panel renders a generic retryable message
    /// either way.
    case failed(code: String?)
}

/// Orchestrates the read-only drives history: subscribes the repository's
/// owner rides stream, folds its emissions into ``DrivesUiState``, and
/// lazily resolves the denormalized car-photo paths to download URLs for
/// rendering — the same once-per-path + negative-cache resolution as
/// `GarageCoordinator`'s cover photos. Pure Swift (no Firebase/SwiftUI
/// types) so it is unit-testable with a fake repository. Deletion (Android's
/// `DrivesCoordinator.delete`) goes through the `drives-delete` callable and
/// arrives with the recording slice.
@MainActor
@Observable
final class DrivesCoordinator {
    private let repository: DrivesRepository?
    private let uid: String?
    /// Live tasks. `nonisolated(unsafe)` so the nonisolated deinit can
    /// cancel them — every mutation happens on the main actor, and by the
    /// time deinit runs no other reference exists, so the unguarded access
    /// cannot race (same pattern as `EventsCoordinator` /
    /// `GarageCoordinator`).
    @ObservationIgnored
    nonisolated(unsafe) private var subscription: Task<Void, Never>?
    @ObservationIgnored
    nonisolated(unsafe) private var imageResolutions: [String: Task<Void, Never>] = [:]
    /// Every path a resolution was ATTEMPTED for — the negative cache. A
    /// failed resolution stays attempted (its card keeps the placeholder),
    /// so later snapshots never re-pay the Storage round-trip for a path
    /// that already failed; ``reload()`` clears the failures so the explicit
    /// user action retries them.
    @ObservationIgnored
    private var attemptedImagePaths: Set<String> = []

    private(set) var state: DrivesUiState
    /// Resolved car-photo URLs by Storage path. A path that failed to
    /// resolve is absent — its card keeps the placeholder (cosmetic, never
    /// an error state). Resolved at most once per path (success or failure),
    /// so every later snapshot of an unchanged history re-pays nothing.
    private(set) var imageURLs: [String: URL] = [:]

    /// - Parameters:
    ///   - repository: nil when Firebase is not configured in this build.
    ///   - uid: the signed-in user's uid; nil when there is no session
    ///     (the panel should not be reachable then, but never crash).
    init(repository: DrivesRepository?, uid: String?) {
        self.repository = repository
        self.uid = uid
        self.state = (repository == nil || uid == nil) ? .unavailable : .loading
    }

    deinit {
        subscription?.cancel()
        for task in imageResolutions.values {
            task.cancel()
        }
    }

    /// Begins observing on first appearance. Idempotent: a second call (e.g.
    /// SwiftUI re-running `.task` after a tab switch) keeps the live
    /// subscription and its current state instead of flashing back to
    /// loading. No-op when unavailable.
    func start() {
        guard subscription == nil, repository != nil, uid != nil else { return }
        subscribe()
    }

    /// The "try again" affordance — tears the current listener down, returns
    /// to ``DrivesUiState/loading``, and re-subscribes from scratch (the
    /// same semantics as `EventsCoordinator.reload()`). No-op when
    /// unavailable.
    func reload() {
        guard repository != nil, uid != nil else { return }
        subscribe()
    }

    private func subscribe() {
        subscription?.cancel()
        state = .loading
        // Retry FAILED photo resolutions on the explicit re-subscribe (the
        // retry affordance); successful URLs stay cached, and paths still IN
        // FLIGHT stay attempted so a quick reload never starts a duplicate
        // downloadURL() for the same path.
        attemptedImagePaths = Set(imageURLs.keys).union(imageResolutions.keys)
        // The stream is created HERE, not inside the task, so the listener
        // is attached synchronously — reload() has re-subscribed by the time
        // it returns (see EventsCoordinator.subscribe).
        guard let repository, let uid else { return }
        let stream = repository.drives(uid: uid)
        // `self` is captured weakly so the long-lived stream task never
        // retains the coordinator (see EventsCoordinator.subscribe).
        subscription = Task { [weak self] in
            for await snapshot in stream {
                guard !Task.isCancelled, let self else { return }
                self.apply(snapshot)
            }
        }
    }

    private func apply(_ snapshot: DrivesSnapshot) {
        switch snapshot {
        case .failed(let code):
            // Keep any already-resolved imageURLs: a transient listener
            // error must not blank photos that were fine a snapshot ago.
            state = .failed(code: code)
        case .loaded(let drives):
            state = drives.isEmpty ? .empty : .loaded(drives)
            resolveImagesIfNeeded(for: drives)
        }
    }

    /// Kicks off a one-time URL resolution for each car-photo path not yet
    /// attempted. A path is attempted at most ONCE — success or failure —
    /// until ``reload()``, so a missing/unreachable photo never turns every
    /// listener emission into a Storage round-trip. Paths that leave the
    /// history keep their cached URL — the maps only grow with DISTINCT
    /// photo paths ever seen, and dropping a still-listed drive's URL on the
    /// next emission would be a regression, so eviction is complexity
    /// without a payoff (the garage made the same call).
    private func resolveImagesIfNeeded(for drives: [SavedDrive]) {
        guard let repository else { return }
        for path in drives.compactMap(\.carImagePath) {
            guard !attemptedImagePaths.contains(path) else { continue }
            attemptedImagePaths.insert(path)
            imageResolutions[path] = Task { [weak self] in
                let url = await repository.imageDownloadURL(for: path)
                guard !Task.isCancelled, let self else { return }
                self.imageResolutions[path] = nil
                if let url {
                    self.imageURLs[path] = url
                }
            }
        }
    }
}
