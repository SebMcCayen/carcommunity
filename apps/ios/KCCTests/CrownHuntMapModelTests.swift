import XCTest

@testable import KCC

final class CrownHuntMapModelTests: XCTestCase {
    func testProximityMeasuresToTheRingAndMatchesAndroidApproachWindow() throws {
        for radius in [30.0, 75.0, 150.0] {
            for (gap, fraction) in [(-1.0, 1.0), (0.0, 1.0), (1.0, 0.998),
                                    (250.0, 0.5), (500.0, 0.0), (5_000.0, 0.0)] {
                let proximity = try XCTUnwrap(CrownProximity(distanceMeters: radius + gap, collectRadiusMeters: radius))
                XCTAssertEqual(proximity.remainingToRingMeters, max(0, gap), accuracy: 0.0001)
                XCTAssertEqual(proximity.fraction, fraction, accuracy: 0.0001)
            }
        }
        for distance in [-1.0, Double.nan, .infinity] {
            XCTAssertNil(CrownProximity(distanceMeters: distance, collectRadiusMeters: 75))
        }
        for radius in [0.0, -1, Double.nan, .infinity] {
            XCTAssertNil(CrownProximity(distanceMeters: 100, collectRadiusMeters: radius))
        }
    }

    func testProximityFormatsMetresKilometresAndTheActualRadiusInBothLanguages() throws {
        let proximity = try XCTUnwrap(CrownProximity(distanceMeters: 1_550, collectRadiusMeters: 150))
        for language in ["sv", "en"] {
            let locale = Locale(identifier: language)
            let path = try XCTUnwrap(Bundle.main.path(forResource: language, ofType: "lproj"))
            let bundle = try XCTUnwrap(Bundle(path: path))
            let swedish = language == "sv"
            XCTAssertEqual(CrownProximityText.distance(120.5, locale: locale, bundle: bundle),
                           swedish ? "121 m bort" : "121 m away")
            XCTAssertEqual(CrownProximityText.distance(999, short: true, locale: locale, bundle: bundle), "999 m")
            XCTAssertEqual(CrownProximityText.distance(1_000, locale: locale, bundle: bundle),
                           swedish ? "1,0 km bort" : "1.0 km away")
            XCTAssertEqual(CrownProximityText.distance(1_450, short: true, locale: locale, bundle: bundle),
                           swedish ? "1,5 km" : "1.5 km")
            XCTAssertEqual(CrownProximityText.remaining(proximity, locale: locale, bundle: bundle),
                           swedish ? "1,4 km kvar" : "1.4 km to go")
            XCTAssertEqual(CrownProximityText.radiusHint(proximity, locale: locale, bundle: bundle),
                           swedish ? "Du behöver vara inom 150 m från kronan." : "You need to be within 150 m of the crown.")
            XCTAssertEqual(CrownProximityText.distance(.nan, locale: locale, bundle: bundle), "")
        }
    }

    func testDistantDayCrownsUseAndroidLightPairsWithoutChangingReadyOrNightColors() {
        let expectedSurfaces: [UInt32] = [0xFFECE9E3, 0xFFE6F3EB, 0xFFEEE7FA, 0xFFFFF0CC]
        let expectedInks: [UInt32] = [0xFF5B6168, 0xFF1B6E4B, 0xFF6640A0, 0xFF946000]
        for rank in 0..<4 {
            let day = CrownMapTileLightPalette.colors(rank: rank, mapMode: .day, inRange: false)
            XCTAssertEqual(day?.surface, expectedSurfaces[rank])
            XCTAssertEqual(day?.foreground, expectedInks[rank])
            if let day { XCTAssertGreaterThanOrEqual(IncidentMarkerStyle.contrast(day.surface, day.foreground), 4.5) }
            XCTAssertNil(CrownMapTileLightPalette.colors(rank: rank, mapMode: .night, inRange: false))
            XCTAssertNil(CrownMapTileLightPalette.colors(rank: rank, mapMode: .day, inRange: true))
            XCTAssertNil(CrownMapTileLightPalette.colors(rank: rank, mapMode: .night, inRange: true))
        }
    }

    private final class PerkEffectsRepository: CrownPerkDeployRepository, @unchecked Sendable {
        private let updates = AsyncStream<CrownPerkEffectSnapshot>.makeStream()
        private(set) var deployCount = 0

        func effects(uid: String, now: Date) -> AsyncStream<CrownPerkEffectSnapshot> {
            updates.stream
        }

        func send(_ snapshot: CrownPerkEffectSnapshot) {
            updates.continuation.yield(snapshot)
        }

        func deploy(
            perkId: String,
            latitude: Double?,
            longitude: Double?,
            idempotencyKey: String
        ) async throws -> CrownPerkDeployResult {
            deployCount += 1
            return CrownPerkDeployResult(
                perkId: perkId,
                kind: PerkKind.fromWire(perkId) ?? .trap,
                effectId: "effect",
                expiresAt: .distantFuture,
                inventoryCount: 1,
                alreadyDeployed: false
            )
        }
    }

    private final class OwnedPerksRepository: PerkShopRepository {
        func catalog() -> AsyncStream<PerkCatalogSnapshot> {
            AsyncStream { $0.finish() }
        }

        func inventory(uid: String) -> AsyncStream<[String: Int]> {
            AsyncStream {
                $0.yield(["spike_strip": 1, "shield": 2, "boost": 1])
                $0.finish()
            }
        }

        func buyPerk(perkId: String, idempotencyKey: String) async throws -> PerkPurchaseResult {
            throw PerkPurchaseError(reason: .unknown)
        }
    }

    func testSpawnQueryUsesNeighbourRingAndBoundedBatches() {
        let near = CrownSpawnQuery.cellKeys(
            latitude: 57.4872,
            longitude: 12.0761,
            visibleRadiusMeters: 200
        )
        XCTAssertEqual(near.count, 9)
        XCTAssertTrue(near.contains("5748_1207"))

        let wide = CrownSpawnQuery.cellKeys(
            latitude: 57.4872,
            longitude: 12.0761,
            visibleRadiusMeters: 50_000
        )
        XCTAssertLessThanOrEqual(wide.count, CrownSpawnQuery.maximumCells)
        let batches = CrownSpawnQuery.batches(wide)
        XCTAssertLessThanOrEqual(batches.count, 5)
        XCTAssertTrue(batches.allSatisfy { $0.count <= CrownSpawnQuery.firestoreInLimit })
    }

    func testCollectRadiusFallsClosedToDefault() {
        XCTAssertEqual(CrownSpawnLimits.collectRadius(nil), 75)
        XCTAssertEqual(CrownSpawnLimits.collectRadius(-1), 75)
        XCTAssertEqual(CrownSpawnLimits.collectRadius(251), 75)
        XCTAssertEqual(CrownSpawnLimits.collectRadius(120), 120)
    }

    func testProofRequiresTwoInRangeFixesAtLeastFourSecondsApart() {
        let now = Date(timeIntervalSince1970: 2_000)
        let spawn = makeSpawn()
        var tracker = CrownFixTracker()
        tracker.record(fix(at: now.addingTimeInterval(-3)))
        tracker.record(fix(at: now))
        XCTAssertNil(tracker.proof(for: spawn, now: now))

        tracker.record(fix(at: now.addingTimeInterval(-5)))
        let proof = tracker.proof(for: spawn, now: now)
        XCTAssertNotNil(proof)
        XCTAssertGreaterThanOrEqual(
            proof!.current.timestamp.timeIntervalSince(proof!.previous.timestamp),
            CrownSpawnLimits.minimumDwell
        )
    }

    func testProofRejectsOutOfRangeEarlierFix() {
        let now = Date(timeIntervalSince1970: 2_000)
        let spawn = makeSpawn()
        var tracker = CrownFixTracker()
        tracker.record(LocationFix.of(
            latitude: 57.50,
            longitude: 12.10,
            timestamp: now.addingTimeInterval(-5),
            accuracyMeters: 5,
            speedMetersPerSecond: 0
        )!)
        tracker.record(fix(at: now))
        XCTAssertNil(tracker.proof(for: spawn, now: now))
    }

    func testGateWaitsForProofAndRejectsDerivedMovement() {
        let now = Date(timeIntervalSince1970: 2_000)
        let spawn = makeSpawn()
        let current = fix(at: now)
        XCTAssertEqual(
            CrownCollectGate.evaluate(
                spawn: spawn, latest: current, proof: nil, enabled: true, now: now
            ),
            .confirming
        )
        let movingPrevious = LocationFix.of(
            latitude: 57.4869,
            longitude: 12.0761,
            timestamp: now.addingTimeInterval(-5),
            accuracyMeters: 5,
            speedMetersPerSecond: 0
        )!
        XCTAssertEqual(
            CrownCollectGate.evaluate(
                spawn: spawn,
                latest: current,
                proof: CrownFixPair(previous: movingPrevious, current: current),
                enabled: true,
                now: now
            ),
            .moving
        )
    }

    func testGateBlocksStaleOrCoarseCurrentFix() {
        let now = Date(timeIntervalSince1970: 2_000)
        let spawn = makeSpawn()
        let stale = fix(at: now.addingTimeInterval(-61))
        XCTAssertEqual(
            CrownCollectGate.evaluate(spawn: spawn, latest: stale, proof: nil, enabled: true, now: now),
            .noPosition
        )
        let coarse = LocationFix.of(
            latitude: spawn.latitude,
            longitude: spawn.longitude,
            timestamp: now,
            accuracyMeters: 100,
            speedMetersPerSecond: 0
        )!
        XCTAssertEqual(
            CrownCollectGate.evaluate(spawn: spawn, latest: coarse, proof: nil, enabled: true, now: now),
            .waitingForSignal
        )
    }

    @MainActor
    func testQueuedPerkSnapshotDoesNotRestoreEffectsAfterStop() async {
        let repository = PerkEffectsRepository()
        let coordinator = CrownPerkMapCoordinator(
            repository: repository,
            shopRepository: OwnedPerksRepository(),
            uid: "member",
            enabled: true,
            location: { nil }
        )
        await startPerks(coordinator, repository: repository, effects: .empty)
        repository.send(.shield(.distantFuture))
        coordinator.stop()
        await Task.yield()

        XCTAssertEqual(coordinator.effects, .empty)
        XCTAssertFalse(coordinator.hasLoadedEffects)
    }

    @MainActor
    func testTrapDeployRejectsStaleLocation() async {
        let repository = PerkEffectsRepository()
        let now = Date(timeIntervalSince1970: 2_000)
        let coordinator = CrownPerkMapCoordinator(
            repository: repository,
            shopRepository: OwnedPerksRepository(),
            uid: "member",
            enabled: true,
            location: { self.fix(at: now.addingTimeInterval(-61)) }
        )
        defer { coordinator.stop() }
        await startPerks(coordinator, repository: repository, effects: .empty)

        await coordinator.deploy(perkId: "spike_strip", kind: .trap, now: now)

        XCTAssertEqual(coordinator.status, .failed("spike_strip", .noLocation))
        XCTAssertEqual(repository.deployCount, 0)
    }

    @MainActor
    func testActivePerksCannotBeSpentAgainUntilTheyExpire() async {
        let repository = PerkEffectsRepository()
        let now = Date(timeIntervalSince1970: 2_000)
        let expiry = now.addingTimeInterval(0.5)
        let coordinator = CrownPerkMapCoordinator(
            repository: repository,
            shopRepository: OwnedPerksRepository(),
            uid: "member",
            enabled: true,
            location: { nil }
        )
        defer { coordinator.stop() }
        let effects = CrownPerkEffects(
            shieldUntil: expiry,
            boostUntil: now.addingTimeInterval(59.5),
            traps: [CrownOwnTrap(id: "trap", latitude: 57, longitude: 12, expiresAt: expiry)]
        )
        await startPerks(coordinator, repository: repository, effects: effects)

        XCTAssertEqual(effects.activity(for: .shield, at: now), .timed(seconds: 1))
        XCTAssertEqual(effects.activity(for: .boost, at: now), .timed(seconds: 60))
        XCTAssertEqual(effects.activity(for: .trap, at: now), .traps(1))
        XCTAssertEqual(effects.activity(for: .shield, at: expiry), .inactive)
        XCTAssertEqual(effects.activity(for: .trap, at: expiry), .inactive)
        XCTAssertTrue(coordinator.canDeploy(perkId: "shield", kind: .shield, now: expiry))
        XCTAssertTrue(coordinator.canDeploy(perkId: "spike_strip", kind: .trap, now: expiry))
        XCTAssertFalse(coordinator.canDeploy(perkId: "boost", kind: .boost, now: now))
        XCTAssertFalse(coordinator.canDeploy(perkId: "unowned", kind: .shield, now: expiry))

        await coordinator.deploy(perkId: "shield", kind: .shield, now: now)
        await coordinator.deploy(perkId: "spike_strip", kind: .trap, now: now)
        XCTAssertEqual(repository.deployCount, 0)
        XCTAssertEqual(coordinator.status, .idle)
    }

    @MainActor
    func testConfirmedActivationAndLiveTrapRemovalUpdateTheMenu() async {
        let repository = PerkEffectsRepository()
        let now = Date(timeIntervalSince1970: 2_000)
        let coordinator = CrownPerkMapCoordinator(
            repository: repository,
            shopRepository: OwnedPerksRepository(),
            uid: "member",
            enabled: true,
            location: { nil }
        )
        defer { coordinator.stop() }
        await startPerks(coordinator, repository: repository, effects: CrownPerkEffects(
            shieldUntil: nil,
            boostUntil: nil,
            traps: [CrownOwnTrap(id: "trap", latitude: 57, longitude: 12, expiresAt: .distantFuture)]
        ))
        coordinator.openMenu()

        XCTAssertTrue(coordinator.canDeploy(perkId: "shield", kind: .shield, now: now))
        await coordinator.deploy(perkId: "shield", kind: .shield, now: now)
        XCTAssertEqual(repository.deployCount, 1)
        XCTAssertFalse(coordinator.canDeploy(perkId: "shield", kind: .shield, now: now))

        XCTAssertFalse(coordinator.canDeploy(perkId: "spike_strip", kind: .trap, now: now))
        repository.send(.traps([]))
        await waitForPerks { coordinator.canDeploy(perkId: "spike_strip", kind: .trap, now: now) }
    }

    @MainActor
    private func startPerks(
        _ coordinator: CrownPerkMapCoordinator,
        repository: PerkEffectsRepository,
        effects: CrownPerkEffects
    ) async {
        coordinator.start()
        repository.send(.shield(effects.shieldUntil))
        repository.send(.boost(effects.boostUntil))
        repository.send(.traps(effects.traps))
        await waitForPerks { coordinator.hasLoadedEffects && coordinator.hasLoadedInventory }
    }

    @MainActor
    private func waitForPerks(_ condition: () -> Bool) async {
        for _ in 0..<100 {
            if condition() { return }
            try? await Task.sleep(for: .milliseconds(10))
        }
        XCTFail("Perk state did not update")
    }

    private func makeSpawn() -> CrownSpawn {
        CrownSpawn(
            id: "spawn-1",
            latitude: 57.4872,
            longitude: 12.0761,
            rarity: .rare,
            rewardPoints: 100,
            collectRadiusMeters: 75,
            expiresAt: nil
        )
    }

    private func fix(at date: Date) -> LocationFix {
        LocationFix.of(
            latitude: 57.4872,
            longitude: 12.0761,
            timestamp: date,
            accuracyMeters: 5,
            speedMetersPerSecond: 0,
            isSimulatedBySoftware: false
        )!
    }
}
