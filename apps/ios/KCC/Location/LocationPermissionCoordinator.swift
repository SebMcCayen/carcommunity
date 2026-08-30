// Pure orchestration of the location ask-flow: idle → rationale → requesting
// → granted / denied-needs-settings, driven entirely through the
// ``LocationProvider`` seam so the whole machine unit-tests against
// ``StubLocationProvider``.
//
// This is where two parity rules live (mobile-platform-parity, "Security and
// privacy"):
// - "Request permissions only when the related feature is used": nothing
//   happens until a feature calls ``requestAccess()`` from an explicit user
//   action. Constructing or starting the coordinator never prompts.
// - "Explain why location ... permission is required": from not-determined,
//   ``requestAccess()`` lands in ``LocationPermissionFlowState/rationale``
//   FIRST — the UI shows the why (the `map.locationNeededTitle` /
//   `map.locationPermissionBody` strings Android's `LocationAccessPrompt`
//   uses) — and only ``proceedFromRationale()`` raises the system dialog.
//   The one-shot system prompt is never spent on a user who has not read
//   what it is for.
//
// The iOS counterpart of Android's `LocationAccess` +
// `locationPermissionRemedy` decision layer. Deliberate deviation, documented
// per the parity instructions: Android must disambiguate "ask again" from
// "don't-ask-again → Settings" (`LocationPermissionRemedy`), because its
// dialog can sometimes be re-raised. On iOS the dialog exists exactly once —
// after any denial the ONLY remedy is the app's Settings page — so the remedy
// type collapses into the single ``LocationPermissionFlowState/deniedNeedsSettings``
// state (the settings-hint), and there is no request-again branch to port.

import Foundation
import Observation

/// Where the ask-flow currently stands — what the UI renders.
enum LocationPermissionFlowState: Equatable, Sendable {
    /// Nothing in flight and no grant: either the feature has not asked yet,
    /// or the user dismissed the flow ("Not now"). The UI shows nothing.
    case idle

    /// The feature needs location and the user has never been asked: show
    /// the WHY (rationale) first. ``LocationPermissionCoordinator/proceedFromRationale()``
    /// continues to the system dialog;
    /// ``LocationPermissionCoordinator/dismissRationale()`` backs out.
    case rationale

    /// The system dialog has been raised; waiting for the user's answer.
    case requesting

    /// Location is authorized (while-in-use or always). The feature may
    /// start consuming ``LocationProvider/fixes()``.
    case granted

    /// The app has been denied and iOS will never re-show the dialog: the
    /// only remedy is the app's page in the Settings app, so the UI shows
    /// the settings-hint (`map.locationOpenSettings`).
    /// ``LocationPermissionCoordinator/dismissSettingsHint()`` clears it.
    case deniedNeedsSettings
}

/// Drives ``LocationPermissionFlowState`` from user intents on one side and
/// ``LocationProvider`` authorization changes on the other.
///
/// Pure Swift (no CoreLocation/SwiftUI) and `@MainActor` `@Observable`, the
/// ``EventsCoordinator`` idiom: SwiftUI watches `state`, tests drive the stub
/// provider.
@MainActor
@Observable
final class LocationPermissionCoordinator {
    private let provider: LocationProvider

    /// The live authorization-watching task. `nonisolated(unsafe)` so the
    /// nonisolated deinit can cancel it — every mutation happens on the main
    /// actor, and by the time deinit runs no other reference exists, so the
    /// unguarded access cannot race (same pattern as ``EventsCoordinator``).
    @ObservationIgnored
    nonisolated(unsafe) private var subscription: Task<Void, Never>?

    private(set) var state: LocationPermissionFlowState = .idle

    init(provider: LocationProvider) {
        self.provider = provider
    }

    deinit {
        subscription?.cancel()
    }

    /// Begins watching authorization on first appearance. Idempotent — a
    /// re-run `.task` keeps the live subscription. An ALREADY-granted app
    /// (returning user) folds straight to ``LocationPermissionFlowState/granted``
    /// via the stream's immediate first yield; a not-yet-asked or denied app
    /// stays ``LocationPermissionFlowState/idle`` — starting is not asking.
    func start() {
        guard subscription == nil else { return }
        let stream = provider.authorizationUpdates()
        subscription = Task { [weak self] in
            for await authorization in stream {
                guard !Task.isCancelled, let self else { return }
                self.apply(authorization)
            }
        }
    }

    /// A feature that needs location was just used (the user tapped
    /// recenter, started a drive, opened live sharing…). THE entry point of
    /// the ask-flow — and the only place it can begin, which is what keeps
    /// "request only when the feature is used" true by construction.
    func requestAccess() {
        // Re-entrancy guard: while the system dialog is in flight the
        // provider still reads .notDetermined, so falling through would
        // regress .requesting back to .rationale mid-prompt. The in-flight
        // request resolves via apply(_:) when the user answers.
        guard state != .requesting else { return }
        switch provider.authorization {
        case .whileInUse, .always:
            state = .granted
        case .notDetermined:
            // Explain first. The system dialog is raised only from
            // proceedFromRationale().
            state = .rationale
        case .denied:
            state = .deniedNeedsSettings
        }
    }

    /// The user read the rationale and chose to continue: raise the system
    /// dialog. Only valid from ``LocationPermissionFlowState/rationale``.
    func proceedFromRationale() {
        guard state == .rationale else { return }
        state = .requesting
        provider.requestWhenInUseAuthorization()
    }

    /// The user backed out of the rationale ("Not now"): the flow ends, no
    /// dialog is spent, and the feature can ask again another day.
    func dismissRationale() {
        guard state == .rationale else { return }
        state = .idle
    }

    /// The user dismissed the settings-hint. The denial stands; asking the
    /// feature again will show the hint again.
    func dismissSettingsHint() {
        guard state == .deniedNeedsSettings else { return }
        state = .idle
    }

    /// Folds a provider authorization change into the flow. Handles BOTH
    /// answers to our own dialog and outside changes (the Settings app, a
    /// profile restriction landing) while the app runs.
    private func apply(_ authorization: LocationAuthorization) {
        switch authorization {
        case .whileInUse, .always:
            // A grant is a grant no matter how it arrived (our dialog, or
            // the user flipping the switch in Settings mid-hint).
            state = .granted

        case .denied:
            switch state {
            case .idle:
                // Do not nag: a denial that lands while no feature is asking
                // (fresh start of a previously-denied app) stays quiet until
                // the feature is used again — requestAccess() will surface
                // the settings-hint then.
                break
            case .requesting, .rationale, .granted, .deniedNeedsSettings:
                // Our dialog was answered "don't allow", a stale
                // not-determined read got corrected, or a live grant was
                // revoked under a running feature — in every case the honest
                // next step is the Settings hint.
                state = .deniedNeedsSettings
            }

        case .notDetermined:
            switch state {
            case .granted, .deniedNeedsSettings:
                // The user reset the permission (Settings → reset warnings /
                // app reinstall semantics): back to square one, quietly.
                state = .idle
            case .idle, .rationale, .requesting:
                // Nothing to change: idle stays idle, a rationale is still
                // valid, and while requesting the status legitimately reads
                // not-determined until the user answers the dialog.
                break
            }
        }
    }
}
