import Foundation
import Observation

/// UI-facing state of the published-events list — the iOS port of Android's
/// `EventsListState`, with the empty case lifted into the state itself
/// (Android derives it in `EventsListScreen` from `Loaded(emptyList())`;
/// modelling it here keeps the SwiftUI view a dumb switch).
enum EventsListUiState: Equatable, Sendable {
    /// Waiting for the first snapshot (initial entry, or after a reload).
    case loading
    /// The first snapshot arrived and holds no published events.
    case empty
    /// Published events, soonest first.
    case loaded([EventSummary])
    /// The listener failed. `code` is the bare Firestore status name when one
    /// was available (`FAILED_PRECONDITION`, `PERMISSION_DENIED`,
    /// `UNAVAILABLE`, …) — carried for diagnostics parity with Android's
    /// `EventsListState.Error`; the screen renders a generic retryable
    /// message either way.
    case failed(code: String?)
}

/// Orchestrates the read-only published-events list: subscribes the
/// repository stream and folds its emissions into ``EventsListUiState``.
/// Pure Swift (no Firebase/SwiftUI types) so it is unit-testable with a fake
/// repository — the iOS counterpart of Android's events-list wiring in
/// `EventsRoute` (`observePublishedEvents` + `reloadKey`).
@MainActor
@Observable
final class EventsCoordinator {
    private let repository: EventsRepository
    /// The live stream-consuming task. `nonisolated(unsafe)` so the
    /// nonisolated deinit can cancel it — every mutation happens on the main
    /// actor, and by the time deinit runs no other reference exists, so the
    /// unguarded access cannot race.
    @ObservationIgnored
    nonisolated(unsafe) private var subscription: Task<Void, Never>?

    private(set) var state: EventsListUiState = .loading

    init(repository: EventsRepository) {
        self.repository = repository
    }

    deinit {
        subscription?.cancel()
    }

    /// Begins observing on first appearance. Idempotent: a second call (e.g.
    /// SwiftUI re-running `.task` after a tab switch) keeps the live
    /// subscription and its current state instead of flashing back to
    /// loading.
    func start() {
        guard subscription == nil else { return }
        subscribe()
    }

    /// The "try again" affordance — Android's `reloadKey++`: tears the
    /// current listener down, returns to ``EventsListUiState/loading``, and
    /// re-subscribes from scratch.
    func reload() {
        subscribe()
    }

    private func subscribe() {
        subscription?.cancel()
        state = .loading
        subscription = Task { [repository] in
            for await snapshot in repository.publishedEvents() {
                guard !Task.isCancelled else { return }
                apply(snapshot)
            }
        }
    }

    private func apply(_ snapshot: EventsListSnapshot) {
        switch snapshot {
        case .failed(let code):
            state = .failed(code: code)
        case .loaded(let events):
            state = events.isEmpty ? .empty : .loaded(events)
        }
    }
}
