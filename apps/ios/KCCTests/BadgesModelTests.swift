import XCTest

@testable import KCC

/// Unit tests for the pure badges domain: tolerant award decoding, the
/// client ladder-catalog mirror, the progress-payload parser, and the
/// `BadgeShowcase` fold that merges earned awards + counters into the full
/// wall. No Firebase — every input is a plain value (same conventions as
/// GarageModelTests / EventsModelTests).
final class BadgesModelTests: XCTestCase {

    // MARK: - Badge decoding

    func testBadgeFromMapReadsAllFields() {
        let date = Date(timeIntervalSince1970: 1_700_000_000)
        let badge = Badge.fromMap(
            id: "doc-id",
            map: ["badgeKey": "kronjagare_silver", "name": "Kronjägare Silver", "awardedAt": date]
        )
        XCTAssertEqual(badge.key, "kronjagare_silver")
        XCTAssertEqual(badge.fallbackName, "Kronjägare Silver")
        XCTAssertEqual(badge.awardedAt, date)
    }

    func testBadgeFromMapFallsBackToDocumentIdWhenKeyAbsent() {
        let badge = Badge.fromMap(id: "first_event", map: [:])
        XCTAssertEqual(badge.key, "first_event")
        XCTAssertNil(badge.fallbackName)
        XCTAssertNil(badge.awardedAt)
    }

    func testBadgeFromMapDropsNonDateAwardedAt() {
        // The Firebase mapping layer converts a Timestamp to a Date before
        // this pure decode; anything else must read as "no date", never crash.
        let badge = Badge.fromMap(id: "x", map: ["badgeKey": "x", "awardedAt": "not-a-date"])
        XCTAssertNil(badge.awardedAt)
    }

    func testSortedForListNewestFirstUndatedLast() {
        let older = Badge(key: "a", fallbackName: nil, awardedAt: Date(timeIntervalSince1970: 1_000))
        let newer = Badge(key: "b", fallbackName: nil, awardedAt: Date(timeIntervalSince1970: 2_000))
        let undated = Badge(key: "c", fallbackName: nil, awardedAt: nil)
        let sorted = Badges.sortedForList([older, undated, newer])
        XCTAssertEqual(sorted.map(\.key), ["b", "a", "c"])
    }

    // MARK: - Catalog

    func testCatalogTotalCountMatchesMilestonesPlusRungs() {
        // 9 standalone milestones + 31 ladder rungs (8 ladders; Trogen has 3
        // rungs, the other seven have 4) = 40 — the "x of y" denominator.
        XCTAssertEqual(badgeMilestoneKeys.count, 9)
        XCTAssertEqual(badgeLadders.reduce(0) { $0 + $1.rungs.count }, 31)
        XCTAssertEqual(badgeTotalCount, 40)
    }

    func testTrogenLadderStopsAtGuld() {
        let trogen = badgeLadders.first { $0.id == .trogen }
        XCTAssertEqual(trogen?.rungs.map(\.tier), [.brons, .silver, .guld])
        XCTAssertNil(trogen?.rung(for: .platina))
    }

    func testTierPointsMatchAndroidsCatalog() {
        // Parity with Android's BADGE_TIER_POINTS — not yet read by BadgesWall
        // (no tap-to-detail sheet there yet), but the catalog mirror must stay
        // correct for whichever detail UI reads it next.
        XCTAssertEqual(badgeTierPoints[.brons], 25)
        XCTAssertEqual(badgeTierPoints[.silver], 75)
        XCTAssertEqual(badgeTierPoints[.guld], 200)
        XCTAssertEqual(badgeTierPoints[.platina], 500)
    }

    func testEveryRungKeyResolvesToItsLadder() {
        for ladder in badgeLadders {
            for rung in ladder.rungs {
                let match = rungForBadgeKey(rung.badgeKey)
                XCTAssertEqual(match?.ladder.id, ladder.id, "\(rung.badgeKey)")
                XCTAssertEqual(match?.rung.tier, rung.tier, "\(rung.badgeKey)")
            }
        }
    }

    func testMilestoneKeysAreNotLadderRungs() {
        for key in badgeMilestoneKeys {
            XCTAssertNil(rungForBadgeKey(key), "\(key) should be standalone")
        }
    }

    func testFormatLadderValueRendersKilometresForDistance() {
        XCTAssertEqual(formatLadderValue(unit: .distanceMeters, value: 100_000), "100 km")
        XCTAssertEqual(formatLadderValue(unit: .distanceMeters, value: 34_000), "34 km")
        XCTAssertEqual(formatLadderValue(unit: .count, value: 250), "250")
    }

    func testEveryCatalogKeyHasALocalizedNameKey() {
        // Parity with Android's BadgeStrings.badgeNameRes `when`: every catalog
        // key must resolve to a generated name key, or an English-locale member
        // sees the Swedish fallback.
        let catalog = Set(badgeMilestoneKeys).union(badgeLadders.flatMap(\.badgeKeys))
        for key in catalog {
            XCTAssertNotNil(BadgeStrings.badgeNameKey(for: key), "missing name key for \(key)")
        }
        XCTAssertNil(BadgeStrings.badgeNameKey(for: "not_a_badge"))
    }

    // MARK: - Progress parser

    func testProgressParserReadsCounters() {
        let counters = BadgeProgressResponseParser.parse([
            "crownsCollected": 12,
            "lifetimeDistanceMeters": NSNumber(value: 250_500.9),
            "verifiedEventsAttended": 3,
            "wavesSent": 40,
        ])
        XCTAssertEqual(counters.crownsCollected, 12)
        // Floored to match the server's integer counter.
        XCTAssertEqual(counters.lifetimeDistanceMeters, 250_500)
        XCTAssertEqual(counters.verifiedEventsAttended, 3)
        XCTAssertEqual(counters.wavesSent, 40)
        XCTAssertNil(counters.bestDayStreak)
    }

    func testProgressParserRejectsNegativeNonFiniteAndBoolean() {
        let counters = BadgeProgressResponseParser.parse([
            "crownsCollected": -1,
            "lifetimeDistanceMeters": Double.nan,
            "verifiedEventsAttended": Double.infinity,
            "convoysLed": true,
            "bestDayStreak": "5",
        ])
        XCTAssertNil(counters.crownsCollected)
        XCTAssertNil(counters.lifetimeDistanceMeters)
        XCTAssertNil(counters.verifiedEventsAttended)
        // A boolean must not floor to 1.
        XCTAssertNil(counters.convoysLed)
        XCTAssertNil(counters.bestDayStreak)
    }

    func testProgressParserRejectsOutOfRangeFiniteDoubleWithoutTrapping() {
        // A finite Double outside Int64's range TRAPS on a bare `Int64(_:)`
        // conversion; the parser must degrade to nil instead of crashing on a
        // malformed/unexpected payload.
        let counters = BadgeProgressResponseParser.parse([
            "crownsCollected": 1.0e30,
            "lifetimeDistanceMeters": -1.0e30,
            "verifiedEventsAttended": Double(Int64.max),
        ])
        XCTAssertNil(counters.crownsCollected)
        XCTAssertNil(counters.lifetimeDistanceMeters)
        // Double(Int64.max) rounds UP to 2^63 (not exactly representable),
        // which is one past Int64's range — must also read as nil, not trap.
        XCTAssertNil(counters.verifiedEventsAttended)
    }

    func testObservedValueMapsEachLadderToItsCounter() {
        let counters = BadgeCounters(
            crownsCollected: 1,
            lifetimeDistanceMeters: 2,
            verifiedEventsAttended: 3,
            bestDayStreak: 4,
            convoysLed: 5,
            vehiclesInGarage: 6,
            seasonsWon: 7,
            wavesSent: 8
        )
        XCTAssertEqual(counters.observedValue(for: .kronjagare), 1)
        XCTAssertEqual(counters.observedValue(for: .vagfarare), 2)
        XCTAssertEqual(counters.observedValue(for: .traffrav), 3)
        XCTAssertEqual(counters.observedValue(for: .trogen), 4)
        XCTAssertEqual(counters.observedValue(for: .konvojledare), 5)
        XCTAssertEqual(counters.observedValue(for: .samlare), 6)
        XCTAssertEqual(counters.observedValue(for: .sasongsmastare), 7)
        XCTAssertEqual(counters.observedValue(for: .vinkare), 8)
    }

    // MARK: - Showcase fold

    func testEmptyShowcaseRendersFullLockedCatalog() {
        let showcase = BadgeShowcase.from(badges: [])
        XCTAssertFalse(showcase.hasAnyBadge)
        XCTAssertEqual(showcase.earnedCount, 0)
        XCTAssertEqual(showcase.totalCount, 40)
        // Every ladder is present and locked — an unstarted ladder renders
        // greyed, not hidden.
        XCTAssertEqual(showcase.ladders.count, badgeLadders.count)
        XCTAssertTrue(showcase.ladders.allSatisfy(\.isLocked))
        XCTAssertTrue(showcase.milestones.isEmpty)
        // Each ladder offers its bottom rung as the next goal.
        XCTAssertEqual(showcase.ladders.first?.nextRung?.tier, .brons)
    }

    func testEarnedRungsFoldToHighestHeldAndNextUnheld() {
        let badges = [
            Badge(key: "kronjagare_brons", fallbackName: nil, awardedAt: Date(timeIntervalSince1970: 10)),
            Badge(key: "kronjagare_silver", fallbackName: nil, awardedAt: Date(timeIntervalSince1970: 20)),
        ]
        let showcase = BadgeShowcase.from(badges: badges)
        XCTAssertTrue(showcase.hasAnyBadge)
        XCTAssertEqual(showcase.earnedCount, 2)
        let kronjagare = showcase.ladders.first { $0.ladder.id == .kronjagare }
        XCTAssertEqual(kronjagare?.earnedRungs.map(\.tier), [.brons, .silver])
        XCTAssertEqual(kronjagare?.highestRung?.tier, .silver)
        XCTAssertEqual(kronjagare?.nextRung?.tier, .guld)
        XCTAssertFalse(kronjagare?.isLocked ?? true)
        XCTAssertFalse(kronjagare?.isComplete ?? true)
    }

    func testGapInLadderOffersLowestUnheldRung() {
        // A HIGH rung held but a lower one missing (the monotonic backend never
        // does this) still reports the highest held and the lowest unheld.
        let badges = [Badge(key: "samlare_silver", fallbackName: nil, awardedAt: nil)]
        let showcase = BadgeShowcase.from(badges: badges)
        let samlare = showcase.ladders.first { $0.ladder.id == .samlare }
        XCTAssertEqual(samlare?.highestRung?.tier, .silver)
        // brons is unheld and lowest, so it is offered again rather than skipped.
        XCTAssertEqual(samlare?.nextRung?.tier, .brons)
    }

    func testCompletedLadderHasNoNextRung() {
        let badges = badgeLadders
            .first { $0.id == .trogen }!
            .rungs
            .map { Badge(key: $0.badgeKey, fallbackName: nil, awardedAt: nil) }
        let showcase = BadgeShowcase.from(badges: badges)
        let trogen = showcase.ladders.first { $0.ladder.id == .trogen }
        XCTAssertTrue(trogen?.isComplete ?? false)
        XCTAssertNil(trogen?.nextRung)
    }

    func testCountersDriveFractionToNext() {
        let badges = [Badge(key: "kronjagare_brons", fallbackName: nil, awardedAt: nil)]
        let counters = BadgeCounters(crownsCollected: 25) // next is silver@50
        let showcase = BadgeShowcase.from(badges: badges, counters: counters)
        let kronjagare = showcase.ladders.first { $0.ladder.id == .kronjagare }
        XCTAssertEqual(kronjagare?.observedValue, 25)
        XCTAssertEqual(kronjagare?.fractionToNext ?? 0, 0.5, accuracy: 0.0001)
    }

    func testFractionIsNilWithoutCounter() {
        let badges = [Badge(key: "kronjagare_brons", fallbackName: nil, awardedAt: nil)]
        let showcase = BadgeShowcase.from(badges: badges, counters: .none)
        let kronjagare = showcase.ladders.first { $0.ladder.id == .kronjagare }
        XCTAssertNil(kronjagare?.fractionToNext)
    }

    func testUnknownKeysAreIgnored() {
        let badges = [
            Badge(key: "retired_badge", fallbackName: "Retired", awardedAt: Date()),
            Badge(key: "first_event", fallbackName: "First", awardedAt: Date()),
        ]
        let showcase = BadgeShowcase.from(badges: badges)
        // Only the catalog key counts; the unknown one does not inflate the total.
        XCTAssertEqual(showcase.earnedCount, 1)
        XCTAssertEqual(showcase.milestones.map(\.key), ["first_event"])
    }

    func testMilestonesFoldedInCatalogOrder() {
        let badges = [
            Badge(key: "garage_created", fallbackName: "Garage", awardedAt: nil),
            Badge(key: "first_event", fallbackName: "First", awardedAt: nil),
        ]
        let showcase = BadgeShowcase.from(badges: badges)
        // Catalog order: first_event precedes garage_created.
        XCTAssertEqual(showcase.milestones.map(\.key), ["first_event", "garage_created"])
    }

    func testRecentAwardsNewestFirstAndCapped() {
        // Seven dated awards; the strip caps at six, newest first.
        let base = Date(timeIntervalSince1970: 1_000)
        let keys = [
            "first_event", "five_events", "kronjagare_brons", "traffrav_brons",
            "vagfarare_brons", "konvojledare_brons", "samlare_brons",
        ]
        let badges = keys.enumerated().map { index, key in
            Badge(key: key, fallbackName: key, awardedAt: base.addingTimeInterval(Double(index)))
        }
        let showcase = BadgeShowcase.from(badges: badges)
        XCTAssertEqual(showcase.recentAwards.count, BadgeShowcase.recentAwardsLimit)
        // Newest (samlare_brons, index 6) is first; the oldest (first_event) is
        // dropped by the cap.
        XCTAssertEqual(showcase.recentAwards.first?.badgeKey, "samlare_brons")
        XCTAssertFalse(showcase.recentAwards.contains { $0.badgeKey == "first_event" })
    }

    func testAwardedAtByKeyKeepsNewestPerKey() {
        let older = Badge(key: "first_event", fallbackName: nil, awardedAt: Date(timeIntervalSince1970: 1_000))
        let newer = Badge(key: "first_event", fallbackName: nil, awardedAt: Date(timeIntervalSince1970: 5_000))
        let showcase = BadgeShowcase.from(badges: [older, newer])
        XCTAssertEqual(showcase.awardedAtByKey["first_event"], Date(timeIntervalSince1970: 5_000))
        // A duplicate key collapses to one earned badge.
        XCTAssertEqual(showcase.earnedCount, 1)
    }

    func testMilestoneFoldingPicksTheNewestDuplicateDoc() {
        // Two docs for the same milestone key — the OLDER one appears FIRST in
        // the array, so a naive `first(where:)` pick (the pre-fix behaviour)
        // would surface its stale name/date. The milestone must agree with
        // `awardedAtByKey`, which already keeps the newest per key.
        let older = Badge(key: "first_event", fallbackName: "Stale name", awardedAt: Date(timeIntervalSince1970: 1_000))
        let newer = Badge(key: "first_event", fallbackName: "Fresh name", awardedAt: Date(timeIntervalSince1970: 5_000))
        let showcase = BadgeShowcase.from(badges: [older, newer])
        let milestone = showcase.milestones.first { $0.key == "first_event" }
        XCTAssertEqual(milestone?.fallbackName, "Fresh name")
        XCTAssertEqual(milestone?.awardedAt, Date(timeIntervalSince1970: 5_000))
    }

    func testLaddersInProgressOrdersMostCompleteFirst() {
        let badges = [
            Badge(key: "kronjagare_brons", fallbackName: nil, awardedAt: nil),
            Badge(key: "traffrav_brons", fallbackName: nil, awardedAt: nil),
        ]
        // kronjagare 40/50 = 0.8 toward silver; traffrav 1/5 = 0.2 toward silver.
        let counters = BadgeCounters(crownsCollected: 40, verifiedEventsAttended: 1)
        let showcase = BadgeShowcase.from(badges: badges, counters: counters)
        let ranked = showcase.laddersInProgress
        let kronIndex = ranked.firstIndex { $0.ladder.id == .kronjagare }
        let traffIndex = ranked.firstIndex { $0.ladder.id == .traffrav }
        XCTAssertNotNil(kronIndex)
        XCTAssertNotNil(traffIndex)
        XCTAssertLessThan(kronIndex!, traffIndex!)
    }
}
