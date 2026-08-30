import XCTest

@testable import KCC

/// Unit tests for the pure saved-drives domain: tolerant document decoding
/// (contracts/schemas/saved-drives.schema.json `ride` — absent and null read
/// identically, malformed convoy entries degrade instead of crashing), the
/// newest-first list sort, and the locale-stable stat formatters — mirroring
/// Android's SavedDrive/DriveFormatters coverage.
final class DrivesModelTests: XCTestCase {

    /// The test-side date coercion for ``SavedDrive/fromMap`` (the
    /// repository passes the Firestore `Timestamp` conversion instead).
    private let asDate: (Any?) -> Date? = { $0 as? Date }

    private func drive(
        id: String = "ride-1",
        title: String? = nil,
        distanceMeters: Double? = nil,
        durationSeconds: Int = 60,
        createdAt: Date? = nil,
        maxSpeedMetersPerSecond: Double? = nil,
        carImagePath: String? = nil,
        convoyMembers: [ConvoyDriveMember] = []
    ) -> SavedDrive {
        SavedDrive(
            id: id,
            title: title,
            distanceMeters: distanceMeters,
            durationSeconds: durationSeconds,
            averageSpeedMetersPerSecond: nil,
            startedAt: nil,
            endedAt: nil,
            createdAt: createdAt,
            maxSpeedMetersPerSecond: maxSpeedMetersPerSecond,
            carImagePath: carImagePath,
            convoyMembers: convoyMembers
        )
    }

    // MARK: - document decoding

    func testFromMapDecodesAFullDocument() {
        let started = Date(timeIntervalSince1970: 1_000)
        let ended = Date(timeIntervalSince1970: 2_000)
        let created = Date(timeIntervalSince1970: 3_000)
        let map: [String: Any] = [
            "userId": "uid-1",
            "title": "Evening loop",
            "distanceMeters": 12_345.6,
            "durationSeconds": 1_845,
            "averageSpeedMetersPerSecond": 12.4,
            "maxSpeedMetersPerSecond": 24.7,
            "startedAt": started,
            "endedAt": ended,
            "createdAt": created,
            "carImagePath": "vehicleImages/uid-1/car-1/cover.jpg",
            "convoyMembers": [
                ["uid": "u2", "displayName": "Alex", "avatarPath": "avatars/u2/a.jpg"]
            ],
        ]

        let drive = SavedDrive.fromMap(id: "ride-9", map: map, date: asDate)

        XCTAssertEqual(drive?.id, "ride-9")
        XCTAssertEqual(drive?.title, "Evening loop")
        XCTAssertEqual(drive?.distanceMeters, 12_345.6)
        XCTAssertEqual(drive?.durationSeconds, 1_845)
        XCTAssertEqual(drive?.averageSpeedMetersPerSecond, 12.4)
        XCTAssertEqual(drive?.maxSpeedMetersPerSecond, 24.7)
        XCTAssertEqual(drive?.startedAt, started)
        XCTAssertEqual(drive?.endedAt, ended)
        XCTAssertEqual(drive?.createdAt, created)
        XCTAssertEqual(drive?.carImagePath, "vehicleImages/uid-1/car-1/cover.jpg")
        XCTAssertEqual(
            drive?.convoyMembers,
            [
                ConvoyDriveMember(
                    uid: "u2", displayName: "Alex", avatarPath: "avatars/u2/a.jpg"
                )
            ]
        )
    }

    func testFromMapDropsADocumentWithoutDuration() {
        // A ride with no durationSeconds cannot render a card — dropped, the
        // same posture as Android's toSavedDrive.
        XCTAssertNil(SavedDrive.fromMap(id: "r", map: ["title": "x"], date: asDate))
    }

    func testFromMapReadsAbsentOptionalFieldsAsNil() {
        // Pre-2026-07 drives simply LACK maxSpeed/carImagePath/convoyMembers
        // (no backfill); summary-only saves store null distance. Both must
        // decode as "unknown", never 0 or a crash.
        let drive = SavedDrive.fromMap(id: "r", map: ["durationSeconds": 30], date: asDate)

        XCTAssertEqual(drive?.durationSeconds, 30)
        XCTAssertNil(drive?.title)
        XCTAssertNil(drive?.distanceMeters)
        XCTAssertNil(drive?.averageSpeedMetersPerSecond)
        XCTAssertNil(drive?.maxSpeedMetersPerSecond)
        XCTAssertNil(drive?.startedAt)
        XCTAssertNil(drive?.endedAt)
        XCTAssertNil(drive?.createdAt)
        XCTAssertNil(drive?.carImagePath)
        XCTAssertEqual(drive?.convoyMembers, [])
    }

    func testFromMapReadsExplicitNullsAsNil() {
        let map: [String: Any] = [
            "durationSeconds": 30,
            "title": NSNull(),
            "distanceMeters": NSNull(),
            "maxSpeedMetersPerSecond": NSNull(),
            "carImagePath": NSNull(),
            "convoyMembers": NSNull(),
        ]

        let drive = SavedDrive.fromMap(id: "r", map: map, date: asDate)

        XCTAssertNil(drive?.title)
        XCTAssertNil(drive?.distanceMeters)
        XCTAssertNil(drive?.maxSpeedMetersPerSecond)
        XCTAssertNil(drive?.carImagePath)
        XCTAssertEqual(drive?.convoyMembers, [])
    }

    // MARK: - convoy roster parsing

    func testParseDropsMalformedEntriesAndKeepsTheRest() {
        let raw: [Any] = [
            "not-a-map",
            ["displayName": "No uid"],
            ["uid": "   "],
            ["uid": "u1", "displayName": "Alex"],
            ["uid": "u2", "displayName": "", "avatarPath": "  "],
        ]

        let members = ConvoyDriveMembers.parse(raw)

        XCTAssertEqual(
            members,
            [
                ConvoyDriveMember(uid: "u1", displayName: "Alex", avatarPath: nil),
                // Blank name/avatar normalize to nil (the row's fallback),
                // never the empty string.
                ConvoyDriveMember(uid: "u2", displayName: nil, avatarPath: nil),
            ]
        )
    }

    func testParseDeduplicatesByUidKeepingTheFirst() {
        let raw: [Any] = [
            ["uid": "u1", "displayName": "First"],
            ["uid": "u1", "displayName": "Duplicate"],
        ]

        let members = ConvoyDriveMembers.parse(raw)

        XCTAssertEqual(members.count, 1)
        XCTAssertEqual(members.first?.displayName, "First")
    }

    func testParseCapsAtTheBackendMaximum() {
        let raw: [Any] = (0..<40).map { ["uid": "u\($0)"] }
        XCTAssertEqual(ConvoyDriveMembers.parse(raw).count, ConvoyDriveMembers.maxMembers)
    }

    func testJoinedNamesFallsBackToTheNeutralLabel() {
        let members = [
            ConvoyDriveMember(uid: "u1", displayName: "Alex", avatarPath: nil),
            ConvoyDriveMember(uid: "u2", displayName: nil, avatarPath: nil),
        ]
        XCTAssertEqual(
            ConvoyDriveMembers.joinedNames(members, unknownLabel: "Member"),
            "Alex, Member"
        )
        XCTAssertEqual(ConvoyDriveMembers.joinedNames([], unknownLabel: "Member"), "")
    }

    // MARK: - list sort

    func testSortedForListIsNewestFirstWithUndatedLast() {
        let older = drive(id: "older", createdAt: Date(timeIntervalSince1970: 1_000))
        let newer = drive(id: "newer", createdAt: Date(timeIntervalSince1970: 2_000))
        let undatedA = drive(id: "undated-a")
        let undatedB = drive(id: "undated-b")

        let sorted = SavedDrives.sortedForList([undatedA, older, newer, undatedB])

        // Undated drives keep their original relative order at the end.
        XCTAssertEqual(sorted.map(\.id), ["newer", "older", "undated-a", "undated-b"])
    }

    func testSortedForListIsStableAmongEqualDates() {
        let date = Date(timeIntervalSince1970: 1_000)
        let first = drive(id: "first", createdAt: date)
        let second = drive(id: "second", createdAt: date)

        XCTAssertEqual(
            SavedDrives.sortedForList([first, second]).map(\.id),
            ["first", "second"]
        )
    }

    // MARK: - formatters

    func testFormatDistance() {
        XCTAssertEqual(DriveFormatters.formatDistance(nil), "—")
        XCTAssertEqual(DriveFormatters.formatDistance(-1), "—")
        XCTAssertEqual(DriveFormatters.formatDistance(.infinity), "—")
        XCTAssertEqual(DriveFormatters.formatDistance(820), "820 m")
        XCTAssertEqual(DriveFormatters.formatDistance(999.4), "999 m")
        // A sub-kilometre value that rounds up to 1000 renders as km, never
        // the contradictory "1000 m".
        XCTAssertEqual(DriveFormatters.formatDistance(999.6), "1.0 km")
        XCTAssertEqual(DriveFormatters.formatDistance(12_345), "12.3 km")
        // Locale-stable: always the dot decimal separator, like Android's
        // Locale.ROOT format.
        XCTAssertEqual(DriveFormatters.formatDistance(1_000), "1.0 km")
    }

    func testFormatDuration() {
        XCTAssertEqual(DriveFormatters.formatDuration(0), "0 min")
        XCTAssertEqual(DriveFormatters.formatDuration(-5), "0 min")
        XCTAssertEqual(DriveFormatters.formatDuration(45), "45 s")
        XCTAssertEqual(DriveFormatters.formatDuration(300), "5 min")
        XCTAssertEqual(DriveFormatters.formatDuration(3_900), "1 h 5 min")
    }

    func testFormatSpeed() {
        // Nil is "unknown", NOT zero — the dash, never "0 km/h".
        XCTAssertEqual(DriveFormatters.formatSpeed(nil), "—")
        XCTAssertEqual(DriveFormatters.formatSpeed(-1), "—")
        XCTAssertEqual(DriveFormatters.formatSpeed(.nan), "—")
        XCTAssertEqual(DriveFormatters.formatSpeed(12.5), "45 km/h")
        XCTAssertEqual(DriveFormatters.formatSpeed(0), "0 km/h")
        // A corrupted-but-finite value must not overflow the Int conversion
        // and trap — it falls back to the dash instead.
        XCTAssertEqual(DriveFormatters.formatSpeed(1e300), "—")
    }
}
