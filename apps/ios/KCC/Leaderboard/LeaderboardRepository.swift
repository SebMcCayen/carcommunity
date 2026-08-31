import Foundation

/// One emission of the leaderboard listener for a single scope.
///
/// Like ``EventsListSnapshot``, a repository stream only ever emits SETTLED
/// results (a board or a failure); the coordinator supplies the loading state
/// before the first emission — the same split Android gets from
/// `collectAsState(initial = Loading)`. A MISSING document (a month with no
/// board yet, or the very first run) is NOT a failure: it is a valid EMPTY
/// board, so the repository yields ``loaded`` with every category present but
/// empty and the screen renders its friendly per-category empty state.
enum LeaderboardSnapshot: Equatable, Sendable {
    /// The listener failed. `code` is the bare Firestore status name when one
    /// was available (`PERMISSION_DENIED` for a rules denial, `UNAVAILABLE`
    /// when offline, …) — a stable, PII-safe diagnosis, never exception text
    /// (which can embed the failing path and the project id). The same rule as
    /// ``EventsListSnapshot/failed(code:)`` and Android's `firestoreCode()`.
    case failed(code: String?)

    /// A fresh board for the requested scope: the scope's categories in render
    /// order (each already server-ranked and name/avatar-resolved), built by
    /// ``LeaderboardBoard/board(scope:rawByCategory:viewerUid:)``. Categories
    /// with no rows are present-but-empty, so the screen never drops a section.
    case loaded([LeaderboardCategoryBoard])
}

/// Read-only access to the precomputed social leaderboard — the iOS port of
/// Android's `LeaderboardRepository.kt`.
///
/// Firebase-free protocol so the coordinator and screen are unit-testable with
/// fakes. The one implementation (``FirebaseLeaderboardRepository``) is a
/// single rules-gated Firestore listener on `leaderboards/{scope}` — the
/// collection exposes no callable and its write rule is `false`
/// (firebase/firestore.rules: `allow read: if isActiveMember()`, `allow write:
/// if false`), so there is nothing to write and nothing for the client to
/// compute beyond the pure mapping in ``LeaderboardBoard``. Names, avatars,
/// ranks, opt-out and deleted-member filtering are all resolved server-side, so
/// a member reads the whole board from this one cheap document.
protocol LeaderboardRepository: AnyObject, Sendable {
    /// Emits the board for `scope`, then re-emits whenever a new snapshot lands
    /// (the board is regenerated hourly, so an open screen refreshes on its
    /// own). `viewerUid` flags the signed-in member's own row where present.
    /// Each call returns a fresh stream backed by its own listener; terminating
    /// the stream (dropping the iteration) detaches the listener.
    func observeBoard(scope: LeaderboardScope, viewerUid: String?) -> AsyncStream<LeaderboardSnapshot>

    /// Resolves a stored Storage avatar path to a download URL for the podium
    /// / list avatars, or nil when there is no avatar or resolution failed
    /// (the placeholder renders) — the same seam
    /// ``UserProfileRepository/avatarDownloadURL(for:)`` provides for the own
    /// profile, mirroring Android's `rememberStorageImageUrl`.
    func avatarDownloadURL(for avatarPath: String) async -> URL?

    /// The signed-in user's uid, or nil with no session — so the viewer's own
    /// row is highlighted where it appears. The leaderboard feature is
    /// self-contained (the shell passes no identity), so the repository, which
    /// already owns the Firebase seam, answers it, exactly like
    /// ``EventsRepository/currentUserId()``.
    func currentUserId() -> String?
}
