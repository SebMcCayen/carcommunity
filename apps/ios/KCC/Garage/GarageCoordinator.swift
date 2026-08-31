import Foundation
import Observation

/// UI-facing state of the garage list — the iOS port of Android's
/// `GarageState`, with the empty case lifted into the state (Android derives
/// it in `GarageScreen` from `Loaded(emptyList())`) and the config-less build
/// modelled explicitly (``unavailable``), the same shape as
/// ``EventsListUiState`` / ``ProfileUiState``.
enum GarageUiState: Equatable, Sendable {
    /// Waiting for the first snapshot (initial entry, or after a reload).
    case loading
    /// No repository (Firebase unconfigured in this build) or no signed-in
    /// uid — the garage cannot be observed.
    case unavailable
    /// The first snapshot arrived and holds no vehicles.
    case empty
    /// The user's vehicles, list-sorted.
    case loaded([Vehicle])
    /// The listener failed. `code` is the bare Firestore status name when one
    /// was available — carried for diagnostics parity with
    /// ``EventsListUiState/failed(code:)``; the screen renders a generic
    /// retryable message either way.
    case failed(code: String?)
}

/// UI-facing status of an add-vehicle save — Android's `VehicleSaveStatus`.
enum VehicleSaveStatus: Equatable, Sendable {
    case idle
    case saving
    /// The save resolved; the form closes on observing this.
    case saved
    case failed
}

/// Orchestrates the garage: subscribes the repository's vehicles stream,
/// folds its emissions into ``GarageUiState``, tracks the add-vehicle save
/// status (Android's `GarageCoordinator`), and lazily resolves cover-photo
/// paths to download URLs for rendering (the same path→URL split as
/// ``ProfileCoordinator``'s avatar). Pure Swift (no Firebase/SwiftUI types)
/// so it is unit-testable with a fake repository.
@MainActor
@Observable
final class GarageCoordinator {
    private let repository: VehiclesRepository?
    private let subscriptionRepository: SubscriptionStateRepository?
    private let uid: String?
    /// Live tasks. `nonisolated(unsafe)` so the nonisolated deinit can cancel
    /// them — every mutation happens on the main actor, and by the time
    /// deinit runs no other reference exists, so the unguarded access cannot
    /// race (same pattern as ``EventsCoordinator`` / ``ProfileCoordinator``).
    @ObservationIgnored
    nonisolated(unsafe) private var vehicleSubscription: Task<Void, Never>?
    @ObservationIgnored
    nonisolated(unsafe) private var tierSubscription: Task<Void, Never>?
    @ObservationIgnored
    nonisolated(unsafe) private var imageResolutions: [String: Task<Void, Never>] = [:]
    /// Every path a resolution was ATTEMPTED for — the negative cache. A
    /// failed resolution stays attempted (its card keeps the placeholder), so
    /// later snapshots never re-pay the Storage round-trip for a path that
    /// already failed; ``reload()`` clears the failures so the explicit
    /// user action retries them.
    @ObservationIgnored
    private var attemptedImagePaths: Set<String> = []

    private(set) var state: GarageUiState
    private(set) var saveStatus: VehicleSaveStatus = .idle
    /// Nil is intentionally Community: it covers first load, missing record,
    /// listener failure, malformed data, and config-less builds.
    private(set) var storedSubscription: StoredSubscription?
    var effectiveSubscriptionTier: EffectiveSubscriptionTier {
        storedSubscription?.effectiveTier ?? .community
    }
    var vehicleLimit: Int { effectiveSubscriptionTier.garageVehicleLimit }
    /// Resolved cover-photo URLs by Storage path. A path that failed to
    /// resolve is absent — its card keeps the placeholder (cosmetic, never an
    /// error state). Resolved at most once per path (success or failure), so
    /// every later snapshot of an unchanged garage re-pays nothing.
    private(set) var imageURLs: [String: URL] = [:]

    /// - Parameters:
    ///   - repository: nil when Firebase is not configured in this build.
    ///   - uid: the signed-in member's uid; nil when there is no session
    ///     (the panel should not be reachable then, but never crash).
    init(
        repository: VehiclesRepository?,
        subscriptionRepository: SubscriptionStateRepository? = nil,
        uid: String?
    ) {
        self.repository = repository
        self.subscriptionRepository = subscriptionRepository
        self.uid = uid
        self.state = (repository == nil || uid == nil) ? .unavailable : .loading
        self.storedSubscription = nil
    }

    deinit {
        vehicleSubscription?.cancel()
        tierSubscription?.cancel()
        for task in imageResolutions.values {
            task.cancel()
        }
    }

    /// Begins observing on first appearance. Idempotent: a second call (e.g.
    /// SwiftUI re-running `.task` after a tab switch) keeps the live
    /// subscription and its current state instead of flashing back to
    /// loading. No-op when unavailable.
    func start() {
        subscribeToVehiclesIfNeeded()
        subscribeToTierIfNeeded()
    }

    /// The "try again" affordance — tears the current listener down, returns
    /// to ``GarageUiState/loading``, and re-subscribes from scratch (the same
    /// semantics as ``EventsCoordinator/reload()``). No-op when unavailable.
    func reload() {
        guard repository != nil, uid != nil else { return }
        subscribeToVehicles()
    }

    /// Adds a vehicle through the repository, tracking ``saveStatus`` so the
    /// form can close on success — Android's `GarageCoordinator.save` on its
    /// add path.
    ///
    /// - Returns: the NEW vehicle id minted by garage-addVehicle, or nil when
    ///   the save did not happen (failed, unavailable, or a re-entrant call
    ///   while another save is in flight). A later photo slice needs the id
    ///   to key `vehicleImages/{uid}/{vehicleId}/`.
    @discardableResult
    func addVehicle(_ input: VehicleInput) async -> String? {
        guard saveStatus != .saving else { return nil }
        guard let repository else {
            saveStatus = .failed
            return nil
        }
        saveStatus = .saving
        do {
            let vehicleId = try await repository.addVehicle(input)
            saveStatus = .saved
            return vehicleId
        } catch is CancellationError {
            saveStatus = .idle
            return nil
        } catch {
            saveStatus = .failed
            return nil
        }
    }

    /// Returns ``saveStatus`` to idle — called when the add form opens or
    /// dismisses its error notice (Android's `reset()`). A SAVING status is
    /// deliberately not resettable: re-opening the form while a save is
    /// still in flight (the sheet can be swipe-dismissed mid-save) must not
    /// defeat the re-entrancy guard and let a second add start concurrently.
    func resetSaveStatus() {
        guard saveStatus != .saving else { return }
        saveStatus = .idle
    }

    private func subscribeToVehiclesIfNeeded() {
        guard vehicleSubscription == nil, repository != nil, uid != nil else { return }
        subscribeToVehicles()
    }

    private func subscribeToVehicles() {
        vehicleSubscription?.cancel()
        state = .loading
        // Retry FAILED photo resolutions on the explicit re-subscribe (the
        // retry affordance); successful URLs stay cached, and paths still IN
        // FLIGHT stay attempted so a quick reload never starts a duplicate
        // downloadURL() for the same path.
        attemptedImagePaths = Set(imageURLs.keys).union(imageResolutions.keys)
        // The stream is created HERE, not inside the task, so the listener is
        // attached synchronously — reload() has re-subscribed by the time it
        // returns (see EventsCoordinator.subscribe).
        guard let repository, let uid else { return }
        let stream = repository.vehicles(uid: uid)
        // `self` is captured weakly so the long-lived stream task never
        // retains the coordinator (see EventsCoordinator.subscribe).
        vehicleSubscription = Task { [weak self] in
            for await snapshot in stream {
                guard !Task.isCancelled, let self else { return }
                self.apply(snapshot)
            }
        }
    }

    private func subscribeToTierIfNeeded() {
        guard tierSubscription == nil, let subscriptionRepository, let uid else { return }
        let stream = subscriptionRepository.subscription(uid: uid)
        tierSubscription = Task { [weak self] in
            for await snapshot in stream {
                guard !Task.isCancelled, let self else { return }
                // The repository has already rejected malformed and
                // cross-account data. Nil remains the fail-closed Community
                // state and immediately removes any paid-only affordance.
                self.storedSubscription = snapshot
            }
        }
    }

    private func apply(_ snapshot: GarageSnapshot) {
        switch snapshot {
        case .failed(let code):
            // Keep any already-resolved imageURLs: a transient listener error
            // must not blank pictures that were fine a snapshot ago.
            state = .failed(code: code)
        case .loaded(let vehicles):
            state = vehicles.isEmpty ? .empty : .loaded(vehicles)
            resolveImagesIfNeeded(for: vehicles)
        }
    }

    /// Kicks off a one-time URL resolution for each cover path not yet
    /// attempted. A path is attempted at most ONCE — success or failure —
    /// until ``reload()``, so a missing/unreachable photo never turns every
    /// listener emission into a Storage round-trip. Paths that leave the
    /// garage keep their cached URL — the maps are bounded by the 10-vehicle
    /// cap, so eviction would be complexity without a payoff.
    private func resolveImagesIfNeeded(for vehicles: [Vehicle]) {
        guard let repository else { return }
        for path in vehicles.compactMap(\.imagePath) {
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
