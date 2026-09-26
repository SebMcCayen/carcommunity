import Foundation

/// Events domain model + pure logic — the iOS port of Android's
/// `events/Event.kt` (Phase 12 slice 9), restricted to what the list and
/// detail + RSVP slices need.
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

/// RSVP answer (events/{id}/rsvps/{uid}.status) — the contract enum
/// (contracts/schemas/events.schema.json `eventRsvpStatus`), Android's
/// `RsvpStatus`. The Firestore wire value differs from the Swift case name
/// for ``notGoing``, so the raw value IS the wire spelling.
enum RsvpStatus: String, Equatable, Sendable, CaseIterable {
    case going
    case maybe
    case notGoing = "not_going"

    /// The Firestore wire value (kept as an explicit accessor so call sites
    /// read like Android's `status.wire`).
    var wire: String { rawValue }

    static func fromWire(_ value: String?) -> RsvpStatus? {
        guard let value else { return nil }
        return RsvpStatus(rawValue: value)
    }
}

/// Member-gated detail (events/{id}/details/private) — the long description
/// and the precise street address only. The map location lives on the public
/// teaser (``EventSummary``); it is no longer here. Android's `EventDetail`.
struct EventDetail: Equatable, Sendable {
    let description: String?
    let address: String?
}

/// The single switch for every member-gated UI affordance — the iOS mirror of
/// Android's `config/MemberGating.kt`. `false` = show and enable member
/// features for any signed-in user (the current launch posture; the backend
/// `functions/src/shared/memberGating.ts` and the firestore.rules
/// `isActiveMember()` switch are flipped together with this).
///
/// Threaded as `passesMemberGate`, never as `isActiveMember`: while disabled,
/// any signed-in, non-suspended user passes. Suspension/deletion are NOT
/// handled here — the backend owns those regardless of this switch.
enum MemberGating {
    /// Mirrors Android `MemberGating.ENABLED` (currently `false`).
    static let enabled = false

    /// Resolves a member-gated decision. While ``enabled`` is false this
    /// returns true regardless of entitlement; flipping it back restores the
    /// exact previous behaviour (`isActiveMember` passthrough) at every call
    /// site.
    static func allows(isActiveMember: Bool) -> Bool {
        !enabled || isActiveMember
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
    /// Creator attribution used only to expose edit/remove to that same user.
    let createdByUserId: String?

    init(
        id: String, title: String, summary: String?, startsAt: Date?, endsAt: Date?,
        approximateArea: String?, locationName: String?, latitude: Double?, longitude: Double?,
        isOfficial: Bool, status: EventStatus, counts: RsvpCounts,
        createdByUserId: String? = nil
    ) {
        self.id = id
        self.title = title
        self.summary = summary
        self.startsAt = startsAt
        self.endsAt = endsAt
        self.approximateArea = approximateArea
        self.locationName = locationName
        self.latitude = latitude
        self.longitude = longitude
        self.isOfficial = isOfficial
        self.status = status
        self.counts = counts
        self.createdByUserId = createdByUserId
    }
}

/// Fields managed by the member create/edit form. Optional values are omitted
/// on create and explicitly cleared on edit, matching events.create/update.
struct EventFormInput: Equatable, Sendable {
    var title: String
    var startsAt: Date
    var description: String?
    var address: String?
    var latitude: Double?
    var longitude: Double?
    var publicSiteEnabled: Bool
}

enum CreateEventFailure: Equatable, Sendable { case rateLimited, unknown }
enum ManageEventFailure: Equatable, Sendable { case permissionDenied, immutable, unknown }
struct CreateEventError: Error, Equatable, Sendable { let reason: CreateEventFailure }
struct ManageEventError: Error, Equatable, Sendable { let reason: ManageEventFailure }

struct EventAttendee: Equatable, Sendable, Identifiable {
    let id: String
    let displayName: String?
    let avatarPath: String?
    let status: RsvpStatus
}

enum EventAttendeesResult: Equatable, Sendable {
    case loaded([EventAttendee])
    case requiresPaid
    case unavailable
    case failed
}

struct EventCheckInFix: Equatable, Sendable {
    let latitude: Double
    let longitude: Double
    let accuracyMeters: Double?
    let capturedAt: Date
    let isMock: Bool
}

/// Owner-readable progress from `eventAttendance/{eventId}__{uid}`. The raw
/// location samples and risk signals deliberately never cross this model.
struct EventAttendanceStatus: Equatable, Sendable {
    let verified: Bool
    let sampleCount: Int
    let recordCreatedAt: Date?

    var checkedIn: Bool { verified || sampleCount > 0 }
}

enum EventCheckInResult: String, Equatable, Sendable {
    case recorded, verified
    case alreadyVerified = "already_verified"
    case outsideGeofence = "outside_geofence"
    case outsideWindow = "outside_window"
    case positionTooOld = "position_too_old"
    case riskReview = "risk_review"
    case eventNotCheckinable = "event_not_checkinable"
    case unknown

    var isVerified: Bool { self == .verified || self == .alreadyVerified }
}

/// Pure events-list logic shared by the repository, coordinator, and screen.
enum Events {
    static let titleMax = 200
    static let descriptionMax = 10_000
    static let addressMax = 400
    static let checkInWindowBefore: TimeInterval = 30 * 60
    static let checkInWindowAfter: TimeInterval = 30 * 60
    static let defaultEventDuration: TimeInterval = 4 * 60 * 60
    static let maximumCheckInFixAge: TimeInterval = 60
    /// Mirrors the backend's minimum span between the first and confirming
    /// in-geofence samples. The server remains authoritative.
    static let requiredCheckInDwell: TimeInterval = 10 * 60
    /// Maximum published events the Firestore listener subscribes to
    /// (soonest start first, matching ``sortedForList(_:)``) — mirrors
    /// Android's `Events.PUBLISHED_EVENTS_QUERY_LIMIT`. Keeps the snapshot
    /// bounded as the `events` collection grows without bound; events
    /// starting furthest in the future simply fall off the list.
    static let publishedEventsQueryLimit = 200

    /// RSVP is allowed only for a caller who PASSES THE MEMBER GATE, on a
    /// published event — mirrors the Firestore rule on events/{id}/rsvps/{uid}
    /// (owner + isActiveMember() + published) and Android's `Events.canRsvp`.
    /// "Passes the gate" rather than "is an active member" because both
    /// layers are switchable (``MemberGating``). Cancelled/completed/draft
    /// events are not RSVP-able either way.
    static func canRsvp(passesMemberGate: Bool, status: EventStatus) -> Bool {
        passesMemberGate && status == .published
    }

    /// Whether the exact-location / description detail may be requested —
    /// mirrors the firestore.rules `details/private` read (member gate +
    /// published event) and Android's `Events.canSeeDetails`.
    static func canSeeDetails(passesMemberGate: Bool, status: EventStatus) -> Bool {
        passesMemberGate && status == .published
    }

    static func valid(_ input: EventFormInput) -> Bool {
        let title = input.title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !title.isEmpty, title.count <= titleMax,
              (input.description?.count ?? 0) <= descriptionMax,
              (input.address?.count ?? 0) <= addressMax,
              input.startsAt.timeIntervalSince1970.isFinite,
              (input.latitude == nil) == (input.longitude == nil)
        else { return false }
        if let latitude = input.latitude, let longitude = input.longitude {
            return latitude.isFinite && longitude.isFinite
                && (-90...90).contains(latitude) && (-180...180).contains(longitude)
        }
        return true
    }

    static func canManage(_ event: EventSummary, uid: String?) -> Bool {
        uid != nil && event.createdByUserId == uid
            && (event.status == .draft || event.status == .published)
    }

    static func canCheckIn(_ event: EventSummary, now: Date = Date()) -> Bool {
        guard event.status == .published || event.status == .completed,
              event.latitude != nil, event.longitude != nil,
              let start = event.startsAt
        else { return false }
        let end = event.endsAt ?? start.addingTimeInterval(defaultEventDuration)
        return now >= start.addingTimeInterval(-checkInWindowBefore)
            && now <= end.addingTimeInterval(checkInWindowAfter)
    }

    static func iso8601(_ date: Date) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        return formatter.string(from: date)
    }

    static func createPayload(_ input: EventFormInput) -> [String: Any] {
        var payload: [String: Any] = [
            "title": input.title.trimmingCharacters(in: .whitespacesAndNewlines),
            "startsAt": iso8601(input.startsAt),
            "publishNow": true,
        ]
        if let value = trimmed(input.description) { payload["description"] = value }
        if let value = trimmed(input.address) { payload["address"] = value }
        if let latitude = input.latitude, let longitude = input.longitude {
            payload["latitude"] = latitude
            payload["longitude"] = longitude
        }
        if input.publicSiteEnabled { payload["publicSiteEnabled"] = true }
        return payload
    }

    static func updatePayload(eventId: String, input: EventFormInput) -> [String: Any] {
        [
            "eventId": eventId,
            "title": input.title.trimmingCharacters(in: .whitespacesAndNewlines),
            "startsAt": iso8601(input.startsAt),
            "description": trimmed(input.description).map { $0 as Any } ?? NSNull(),
            "address": trimmed(input.address).map { $0 as Any } ?? NSNull(),
            "latitude": input.latitude.map { $0 as Any } ?? NSNull(),
            "longitude": input.longitude.map { $0 as Any } ?? NSNull(),
        ]
    }

    static func checkInAnchor(sessionFirstFixAt: Date?, recordCreatedAt: Date?) -> Date? {
        switch (sessionFirstFixAt, recordCreatedAt) {
        case let (session?, record?): min(session, record)
        case let (session?, nil): session
        case let (nil, record?): record
        case (nil, nil): nil
        }
    }

    static func checkInRemaining(from anchor: Date, now: Date) -> TimeInterval {
        max(0, requiredCheckInDwell - max(0, now.timeIntervalSince(anchor)))
    }

    static func checkInProgress(from anchor: Date, now: Date) -> Double {
        min(1, max(0, now.timeIntervalSince(anchor)) / requiredCheckInDwell)
    }

    private static func trimmed(_ value: String?) -> String? {
        let result = value?.trimmingCharacters(in: .whitespacesAndNewlines)
        return result?.isEmpty == false ? result : nil
    }

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
