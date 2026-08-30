import XCTest

@testable import KCC

/// Pins the pure friends domain logic — response parsing, the code+reason →
/// error mapping, client-side sorting, and points formatting — mirroring
/// Android's `FriendsTest` coverage so the two platforms cannot drift.
final class FriendsModelsTests: XCTestCase {

    // MARK: - parseList

    func testParseListMapsAllThreeSections() {
        let data: [String: Any] = [
            "friends": [
                [
                    "uid": "f1",
                    "displayName": "GT86_swe",
                    "avatarPath": "avatars/f1.jpg",
                    "friendsSince": "2026-01-15T10:00:00.000Z",
                ]
            ],
            "incoming": [
                [
                    "requestId": "r1",
                    "fromUid": "a",
                    "toUid": "me",
                    "otherUser": ["uid": "a", "displayName": "Anna"],
                    "createdAt": "2026-02-01T10:00:00.000Z",
                ]
            ],
            "outgoing": [
                [
                    "requestId": "r2",
                    "fromUid": "me",
                    "toUid": "b",
                    "otherUser": ["uid": "b"],
                ]
            ],
        ]

        let parsed = FriendsResponseParser.parseList(data)

        XCTAssertEqual(
            parsed.friends,
            [
                FriendSummary(
                    uid: "f1",
                    displayName: "GT86_swe",
                    avatarPath: "avatars/f1.jpg",
                    friendsSince: "2026-01-15T10:00:00.000Z"
                )
            ]
        )
        XCTAssertEqual(parsed.incoming.count, 1)
        XCTAssertEqual(parsed.incoming[0].direction, .incoming)
        XCTAssertEqual(parsed.incoming[0].otherUser.displayName, "Anna")
        XCTAssertEqual(parsed.outgoing.count, 1)
        XCTAssertEqual(parsed.outgoing[0].direction, .outgoing)
        XCTAssertNil(parsed.outgoing[0].createdAt)
    }

    func testParseListDropsRowsMissingRequiredFields() {
        let data: [String: Any] = [
            "friends": [
                ["displayName": "No uid"],
                ["uid": ""],
                ["uid": "ok"],
            ],
            "incoming": [
                // Missing otherUser → dropped.
                ["requestId": "r1", "fromUid": "a", "toUid": "me"],
                // Blank requestId → dropped.
                ["requestId": "", "otherUser": ["uid": "a"]],
            ],
        ]

        let parsed = FriendsResponseParser.parseList(data)

        XCTAssertEqual(parsed.friends.map(\.uid), ["ok"])
        XCTAssertTrue(parsed.incoming.isEmpty)
        XCTAssertTrue(parsed.outgoing.isEmpty)
    }

    func testParseListOfNilIsEmpty() {
        XCTAssertEqual(FriendsResponseParser.parseList(nil), .empty)
    }

    // MARK: - success payloads

    func testParseSendSuccessDefaultsToRequested() {
        XCTAssertEqual(FriendsResponseParser.parseSendSuccess([:]), .requested)
        XCTAssertEqual(FriendsResponseParser.parseSendSuccess(nil), .requested)
        XCTAssertEqual(
            FriendsResponseParser.parseSendSuccess(["status": "requested"]),
            .requested
        )
        XCTAssertEqual(
            FriendsResponseParser.parseSendSuccess(["status": "friends"]),
            .nowFriends
        )
    }

    func testParseRespondSuccess() {
        XCTAssertEqual(
            FriendsResponseParser.parseRespondSuccess(["status": "declined"]),
            .declined
        )
        XCTAssertEqual(FriendsResponseParser.parseRespondSuccess([:]), .accepted)
        XCTAssertEqual(FriendsResponseParser.parseRespondSuccess(nil), .accepted)
    }

    // MARK: - details parsing

    func testParseCandidatesAndReason() {
        let details: [String: Any] = [
            "reason": "AMBIGUOUS_NICKNAME",
            "candidates": [
                ["uid": "a", "displayName": "gt_86", "avatarPath": "p"],
                ["uid": "", "displayName": "dropped"],
                "not a map",
            ],
        ]

        XCTAssertEqual(FriendsResponseParser.reason(of: details), "AMBIGUOUS_NICKNAME")
        XCTAssertEqual(
            FriendsResponseParser.parseCandidates(details),
            [FriendUser(uid: "a", displayName: "gt_86", avatarPath: "p")]
        )
        XCTAssertNil(FriendsResponseParser.reason(of: nil))
        XCTAssertTrue(FriendsResponseParser.parseCandidates(nil).isEmpty)
    }

    // MARK: - send mapping (reason first, then code)

    func testMapSendPrefersReasonOverCode() {
        let candidates = [FriendUser(uid: "a", displayName: "A", avatarPath: nil)]
        XCTAssertEqual(
            FriendsErrorMapper.mapSend(
                FriendCallableError(
                    code: .failedPrecondition,
                    reason: "AMBIGUOUS_NICKNAME",
                    candidates: candidates
                )
            ),
            .ambiguous(candidates: candidates)
        )
        XCTAssertEqual(
            FriendsErrorMapper.mapSend(
                FriendCallableError(code: .failedPrecondition, reason: "NOT_ADDABLE", candidates: [])
            ),
            .failed(.notAddable)
        )
        XCTAssertEqual(
            FriendsErrorMapper.mapSend(
                FriendCallableError(code: .alreadyExists, reason: "ALREADY_FRIENDS", candidates: [])
            ),
            .failed(.alreadyFriends)
        )
        XCTAssertEqual(
            FriendsErrorMapper.mapSend(
                FriendCallableError(
                    code: .alreadyExists, reason: "REQUEST_ALREADY_SENT", candidates: []
                )
            ),
            .failed(.requestAlreadySent)
        )
        XCTAssertEqual(
            FriendsErrorMapper.mapSend(
                FriendCallableError(code: .notFound, reason: "NICKNAME_NOT_FOUND", candidates: [])
            ),
            .failed(.notFound)
        )
        XCTAssertEqual(
            FriendsErrorMapper.mapSend(
                FriendCallableError(code: .invalidArgument, reason: "SELF_REQUEST", candidates: [])
            ),
            .failed(.selfRequest)
        )
    }

    func testMapSendCodeFallbacksWithoutReason() {
        func send(_ code: FriendErrorCode) -> SendRequestResult {
            FriendsErrorMapper.mapSend(
                FriendCallableError(code: code, reason: nil, candidates: [])
            )
        }
        XCTAssertEqual(send(.unauthenticated), .failed(.signedOut))
        XCTAssertEqual(send(.permissionDenied), .failed(.notMember))
        XCTAssertEqual(send(.invalidArgument), .failed(.invalid))
        XCTAssertEqual(send(.notFound), .failed(.notFound))
        // Untagged already-exists falls back to the friendship reading.
        XCTAssertEqual(send(.alreadyExists), .failed(.alreadyFriends))
        // Untagged failed-precondition is the neutral not-addable case.
        XCTAssertEqual(send(.failedPrecondition), .failed(.notAddable))
        XCTAssertEqual(send(.unavailable), .failed(.network))
        XCTAssertEqual(send(.other), .failed(.generic))
    }

    // MARK: - respond / generic / list mapping

    func testMapRespond() {
        func respond(_ code: FriendErrorCode) -> FriendActionError {
            FriendsErrorMapper.mapRespond(
                FriendCallableError(code: code, reason: nil, candidates: [])
            )
        }
        XCTAssertEqual(respond(.unauthenticated), .signedOut)
        XCTAssertEqual(respond(.permissionDenied), .notMember)
        XCTAssertEqual(respond(.notFound), .requestGone)
        XCTAssertEqual(respond(.failedPrecondition), .requestGone)
        XCTAssertEqual(respond(.unavailable), .network)
        XCTAssertEqual(respond(.other), .generic)
    }

    func testMapListSplitsBackendUnavailableFromNetwork() {
        // unavailable + BACKEND_UNAVAILABLE: the backend answered "I cannot
        // serve this" — our fault, distinct from a dropped connection.
        XCTAssertEqual(
            FriendsErrorMapper.mapList(
                FriendCallableError(
                    code: .unavailable, reason: "BACKEND_UNAVAILABLE", candidates: []
                )
            ),
            .temporarilyUnavailable
        )
        XCTAssertEqual(
            FriendsErrorMapper.mapList(
                FriendCallableError(code: .unavailable, reason: nil, candidates: [])
            ),
            .network
        )
        XCTAssertEqual(
            FriendsErrorMapper.mapList(
                FriendCallableError(code: .other, reason: nil, candidates: [])
            ),
            .generic
        )
    }

    // MARK: - sorting

    private func friend(_ uid: String, name: String?, since: String?) -> FriendSummary {
        FriendSummary(uid: uid, displayName: name, avatarPath: nil, friendsSince: since)
    }

    func testSortByNameIsSwedishAndCaseInsensitiveWithBlanksLast() {
        let friends = [
            friend("1", name: "åsa", since: nil),
            friend("2", name: nil, since: nil),
            friend("3", name: "Zeke", since: nil),
            friend("4", name: "adam", since: nil),
            friend("5", name: "Bertil", since: nil),
        ]

        let sorted = sortFriends(friends, by: .name).map(\.uid)

        // Swedish collation: å sorts AFTER z; nil/blank names last.
        XCTAssertEqual(sorted, ["4", "5", "3", "1", "2"])
    }

    func testSortByDatesCompareIsoLexicographicallyWithBlanksLast() {
        let friends = [
            friend("old", name: "A", since: "2025-01-01T00:00:00.000Z"),
            friend("none", name: "B", since: nil),
            friend("new", name: "C", since: "2026-06-01T00:00:00.000Z"),
        ]

        XCTAssertEqual(
            sortFriends(friends, by: .recentlyAdded).map(\.uid),
            ["new", "old", "none"]
        )
        XCTAssertEqual(
            sortFriends(friends, by: .earliestAdded).map(\.uid),
            ["old", "new", "none"]
        )
    }

    func testShareTargetsDropBlankUidsAndSortByName() {
        let data = FriendsData(
            friends: [
                friend("", name: "Ghost", since: nil),
                friend("b", name: "Örjan", since: nil),
                friend("a", name: "Adam", since: nil),
            ],
            incoming: [],
            outgoing: []
        )

        XCTAssertEqual(FriendShareTargets.from(data).map(\.uid), ["a", "b"])
    }

    // MARK: - points formatting

    func testPointsGrouping() {
        XCTAssertEqual(FriendPointsFormat.grouped(0), "0")
        XCTAssertEqual(FriendPointsFormat.grouped(999), "999")
        XCTAssertEqual(FriendPointsFormat.grouped(1_240), "1 240")
        XCTAssertEqual(FriendPointsFormat.grouped(12_000), "12 000")
        XCTAssertEqual(FriendPointsFormat.grouped(1_234_567), "1 234 567")
        // Negative never occurs (ledger floors at 0) but the sign is
        // preserved if one ever does — including the overflow edge.
        XCTAssertEqual(FriendPointsFormat.grouped(-1_240), "-1 240")
        XCTAssertEqual(
            FriendPointsFormat.grouped(Int64.min),
            "-9 223 372 036 854 775 808"
        )
    }
}
