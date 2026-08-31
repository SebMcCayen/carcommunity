import Foundation

/// One emission of the owner badges listener — the iOS port of Android's
/// `BadgesState` minus `Loading`: a repository stream only ever emits SETTLED
/// results (a snapshot or a failure), and the coordinator supplies the loading
/// state before the first emission — the same split as ``EventsListSnapshot``
/// / ``GarageSnapshot``.
enum BadgesSnapshot: Equatable, Sendable {
    /// The listener failed. `code` is the bare Firestore status name when one
    /// was available (`PERMISSION_DENIED` for an undeployed rule,
    /// `UNAVAILABLE` when offline, …) — a stable, PII-safe diagnosis, never
    /// exception text — the same rule as ``EventsListSnapshot/failed(code:)``.
    case failed(code: String?)
    /// A fresh snapshot of the member's earned badges, already list-sorted
    /// (``Badges/sortedForList(_:)``).
    case loaded([Badge])
}

/// Read-only badges access — the iOS port of Android's `BadgesRepository.kt`
/// and `BadgeProgressRepository.kt`, folded into ONE protocol because the wall
/// needs both halves. Firebase-free so the coordinator and screen are
/// unit-testable with fakes.
///
/// Two inputs, two very different trust levels:
///
///  - EARNED badges (``observeBadges(uid:)``) are a direct owner Firestore
///    listener on `users/{uid}/badges` — the awards are public (any
///    authenticated user may read one member's wall, firebase/firestore.rules)
///    and backend-only to write.
///  - PROGRESS counters (``fetchMyProgress()``) come from the owner-only
///    `badges-getMyProgress` callable, because the authoritative
///    `badgeProgress/{uid}` document is denied to EVERY client, owner included.
///    A failure degrades to nil (no bars), never an error — the wall is fully
///    usable (trophies + goals) without the bars.
protocol BadgesRepository: AnyObject, Sendable {
    /// The member's earned badges, newest-award first. Each call returns a
    /// fresh stream backed by its own listener; terminating the stream
    /// (dropping the iteration) detaches the listener.
    func observeBadges(uid: String) -> AsyncStream<BadgesSnapshot>

    /// The caller's OWN server-verified ladder counters, or nil when they
    /// cannot be fetched (any callable failure — offline, App Check, an
    /// unexpected payload). A nil simply leaves the ladders bar-less; it is
    /// never surfaced as an error. The uid is taken server-side from the auth
    /// context, so there is no argument that could select another member.
    func fetchMyProgress() async -> BadgeCounters?

    /// The signed-in user's uid, or nil with no session — the same seam the
    /// events/garage repositories expose (the shell passes no identity).
    func currentUserId() -> String?
}

/// Projects the `badges-getMyProgress` payload into ``BadgeCounters`` — the
/// iOS port of Android's `BadgeProgressResponseParser`.
///
/// Pure and defensive: a counter that is missing, non-numeric, non-finite or
/// negative is read as nil (no bar) rather than a fabricated one — even though
/// the callable already sanitises server-side, the client never trusts the
/// wire shape blindly. Unit-tested off-device with plain dictionaries.
enum BadgeProgressResponseParser {
    static func parse(_ data: [String: Any]) -> BadgeCounters {
        BadgeCounters(
            crownsCollected: counter(data, "crownsCollected"),
            lifetimeDistanceMeters: counter(data, "lifetimeDistanceMeters"),
            verifiedEventsAttended: counter(data, "verifiedEventsAttended"),
            bestDayStreak: counter(data, "bestDayStreak"),
            convoysLed: counter(data, "convoysLed"),
            vehiclesInGarage: counter(data, "vehiclesInGarage"),
            seasonsWon: counter(data, "seasonsWon"),
            wavesSent: counter(data, "wavesSent")
        )
    }

    /// One counter as a non-negative `Int64`, or nil. The callable SDK decodes
    /// JSON numbers as `NSNumber`; a floating value is trusted only when finite
    /// and is floored to match the server's integer counter. A boolean
    /// `NSNumber` (Obj-C bridges `Bool` to `NSNumber`) is rejected so `true`
    /// never reads as the counter `1`.
    private static func counter(_ data: [String: Any], _ key: String) -> Int64? {
        guard let number = data[key] as? NSNumber else { return nil }
        // Reject the boolean bridge: `NSNumber(true)` would otherwise floor to 1.
        if CFGetTypeID(number) == CFBooleanGetTypeID() { return nil }
        let asDouble = number.doubleValue
        guard asDouble.isFinite else { return nil }
        let floored = Int64(asDouble.rounded(.down))
        return floored >= 0 ? floored : nil
    }
}
