import XCTest

@testable import KCC

/// Unit tests for the pure live-location vocabulary — the iOS mirror of
/// Android's `LiveLocationTest`/`BackgroundLocationTest`: the sharing rule,
/// the 6h cap agreement, the publish throttle, and the wire mappings.
final class LiveLocationModelsTests: XCTestCase {

    private let now = Date(timeIntervalSince1970: 1_700_000_000)

    private func session(
        status: LiveSessionStatus = .active,
        expiresAt: Date?
    ) -> LiveSessionInfo {
        LiveSessionInfo(
            sessionId: "session-1",
            status: status,
            duration: .sixHours,
            expiresAt: expiresAt
        )
    }

    // MARK: - isSharing

    func testActiveUnexpiredSessionIsSharing() {
        XCTAssertTrue(LiveLocation.isSharing(session(expiresAt: now.addingTimeInterval(60)), at: now))
    }

    func testExpiredActiveSessionIsNotSharing() {
        XCTAssertFalse(LiveLocation.isSharing(session(expiresAt: now.addingTimeInterval(-1)), at: now))
    }

    func testStoppedSessionIsNotSharing() {
        XCTAssertFalse(
            LiveLocation.isSharing(
                session(status: .stopped, expiresAt: now.addingTimeInterval(60)),
                at: now
            )
        )
    }

    func testNilSessionIsNotSharing() {
        XCTAssertFalse(LiveLocation.isSharing(nil, at: now))
    }

    /// A nil (unparseable) expiry must not hide an active session — the user
    /// is never left without a stop control (Android's contract).
    func testActiveSessionWithUnparseableExpiryIsStillSharing() {
        XCTAssertTrue(LiveLocation.isSharing(session(expiresAt: nil), at: now))
    }

    // MARK: - remainingSeconds

    func testRemainingSecondsFloorsAtZero() {
        XCTAssertEqual(
            LiveLocation.remainingSeconds(session(expiresAt: now.addingTimeInterval(-30)), at: now),
            0
        )
        XCTAssertEqual(
            LiveLocation.remainingSeconds(session(expiresAt: now.addingTimeInterval(90)), at: now),
            90
        )
        XCTAssertNil(LiveLocation.remainingSeconds(session(expiresAt: nil), at: now))
    }

    // MARK: - duration / cap agreement

    func testDefaultSessionDurationIsTheSixHourCap() {
        XCTAssertEqual(LiveLocation.defaultSessionDuration, .sixHours)
        // The client copy of the server's LIVE_SESSION_MAX_MS (and Android's
        // LiveLocation.LIVE_SESSION_MAX_MS): assert the default window IS the
        // hard cap, so a retune must touch both deliberately.
        XCTAssertEqual(
            TimeInterval(LiveLocation.defaultSessionDuration.hours) * 3600,
            LiveLocation.maxSessionInterval
        )
    }

    func testDurationKeysRoundTrip() {
        for duration in LiveSessionDuration.allCases {
            XCTAssertEqual(LiveSessionDuration.fromKey(duration.key), duration)
        }
        XCTAssertNil(LiveSessionDuration.fromKey("30m"))
        XCTAssertNil(LiveSessionDuration.fromKey(nil))
    }

    func testStatusWireValuesRoundTrip() {
        XCTAssertEqual(LiveSessionStatus.fromWire("active"), .active)
        XCTAssertEqual(LiveSessionStatus.fromWire("stopped"), .stopped)
        XCTAssertEqual(LiveSessionStatus.fromWire("expired"), .expired)
        XCTAssertNil(LiveSessionStatus.fromWire("paused"))
    }

    // MARK: - cadence throttle (BackgroundLocation.shouldPublish parity)

    func testFirstFixAlwaysPublishes() {
        XCTAssertTrue(
            LiveShareCadence.shouldPublish(
                lastSubmittedAt: nil,
                lastSubmittedLatitude: nil,
                lastSubmittedLongitude: nil,
                latitude: 57.5,
                longitude: 12.1,
                now: now
            )
        )
    }

    func testSmallJitterWithinHeartbeatDoesNotPublish() {
        // ~5 m north — under the 15 m movement threshold, 10 s after the
        // last submit — inside the 3 min heartbeat.
        XCTAssertFalse(
            LiveShareCadence.shouldPublish(
                lastSubmittedAt: now.addingTimeInterval(-10),
                lastSubmittedLatitude: 57.5,
                lastSubmittedLongitude: 12.1,
                latitude: 57.500045,
                longitude: 12.1,
                now: now
            )
        )
    }

    func testMovementPastThresholdPublishes() {
        // ~20 m north — past the 15 m movement threshold.
        XCTAssertTrue(
            LiveShareCadence.shouldPublish(
                lastSubmittedAt: now.addingTimeInterval(-5),
                lastSubmittedLatitude: 57.5,
                lastSubmittedLongitude: 12.1,
                latitude: 57.50018,
                longitude: 12.1,
                now: now
            )
        )
    }

    func testFixFasterThanMinUpdateIntervalIsThrottledEvenWithMovement() {
        // ~110 m of movement, but only 1 s after the last submit — under the
        // 3 s publication floor. CoreLocation can deliver above 1 Hz, so the
        // floor is enforced in the publication throttle on both platforms.
        XCTAssertFalse(
            LiveShareCadence.shouldPublish(
                lastSubmittedAt: now.addingTimeInterval(-1),
                lastSubmittedLatitude: 57.5,
                lastSubmittedLongitude: 12.1,
                latitude: 57.501,
                longitude: 12.1,
                now: now
            )
        )
    }

    func testStationaryHeartbeatPublishes() {
        // No movement at all, but the 3 min heartbeat has elapsed.
        XCTAssertTrue(
            LiveShareCadence.shouldPublish(
                lastSubmittedAt: now.addingTimeInterval(-LiveShareCadence.stationaryHeartbeat),
                lastSubmittedLatitude: 57.5,
                lastSubmittedLongitude: 12.1,
                latitude: 57.5,
                longitude: 12.1,
                now: now
            )
        )
    }

    func testDistanceMetersMatchesKnownValue() {
        // One degree of latitude is ~111.2 km on the WGS-84 sphere model.
        let distance = LiveShareCadence.distanceMeters(lat1: 57.0, lon1: 12.0, lat2: 58.0, lon2: 12.0)
        XCTAssertEqual(distance, 111_195, accuracy: 100)
        XCTAssertEqual(LiveShareCadence.distanceMeters(lat1: 57.5, lon1: 12.1, lat2: 57.5, lon2: 12.1), 0)
    }

    func testSlowMotionUsesThreeSecondCadenceBelowDistanceThreshold() {
        func eligible(_ elapsed: TimeInterval) -> Bool {
            LiveShareCadence.shouldPublish(
                lastSubmittedAt: now.addingTimeInterval(-elapsed),
                lastSubmittedLatitude: 57.5, lastSubmittedLongitude: 12.1,
                latitude: 57.500027, longitude: 12.1, now: now,
                lastSubmittedSpeedMetersPerSecond: 1, speedMetersPerSecond: 1
            )
        }
        XCTAssertFalse(eligible(2.999))
        XCTAssertTrue(eligible(3))
    }

    func testStopBypassesFloorOnceThenUsesSparseHeartbeat() {
        func eligible(_ previous: Double?, _ speed: Double?, _ elapsed: TimeInterval) -> Bool {
            LiveShareCadence.shouldPublish(
                lastSubmittedAt: now.addingTimeInterval(-elapsed),
                lastSubmittedLatitude: 57.5, lastSubmittedLongitude: 12.1,
                latitude: 57.5, longitude: 12.1, now: now,
                lastSubmittedSpeedMetersPerSecond: previous, speedMetersPerSecond: speed
            )
        }
        XCTAssertTrue(eligible(12, 0, 1))
        XCTAssertFalse(eligible(0, 0, 3))
        XCTAssertFalse(eligible(12, nil, 1))
        XCTAssertFalse(eligible(12, .nan, 1))
        XCTAssertTrue(eligible(0, 0, LiveShareCadence.stationaryHeartbeat))
    }

    // MARK: - wire mappings

    func testLiveCoordinateFromFixCarriesEveryField() throws {
        let timestamp = Date(timeIntervalSince1970: 1_700_000_123.5)
        let fix = try XCTUnwrap(
            LocationFix.of(
                latitude: 57.5,
                longitude: 12.1,
                timestamp: timestamp,
                accuracyMeters: 8,
                headingDegrees: 90,
                speedMetersPerSecond: 13.9
            )
        )
        let coordinate = LiveCoordinate(fix: fix)
        XCTAssertEqual(coordinate.latitude, 57.5)
        XCTAssertEqual(coordinate.longitude, 12.1)
        XCTAssertEqual(coordinate.accuracyMeters, 8)
        XCTAssertEqual(coordinate.headingDegrees, 90)
        XCTAssertEqual(coordinate.speedMetersPerSecond, 13.9)
        // The ISO instant round-trips to the fix's timestamp (ms precision).
        let parsed = try XCTUnwrap(LiveIsoInstant.parse(coordinate.recordedAtIso))
        XCTAssertEqual(parsed.timeIntervalSince1970, timestamp.timeIntervalSince1970, accuracy: 0.001)
        XCTAssertTrue(coordinate.recordedAtIso.hasSuffix("Z"))
    }

    func testIsoParsingAcceptsBothBackendAndAndroidShapes() {
        // Backend: JavaScript Date.toISOString() — always fractional.
        XCTAssertEqual(
            LiveIsoInstant.parse("2026-08-30T12:00:00.000Z"),
            Date(timeIntervalSince1970: 1_788_091_200)
        )
        // Android: Instant.toString() — fractional omitted when zero.
        XCTAssertEqual(
            LiveIsoInstant.parse("2026-08-30T12:00:00Z"),
            Date(timeIntervalSince1970: 1_788_091_200)
        )
        XCTAssertNil(LiveIsoInstant.parse("not-a-date"))
    }

    func testSessionInfoFromMapParsesTheBackendNode() throws {
        let info = try XCTUnwrap(
            LiveSessionInfo.fromMap([
                "id": "session-9",
                "status": "active",
                "duration": "6h",
                "expiresAt": "2026-08-30T12:00:00.000Z",
                "vehicleId": "vehicle-7",
                "mainCar": ["imagePath": "vehicleImages/u/vehicle-7/cover"],
                "convoyId": "convoy-2",
                "convoyAutoStarted": true,
            ])
        )
        XCTAssertEqual(info.sessionId, "session-9")
        XCTAssertEqual(info.status, .active)
        XCTAssertEqual(info.duration, .sixHours)
        XCTAssertEqual(info.expiresAt, Date(timeIntervalSince1970: 1_788_091_200))
        XCTAssertEqual(info.vehicleId, "vehicle-7")
        XCTAssertEqual(info.carImagePath, "vehicleImages/u/vehicle-7/cover")
        XCTAssertEqual(info.convoyId, "convoy-2")
        XCTAssertTrue(info.convoyAutoStarted)
    }

    func testSessionInfoFromMapToleratesUnknownDurationAndBadExpiry() throws {
        // Older/newer wire values degrade field-by-field, never to a crash —
        // and an unparseable expiry yields nil (still-sharing per isSharing).
        let info = try XCTUnwrap(
            LiveSessionInfo.fromMap([
                "id": "session-9",
                "status": "active",
                "duration": "45m",
                "expiresAt": "soon",
            ])
        )
        XCTAssertFalse(info.convoyAutoStarted)
        XCTAssertNil(info.duration)
        XCTAssertNil(info.expiresAt)
        XCTAssertTrue(LiveLocation.isSharing(info, at: now))
    }

    func testSessionInfoFromMapRejectsMissingRequiredFields() {
        XCTAssertNil(LiveSessionInfo.fromMap(["status": "active"]))
        XCTAssertNil(LiveSessionInfo.fromMap(["id": "session-9"]))
        XCTAssertNil(LiveSessionInfo.fromMap(["id": "session-9", "status": "unheard-of"]))
    }

    func testLiveMarkerParsesViewerFieldsAndMainCarPhoto() throws {
        let marker = try XCTUnwrap(LiveMarker.fromMap(uid: "member-1", [
            "latitude": NSNumber(value: 57.49),
            "longitude": NSNumber(value: 12.08),
            "displayName": " Driver ",
            "mainCar": ["imagePath": "garage/member-1/car.jpg"],
            "recordedAt": "2026-08-30T12:00:00.000Z",
            "accuracyMeters": NSNumber(value: 8.5),
        ]))
        XCTAssertEqual(marker.uid, "member-1")
        XCTAssertEqual(marker.displayName, "Driver")
        XCTAssertEqual(marker.imagePath, "garage/member-1/car.jpg")
        XCTAssertEqual(marker.accuracyMeters, 8.5)
        XCTAssertEqual(marker.recordedAt, Date(timeIntervalSince1970: 1_788_091_200))
    }

    func testLiveMarkerRejectsMissingOrOutOfRangeCoordinates() {
        XCTAssertNil(LiveMarker.fromMap(uid: "member", ["longitude": 12.0]))
        XCTAssertNil(LiveMarker.fromMap(uid: "member", ["latitude": 91.0, "longitude": 12.0]))
        XCTAssertNil(LiveMarker.fromMap(uid: "", ["latitude": 57.0, "longitude": 12.0]))
    }
}

final class DriverMotionTimelineTests: XCTestCase {
    private let point = DriverMotionPoint(latitude: 57, longitude: 12, bearing: 0)
    private let epoch = Date(timeIntervalSince1970: 10_000)

    func testThreeSecondUpdatesStayContinuousAndRetargetWithoutTeleporting() {
        var motion = DriverMotionTimeline()
        motion.update(point, recordedAt: epoch, sampleAge: 0, now: 0, speed: 10, course: 0)
        XCTAssertEqual(point, motion.frame(at: 0.250))
        var previous = motion.frame(at: 0.250)!
        for step in 6...60 {
            let frame = motion.frame(at: Double(step) / 20)!
            XCTAssertGreaterThan(frame.latitude, previous.latitude)
            previous = frame
        }
        let next = DriverMotionPoint(latitude: point.latitude + 30 / 6_371_000 * 180 / .pi, longitude: point.longitude, bearing: 0)
        let before = motion.frame(at: 3)
        motion.update(next, recordedAt: epoch.addingTimeInterval(3), sampleAge: 0, now: 3, speed: 10, course: 0)
        XCTAssertEqual(before, motion.frame(at: 3))
        XCTAssertEqual(next.latitude, motion.frame(at: 3.250)!.latitude, accuracy: 1e-9)
        XCTAssertGreaterThan(motion.frame(at: 3.300)!.latitude, next.latitude)
    }

    func testSelfNearbyAndConvoyHaveOnePresentationPolicy() {
        var tracks = Array(repeating: DriverMotionTimeline(), count: 3)
        for index in tracks.indices {
            tracks[index].update(point, recordedAt: epoch, sampleAge: 0.100, now: 0.100, speed: 20, course: 90)
        }
        for step in 1...250 {
            let time = Double(step) / 60
            let frames = tracks.indices.map { index in tracks[index].frame(at: time) }
            XCTAssertEqual(frames[0], frames[1]); XCTAssertEqual(frames[0], frames[2])
        }
        XCTAssertEqual(DriverMotionPolicy.presentationDelay, 0.250)
    }

    func testStopReplacesEstimatedVelocityAndStaysStopped() {
        var motion = DriverMotionTimeline()
        motion.update(point, recordedAt: epoch, sampleAge: 0, now: 0, speed: 20, course: 0)
        _ = motion.frame(at: 2)
        let stopped = DriverMotionPoint(latitude: 57.0001, longitude: 12, bearing: 0)
        motion.update(stopped, recordedAt: epoch.addingTimeInterval(2), sampleAge: 0, now: 2, speed: 0, course: 0)
        XCTAssertEqual(stopped, motion.frame(at: 2.250))
        XCTAssertEqual(stopped, motion.frame(at: 30))
        XCTAssertFalse(motion.isAnimating(at: 2.250))
    }

    func testPredictionIsBoundedBySourceTimeRatherThanPacketArrival() {
        var motion = DriverMotionTimeline()
        motion.update(point, recordedAt: epoch, sampleAge: 20, now: 30, speed: 20, course: 0)
        XCTAssertEqual(point, motion.frame(at: 30))
        XCTAssertEqual(motion.frame(at: 30), motion.frame(at: 60))
        XCTAssertFalse(motion.isAnimating(at: 30))
        var fresh = DriverMotionTimeline()
        fresh.update(point, recordedAt: epoch, sampleAge: 0, now: 0, speed: 20, course: 0)
        XCTAssertEqual(fresh.frame(at: 3.750), fresh.frame(at: 60))
        XCTAssertFalse(fresh.isAnimating(at: 3.750))
    }

    func testOldDuplicateFutureAndUndatedSamplesDoNotInventMotion() {
        var motion = DriverMotionTimeline()
        motion.update(point, recordedAt: epoch, sampleAge: 0, now: 0)
        XCTAssertFalse(motion.update(point, recordedAt: epoch, sampleAge: 0, now: 1))
        XCTAssertFalse(motion.update(point, recordedAt: epoch.addingTimeInterval(-1), sampleAge: 0, now: 1))
        for (recordedAt, age) in [(Optional<Date>.none, 0.0), (Optional(epoch), -1.0)] {
            var unknown = DriverMotionTimeline()
            unknown.update(point, recordedAt: recordedAt, sampleAge: age, now: 0, speed: 20, course: 0)
            XCTAssertEqual(point, unknown.frame(at: 30))
        }
    }

    func testBearingAndLongitudeCrossTheirWrapWithoutSpinning() {
        let a = DriverMotionPoint(latitude: 0, longitude: 179, bearing: 359)
        let b = DriverMotionPoint(latitude: 0, longitude: -179, bearing: 1)
        let middle = DriverMotionTimeline.interpolate(a, b, fraction: 0.5)
        XCTAssertEqual(middle.longitude, -180, accuracy: 1e-9)
        XCTAssertEqual(middle.bearing!, 0, accuracy: 1e-9)
    }

    func testTurnCorrectionDoesNotTeleportAndThenFollowsTheNewCourse() {
        var motion = DriverMotionTimeline()
        motion.update(point, recordedAt: epoch, sampleAge: 0, now: 0, speed: 10, course: 0)
        let before = motion.frame(at: 3)
        let corner = DriverMotionPoint(latitude: 57.00027, longitude: 12, bearing: 90)
        motion.update(corner, recordedAt: epoch.addingTimeInterval(3), sampleAge: 0, now: 3, speed: 10, course: 90)
        XCTAssertEqual(before, motion.frame(at: 3))
        let east = motion.frame(at: 3.500)!
        XCTAssertGreaterThan(east.longitude, corner.longitude)
        XCTAssertEqual(east.bearing!, 90, accuracy: 1e-9)
    }

    func testMarkerParsingPreservesOptionalPhysicalMotionFields() {
        let marker = LiveMarker.fromMap(uid: "driver", ["latitude": 57.0, "longitude": 12.0,
            "recordedAt": "2026-11-17T18:00:00Z", "headingDegrees": 359.0, "speedMetersPerSecond": 12.5])!
        XCTAssertEqual(marker.headingDegrees, 359)
        XCTAssertEqual(marker.speedMetersPerSecond, 12.5)
        let member = ConvoyMemberPosition(marker: marker)
        XCTAssertEqual(member.headingDegrees, marker.headingDegrees)
        XCTAssertEqual(member.speedMetersPerSecond, marker.speedMetersPerSecond)
    }
}
