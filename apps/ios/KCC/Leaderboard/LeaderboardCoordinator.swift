import Foundation
import Observation

/// UI-facing state of the leaderboard read for the selected scope — the iOS
/// counterpart of Android's `LeaderboardUiState`, with two cases the Android
/// route derives elsewhere lifted into the state itself so the SwiftUI view
/// stays a dumb switch: ``unavailable`` (config-less build, Android maps a null
/// repository to `Loading`) and ``empty`` (Android renders per-category empty
/// states inside `Loaded`).
enum LeaderboardUiState: Equatable, Sendable {
    /// Waiting for the first snapshot (initial entry, or after a scope change).
    case loading
    /// No repository — Firebase is unconfigured in this build. The screen shows
    /// its placeholder rather than a spinner that never resolves.
    case unavailable
    /// The board arrived but every category is empty (a month with no board
    /// yet, or the very first run). A distinct screen-level state so a fresh
    /// board reads as "nothing here yet", not a broken load.
    case empty
    /// The board's categories in render order, at least one of which has
    /// entries. Categories with no rows are still present so the screen renders
    /// their per-category empty state.
    case loaded([LeaderboardCategoryBoard])
    /// The listener failed. `code` is the bare Firestore status name when one
    /// was available (`PERMISSION_DENIED`, `UNAVAILABLE`, …) — carried for
    /// diagnostics parity with Android's `LeaderboardUiState.Error`; the screen
    /// renders a generic retryable message either way.
    case failed(code: String?)
}

/// Orchestrates the read-only social leaderboard: holds the All-time /
/// This-month scope selection, subscribes the matching board stream, and folds
/// its emissions into ``LeaderboardUiState``. Pure Swift (no Firebase/SwiftUI
/// types) so it is unit-testable with a fake repository — the iOS counterpart
/// of Android's leaderboard wiring in `LeaderboardRoute` (the `scope` state +
/// `observeBoard`).
///
/// The stream re-subscribes when the scope changes (Android re-keys
/// `observeBoard(scope, uid)` on the scope), returning to ``loading`` until the
/// new board's first snapshot lands. `viewerUid` is resolved once from the
/// repository at construction so the viewer's own row is highlighted where it
/// appears.
@MainActor
@Observable
final class LeaderboardCoordinator {
    private let repository: LeaderboardRepository?
    private let viewerUid: String?
    /// The live stream-consuming task. `nonisolated(unsafe)` so the nonisolated
    /// deinit can cancel it — every mutation happens on the main actor, and by
    /// the time deinit runs no other reference exists, so the unguarded access
    /// cannot race (same pattern as ``EventsCoordinator``).
    @ObservationIgnored
    nonisolated(unsafe) private var subscription: Task<Void, Never>?

    /// The selected board. Changing it re-subscribes; the screen drives it from
    /// the All-time / This-month toggle.
    private(set) var scope: LeaderboardScope = .allTime
    /// The category whose podium is shown. Scope switching re-subscribes;
    /// category switching does NOT — every category rides on the one document
    /// read, so it is a pure display choice, driven by the screen's category
    /// picker. Kept valid for the current scope: selecting the all-time-only
    /// `streak` and then switching to This-month falls back to the first
    /// category (Android stacks every category, so it never faces this; the
    /// iOS one-at-a-time picker must).
    private(set) var selectedCategory: LeaderboardCategory = .crownPoints
    private(set) var state: LeaderboardUiState

    /// - Parameter repository: nil when Firebase is not configured in this
    ///   build → the coordinator rests in ``LeaderboardUiState/unavailable``.
    init(repository: LeaderboardRepository?) {
        self.repository = repository
        self.viewerUid = repository?.currentUserId()
        self.state = repository == nil ? .unavailable : .loading
    }

    deinit {
        subscription?.cancel()
    }

    /// Begins observing on first appearance. Idempotent: a second call (e.g.
    /// SwiftUI re-running `.task`) keeps the live subscription and its current
    /// state instead of flashing back to loading. No-op when unavailable.
    func start() {
        guard subscription == nil, repository != nil else { return }
        subscribe()
    }

    /// The categories the current scope publishes, in render order — the
    /// options the screen's category picker offers. Monthly omits the
    /// all-time-only `streak`.
    var availableCategories: [LeaderboardCategory] {
        LeaderboardBoard.categories(for: scope)
    }

    /// Switches the board. A no-op when the scope is unchanged (so tapping the
    /// already-selected tab does not tear a live listener down); otherwise
    /// returns to ``LeaderboardUiState/loading`` and re-subscribes for the new
    /// scope, mirroring Android re-keying `observeBoard` on the scope. If the
    /// selected category is not published by the new scope (`streak` leaving
    /// All-time), it falls back to the first available category so the picker
    /// never rests on a hidden option.
    func select(scope newScope: LeaderboardScope) {
        guard newScope != scope, repository != nil else { return }
        scope = newScope
        if !availableCategories.contains(selectedCategory) {
            selectedCategory = availableCategories.first ?? .crownPoints
        }
        subscribe()
    }

    /// Switches the shown category — a pure display choice (no re-subscribe:
    /// every category is already in the one loaded board). Ignores a category
    /// the current scope does not publish.
    func select(category newCategory: LeaderboardCategory) {
        guard availableCategories.contains(newCategory) else { return }
        selectedCategory = newCategory
    }

    /// The loaded board for the ``selectedCategory``, or nil when the state is
    /// not ``LeaderboardUiState/loaded(_:)`` (or the category is somehow
    /// absent). The screen renders this one category's podium + list.
    var selectedCategoryBoard: LeaderboardCategoryBoard? {
        guard case .loaded(let categories) = state else { return nil }
        return categories.first { $0.category == selectedCategory }
    }

    /// The "try again" affordance — tears the current listener down, returns to
    /// ``LeaderboardUiState/loading``, and re-subscribes the current scope from
    /// scratch. No-op when unavailable.
    func reload() {
        guard repository != nil else { return }
        subscribe()
    }

    private func subscribe() {
        guard let repository else { return }
        subscription?.cancel()
        state = .loading
        // The stream is created HERE, not inside the task, so the listener is
        // attached synchronously — select/reload has re-subscribed by the time
        // it returns, deterministically (and testably), rather than whenever
        // the task first runs (same pattern as EventsCoordinator.subscribe).
        let stream = repository.observeBoard(scope: scope, viewerUid: viewerUid)
        // `self` is captured weakly so the long-lived stream task never retains
        // the coordinator (see EventsCoordinator.subscribe).
        subscription = Task { [weak self] in
            for await snapshot in stream {
                guard !Task.isCancelled, let self else { return }
                self.apply(snapshot)
            }
        }
    }

    private func apply(_ snapshot: LeaderboardSnapshot) {
        switch snapshot {
        case .failed(let code):
            state = .failed(code: code)
        case .loaded(let categories):
            // A board whose every category is empty is the fresh/missing-doc
            // case: a distinct screen-level empty state, not a spinner or a
            // list of empty sections.
            state = categories.allSatisfy { $0.entries.isEmpty }
                ? .empty
                : .loaded(categories)
        }
    }

    /// Resolves a stored avatar path to a download URL for a podium / list
    /// avatar, or nil when there is none — forwards to the repository so the
    /// screen never touches Firebase directly. nil in a config-less build (the
    /// placeholder renders).
    func avatarURL(for avatarPath: String) async -> URL? {
        await repository?.avatarDownloadURL(for: avatarPath)
    }
}
