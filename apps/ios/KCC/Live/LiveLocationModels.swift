// Pure live-location models + timing policy — the Firebase-free vocabulary of
// the live-sharing feature, the iOS port of Android's `live/LiveLocation.kt`
// (session model) and `location/BackgroundLocation.kt` (cadence constants +
// publish throttle). Everything here is deterministic and unit-testable with
// no SDK, mirroring the Kotlin sources' "pure logic first" split.
//
// Deliberate deviations from the Kotlin sources (documented per the parity
// instructions):
// - `LiveSessionInfo` omits Android's `convoyAutoStarted`, `mainCar` and
//   `vehicleId` fields: they exist solely for the convoy coupling and the
//   drive-recording car stamp, neither of which has ported yet. They are
//   additive session-node reads and arrive with their slices.
// - Times are `Date`/`TimeInterval` instead of epoch millis — the platform's
//   native clock vocabulary, exactly as `LocationFix` did for `LiveCoordinate`.

import Foundation

/// Session durations — mirror the backend LIVE_SESSION_DURATIONS map
/// (functions/src/live/live-core.ts) and Android's `LiveSessionDuration`.
enum LiveSessionDuration: String, CaseIterable, Sendable {
    case oneHour = "1h"
    case twoHours = "2h"
    case fourHours = "4h"

    /// The window every session now starts with (single AND convoy): 6 hours,
    /// i.e. the hard cap (``LiveLocation/maxSessionInterval``). A session
    /// simply runs to 6h and auto-stops — nothing prompts the user to prolong
    /// it. The shorter keys above are kept only for backward compatibility
    /// (sessions/older clients that still carry them).
    case sixHours = "6h"

    /// The wire key sent to `live-startSession` and stored on the session node.
    var key: String { rawValue }

    var hours: Int {
        switch self {
        case .oneHour: 1
        case .twoHours: 2
        case .fourHours: 4
        case .sixHours: 6
        }
    }

    static func fromKey(_ value: String?) -> LiveSessionDuration? {
        value.flatMap(LiveSessionDuration.init(rawValue:))
    }
}

/// Session status stored at `liveLocation/{uid}/session.status`
/// (contracts/schemas/live-location.schema.json `liveLocationSessionStatus`).
enum LiveSessionStatus: String, Sendable {
    case active
    case stopped
    case expired

    static func fromWire(_ value: String?) -> LiveSessionStatus? {
        value.flatMap(LiveSessionStatus.init(rawValue:))
    }
}

/// The caller's own session node (owner-readable at
/// `liveLocation/{uid}/session` — firebase/database.rules.json). `expiresAt`
/// is the parsed ISO expiry, or nil when it could not be parsed — in which
/// case an ACTIVE session is still treated as sharing so the user is never
/// left without a stop control (Android's `LiveSessionInfo` contract).
struct LiveSessionInfo: Equatable, Sendable {
    let sessionId: String
    let status: LiveSessionStatus
    let duration: LiveSessionDuration?
    let expiresAt: Date?

    /// Maps the RTDB session node's dictionary value to the model, or nil when
    /// the node is missing a required field. Pure (`[String: Any]` in, model
    /// out) so the wire mapping is unit-testable without the Database SDK —
    /// the same seam `UserProfile.fromMap` gives the Firestore profile.
    static func fromMap(_ map: [String: Any]) -> LiveSessionInfo? {
        guard
            let status = LiveSessionStatus.fromWire(map["status"] as? String),
            let sessionId = map["id"] as? String
        else { return nil }
        return LiveSessionInfo(
            sessionId: sessionId,
            status: status,
            duration: LiveSessionDuration.fromKey(map["duration"] as? String),
            expiresAt: (map["expiresAt"] as? String).flatMap(LiveIsoInstant.parse)
        )
    }
}

/// One GPS sample to publish via `live-updatePosition` — the iOS mirror of
/// Android's `LiveCoordinate` (`live/LiveLocationRepository.kt`), carrying the
/// wire-format ISO timestamp the callable expects
/// (contracts/schemas/live-location.schema.json `liveLocationCoordinate`).
struct LiveCoordinate: Equatable, Sendable {
    let latitude: Double
    let longitude: Double
    let recordedAtIso: String
    let accuracyMeters: Double?
    let headingDegrees: Double?
    let speedMetersPerSecond: Double?

    /// Maps a ``LocationFix`` from the provider seam to the publishable wire
    /// shape — the iOS `BackgroundLocation.buildCoordinate`: the fix timestamp
    /// becomes an ISO-8601 instant, and the optional accuracy/heading/speed
    /// fields pass straight through (already nil-normalized at the seam).
    init(fix: LocationFix) {
        latitude = fix.latitude
        longitude = fix.longitude
        recordedAtIso = LiveIsoInstant.format(fix.timestamp)
        accuracyMeters = fix.accuracyMeters
        headingDegrees = fix.headingDegrees
        speedMetersPerSecond = fix.speedMetersPerSecond
    }
}

/// Live-location domain rules — the iOS port of Android's `LiveLocation`
/// object, mirroring the backend contract (functions/src/live/live-core.ts).
enum LiveLocation {
    /// The window a Single (solo) live session starts with. Starting is
    /// IMMEDIATE — the user is not asked to pick a time — so this fixed
    /// default is the single source of truth for every session start. It is
    /// the 6h hard cap: the session runs for 6 hours and then auto-stops,
    /// with nothing asking the user to prolong it; the user can Stop (or
    /// Hide me now) at any time. The backend `live-startSession` callable
    /// still requires a `duration`; this `6h` key is passed through unchanged.
    static let defaultSessionDuration: LiveSessionDuration = .sixHours

    /// Absolute hard cap on any one live-sharing window — the iOS copy of the
    /// server's `LIVE_SESSION_MAX_MS` (functions/src/live/live-core.ts) and
    /// Android's `LiveLocation.LIVE_SESSION_MAX_MS`. The three cannot share a
    /// literal constant across language boundaries, so each side defines it
    /// and asserts agreement in tests (LiveLocationModelsTests here). Retune
    /// in ALL places.
    static let maxSessionInterval: TimeInterval = 6 * 60 * 60

    /// Whether the caller is currently sharing: an active session that has
    /// not passed its expiry. Mirrors the backend isSessionActive check. A
    /// nil (unparseable) expiry does not hide an active session.
    static func isSharing(_ session: LiveSessionInfo?, at now: Date) -> Bool {
        guard let session, session.status == .active else { return false }
        guard let expiresAt = session.expiresAt else { return true }
        return expiresAt > now
    }

    /// Whole seconds remaining until expiry, floored at 0; nil if unknown.
    static func remainingSeconds(_ session: LiveSessionInfo?, at now: Date) -> Int? {
        guard let expiresAt = session?.expiresAt else { return nil }
        return max(0, Int(expiresAt.timeIntervalSince(now)))
    }
}

/// Publish cadence policy while sharing — the iOS copy of Android's
/// `BackgroundLocation` timing constants and its `shouldPublish` throttle.
/// The values are the battery/traffic contract both platforms share; retune
/// them together (see the Kotlin source for each constant's full rationale).
enum LiveShareCadence {
    /// Requested cadence for provider fixes while sharing (~5 s).
    static let updateInterval: TimeInterval = 5

    /// Fastest cadence we will accept updates at (throttles bursty fixes).
    static let minUpdateInterval: TimeInterval = 2

    /// How far the device must have moved since the last SUBMITTED fix before
    /// a new one is worth a network round-trip.
    static let movementThresholdMeters: Double = 15

    /// Publish at least this often even when stationary, so viewers can tell
    /// a parked friend from a dead phone. Any reader-staleness window must
    /// stay STRICTLY GREATER than this (see the Kotlin source's
    /// reader-staleness reconciliation note).
    static let stationaryHeartbeat: TimeInterval = 3 * 60

    /// How often a sharer re-checks the clock against the session expiry.
    static let expiryTick: TimeInterval = 15

    /// Whether a freshly received fix is worth publishing, given the last one
    /// we SUBMITTED (recorded at dispatch, not on backend confirmation — see
    /// Android's `shouldPublish` for why). Movement OR the stationary
    /// heartbeat qualifies; the first fix of a session always does.
    static func shouldPublish(
        lastSubmittedAt: Date?,
        lastSubmittedLatitude: Double?,
        lastSubmittedLongitude: Double?,
        latitude: Double,
        longitude: Double,
        now: Date
    ) -> Bool {
        guard
            let lastSubmittedAt,
            let lastSubmittedLatitude,
            let lastSubmittedLongitude
        else { return true }
        if now.timeIntervalSince(lastSubmittedAt) >= stationaryHeartbeat { return true }
        let moved = distanceMeters(
            lat1: lastSubmittedLatitude,
            lon1: lastSubmittedLongitude,
            lat2: latitude,
            lon2: longitude
        )
        return moved >= movementThresholdMeters
    }

    /// Great-circle distance in metres between two WGS-84 coordinates
    /// (haversine, matching Android's `distanceMeters`).
    static func distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double) -> Double {
        let earthRadiusMeters = 6_371_000.0
        let dLat = (lat2 - lat1) * .pi / 180
        let dLon = (lon2 - lon1) * .pi / 180
        let a =
            sin(dLat / 2) * sin(dLat / 2)
            + cos(lat1 * .pi / 180) * cos(lat2 * .pi / 180)
            * sin(dLon / 2) * sin(dLon / 2)
        return 2 * earthRadiusMeters * atan2(sqrt(a), sqrt(1 - a))
    }
}

/// ISO-8601 instant formatting/parsing for the live-location wire shapes.
///
/// The backend writes `expiresAt`/`recordedAt` with JavaScript's
/// `Date.toISOString()` (always fractional, e.g. `2026-08-30T12:00:00.000Z`),
/// while Android publishes `Instant.toString()` (fractional only when
/// nonzero) — so the parser accepts both, and the formatter always emits
/// fractional seconds, which every consumer parses.
enum LiveIsoInstant {
    /// Cached formatters: this path runs in the publish loop (every few
    /// seconds while sharing), so per-call allocation is avoidable overhead.
    /// `nonisolated(unsafe)` is sound because `ISO8601DateFormatter` is
    /// documented thread-safe and both instances are configured once here
    /// and never mutated again.
    nonisolated(unsafe) private static let fractional: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter
    }()

    nonisolated(unsafe) private static let wholeSeconds: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        return formatter
    }()

    static func format(_ date: Date) -> String {
        fractional.string(from: date)
    }

    static func parse(_ iso: String) -> Date? {
        fractional.date(from: iso) ?? wholeSeconds.date(from: iso)
    }
}
