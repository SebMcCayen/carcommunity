import Foundation

/// Friends domain (member-gated friend graph) — the iOS port of Android's
/// `friends/Friends.kt`. The backend (europe-west1 callables `friend-list` /
/// `friend-sendRequest` / `friend-respondRequest` / `friend-cancelRequest` /
/// `friend-remove`) is the source of truth; the client never writes the graph
/// directly (firebase/firestore.rules: no client writes on
/// `users/{uid}/friends` or `friendRequests`). Everything here is pure Swift
/// so the mapping/parsing logic is unit-testable without Firebase.

/// A member as referenced by a friend row, request, or ambiguity candidate.
struct FriendUser: Equatable, Sendable {
    let uid: String
    let displayName: String?
    let avatarPath: String?
}

/// An established friendship.
struct FriendSummary: Equatable, Sendable, Identifiable {
    let uid: String
    let displayName: String?
    let avatarPath: String?
    /// ISO-8601 timestamp; kept as the raw string (display is best-effort).
    let friendsSince: String?

    var id: String { uid }
}

enum FriendRequestDirection: Equatable, Sendable {
    case incoming
    case outgoing
}

/// A pending friend request, in either direction.
struct FriendRequestSummary: Equatable, Sendable, Identifiable {
    let requestId: String
    let fromUid: String
    let toUid: String
    let direction: FriendRequestDirection
    let otherUser: FriendUser
    let createdAt: String?

    var id: String { requestId }
}

/// The full snapshot returned by `friend-list`.
struct FriendsData: Equatable, Sendable {
    let friends: [FriendSummary]
    let incoming: [FriendRequestSummary]
    let outgoing: [FriendRequestSummary]

    static let empty = FriendsData(friends: [], incoming: [], outgoing: [])
}

/// A user-facing failure category, mapped from an HttpsError code (+ the
/// `details.reason` discriminator). The screen renders each via a `friends.*`
/// string. ``notAddable`` is deliberately neutral — it must never reveal
/// whether the caller was blocked or did the blocking (Android:
/// `FriendActionError`).
enum FriendActionError: Equatable, Sendable {
    case signedOut
    case notMember
    case invalid
    case selfRequest
    case notFound
    case alreadyFriends
    case requestAlreadySent
    case notAddable
    case requestGone
    case network
    /// The backend is reachable but cannot serve the request right now
    /// (`unavailable` + `details.reason = BACKEND_UNAVAILABLE`) — OUR fault,
    /// not the caller's; retrying is the only useful advice. Distinct from
    /// ``network``, where the DEVICE could not reach us.
    case temporarilyUnavailable
    /// Last-resort sink for a failure we could not classify.
    case generic
}

/// Outcome of `friend-sendRequest` (Android: `SendRequestResult`).
enum SendRequestResult: Equatable, Sendable {
    /// A pending request was created.
    case requested
    /// The request auto-accepted an inbound one — the two are now friends.
    case nowFriends
    /// The nickname matched several members; the caller must pick one.
    case ambiguous(candidates: [FriendUser])
    case failed(FriendActionError)
}

/// Outcome of `friend-respondRequest`.
enum RespondResult: Equatable, Sendable {
    case accepted
    case declined
    case failed(FriendActionError)
}

/// Outcome of `friend-cancelRequest` (withdrawing the caller's OWN pending
/// outgoing request). There is no "nothing to cancel" failure: the callable
/// answers every non-cancellable case with the same successful no-op.
enum CancelResult: Equatable, Sendable {
    case cancelled
    case failed(FriendActionError)
}

/// Outcome of `friend-remove` (idempotent).
enum RemoveResult: Equatable, Sendable {
    case removed
    case failed(FriendActionError)
}

/// Outcome of `friend-list`.
enum FriendsResult: Equatable, Sendable {
    case loaded(FriendsData)
    case failed(FriendActionError)
}

/// The canonical HttpsError codes the friends slice branches on, decoupled
/// from the Firebase SDK so the mapping is testable with plain values
/// (Android: `FriendErrorCode`). Any code we don't special-case collapses to
/// ``other``.
enum FriendErrorCode: Equatable, Sendable {
    case unauthenticated
    case permissionDenied
    case invalidArgument
    case notFound
    case alreadyExists
    case failedPrecondition
    /// Transport-level: no/lost connectivity or a server-side timeout
    /// (UNAVAILABLE and DEADLINE_EXCEEDED both fold here).
    case unavailable
    case other
}

/// Pure representation of a callable failure: the code, the optional
/// `details.reason` discriminator, and any ambiguity candidates carried in
/// `details.candidates` (Android: `FriendCallableError`). Conforms to
/// `Error` so it can travel as a `Result` failure; it still carries only
/// contract codes and reasons — never an SDK message.
struct FriendCallableError: Error, Equatable, Sendable {
    let code: FriendErrorCode
    let reason: String?
    let candidates: [FriendUser]
}

/// Pure code→result mapping. Branch on the HttpsError code (never the
/// message) and, for the overloaded codes, on the `details.reason`
/// discriminator (Android: `FriendsErrorMapper`).
enum FriendsErrorMapper {
    static let reasonAmbiguous = "AMBIGUOUS_NICKNAME"
    static let reasonNotAddable = "NOT_ADDABLE"
    static let reasonAlreadyFriends = "ALREADY_FRIENDS"
    static let reasonRequestAlreadySent = "REQUEST_ALREADY_SENT"
    static let reasonNicknameNotFound = "NICKNAME_NOT_FOUND"
    static let reasonSelfRequest = "SELF_REQUEST"
    static let reasonBackendUnavailable = "BACKEND_UNAVAILABLE"

    static func mapSend(_ error: FriendCallableError) -> SendRequestResult {
        // Reason-tagged failures are checked BEFORE the bare code: several
        // distinct outcomes share one code ('already-exists' = already-friends
        // OR request-already-sent; 'failed-precondition' = ambiguous OR
        // not-addable), so the code alone cannot pick the right message and
        // must never mis-route a picker or leak block direction.
        switch error.reason {
        case reasonAmbiguous: return .ambiguous(candidates: error.candidates)
        case reasonNotAddable: return .failed(.notAddable)
        case reasonAlreadyFriends: return .failed(.alreadyFriends)
        case reasonRequestAlreadySent: return .failed(.requestAlreadySent)
        case reasonNicknameNotFound: return .failed(.notFound)
        case reasonSelfRequest: return .failed(.selfRequest)
        default: return .failed(sendError(for: error.code))
        }
    }

    /// Code-only fallback for a send failure with no `details.reason`.
    private static func sendError(for code: FriendErrorCode) -> FriendActionError {
        switch code {
        case .unauthenticated: return .signedOut
        case .permissionDenied: return .notMember
        case .invalidArgument: return .invalid
        case .notFound: return .notFound
        // Untagged 'already-exists': we cannot tell already-friends from
        // request-already-sent, so fall back to the friendship reading.
        case .alreadyExists: return .alreadyFriends
        // A failed-precondition on send that isn't reason-tagged is treated
        // as the neutral not-addable case.
        case .failedPrecondition: return .notAddable
        case .unavailable: return .network
        case .other: return .generic
        }
    }

    static func mapRespond(_ error: FriendCallableError) -> FriendActionError {
        switch error.code {
        case .unauthenticated: return .signedOut
        case .permissionDenied: return .notMember
        // No such request / not the recipient, or already accepted/declined:
        // the request can no longer be acted on.
        case .notFound, .failedPrecondition: return .requestGone
        case .unavailable: return .network
        default: return .generic
        }
    }

    static func mapGeneric(_ error: FriendCallableError) -> FriendActionError {
        switch error.code {
        case .unauthenticated: return .signedOut
        case .permissionDenied: return .notMember
        case .unavailable: return .network
        default: return .generic
        }
    }

    /// Maps a `friend-list` failure. Separate from ``mapGeneric(_:)`` because
    /// loading the snapshot has a failure mode the mutations do not: the
    /// backend can be reachable yet unable to serve the read at all
    /// (``FriendActionError/temporarilyUnavailable`` — see Android's
    /// regression guard of 2026-07-19). The reason discriminator is checked
    /// BEFORE the bare code, matching ``mapSend(_:)``.
    static func mapList(_ error: FriendCallableError) -> FriendActionError {
        if error.reason == reasonBackendUnavailable { return .temporarilyUnavailable }
        return mapGeneric(error)
    }
}

/// Pure parsing of the callable response payloads (plain dictionaries/arrays
/// as the Functions SDK deserializes JSON). Missing/blank required fields
/// drop the row rather than crash, so a partial backend response degrades
/// gracefully (Android: `FriendsResponseParser`).
enum FriendsResponseParser {
    static func parseList(_ data: [String: Any]?) -> FriendsData {
        guard let data else { return .empty }
        let friends = (data["friends"] as? [Any] ?? []).compactMap(parseFriend(_:))
        let incoming = (data["incoming"] as? [Any] ?? []).compactMap {
            parseRequest($0, direction: .incoming)
        }
        let outgoing = (data["outgoing"] as? [Any] ?? []).compactMap {
            parseRequest($0, direction: .outgoing)
        }
        return FriendsData(friends: friends, incoming: incoming, outgoing: outgoing)
    }

    /// Maps a `friend-sendRequest` success payload to its result. A missing
    /// status on a 2xx is still a created request, not a failure.
    static func parseSendSuccess(_ data: [String: Any]?) -> SendRequestResult {
        (data?["status"] as? String) == "friends" ? .nowFriends : .requested
    }

    /// Maps a `friend-respondRequest` success payload to its result.
    static func parseRespondSuccess(_ data: [String: Any]?) -> RespondResult {
        (data?["status"] as? String) == "declined" ? .declined : .accepted
    }

    /// Parses the `details.candidates` of an ambiguous-nickname failure.
    static func parseCandidates(_ details: Any?) -> [FriendUser] {
        guard let map = details as? [String: Any],
            let list = map["candidates"] as? [Any]
        else { return [] }
        return list.compactMap(parseUser(_:))
    }

    static func reason(of details: Any?) -> String? {
        (details as? [String: Any])?["reason"] as? String
    }

    private static func parseFriend(_ raw: Any) -> FriendSummary? {
        guard let map = raw as? [String: Any],
            let uid = map["uid"] as? String, !uid.isEmpty
        else { return nil }
        return FriendSummary(
            uid: uid,
            displayName: map["displayName"] as? String,
            avatarPath: map["avatarPath"] as? String,
            friendsSince: map["friendsSince"] as? String
        )
    }

    private static func parseRequest(
        _ raw: Any,
        direction: FriendRequestDirection
    ) -> FriendRequestSummary? {
        guard let map = raw as? [String: Any],
            let requestId = map["requestId"] as? String, !requestId.isEmpty,
            let other = parseUser(map["otherUser"] as Any)
        else { return nil }
        return FriendRequestSummary(
            requestId: requestId,
            fromUid: map["fromUid"] as? String ?? "",
            toUid: map["toUid"] as? String ?? "",
            direction: direction,
            otherUser: other,
            createdAt: map["createdAt"] as? String
        )
    }

    private static func parseUser(_ raw: Any) -> FriendUser? {
        guard let map = raw as? [String: Any],
            let uid = map["uid"] as? String, !uid.isEmpty
        else { return nil }
        return FriendUser(
            uid: uid,
            displayName: map["displayName"] as? String,
            avatarPath: map["avatarPath"] as? String
        )
    }
}

/// How the established friends list is ordered on the Friends screen. Purely
/// a client-side view preference (Android: `FriendSort`).
enum FriendSort: Equatable, Sendable, CaseIterable {
    /// Case-insensitive, locale-aware (Swedish) by display name, A→Ö.
    case name
    /// Most recently added first (`friendsSince` descending).
    case recentlyAdded
    /// Earliest added first — the order `friend-list` itself returns, so it
    /// is the default and the list never reorders on first load.
    case earliestAdded
}

/// Pure, client-side ordering of the already-loaded friends list (Android:
/// `sortFriends`). ``FriendSort/name`` compares with the Swedish locale so
/// å/ä/ö sort AFTER z; the date sorts compare the raw ISO-8601 strings
/// lexicographically — identical to how the backend orders them. Rows missing
/// the sort key always sort LAST; the sort is stable.
func sortFriends(_ friends: [FriendSummary], by sort: FriendSort) -> [FriendSummary] {
    // Decorate with the index so ties keep their incoming order (Swift's
    // sort(by:) is not documented as stable).
    let indexed = friends.enumerated()
    switch sort {
    case .name:
        let locale = Locale(identifier: "sv_SE")
        return indexed.sorted { a, b in
            let aBlank = a.element.displayName.isBlankOrNil
            let bBlank = b.element.displayName.isBlankOrNil
            if aBlank != bBlank { return bBlank }
            let comparison = (a.element.displayName ?? "").compare(
                b.element.displayName ?? "",
                options: [.caseInsensitive],
                range: nil,
                locale: locale
            )
            if comparison != .orderedSame { return comparison == .orderedAscending }
            return a.offset < b.offset
        }.map(\.element)
    case .recentlyAdded:
        return indexed.sorted { a, b in
            let aBlank = a.element.friendsSince.isBlankOrNil
            let bBlank = b.element.friendsSince.isBlankOrNil
            if aBlank != bBlank { return bBlank }
            let lhs = a.element.friendsSince ?? ""
            let rhs = b.element.friendsSince ?? ""
            if lhs != rhs { return lhs > rhs }
            return a.offset < b.offset
        }.map(\.element)
    case .earliestAdded:
        return indexed.sorted { a, b in
            let aBlank = a.element.friendsSince.isBlankOrNil
            let bBlank = b.element.friendsSince.isBlankOrNil
            if aBlank != bBlank { return bBlank }
            let lhs = a.element.friendsSince ?? ""
            let rhs = b.element.friendsSince ?? ""
            if lhs != rhs { return lhs < rhs }
            return a.offset < b.offset
        }.map(\.element)
    }
}

extension Optional where Wrapped == String {
    fileprivate var isBlankOrNil: Bool {
        guard let self else { return true }
        return self.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }
}

/// Pure transform from a loaded friends snapshot to the friends a DM (or a
/// share) can target: established friends only, blank-uid rows dropped,
/// name-ordered for a scannable picker (Android: `FriendShareTargets`).
enum FriendShareTargets {
    static func from(_ data: FriendsData) -> [FriendSummary] {
        sortFriends(data.friends.filter { !$0.uid.isEmpty }, by: .name)
    }
}

/// Pure, locale-independent formatting of a Crown Points balance for the
/// compact chip beside a friend's name (Android: `FriendPointsFormat`).
/// Groups thousands with a space ("1 240", "12 000") — the Swedish
/// digit-grouping convention.
enum FriendPointsFormat {
    static func grouped(_ balance: Int64) -> String {
        let negative = balance < 0
        // Magnitude via UInt64 so Int64.min (whose abs overflows) still
        // yields correct digits.
        let magnitude: UInt64 = negative ? (0 &- UInt64(bitPattern: balance)) : UInt64(balance)
        let digits = Array(String(magnitude))
        let firstGroup = digits.count % 3
        var result = ""
        for (index, digit) in digits.enumerated() {
            if index != 0 && (index - firstGroup) % 3 == 0 { result.append(" ") }
            result.append(digit)
        }
        return negative ? "-\(result)" : result
    }
}
