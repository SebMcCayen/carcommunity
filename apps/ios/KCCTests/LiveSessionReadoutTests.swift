import XCTest
@testable import KCC

final class LiveSessionReadoutTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 20_000)
    private func session(_ id: String = "a", elapsed: TimeInterval = 120) -> LiveSessionInfo {
        LiveSessionInfo(sessionId: id, status: .active, duration: .sixHours,
            expiresAt: now.addingTimeInterval(21_600 - elapsed))
    }

    func testTapAnchorSurvivesColdNilAndLaterServerClockChanges() {
        var value = LiveSessionReadout()
        value.requestedStart(at: now)
        value.observe(nil, at: now)
        value.observe(session(elapsed: 40), at: now.addingTimeInterval(2))
        XCTAssertEqual(value.startedAt, now)
        value.observe(session(elapsed: 400), at: now.addingTimeInterval(5))
        XCTAssertEqual(value.startedAt, now)
    }

    func testColdResumeAndNewSessionDoNotReuseTheOldTimeOrSpeed() {
        var value = LiveSessionReadout()
        value.observe(session(), at: now)
        XCTAssertEqual(value.startedAt, now.addingTimeInterval(-120))
        value.acceptSpeed(from: LocationFix.of(latitude: 57, longitude: 12,
            timestamp: now, speedMetersPerSecond: 10)!)
        value.observe(session("b", elapsed: 30), at: now)
        XCTAssertEqual(value.startedAt, now.addingTimeInterval(-30))
        XCTAssertNil(value.speed)
        value.observe(nil, at: now)
        XCTAssertNil(value.startedAt)
        XCTAssertNil(value.sessionId)
    }

    func testExpiredSessionAndFailedStartDiscardDisplayState() {
        var value = LiveSessionReadout()
        value.requestedStart(at: now.addingTimeInterval(-10))
        value.cancelPendingStart()
        value.observe(session(elapsed: 30), at: now)
        XCTAssertEqual(value.startedAt, now.addingTimeInterval(-30))
        value.observe(session(), at: now.addingTimeInterval(21_600))
        XCTAssertNil(value.startedAt)
        XCTAssertNil(value.speed)
    }

    func testFutureStartClampsAndTimerKeepsHours() {
        var value = LiveSessionReadout()
        value.observe(session(elapsed: -120), at: now)
        XCTAssertEqual(value.startedAt, now)
        XCTAssertEqual(LiveSessionReadoutFormat.elapsed(startedAt: now, now: now.addingTimeInterval(-5)), "0:00")
        XCTAssertEqual(LiveSessionReadoutFormat.elapsed(startedAt: now, now: now.addingTimeInterval(3_723)), "1:02:03")
    }

    func testSpeedUsesPlatformReadingDeadbandAndStaleCutoff() {
        func sample(_ speed: Double?, age: TimeInterval = 0) -> LiveSpeedSample {
            LiveSpeedSample(metersPerSecond: speed, capturedAt: now.addingTimeInterval(-age))
        }
        XCTAssertEqual(LiveSessionReadoutFormat.speedKmh(sample(10), at: now, previous: nil), 36)
        XCTAssertEqual(LiveSessionReadoutFormat.speedKmh(sample(36.8 / 3.6), at: now, previous: 36), 36)
        XCTAssertEqual(LiveSessionReadoutFormat.speedKmh(sample(37.2 / 3.6), at: now, previous: 36), 37)
        XCTAssertEqual(LiveSessionReadoutFormat.speedKmh(sample(0), at: now, previous: 36), 0)
        XCTAssertNil(LiveSessionReadoutFormat.speedKmh(sample(nil), at: now, previous: 36))
        XCTAssertNil(LiveSessionReadoutFormat.speedKmh(sample(10, age: 15), at: now, previous: 36))
        for invalid in [-1.0, .nan, .infinity, .greatestFiniteMagnitude] {
            XCTAssertNil(LiveSessionReadoutFormat.speedKmh(sample(invalid), at: now, previous: nil))
        }
    }
}
