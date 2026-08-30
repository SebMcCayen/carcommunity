// The seam between the app and device positioning, kept free of CoreLocation
// so everything above it — the permission coordinator now; the map puck,
// drive recording and live sharing later — compiles, unit-tests and passes CI
// without a device, GPS or a simulator (the StubMapSurface philosophy applied
// to location).
//
// The iOS counterpart of the seam Android's `location/` package puts between
// features and the fused location provider: `currentLocationAccess` +
// permission launchers on one side, the `LocationSharingService`'s fix
// callback on the other. Kotlin's `StateFlow`s become "current value +
// AsyncStream that yields it first" (the AuthRepository idiom), and the fix
// callback becomes a stream whose termination IS the stop call.
//
// @MainActor like the `MapSurface` seam: `CLLocationManager` wants to live on
// one runloop, the consumers (SwiftUI-facing coordinators) are main-actor
// anyway, and a lock-free single-actor provider is the simplest thing that is
// correct under Swift 6.

import Foundation
import Observation

/// Device positioning, behind a CoreLocation-free protocol.
///
/// The production implementation is ``CoreLocationProvider``;
/// ``StubLocationProvider`` compiles everywhere and stands in for tests and
/// config-less/CI builds, exactly as `StubMapSurface` does for the map.
///
/// The privacy contract of this seam (mobile-platform-parity, "Security and
/// privacy"):
/// - NOTHING here asks the user for permission implicitly. The one and only
///   trigger is ``requestWhenInUseAuthorization()``, and callers invoke it
///   only from an explicit user action, AFTER explaining why — that ordering
///   is owned by ``LocationPermissionCoordinator``.
/// - Fixes flow only while someone is consuming a ``fixes()`` stream;
///   terminating the stream stops the hardware. "Stop sharing when a drive
///   ends" is therefore structural: ending the feature tears down its stream.
@MainActor
protocol LocationProvider: AnyObject {
    /// The app's current authorization, as last reported by the platform.
    var authorization: LocationAuthorization { get }

    /// Authorization changes. Each call returns a fresh stream that yields
    /// the CURRENT value immediately and then every change (the
    /// `AuthRepository.authStateUpdates()` contract), so a consumer never
    /// races the read-then-subscribe gap. Terminating the stream (dropping
    /// the iteration) detaches it.
    func authorizationUpdates() -> AsyncStream<LocationAuthorization>

    /// Raise the system when-in-use permission dialog.
    ///
    /// The ONLY way the app ever asks. A no-op unless
    /// ``authorization`` is ``LocationAuthorization/notDetermined`` — iOS
    /// never re-presents the dialog after a denial, so calling this in any
    /// other state must not pretend otherwise. The outcome arrives through
    /// ``authorizationUpdates()``.
    func requestWhenInUseAuthorization()

    /// Start a stream of position fixes.
    ///
    /// Device positioning runs exactly while at least one stream is live AND
    /// the app is authorized: the first authorized stream starts it,
    /// terminating the last one — or a revocation — stops it. The stream's
    /// lifecycle IS the start/stop control, so a feature that goes away
    /// cannot leave the GPS running. While ``authorization`` is not granted
    /// a stream yields nothing and no positioning call is made at all
    /// (starting a stream never triggers a permission prompt); a grant
    /// arriving later makes fixes flow without a re-subscribe.
    func fixes() -> AsyncStream<LocationFix>
}

/// ``LocationProvider`` with no device or CoreLocation dependency — the
/// CI/config-less/test implementation, mirroring ``StubMapSurface``'s role
/// for the map seam. Deterministic and scriptable: tests drive authorization
/// with ``setAuthorization(_:)``, script the outcome of a permission request
/// with ``scriptRequestOutcome(_:)``, and push fixes with ``emitFix(_:)``.
///
/// `@Observable` so future SwiftUI chrome can watch it directly, like the
/// map stub.
@Observable
@MainActor
final class StubLocationProvider: LocationProvider {
    private(set) var authorization: LocationAuthorization

    /// How many times ``requestWhenInUseAuthorization()`` actually raised the
    /// (simulated) dialog — used by tests to assert the coordinator asks
    /// exactly once, and only after the rationale.
    private(set) var whenInUseRequestCount = 0

    /// How many ``fixes()`` streams are currently being consumed — the
    /// DEMAND side of positioning, observable so tests can assert a
    /// feature's teardown really released its stream. Deliberately not "is
    /// the GPS running": in the real provider hardware runs only while
    /// demand exists AND the app is authorized (see
    /// ``CoreLocationProvider``), so an unauthorized consumer shows up here
    /// while running no hardware — exactly the case tests need to see.
    private(set) var activeFixStreamCount = 0

    /// What a permission request resolves to, or nil to leave the simulated
    /// dialog pending (the default — tests then observe the in-flight state
    /// and resolve it themselves via ``setAuthorization(_:)``).
    @ObservationIgnored
    private var requestOutcome: LocationAuthorization?

    @ObservationIgnored
    private var authContinuations: [UUID: AsyncStream<LocationAuthorization>.Continuation] = [:]

    @ObservationIgnored
    private var fixContinuations: [UUID: AsyncStream<LocationFix>.Continuation] = [:]

    init(authorization: LocationAuthorization = .notDetermined) {
        self.authorization = authorization
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
        // Mirror the real provider: the dialog only exists before the first
        // answer.
        guard authorization == .notDetermined else { return }
        whenInUseRequestCount += 1
        if let outcome = requestOutcome {
            setAuthorization(outcome)
        }
    }

    func fixes() -> AsyncStream<LocationFix> {
        AsyncStream { continuation in
            let id = UUID()
            fixContinuations[id] = continuation
            activeFixStreamCount += 1
            continuation.onTermination = { [weak self] _ in
                Task { @MainActor in
                    guard let self, self.fixContinuations.removeValue(forKey: id) != nil else {
                        return
                    }
                    self.activeFixStreamCount -= 1
                }
            }
        }
    }

    // MARK: - Scripting hooks (tests / config-less builds)

    /// Sets the authorization and notifies every live
    /// ``authorizationUpdates()`` stream — how a test plays the user's answer
    /// in the system dialog, or a Settings-app change while the app runs.
    func setAuthorization(_ authorization: LocationAuthorization) {
        self.authorization = authorization
        for sink in authContinuations.values {
            sink.yield(authorization)
        }
    }

    /// Scripts what a future ``requestWhenInUseAuthorization()`` resolves to.
    /// Pass nil (the default) to leave the simulated dialog pending instead.
    func scriptRequestOutcome(_ outcome: LocationAuthorization?) {
        requestOutcome = outcome
    }

    /// Pushes a fix to every live ``fixes()`` stream — dropped entirely
    /// while unauthorized, because the protocol promises an unauthorized
    /// stream yields nothing and the real provider cannot deliver a fix
    /// then. The stub enforcing the same rule keeps consumer tests from
    /// passing under behavior production can never produce.
    func emitFix(_ fix: LocationFix) {
        guard authorization.isAuthorized else { return }
        for sink in fixContinuations.values {
            sink.yield(fix)
        }
    }
}
