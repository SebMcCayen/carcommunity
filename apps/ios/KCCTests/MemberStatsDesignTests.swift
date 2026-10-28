import SwiftUI
import UIKit
import XCTest
@testable import KCC

@MainActor
final class MemberStatsDesignTests: XCTestCase {
    private let longName = "En medlem med ett mycket långt namn som ska gå att läsa"
    private let date = Date(timeIntervalSince1970: 1_700_000_000)

    func testPublicIdentityVehiclesAndHeldAwardsWrapWithoutNewImageLookups() {
        let profile = MemberProfile(uid: "synthetic", displayName: longName,
            bio: "En längre presentation som ska visas i sin helhet även med stor text.", avatarPath: nil,
            facebook: nil, instagram: nil, youtube: nil, createdAt: date)
        assertWrapping(MemberProfileHeader(profile: profile, pointsBalance: Int64.max, avatarURL: nil))
        assertWrapping(MemberProfileHeader(profile: profile, pointsBalance: 0, avatarURL: nil))
        let vehicle = Vehicle(id: "synthetic", make: "En tillverkare", model: "med ett långt modellnamn",
            makeId: nil, modelId: nil, modelYear: 2026, powertrain: .electric, engineDescription: nil,
            modifications: nil, registrationPlate: "TEST123", imagePath: nil, photoPaths: [], isMainCar: true)
        assertWrapping(MemberVehicleSummary(vehicle: vehicle))
        for key in ["kronjagare_platina", "first_event", "unknown-synthetic-award"] {
            assertWrapping(MemberBadgeSummary(badge: Badge(key: key, fallbackName: longName, awardedAt: date)))
        }
    }

    func testEarnedLockedAndUndatedBadgeTilesRemainReadable() {
        for earned in [true, false] {
            for date: Date? in [date, nil] {
                assertWrapping(BadgeMedallionTile(tier: .platina, earned: earned, label: longName,
                    ladderName: "Kronjägare", awardedAt: date))
            }
        }
        let showcase = BadgeShowcase.from(badges: [Badge(key: "kronjagare_brons", fallbackName: nil, awardedAt: date)],
            counters: BadgeCounters(crownsCollected: 34))
        for progress in showcase.laddersInProgress { assertWrapping(LadderProgressRow(progress: progress)) }
        // The same whole wall has a one-column accessibility layout, not
        // fixed-height miniature labels or a second embedded scroller.
        assertWrapping(BadgeWallContent(showcase: showcase), heightLimit: 100000)
    }

    func testLedgerKeepsSignedValuesDatesAndOptionalBalancesReadable() {
        for balance: Int64? in [nil, 0, Int64.max] { assertWrapping(PointsBalanceCard(balance: balance)) }
        for amount: Int64 in [Int64.min, -1, 0, 1, Int64.max] {
            assertWrapping(PointsEntryCard(entry: PointsEntry(id: "synthetic", amount: amount, balanceAfter: Int64.max,
                description: "En längre förklaring av den här poängtransaktionen som ska kunna radbrytas.", createdAt: date)))
        }
        assertWrapping(PointsEntryCard(entry: PointsEntry(id: "undated", amount: 1, balanceAfter: nil,
            description: "", createdAt: nil)))
    }

    func testHistoryFiltersKeepOrderAndZeroCorrectionsInAllOnly() {
        let entries: [PointsEntry] = [Int64.min, 9, 0, -1, Int64.max].enumerated().map {
            PointsEntry(id: String($0.offset), amount: $0.element, balanceAfter: nil, description: "", createdAt: nil)
        }
        XCTAssertEqual(PointsHistoryFilter.all.apply(entries), entries)
        XCTAssertEqual(PointsHistoryFilter.credits.apply(entries).map(\.id), ["1", "4"])
        XCTAssertEqual(PointsHistoryFilter.debits.apply(entries).map(\.id), ["0", "3"])
        XCTAssertTrue(PointsHistoryFilter.credits.apply([]).isEmpty)
    }

    func testRankingNamesAndPublishedRanksWrapAcrossAllValueFormats() {
        let coordinator = LeaderboardCoordinator(repository: nil)
        for format: LeaderboardValueFormat in [.crownPoints, .distanceKm, .count, .waves, .days] {
            let top = entry(rank: 1, viewer: true)
            assertWrapping(LeaderboardPodiumTile(entry: top, format: format, coordinator: coordinator))
            assertWrapping(LeaderboardListRow(entry: entry(rank: 1004, viewer: false), format: format, coordinator: coordinator))
        }
        let board = LeaderboardCategoryBoard(category: .crownPoints, entries: [entry(rank: 1, viewer: false),
            entry(rank: 2, viewer: true), entry(rank: 3, viewer: false), entry(rank: 4, viewer: false)])
        assertWrapping(LeaderboardCategorySection(board: board, coordinator: coordinator))
        assertWrapping(LeaderboardCategoryPicker(coordinator: coordinator))
        XCTAssertEqual(board.entries.map(\.rank), [1, 2, 3, 4])
        XCTAssertEqual(coordinator.state, .unavailable)
    }

    func testScopeAndLedgerTabsStayBoundedWithFullAccessibilityLabels() {
        for width: CGFloat in [288, 358] {
            let scopes = StatsReadingTabs(options: LeaderboardScope.allCases, selection: .constant(.lastMonth),
                title: LeaderboardFormat.scopeTitleKey, identifier: "test.scopes")
            let filters = StatsReadingTabs(options: PointsHistoryFilter.allCases, selection: .constant(.all),
                title: { $0.titleKey }, identifier: "test.filters")
            for result in [measure(scopes, width: width, size: .accessibility5), measure(filters, width: width, size: .accessibility5)] {
                XCTAssertLessThanOrEqual(result.width, width + 1)
                XCTAssertGreaterThanOrEqual(result.height, 48)
                XCTAssertLessThan(result.height, 160)
            }
        }
    }

    private func entry(rank: Int, viewer: Bool) -> LeaderboardEntry {
        LeaderboardEntry(rank: rank, uid: "synthetic-\(rank)", displayName: longName, avatarPath: nil,
            value: 1_234_567, isViewer: viewer)
    }

    private func assertWrapping<Content: View>(_ view: Content, heightLimit: CGFloat = 10000,
        file: StaticString = #filePath, line: UInt = #line) {
        for (language, dark) in [("sv", true), ("en", false)] {
            for width: CGFloat in [288, 358] {
                let normal = measure(view, width: width, size: .large, language: language, dark: dark, heightLimit: heightLimit)
                let large = measure(view, width: width, size: .accessibility5, language: language, dark: dark, heightLimit: heightLimit)
                XCTAssertLessThanOrEqual(large.width, width + 1, file: file, line: line)
                XCTAssertGreaterThan(large.height, normal.height, file: file, line: line)
                XCTAssertTrue(large.height.isFinite, file: file, line: line)
            }
        }
    }

    private func measure<Content: View>(_ view: Content, width: CGFloat, size: DynamicTypeSize,
        language: String = "sv", dark: Bool = true, heightLimit: CGFloat = 10000) -> CGSize {
        let host = UIHostingController(rootView: view.environment(\.locale, Locale(identifier: language))
            .environment(\.dynamicTypeSize, size).environment(\.colorScheme, dark ? .dark : .light))
        host.overrideUserInterfaceStyle = dark ? .dark : .light
        host.loadViewIfNeeded()
        return host.sizeThatFits(in: CGSize(width: width, height: heightLimit))
    }
}
