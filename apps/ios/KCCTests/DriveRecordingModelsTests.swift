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

    func testIncrementalSummaryMatchesCanonicalDistanceAndRestoredRecorder() {
        var recorder = DriveRecorder(startedAt: start, context: context())
        let fixes = [
            fix(latitude: 57, longitude: 12, offset: 0),
            fix(latitude: 57.0001, longitude: 12, offset: 2),
            fix(latitude: 58, longitude: 12, offset: 4), // retained spike, excluded distance
            fix(latitude: 58.0001, longitude: 12, offset: 6),
        ]
        for fix in fixes { XCTAssertTrue(recorder.add(fix)) }
        let endedAt = start.addingTimeInterval(10)
        let expected = DriveRecorder.totalDistance(recorder.points)

        XCTAssertEqual(recorder.summary(endedAt: endedAt).distanceMeters!, expected, accuracy: 0.001)
        XCTAssertEqual(recorder.request(endedAt: endedAt, title: nil).points, recorder.points)

        let restored = DriveRecorder(
            startedAt: start,
            context: context(),
            restoring: recorder.points
        )
        XCTAssertEqual(restored.summary(endedAt: endedAt), recorder.summary(endedAt: endedAt))
    }

    func testLongRouteSummariesDoNoAdditionalSegmentWork() {
        let firstTimestamp: Int64 = 1_900_000_000_000
        let restoredPoints = (0..<10_000).map { index in
            RecordedDrivePoint(
                latitude: 57 + Double(index) * 0.000001,
                longitude: 12,
                timestampMilliseconds: firstTimestamp + Int64(index * 2_000)
            )
        }
        let lastTimestamp = restoredPoints.last!.timestampMilliseconds
        let segmentWork = SegmentWorkCounter()

        DriveRecorder.withSegmentEvaluationObserver({ start, end in
            guard start.timestampMilliseconds >= firstTimestamp,
                  end.timestampMilliseconds <= lastTimestamp
            else { return }
            segmentWork.increment()
        }) {
            let recorder = DriveRecorder(
                startedAt: start,
                context: context(),
                restoring: restoredPoints
            )
            XCTAssertEqual(segmentWork.value, 9_999)

            for offset in 0..<1_000 {
                _ = recorder.summary(endedAt: start.addingTimeInterval(TimeInterval(20_000 + offset)))
            }

            // Summary must consume the cached aggregate. If it regresses to
            // totalDistance(points), this counter increases by 9,999 per call.
            XCTAssertEqual(segmentWork.value, 9_999)
            XCTAssertEqual(recorder.points.count, 10_000)

            let canonicalDistance = DriveRecorder.totalDistance(restoredPoints)
            XCTAssertEqual(segmentWork.value, 19_998)
            XCTAssertEqual(
                recorder.summary(endedAt: start.addingTimeInterval(20_000)).distanceMeters!,
                canonicalDistance,
                accuracy: 0.001
            )
            XCTAssertEqual(segmentWork.value, 19_998)
        }
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

    func testRecordingDiagnosticsCountRejectionsIntentionalDropsAndReceiptGaps() throws {
        var recorder = DriveRecorder(startedAt: start, context: context())
        func event(_ decision: RecordingLocationDecision, _ uptime: Double) -> RecordingLocationEvent {
            RecordingLocationEvent(decision: decision, receivedUptime: uptime)
        }
        XCTAssertTrue(recorder.add(event(.accepted(fix(latitude: 57, longitude: 12, offset: 0)), 100)))
        XCTAssertFalse(recorder.add(event(.accepted(fix(latitude: 57, longitude: 12, offset: 1)), 101)))
        XCTAssertFalse(recorder.add(event(.accepted(fix(latitude: 57.00001, longitude: 12, offset: 1)), 102)))
        XCTAssertFalse(recorder.add(event(.rejected(.accuracy), 103)))
        XCTAssertFalse(recorder.add(event(.rejected(.speed), 104)))
        XCTAssertFalse(recorder.add(event(.rejected(.stale), 124)))
        XCTAssertFalse(recorder.add(event(.rejected(.invalid), 125)))
        XCTAssertFalse(recorder.add(event(.rejected(.time), 126)))
        XCTAssertTrue(recorder.add(event(.accepted(fix(latitude: 57.0001, longitude: 12, offset: 30)), 130)))
        let diagnostics = recorder.recordingDiagnostics
        XCTAssertEqual(diagnostics.receivedPoints, 9)
        XCTAssertEqual(diagnostics.acceptedPoints, 2)
        XCTAssertEqual(diagnostics.droppedStationary, 1)
        XCTAssertEqual(diagnostics.droppedThrottled, 1)
        XCTAssertEqual(diagnostics.rejectedAccuracy, 1)
        XCTAssertEqual(diagnostics.rejectedSpeed, 1)
        XCTAssertEqual(diagnostics.rejectedStale, 1)
        XCTAssertEqual(diagnostics.rejectedInvalid, 1)
        XCTAssertEqual(diagnostics.rejectedTime, 1)
        XCTAssertEqual(diagnostics.maxGapMs, 20_000)
        XCTAssertEqual(diagnostics.gapCount, 1)
        let payload = recorder.request(endedAt: start.addingTimeInterval(40), title: nil).payload
        let wire = try XCTUnwrap(payload["recordingDiagnostics"] as? [String: Any])
        XCTAssertEqual(wire["algorithmVersion"] as? String, "recording-quality-v1")
        XCTAssertFalse(wire.keys.contains("latitude"))
        XCTAssertFalse(wire.keys.contains("receivedUptime"))
    }

    func testRestoredDiagnosticsKeepCountersButResetMonotonicReceiptEpoch() {
        var recorder = DriveRecorder(startedAt: start, context: context())
        _ = recorder.add(RecordingLocationEvent(decision: .accepted(
            fix(latitude: 57, longitude: 12, offset: 0)), receivedUptime: 9_000))
        _ = recorder.add(RecordingLocationEvent(decision: .rejected(.accuracy), receivedUptime: 9_020))
        var restored = DriveRecorder(startedAt: start, context: context(), restoring: recorder.points,
            diagnostics: recorder.recordingDiagnostics)
        _ = restored.add(RecordingLocationEvent(decision: .rejected(.stale), receivedUptime: 100))
        XCTAssertEqual(restored.recordingDiagnostics.receivedPoints, 3)
        XCTAssertEqual(restored.recordingDiagnostics.acceptedPoints, 1)
        XCTAssertEqual(restored.recordingDiagnostics.rejectedAccuracy, 1)
        XCTAssertEqual(restored.recordingDiagnostics.rejectedStale, 1)
        XCTAssertEqual(restored.recordingDiagnostics.resumedCount, 1)
        XCTAssertEqual(restored.recordingDiagnostics.maxGapMs, 20_000)
        XCTAssertEqual(restored.recordingDiagnostics.gapCount, 1)
    }

    func testInvalidTimestampCannotTrapOrEnterTheSaveRequest() {
        var recorder = DriveRecorder(startedAt: start, context: context())
        for timestamp in [Date(timeIntervalSince1970: .infinity), Date(timeIntervalSince1970: -1)] {
            let invalid = LocationFix.of(latitude: 57, longitude: 12, timestamp: timestamp)!
            XCTAssertFalse(recorder.add(invalid))
        }
        XCTAssertEqual(recorder.recordingDiagnostics.rejectedInvalid, 2)
        XCTAssertTrue(recorder.points.isEmpty)
    }

    func testCapacityDropsAreCountedAndTelemetrySaturatesAtItsBound() {
        let points = (0..<DriveRecorder.maximumRoutePoints).map { index in
            RecordedDrivePoint(latitude: 57 + Double(index) * 0.000001, longitude: 12,
                timestampMilliseconds: 1_700_000_000_000 + Int64(index * 2_000))
        }
        var diagnostics = DriveRecordingDiagnostics()
        diagnostics.receivedPoints = DriveRecordingDiagnostics.maximumCount
        diagnostics.droppedCapacity = DriveRecordingDiagnostics.maximumCount
        var recorder = DriveRecorder(startedAt: start, context: context(), restoring: points,
            diagnostics: diagnostics)
        XCTAssertFalse(recorder.add(fix(latitude: 58, longitude: 12, offset: 100_000)))
        XCTAssertEqual(recorder.recordingDiagnostics.droppedCapacity, DriveRecordingDiagnostics.maximumCount)
        XCTAssertEqual(recorder.recordingDiagnostics.receivedPoints, DriveRecordingDiagnostics.maximumCount)
        XCTAssertEqual(recorder.points.count, DriveRecorder.maximumRoutePoints)
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

private final class SegmentWorkCounter: @unchecked Sendable {
    private let lock = NSLock()
    private var count = 0

    var value: Int {
        lock.lock()
        defer { lock.unlock() }
        return count
    }

    func increment() {
        lock.lock()
        count += 1
        lock.unlock()
    }
}
