import XCTest

@testable import KCC

final class DriveRecordingJournalTests: XCTestCase {
    func testJournalRestoresMatchingSessionAndIgnoresPartialLastLine() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let url = directory.appendingPathComponent("active.routejournal")
        let journal = FileDriveRecordingJournal(fileURL: url)
        let start = Date(timeIntervalSince1970: 1_700_000_000)
        journal.begin(context: context("session-1"), startedAt: start)
        journal.append(
            RecordedDrivePoint(latitude: 57, longitude: 12, timestampMilliseconds: 1_700_000_001_000)
        )
        let enriched = DriveRecordingContext(
            sourceSessionId: "session-1",
            vehicleId: "vehicle-2",
            carImagePath: "vehicleImages/u/vehicle-2/cover",
            convoyMembers: [ConvoyDriveMember(uid: "other", displayName: "Ada", avatarPath: nil)]
        )
        journal.updateContext(enriched)
        let stoppedAt = start.addingTimeInterval(60)
        journal.markStopped(at: stoppedAt)
        let handle = try FileHandle(forWritingTo: url)
        try handle.seekToEnd()
        try handle.write(contentsOf: Data("broken,".utf8))
        try handle.close()

        XCTAssertNil(journal.restore(sessionId: "different"))
        let restored = try XCTUnwrap(journal.restore(sessionId: "session-1"))
        XCTAssertEqual(restored.startedAt, start)
        XCTAssertEqual(restored.context, enriched)
        XCTAssertEqual(restored.stoppedAt, stoppedAt)
        XCTAssertEqual(restored.points, [
            RecordedDrivePoint(
                latitude: 57,
                longitude: 12,
                timestampMilliseconds: 1_700_000_001_000
            )
        ])
    }

    func testClearRemovesJournal() throws {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("\(UUID().uuidString).routejournal")
        let journal = FileDriveRecordingJournal(fileURL: url)
        journal.begin(context: context("session"), startedAt: Date())
        XCTAssertTrue(FileManager.default.fileExists(atPath: url.path))
        journal.clear()
        XCTAssertFalse(FileManager.default.fileExists(atPath: url.path))
    }

    private func context(_ sessionId: String) -> DriveRecordingContext {
        DriveRecordingContext(
            sourceSessionId: sessionId,
            vehicleId: "vehicle",
            carImagePath: "vehicleImages/u/vehicle/cover",
            convoyMembers: [ConvoyDriveMember(uid: "other", displayName: "Ada", avatarPath: nil)]
        )
    }
}
