import XCTest

@testable import KCC

/// Unit tests for the pure notifications domain: tolerant document decoding,
/// the unread/sort logic, and the deep-link derivation for EVERY category
/// (the exported tap-destination map the future wiring PR routes on). No
/// Firebase — the decoder takes a plain dictionary, the same seam the Firebase
/// repository funnels `Timestamp`-converted fields through.
final class NotificationsModelTests: XCTestCase {

    private let recipient = "me-uid"

    // MARK: - decoding

    func testDecodeFullDocument() {
        let created = Date(timeIntervalSince1970: 1_700_000_000)
        let item = AppNotification.decode(
            id: "n1",
            fields: [
                "category": "event_created",
                "title": "New event",
                "previewText": "A cars & coffee meet",
                "body": "Full body text",
                "read": true,
                "createdAt": created,
                "actionType": "open_event",
                "relatedEntityId": "event-123",
            ]
        )
        XCTAssertEqual(
            item,
            AppNotification(
                id: "n1",
                category: .eventCreated,
                title: "New event",
                previewText: "A cars & coffee meet",
                body: "Full body text",
                isRead: true,
                createdAt: created,
                actionType: .openEvent,
                relatedEntityId: "event-123"
            )
        )
    }

    func testDecodeDropsDocumentWithoutTitle() {
        XCTAssertNil(
            AppNotification.decode(id: "n1", fields: ["category": "system_notice"])
        )
    }

    func testDecodeUnknownCategoryDegradesToSystemNotice() {
        let item = AppNotification.decode(id: "n1", fields: ["title": "Hi", "category": "brand_new"])
        XCTAssertEqual(item?.category, .systemNotice)
    }

    func testDecodeUnknownActionTypeDegradesToNone() {
        let item = AppNotification.decode(
            id: "n1",
            fields: ["title": "Hi", "actionType": "open_teleporter"]
        )
        XCTAssertEqual(item?.actionType, .none)
    }

    func testDecodeDefaultsReadFlagAndOptionalsWhenAbsent() {
        let item = AppNotification.decode(id: "n1", fields: ["title": "Hi"])
        XCTAssertEqual(item?.isRead, false)
        XCTAssertNil(item?.createdAt)
        XCTAssertNil(item?.previewText)
        XCTAssertNil(item?.body)
        XCTAssertNil(item?.relatedEntityId)
        XCTAssertEqual(item?.category, .systemNotice)
        XCTAssertEqual(item?.actionType, .none)
    }

    // MARK: - unread + sort

    func testUnreadCountCountsOnlyUnread() {
        let items = [
            Self.item("a", read: false),
            Self.item("b", read: true),
            Self.item("c", read: false),
        ]
        XCTAssertEqual(Notifications.unreadCount(items), 2)
    }

    func testHasUnreadRules() {
        let older = Date(timeIntervalSince1970: 100)
        let newer = Date(timeIntervalSince1970: 200)
        // Empty inbox: nothing unseen.
        XCTAssertFalse(Notifications.hasUnread(newest: nil, lastSeen: newer))
        // Never opened (nil marker) + a notification exists: unseen.
        XCTAssertTrue(Notifications.hasUnread(newest: older, lastSeen: nil))
        // Newest post-dates the marker: unseen.
        XCTAssertTrue(Notifications.hasUnread(newest: newer, lastSeen: older))
        // Newest at/older than the marker: seen.
        XCTAssertFalse(Notifications.hasUnread(newest: older, lastSeen: newer))
        XCTAssertFalse(Notifications.hasUnread(newest: older, lastSeen: older))
    }

    func testNewestCreatedAtIgnoresSortOrderAndMissingTimestamps() {
        let items = [
            Self.item("a", createdAt: Date(timeIntervalSince1970: 100)),
            Self.item("b", createdAt: nil),
            Self.item("c", createdAt: Date(timeIntervalSince1970: 300)),
        ]
        XCTAssertEqual(Notifications.newestCreatedAt(items), Date(timeIntervalSince1970: 300))
    }

    func testSortedForInboxNewestFirstWithMissingTimestampsLast() {
        let a = Self.item("a", createdAt: Date(timeIntervalSince1970: 100))
        let b = Self.item("b", createdAt: nil)
        let c = Self.item("c", createdAt: Date(timeIntervalSince1970: 300))
        let sorted = Notifications.sortedForInbox([a, b, c])
        XCTAssertEqual(sorted.map(\.id), ["c", "a", "b"])
    }

    // MARK: - deep link, every category

    func testDeepLinkEventCategoriesOpenTheEvent() {
        for category in [
            NotificationCategory.eventCreated, .eventReminder, .eventUpdated, .eventCancelled,
        ] {
            let link = Notifications.deepLink(
                category: category, relatedEntityId: "event-9", recipientUid: recipient
            )
            XCTAssertEqual(link, NotificationDeepLink(target: .event, entityId: "event-9"), "\(category)")
        }
    }

    func testDeepLinkDirectMessageResolvesTheOtherMember() {
        let link = Notifications.deepLink(
            category: .directMessage,
            relatedEntityId: "me-uid__friend-uid",
            recipientUid: recipient
        )
        XCTAssertEqual(link, NotificationDeepLink(target: .dm, entityId: "friend-uid"))
    }

    func testDeepLinkDirectMessageMalformedPairIdFallsBackToTheList() {
        // Not a two-part pairId → no counterpart → the DM list, never an
        // arbitrary segment handed to the navigator.
        for pairId in ["single", "a__b__c", "someone__other", ""] {
            let link = Notifications.deepLink(
                category: .directMessage, relatedEntityId: pairId, recipientUid: recipient
            )
            XCTAssertEqual(link, NotificationDeepLink(target: .dm, entityId: nil), "\(pairId)")
        }
    }

    func testDeepLinkConvoyCategoriesOpenTheConvoy() {
        for category in [NotificationCategory.convoyInvite, .convoyUpdate] {
            let link = Notifications.deepLink(
                category: category, relatedEntityId: "convoy-7", recipientUid: recipient
            )
            XCTAssertEqual(link, NotificationDeepLink(target: .convoys, entityId: "convoy-7"), "\(category)")
        }
    }

    func testDeepLinkConvoyChatOpensTheConvoyChat() {
        let link = Notifications.deepLink(
            category: .convoyChat, relatedEntityId: "convoy-7", recipientUid: recipient
        )
        XCTAssertEqual(link, NotificationDeepLink(target: .convoyChat, entityId: "convoy-7"))
    }

    func testDeepLinkCommunityChatHasNoEntity() {
        let link = Notifications.deepLink(
            category: .communityChat, relatedEntityId: "message-42", recipientUid: recipient
        )
        XCTAssertEqual(link, NotificationDeepLink(target: .communityChat, entityId: nil))
    }

    func testDeepLinkFriendRequestOpensFriends() {
        let link = Notifications.deepLink(
            category: .friendRequest, relatedEntityId: "requester-uid", recipientUid: recipient
        )
        XCTAssertEqual(link, NotificationDeepLink(target: .friends, entityId: "requester-uid"))
    }

    func testDeepLinkWaveFallsBackToTheList() {
        let link = Notifications.deepLink(
            category: .wave, relatedEntityId: "waver-uid", recipientUid: recipient
        )
        XCTAssertEqual(link, NotificationDeepLink(target: .notifications, entityId: nil))
    }

    func testDeepLinkSubscriptionHasNoEntity() {
        let link = Notifications.deepLink(
            category: .subscriptionStatus, relatedEntityId: "ignored", recipientUid: recipient
        )
        XCTAssertEqual(link, NotificationDeepLink(target: .subscription, entityId: nil))
    }

    func testDeepLinkOperationalCategoriesFallBackToTheList() {
        for category in [
            NotificationCategory.adminMessage, .accountWarning, .accountSuspension, .systemNotice,
        ] {
            let link = Notifications.deepLink(
                category: category, relatedEntityId: "op-ref", recipientUid: recipient
            )
            XCTAssertEqual(
                link, NotificationDeepLink(target: .notifications, entityId: nil), "\(category)"
            )
        }
    }

    func testDeepLinkBlankEntityIdNormalisesToNil() {
        let link = Notifications.deepLink(
            category: .eventCreated, relatedEntityId: "   ", recipientUid: recipient
        )
        XCTAssertEqual(link, NotificationDeepLink(target: .event, entityId: nil))
    }

    func testDeepLinkFromItemUsesTheItemsCategoryAndEntity() {
        let item = Self.item("n1", category: .eventReminder, relatedEntityId: "event-1")
        XCTAssertEqual(
            Notifications.deepLink(for: item, recipientUid: recipient),
            NotificationDeepLink(target: .event, entityId: "event-1")
        )
    }

    // MARK: - fixtures

    private static func item(
        _ id: String,
        category: NotificationCategory = .systemNotice,
        read: Bool = false,
        createdAt: Date? = Date(timeIntervalSince1970: 1_700_000_000),
        relatedEntityId: String? = nil
    ) -> AppNotification {
        AppNotification(
            id: id,
            category: category,
            title: "Title \(id)",
            isRead: read,
            createdAt: createdAt,
            relatedEntityId: relatedEntityId
        )
    }
}
