// The real `CLLocationManager`-backed ``LocationProvider`` — the only file in
// the app that imports CoreLocation, so the framework stays behind the seam
// the way Mapbox stays behind `MapSurface` and Firebase behind the
// repositories.
//
// The iOS counterpart of the platform half of Android's `location/` package
// (`currentLocationAccess` + the fused-provider callback in
// `LocationSharingService`): delegate callbacks become AsyncStream yields,
// and the status matrix becomes ``authorization(from:)``.

import CoreLocation
import Foundation

/// ``LocationProvider`` backed by `CLLocationManager`.
///
/// Main-actor by design: the manager is created on the main runloop and its
/// delegate callbacks are hopped back onto the main actor before touching any
/// state, so the whole provider is single-threaded and lock-free under
/// Swift 6.
///
/// Privacy contract (mobile-platform-parity, "Security and privacy"):
/// - The system dialog is raised ONLY by
///   ``requestWhenInUseAuthorization()`` — constructing the provider,
///   subscribing to authorization changes, and even starting a fix stream
///   never prompt. When-in-use is the only level ever requested; see
///   ``LocationAuthorization/always`` for why there is no Always request.
/// - Positioning hardware runs only while at least one ``fixes()`` stream is
///   live AND the app is authorized; the last termination — or a revocation —
///   stops it, and starting a stream while unauthorized issues no
///   CoreLocation call at all (see ``reconcileUpdates()``).
@MainActor
final class CoreLocationProvider: NSObject, LocationProvider {
    private let manager: CLLocationManager

    private(set) var authorization: LocationAuthorization

    private var authContinuations: [UUID: AsyncStream<LocationAuthorization>.Continuation] = [:]
    private var fixContinuations: [UUID: AsyncStream<LocationFix>.Continuation] = [:]

    override init() {
        let manager = CLLocationManager()
        self.manager = manager
        // The synchronous read is safe (and prompt-free); the delegate keeps
        // it current from here on.
        self.authorization = Self.authorization(from: manager.authorizationStatus)
        super.init()
        manager.desiredAccuracy = kCLLocationAccuracyBest
        // Match the fix cadence intent of Android's fused request for a
        // driving app: continuous updates tuned for road use. Automotive
        // hints the chip's fusion without gating updates to driving.
        manager.activityType = .automotiveNavigation
        manager.delegate = self
    }

    // MARK: - LocationProvider

    func authorizationUpdates() -> AsyncStream<LocationAuthorization> {
        AsyncStream { continuation in
            continuation.yield(authorization)
            let id = UUID()
            authContinuations[id] = continuation
            continuation.onTermination = { [weak self] _ in
                Task { @MainActor in self?.authContinuations[id] = nil }
            }
        }
    }

    func requestWhenInUseAuthorization() {
        // iOS only ever shows the dialog from not-determined; calling in any
        // other state is a silent no-op system-side, and the guard keeps this
        // provider from even appearing to ask (mirrors the stub, and keeps
        // the "denial is fixed in Settings, never by re-asking" rule honest).
        guard authorization == .notDetermined else { return }
        manager.requestWhenInUseAuthorization()
    }

    func fixes() -> AsyncStream<LocationFix> {
        AsyncStream { continuation in
            let id = UUID()
            fixContinuations[id] = continuation
            reconcileUpdates()
            continuation.onTermination = { [weak self] _ in
                Task { @MainActor in self?.dropFixStream(id) }
            }
        }
    }

    private func dropFixStream(_ id: UUID) {
        guard fixContinuations.removeValue(forKey: id) != nil else { return }
        reconcileUpdates()
    }

    /// Whether `startUpdatingLocation()` has been issued without a matching
    /// stop — so start/stop are only ever sent on a real transition.
    private var updatesRunning = false

    /// The ONE place the hardware is started or stopped, recomputed from the
    /// two inputs that may change independently: positioning runs exactly
    /// while (a) at least one ``fixes()`` stream is live AND (b) the app is
    /// authorized.
    ///
    /// Gating on authorization is part of the privacy contract: starting
    /// location updates while not-determined must never happen, so no code
    /// path — not even a subscribed-but-unanswered fix stream — can nudge the
    /// system toward a prompt; only ``requestWhenInUseAuthorization()`` asks.
    /// The gate also means a grant arriving while streams are already live
    /// starts updates then (no re-subscribe needed), and a revocation under a
    /// running feature stops the hardware immediately instead of leaving it
    /// spinning for nothing. Last stream gone → stop, which is what makes
    /// "stop sharing when a drive ends" structural: the feature's stream
    /// teardown IS the GPS stop.
    private func reconcileUpdates() {
        let shouldRun = !fixContinuations.isEmpty && authorization.isAuthorized
        guard shouldRun != updatesRunning else { return }
        updatesRunning = shouldRun
        if shouldRun {
            manager.startUpdatingLocation()
        } else {
            manager.stopUpdatingLocation()
        }
    }

    // MARK: - Status mapping

    /// `CLAuthorizationStatus` → the seam's ``LocationAuthorization``.
    /// `restricted` folds into ``LocationAuthorization/denied``: it cannot be
    /// fixed by asking, which is the property the coordinator branches on.
    /// Static, stateless and nonisolated so the matrix is directly
    /// unit-testable and callable from the nonisolated delegate callbacks.
    nonisolated static func authorization(from status: CLAuthorizationStatus) -> LocationAuthorization {
        switch status {
        case .notDetermined: .notDetermined
        case .restricted, .denied: .denied
        case .authorizedWhenInUse: .whileInUse
        case .authorizedAlways: .always
        @unknown default: .denied
        }
    }

    /// `CLLocation` → ``LocationFix``, through the pure normalizer so
    /// CoreLocation's negative "unknown" sentinels become nils exactly once,
    /// at the seam. Nil for a corrupt (non-finite) coordinate. Nonisolated:
    /// called from the delegate callback before the hop onto the main actor.
    nonisolated static func fix(from location: CLLocation) -> LocationFix? {
        LocationFix.of(
            latitude: location.coordinate.latitude,
            longitude: location.coordinate.longitude,
            timestamp: location.timestamp,
            accuracyMeters: location.horizontalAccuracy,
            headingDegrees: location.course,
            speedMetersPerSecond: location.speed
        )
    }

    // MARK: - Fan-out (main actor)

    private func apply(_ authorization: LocationAuthorization) {
        guard authorization != self.authorization else { return }
        self.authorization = authorization
        // An authorization change can flip whether positioning may run: a
        // grant landing under live streams starts updates, a revocation
        // stops them (see reconcileUpdates()).
        reconcileUpdates()
        for sink in authContinuations.values {
            sink.yield(authorization)
        }
    }

    private func deliver(_ fixes: [LocationFix]) {
        for fix in fixes {
            for sink in fixContinuations.values {
                sink.yield(fix)
            }
        }
    }
}

// Delegate methods are nonisolated (CoreLocation's protocol predates actors);
// each maps its payload to Sendable values first, then hops onto the main
// actor to touch provider state.
extension CoreLocationProvider: CLLocationManagerDelegate {
    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let mapped = Self.authorization(from: manager.authorizationStatus)
        Task { @MainActor in self.apply(mapped) }
    }

    nonisolated func locationManager(
        _ manager: CLLocationManager,
        didUpdateLocations locations: [CLLocation]
    ) {
        let fixes = locations.compactMap(Self.fix(from:))
        guard !fixes.isEmpty else { return }
        Task { @MainActor in self.deliver(fixes) }
    }

    nonisolated func locationManager(
        _ manager: CLLocationManager,
        didFailWithError error: Error
    ) {
        // kCLErrorDenied (permission revoked / services switched off) also
        // fires the authorization callback, which is the channel consumers
        // actually watch; transient fix failures (kCLErrorLocationUnknown)
        // resolve themselves and CoreLocation keeps trying. Nothing to
        // publish — and per the parity analytics rules, nothing (least of all
        // a position) is logged.
    }
}
