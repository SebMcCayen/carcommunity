import Foundation

struct LiveSpeedSample: Equatable, Sendable {
    let metersPerSecond: Double?
    let capturedAt: Date
}

/// Display-only state, fed by the existing authorized live publisher. It owns
/// no GPS stream, network request or recorded route.
struct LiveSessionReadout: Equatable, Sendable {
    private(set) var sessionId: String?
    private(set) var startedAt: Date?
    private(set) var speed: LiveSpeedSample?
    private var pendingStart: Date?

    mutating func requestedStart(at now: Date) { pendingStart = now }
    mutating func cancelPendingStart() { pendingStart = nil }
    mutating func clearSpeed() { speed = nil }

    mutating func observe(_ session: LiveSessionInfo?, at now: Date) {
        guard LiveLocation.isSharing(session, at: now), let session else {
            sessionId = nil; startedAt = nil; speed = nil
            return
        }
        guard sessionId != session.sessionId else { return }
        sessionId = session.sessionId
        let tap = pendingStart.flatMap { now.timeIntervalSince($0) <= 120 ? $0 : nil }
        let observed = session.expiresAt.flatMap { expiry in
            session.duration.map { expiry.addingTimeInterval(-Double($0.hours) * 3_600) }
        }
        let candidate = tap ?? observed ?? now
        startedAt = candidate.timeIntervalSince1970.isFinite ? min(candidate, now) : now
        pendingStart = nil
        speed = nil
    }

    mutating func acceptSpeed(from fix: LocationFix) {
        guard sessionId != nil, fix.timestamp.timeIntervalSince1970.isFinite else { return }
        if let speed, fix.timestamp < speed.capturedAt { return }
        speed = LiveSpeedSample(metersPerSecond: fix.speedMetersPerSecond, capturedAt: fix.timestamp)
    }
}

enum LiveSessionReadoutFormat {
    static func elapsed(startedAt: Date, now: Date) -> String {
        let delta = now.timeIntervalSince(startedAt)
        guard delta.isFinite else { return "0:00" }
        let seconds = Int(min(Double(Int.max / 2), max(0, delta)))
        if seconds >= 3_600 {
            return String(format: "%d:%02d:%02d", seconds / 3_600, (seconds / 60) % 60, seconds % 60)
        }
        return String(format: "%d:%02d", seconds / 60, seconds % 60)
    }

    /// Same 15-second stale cutoff and 1 km/h deadband as Android. Missing
    /// speed stays unknown; a genuine stationary sample displays zero.
    static func speedKmh(_ sample: LiveSpeedSample?, at now: Date, previous: Int?) -> Int? {
        guard let sample, now.timeIntervalSince(sample.capturedAt) < 15,
              let mps = sample.metersPerSecond, mps.isFinite, mps >= 0 else { return nil }
        let kmh = mps * 3.6
        guard kmh.isFinite, kmh.rounded() < Double(Int.max) else { return nil }
        if let previous, abs(kmh - Double(previous)) < 1 { return previous }
        return Int(kmh.rounded())
    }
}
