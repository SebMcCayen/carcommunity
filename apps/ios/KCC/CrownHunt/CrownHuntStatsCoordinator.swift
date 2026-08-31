import Foundation
import Observation

/// UI-facing state of the Crown-Hunt hub stats + season board — the iOS port of
/// Android's `CrownStatsUiState`, with the empty and unavailable cases lifted
/// into the state so the SwiftUI view stays a dumb switch.
enum CrownHuntStatsUiState: Equatable, Sendable {
    /// Waiting for the first read (initial entry, or after a reload).
    case loading
    /// The member has never collected a crown AND the board is empty — the
    /// "collect your first crown" prompt, not a wall of zeros.
    case empty(seasonId: String)
    /// The viewer's stats (nil until they collect) + this season's board.
    case loaded(CrownStatsData)
    /// No repository in this build (config-less: no GoogleService-Info.plist).
    case unavailable
    /// The read failed. `code` is the bare Firestore status name when one was
    /// available; the screen renders a generic retryable message either way.
    case failed(code: String?)
}

/// Orchestrates the read-only stats + season board: subscribes the repository's
/// one-shot read and folds its emission into ``CrownHuntStatsUiState``. Pure
/// Swift (no Firebase/SwiftUI types) so it is unit-testable with a fake — the
/// iOS counterpart of Android's stats wiring in `CrownHuntRoute`.
@MainActor
@Observable
final class CrownHuntStatsCoordinator {
    /// Nil in a config-less build → the surface shows ``CrownHuntStatsUiState/unavailable``.
    private let repository: CrownHuntStatsRepository?
    private let uid: String?
    /// While disabled, any signed-in non-suspended user passes (the current
    /// launch posture). Mirrors Android's `passesMemberGate` on `CrownHuntRoute`.
    private let passesMemberGate: Bool

    @ObservationIgnored
    nonisolated(unsafe) private var subscription: Task<Void, Never>?

    private(set) var state: CrownHuntStatsUiState = .loading

    init(repository: CrownHuntStatsRepository?, uid: String?, passesMemberGate: Bool) {
        self.repository = repository
        self.uid = uid
        self.passesMemberGate = passesMemberGate
    }

    deinit {
        subscription?.cancel()
    }

    /// Begins the read on first appearance. Idempotent: a second call keeps the
    /// live subscription and its current state instead of flashing back to
    /// loading.
    func start() {
        guard subscription == nil else { return }
        subscribe()
    }

    /// The "try again" affordance — tears the current read down, returns to
    /// ``CrownHuntStatsUiState/loading``, and re-reads from scratch.
    func reload() {
        subscribe()
    }

    private func subscribe() {
        subscription?.cancel()
        // No repository / no session / gated out → a terminal state, no read.
        guard let repository, let uid, passesMemberGate else {
            state = .unavailable
            return
        }
        state = .loading
        let stream = repository.stats(uid: uid)
        subscription = Task { [weak self] in
            for await snapshot in stream {
                guard !Task.isCancelled, let self else { return }
                self.apply(snapshot)
            }
        }
    }

    private func apply(_ snapshot: CrownStatsSnapshot) {
        switch snapshot {
        case .failed(let code):
            state = .failed(code: code)
        case .loaded(let data):
            if data.personal == nil, data.board.rows.isEmpty {
                state = .empty(seasonId: data.board.seasonId)
            } else {
                state = .loaded(data)
            }
        }
    }
}
