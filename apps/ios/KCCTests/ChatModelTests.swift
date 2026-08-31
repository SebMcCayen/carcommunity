import XCTest

@testable import KCC

/// Pure model/logic tests for the chat channels — tolerant decoding, thread
/// merges, unread derivation, block filtering, and the convoy-list projection.
/// No Firebase; every rule mirrors Android's `ChatChannels.kt` /
/// `ConvoyChatRepository.kt` / `ConvoyRowFormat.kt` pure objects.
final class ChatModelTests: XCTestCase {

    // MARK: - parseMessage tolerant decoding

    func testParseMessageReadsAllFields() {
        let message = ChannelResponseParser.parseMessage([
            "id": "m1",
            "senderUid": "u1",
            "text": "hi",
            "senderDisplayName": "Ada",
            "senderAvatarPath": "avatars/u1.jpg",
            "createdAt": "2026-08-29T10:00:00Z",
            "mentionedUids": ["u2", "u2", "", "u3"],
            "clientId": "c1",
        ])
        XCTAssertEqual(message?.id, "m1")
        XCTAssertEqual(message?.senderUid, "u1")
        XCTAssertEqual(message?.text, "hi")
        XCTAssertEqual(message?.senderDisplayName, "Ada")
        XCTAssertEqual(message?.senderAvatarPath, "avatars/u1.jpg")
        XCTAssertEqual(message?.mentionedUids, ["u2", "u3"])  // deduped, blanks dropped
        XCTAssertEqual(message?.clientId, "c1")
        XCTAssertNotNil(message?.createdAt)
        XCTAssertEqual(message?.createdAtIso, "2026-08-29T10:00:00Z")
    }

    func testParseMessageDropsRowWithMissingOrBlankIdOrSender() {
        XCTAssertNil(ChannelResponseParser.parseMessage(["senderUid": "u1", "text": "x"]))
        XCTAssertNil(ChannelResponseParser.parseMessage(["id": "", "senderUid": "u1"]))
        XCTAssertNil(ChannelResponseParser.parseMessage(["id": "m1", "senderUid": ""]))
        XCTAssertNil(ChannelResponseParser.parseMessage(["id": "m1"]))
        XCTAssertNil(ChannelResponseParser.parseMessage("not a map"))
    }

    func testParseMessageDegradesTextToEmptyAndKeepsUnparseableDate() {
        let message = ChannelResponseParser.parseMessage([
            "id": "m1", "senderUid": "u1", "createdAt": "not-a-date",
        ])
        XCTAssertEqual(message?.text, "")
        XCTAssertNil(message?.createdAt)  // unparseable → nil instant, message kept
        // Regression: the raw invalid string must NOT be kept as createdAtIso —
        // it would otherwise be usable as a pagination cursor and cause an
        // avoidable loadOlder(before:) backend reject.
        XCTAssertNil(message?.createdAtIso)
        XCTAssertNil(message?.senderDisplayName)
        XCTAssertEqual(message?.mentionedUids, [])
    }

    /// Parity guard: community/convoy channels carry NO moderation-state field
    /// (the `visible → auto_hidden → removed` machine is Event-chat only). A doc
    /// that happens to carry one still decodes normally and is NOT hidden
    /// client-side — the only client-side hiding is blocking. Mirrors Android's
    /// `ChannelMessage`, which decodes no moderation field.
    func testMessagesIgnoreAnyModerationStateField() {
        let message = ChannelResponseParser.parseMessage([
            "id": "m1", "senderUid": "u1", "text": "hi", "moderationState": "auto_hidden",
        ])
        XCTAssertNotNil(message)
        XCTAssertEqual(message?.text, "hi")
    }

    // MARK: - parseReplyTo

    func testParseReplyToRequiresMessageIdAndSender() {
        let reply = ChannelResponseParser.parseReplyTo([
            "messageId": "p1", "senderUid": "u9", "senderDisplayName": "Bo", "textPreview": "prev",
        ])
        XCTAssertEqual(reply?.messageId, "p1")
        XCTAssertEqual(reply?.senderUid, "u9")
        XCTAssertEqual(reply?.textPreview, "prev")

        XCTAssertNil(ChannelResponseParser.parseReplyTo(["messageId": "p1"]))  // no sender
        XCTAssertNil(ChannelResponseParser.parseReplyTo(["senderUid": "u9"]))  // no messageId
        XCTAssertNil(ChannelResponseParser.parseReplyTo(nil))
    }

    // MARK: - parsePostSuccess / parseMessagesPage

    func testParsePostSuccessNeedsMessageId() {
        if case .sent(let id, let mentions) = ChannelResponseParser.parsePostSuccess([
            "messageId": "m5", "mentionedUids": ["u2"],
        ]) {
            XCTAssertEqual(id, "m5")
            XCTAssertEqual(mentions, ["u2"])
        } else {
            XCTFail("expected sent")
        }
        XCTAssertEqual(ChannelResponseParser.parsePostSuccess([:]), .failed(.generic))
        XCTAssertEqual(ChannelResponseParser.parsePostSuccess(["messageId": ""]), .failed(.generic))
    }

    func testParseMessagesPageReadsCursorAndHasMore() {
        let page = ChannelResponseParser.parseMessagesPage([
            "messages": [
                ["id": "m1", "senderUid": "u1", "createdAt": "2026-08-29T10:00:00Z"],
                ["id": "", "senderUid": "u1"],  // dropped
            ],
            "nextBefore": "2026-08-29T09:00:00Z",
            "hasMore": true,
        ])
        XCTAssertEqual(page.messages.count, 1)
        XCTAssertEqual(page.nextBefore, "2026-08-29T09:00:00Z")
        XCTAssertTrue(page.hasMore)

        let empty = ChannelResponseParser.parseMessagesPage([:])
        XCTAssertEqual(empty.messages, [])
        XCTAssertNil(empty.nextBefore)
        XCTAssertFalse(empty.hasMore)
    }

    // MARK: - ChannelThread

    func testIsSendableEnforcesTrimAndLength() {
        XCTAssertFalse(ChannelThread.isSendable("   "))
        XCTAssertTrue(ChannelThread.isSendable("  hi  "))
        XCTAssertTrue(ChannelThread.isSendable(String(repeating: "x", count: channelMessageMaxLength)))
        XCTAssertFalse(ChannelThread.isSendable(String(repeating: "x", count: channelMessageMaxLength + 1)))
    }

    func testMergeDeduplicatesLiveWinsAndSortsChronologically() {
        let older = [msg("a", secs: 100), msg("b", secs: 200)]
        let live = [msg("b", secs: 200, text: "edited"), msg("c", secs: 300)]
        let merged = ChannelThread.merge(older: older, live: live)
        XCTAssertEqual(merged.map(\.id), ["a", "b", "c"])
        XCTAssertEqual(merged.first { $0.id == "b" }?.text, "edited")  // live copy wins
    }

    func testMergeSortsNilDateLast() {
        let merged = ChannelThread.merge(
            older: [msg("late", secs: nil), msg("a", secs: 100)],
            live: []
        )
        XCTAssertEqual(merged.map(\.id), ["a", "late"])
    }

    func testMergeWithPendingDropsReconciledBubble() {
        let live = [msg("c1", secs: 500)]  // delivered doc whose id == the clientId
        let pending = [pendingBubble("c1"), pendingBubble("c2")]
        let merged = ChannelThread.mergeWithPending(older: [], live: live, pending: pending)
        XCTAssertEqual(Set(merged.map(\.id)), ["c1", "c2"])
        // The reconciled c1 keeps the server copy (delivery state sent), c2 stays pending.
        XCTAssertEqual(merged.first { $0.id == "c1" }?.deliveryState, .sent)
        XCTAssertEqual(merged.first { $0.id == "c2" }?.deliveryState, .sending)
    }

    func testOldestCursorIsEarliestIso() {
        let cursor = ChannelThread.oldestCursor([msg("b", secs: 200), msg("a", secs: 100)])
        XCTAssertEqual(cursor, msg("a", secs: 100).createdAtIso)
    }

    /// Regression: a same-instant tie must resolve deterministically (by id,
    /// the same total order the merge sorts by) rather than `min` being free
    /// to return either candidate depending on element order.
    func testOldestCursorTieBreaksById() {
        let earliest = msg("a", secs: 100)
        let tied1 = msg("z", secs: 200)
        let tied2 = msg("m", secs: 200)
        XCTAssertEqual(
            ChannelThread.oldestCursor([tied1, tied2, earliest]),
            earliest.createdAtIso)
        // Among two messages at the SAME instant, "a" beats "b" is well
        // defined by the shared total order, not by array position.
        let a = msg("a", secs: 100)
        let b = msg("b", secs: 100)
        XCTAssertEqual(ChannelThread.oldestCursor([b, a]), a.createdAtIso)
        XCTAssertEqual(ChannelThread.oldestCursor([a, b]), a.createdAtIso)
    }

    func testHasUnread() {
        let mine = msg("m", secs: 100, sender: "me")
        let theirs = msg("t", secs: 100, sender: "you")
        XCTAssertFalse(ChannelThread.hasUnread(newest: nil, callerUid: "me", lastReadAt: nil))
        XCTAssertFalse(ChannelThread.hasUnread(newest: mine, callerUid: "me", lastReadAt: nil))
        XCTAssertTrue(ChannelThread.hasUnread(newest: theirs, callerUid: "me", lastReadAt: nil))
        XCTAssertFalse(
            ChannelThread.hasUnread(
                newest: theirs, callerUid: "me",
                lastReadAt: Date(timeIntervalSince1970: 200)))
        XCTAssertTrue(
            ChannelThread.hasUnread(
                newest: theirs, callerUid: "me",
                lastReadAt: Date(timeIntervalSince1970: 50)))
    }

    func testUnreadCountCountsOthersAfterMarker() {
        let window = [
            msg("a", secs: 100, sender: "you"),
            msg("b", secs: 200, sender: "me"),
            msg("c", secs: 300, sender: "you"),
            msg("d", secs: nil, sender: "you"),  // no date → not counted
        ]
        XCTAssertEqual(
            ChannelThread.unreadCount(
                window: window, callerUid: "me",
                lastReadAt: Date(timeIntervalSince1970: 150)),
            1)  // only c
        XCTAssertEqual(ChannelThread.unreadCount(window: window, callerUid: "me", lastReadAt: nil), 2)  // a + c
    }

    func testAnyConvoyUnread() {
        let latest = ["x": Date(timeIntervalSince1970: 300), "y": Date(timeIntervalSince1970: 100)]
        let read = ["x": Date(timeIntervalSince1970: 200), "y": Date(timeIntervalSince1970: 100)]
        XCTAssertTrue(ChannelThread.anyConvoyUnread(latestByConvoy: latest, lastReadByConvoy: read))
        // caught up on x, and a convoy with no marker is unread:
        let read2 = ["x": Date(timeIntervalSince1970: 400)]
        XCTAssertTrue(ChannelThread.anyConvoyUnread(latestByConvoy: latest, lastReadByConvoy: read2))  // y has no marker
        XCTAssertFalse(
            ChannelThread.anyConvoyUnread(
                latestByConvoy: ["x": Date(timeIntervalSince1970: 100)],
                lastReadByConvoy: ["x": Date(timeIntervalSince1970: 200)]))
    }

    // MARK: - Blocking

    func testFilterHiddenAuthorsDropsBlockedBothDirections() {
        let messages = [msg("a", secs: 1, sender: "friend"), msg("b", secs: 2, sender: "blocked")]
        let filtered = ChatBlockVisibility.filterHiddenAuthors(messages, hidden: ["blocked"])
        XCTAssertEqual(filtered.map(\.id), ["a"])
        XCTAssertEqual(ChatBlockVisibility.filterHiddenAuthors(messages, hidden: []).count, 2)
    }

    func testNewestVisibleSkipsHiddenAuthors() {
        // newest-first window
        let window = [msg("b", secs: 2, sender: "blocked"), msg("a", secs: 1, sender: "friend")]
        XCTAssertEqual(ChatBlockVisibility.newestVisible(window, hidden: ["blocked"])?.id, "a")
        XCTAssertNil(ChatBlockVisibility.newestVisible(window, hidden: ["blocked", "friend"]))
    }

    // MARK: - ChannelErrorMapper

    func testMapSend() {
        XCTAssertEqual(ChannelErrorMapper.mapSend(.unauthenticated), .signedOut)
        XCTAssertEqual(ChannelErrorMapper.mapSend(.permissionDenied), .notMember)
        XCTAssertEqual(ChannelErrorMapper.mapSend(.invalidArgument), .invalid)
        XCTAssertEqual(ChannelErrorMapper.mapSend(.failedPrecondition), .cannotDeliver)
        XCTAssertEqual(ChannelErrorMapper.mapSend(.notFound), .cannotDeliver)
        XCTAssertEqual(ChannelErrorMapper.mapSend(.other), .generic)
        XCTAssertTrue(ChannelSendError.generic.isRetryable)
        XCTAssertFalse(ChannelSendError.cannotDeliver.isRetryable)
    }

    func testReportReasonWireStrings() {
        XCTAssertEqual(ChatReportReason.hateOrAbuse.wire, "hate_or_abuse")
        XCTAssertEqual(ChatReportReason.unsafeDriving.wire, "unsafe_driving")
        XCTAssertEqual(ChatReportReason.allCases.map(\.wire).count, 6)
    }

    // MARK: - Convoy-list projection

    func testChatEligibleConvoysKeepsOnlyAcceptedViewer() {
        let convoys = ConvoyChatMapper.chatEligibleConvoys([
            "convoys": [
                [
                    "convoyId": "c1", "status": "active", "title": "Trip",
                    "viewer": ["inviteStatus": "accepted"],
                    "members": [
                        ["inviteStatus": "accepted", "displayName": "Ada"],
                        ["inviteStatus": "accepted", "displayName": "Bo"],
                        ["inviteStatus": "invited", "displayName": "Cy"],
                    ],
                    "createdAt": "2026-08-01T10:00:00Z",
                ],
                [  // viewer not accepted → dropped
                    "convoyId": "c2", "status": "active",
                    "viewer": ["inviteStatus": "invited"],
                ],
                ["status": "active", "viewer": ["inviteStatus": "accepted"]],  // no id → dropped
            ]
        ])
        XCTAssertEqual(convoys.count, 1)
        let c1 = convoys[0]
        XCTAssertEqual(c1.convoyId, "c1")
        XCTAssertEqual(c1.title, "Trip")
        XCTAssertEqual(c1.memberCount, 2)  // accepted only
        XCTAssertEqual(c1.memberNames, ["Ada", "Bo"])
        XCTAssertNotNil(c1.createdAt)
    }

    func testChatEligibleConvoysFallsBackToMemberUidsCount() {
        let convoys = ConvoyChatMapper.chatEligibleConvoys([
            "convoys": [
                [
                    "convoyId": "c1", "status": "forming",
                    "viewer": ["inviteStatus": "accepted"],
                    "memberUids": ["u1", "u2", "u3"],
                ]
            ]
        ])
        XCTAssertEqual(convoys.first?.memberCount, 3)
        XCTAssertNil(convoys.first?.title)  // absent title → nil
    }

    // MARK: - Convoy row format

    func testGroupSplitsOngoingVsPastNewestFirst() {
        let convoys = [
            ChatConvoy(convoyId: "a", status: "active", memberCount: 1, createdAt: Date(timeIntervalSince1970: 100)),
            ChatConvoy(convoyId: "e1", status: "ended", memberCount: 1, createdAt: Date(timeIntervalSince1970: 200)),
            ChatConvoy(convoyId: "e2", status: "ended", memberCount: 1, createdAt: Date(timeIntervalSince1970: 300)),
            ChatConvoy(convoyId: "f", status: "forming", memberCount: 1, createdAt: Date(timeIntervalSince1970: 50)),
        ]
        let grouped = ConvoyRowFormat.group(convoys)
        XCTAssertEqual(grouped.ongoing.map(\.convoyId), ["a", "f"])  // newest-first
        XCTAssertEqual(grouped.past.map(\.convoyId), ["e2", "e1"])  // newest-first
    }

    func testPhase() {
        XCTAssertEqual(ConvoyRowFormat.phase(status: "ended"), .past)
        XCTAssertEqual(ConvoyRowFormat.phase(status: "active"), .ongoing)
        XCTAssertEqual(ConvoyRowFormat.phase(status: "forming"), .ongoing)
    }

    func testMemberLabelTruncatesAndCountsOverflow() {
        let label = ConvoyRowFormat.memberLabel(names: [" Ada ", "Bo", "", "Cy", "Di"])
        XCTAssertEqual(label.shownNames, ["Ada", "Bo"])
        XCTAssertEqual(label.overflow, 2)  // Cy, Di (blank dropped)
        XCTAssertEqual(ConvoyRowFormat.memberLabel(names: []).shownNames, [])
    }

    // MARK: - Tab order

    func testChatTabOrderIsLoadBearing() {
        XCTAssertEqual(ChatTab.allCases, [.community, .convoys, .friends, .notifications])
        XCTAssertEqual(ChatTab.community.rawValue, 0)
        XCTAssertEqual(ChatTab.notifications.rawValue, 3)
        XCTAssertEqual(ChatTab.defaultTab, .community)
        XCTAssertTrue(ChatTab.community.isImplemented)
        XCTAssertTrue(ChatTab.convoys.isImplemented)
        XCTAssertFalse(ChatTab.friends.isImplemented)
        XCTAssertFalse(ChatTab.notifications.isImplemented)
    }

    // MARK: - Fixtures

    private func msg(
        _ id: String,
        secs: TimeInterval?,
        sender: String = "u1",
        text: String = "t"
    ) -> ChannelMessage {
        let date = secs.map { Date(timeIntervalSince1970: $0) }
        return ChannelMessage(
            id: id, senderUid: sender, text: text,
            createdAt: date, createdAtIso: date.map(ChannelTime.isoString))
    }

    private func pendingBubble(_ clientId: String) -> ChannelMessage {
        ChannelMessage(
            id: clientId, senderUid: "me", text: "pending",
            createdAt: Date(timeIntervalSince1970: 500), clientId: clientId, deliveryState: .sending)
    }
}
