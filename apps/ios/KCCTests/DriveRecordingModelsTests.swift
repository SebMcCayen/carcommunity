import XCTest

@testable import KCC

final class DriveRecordingModelsTests: XCTestCase {
    private let start = Date(timeIntervalSince1970: 1_700_000_000)

    func testRecorderThrottlesOrdersCapsAndFiltersGpsSpikeFromPreview() {
        var recorder = DriveRecorder(startedAt: start, context: context())
        XCTAssertTrue(recorder.add(fix(latitude: 57, longitude: 12, offset: 0)))
        XCTAssertFalse(recorder.add(fix(latitude: 57.0001, longitude: 12, offset: 1)))
        XCTAssertTrue(recorder.add(fix(latitude: 57.0002, longitude: 12, offset: 2)))
        // Several kilometres in two seconds is a GPS jump. It is retained in
        // the route, while the same backend >200 km/h rule excludes the segment.
        XCTAssertTrue(recorder.add(fix(latitude: 58, longitude: 12, offset: 4)))
        XCTAssertEqual(recorder.points.count, 3)
        XCTAssertLessThan(recorder.summary(endedAt: start.addingTimeInterval(4)).distanceMeters!, 50)
    }

    func testRequestUsesLastFixClockAndCarriesVehicleConvoyAndIdempotencyContext() {
        let member = ConvoyDriveMember(uid: " other ", displayName: " Ada ", avatarPath: nil)
        var recorder = DriveRecorder(
            startedAt: start,
            context: DriveRecordingContext(
                sourceSessionId: "session-1",
                vehicleId: "vehicle-1",
                carImagePath: "vehicleImages/u/vehicle-1/cover",
                convoyMembers: [member, member]
            )
        )
        XCTAssertTrue(recorder.add(fix(latitude: 57, longitude: 12, offset: 10)))
        let request = recorder.request(endedAt: start.addingTimeInterval(5), title: "  Evening  ")
        let payload = request.payload
        XCTAssertEqual(payload["sourceSessionId"] as? String, "session-1")
        XCTAssertEqual(payload["vehicleId"] as? String, "vehicle-1")
        XCTAssertEqual(payload["carImagePath"] as? String, "vehicleImages/u/vehicle-1/cover")
        XCTAssertEqual(payload["title"] as? String, "Evening")
        XCTAssertEqual((payload["routePoints"] as? [[String: Any]])?.count, 1)
        let members = payload["convoyMembers"] as? [[String: String]]
        XCTAssertEqual(members, [["uid": "other", "displayName": "Ada"]])
        XCTAssertEqual(request.endedAt, start.addingTimeInterval(10))
    }

    func testRawRouteCodecMatchesCanonicalKnownFixture() {
        let point = RecordedDrivePoint(
            latitude: 57.4,
            longitude: 12,
            timestampMilliseconds: 1_700_000_000_000
        )
        let bytes = [UInt8](DriveRouteCodec.encode([point]))
        XCTAssertEqual(Array(bytes.prefix(7)), [0x43, 0x43, 0x52, 0x42, 0x01, 0x00, 0x01])
        // Pin the full Android/backend-compatible encoding, including deltas.
        XCTAssertEqual(bytes, [
            67, 67, 82, 66, 1, 0, 1, 192, 215, 188, 5, 128, 190, 146, 1,
            128, 208, 149, 255, 188, 49,
        ])
    }

    func testTitleCapMatchesBackendUtf16LengthWithoutSplittingEmoji() {
        let title = String(repeating: "🚗", count: 101)
        let normalized = DriveSaveRequest.normalizedTitle(title)
        XCTAssertEqual(normalized?.count, 100)
        XCTAssertEqual(normalized?.utf16.count, DriveSaveRequest.maximumTitleLength)
    }

    private func context() -> DriveRecordingContext {
        DriveRecordingContext(
            sourceSessionId: "session",
            vehicleId: nil,
            carImagePath: nil,
            convoyMembers: []
        )
    }

    private func fix(latitude: Double, longitude: Double, offset: TimeInterval) -> LocationFix {
        LocationFix.of(
            latitude: latitude,
            longitude: longitude,
            timestamp: start.addingTimeInterval(offset)
        )!
    }
}
