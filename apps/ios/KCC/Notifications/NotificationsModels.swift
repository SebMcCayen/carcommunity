import Foundation

/// In-app notifications domain model + pure logic — the iOS port of Android's
/// `notifications/Notification.kt` (Phase 12 slice 21, in-app portion).
///
/// Mirrors the backend `notifications-core` contract
/// (functions/src/notifications/notifications-core.ts): the notification
/// categories, the durable inbox item shape (notifications/{uid}/items), and
/// the tap-destination derivation (`buildPushDeepLink`). Pure Swift so it is
/// unit-testable off-device and shared by the repository, coordinator, and
/// screens.
///
/// Push DELIVERY (APNs) and push-token registration are deliberately OUT OF
/// SCOPE — they await the end-of-MVP Apple/Firebase console setup, exactly as
/// Android's header notes for its own FCM slice. This is the Firestore-backed
/// in-app inbox only.

// MARK: - Category

/// Notification category (notifications/{uid}/items/{id}.category) — the closed
/// set the backend `NOTIFICATION_CATEGORIES` recognises, Android's
/// `NotificationCategory`. The Firestore wire value is carried explicitly so
/// call sites read like Android's `category.wire`.
enum NotificationCategory: String, Equatable, Sendable, CaseIterable {
    case eventCreated = "event_created"
    case eventReminder = "event_reminder"
    case eventUpdated = "event_updated"
    case eventCancelled = "event_cancelled"
    case adminMessage = "admin_message"
    case accountWarning = "account_warning"
    case accountSuspension = "account_suspension"
    case subscriptionStatus = "subscription_status"
    case systemNotice = "system_notice"
    // Social categories: member-to-member activity. All optional — a user can
    // always silence other members (backend SOCIAL_NOTIFICATION_CATEGORIES).
    case directMessage = "direct_message"
    case communityChat = "community_chat"
    case convoyChat = "convoy_chat"
    case friendRequest = "friend_request"
    case convoyInvite = "convoy_invite"
    /// Convoy MEMBERSHIP / LIFECYCLE notices (someone left, leadership
    /// transferred, the convoy ended). Deliberately separate from
    /// ``convoyInvite`` so silencing invites does not silence "the convoy you
    /// are driving in just ended".
    case convoyUpdate = "convoy_update"
    /// A nearby live sharer waved at you OUTSIDE a convoy (backend
    /// `live.sendWave`). Social, opt-out-able.
    case wave

    /// The Firestore wire value (identical to the raw value; kept as an
    /// explicit accessor so call sites read like Android's `category.wire`).
    var wire: String { rawValue }

    /// Unknown categories fall back to a neutral system notice for display —
    /// Android's `NotificationCategory.fromWire`.
    static func fromWire(_ value: String?) -> NotificationCategory {
        guard let value, let category = NotificationCategory(rawValue: value) else {
            return .systemNotice
        }
        return category
    }

    /// The `notifications.category*` localization key for this category —
    /// the iOS port of Android's `NotificationCategory.labelRes()`. Keeping
    /// the mapping here means the inbox rows and the settings rows can't drift
    /// apart as categories are added.
    var labelKey: String {
        switch self {
        case .eventCreated: return "notifications.categoryEventCreated"
        case .eventReminder: return "notifications.categoryEventReminder"
        case .eventUpdated: return "notifications.categoryEventUpdated"
        case .eventCancelled: return "notifications.categoryEventCancelled"
        case .adminMessage: return "notifications.categoryAdminMessage"
        case .accountWarning: return "notifications.categoryAccountWarning"
        case .accountSuspension: return "notifications.categoryAccountSuspension"
        case .subscriptionStatus: return "notifications.categorySubscription"
        case .systemNotice: return "notifications.categorySystem"
        case .directMessage: return "notifications.categoryDirectMessage"
        case .communityChat: return "notifications.categoryCommunityChat"
        case .convoyChat: return "notifications.categoryConvoyChat"
        case .friendRequest: return "notifications.categoryFriendRequest"
        case .convoyInvite: return "notifications.categoryConvoyInvite"
        case .convoyUpdate: return "notifications.categoryConvoyUpdate"
        case .wave: return "notifications.categoryWave"
        }
    }
}

// MARK: - Action type

/// What the backend says this item is FOR (notifications-core `actionType`,
/// `NOTIFICATION_ACTION_TYPES`). The full contract vocabulary is modelled for
/// tolerant decoding; an unknown/absent value degrades to ``none`` (a plain,
/// non-actionable row) — Android's `NotificationActionType.fromWire`.
enum NotificationActionType: String, Equatable, Sendable, CaseIterable {
    case none
    case openNotifications = "open_notifications"
    case openEvent = "open_event"
    case openProfile = "open_profile"
    case openSubscription = "open_subscription"
    case openSettings = "open_settings"

    var wire: String { rawValue }

    static func fromWire(_ value: String?) -> NotificationActionType {
        guard let value, let type = NotificationActionType(rawValue: value) else {
            return .none
        }
        return type
    }
}

// MARK: - Inbox item

/// A durable inbox item (notifications/{uid}/items/{id}) — Android's
/// `AppNotification`.
///
/// `relatedEntityId` is the backend's "who/what is this about" pointer. Its
/// meaning is per-category (an eventId for event notices, the convoy id for
/// convoy notices, the DM pairId for a direct message, the other member's uid
/// for a friend request), which is exactly what ``NotificationDeepLink``
/// resolves.
struct AppNotification: Equatable, Sendable, Identifiable {
    let id: String
    let category: NotificationCategory
    let title: String
    let previewText: String?
    let body: String?
    let isRead: Bool
    let createdAt: Date?
    let actionType: NotificationActionType
    let relatedEntityId: String?

    init(
        id: String,
        category: NotificationCategory,
        title: String,
        previewText: String? = nil,
        body: String? = nil,
        isRead: Bool = false,
        createdAt: Date? = nil,
        actionType: NotificationActionType = .none,
        relatedEntityId: String? = nil
    ) {
        self.id = id
        self.category = category
        self.title = title
        self.previewText = previewText
        self.body = body
        self.isRead = isRead
        self.createdAt = createdAt
        self.actionType = actionType
        self.relatedEntityId = relatedEntityId
    }

    /// Tolerant decoding from a raw Firestore document — the iOS port of
    /// Android's `DocumentSnapshot.toNotification()`. A row without a `title`
    /// is dropped (nil); everything else degrades gracefully (unknown category
    /// → ``NotificationCategory/systemNotice``, unknown/absent actionType →
    /// ``NotificationActionType/none``, absent read flag → false).
    ///
    /// `createdAt` is read as a `Date` so the decoder stays Firebase-free and
    /// unit-testable: the ``FirebaseNotificationsRepository`` converts the
    /// Firestore `Timestamp` to a `Date` before handing the fields here (the
    /// same seam that keeps ``RsvpCounts/fromMap(_:)`` Firebase-free).
    static func decode(id: String, fields: [String: Any]) -> AppNotification? {
        guard let title = fields["title"] as? String else { return nil }
        return AppNotification(
            id: id,
            category: NotificationCategory.fromWire(fields["category"] as? String),
            title: title,
            previewText: fields["previewText"] as? String,
            body: fields["body"] as? String,
            // Firestore booleans arrive as NSNumber; `as? Bool` reads them.
            isRead: fields["read"] as? Bool ?? false,
            createdAt: fields["createdAt"] as? Date,
            actionType: NotificationActionType.fromWire(fields["actionType"] as? String),
            relatedEntityId: fields["relatedEntityId"] as? String
        )
    }
}

// MARK: - Deep link target

/// Where tapping a notification should land — the iOS port of the backend's
/// `PUSH_DEEP_LINK_TARGETS` (functions/src/notifications/notifications-core.ts).
///
/// Values name screens the shell ALREADY has (``ShellRoute`` / chat-hub tabs);
/// this is a naming of existing destinations, not a new navigation graph. The
/// enum is EXPORTED so a later wiring PR can route taps — routing is NOT wired
/// here (no ``ShellView`` edits in this slice).
enum NotificationDeepLinkTarget: String, Equatable, Sendable, CaseIterable {
    case dm
    case communityChat = "community_chat"
    case convoyChat = "convoy_chat"
    case convoys
    case friends
    case event
    case subscription
    /// The notifications list itself — the fallback destination for a notice
    /// with no per-entity anchor (system notices, waves, admin messages).
    case notifications
}

/// A resolved tap destination: the ``NotificationDeepLinkTarget`` plus the
/// entity id the target needs (otherUid, convoyId, eventId), or nil when the
/// target is a single screen with no per-entity anchor — the iOS port of the
/// backend `PushDeepLink` interface.
struct NotificationDeepLink: Equatable, Sendable {
    let target: NotificationDeepLinkTarget
    let entityId: String?
}

// MARK: - Pure inbox logic

/// Pure notification-inbox logic shared by the repository, coordinator, and
/// screen — Android's `Notifications` object.
enum Notifications {
    /// Maximum inbox items the Firestore listener subscribes to (newest first
    /// by createdAt). Keeps the snapshot bounded as the per-user collection
    /// grows; older items simply fall off the inbox — Android's
    /// `INBOX_QUERY_LIMIT`.
    static let inboxQueryLimit = 50

    /// Number of unread items — Android's `unreadCount`.
    static func unreadCount(_ items: [AppNotification]) -> Int {
        items.reduce(into: 0) { count, item in
            if !item.isRead { count += 1 }
        }
    }

    /// The newest notification instant in `items` — the value compared against
    /// the last-seen marker in ``hasUnread(newest:lastSeen:)``. Nil when the
    /// inbox is empty or no item carries a parseable createdAt. Independent of
    /// client sort order (takes the maximum) — Android's `newestCreatedAtMillis`.
    static func newestCreatedAt(_ items: [AppNotification]) -> Date? {
        items.compactMap(\.createdAt).max()
    }

    /// True when the inbox has something the user has not SEEN yet: the newest
    /// notification post-dates the caller's last-seen marker (nil marker =
    /// never opened → any notification is unseen). Drives the Notifications
    /// red dot — Android's `hasUnread`, DELIBERATELY separate from the per-item
    /// read flag: opening the inbox stamps the marker (`markSeen`) and clears
    /// the dot WITHOUT marking every row read. A notification with no parseable
    /// createdAt can never be shown to be newer than the marker, so it never
    /// lights the dot on its own.
    static func hasUnread(newest: Date?, lastSeen: Date?) -> Bool {
        guard let newest else { return false }
        guard let lastSeen else { return true }
        return newest > lastSeen
    }

    /// Newest first; items without a timestamp sort last (stable among ties) —
    /// Android's `sortedForInbox`. The explicit index tie-break guarantees a
    /// stable order without leaning on an undocumented stdlib property (the
    /// same guard ``Events/sortedForList(_:)`` uses).
    static func sortedForInbox(_ items: [AppNotification]) -> [AppNotification] {
        items.enumerated()
            .sorted { lhs, rhs in
                switch (lhs.element.createdAt, rhs.element.createdAt) {
                case let (left?, right?):
                    if left != right { return left > right }
                    return lhs.offset < rhs.offset
                case (nil, nil):
                    return lhs.offset < rhs.offset
                case (nil, .some):
                    return false
                case (.some, nil):
                    return true
                }
            }
            .map(\.element)
    }

    /// Derives the tap destination from an item's category + `relatedEntityId`
    /// — the iOS port of the backend `buildPushDeepLink`. No producer changes
    /// and no new wire field: the same `relatedEntityId` producers already
    /// write.
    ///
    /// The one non-obvious case is ``NotificationCategory/directMessage``,
    /// whose `relatedEntityId` is the conversation pairId (`uidA__uidB`,
    /// sorted). The DM screen opens by the OTHER member's uid, so the
    /// recipient's own uid is subtracted out. A pairId that does not split into
    /// EXACTLY two parts including the recipient degrades to the conversation
    /// LIST (entityId nil) rather than handing an arbitrary segment to a
    /// navigator — the backend's strict-parse rule.
    static func deepLink(
        for item: AppNotification,
        recipientUid: String
    ) -> NotificationDeepLink {
        deepLink(
            category: item.category,
            relatedEntityId: item.relatedEntityId,
            recipientUid: recipientUid
        )
    }

    /// The category/relatedEntityId form of ``deepLink(for:recipientUid:)``,
    /// exposed for direct unit testing of every category branch.
    static func deepLink(
        category: NotificationCategory,
        relatedEntityId: String?,
        recipientUid: String
    ) -> NotificationDeepLink {
        switch category {
        case .directMessage:
            // Strict: the pairId must split into EXACTLY two parts, one of
            // which is the recipient — then the other is the counterpart. A
            // loose "first segment that isn't me" search would, given anything
            // that is not a well-formed pairId, deep-link the member into a
            // stranger's thread (backend dm-core note).
            let parts = relatedEntityId?.components(separatedBy: "__") ?? []
            if parts.count == 2, parts.contains(recipientUid),
                let other = parts.first(where: { $0 != recipientUid }) {
                return NotificationDeepLink(target: .dm, entityId: other)
            }
            // Without a resolvable counterpart the thread cannot be opened; the
            // conversation list is the closest correct destination.
            return NotificationDeepLink(target: .dm, entityId: nil)
        case .convoyChat:
            return NotificationDeepLink(target: .convoyChat, entityId: entityIdOrNil(relatedEntityId))
        case .communityChat:
            // Deliberately nil: the two producers write DIFFERENT id kinds into
            // this one field, so it names no single entity the client could
            // open by — the channel itself is the only unambiguous destination.
            return NotificationDeepLink(target: .communityChat, entityId: nil)
        case .convoyInvite, .convoyUpdate:
            // relatedEntityId is the convoy id — passing it through opens THAT
            // convoy rather than the convoy list.
            return NotificationDeepLink(target: .convoys, entityId: entityIdOrNil(relatedEntityId))
        case .friendRequest:
            return NotificationDeepLink(target: .friends, entityId: entityIdOrNil(relatedEntityId))
        case .wave:
            // The inbox has no profile deep-link target, so a tap lands on the
            // list rather than handing an arbitrary uid to a navigator with no
            // matching destination.
            return NotificationDeepLink(target: .notifications, entityId: nil)
        case .eventCreated, .eventReminder, .eventUpdated, .eventCancelled:
            // relatedEntityId is the eventId — passing it through opens THAT
            // event's detail rather than the events list.
            return NotificationDeepLink(target: .event, entityId: entityIdOrNil(relatedEntityId))
        case .subscriptionStatus:
            // Deliberately nil: the subscription screen is a single destination
            // with no per-entity anchor.
            return NotificationDeepLink(target: .subscription, entityId: nil)
        case .adminMessage, .accountWarning, .accountSuspension, .systemNotice:
            // These land on the notifications list, and their relatedEntityId
            // is an operator-supplied free-text reference with no client-side
            // meaning — it must not be handed to a navigator.
            return NotificationDeepLink(target: .notifications, entityId: nil)
        }
    }

    /// A blank/whitespace-only relatedEntityId is not an entity — normalising
    /// to nil keeps a malformed item degrading to the LIST screen instead of
    /// putting an empty entityId on the wire (backend `entityIdOrNull`).
    private static func entityIdOrNil(_ relatedEntityId: String?) -> String? {
        let trimmed = relatedEntityId?.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let trimmed, !trimmed.isEmpty else { return nil }
        return trimmed
    }
}
