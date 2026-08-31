import Foundation
import Observation

/// UI-facing state of the badge wall — the iOS counterpart of how Android's
/// profile route folds the earned-badge listener and the `getMyProgress`
/// callable together before handing `ProfileBadgesSection` a `BadgeShowcase`.
///
/// The five states match the feature spec. Both ``empty`` and ``loaded`` carry
/// the full folded wall: the badge wall ALWAYS renders every rung of every
/// ladder plus the standalone milestones (earned lit, unearned greyed), so a
/// member with nothing earned still gets the complete "here is what you can
/// earn" menu WITH progress bars — that is exactly the case ``empty``
/// distinguishes (settled, zero earned) from ``loaded`` (settled, ≥1 earned).
enum BadgesUiState: Equatable, Sendable {
    /// Waiting for the first earned-badges snapshot (initial entry or reload).
    case loading
    /// No repository (Firebase unconfigured in this build) or no signed-in
    /// uid — the wall cannot be observed.
    case unavailable
    /// The listener settled and the member holds no catalog badge yet. Carries
    /// the full locked wall (with any resolved progress bars) so the screen
    /// shows the goal menu rather than a blank panel.
    case empty(BadgeShowcase)
    /// The member holds at least one badge; the full wall, earned + unearned.
    case loaded(BadgeShowcase)
    /// The earned-badges listener failed. `code` is the bare Firestore status
    /// name when one was available — diagnostics parity with
    /// ``EventsListUiState/failed(code:)``; the screen renders a generic
    /// retryable message either way. A PROGRESS-callable failure never lands
    /// here: it only drops the bars.
    case failed(code: String?)
}

/// Orchestrates the badge wall: subscribes the earned-badges listener, fires
/// the owner-only progress callable once, and MERGES both into the full
/// ``BadgeShowcase`` (every ladder rung + milestone, earned and unearned, with
/// progress bars wherever a counter is known). Pure Swift (no Firebase/SwiftUI
/// types) so it is unit-testable with a fake repository — the same shape as
/// ``EventsCoordinator`` / ``GarageCoordinator``.
@MainActor
@Observable
final class BadgesCoordinator {
    private let repository: BadgesRepository?
    private let uid: String?

    /// Live tasks. `nonisolated(unsafe)` so the nonisolated deinit can cancel
    /// them — every mutation happens on the main actor, and by the time deinit
    /// runs no other reference exists, so the unguarded access cannot race
    /// (same pattern as ``EventsCoordinator`` / ``GarageCoordinator``).
    @ObservationIgnored
    nonisolated(unsafe) private var subscription: Task<Void, Never>?
    @ObservationIgnored
    nonisolated(unsafe) private var progressTask: Task<Void, Never>?

    /// The latest earned-badges snapshot folded so far, held so a later
    /// progress result can re-merge against it. Nil until the first snapshot.
    @ObservationIgnored
    private var latestBadges: [Badge]?
    /// The latest counters, or ``BadgeCounters/none`` until the callable
    /// resolves (or when it is unavailable / fails — a bar-less wall).
    @ObservationIgnored
    private var latestCounters: BadgeCounters = .none

    private(set) var state: BadgesUiState

    /// - Parameters:
    ///   - repository: nil when Firebase is not configured in this build.
    ///   - uid: the signed-in member's uid; nil when there is no session. When
    ///     omitted, the repository's own ``BadgesRepository/currentUserId()``
    ///     is used, matching how the events/garage features resolve identity
    ///     without the shell threading it in.
    init(repository: BadgesRepository?, uid: String? = nil) {
        self.repository = repository
        let resolvedUid = uid ?? repository?.currentUserId()
        self.uid = resolvedUid
        self.state = (repository == nil || resolvedUid == nil) ? .unavailable : .loading
    }

    deinit {
        subscription?.cancel()
        progressTask?.cancel()
    }

    /// Begins observing on first appearance. Idempotent: a second call (e.g.
    /// SwiftUI re-running `.task` after a tab switch) keeps the live
    /// subscription and its current state instead of flashing back to loading.
    /// No-op when unavailable.
    func start() {
        guard subscription == nil, repository != nil, uid != nil else { return }
        subscribe()
    }

    /// The "try again" affordance — tears the current listener down, returns
    /// to ``BadgesUiState/loading``, and re-subscribes from scratch, re-firing
    /// the progress callable too (Android's `reloadKey++`). No-op when
    /// unavailable.
    func reload() {
        guard repository != nil, uid != nil else { return }
        subscribe()
    }

    private func subscribe() {
        subscription?.cancel()
        progressTask?.cancel()
        state = .loading
        latestBadges = nil
        latestCounters = .none
        guard let repository, let uid else { return }

        // Fire the owner-only progress callable ONCE per subscribe. Its result
        // only enriches the wall with bars, so it is best-effort: a nil (or a
        // late arrival) simply re-merges with absent counters, never a failure.
        progressTask = Task { [weak self] in
            let counters = await repository.fetchMyProgress()
            guard !Task.isCancelled, let self, let counters else { return }
            self.applyCounters(counters)
        }

        // The stream is created HERE, not inside the task, so the listener is
        // attached synchronously — reload() has re-subscribed by the time it
        // returns (see EventsCoordinator.subscribe). `self` is captured weakly
        // so the long-lived stream task never retains the coordinator.
        let stream = repository.observeBadges(uid: uid)
        subscription = Task { [weak self] in
            for await snapshot in stream {
                guard !Task.isCancelled, let self else { return }
                self.apply(snapshot)
            }
        }
    }

    private func apply(_ snapshot: BadgesSnapshot) {
        switch snapshot {
        case .failed(let code):
            state = .failed(code: code)
        case .loaded(let badges):
            latestBadges = badges
            rebuild()
        }
    }

    private func applyCounters(_ counters: BadgeCounters) {
        latestCounters = counters
        // A listener failure must stick: `latestBadges` is not cleared on a
        // failed emission, so a late progress result must not resurrect a
        // stale wall over the error.
        if case .failed = state { return }
        // Only re-merge once we actually have earned badges to fold against;
        // otherwise the counters wait and are picked up by the first snapshot.
        guard latestBadges != nil else { return }
        rebuild()
    }

    /// Merges the held awards and the counters into the full wall and settles
    /// on ``loaded`` or ``empty`` by whether any catalog badge is held.
    private func rebuild() {
        guard let badges = latestBadges else { return }
        let showcase = BadgeShowcase.from(badges: badges, counters: latestCounters)
        state = showcase.hasAnyBadge ? .loaded(showcase) : .empty(showcase)
    }
}
