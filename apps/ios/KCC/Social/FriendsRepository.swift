import Foundation

/// Friends access — the iOS port of Android's `FriendsRepository`.
///
/// Every operation is a member-gated europe-west1 callable; there is NO
/// client Firestore listener for the friend graph (it is read via
/// `friend-list` and re-fetched after each mutation), mirroring the
/// callable-only-write model in firebase/firestore.rules. Firebase-free
/// protocol so ``FriendsCoordinator`` is unit-testable with fakes.
protocol FriendsRepository: AnyObject, Sendable {
    /// Fetches the caller's friends + incoming/outgoing pending requests.
    func list() async -> FriendsResult

    /// Sends a request to whoever owns `nickname` (may be ambiguous).
    func sendRequest(nickname: String) async -> SendRequestResult

    /// Sends a request to a specific uid (used to resolve an ambiguity).
    func sendRequest(toUid: String) async -> SendRequestResult

    /// Accepts or declines an incoming request.
    func respond(requestId: String, accept: Bool) async -> RespondResult

    /// Withdraws the caller's own pending outgoing request to `toUid`.
    ///
    /// Addressed by RECIPIENT rather than by request id: the backend derives
    /// the request document from (caller, toUid), so a caller can only ever
    /// cancel a request they themselves sent. Idempotent; an already-handled
    /// (or never sent) request is a no-op.
    func cancelRequest(toUid: String) async -> CancelResult

    /// Removes an established friend. Idempotent.
    func remove(friendUid: String) async -> RemoveResult
}

/// Reads members' PUBLIC Crown Points balances — the same denormalized
/// `pointsLedger/{uid}.balance` the member profile surfaces as its headline
/// number (Android: `FriendPointsRepository`). Firebase-free so
/// ``FriendsCoordinator`` stays unit-testable with a fake.
protocol FriendPointsRepository: AnyObject, Sendable {
    /// Best-effort per-uid balance lookup for the friends list. A uid with no
    /// wallet — or one whose read failed — is simply ABSENT from the returned
    /// map (the screen renders that as 0), so a points read can never fail or
    /// block the friends list itself.
    func balances(for uids: [String]) async -> [String: Int64]
}
