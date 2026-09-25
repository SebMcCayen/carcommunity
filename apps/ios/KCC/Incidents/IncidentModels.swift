import Foundation

enum IncidentType: String, CaseIterable, Sendable {
    case accident
    case roadwork
    case hazard
    case police
    case roadClosed = "road_closed"

    var titleKey: String {
        switch self {
        case .accident: "incidents.typeAccident"
        case .roadwork: "incidents.typeRoadwork"
        case .hazard: "incidents.typeHazard"
        case .police: "incidents.typePolice"
        case .roadClosed: "incidents.typeRoadClosed"
        }
    }

    var symbolName: String {
        switch self {
        case .accident: "car.side.front.open"
        case .roadwork: "road.lanes.curved.left"
        case .hazard: "exclamationmark.triangle.fill"
        case .police: "shield.fill"
        case .roadClosed: "nosign"
        }
    }

    var markerColorArgb: UInt32 {
        switch self {
        case .accident: 0xFFD32F2F
        case .roadwork: 0xFFF57C00
        case .hazard: 0xFFFBC02D
        case .police: 0xFF1565C0
        case .roadClosed: 0xFF6A1B9A
        }
    }

    var glyphColorArgb: UInt32 { self == .hazard ? 0xFF111111 : 0xFFFFFFFF }
}

struct RoadIncident: Equatable, Identifiable, Sendable {
    let id: String
    let type: IncidentType
    let longitude: Double
    let latitude: Double
    let note: String?
    let source: String
    let reporterUid: String?
    let createdAt: Date?
    let postedAt: Date?
    var confirmationCount: Int
    var clearedCount: Int
    var reportedCleared: Bool

    var isImported: Bool { source == "trafikverket" }

    func isOwned(by uid: String?) -> Bool {
        guard let uid, !uid.isEmpty else { return false }
        return reporterUid == uid
    }

    var mapMarker: MapIncidentMarker {
        MapIncidentMarker(
            id: id,
            longitude: longitude,
            latitude: latitude,
            colorArgb: type.markerColorArgb,
            iconName: type.symbolName,
            glyphColorArgb: type.glyphColorArgb,
            reportedCleared: reportedCleared
        )
    }
}

struct IncidentConfirmation: Equatable, Sendable {
    let confirmationCount: Int
    let clearedCount: Int
    let reportedCleared: Bool
    let alreadyConfirmed: Bool
}

struct IncidentClearResult: Equatable, Sendable {
    let clearedCount: Int
    let confirmationCount: Int
    let reportedCleared: Bool
    let removed: Bool
    let alreadyVoted: Bool
}

enum IncidentClearRejection: String, Error, Equatable, Sendable {
    case imported = "imported_incident"
    case inactive = "incident_inactive"
    case outOfRange = "out_of_range"
    case positionTooOld = "position_too_old"
    case notCounted = "vote_not_counted"
}

struct PoliceReport: Equatable, Identifiable, Sendable {
    let id: String
    let latitude: Double
    let longitude: Double
    let source: String
    let expiresAt: Date?
    let mine: Bool
    var confirmationCount: Int
    var disputeCount: Int

    func isLive(at date: Date) -> Bool { expiresAt.map { $0 > date } ?? false }

    var marker: MapIncidentMarker {
        MapIncidentMarker(
            id: PoliceMapMarker.prefix + id,
            longitude: longitude,
            latitude: latitude,
            colorArgb: 0xFF1565C0,
            iconName: "shield.fill",
            glyphColorArgb: 0xFFFFFFFF
        )
    }
}

struct PoliceVerification: Equatable, Sendable {
    let policeReportId: String
    let confirmationCount: Int
    let disputeCount: Int
    let alreadyVoted: Bool
    let switched: Bool
}

enum PoliceMapMarker {
    static let prefix = "police:"
    static let coincidenceEpsilon = 0.0001

    static func markers(pins: [PoliceReport], incidents: [RoadIncident]) -> [MapIncidentMarker] {
        let policeIncidents = incidents.filter { $0.type == .police }
        return pins.filter { pin in
            !policeIncidents.contains {
                abs($0.latitude - pin.latitude) <= coincidenceEpsilon
                    && abs($0.longitude - pin.longitude) <= coincidenceEpsilon
            }
        }.map(\.marker)
    }
}

enum IncidentViewport {
    static let minimumRadius = 100.0
    static let maximumRadius = 50_000.0
    static let defaultRadius = 15_000.0

    static func radius(_ raw: Double?) -> Double {
        guard let raw, raw.isFinite else { return defaultRadius }
        return min(max(raw, minimumRadius), maximumRadius)
    }

    static func distanceMeters(
        latitude1: Double, longitude1: Double,
        latitude2: Double, longitude2: Double
    ) -> Double {
        let radius = 6_371_000.0
        let lat1 = latitude1 * .pi / 180
        let lat2 = latitude2 * .pi / 180
        let deltaLat = (latitude2 - latitude1) * .pi / 180
        let deltaLon = (longitude2 - longitude1) * .pi / 180
        let a = sin(deltaLat / 2) * sin(deltaLat / 2)
            + cos(lat1) * cos(lat2) * sin(deltaLon / 2) * sin(deltaLon / 2)
        return radius * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}

enum IncidentAttribution {
    static func markerIsVisible(_ point: MapScreenPoint?, width: Double, height: Double) -> Bool {
        guard let point, point.trustworthy else { return false }
        return point.x >= -30 && point.y >= -30
            && point.x <= width + 30 && point.y <= height + 30
    }
}

enum PoliceProximity {
    static let alertRadiusMeters = 500.0

    static func newAlerts(
        driver: LocationFix?,
        pins: [PoliceReport],
        alreadyAlerted: Set<String>,
        now: Date = Date()
    ) -> [PoliceReport] {
        guard let driver, driver.latitude.isFinite, driver.longitude.isFinite else { return [] }
        var seen = alreadyAlerted
        return pins.filter { pin in
            guard !pin.mine, pin.isLive(at: now), !seen.contains(pin.id),
                  pin.latitude.isFinite, pin.longitude.isFinite else { return false }
            let close = IncidentViewport.distanceMeters(
                latitude1: driver.latitude, longitude1: driver.longitude,
                latitude2: pin.latitude, longitude2: pin.longitude
            ) <= alertRadiusMeters
            if close { seen.insert(pin.id) }
            return close
        }
    }
}

enum IncidentWire {
    private static let fractional = Date.ISO8601FormatStyle(includingFractionalSeconds: true)
    private static let wholeSeconds = Date.ISO8601FormatStyle()

    static func format(_ date: Date) -> String { date.formatted(fractional) }

    static func incident(_ raw: Any?) -> RoadIncident? {
        guard let map = raw as? [String: Any],
              let id = map["id"] as? String,
              let typeRaw = map["type"] as? String,
              let type = IncidentType(rawValue: typeRaw),
              let latitude = (map["latitude"] as? NSNumber)?.doubleValue,
              let longitude = (map["longitude"] as? NSNumber)?.doubleValue,
              latitude.isFinite, longitude.isFinite else { return nil }
        return RoadIncident(
            id: id, type: type, longitude: longitude, latitude: latitude,
            note: map["note"] as? String,
            source: map["source"] as? String ?? "user",
            reporterUid: map["reporterUid"] as? String,
            createdAt: date(map["createdAt"]), postedAt: date(map["postedAt"]),
            confirmationCount: (map["confirmationCount"] as? NSNumber)?.intValue ?? 0,
            clearedCount: (map["clearedCount"] as? NSNumber)?.intValue ?? 0,
            reportedCleared: map["reportedCleared"] as? Bool ?? false
        )
    }

    static func police(_ raw: Any?) -> PoliceReport? {
        guard let map = raw as? [String: Any],
              let id = map["id"] as? String,
              let latitude = (map["latitude"] as? NSNumber)?.doubleValue,
              let longitude = (map["longitude"] as? NSNumber)?.doubleValue,
              latitude.isFinite, longitude.isFinite else { return nil }
        return PoliceReport(
            id: id, latitude: latitude, longitude: longitude,
            source: map["source"] as? String ?? "manual",
            expiresAt: date(map["expiresAt"]), mine: map["mine"] as? Bool ?? false,
            confirmationCount: (map["confirmationCount"] as? NSNumber)?.intValue ?? 0,
            disputeCount: (map["disputeCount"] as? NSNumber)?.intValue ?? 0
        )
    }

    private static func date(_ raw: Any?) -> Date? {
        guard let value = raw as? String else { return nil }
        return (try? Date(value, strategy: fractional))
            ?? (try? Date(value, strategy: wholeSeconds))
    }
}
