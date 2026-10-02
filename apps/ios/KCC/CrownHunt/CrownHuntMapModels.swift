import Foundation

enum CrownMapTarget: Equatable, Sendable, Identifiable {
    case point(CrownMapPoint)
    case spawn(CrownSpawn)

    var id: String {
        switch self {
        case .point(let point): "point:\(point.id)"
        case .spawn(let spawn): "spawn:\(spawn.id)"
        }
    }

    var latitude: Double {
        switch self {
        case .point(let point): point.latitude
        case .spawn(let spawn): spawn.latitude
        }
    }

    var longitude: Double {
        switch self {
        case .point(let point): point.longitude
        case .spawn(let spawn): spawn.longitude
        }
    }

    var collectRadiusMeters: Double {
        switch self {
        case .point(let point): point.geofenceRadiusMeters
        case .spawn(let spawn): spawn.collectRadiusMeters
        }
    }
}

struct CrownMapPoint: Equatable, Sendable, Identifiable {
    let id: String
    let title: String
    let detail: String?
    let rewardPoints: Int
    let latitude: Double
    let longitude: Double
    let geofenceRadiusMeters: Double
}

enum CrownSpawnClaimResult: String, Equatable, Sendable {
    case awarded
    case alreadyTaken = "already_taken"
    case alreadyCollected = "already_collected"
    case outsideRadius = "outside_radius"
    case mustBeStationary = "must_be_stationary"
    case positionTooOld = "position_too_old"
    case crownExpired = "crown_expired"
    case dailyLimitReached = "daily_limit_reached"
    case riskReview = "risk_review"
    case featureDisabled = "feature_disabled"
    case notEligible = "not_eligible"
}

struct CrownSpawn: Equatable, Sendable, Identifiable {
    let id: String
    let latitude: Double
    let longitude: Double
    let rarity: CrownRarity
    let rewardPoints: Int
    let collectRadiusMeters: Double
    let expiresAt: Date?

    var marker: MapCrownMarker {
        let style = CrownMarkerStyle.style(for: rarity)
        return MapCrownMarker(
            id: "spawn:\(id)",
            longitude: longitude,
            latitude: latitude,
            discColorArgb: style.disc,
            iconName: style.symbol,
            glyphColorArgb: style.glyph,
            glowColorArgb: style.glow
        )
    }
}

struct CrownSpawnClaimOutcome: Equatable, Sendable {
    let result: CrownSpawnClaimResult
    let pointsAwarded: Int?
    let newBalance: Int?
    let rarity: CrownRarity?
}

struct CrownPointClaimOutcome: Equatable, Sendable {
    let result: CrownHuntClaimResult
    let pointsAwarded: Int?
    let newBalance: Int?
}

enum CrownMarkerStyle {
    struct Values: Equatable, Sendable {
        let disc: UInt32
        let glyph: UInt32
        let glow: UInt32?
        let symbol: String
    }

    static func style(for rarity: CrownRarity) -> Values {
        switch rarity {
        case .common: Values(disc: 0xFF607D8B, glyph: 0xFFFFFFFF, glow: nil, symbol: "crown.fill")
        case .uncommon: Values(disc: 0xFF2E7D32, glyph: 0xFFFFFFFF, glow: nil, symbol: "crown.fill")
        case .rare: Values(disc: 0xFF1565C0, glyph: 0xFFFFFFFF, glow: nil, symbol: "crown.fill")
        case .legendary:
            Values(disc: 0xFFFFB300, glyph: 0xFF1B1B1B, glow: 0x99FFD54F, symbol: "crown.fill")
        }
    }

    static func point(inRange: Bool) -> MapCrownMarkerStyle {
        MapCrownMarkerStyle(
            disc: inRange ? 0xFF9C27B0 : 0xFF6B7280,
            glyph: 0xFFFFFFFF
        )
    }
}

struct MapCrownMarkerStyle: Equatable, Sendable {
    let disc: UInt32
    let glyph: UInt32
}

enum CrownSpawnLimits {
    static let defaultCollectRadiusMeters = 75.0
    static let maximumStoredCollectRadiusMeters = 250.0
    static let maximumSpeedMetersPerSecond = 2.0
    static let minimumDwell: TimeInterval = 4
    static let maximumDwell: TimeInterval = 300
    static let maximumPositionAge: TimeInterval = 60

    static func collectRadius(_ stored: Double?) -> Double {
        guard let stored, stored.isFinite, stored > 0,
              stored <= maximumStoredCollectRadiusMeters else {
            return defaultCollectRadiusMeters
        }
        return stored
    }
}

enum CrownGeo {
    private static let earthRadiusMeters = 6_371_008.8

    static func distanceMeters(
        latitude: Double,
        longitude: Double,
        toLatitude: Double,
        toLongitude: Double
    ) -> Double {
        let lat1 = latitude * .pi / 180
        let lat2 = toLatitude * .pi / 180
        let dLat = (toLatitude - latitude) * .pi / 180
        let dLon = (toLongitude - longitude) * .pi / 180
        let a = sin(dLat / 2) * sin(dLat / 2)
            + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return earthRadiusMeters * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}

enum CrownSpawnQuery {
    static let cellDegrees = 0.01
    static let minimumRing = 1
    static let maximumRing = 5
    static let maximumCells = 150
    static let firestoreInLimit = 30
    static let maximumQueryRadiusMeters = 9_000.0
    private static let metresPerLatitudeDegree = 111_320.0

    static func rings(visibleRadiusMeters: Double?) -> Int {
        guard let radius = visibleRadiusMeters, radius.isFinite, radius > 0 else {
            return minimumRing
        }
        let bounded = min(radius, maximumQueryRadiusMeters)
        return min(max(Int(ceil(bounded / (cellDegrees * metresPerLatitudeDegree))), minimumRing), maximumRing)
    }

    static func cellKeys(latitude: Double, longitude: Double, visibleRadiusMeters: Double?) -> [String] {
        guard latitude.isFinite, longitude.isFinite else { return [] }
        let latIndex = Int(floor(min(max(latitude, -90), 90) / cellDegrees))
        let lonIndex = Int(floor(min(max(longitude, -180), 180) / cellDegrees))
        let ring = rings(visibleRadiusMeters: visibleRadiusMeters)
        var keys: [String] = []
        for latOffset in -ring...ring {
            for lonOffset in -ring...ring {
                keys.append("\(latIndex + latOffset)_\(lonIndex + lonOffset)")
            }
        }
        return Array(keys.prefix(maximumCells))
    }

    static func batches(_ keys: [String]) -> [[String]] {
        let bounded = Array(keys.prefix(maximumCells))
        return stride(from: 0, to: bounded.count, by: firestoreInLimit).map {
            Array(bounded[$0..<min($0 + firestoreInLimit, bounded.count)])
        }
    }
}

struct CrownFixPair: Equatable, Sendable {
    let previous: LocationFix
    let current: LocationFix

    var movementSpeedMetersPerSecond: Double {
        let elapsed = current.timestamp.timeIntervalSince(previous.timestamp)
        guard elapsed > 0 else { return .infinity }
        let derived = CrownGeo.distanceMeters(
            latitude: previous.latitude,
            longitude: previous.longitude,
            toLatitude: current.latitude,
            toLongitude: current.longitude
        ) / elapsed
        // One instantaneous spike is common GPS jitter. Treat reported speed
        // as corroborated movement only when BOTH fixes exceed the server's
        // ceiling, then use the slower reading so one sample cannot inflate it.
        let corroborated: Double
        if let earlier = previous.speedMetersPerSecond,
           let later = current.speedMetersPerSecond,
           earlier > CrownSpawnLimits.maximumSpeedMetersPerSecond,
           later > CrownSpawnLimits.maximumSpeedMetersPerSecond {
            corroborated = min(earlier, later)
        } else {
            corroborated = 0
        }
        return max(derived, corroborated)
    }
}

struct CrownFixTracker: Equatable, Sendable {
    private(set) var fixes: [LocationFix] = []
    private let capacity = 120

    mutating func record(_ fix: LocationFix) {
        fixes.append(fix)
        fixes.sort { $0.timestamp < $1.timestamp }
        fixes = Array(fixes.suffix(capacity))
    }

    func latest(now: Date) -> LocationFix? {
        fixes.last(where: { now.timeIntervalSince($0.timestamp) >= 0
            && now.timeIntervalSince($0.timestamp) <= CrownSpawnLimits.maximumPositionAge })
    }

    func proof(for spawn: CrownSpawn, now: Date) -> CrownFixPair? {
        let fresh = fixes.reversed().filter { fix in
            now.timeIntervalSince(fix.timestamp) >= 0
                && now.timeIntervalSince(fix.timestamp) <= CrownSpawnLimits.maximumPositionAge
                && inRange(fix, of: spawn)
        }
        for current in fresh {
            let candidates = fixes.filter { previous in
                let gap = current.timestamp.timeIntervalSince(previous.timestamp)
                return gap >= CrownSpawnLimits.minimumDwell
                    && gap <= CrownSpawnLimits.maximumDwell
                    && inRange(previous, of: spawn)
            }
            if let previous = candidates.min(by: { accuracyRank($0) < accuracyRank($1) }) {
                return CrownFixPair(previous: previous, current: current)
            }
        }
        return nil
    }

    private func inRange(_ fix: LocationFix, of spawn: CrownSpawn) -> Bool {
        CrownGeo.distanceMeters(
            latitude: fix.latitude,
            longitude: fix.longitude,
            toLatitude: spawn.latitude,
            toLongitude: spawn.longitude
        ) <= spawn.collectRadiusMeters
    }

    private func accuracyRank(_ fix: LocationFix) -> Double {
        fix.accuracyMeters ?? .greatestFiniteMagnitude
    }
}

enum CrownCollectState: Equatable, Sendable {
    case ready
    case noPosition
    case tooFar(Double)
    case waitingForSignal
    case confirming
    case moving
    case featureOff
}

enum CrownCollectGate {
    static func evaluate(
        spawn: CrownSpawn,
        latest: LocationFix?,
        proof: CrownFixPair?,
        enabled: Bool,
        now: Date
    ) -> CrownCollectState {
        guard enabled else { return .featureOff }
        guard let latest,
              now.timeIntervalSince(latest.timestamp) >= 0,
              now.timeIntervalSince(latest.timestamp) <= CrownSpawnLimits.maximumPositionAge else {
            return .noPosition
        }
        let distance = CrownGeo.distanceMeters(
            latitude: latest.latitude,
            longitude: latest.longitude,
            toLatitude: spawn.latitude,
            toLongitude: spawn.longitude
        )
        guard distance <= spawn.collectRadiusMeters else { return .tooFar(distance) }
        if let accuracy = latest.accuracyMeters, accuracy > spawn.collectRadiusMeters {
            return .waitingForSignal
        }
        guard let proof else { return .confirming }
        guard proof.movementSpeedMetersPerSecond <= CrownSpawnLimits.maximumSpeedMetersPerSecond else {
            return .moving
        }
        return .ready
    }
}
