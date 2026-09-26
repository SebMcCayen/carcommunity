import Foundation

protocol DriveHistoryRepository: Sendable {
    func listHistory(cursorRideId: String?, pageSize: Int) async throws -> DriveHistoryPage
    func fetchStats(monthStart: Date, monthEnd: Date) async throws -> DriveStatsSnapshot
    func deleteDrive(rideId: String) async throws
    func loadRoute(rideId: String) async -> DriveRouteReplayState
    func imageDownloadURL(for imagePath: String) async -> URL?
}

enum DriveRouteReplayState: Equatable, Sendable {
    case unavailable
    case ready([DriveRoutePoint])
}

struct DriveRoutePoint: Equatable, Sendable {
    let latitude: Double
    let longitude: Double
    let timestampMilliseconds: Int64
}

struct DriveKilometerMarker: Equatable, Sendable {
    let latitude: Double
    let longitude: Double
    let kilometer: Int
}

enum DriveRouteDistanceMarkers {
    static let maximumMarkers = 500
    private static let intervalSteps = [1, 2, 5, 10, 20, 50, 100, 200, 500, 1_000]

    static func markers(for points: [DriveRoutePoint]) -> [DriveKilometerMarker] {
        guard points.count >= 2 else { return [] }
        let lengths = zip(points, points.dropFirst()).map { segmentDistance($0, $1) }
        let total = lengths.reduce(0, +)
        guard total >= 1_000 else { return [] }
        let intervalKm = intervalSteps.first { total / (Double($0) * 1_000) <= Double(maximumMarkers) }
            ?? intervalSteps.last!
        let interval = Double(intervalKm) * 1_000
        var next = interval
        var cumulative = 0.0
        var result: [DriveKilometerMarker] = []
        for (index, length) in lengths.enumerated() where length > 0 && length.isFinite {
            let start = cumulative
            let end = cumulative + length
            while next <= end && result.count < maximumMarkers {
                let fraction = min(1, max(0, (next - start) / length))
                let first = points[index]
                let second = points[index + 1]
                result.append(DriveKilometerMarker(
                    latitude: first.latitude + (second.latitude - first.latitude) * fraction,
                    longitude: first.longitude + (second.longitude - first.longitude) * fraction,
                    kilometer: Int(next / 1_000)
                ))
                next += interval
            }
            cumulative = end
        }
        return result
    }

    private static func segmentDistance(_ first: DriveRoutePoint, _ second: DriveRoutePoint) -> Double {
        let elapsed = Double(second.timestampMilliseconds - first.timestampMilliseconds) / 1_000
        guard elapsed > 0 else { return 0 }
        let distance = LiveShareCadence.distanceMeters(
            lat1: first.latitude, lon1: first.longitude,
            lat2: second.latitude, lon2: second.longitude
        )
        return distance / elapsed <= DriveRecorder.maximumPlausibleSpeedMetersPerSecond ? distance : 0
    }
}
