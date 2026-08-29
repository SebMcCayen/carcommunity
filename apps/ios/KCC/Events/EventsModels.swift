import Foundation

/// Events domain model + pure logic — the iOS port of Android's
/// `events/Event.kt` (Phase 12 slice 9), restricted to what the read-only
/// list slice needs.
///
/// Mirrors the backend events-core contract: the event status vocabulary and
/// the teaser/detail split (`events/{id}` teaser vs `events/{id}/details/private`
/// member-gated — contracts/schemas/events.schema.json `eventTeaser`). Pure
/// Swift so it is unit-testable and shared by the repository and screens.

/// Event lifecycle status (events/{id}.status).
enum EventStatus: String, Equatable, Sendable, CaseIterable {
    case draft
    case published
    case cancelled
    case completed

    /// The Firestore wire value (identical to the case name; kept as an
    /// explicit accessor so call sites read like Android's `status.wire`).
    var wire: String { rawValue }

    static func fromWire(_ value: String?) -> EventStatus? {
        guard let value else { return nil }
        return EventStatus(rawValue: value)
    }
}

/// Denormalized RSVP tallies stored on the teaser event doc
/// (events/{id}.rsvpCounts, maintained by the events-onRsvpWrite trigger).
struct RsvpCounts: Equatable, Sendable {
    let going: Int
    let maybe: Int
    let notGoing: Int

    var total: Int { going + maybe + notGoing }

    static let empty = RsvpCounts(going: 0, maybe: 0, notGoing: 0)

    /// Reads the rsvpCounts map defensively (missing/malformed/negative → 0),
    /// mirroring Android's `RsvpCounts.fromMap`.
    static func fromMap(_ map: [String: Any]?) -> RsvpCounts {
        guard let map else { return .empty }
        func read(_ key: String) -> Int {
            guard let number = map[key] as? NSNumber else { return 0 }
            return max(0, number.intValue)
        }
        return RsvpCounts(
            going: read("going"),
            maybe: read("maybe"),
            notGoing: read("not_going")
        )
    }
}

/// Teaser-safe event summary (events/{id}) — visible to any authenticated
/// user (firebase/firestore.rules: status published/completed readable).
///
/// Carries the PUBLIC map location (locationName + latitude/longitude) as of
/// the deliberate 2026-07 change; the long description and the precise street
/// address stay member-only under `details/private` (not read by this slice).
struct EventSummary: Equatable, Sendable, Identifiable {
    let id: String
    let title: String
    let summary: String?
    let startsAt: Date?
    let endsAt: Date?
    /// Coarse area label. Nil when the organiser gave none — the member
    /// create form dropped its input (2026-08); older events keep theirs.
    let approximateArea: String?
    /// Public place name for the map pin; nil when the organiser set none.
    let locationName: String?
    let latitude: Double?
    let longitude: Double?
    let isOfficial: Bool
    let status: EventStatus
    let counts: RsvpCounts
}

/// Pure events-list logic shared by the repository, coordinator, and screen.
enum Events {
    /// Maximum published events the Firestore listener subscribes to
    /// (soonest start first, matching ``sortedForList(_:)``) — mirrors
    /// Android's `Events.PUBLISHED_EVENTS_QUERY_LIMIT`. Keeps the snapshot
    /// bounded as the `events` collection grows without bound; events
    /// starting furthest in the future simply fall off the list.
    static let publishedEventsQueryLimit = 200

    /// Published events sorted by soonest start first — nil start times last,
    /// original order preserved among ties (Android's `sortedForList`, which
    /// relies on a stable sort; the explicit index tie-break here guarantees
    /// the same without leaning on an undocumented stdlib property).
    static func sortedForList(_ events: [EventSummary]) -> [EventSummary] {
        events.enumerated()
            .sorted { lhs, rhs in
                switch (lhs.element.startsAt, rhs.element.startsAt) {
                case let (left?, right?):
                    if left != right { return left < right }
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
}
