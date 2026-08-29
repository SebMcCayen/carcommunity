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
}
