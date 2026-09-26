import Foundation

enum DriveSubscriptionTier: String, Equatable, Sendable {
    case community, plus, supporter, unknown

    init(wireValue: Any?) {
        self = (wireValue as? String).flatMap(Self.init(rawValue:)) ?? .unknown
    }
}

struct DriveHistoryPage: Equatable, Sendable {
    let tier: DriveSubscriptionTier
    let drives: [SavedDrive]
    let hasMore: Bool
    let nextCursorRideId: String?
    let hiddenDriveCount: Int

    static func fromWire(_ raw: Any?) throws -> DriveHistoryPage {
        guard let map = raw as? [String: Any] else { throw DriveHistoryError.invalidResponse }
        let drives = (map["drives"] as? [Any] ?? []).compactMap(Self.drive(from:))
        return DriveHistoryPage(
            tier: DriveSubscriptionTier(wireValue: map["tier"]),
            drives: drives,
            hasMore: map["hasMore"] as? Bool ?? false,
            nextCursorRideId: nonBlank(map["nextCursorRideId"] as? String),
            hiddenDriveCount: max(0, (map["hiddenDriveCount"] as? NSNumber)?.intValue ?? 0)
        )
    }

    private static func drive(from raw: Any) -> SavedDrive? {
        guard let map = raw as? [String: Any],
              let id = nonBlank(map["rideId"] as? String),
              let duration = (map["durationSeconds"] as? NSNumber)?.intValue,
              duration >= 0 else { return nil }
        func date(_ key: String) -> Date? {
            guard let millis = (map[key] as? NSNumber)?.doubleValue, millis.isFinite else { return nil }
            return Date(timeIntervalSince1970: millis / 1_000)
        }
        func nonnegative(_ key: String) -> Double? {
            guard let value = (map[key] as? NSNumber)?.doubleValue,
                  value.isFinite, value >= 0 else { return nil }
            return value
        }
        return SavedDrive(
            id: id,
            title: nonBlank(map["title"] as? String),
            distanceMeters: nonnegative("distanceMeters"),
            durationSeconds: duration,
            averageSpeedMetersPerSecond: nonnegative("averageSpeedMetersPerSecond"),
            startedAt: date("startedAtMillis"),
            endedAt: date("endedAtMillis"),
            createdAt: date("createdAtMillis"),
            maxSpeedMetersPerSecond: nonnegative("maxSpeedMetersPerSecond"),
            routeThumbnail: nonBlank(map["routeThumbnail"] as? String),
            carImagePath: nonBlank(map["carImagePath"] as? String),
            convoyMembers: ConvoyDriveMembers.parse(map["convoyMembers"])
        )
    }

    private static func nonBlank(_ value: String?) -> String? {
        guard let value, !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return nil
        }
        return value
    }
}

struct DriveStatsSnapshot: Equatable, Sendable {
    let tier: DriveSubscriptionTier
    let totalDrives: Int
    let totalDistanceMeters: Double
    let totalDurationSeconds: Int
    let longestDriveMeters: Double
    let averageDriveMeters: Double
    let fastestAverageSpeedMetersPerSecond: Double?
    let highestMaxSpeedMetersPerSecond: Double?
    let thisMonthDrives: Int
    let thisMonthDistanceMeters: Double

    static func fromWire(_ raw: Any?) throws -> DriveStatsSnapshot {
        guard let map = raw as? [String: Any] else { throw DriveHistoryError.invalidResponse }
        func number(_ key: String) -> Double {
            guard let value = (map[key] as? NSNumber)?.doubleValue,
                  value.isFinite, value >= 0 else { return 0 }
            return value
        }
        func positive(_ key: String) -> Double? {
            let value = number(key)
            return value > 0 ? value : nil
        }
        return DriveStatsSnapshot(
            tier: DriveSubscriptionTier(wireValue: map["tier"]),
            totalDrives: max(0, (map["totalDrives"] as? NSNumber)?.intValue ?? 0),
            totalDistanceMeters: number("totalDistanceMeters"),
            totalDurationSeconds: max(0, (map["totalDurationSeconds"] as? NSNumber)?.intValue ?? 0),
            longestDriveMeters: number("longestDriveMeters"),
            averageDriveMeters: number("averageDriveMeters"),
            fastestAverageSpeedMetersPerSecond: positive("fastestAverageSpeedMps"),
            highestMaxSpeedMetersPerSecond: positive("highestMaxSpeedMps"),
            thisMonthDrives: max(0, (map["thisMonthDrives"] as? NSNumber)?.intValue ?? 0),
            thisMonthDistanceMeters: number("thisMonthDistanceMeters")
        )
    }
}

enum DriveDateRange: Sendable { case all, thisWeek, thisMonth }
enum DriveDistanceBand: Sendable { case all, under10, from10To50, over50 }
enum DriveSort: Sendable { case newest, longest, fastestAverage }

struct DriveFilterCriteria: Equatable, Sendable {
    var query = ""
    var dateRange: DriveDateRange = .all
    var distanceBand: DriveDistanceBand = .all
    var sort: DriveSort = .newest

    var activeFilterCount: Int {
        (query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? 0 : 1)
            + (dateRange == .all ? 0 : 1)
            + (distanceBand == .all ? 0 : 1)
    }
}

enum DriveFilters {
    static func apply(
        _ drives: [SavedDrive], criteria: DriveFilterCriteria, now: Date = .now,
        calendar: Calendar = .current
    ) -> [SavedDrive] {
        let query = criteria.query.trimmingCharacters(in: .whitespacesAndNewlines)
        let week = calendar.dateInterval(of: .weekOfYear, for: now)?.start ?? .distantPast
        let month = calendar.dateInterval(of: .month, for: now)?.start ?? .distantPast
        let filtered = drives.filter { drive in
            let queryMatches = query.isEmpty || (drive.title?.localizedCaseInsensitiveContains(query) == true)
            let date = drive.startedAt ?? drive.createdAt
            let dateMatches: Bool = switch criteria.dateRange {
            case .all: true
            case .thisWeek: date.map { $0 >= week } ?? false
            case .thisMonth: date.map { $0 >= month } ?? false
            }
            let distanceMatches: Bool = switch (criteria.distanceBand, drive.distanceMeters) {
            case (.all, _): true
            case (_, nil): false
            case (.under10, let distance?): distance >= 0 && distance < 10_000
            case (.from10To50, let distance?): distance >= 10_000 && distance < 50_000
            case (.over50, let distance?): distance >= 50_000
            }
            return queryMatches && dateMatches && distanceMatches
        }
        return filtered.enumerated().sorted { lhs, rhs in
            let left = lhs.element
            let right = rhs.element
            let order: ComparisonResult = switch criteria.sort {
            case .newest: compare(left.createdAt, right.createdAt)
            case .longest: compare(left.distanceMeters, right.distanceMeters)
            case .fastestAverage:
                compare(
                    DriveFormatters.effectiveAverageSpeed(
                        stored: left.averageSpeedMetersPerSecond,
                        distanceMeters: left.distanceMeters,
                        durationSeconds: left.durationSeconds
                    ),
                    DriveFormatters.effectiveAverageSpeed(
                        stored: right.averageSpeedMetersPerSecond,
                        distanceMeters: right.distanceMeters,
                        durationSeconds: right.durationSeconds
                    )
                )
            }
            return order == .orderedDescending || (order == .orderedSame && lhs.offset < rhs.offset)
        }.map(\.element)
    }

    private static func compare<T: Comparable>(_ left: T?, _ right: T?) -> ComparisonResult {
        switch (left, right) {
        case let (left?, right?): left == right ? .orderedSame : (left > right ? .orderedDescending : .orderedAscending)
        case (.some, nil): .orderedDescending
        case (nil, .some): .orderedAscending
        case (nil, nil): .orderedSame
        }
    }
}

enum DriveHistoryError: Error, Equatable, Sendable {
    case unavailable
    case invalidResponse
    case callable(KccFunctionsErrorCode)
}
