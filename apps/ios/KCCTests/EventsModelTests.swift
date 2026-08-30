import XCTest

@testable import KCC

/// Pure events-domain logic — the iOS counterpart of Android's `EventTest`
/// coverage for the pieces this slice ports: list ordering and defensive
/// wire parsing.
final class EventsModelTests: XCTestCase {

    private func event(_ id: String, startsAt: Date?) -> EventSummary {
        EventSummary(
            id: id,
            title: id,
            summary: nil,
            startsAt: startsAt,
            endsAt: nil,
            approximateArea: nil,
            locationName: nil,
            latitude: nil,
            longitude: nil,
            isOfficial: false,
            status: .published,
            counts: .empty
        )
    }

    func testSortedForListOrdersSoonestFirstWithNilStartsLast() {
        let soon = event("soon", startsAt: Date(timeIntervalSince1970: 1_000))
        let later = event("later", startsAt: Date(timeIntervalSince1970: 2_000))
        let unknown = event("unknown", startsAt: nil)

        let sorted = Events.sortedForList([unknown, later, soon])

        XCTAssertEqual(sorted.map(\.id), ["soon", "later", "unknown"])
    }

    func testSortedForListIsStableAmongEqualStarts() {
        let when = Date(timeIntervalSince1970: 1_000)
        let first = event("first", startsAt: when)
        let second = event("second", startsAt: when)
        let noTimeA = event("noTimeA", startsAt: nil)
        let noTimeB = event("noTimeB", startsAt: nil)

        let sorted = Events.sortedForList([first, second, noTimeA, noTimeB])

        XCTAssertEqual(sorted.map(\.id), ["first", "second", "noTimeA", "noTimeB"])
    }

    func testEventStatusFromWire() {
        XCTAssertEqual(EventStatus.fromWire("published"), .published)
        XCTAssertEqual(EventStatus.fromWire("completed"), .completed)
        XCTAssertNil(EventStatus.fromWire("archived"))
        XCTAssertNil(EventStatus.fromWire(nil))
    }

    func testRsvpCountsFromMapReadsDefensively() {
        XCTAssertEqual(RsvpCounts.fromMap(nil), .empty)
        XCTAssertEqual(RsvpCounts.fromMap([:]), .empty)

        let counts = RsvpCounts.fromMap([
            "going": 3,
            "maybe": -2,  // negative clamps to 0
            "not_going": 1,
            "extra": "ignored",
        ])
        XCTAssertEqual(counts, RsvpCounts(going: 3, maybe: 0, notGoing: 1))
        XCTAssertEqual(counts.total, 4)

        // A malformed value degrades to 0, never a crash.
        XCTAssertEqual(RsvpCounts.fromMap(["going": "many"]).going, 0)
    }

    // MARK: - detail + RSVP slice

    func testRsvpStatusWireVocabulary() {
        // The contract enum (eventRsvpStatus) — note the underscore spelling
        // the rules validate (`validRsvpDocument`).
        XCTAssertEqual(RsvpStatus.going.wire, "going")
        XCTAssertEqual(RsvpStatus.maybe.wire, "maybe")
        XCTAssertEqual(RsvpStatus.notGoing.wire, "not_going")

        XCTAssertEqual(RsvpStatus.fromWire("going"), .going)
        XCTAssertEqual(RsvpStatus.fromWire("maybe"), .maybe)
        XCTAssertEqual(RsvpStatus.fromWire("not_going"), .notGoing)
        // Never a fabricated answer for an unknown/absent value.
        XCTAssertNil(RsvpStatus.fromWire("notGoing"))
        XCTAssertNil(RsvpStatus.fromWire("attending"))
        XCTAssertNil(RsvpStatus.fromWire(nil))
    }

    func testCanRsvpRequiresGateAndPublished() {
        XCTAssertTrue(Events.canRsvp(passesMemberGate: true, status: .published))
        XCTAssertFalse(Events.canRsvp(passesMemberGate: false, status: .published))
        for status in [EventStatus.draft, .cancelled, .completed] {
            XCTAssertFalse(Events.canRsvp(passesMemberGate: true, status: status))
        }
    }

    func testCanSeeDetailsRequiresGateAndPublished() {
        XCTAssertTrue(Events.canSeeDetails(passesMemberGate: true, status: .published))
        XCTAssertFalse(Events.canSeeDetails(passesMemberGate: false, status: .published))
        for status in [EventStatus.draft, .cancelled, .completed] {
            XCTAssertFalse(Events.canSeeDetails(passesMemberGate: true, status: status))
        }
    }

    func testMemberGatingDisabledLetsEveryoneThrough() {
        // The launch posture — Android MemberGating.ENABLED == false. If this
        // flips, every passesMemberGate call site needs the real entitlement
        // threaded through (see MemberGating docs).
        XCTAssertFalse(MemberGating.enabled)
        XCTAssertTrue(MemberGating.allows(isActiveMember: false))
        XCTAssertTrue(MemberGating.allows(isActiveMember: true))
    }
}
