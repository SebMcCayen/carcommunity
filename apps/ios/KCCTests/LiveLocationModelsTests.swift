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
            ])
        )
        XCTAssertEqual(info.sessionId, "session-9")
        XCTAssertEqual(info.status, .active)
        XCTAssertEqual(info.duration, .sixHours)
        XCTAssertEqual(info.expiresAt, Date(timeIntervalSince1970: 1_788_091_200))
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
        XCTAssertNil(info.duration)
        XCTAssertNil(info.expiresAt)
        XCTAssertTrue(LiveLocation.isSharing(info, at: now))
    }

    func testSessionInfoFromMapRejectsMissingRequiredFields() {
        XCTAssertNil(LiveSessionInfo.fromMap(["status": "active"]))
        XCTAssertNil(LiveSessionInfo.fromMap(["id": "session-9"]))
        XCTAssertNil(LiveSessionInfo.fromMap(["id": "session-9", "status": "unheard-of"]))
    }
}
