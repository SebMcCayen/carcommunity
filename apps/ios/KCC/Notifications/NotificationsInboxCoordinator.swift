import Foundation
import Observation

/// UI-facing state of the notification inbox — the iOS fold of Android's
/// `NotificationsState`, with the empty case lifted into the state and the
/// config-less build modelled explicitly (``unavailable``), the same shape as
/// ``GarageUiState`` / ``EventsListUiState``.
enum NotificationsInboxUiState: Equatable, Sendable {
    /// Waiting for the first snapshot (initial entry, or after a reload).
    case loading
    /// No repository (Firebase unconfigured in this build) or no signed-in
    /// uid — the inbox cannot be observed.
    case unavailable
    /// The first snapshot arrived and holds no notifications.
    case empty
    /// The caller's notifications, newest first.
    case loaded([AppNotification])
    /// The listener failed. `code` is the bare Firestore status name when one
    /// was available — carried for diagnostics parity with
    /// ``EventsListUiState/failed(code:)``; the screen renders a generic
    /// retryable message either way.
    case failed(code: String?)
}

/// UI-facing status of a mark-read / mark-all-read action — Android's
/// `MarkReadStatus`, extended with the failing contract code so the screen can
/// diagnose parity with the events/garage save-failure pattern.
enum MarkReadStatus: Equatable, Sendable {
    case idle
    case working
    /// The callable failed. `code` is the contract error code
    /// (``KccFunctionsErrorCode``) when one was available.
    case failed(code: KccFunctionsErrorCode?)
}

/// Orchestrates the read-only notification inbox: subscribes the repository's
/// items stream, folds its emissions into ``NotificationsInboxUiState``,
/// derives the unread count, and drives the mark-read / mark-all-read
/// callables (single-flight). Pure Swift (no Firebase/SwiftUI types) so it is
/// unit-testable with a fake repository — the iOS counterpart of Android's
/// `NotificationsCoordinator` + the inbox wiring in `NotificationsRoute`.
///
/// Opening the inbox best-effort stamps the last-seen marker (``markSeen()``)
/// so the red dot clears WITHOUT marking any row read — the notifications
/// mirror of the community channel's markRead-on-open. Per-row read state
/// stays a per-item tap / the explicit "mark all read".
@MainActor
@Observable
final class NotificationsInboxCoordinator {
    private let repository: NotificationsRepository?
    private let uid: String?
    /// The live stream-consuming task. `nonisolated(unsafe)` so the
    /// nonisolated deinit can cancel it — every mutation happens on the main
    /// actor, and by the time deinit runs no other reference exists, so the
    /// unguarded access cannot race (the same pattern as ``GarageCoordinator``).
    @ObservationIgnored
    nonisolated(unsafe) private var subscription: Task<Void, Never>?

    private(set) var state: NotificationsInboxUiState
    private(set) var markReadStatus: MarkReadStatus = .idle

    /// The number of unread notifications currently loaded — Android's
    /// `Notifications.unreadCount`, gating the "mark all read" affordance.
    /// Zero in any non-loaded state.
    var unreadCount: Int {
        guard case .loaded(let items) = state else { return 0 }
        return Notifications.unreadCount(items)
    }

    /// - Parameters:
    ///   - repository: nil when Firebase is not configured in this build.
    ///   - uid: the signed-in member's uid; nil when there is no session.
    init(repository: NotificationsRepository?, uid: String?) {
        self.repository = repository
        self.uid = uid
        self.state = (repository == nil || uid == nil) ? .unavailable : .loading
    }

    deinit {
        subscription?.cancel()
    }

    /// Begins observing on first appearance. Idempotent: a second call (e.g.
    /// SwiftUI re-running `.task` after a tab switch) keeps the live
    /// subscription and its current state instead of flashing back to loading.
    /// No-op when unavailable. Also best-effort stamps the seen marker.
    func start() {
        guard subscription == nil, repository != nil, uid != nil else { return }
        subscribe()
        markSeen()
    }

    /// The "try again" affordance — tears the current listener down, returns to
    /// ``NotificationsInboxUiState/loading``, and re-subscribes from scratch
    /// (the same semantics as ``GarageCoordinator/reload()``). No-op when
    /// unavailable.
    func reload() {
        guard repository != nil, uid != nil else { return }
        subscribe()
    }

    /// Marks one notification read through the repository callable, tracking
    /// ``markReadStatus`` (single-flight — Android's `execute` gate). The
    /// inbox list itself updates from the next listener snapshot, not
    /// optimistically, so a refused mark-read never leaves a row falsely
    /// styled read.
    func markRead(notificationId: String) async {
        await execute { repository in
            try await repository.markRead(notificationId: notificationId)
        }
    }

    /// Marks every unread notification read (`notifications.markAllRead`),
    /// tracking ``markReadStatus``.
    func markAllRead() async {
        await execute { repository in
            try await repository.markAllRead()
        }
    }

    /// Clears a ``MarkReadStatus/failed(code:)`` back to idle — called when the
    /// screen dismisses the failure notice (Android's `reset`). A WORKING
    /// status is deliberately not resettable, so it cannot defeat the
    /// single-flight guard.
    func resetMarkReadStatus() {
        guard markReadStatus != .working else { return }
        markReadStatus = .idle
    }

    private func execute(_ action: @escaping (NotificationsRepository) async throws -> Void) async {
        guard markReadStatus != .working, let repository else { return }
        markReadStatus = .working
        do {
            try await action(repository)
            markReadStatus = .idle
        } catch is CancellationError {
            markReadStatus = .idle
        } catch let error as KccFunctionsError {
            markReadStatus = .failed(code: error.code)
        } catch {
            markReadStatus = .failed(code: nil)
        }
    }

    /// Best-effort seen-marker stamp on open — clears the red dot without
    /// marking rows read. Failures are swallowed: the dot simply persists
    /// until the next open (Android's `markSeen` on the inbox route).
    private func markSeen() {
        guard let repository else { return }
        Task { [weak self] in
            _ = self
            try? await repository.markSeen()
        }
    }

    private func subscribe() {
        subscription?.cancel()
        state = .loading
        // The stream is created HERE, not inside the task, so the listener is
        // attached synchronously — reload() has re-subscribed by the time it
        // returns (see EventsCoordinator.subscribe).
        guard let repository, let uid else { return }
        let stream = repository.notifications(uid: uid)
        // `self` is captured weakly so the long-lived stream task never retains
        // the coordinator (see GarageCoordinator.subscribe).
        subscription = Task { [weak self] in
            for await snapshot in stream {
                guard !Task.isCancelled, let self else { return }
                self.apply(snapshot)
            }
        }
    }

    private func apply(_ snapshot: NotificationsSnapshot) {
        switch snapshot {
        case .failed(let code):
            state = .failed(code: code)
        case .loaded(let items):
            state = items.isEmpty ? .empty : .loaded(items)
        }
    }
}
