import XCTest

@testable import KCC

/// Pins the pure DM domain logic — most importantly the pairId derivation,
/// which MUST be byte-for-byte identical to the backend's `dmPairId`
/// (JS `Array.sort` = UTF-16 code-unit order) and Android's `dmPairId`
/// (Java `String.compareTo` = the same order) so all three platforms resolve
/// the SAME `conversations/{pairId}` document.
final class DmModelsTests: XCTestCase {

    // MARK: - pairId

    func testPairIdIsOrderIndependent() {
        XCTAssertEqual(dmPairId("alice", "bob"), "alice__bob")
        XCTAssertEqual(dmPairId("bob", "alice"), "alice__bob")
    }

    func testPairIdUsesUtf16CodeUnitOrdering() {
        // Uppercase sorts before lowercase in code-unit order ('Z' = 0x5A <
        // 'a' = 0x61) — a locale/canonical comparison would disagree.
        XCTAssertEqual(dmPairId("Zoe1", "abc2"), "Zoe1__abc2")
        // Digits (0x30..) sort before uppercase letters.
        XCTAssertEqual(dmPairId("A1", "9z"), "9z__A1")
        // '_' (0x5F) sorts after uppercase, before lowercase.
        XCTAssertEqual(dmPairId("_x", "ax"), "_x__ax")
        XCTAssertEqual(dmPairId("_x", "Xx"), "Xx___x")
        // A shared prefix: the shorter string sorts first.
        XCTAssertEqual(dmPairId("abc", "abcd"), "abc__abcd")
        // Equal uids (never happens in production — the backend rejects
        // self-DMs) still derive deterministically.
        XCTAssertEqual(dmPairId("same", "same"), "same__same")
        // Typical Firebase-style uids.
        XCTAssertEqual(
            dmPairId("uidB2222222222222222222222222", "uidA1111111111111111111111111"),
            "uidA1111111111111111111111111__uidB2222222222222222222222222"
        )
    }

    // MARK: - unread clamping

    func testUnreadClampsToNonNegativeInt() {
        XCTAssertEqual(DmMapper.unread(for: "me", in: [:]), 0)
        XCTAssertEqual(DmMapper.unread(for: "me", in: ["me": 3]), 3)
        XCTAssertEqual(DmMapper.unread(for: "me", in: ["me": -5]), 0)
        // A huge backend value must clamp, not wrap negative and hide the
        // badge.
        XCTAssertEqual(
            DmMapper.unread(for: "me", in: ["me": Int64.max]),
            Int(Int32.max)
        )
        XCTAssertEqual(DmMapper.unread(for: "me", in: ["other": 9]), 0)
    }

    // MARK: - conversation projection

    private func doc(
        members: [String] = ["me", "other"],
        blockedPair: Bool = false
    ) -> DmConversationDoc {
        DmConversationDoc(
            members: members,
            memberProfiles: [
                "other": DmUser(uid: "other", displayName: "Anna", avatarPath: "p")
            ],
            lastMessageText: "hej",
            lastMessageSenderUid: "other",
            lastMessageAtMillis: 1_000,
            unread: ["me": 2, "other": 0],
            blockedPair: blockedPair
        )
    }

    func testConversationProjectsTheOtherMember() {
        let row = DmMapper.conversation(conversationId: "me__other", doc: doc(), callerUid: "me")

        XCTAssertEqual(row.otherUser, DmUser(uid: "other", displayName: "Anna", avatarPath: "p"))
        XCTAssertEqual(
            row.lastMessage,
            DmMessagePreview(text: "hej", senderUid: "other", createdAtMillis: 1_000)
        )
        XCTAssertEqual(row.unreadCount, 2)
        XCTAssertEqual(row.lastMessageAtMillis, 1_000)
    }

    func testConversationWithoutAPreviewOrProfileDegrades() {
        let bare = DmConversationDoc(
            members: ["me", "ghost"],
            memberProfiles: [:],
            lastMessageText: nil,
            lastMessageSenderUid: nil,
            lastMessageAtMillis: nil,
            unread: [:]
        )
        let row = DmMapper.conversation(conversationId: "id", doc: bare, callerUid: "me")

        XCTAssertEqual(row.otherUser, DmUser(uid: "ghost", displayName: nil, avatarPath: nil))
        XCTAssertNil(row.lastMessage)
        XCTAssertEqual(row.unreadCount, 0)
    }

    func testIsHiddenByBlockReadsTheMarker() {
        XCTAssertFalse(DmMapper.isHiddenByBlock(doc()))
        XCTAssertTrue(DmMapper.isHiddenByBlock(doc(blockedPair: true)))
    }

    func testSortConversationsNewestFirstWithMissingTimestampsLast() {
        func row(_ id: String, at millis: Int64?) -> DmConversation {
            DmConversation(
                conversationId: id,
                otherUser: DmUser(uid: id, displayName: nil, avatarPath: nil),
                lastMessage: nil,
                unreadCount: 0,
                lastMessageAtMillis: millis
            )
        }
        let sorted = DmMapper.sortConversations([
            row("old", at: 1), row("none", at: nil), row("new", at: 9),
        ])
        XCTAssertEqual(sorted.map(\.conversationId), ["new", "old", "none"])
    }

    // MARK: - block filter

    func testFilterHiddenAuthorsDropsBothDirectionsAndKeepsNilAuthors() {
        let items: [(String, String?)] = [("a", "u1"), ("b", "u2"), ("c", nil)]
        let filtered = BlockVisibility.filterHiddenAuthors(
            items, hidden: ["u2"], authorUidOf: \.1
        )
        // The hidden set is the symmetric union (blocked + blocked-by), so
        // one containment check covers both directions; a nil author is KEPT
        // (a malformed document is a rendering problem, not block evasion).
        XCTAssertEqual(filtered.map(\.0), ["a", "c"])
        XCTAssertEqual(
            BlockVisibility.filterHiddenAuthors(items, hidden: [], authorUidOf: \.1).count,
            3
        )
    }

    // MARK: - thread merging

    private func message(
        _ id: String,
        at millis: Int64?,
        sender: String = "u1",
        clientId: String? = nil,
        state: DmDeliveryState = .sent
    ) -> DmMessage {
        DmMessage(
            id: id,
            senderUid: sender,
            text: "t-\(id)",
            createdAtMillis: millis,
            createdAtIso: millis.map(millisToIso(_:)),
            clientId: clientId,
            deliveryState: state
        )
    }

    func testMergeDeduplicatesByIdWithLiveWinning() {
        let older = [message("a", at: 1), message("b", at: 2)]
        let live = [
            DmMessage(
                id: "b", senderUid: "u1", text: "live-copy", createdAtMillis: 2,
                createdAtIso: nil
            ),
            message("c", at: 3),
        ]

        let merged = DmThreadLogic.merge(older: older, live: live)

        XCTAssertEqual(merged.map(\.id), ["a", "b", "c"])
        XCTAssertEqual(merged[1].text, "live-copy")
    }

    func testMergeOrdersChronologicallyWithMissingTimestampsLast() {
        let merged = DmThreadLogic.merge(
            older: [message("late", at: nil)],
            live: [message("b", at: 2), message("a", at: 1)]
        )
        XCTAssertEqual(merged.map(\.id), ["a", "b", "late"])
    }

    func testMergeWithPendingDropsADeliveredBubble() {
        // The delivered doc's id EQUALS the optimistic bubble's clientId, so
        // the pair renders as exactly ONE message.
        let pendingBubble = message("client-1", at: 10, sender: "me", clientId: "client-1", state: .sending)
        let delivered = message("client-1", at: 11, sender: "me", clientId: "client-1")

        let merged = DmThreadLogic.mergeWithPending(
            older: [],
            live: [message("a", at: 1), delivered],
            pending: [pendingBubble]
        )

        XCTAssertEqual(merged.map(\.id), ["a", "client-1"])
        XCTAssertEqual(merged[1].deliveryState, .sent)
    }

    func testMergeWithPendingKeepsAnUndeliveredBubbleInOrder() {
        let pendingBubble = message("client-1", at: 10, sender: "me", clientId: "client-1", state: .sending)

        let merged = DmThreadLogic.mergeWithPending(
            older: [],
            live: [message("a", at: 1)],
            pending: [pendingBubble]
        )

        XCTAssertEqual(merged.map(\.id), ["a", "client-1"])
        XCTAssertEqual(merged[1].deliveryState, .sending)
    }

    func testOldestCursorIsTheEarliestMessagesIso() {
        let messages = [message("b", at: 2_000), message("a", at: 1_000), message("n", at: nil)]
        XCTAssertEqual(DmThreadLogic.oldestCursor(messages), millisToIso(1_000))
        XCTAssertNil(DmThreadLogic.oldestCursor([]))
    }

    // MARK: - sendable bounds

    func testIsSendableTrimsAndEnforcesTheBackendCap() {
        XCTAssertFalse(DmThreadLogic.isSendable(""))
        XCTAssertFalse(DmThreadLogic.isSendable("   \n"))
        XCTAssertTrue(DmThreadLogic.isSendable("x"))
        XCTAssertTrue(DmThreadLogic.isSendable(String(repeating: "a", count: 2_000)))
        XCTAssertFalse(DmThreadLogic.isSendable(String(repeating: "a", count: 2_001)))
        // Surrounding whitespace does not count toward the cap.
        XCTAssertTrue(DmThreadLogic.isSendable("  " + String(repeating: "a", count: 2_000) + "  "))
    }

    func testIsSendableCountsUtf16CodeUnitsLikeTheBackend() {
        // "😀" is ONE grapheme but TWO UTF-16 code units — the unit the
        // backend (JS string.length) and Android (Kotlin String.length)
        // validate in. 1000 of them hit the 2000-unit cap exactly; 1001
        // exceed it even though a grapheme count (1001) would still pass.
        XCTAssertTrue(DmThreadLogic.isSendable(String(repeating: "😀", count: 1_000)))
        XCTAssertFalse(DmThreadLogic.isSendable(String(repeating: "😀", count: 1_001)))
    }

    // MARK: - error mapping

    func testMapSendCollapsesFailedPreconditionNeutrally() {
        // NOT_FRIENDS and blocked both arrive as failed-precondition with no
        // discriminator; both collapse to one neutral message so a block is
        // never revealed.
        XCTAssertEqual(DmErrorMapper.mapSend(.failedPrecondition), .cannotDeliver)
        XCTAssertEqual(DmErrorMapper.mapSend(.unauthenticated), .signedOut)
        XCTAssertEqual(DmErrorMapper.mapSend(.permissionDenied), .notMember)
        XCTAssertEqual(DmErrorMapper.mapSend(.invalidArgument), .invalid)
        XCTAssertEqual(DmErrorMapper.mapSend(.notFound), .generic)
        XCTAssertEqual(DmErrorMapper.mapSend(.other), .generic)
    }

    func testOnlyGenericIsRetryable() {
        XCTAssertTrue(DmSendError.generic.isRetryable)
        for terminal in [DmSendError.signedOut, .notMember, .invalid, .cannotDeliver] {
            XCTAssertFalse(terminal.isRetryable)
        }
    }

    // MARK: - callable payload parsing

    func testParseSendSuccessRequiresBothIds() {
        XCTAssertEqual(
            DmResponseParser.parseSendSuccess(["conversationId": "c", "messageId": "m"]),
            .sent(conversationId: "c", messageId: "m")
        )
        XCTAssertEqual(
            DmResponseParser.parseSendSuccess(["conversationId": "c"]),
            .failed(.generic)
        )
        XCTAssertEqual(DmResponseParser.parseSendSuccess(nil), .failed(.generic))
    }

    func testParseMessagesPage() {
        let data: [String: Any] = [
            "messages": [
                [
                    "id": "m1",
                    "senderUid": "u1",
                    "text": "hej",
                    "createdAt": "2026-01-01T10:00:00.000Z",
                    "clientId": "c1",
                ],
                // Missing id → dropped.
                ["senderUid": "u1", "text": "no id"],
            ],
            "nextBefore": "2026-01-01T09:00:00.000Z",
            "hasMore": true,
        ]

        let page = DmResponseParser.parseMessagesPage(data)

        XCTAssertEqual(page.messages.count, 1)
        XCTAssertEqual(page.messages[0].id, "m1")
        XCTAssertEqual(page.messages[0].clientId, "c1")
        XCTAssertEqual(page.messages[0].createdAtIso, "2026-01-01T10:00:00.000Z")
        XCTAssertNotNil(page.messages[0].createdAtMillis)
        XCTAssertEqual(page.nextBefore, "2026-01-01T09:00:00.000Z")
        XCTAssertTrue(page.hasMore)

        let empty = DmResponseParser.parseMessagesPage(nil)
        XCTAssertTrue(empty.messages.isEmpty)
        XCTAssertNil(empty.nextBefore)
        XCTAssertFalse(empty.hasMore)
    }

    func testParseReplyToRequiresIdAndSender() {
        let full = DmResponseParser.parseReplyTo([
            "messageId": "m1",
            "senderUid": "u1",
            "senderDisplayName": "Anna",
            "textPreview": "quoted",
        ])
        XCTAssertEqual(
            full,
            DmReplyTo(
                messageId: "m1", senderUid: "u1", senderDisplayName: "Anna",
                textPreview: "quoted"
            )
        )
        XCTAssertNil(DmResponseParser.parseReplyTo(["senderUid": "u1"]))
        XCTAssertNil(DmResponseParser.parseReplyTo(nil))
        XCTAssertNil(DmResponseParser.parseReplyTo("not a map"))
    }

    // MARK: - ISO round-trip

    func testIsoConversionsRoundTrip() {
        let iso = "2026-01-01T10:00:00.000Z"
        let millis = isoToMillis(iso)
        XCTAssertEqual(millis, 1_767_261_600_000)
        XCTAssertEqual(millis.map(millisToIso(_:)), iso)
        // Whole-second timestamps (no fractional part) also parse.
        XCTAssertEqual(isoToMillis("2026-01-01T10:00:00Z"), 1_767_261_600_000)
        XCTAssertNil(isoToMillis("not-a-date"))
    }

    // MARK: - inbox aggregate

    func testAnyUnreadIsAPositiveClaimOnly() {
        func row(_ unread: Int) -> DmConversation {
            DmConversation(
                conversationId: "c\(unread)",
                otherUser: DmUser(uid: "u", displayName: nil, avatarPath: nil),
                lastMessage: nil,
                unreadCount: unread,
                lastMessageAtMillis: nil
            )
        }
        XCTAssertFalse(DmConversationsState.loading.anyUnread)
        XCTAssertFalse(DmConversationsState.error(code: nil).anyUnread)
        XCTAssertFalse(DmConversationsState.loaded([row(0)]).anyUnread)
        XCTAssertTrue(DmConversationsState.loaded([row(0), row(2)]).anyUnread)
    }

    // MARK: - new dialogue

    func testOpenTargetPrefersTheExistingInboxName() {
        let friend = FriendSummary(
            uid: "u1", displayName: "Friend-row name", avatarPath: nil, friendsSince: nil
        )
        let conversations = [
            DmConversation(
                conversationId: "me__u1",
                otherUser: DmUser(uid: "u1", displayName: "Inbox name", avatarPath: nil),
                lastMessage: nil,
                unreadCount: 0,
                lastMessageAtMillis: nil
            )
        ]

        let target = NewDialogue.openTarget(for: friend, in: conversations)

        XCTAssertEqual(target, DmOpenTarget(uid: "u1", displayName: "Inbox name", isExisting: true))
    }

    func testOpenTargetFallsThroughBlankNamesToNil() {
        let friend = FriendSummary(uid: "u1", displayName: "  ", avatarPath: nil, friendsSince: nil)
        let conversations = [
            DmConversation(
                conversationId: "me__u1",
                otherUser: DmUser(uid: "u1", displayName: "", avatarPath: nil),
                lastMessage: nil,
                unreadCount: 0,
                lastMessageAtMillis: nil
            )
        ]

        let target = NewDialogue.openTarget(for: friend, in: conversations)

        // Neither source has a usable name → nil, never a blank string, so
        // the thread title falls back to the neutral placeholder.
        XCTAssertEqual(target, DmOpenTarget(uid: "u1", displayName: nil, isExisting: true))
    }

    func testOpenTargetForANewConversationUsesTheFriendRowName() {
        let friend = FriendSummary(uid: "u2", displayName: "Anna", avatarPath: nil, friendsSince: nil)

        let target = NewDialogue.openTarget(for: friend, in: [])

        XCTAssertEqual(target, DmOpenTarget(uid: "u2", displayName: "Anna", isExisting: false))
    }
}
