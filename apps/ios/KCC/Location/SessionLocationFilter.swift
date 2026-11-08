import Foundation

/// Quality and motion evidence for the dedicated live/recording providers.
/// Foreground map/search/crown positioning and Mapbox guidance keep their own
/// GPS streams. No extra sensor, permission, request or persisted location.
struct SessionLocationFilter: Sendable {
    // Android rejects coarse fixes before publishing (200 m). Recording needs
    // a tighter 50 m ceiling: a usable road marker is not necessarily a usable
    // distance sample. Unknown precision is also withheld on this local seam.
    static let maximumAccuracyMeters = 50.0
    private var anchor: LocationFix?
    private var lastRaw: LocationFix?
    private var candidate: LocationFix?
    private var moving = false
    private var reacquiring = false

    mutating func process(_ fix: LocationFix, now: Date) -> LocationFix? {
        let age = now.timeIntervalSince(fix.timestamp)
        guard fix.latitude.isFinite, (-90...90).contains(fix.latitude),
              fix.longitude.isFinite, (-180...180).contains(fix.longitude),
              age.isFinite, (-5...15).contains(age),
              let accuracy = fix.accuracyMeters, accuracy.isFinite,
              (0...Self.maximumAccuracyMeters).contains(accuracy) else { return nil }
        if let lastRaw {
            let dt = fix.timestamp.timeIntervalSince(lastRaw.timestamp)
            guard dt > 0, Self.distance(lastRaw, fix) / dt <= 55.6 else { return nil }
            if dt > 15 { reacquiring = true }
        }
        let speed = Self.trustedSpeed(fix)
        guard let anchor else { return adopt(fix, speed: speed) }
        lastRaw = fix
        let distance = Self.distance(anchor, fix)
        let radius = max(10, min(50, (anchor.accuracyMeters ?? 0) + accuracy))

        // Accurate zero speed is affirmative stationary evidence. Finish the
        // last moving segment once, then hold the parked coordinate.
        if speed == 0 {
            if moving && !reacquiring { return adopt(fix, speed: 0) }
            if !reacquiring || distance <= radius {
                candidate = nil; reacquiring = false
                return held(anchor, at: fix, speed: 0)
            }
        }
        if moving, !reacquiring, let speed, speed > 0 { return adopt(fix, speed: speed) }
        if distance <= radius {
            candidate = nil; reacquiring = false
            return held(anchor, at: fix, speed: nil)
        }

        // With uncertain speed, or when first leaving a parked anchor, one
        // displaced fix is insufficient. Require another fix in that area or
        // continuing in the same direction. A return across the anchor rejects
        // the candidate instead of counting both halves of a GPS bounce.
        if let candidate {
            let nextDistance = Self.distance(candidate, fix)
            let candidateRadius = max(10, min(25, (candidate.accuracyMeters ?? 0) + accuracy))
            let oldDistance = Self.distance(anchor, candidate)
            let confirmsArea = nextDistance <= candidateRadius
            let continues = distance > oldDistance + radius / 2 &&
                Self.directionAgreement(anchor, candidate, fix) > 0.8
            if confirmsArea || continues {
                if fix.timestamp.timeIntervalSince(candidate.timestamp) >= 2 { return adopt(fix, speed: speed) }
                // CoreLocation commonly delivers at 1 Hz. Preserve the first
                // corroborated candidate until its evidence window matures.
                return held(anchor, at: fix, speed: nil)
            }
        }
        candidate = fix
        return held(anchor, at: fix, speed: nil)
    }

    private mutating func adopt(_ fix: LocationFix, speed: Double?) -> LocationFix {
        let value = copy(position: fix, sample: fix, speed: speed)
        anchor = value; lastRaw = fix; candidate = nil; moving = (speed ?? 0) > 0; reacquiring = false
        return value
    }
    private func held(_ anchor: LocationFix, at fix: LocationFix, speed: Double?) -> LocationFix {
        copy(position: anchor, sample: fix, speed: speed)
    }
    private func copy(position: LocationFix, sample: LocationFix, speed: Double?) -> LocationFix {
        LocationFix.of(latitude: position.latitude, longitude: position.longitude,
            timestamp: sample.timestamp, accuracyMeters: position.accuracyMeters,
            headingDegrees: (speed ?? 0) > 0 ? sample.headingDegrees : nil,
            speedMetersPerSecond: speed, speedAccuracyMetersPerSecond: sample.speedAccuracyMetersPerSecond,
            isSimulatedBySoftware: sample.isSimulatedBySoftware)!
    }
    private static func trustedSpeed(_ fix: LocationFix) -> Double? {
        guard let speed = fix.speedMetersPerSecond, speed.isFinite, (0...55.6).contains(speed),
              let uncertainty = fix.speedAccuracyMetersPerSecond,
              uncertainty.isFinite, (0...1.5).contains(uncertainty) else { return nil }
        if speed <= max(0.5, uncertainty) { return 0 }
        return speed - uncertainty > 0.5 ? speed : nil
    }
    private static func distance(_ a: LocationFix, _ b: LocationFix) -> Double {
        LiveShareCadence.distanceMeters(lat1: a.latitude, lon1: a.longitude, lat2: b.latitude, lon2: b.longitude)
    }
    private static func directionAgreement(_ origin: LocationFix, _ a: LocationFix, _ b: LocationFix) -> Double {
        let scale = cos(origin.latitude * .pi / 180)
        let ax = (a.longitude - origin.longitude) * scale, ay = a.latitude - origin.latitude
        let bx = (b.longitude - origin.longitude) * scale, by = b.latitude - origin.latitude
        let denominator = hypot(ax, ay) * hypot(bx, by)
        return denominator > 0 ? (ax * bx + ay * by) / denominator : -1
    }
}
