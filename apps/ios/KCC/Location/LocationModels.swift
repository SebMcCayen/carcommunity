// Pure location models — the CoreLocation-free vocabulary of the location
// layer, shared by the provider seam, the permission coordinator, and (in
// later PRs) the map puck and drives/live-location features.
//
// The iOS counterpart of the pure half of Android's `location/` package:
// `LocationFix` mirrors the shape `BackgroundLocation.buildCoordinate` maps a
// platform fix into (`LiveCoordinate`: latitude/longitude/recordedAt +
// optional accuracy/heading/speed), and `LocationAuthorization` plays the role
// of `LocationAccess` — the app's own reading of whether it may position.
//
// Deliberate deviations from the Kotlin source (documented per the parity
// instructions):
// - Android's `LocationAccess` folds the device-wide location toggle in as
//   `SERVICES_OFF`, because on Android permission and the master switch are
//   two separate screens with two separate remedies. iOS has no equivalent
//   split to act on from the app: when Location Services are off system-wide,
//   CoreLocation reports the app's authorization as denied-shaped and the
//   remedy (the Settings app) is the same screen either way, so the enum
//   carries no separate services-off case.
// - Android's `LocationPermissionRemedy` (request-again vs open-settings)
//   does not port: iOS never re-presents the system dialog after a denial —
//   `requestWhenInUseAuthorization()` is a no-op unless the status is
//   not-determined — so a denial's remedy is ALWAYS the app's Settings page.
//   That rule lives in ``LocationPermissionCoordinator`` as the
//   denied-needs-settings state instead of a two-case type.

import Foundation

/// The app's location authorization, as the location layer reads it — the
/// CoreLocation-free projection of `CLAuthorizationStatus` that crosses the
/// ``LocationProvider`` seam.
enum LocationAuthorization: Equatable, Sendable {
    /// The user has never been asked. The system dialog CAN still be raised
    /// (the only state in which it can).
    case notDetermined

    /// The app may not use location: the user denied it, a profile restricts
    /// it (`CLAuthorizationStatus.restricted`), or Location Services are off
    /// system-wide. Folded into ONE case deliberately — none of these can be
    /// fixed by asking again, and all of them are fixed in Settings.
    case denied

    /// Location while the app is in use — the grant the map puck and
    /// in-app drive recording need.
    case whileInUse

    /// Location even in the background. NOT requested by this app today:
    /// Android's live sharing runs on a location-typed foreground service
    /// without `ACCESS_BACKGROUND_LOCATION` (see Android's
    /// `LocationSharingService` — "Permissions: no ACCESS_BACKGROUND_LOCATION"),
    /// and the iOS analogue when live sharing ports is the `location`
    /// background mode on a when-in-use grant, not an Always request. The case
    /// exists so a status the SYSTEM can still report (granted via Settings)
    /// maps honestly instead of falling into a default.
    case always

    /// True when the app may read the user's position right now.
    var isAuthorized: Bool {
        switch self {
        case .whileInUse, .always: true
        case .notDetermined, .denied: false
        }
    }
}

/// One GPS fix, as the rest of the app consumes it — the iOS mirror of the
/// `LiveCoordinate` shape Android's `BackgroundLocation.buildCoordinate`
/// publishes (latitude/longitude/recordedAt + optional accuracy, heading,
/// speed), expressed with a `Date` instead of a wire-format ISO string
/// because nothing here is being serialized yet; the live-location PR maps
/// `timestamp` to the same ISO-8601 instant Android sends.
///
/// The optional fields are nil when the platform could not measure them —
/// never a sentinel. CoreLocation encodes "unknown" as NEGATIVE values
/// (`horizontalAccuracy < 0`, `course < 0`, `speed < 0`);
/// ``of(latitude:longitude:timestamp:accuracyMeters:headingDegrees:speedMetersPerSecond:)``
/// normalizes those to nil at the seam so no consumer ever branches on a
/// magic -1. (Android's fused provider hands optionals directly, so the two
/// platforms agree on nil-means-unknown.)
struct LocationFix: Equatable, Sendable {
    /// WGS-84 latitude in degrees.
    let latitude: Double

    /// WGS-84 longitude in degrees.
    let longitude: Double

    /// When the fix was measured (the platform's fix timestamp, not receipt
    /// time).
    let timestamp: Date

    /// Horizontal accuracy radius in metres, or nil when unknown.
    let accuracyMeters: Double?

    /// Direction of travel in degrees clockwise from true north
    /// [0, 360), or nil when unknown (stationary, or no course).
    let headingDegrees: Double?

    /// Ground speed in metres per second, or nil when unknown.
    let speedMetersPerSecond: Double?

    /// Maps raw platform values into a clean fix, or nil when the coordinate
    /// itself is unusable (non-finite — a fix with no position is not a fix).
    ///
    /// Pure and CoreLocation-free so the sentinel-normalization rules are
    /// unit-testable off-device — the same reason Android keeps
    /// `buildCoordinate` free of framework types. `CoreLocationProvider` is
    /// the only production caller; it feeds `CLLocation`'s fields straight in.
    ///
    /// Normalization, per field:
    /// - `accuracyMeters`: negative (CoreLocation's "invalid") or non-finite
    ///   → nil.
    /// - `headingDegrees`: negative or non-finite → nil; 360 wraps to 0 so
    ///   the value is always in [0, 360).
    /// - `speedMetersPerSecond`: negative or non-finite → nil.
    static func of(
        latitude: Double,
        longitude: Double,
        timestamp: Date,
        accuracyMeters: Double? = nil,
        headingDegrees: Double? = nil,
        speedMetersPerSecond: Double? = nil
    ) -> LocationFix? {
        guard latitude.isFinite, longitude.isFinite else { return nil }
        let accuracy = accuracyMeters.flatMap { value -> Double? in
            guard value.isFinite, value >= 0 else { return nil }
            return value
        }
        let heading = headingDegrees.flatMap { value -> Double? in
            guard value.isFinite, value >= 0 else { return nil }
            return value.truncatingRemainder(dividingBy: 360)
        }
        let speed = speedMetersPerSecond.flatMap { value -> Double? in
            guard value.isFinite, value >= 0 else { return nil }
            return value
        }
        return LocationFix(
            latitude: latitude,
            longitude: longitude,
            timestamp: timestamp,
            accuracyMeters: accuracy,
            headingDegrees: heading,
            speedMetersPerSecond: speed
        )
    }
}
