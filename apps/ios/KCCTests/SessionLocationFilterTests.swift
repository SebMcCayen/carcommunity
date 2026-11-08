import CoreLocation
import XCTest
@testable import KCC

final class SessionLocationFilterTests: XCTestCase {
    private let start = Date(timeIntervalSince1970: 1_800_000_000)
    private func fix(_ meters: Double, at seconds: Double, accuracy: Double? = 5,
                     speed: Double? = nil, uncertainty: Double? = nil) -> LocationFix {
        LocationFix.of(latitude: 57 + meters / 111_195, longitude: 12,
            timestamp: start.addingTimeInterval(seconds), accuracyMeters: accuracy,
            speedMetersPerSecond: speed, speedAccuracyMetersPerSecond: uncertainty)!
    }
    private func recorder() -> DriveRecorder {
        DriveRecorder(startedAt: start, context: DriveRecordingContext(sourceSessionId: "test",
            vehicleId: nil, carImagePath: nil, convoyMembers: []))
    }
    private func accept(_ sample: LocationFix, with filter: inout SessionLocationFilter,
                        recording: inout DriveRecorder) -> LocationFix? {
        let value = filter.process(sample, now: sample.timestamp)
        if let value { recording.add(value) }
        return value
    }

    func testTwoMinutesOfParkedJitterCannotBecomeDistanceOrSixteenKmh() throws {
        var filter = SessionLocationFilter(), recording = recorder(), unfiltered = recorder()
        let first = try XCTUnwrap(accept(fix(0, at: 0, speed: 0, uncertainty: 0.2), with: &filter, recording: &recording))
        unfiltered.add(fix(0, at: 0))
        for seconds in stride(from: 2, through: 120, by: 2) {
            // A raw 4.4 m/s reading is about 16 km/h, but its uncertainty is
            // larger than the reading. Alternating GPS offsets are not travel.
            let raw = fix(seconds % 4 == 0 ? 8 : -8, at: Double(seconds), speed: 4.4, uncertainty: 5)
            unfiltered.add(raw)
            let output = try XCTUnwrap(accept(raw, with: &filter, recording: &recording))
            XCTAssertEqual(output.latitude, first.latitude)
            XCTAssertEqual(output.timestamp, raw.timestamp, "Live heartbeat must stay fresh")
            XCTAssertNil(output.speedMetersPerSecond, "Unknown speed must not be invented")
        }
        XCTAssertEqual(recording.points.count, 1)
        XCTAssertGreaterThan(unfiltered.summary(endedAt: start.addingTimeInterval(120)).distanceMeters!, 900,
                             "This trace reproduces almost a kilometre without the filter")
        XCTAssertEqual(DriveRecorder.totalDistance(recording.points), 0)
        XCTAssertEqual(recording.request(endedAt: start.addingTimeInterval(120), title: nil).endedAt,
                       start.addingTimeInterval(120), "A parked session still lasts two minutes")
    }

    func testAccurateStationarySpeedHoldsEvenDisplacedFixes() throws {
        var filter = SessionLocationFilter()
        _ = filter.process(fix(0, at: 0, speed: 0, uncertainty: 0.2), now: start)
        for seconds in stride(from: 5, through: 120, by: 5) {
            let raw = fix(seconds % 10 == 0 ? 40 : -40, at: Double(seconds), speed: 0.2, uncertainty: 0.4)
            let value = try XCTUnwrap(filter.process(raw, now: raw.timestamp))
            XCTAssertEqual(value.latitude, 57)
            XCTAssertEqual(value.speedMetersPerSecond, 0)
        }
    }

    func testAPositiveSpeedInsidePositionUncertaintyCannotStartMovement() throws {
        var filter = SessionLocationFilter()
        _ = filter.process(fix(0, at: 0, speed: 0, uncertainty: 0.2), now: start)
        for seconds in stride(from: 2, through: 120, by: 2) {
            let raw = fix(seconds % 4 == 0 ? 8 : -8, at: Double(seconds), speed: 4.4, uncertainty: 0.2)
            let value = try XCTUnwrap(filter.process(raw, now: raw.timestamp))
            XCTAssertEqual(value.latitude, 57)
            XCTAssertNil(value.speedMetersPerSecond)
        }
    }

    func testStartingTheSessionWithAPositiveSpeedDoesNotBypassParkedConfirmation() throws {
        var filter = SessionLocationFilter(), recording = recorder()
        for seconds in stride(from: 0, through: 120, by: 2) {
            let meters = seconds == 0 ? 0.0 : (seconds % 4 == 0 ? 8.0 : -8.0)
            let value = try XCTUnwrap(accept(fix(meters, at: Double(seconds), speed: 4.4, uncertainty: 0.2),
                                            with: &filter, recording: &recording))
            XCTAssertNil(value.speedMetersPerSecond)
            XCTAssertEqual(value.latitude, 57)
        }
        XCTAssertEqual(recording.points.count, 1)
    }

    func testCoarseStaleUnknownAndImpossibleFixesAreRejectedBeforeSeeding() {
        for sample in [fix(0, at: 0, accuracy: 51), fix(0, at: 0, accuracy: nil),
                       fix(0, at: -16), fix(0, at: 6),
                       LocationFix.of(latitude: 91, longitude: 12, timestamp: start, accuracyMeters: 5)!] {
            var filter = SessionLocationFilter()
            XCTAssertNil(filter.process(sample, now: start))
            XCTAssertNotNil(filter.process(fix(0, at: 0), now: start))
        }
    }

    func testUnknownSpeedAccuracyDoesNotAuthorizeAPositiveSpeed() throws {
        var filter = SessionLocationFilter()
        let value = try XCTUnwrap(filter.process(fix(0, at: 0, speed: 4.4), now: start))
        XCTAssertNil(value.speedMetersPerSecond)
    }

    func testGenuineDrivingKeepsDistanceAndSpeedResponsive() throws {
        var filter = SessionLocationFilter(), recording = recorder()
        for seconds in stride(from: 0, through: 40, by: 2) {
            let raw = fix(Double(seconds) * 10, at: Double(seconds), speed: 10, uncertainty: 0.2)
            let value = try XCTUnwrap(accept(raw, with: &filter, recording: &recording))
            if seconds >= 4 {
                XCTAssertEqual(value.latitude, raw.latitude)
                XCTAssertEqual(value.speedMetersPerSecond, 10)
            }
        }
        XCTAssertEqual(recording.points.count, 20)
        XCTAssertEqual(recording.summary(endedAt: start.addingTimeInterval(40)).distanceMeters!, 400, accuracy: 0.1)
    }

    func testStartingStoppingAndStartingAgainDoesNotLoseTheRouteOrInventAPeak() throws {
        var filter = SessionLocationFilter(), recording = recorder()
        let samples = [fix(0, at: 0, speed: 0, uncertainty: 0.2),
            fix(10, at: 2, speed: 5, uncertainty: 0.2), fix(20, at: 4, speed: 5, uncertainty: 0.2),
            fix(30, at: 6, speed: 5, uncertainty: 0.2), fix(40, at: 8, speed: 0, uncertainty: 0.2),
            fix(38, at: 10, speed: 0, uncertainty: 0.2), fix(44, at: 12, speed: 0, uncertainty: 0.2),
            fix(50, at: 14, speed: 5, uncertainty: 0.2), fix(60, at: 16, speed: 5, uncertainty: 0.2),
            fix(70, at: 18, speed: 5, uncertainty: 0.2)]
        for sample in samples { XCTAssertNotNil(accept(sample, with: &filter, recording: &recording)) }
        XCTAssertEqual(recording.summary(endedAt: start.addingTimeInterval(18)).distanceMeters!, 70, accuracy: 0.1)
        for (a, b) in zip(recording.points, recording.points.dropFirst()) {
            let seconds = Double(b.timestampMilliseconds - a.timestampMilliseconds) / 1_000
            XCTAssertLessThanOrEqual(DriveRecorder.segmentDistance(from: a, to: b) / seconds, 5.01)
        }
    }

    func testCreepingWithoutSpeedEventuallyMovesWithoutInflatingDistanceOrSpeed() {
        var filter = SessionLocationFilter(), recording = recorder()
        for seconds in stride(from: 0, through: 80, by: 2) {
            _ = accept(fix(Double(seconds) * 0.5, at: Double(seconds)), with: &filter, recording: &recording)
        }
        XCTAssertGreaterThan(recording.points.count, 2)
        let distance = recording.summary(endedAt: start.addingTimeInterval(80)).distanceMeters!
        XCTAssertGreaterThan(distance, 25)
        XCTAssertLessThanOrEqual(distance, 40.1)
        for (a, b) in zip(recording.points, recording.points.dropFirst()) {
            let seconds = Double(b.timestampMilliseconds - a.timestampMilliseconds) / 1_000
            XCTAssertLessThanOrEqual(DriveRecorder.segmentDistance(from: a, to: b) / seconds, 0.51)
        }
    }

    func testOneHertzUpdatesCanConfirmMovementAfterStandingStill() throws {
        var filter = SessionLocationFilter(), recording = recorder()
        _ = accept(fix(0, at: 0, speed: 0, uncertainty: 0.2), with: &filter, recording: &recording)
        for seconds in 1...20 {
            let sample = fix(Double(seconds) * 10, at: Double(seconds), speed: 10, uncertainty: 0.2)
            let value = try XCTUnwrap(accept(sample, with: &filter, recording: &recording))
            if seconds >= 5 { XCTAssertEqual(value.speedMetersPerSecond, 10) }
        }
        XCTAssertEqual(try XCTUnwrap(recording.summary(endedAt: start.addingTimeInterval(20)).distanceMeters),
                       200, accuracy: 0.1)
        // Recorder remains capped to one point per two seconds after the
        // initial confirmation; the 1 Hz input must not stall that stream.
        XCTAssertGreaterThanOrEqual(recording.points.count, 9)
    }

    func testUncertainPositiveSpeedDoesNotPermanentlyFreezeSlowTraffic() throws {
        var filter = SessionLocationFilter(), recording = recorder()
        for seconds in stride(from: 0, through: 80, by: 2) {
            let sample = fix(Double(seconds), at: Double(seconds), speed: 1, uncertainty: 1.5)
            let output = try XCTUnwrap(accept(sample, with: &filter, recording: &recording))
            XCTAssertNil(output.speedMetersPerSecond, "Uncertain slow motion is not a proven zero")
        }
        let distance = try XCTUnwrap(recording.summary(endedAt: start.addingTimeInterval(80)).distanceMeters)
        XCTAssertGreaterThan(distance, 60)
        XCTAssertLessThanOrEqual(distance, 80.1)
    }

    func testImprovedAccuracyAtOneHertzCanConfirmRealMotorwayMovement() throws {
        var filter = SessionLocationFilter(), recording = recorder()
        _ = accept(fix(0, at: 0, accuracy: 45, speed: 0, uncertainty: 0.2),
                   with: &filter, recording: &recording)
        var latest: LocationFix?
        for seconds in 1...120 {
            latest = accept(fix(Double(seconds) * 20, at: Double(seconds), accuracy: 5,
                                speed: 20, uncertainty: 0.2), with: &filter, recording: &recording)
        }
        XCTAssertEqual(try XCTUnwrap(latest).latitude, fix(2_400, at: 120).latitude)
        XCTAssertEqual(latest?.speedMetersPerSecond, 20)
        let distance = try XCTUnwrap(recording.summary(endedAt: start.addingTimeInterval(120)).distanceMeters)
        // The existing two-second recorder cadence may omit the final sample;
        // this must still record the drive, within one sampling interval.
        XCTAssertGreaterThan(distance, 2_400 - 20 * DriveRecorder.minimumSampleInterval)
        XCTAssertLessThanOrEqual(distance, 2_400.1)
    }

    func testAnIsolatedJumpDoesNotPoisonTheNextGoodFixOrGoBackwardsInTime() throws {
        var filter = SessionLocationFilter()
        _ = filter.process(fix(0, at: 0, speed: 10, uncertainty: 0.2), now: start)
        XCTAssertNil(filter.process(fix(500, at: 2), now: start.addingTimeInterval(2)))
        let good = fix(40, at: 4, speed: 10, uncertainty: 0.2)
        XCTAssertEqual(try XCTUnwrap(filter.process(good, now: good.timestamp)).latitude, 57)
        XCTAssertNil(filter.process(good, now: good.timestamp))
        XCTAssertNil(filter.process(fix(20, at: 2), now: good.timestamp))
        let confirmed = fix(60, at: 6, speed: 10, uncertainty: 0.2)
        XCTAssertEqual(try XCTUnwrap(filter.process(confirmed, now: confirmed.timestamp)).latitude, confirmed.latitude)
    }

    func testAConfirmedRelocationAfterAGpsGapDoesNotPinTheOldParkedPosition() throws {
        var filter = SessionLocationFilter(), recording = recorder()
        _ = accept(fix(0, at: 0, speed: 0, uncertainty: 0.2), with: &filter, recording: &recording)
        // The phone really relocated while GPS was unavailable. One recovered
        // point is not enough, but two consistent fixes must restore the map.
        let first = try XCTUnwrap(accept(fix(200, at: 120, speed: 0, uncertainty: 0.2),
                                        with: &filter, recording: &recording))
        XCTAssertEqual(first.latitude, 57)
        let second = try XCTUnwrap(accept(fix(201, at: 122, speed: 0, uncertainty: 0.2),
                                         with: &filter, recording: &recording))
        XCTAssertEqual(second.latitude, fix(201, at: 122).latitude)
        XCTAssertEqual(second.speedMetersPerSecond, 0)
        XCTAssertEqual(recording.points.count, 2)
        XCTAssertGreaterThan(recording.points[1].timestampMilliseconds - recording.points[0].timestampMilliseconds, 120_000)
    }

    func testPlatformSpeedUncertaintyIsPreservedAndNormalized() throws {
        let location = CLLocation(coordinate: CLLocationCoordinate2D(latitude: 57, longitude: 12),
            altitude: 0, horizontalAccuracy: 5, verticalAccuracy: 5, course: 0, courseAccuracy: 1,
            speed: 4.4, speedAccuracy: 5, timestamp: start)
        XCTAssertEqual(CoreLocationProvider.fix(from: location)?.speedAccuracyMetersPerSecond, 5)
        for uncertainty in [-1.0, .nan, .infinity] {
            XCTAssertNil(fix(0, at: 0, speed: 4.4, uncertainty: uncertainty).speedAccuracyMetersPerSecond)
        }
    }
}
