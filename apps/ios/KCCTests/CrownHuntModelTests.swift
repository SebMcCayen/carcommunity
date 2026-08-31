import XCTest

@testable import KCC

/// Pure model tests: the wire vocabularies round-trip and reject junk, and the
/// season clock produces the backend's `YYYY-MM` id in Europe/Stockholm with
/// ASCII digits regardless of device locale.
final class CrownHuntModelTests: XCTestCase {

    // MARK: - Wire vocabularies

    func testClaimResultFromWireCoversEveryContractCode() {
        XCTAssertEqual(CrownHuntClaimResult.fromWire("awarded"), .awarded)
        XCTAssertEqual(CrownHuntClaimResult.fromWire("already_claimed"), .alreadyClaimed)
        XCTAssertEqual(CrownHuntClaimResult.fromWire("outside_geofence"), .outsideGeofence)
        XCTAssertEqual(CrownHuntClaimResult.fromWire("moving_too_fast"), .movingTooFast)
        XCTAssertEqual(CrownHuntClaimResult.fromWire("position_too_old"), .positionTooOld)
        XCTAssertEqual(CrownHuntClaimResult.fromWire("point_inactive"), .pointInactive)
        XCTAssertEqual(CrownHuntClaimResult.fromWire("cooldown_active"), .cooldownActive)
        XCTAssertEqual(CrownHuntClaimResult.fromWire("daily_limit_reached"), .dailyLimitReached)
        XCTAssertEqual(CrownHuntClaimResult.fromWire("risk_review"), .riskReview)
        XCTAssertEqual(CrownHuntClaimResult.fromWire("feature_disabled"), .featureDisabled)
        XCTAssertEqual(CrownHuntClaimResult.fromWire("not_eligible"), .notEligible)
        // All 11 codes are represented.
        XCTAssertEqual(CrownHuntClaimResult.allCases.count, 11)
    }

    func testUnknownWireValuesMapToNil() {
        XCTAssertNil(CrownHuntClaimResult.fromWire("teleported"))
        XCTAssertNil(CrownHuntClaimResult.fromWire(nil))
        XCTAssertNil(CrownRarity.fromWire("mythic"))
        XCTAssertNil(CrownRarity.fromWire(nil))
        XCTAssertNil(PerkKind.fromWire("nuke"))
        XCTAssertNil(PerkKind.fromWire(nil))
        XCTAssertNil(CrownHuntPointStatus.fromWire("archived"))
    }

    func testRarityAndPerkKindWireValues() {
        XCTAssertEqual(CrownRarity.fromWire("legendary"), .legendary)
        XCTAssertEqual(CrownRarity.allCases.map(\.wire), ["common", "uncommon", "rare", "legendary"])
        XCTAssertEqual(PerkKind.fromWire("trap"), .trap)
        XCTAssertEqual(PerkKind.fromWire("shield"), .shield)
        XCTAssertEqual(PerkKind.fromWire("boost"), .boost)
    }

    // MARK: - Season clock

    /// Builds a UTC instant from calendar components, so the season assertions
    /// read plainly instead of leaning on epoch arithmetic.
    private func utc(_ year: Int, _ month: Int, _ day: Int, _ hour: Int, _ minute: Int) -> Date {
        var components = DateComponents()
        components.year = year
        components.month = month
        components.day = day
        components.hour = hour
        components.minute = minute
        components.timeZone = TimeZone(identifier: "UTC")
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        return calendar.date(from: components)!
    }

    func testSeasonIdRollsAtTheStockholmMonthBoundary() {
        // 2026-08-31 22:30 UTC is already 2026-09-01 00:30 in Stockholm (CEST,
        // UTC+2), so the season rolls to September at the local, not UTC, edge.
        XCTAssertEqual(CrownSeasonClock.seasonId(for: utc(2026, 8, 31, 22, 30)), "2026-09")
        // 21:30 UTC is still 23:30 in Stockholm on the 31st → August.
        XCTAssertEqual(CrownSeasonClock.seasonId(for: utc(2026, 8, 31, 21, 30)), "2026-08")
    }

    func testSeasonIdUsesAsciiDigitsRegardlessOfLocale() {
        let season = CrownSeasonClock.seasonId(for: utc(2026, 8, 5, 12, 0))
        XCTAssertEqual(season, "2026-08")
        XCTAssertTrue(season.allSatisfy { ($0.isNumber && $0.isASCII) || $0 == "-" })
    }

    func testAllTimeScopeConstant() {
        XCTAssertEqual(CrownSeasonClock.allTimeScope, "alltime")
    }
}
