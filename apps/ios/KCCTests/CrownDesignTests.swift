import SwiftUI
import UIKit
import XCTest
@testable import KCC

@MainActor
final class CrownDesignTests: XCTestCase {
    @MainActor private final class Repository: CrownHuntMapRepository {
        let point = CrownMapPoint(id: "placed", title: "En säker plats med ett långt namn", detail: nil,
            rewardPoints: 130, latitude: 57, longitude: 12, geofenceRadiusMeters: 75)
        let spawn: CrownSpawn
        var holdCollectedRead = false
        private var collectedContinuation: CheckedContinuation<Set<String>, Never>?
        var collectedReadPending: Bool { collectedContinuation != nil }
        func resumeCollectedRead() {
            collectedContinuation?.resume(returning: [])
            collectedContinuation = nil
        }
        init(now: Date) {
            spawn = CrownSpawn(id: "spawn", latitude: 57, longitude: 12, rarity: .rare,
                rewardPoints: 100, collectRadiusMeters: 75, expiresAt: now.addingTimeInterval(3600))
        }
        func activePoints() async throws -> [CrownMapPoint] { [point] }
        func nearbySpawns(cellKeys: [String], now: Date) async throws -> [CrownSpawn] { [spawn] }
        func collectedSpawnIds(uid: String, now: Date) async throws -> Set<String> {
            if holdCollectedRead {
                return await withCheckedContinuation { collectedContinuation = $0 }
            }
            return []
        }
        func submitPointClaim(pointId: String, fix: LocationFix, idempotencyKey: String) async throws -> CrownPointClaimOutcome {
            CrownPointClaimOutcome(result: .awarded, pointsAwarded: 7, newBalance: 7)
        }
        func submitSpawnClaim(spawnId: String, proof: CrownFixPair, idempotencyKey: String) async throws -> CrownSpawnClaimOutcome {
            CrownSpawnClaimOutcome(result: .awarded, pointsAwarded: 7, newBalance: 7, rarity: .rare)
        }
    }

    func testMarkerProximityExpiresWithoutChangingSourceRewards() async throws {
        let now = Date(timeIntervalSince1970: 2000)
        let repository = Repository(now: now)
        let provider = StubLocationProvider(authorization: .whileInUse)
        let coordinator = makeCoordinator(repository, provider)
        await coordinator.refresh(camera: .of(latitude: 57, longitude: 12, zoom: 14, bearing: 0, pitch: 0), visibleRadiusMeters: 200)
        XCTAssertEqual(coordinator.markers(at: now).count, 2)
        XCTAssertTrue(coordinator.markers(at: now).allSatisfy { !$0.isInRange })
        coordinator.start()
        defer { coordinator.stop() }
        provider.emitFix(fix(now))
        try await waitForFix(coordinator, timestamp: now)
        let near = coordinator.markers(at: now)
        XCTAssertTrue(near.allSatisfy(\.isInRange))
        XCTAssertTrue(near.allSatisfy { $0.visualRank == 2 })
        XCTAssertTrue(coordinator.markers(at: now.addingTimeInterval(61)).allSatisfy { !$0.isInRange })
        XCTAssertEqual(coordinator.points.first?.rewardPoints, 130)
        XCTAssertEqual(coordinator.spawns.first?.rewardPoints, 100)
        XCTAssertFalse(coordinator.markers(at: now.addingTimeInterval(3601)).contains { $0.id == "spawn:spawn" })
    }

    func testExclusiveAwardRetainsActualClippedPointsForTheReadingPanel() async throws {
        let now = Date(timeIntervalSince1970: 2000)
        let repository = Repository(now: now)
        let provider = StubLocationProvider(authorization: .whileInUse)
        let coordinator = makeCoordinator(repository, provider)
        await coordinator.refresh(camera: .of(latitude: 57, longitude: 12, zoom: 14, bearing: 0, pitch: 0), visibleRadiusMeters: 200)
        coordinator.start()
        defer { coordinator.stop() }
        provider.emitFix(fix(now.addingTimeInterval(-5)))
        try await waitForFix(coordinator, timestamp: now.addingTimeInterval(-5))
        provider.emitFix(fix(now))
        try await waitForFix(coordinator, timestamp: now)
        coordinator.select(markerId: "spawn:spawn")
        XCTAssertEqual(coordinator.collectState(now: now), .ready)
        await coordinator.collect(now: now)
        XCTAssertNil(coordinator.selectedTarget, "Exclusive source keeps its existing removal behavior")
        guard case .spawn(let outcome) = coordinator.claimStatus else { return XCTFail("Missing server result") }
        XCTAssertEqual(outcome.pointsAwarded, 7)
        XCTAssertTrue(CrownCollectPresentation.isAwarded(coordinator.claimStatus))
        XCTAssertFalse(CrownCollectPresentation.canSubmit(.ready, status: coordinator.claimStatus, collected: false))
        XCTAssertTrue(coordinator.spawns.isEmpty)
    }

    func testOlderRefreshCannotResurrectAConfirmedCollection() async throws {
        let now = Date(timeIntervalSince1970: 2000)
        let repository = Repository(now: now)
        let provider = StubLocationProvider(authorization: .whileInUse)
        let coordinator = makeCoordinator(repository, provider)
        await coordinator.refresh(camera: .of(latitude: 57, longitude: 12, zoom: 14, bearing: 0, pitch: 0), visibleRadiusMeters: 200)
        coordinator.start()
        provider.emitFix(fix(now.addingTimeInterval(-5)))
        try await waitForFix(coordinator, timestamp: now.addingTimeInterval(-5))
        provider.emitFix(fix(now))
        try await waitForFix(coordinator, timestamp: now)
        repository.holdCollectedRead = true
        let refresh = Task {
            await coordinator.refresh(camera: .of(latitude: 57, longitude: 12.03, zoom: 14, bearing: 0, pitch: 0), visibleRadiusMeters: 200)
        }
        defer { repository.resumeCollectedRead(); refresh.cancel(); coordinator.stop() }
        let deadline = ContinuousClock.now.advanced(by: .seconds(3))
        while !repository.collectedReadPending, ContinuousClock.now < deadline {
            try await Task.sleep(for: .milliseconds(5))
        }
        guard repository.collectedReadPending else { return XCTFail("Refresh did not enter its read gate") }
        coordinator.select(markerId: "spawn:spawn")
        await coordinator.collect(now: now)
        repository.resumeCollectedRead()
        await refresh.value
        XCTAssertTrue(coordinator.collectedSpawnIds.contains("spawn"))
        XCTAssertEqual(coordinator.markers(at: now).first { $0.id == "spawn:spawn" }?.collectedByYou, true)
    }

    func testReadingWidgetsWrapLongNamesAndAmountsAtLargeText() {
        let stats = CrownPersonalStats(points: 123456, crownsCollected: 1234, seasonRank: 123,
            seasonPoints: 12345, seasonCrowns: 123, byRarity: [:], streakCurrent: 7, streakBest: 10, seasonsWon: 2, rarest: .rare)
        assertWrapping(PersonalStatsCard(stats: stats))
        assertWrapping(CrownSeasonSummary(stats: stats, seasonId: "2026-10"))
        assertWrapping(LeaderboardRowView(row: CrownLeaderboardRow(rank: 123, uid: "synthetic",
            displayName: "En testmedlem med ett mycket långt visningsnamn", points: 12345678, crownsCollected: 100, isViewer: true)))
        assertWrapping(CrownClaimRow(claim: CrownHuntClaim(id: "test", pointId: "point", result: .awarded, claimedAt: nil, pointsAwarded: 7)))
        assertWrapping(PerkCard(item: PerkShopItem(entry: PerkCatalogEntry(perkId: "shield", kind: .shield,
            name: "Skydd", iconKey: "shield", costKp: 150, blurb: "En längre beskrivning av skyddet", nameEn: "Shield"),
            ownedCount: 2, affordable: true), buyStatus: .idle, onBuy: { _ in XCTFail("Layout must not purchase") }))
    }

    func testAllCollectionRecoveryStatesLayOutWithoutNetwork() {
        let states: [CrownCollectState] = [.ready, .noPosition, .tooFar(120), .waitingForSignal, .confirming, .moving, .featureOff]
        let expired = measure(CrownCollectFeedback(state: .ready, status: .idle, expired: true),
            width: 288, size: .accessibility5, language: "sv", dark: true)
        XCTAssertGreaterThan(expired.height, 0)
        XCTAssertLessThanOrEqual(expired.width, 289)
        for state in states {
            let view = CrownCollectFeedback(state: state, status: .failed(code: "unavailable"))
            let result = measure(view, width: 288, size: .accessibility5, language: "sv", dark: true)
            XCTAssertLessThanOrEqual(result.width, 289)
            XCTAssertGreaterThan(result.height, 0)
        }
    }

    func testHubTabsKeepTheirFullTextAtAccessibilitySizes() {
        for width: CGFloat in [320, 390] {
            let tabs = CrownHubTabs(selection: .constant(.overview), shopEnabled: true)
            let result = measure(tabs, width: width, size: .accessibility5, language: "sv", dark: false)
            XCTAssertLessThanOrEqual(result.width, width + 1)
            XCTAssertGreaterThanOrEqual(result.height, 48)
            XCTAssertLessThan(result.height, 160)
        }
    }

    private func makeCoordinator(_ repository: Repository, _ provider: StubLocationProvider) -> CrownHuntMapCoordinator {
        CrownHuntMapCoordinator(repository: repository, locationProvider: provider, crownHuntEnabled: true,
            spawnEnabled: true, passesMemberGate: true, uid: "synthetic")
    }
    private func fix(_ timestamp: Date) -> LocationFix {
        LocationFix.of(latitude: 57, longitude: 12, timestamp: timestamp, accuracyMeters: 3, speedMetersPerSecond: 0)!
    }
    private func waitForFix(_ coordinator: CrownHuntMapCoordinator, timestamp: Date) async throws {
        let deadline = ContinuousClock.now.advanced(by: .seconds(3))
        while coordinator.latestFix?.timestamp != timestamp, ContinuousClock.now < deadline {
            try await Task.sleep(for: .milliseconds(5))
        }
        XCTAssertEqual(coordinator.latestFix?.timestamp, timestamp)
    }
    private func assertWrapping<Content: View>(_ view: Content, file: StaticString = #filePath, line: UInt = #line) {
        for (language, dark) in [("sv", true), ("en", false)] {
            for width: CGFloat in [288, 358] {
                let normal = measure(view, width: width, size: .large, language: language, dark: dark)
                let large = measure(view, width: width, size: .accessibility5, language: language, dark: dark)
                XCTAssertLessThanOrEqual(large.width, width + 1, file: file, line: line)
                XCTAssertGreaterThan(large.height, normal.height, file: file, line: line)
                XCTAssertTrue(large.height.isFinite, file: file, line: line)
            }
        }
    }
    private func measure<Content: View>(_ view: Content, width: CGFloat, size: DynamicTypeSize, language: String, dark: Bool) -> CGSize {
        let host = UIHostingController(rootView: view.environment(\.locale, Locale(identifier: language))
            .environment(\.dynamicTypeSize, size).environment(\.colorScheme, dark ? .dark : .light))
        host.overrideUserInterfaceStyle = dark ? .dark : .light
        host.loadViewIfNeeded()
        return host.sizeThatFits(in: CGSize(width: width, height: 10000))
    }
}
