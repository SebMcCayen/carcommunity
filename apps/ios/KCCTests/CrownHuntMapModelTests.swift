import XCTest

@testable import KCC

final class CrownHuntMapModelTests: XCTestCase {
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
