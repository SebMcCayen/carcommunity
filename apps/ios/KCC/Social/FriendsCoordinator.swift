import Foundation
import Observation

/// UI-facing status of the friends snapshot (list + pending requests) —
/// Android's `FriendsStatus`.
enum FriendsStatus: Equatable, Sendable {
    case loading
    /// `points` is each friend's PUBLIC Crown Points balance (uid → balance),
    /// overlaid onto the list after it loads. A uid ABSENT here has no read
    /// yet, no wallet, or a failed read — the screen renders that as 0.
    case loaded(
        friends: [FriendSummary],
        incoming: [FriendRequestSummary],
        outgoing: [FriendRequestSummary],
        points: [String: Int64]
    )
    /// The snapshot failed to load. Carries the mapped error so the screen
    /// can surface the specific auth/member-gating message (via its
    /// `friends.*` mapping) rather than a single generic load-error string.
    case error(FriendActionError)
}

/// Sub-state of the "add friend by nickname" flow — Android's
/// `AddFriendState`.
enum AddFriendState: Equatable, Sendable {
    case idle
    case working
    /// The nickname was ambiguous — the screen shows a member picker.
    case chooser(candidates: [FriendUser])
    case error(FriendActionError)
    /// The request landed. `nowFriends` is true when it auto-accepted an
    /// inbound one.
    case sent(nowFriends: Bool)
}

/// Orchestrates the friends screen (load + add + respond + cancel + remove) —
/// the iOS port of Android's `FriendsCoordinator`. Pure Swift so it is
/// unit-testable with a fake repository. There is no live listener, so every
/// successful mutation re-fetches the snapshot via ``load()``.
///
/// NOTE Android additionally reports the two genuine-fault categories
/// (`Generic` / `TemporarilyUnavailable`) through the shared
/// `errors-reportClientError` pipeline; iOS has no client-error reporter yet,
/// so that seam is deferred with it (the mapped categories are identical, so
/// wiring it later is additive).
@MainActor
@Observable
final class FriendsCoordinator {
    private let repository: FriendsRepository
    private let pointsRepository: FriendPointsRepository?

    private(set) var status: FriendsStatus = .loading
    private(set) var add: AddFriendState = .idle

    /// Failure of a row action (accept/decline/cancel/remove) — surfaced
    /// once, then cleared. Success is reflected by the reloaded snapshot.
    private(set) var actionError: FriendActionError?

    /// Keys of rows whose accept/decline/cancel/remove callable is currently
    /// in flight. Guards against overlapping invocations from rapid taps and
    /// lets the UI disable that row's action buttons while it runs.
    ///
    /// Every key is NAMESPACED by action (``respondBusyKey(_:)`` /
    /// ``cancelBusyKey(_:)`` / ``removeBusyKey(_:)``): the ids come from
    /// three different spaces — a requestId, a recipient uid, and a friend
    /// uid — and as bare strings in one Set they could collide and mark the
    /// wrong row busy. The screen builds its lookup keys with the SAME
    /// helpers, so the two never drift.
    private(set) var busyRows: Set<String> = []

    /// Monotonic generation of the loaded snapshot, so the best-effort points
    /// overlay is applied only when the list it decorated is still current —
    /// a mutation that reloaded the snapshot in the meantime wins, and stale
    /// points can never clobber a newer list (Android's `===` identity
    /// check).
    private var loadGeneration = 0

    init(repository: FriendsRepository, pointsRepository: FriendPointsRepository? = nil) {
        self.repository = repository
        self.pointsRepository = pointsRepository
    }

    func load() async {
        switch await repository.list() {
        case .loaded(let data):
            loadGeneration += 1
            // Publish the list FIRST so it renders immediately, then overlay
            // each friend's Crown Points as a best-effort second step that
            // can never fail or delay the list.
            status = .loaded(
                friends: data.friends,
                incoming: data.incoming,
                outgoing: data.outgoing,
                points: [:]
            )
            await overlayPoints(for: data, generation: loadGeneration)
        case .failed(let error):
            loadGeneration += 1
            status = .error(error)
        }
    }

    /// Fills in the friends' public Crown Points balances after the list has
    /// been published. Deliberately SILENT on failure: the balances are a
    /// decorative overlay, never load-bearing. No-ops when no points
    /// repository is wired or the list is empty.
    private func overlayPoints(for data: FriendsData, generation: Int) async {
        guard let pointsRepository, !data.friends.isEmpty else { return }
        let balances = await pointsRepository.balances(for: data.friends.map(\.uid))
        guard !balances.isEmpty, generation == loadGeneration else { return }
        status = .loaded(
            friends: data.friends,
            incoming: data.incoming,
            outgoing: data.outgoing,
            points: balances
        )
    }

    func sendRequest(nickname: String) async {
        let trimmed = nickname.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            add = .error(.invalid)
            return
        }
        await runSend { [repository] in await repository.sendRequest(nickname: trimmed) }
    }

    /// Resolves an ambiguous nickname by re-sending to the chosen candidate.
    func chooseCandidate(uid: String) async {
        await runSend { [repository] in await repository.sendRequest(toUid: uid) }
    }

    private func runSend(_ action: @Sendable () async -> SendRequestResult) async {
        guard add != .working else { return }
        add = .working
        switch await action() {
        case .requested:
            add = .sent(nowFriends: false)
        case .nowFriends:
            add = .sent(nowFriends: true)
        case .ambiguous(let candidates):
            add = .chooser(candidates: candidates)
        case .failed(let error):
            add = .error(error)
        }
        // A landed request/friendship changes the pending lists — refresh, so
        // the new outgoing "waiting for a reply" row appears immediately.
        if case .sent = add { await load() }
    }

    func accept(requestId: String) async {
        await respond(requestId: requestId, accept: true)
    }

    func decline(requestId: String) async {
        await respond(requestId: requestId, accept: false)
    }

    private func respond(requestId: String, accept: Bool) async {
        let key = Self.respondBusyKey(requestId)
        // Ignore a second tap on a row whose accept/decline is already
        // running.
        guard !busyRows.contains(key) else { return }
        busyRows.insert(key)
        actionError = nil
        defer { busyRows.remove(key) }
        switch await repository.respond(requestId: requestId, accept: accept) {
        case .accepted, .declined:
            await load()
        case .failed(let error):
            actionError = error
            // The request may be gone/handled server-side — resync so the
            // stale row disappears rather than lingering.
            await load()
        }
    }

    /// Withdraws the caller's OWN pending outgoing request to `toUid` (the
    /// "Cancel request" affordance on an outgoing row). The callable is
    /// idempotent, so success always resyncs the snapshot; a mapped failure
    /// also resyncs so a request already handled server-side does not linger.
    func cancel(toUid: String) async {
        let key = Self.cancelBusyKey(toUid)
        guard !busyRows.contains(key) else { return }
        busyRows.insert(key)
        actionError = nil
        defer { busyRows.remove(key) }
        switch await repository.cancelRequest(toUid: toUid) {
        case .cancelled:
            await load()
        case .failed(let error):
            actionError = error
            await load()
        }
    }

    func remove(friendUid: String) async {
        let key = Self.removeBusyKey(friendUid)
        // Ignore a second tap on a friend whose removal is already running.
        guard !busyRows.contains(key) else { return }
        busyRows.insert(key)
        actionError = nil
        defer { busyRows.remove(key) }
        switch await repository.remove(friendUid: friendUid) {
        case .removed:
            await load()
        case .failed(let error):
            actionError = error
        }
    }

    /// Clears the add-friend sub-state (e.g. after dismissing a
    /// picker/result).
    func resetAdd() {
        add = .idle
    }

    func clearActionError() {
        actionError = nil
    }

    /// `busyRows` key namespacing — accept/decline are keyed by requestId,
    /// cancel by the recipient uid, remove by the friend uid: three DIFFERENT
    /// id spaces that must not collide in the single Set. The screen builds
    /// its lookup keys with these same helpers.
    /// Pure string builders — nonisolated so the screen (and tests) can key
    /// rows without hopping onto the main actor.
    nonisolated static func respondBusyKey(_ requestId: String) -> String { "respond:\(requestId)" }
    nonisolated static func cancelBusyKey(_ toUid: String) -> String { "cancel:\(toUid)" }
    nonisolated static func removeBusyKey(_ friendUid: String) -> String { "remove:\(friendUid)" }
}
