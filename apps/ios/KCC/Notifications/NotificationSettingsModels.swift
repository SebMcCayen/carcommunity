import Foundation

/// Notification-settings domain model + pure logic — the iOS port of Android's
/// per-category preference model in `notifications/NotificationSettings.kt`.
///
/// Per-category in-app / push opt-outs are stored on the owner-writable
/// `userPrivate/{uid}.notificationPreferences` map (category → { inApp, push }),
/// a direct rules-validated write — NO callable. Missing entries default to
/// enabled. The essential account-notice categories can never be disabled
/// (also enforced at delivery time by the backend writer). Pure Swift so the
/// core is unit-testable off-device; tracks the backend
/// `NOTIFICATION_CATEGORIES` that this client's categories are defined against.

/// The category ids the settings screen renders, and their essential/social
/// classification — Android's `NotificationCategories`.
enum NotificationCategories {
    /// Every category the backend defines in `NOTIFICATION_CATEGORIES`, listed
    /// in the order the settings screen renders them. The set of ids must stay
    /// in sync with that backend list; the ORDER intentionally moves the
    /// essential account notices last (they render locked-on) rather than
    /// splitting the tunable categories in two — Android's `ACTIVE`.
    static let active: [NotificationCategory] = [
        .eventCreated,
        .eventReminder,
        .eventUpdated,
        .eventCancelled,
        .adminMessage,
        .subscriptionStatus,
        .systemNotice,
        .directMessage,
        .communityChat,
        .convoyChat,
        .friendRequest,
        .convoyInvite,
        .convoyUpdate,
        .wave,
        .accountWarning,
        .accountSuspension,
    ]

    /// Social categories — member-to-member activity (backend
    /// `SOCIAL_NOTIFICATION_CATEGORIES`). Never essential: a user must always
    /// be able to silence other members.
    static let social: Set<NotificationCategory> = [
        .directMessage,
        .communityChat,
        .convoyChat,
        .friendRequest,
        .convoyInvite,
        .convoyUpdate,
        .wave,
    ]

    /// Essential account notices — cannot be disabled in-app or push
    /// (`ESSENTIAL_NOTIFICATION_CATEGORIES`).
    static let essential: Set<NotificationCategory> = [
        .accountWarning,
        .accountSuspension,
    ]

    static func isEssential(_ category: NotificationCategory) -> Bool {
        essential.contains(category)
    }
}

/// The notification channel a toggle targets — Android's `NotificationChannel`.
enum NotificationChannel: Equatable, Sendable, CaseIterable {
    case inApp
    case push
}

/// A category's per-channel opt-in. Both channels default to enabled —
/// Android's `CategoryPreference`.
struct CategoryPreference: Equatable, Sendable {
    let inApp: Bool
    let push: Bool

    init(inApp: Bool = true, push: Bool = true) {
        self.inApp = inApp
        self.push = push
    }
}

/// The owner's per-category preferences — Android's `NotificationPreferences`.
/// Missing categories read as enabled; essential categories always read as
/// fully enabled and reject toggles.
struct NotificationPreferences: Equatable, Sendable {
    private let byCategory: [NotificationCategory: CategoryPreference]

    init(_ byCategory: [NotificationCategory: CategoryPreference] = [:]) {
        self.byCategory = byCategory
    }

    /// All categories enabled — the default when no preferences are stored.
    static let allEnabled = NotificationPreferences()

    /// The effective preference for a category: essential categories are
    /// always fully enabled; everything else falls back to enabled when
    /// unset — Android's `effective`.
    func effective(_ category: NotificationCategory) -> CategoryPreference {
        if NotificationCategories.isEssential(category) {
            return CategoryPreference(inApp: true, push: true)
        }
        return byCategory[category] ?? CategoryPreference(inApp: true, push: true)
    }

    /// Returns a copy with one channel toggled; a no-op for essential
    /// categories — Android's `withToggle`.
    func withToggle(
        _ category: NotificationCategory,
        channel: NotificationChannel,
        enabled: Bool
    ) -> NotificationPreferences {
        if NotificationCategories.isEssential(category) { return self }
        let current = effective(category)
        let updated: CategoryPreference
        switch channel {
        case .inApp:
            updated = CategoryPreference(inApp: enabled, push: current.push)
        case .push:
            updated = CategoryPreference(inApp: current.inApp, push: enabled)
        }
        var next = byCategory
        next[category] = updated
        return NotificationPreferences(next)
    }

    /// Firestore representation: only non-essential categories are persisted —
    /// Android's `toFirestoreMap`. Keyed by the wire category id.
    func toFirestoreMap() -> [String: [String: Bool]] {
        var result: [String: [String: Bool]] = [:]
        for (category, preference) in byCategory where !NotificationCategories.isEssential(category) {
            result[category.wire] = ["inApp": preference.inApp, "push": preference.push]
        }
        return result
    }

    /// Tolerant decoding from the raw `notificationPreferences` map — Android's
    /// `fromFirestore`. An absent map, an unknown category id, or a malformed
    /// entry degrades to the enabled default rather than throwing.
    static func fromFirestore(_ raw: [String: Any]?) -> NotificationPreferences {
        guard let raw else { return .allEnabled }
        var parsed: [NotificationCategory: CategoryPreference] = [:]
        for (key, value) in raw {
            guard let category = NotificationCategory(rawValue: key),
                let entry = value as? [String: Any]
            else { continue }
            let inApp = entry["inApp"] as? Bool ?? true
            let push = entry["push"] as? Bool ?? true
            parsed[category] = CategoryPreference(inApp: inApp, push: push)
        }
        return NotificationPreferences(parsed)
    }
}
