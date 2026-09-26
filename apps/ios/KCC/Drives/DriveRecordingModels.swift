import Foundation

/// One route point accepted by the drive recorder. The wire shape mirrors
/// `saved-drives.schema.json#/$defs/routePoint` exactly.
struct RecordedDrivePoint: Equatable, Sendable {
    let latitude: Double
    let longitude: Double
    let timestampMilliseconds: Int64

    init?(fix: LocationFix) {
        guard fix.latitude.isFinite, (-90...90).contains(fix.latitude),
              fix.longitude.isFinite, (-180...180).contains(fix.longitude)
        else { return nil }
        latitude = fix.latitude
        longitude = fix.longitude
        timestampMilliseconds = Int64((fix.timestamp.timeIntervalSince1970 * 1_000).rounded())
    }

    init(latitude: Double, longitude: Double, timestampMilliseconds: Int64) {
        self.latitude = latitude
        self.longitude = longitude
        self.timestampMilliseconds = timestampMilliseconds
    }

    var wireValue: [String: Any] {
        [
            "latitude": latitude,
            "longitude": longitude,
            "timestampMs": timestampMilliseconds,
        ]
    }
}

struct DriveRecordingContext: Equatable, Sendable {
    let sourceSessionId: String
    let vehicleId: String?
    let carImagePath: String?
    let convoyMembers: [ConvoyDriveMember]
    let expiresAt: Date?

    init(
        sourceSessionId: String,
        vehicleId: String?,
        carImagePath: String?,
        convoyMembers: [ConvoyDriveMember],
        expiresAt: Date? = nil
    ) {
        self.sourceSessionId = sourceSessionId
        self.vehicleId = vehicleId
        self.carImagePath = carImagePath
        self.convoyMembers = convoyMembers
        self.expiresAt = expiresAt
    }
}

struct DriveSaveRequest: Equatable, Sendable {
    static let maximumTitleLength = 200

    let startedAt: Date
    let endedAt: Date
    let points: [RecordedDrivePoint]
    let context: DriveRecordingContext
    let title: String?

    var payload: [String: Any] {
        var result: [String: Any] = [
            "startedAt": startedAt.formatted(.iso8601),
            "endedAt": endedAt.formatted(.iso8601),
            "sourceSessionId": context.sourceSessionId,
        ]
        if !points.isEmpty { result["routePoints"] = points.map(\.wireValue) }
        if let title = Self.normalizedTitle(title) { result["title"] = title }
        if let vehicleId = Self.nonBlank(context.vehicleId) { result["vehicleId"] = vehicleId }
        if let carImagePath = Self.nonBlank(context.carImagePath) {
            result["carImagePath"] = carImagePath
        }
        let members = ConvoyDriveMembers.requestList(context.convoyMembers)
        if !members.isEmpty { result["convoyMembers"] = members }
        return result
    }

    static func normalizedTitle(_ value: String?) -> String? {
        guard let value = nonBlank(value) else { return nil }
        var result = ""
        var utf16Count = 0
        for character in value {
            let width = String(character).utf16.count
            guard utf16Count + width <= maximumTitleLength else { break }
            result.append(character)
            utf16Count += width
        }
        return result.isEmpty ? nil : result
    }

    private static func nonBlank(_ value: String?) -> String? {
        guard let value = value?.trimmingCharacters(in: .whitespacesAndNewlines),
              !value.isEmpty else { return nil }
        return value
    }
}

struct DriveSaveResult: Equatable, Sendable {
    let rideId: String
    let routePath: String?
    let alreadySaved: Bool
}

struct DriveRecordingSummary: Equatable, Sendable {
    let pointCount: Int
    let durationSeconds: Int
    let distanceMeters: Double?
    let averageSpeedMetersPerSecond: Double?
}

/// A bounded in-memory recorder. Exact coordinates stay private in memory/the
/// protected journal until live-session teardown auto-saves them to owner-only
/// History; a definitive refusal releases them when the user closes the prompt.
struct DriveRecorder: Sendable {
    static let maximumRoutePoints = 20_000
    static let minimumSampleInterval: TimeInterval = 2
    static let maximumPlausibleSpeedMetersPerSecond = 55.6

    let startedAt: Date
    private(set) var context: DriveRecordingContext
    private(set) var points: [RecordedDrivePoint] = []

    init(startedAt: Date, context: DriveRecordingContext) {
        self.startedAt = startedAt
        self.context = context
        points.reserveCapacity(1_024)
    }

    init(startedAt: Date, context: DriveRecordingContext, restoring restored: [RecordedDrivePoint]) {
        self.init(startedAt: startedAt, context: context)
        for point in restored.prefix(Self.maximumRoutePoints) {
            _ = add(point)
        }
    }

    mutating func enrichContext(_ update: DriveRecordingContext) {
        guard update.sourceSessionId == context.sourceSessionId else { return }
        context = DriveRecordingContext(
            sourceSessionId: context.sourceSessionId,
            vehicleId: update.vehicleId ?? context.vehicleId,
            carImagePath: update.carImagePath ?? context.carImagePath,
            convoyMembers: update.convoyMembers.isEmpty ? context.convoyMembers : update.convoyMembers,
            expiresAt: update.expiresAt ?? context.expiresAt
        )
    }

    @discardableResult
    mutating func add(_ fix: LocationFix) -> Bool {
        guard points.count < Self.maximumRoutePoints,
              let point = RecordedDrivePoint(fix: fix) else { return false }
        return add(point)
    }

    @discardableResult
    private mutating func add(_ point: RecordedDrivePoint) -> Bool {
        guard points.count < Self.maximumRoutePoints else { return false }
        if let previous = points.last {
            let delta = Double(point.timestampMilliseconds - previous.timestampMilliseconds) / 1_000
            guard delta >= Self.minimumSampleInterval else { return false }
        }
        points.append(point)
        return true
    }

    func summary(endedAt: Date) -> DriveRecordingSummary {
        let duration = max(0, Int(endedAt.timeIntervalSince(startedAt).rounded()))
        let distance = Self.totalDistance(points)
        return DriveRecordingSummary(
            pointCount: points.count,
            durationSeconds: duration,
            distanceMeters: points.count >= 2 ? distance : nil,
            averageSpeedMetersPerSecond: duration > 0 && points.count >= 2
                ? distance / Double(duration)
                : nil
        )
    }

    func request(endedAt: Date, title: String?) -> DriveSaveRequest {
        let lastFix = points.last.map {
            Date(timeIntervalSince1970: Double($0.timestampMilliseconds) / 1_000)
        }
        let minimumEnd = startedAt.addingTimeInterval(0.001)
        let effectiveEnd = max(lastFix ?? endedAt, minimumEnd)
        return DriveSaveRequest(
            startedAt: startedAt,
            endedAt: effectiveEnd,
            points: points,
            context: context,
            title: title
        )
    }

    static func totalDistance(_ points: [RecordedDrivePoint]) -> Double {
        guard points.count >= 2 else { return 0 }
        return zip(points, points.dropFirst()).reduce(0) { total, pair in
            total + segmentDistance(from: pair.0, to: pair.1)
        }
    }

    static func segmentDistance(from start: RecordedDrivePoint, to end: RecordedDrivePoint) -> Double {
        let delta = Double(end.timestampMilliseconds - start.timestampMilliseconds) / 1_000
        guard delta > 0 else { return 0 }
        let distance = LiveShareCadence.distanceMeters(
            lat1: start.latitude,
            lon1: start.longitude,
            lat2: end.latitude,
            lon2: end.longitude
        )
        return distance / delta <= maximumPlausibleSpeedMetersPerSecond ? distance : 0
    }
}

extension ConvoyDriveMembers {
    static func requestList(_ members: [ConvoyDriveMember]) -> [[String: String]] {
        var seen = Set<String>()
        return members.compactMap { member in
            let uid = member.uid.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !uid.isEmpty, seen.insert(uid).inserted else { return nil }
            var result = ["uid": uid]
            if let name = member.displayName?.trimmingCharacters(in: .whitespacesAndNewlines),
               !name.isEmpty { result["displayName"] = name }
            if let path = member.avatarPath?.trimmingCharacters(in: .whitespacesAndNewlines),
               !path.isEmpty { result["avatarPath"] = path }
            return result
        }
        .prefix(maxMembers)
        .map { $0 }
    }
}
