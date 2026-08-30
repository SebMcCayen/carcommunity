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
///   live; the last termination stops it.
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
            let wasIdle = fixContinuations.isEmpty
            fixContinuations[id] = continuation
            if wasIdle {
                // First consumer: start the hardware. Deliberately NOT gated
                // on authorization here — starting while unauthorized simply
                // yields nothing (no prompt, no error dialog), and the grant
                // arriving later makes fixes flow without a re-subscribe.
                manager.startUpdatingLocation()
            }
            continuation.onTermination = { [weak self] _ in
                Task { @MainActor in self?.dropFixStream(id) }
            }
        }
    }

    private func dropFixStream(_ id: UUID) {
        guard fixContinuations.removeValue(forKey: id) != nil else { return }
        if fixContinuations.isEmpty {
            // Last consumer gone: stop the hardware. This is what makes
            // "stop sharing when a drive ends" structural — the feature's
            // stream teardown IS the GPS stop.
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
