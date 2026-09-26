import Foundation

struct ConvoyFollowMeState: Equatable, Sendable {
    let leaderUid: String?
    let polyline: String?
    let updatedAt: Date?
}

/// Firebase-free policy and CCRB v1 codec for the shared convoy leader trail.
enum FollowMeTrail {
    static let windowMeters = 15_000.0
    static let staleAfter: TimeInterval = 90
    static let staleRecheck: Duration = .seconds(30)
    static let writeThrottle: TimeInterval = 4

    static func isSelfLeading(leaderUid: String?, selfUid: String?) -> Bool {
        guard let leaderUid, let selfUid else { return false }
        return leaderUid == selfUid
    }

    static func shouldDraw(
        leaderUid: String?,
        selfUid: String?,
        leaderIsMember: Bool,
        lastFreshAt: Date?,
        now: Date,
        staleAfter: TimeInterval = staleAfter
    ) -> Bool {
        guard let leaderUid,
              leaderUid != selfUid,
              leaderIsMember,
              let lastFreshAt
        else { return false }
        return now.timeIntervalSince(lastFreshAt) < staleAfter
    }

    /// Encodes oldest-to-newest geometry using the Android/backend CCRB v1 wire format.
    static func encode(_ points: [MapPoint]) -> String {
        guard !points.isEmpty else { return "" }
        var bytes = Data([0x43, 0x43, 0x52, 0x42, 0x01, 0x00])
        appendUVarint(UInt64(points.count), to: &bytes)
        var previousLatitude = 0
        var previousLongitude = 0
        var previousTimestamp = 0
        for (index, point) in points.enumerated() {
            let latitude = fixedCoordinate(point.latitude)
            let longitude = fixedCoordinate(point.longitude)
            appendUVarint(zigZag(latitude - previousLatitude), to: &bytes)
            appendUVarint(zigZag(longitude - previousLongitude), to: &bytes)
            appendUVarint(UInt64(index - previousTimestamp), to: &bytes)
            previousLatitude = latitude
            previousLongitude = longitude
            previousTimestamp = index
        }
        return bytes.base64EncodedString()
    }

    /// Decodes a base64 CCRB v1 trail. Corrupt or implausible input fails closed.
    static func decode(_ polyline: String?) -> [MapPoint] {
        guard let polyline, !polyline.isEmpty,
              let bytes = Data(base64Encoded: polyline), bytes.count >= 6,
              Array(bytes.prefix(5)) == [0x43, 0x43, 0x52, 0x42, 0x01]
        else { return [] }

        var cursor = 6
        guard let countValue = readUVarint(bytes, cursor: &cursor),
              countValue <= 1_000_000
        else { return [] }
        let count = Int(countValue)
        var latitude = 0
        var longitude = 0
        var timestamp: UInt64 = 0
        var points: [MapPoint] = []
        points.reserveCapacity(min(count, 1_024))
        for _ in 0..<count {
            guard let latitudeDelta = readUVarint(bytes, cursor: &cursor),
                  let longitudeDelta = readUVarint(bytes, cursor: &cursor),
                  let timestampDelta = readUVarint(bytes, cursor: &cursor),
                  latitudeDelta <= UInt64(UInt32.max),
                  longitudeDelta <= UInt64(UInt32.max),
                  timestampDelta <= UInt64.max - timestamp
            else { return [] }
            latitude += unZigZag(latitudeDelta)
            longitude += unZigZag(longitudeDelta)
            timestamp += timestampDelta
            guard abs(latitude) <= 9_000_000, abs(longitude) <= 18_000_000 else { return [] }
            points.append(MapPoint(
                longitude: Double(longitude) / 100_000,
                latitude: Double(latitude) / 100_000
            ))
        }
        return points
    }

    private static func fixedCoordinate(_ value: Double) -> Int {
        // Kotlin roundToInt rounds a half toward positive infinity.
        Int(floor(value * 100_000 + 0.5))
    }

    private static func zigZag(_ value: Int) -> UInt64 {
        let signed = Int64(value)
        return UInt64(bitPattern: (signed << 1) ^ (signed >> 63))
    }

    private static func unZigZag(_ value: UInt64) -> Int {
        Int(Int64(value >> 1) ^ -Int64(value & 1))
    }

    private static func appendUVarint(_ value: UInt64, to data: inout Data) {
        var remainder = value
        while remainder & ~UInt64(0x7f) != 0 {
            data.append(UInt8((remainder & 0x7f) | 0x80))
            remainder >>= 7
        }
        data.append(UInt8(remainder))
    }

    private static func readUVarint(_ data: Data, cursor: inout Int) -> UInt64? {
        var result: UInt64 = 0
        var shift = 0
        while shift < 64 {
            guard cursor < data.count else { return nil }
            let byte = data[cursor]
            cursor += 1
            if shift == 63, byte & 0x7e != 0 { return nil }
            result |= UInt64(byte & 0x7f) << shift
            if byte & 0x80 == 0 { return result }
            shift += 7
        }
        return nil
    }
}

/// Rolling 15 km leader buffer with the same jitter and discontinuity rules as Android.
struct FollowMeTrailPublisher {
    private let throttle: TimeInterval
    private var pointsStorage: [MapPoint] = []
    private var lastWriteAt: Date?
    private var dirty = false

    init(throttle: TimeInterval = FollowMeTrail.writeThrottle) {
        self.throttle = throttle
    }

    var points: [MapPoint] { pointsStorage }

    mutating func ingest(_ point: MapPoint, now: Date) -> String? {
        guard point.latitude.isFinite, point.longitude.isFinite,
              abs(point.latitude) <= 90, abs(point.longitude) <= 180
        else { return nil }
        if append(point) { dirty = true }
        guard dirty else { return nil }
        if let lastWriteAt, now.timeIntervalSince(lastWriteAt) < throttle { return nil }
        lastWriteAt = now
        dirty = false
        return FollowMeTrail.encode(pointsStorage)
    }

    mutating func reset() {
        pointsStorage = []
        lastWriteAt = nil
        dirty = false
    }

    private mutating func append(_ point: MapPoint) -> Bool {
        guard let last = pointsStorage.last else {
            pointsStorage.append(point)
            return true
        }
        let moved = Self.distance(from: last, to: point)
        if moved < 5 { return false }
        if moved > 300 {
            pointsStorage = [point]
            return true
        }
        pointsStorage.append(point)
        trimToWindow()
        return true
    }

    private mutating func trimToWindow() {
        var total = Self.length(pointsStorage)
        while pointsStorage.count > 2 {
            let lead = Self.distance(from: pointsStorage[0], to: pointsStorage[1])
            guard total - lead >= FollowMeTrail.windowMeters else { break }
            pointsStorage.removeFirst()
            total -= lead
        }
    }

    static func length(_ points: [MapPoint]) -> Double {
        zip(points, points.dropFirst()).reduce(0) { $0 + distance(from: $1.0, to: $1.1) }
    }

    private static func distance(from first: MapPoint, to second: MapPoint) -> Double {
        LiveShareCadence.distanceMeters(
            lat1: first.latitude,
            lon1: first.longitude,
            lat2: second.latitude,
            lon2: second.longitude
        )
    }
}
