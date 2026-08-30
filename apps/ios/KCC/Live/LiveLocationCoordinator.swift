import Foundation
import Observation

/// Outcome of ONE live-location command, returned to the caller — the iOS
/// port of Android's `LiveCommandResult`, so optimistic UI (the wiring PR's
/// map toggle) can resolve a single call without reading the shared status.
enum LiveCommandResult: Sendable {
    /// The callable returned without error.
    case success
    /// The callable failed; nothing was started/stopped.
    case failed
    /// Another command was already in flight, so this one was not issued.
    case busy
}

/// UI-facing status of an in-flight live-location command (start/stop/hide)
/// — Android's `LiveActionStatus`.
enum LiveActionStatus: Equatable, Sendable {
    case idle
    case working
    case failed
}

/// Orchestrates the caller's own live-location session — the iOS port of
/// Android's `LiveLocationCoordinator` plus the publish loop of its
/// `LocationSharingService`, folded into one pure, observable object (no
/// Firebase/SwiftUI types) so the whole lifecycle is unit-testable with a
/// fake repository and ``StubLocationProvider``.
///
/// Responsibilities:
/// - Observes the own session node (``LiveLocationRepository/ownSessionUpdates(uid:)``)
///   — the single source of truth for "am I sharing".
/// - While sharing, consumes ONE ``LocationProvider/fixes()`` stream and
///   publishes qualifying fixes via `live-updatePosition`, throttled by
///   ``LiveShareCadence/shouldPublish(lastSubmittedAt:lastSubmittedLatitude:lastSubmittedLongitude:latitude:longitude:now:)``
///   (movement OR the stationary heartbeat; the first fix always publishes).
/// - Tears the fixes stream down the moment sharing ends — stop, hide-me-now,
///   expiry, or a session node going away. Ending the stream stops the GPS
///   (the ``LocationProvider`` contract), so "stop sharing when the drive
///   ends" is structural, and an unauthorized stream yields nothing at all —
///   fixes flow only while sharing AND authorized.
/// - Derives the ported shell inputs: ``toggleAction`` feeds
///   ``LiveShareToggle/action(isSharing:canShare:wired:)`` and
///   ``manageRows(hasStop:)`` feeds
///   ``LiveManageSheet/actions(isSharing:canShareLive:hasStop:)`` — the rules
///   live THERE (with their own tests); this coordinator only supplies the
///   observed state.
///
/// Privacy: nothing here logs — a log line in this file would carry exact
/// GPS coordinates or the uid, both banned by the parity instructions.
@MainActor
@Observable
final class LiveLocationCoordinator {
    private let repository: LiveLocationRepository?
    private let provider: LocationProvider
    /// Injected wall clock so expiry/cadence decisions are deterministic
    /// under test (Android threads `nowMillis` for the same reason).
    @ObservationIgnored
    private let now: @Sendable () -> Date

    /// The observed own-session node; nil before the first emission and when
    /// no session exists.
    private(set) var session: LiveSessionInfo?

    /// Status of the in-flight command, for button progress/error states.
    private(set) var actionStatus: LiveActionStatus = .idle

    /// Whether the caller may START a session — the LIVE_LOCATION feature
    /// flag (NOT a membership gate: sharing your own position is free, per
    /// the backend's requireActiveActor check). Settable so the wiring PR
    /// can feed the observed flag; defaults to true, matching the flag's
    /// current repo-wide state.
    var canShare: Bool

    /// Whether live sharing is operational in this build: a repository
    /// exists (Firebase configured) and someone is signed in.
    var wired: Bool { repository != nil && uid != nil }

    /// Whether an active, unexpired session is currently sharing — the
    /// observed-session rule (`LiveLocation.isSharing`), not a local flag.
    var isSharing: Bool { LiveLocation.isSharing(session, at: now()) }

    /// What the floating map share toggle should do right now — the input
    /// side of the ported ``LiveShareToggle``.
    var toggleAction: LiveShareAction {
        LiveShareToggle.action(isSharing: isSharing, canShare: canShare, wired: wired)
    }

    /// Which rows the live-share manage sheet shows right now — the input
    /// side of the ported ``LiveManageSheet``. `hasStop` distinguishes the
    /// bottom bar's STOP sheet (true) from the turn-by-turn sheet (false).
    func manageRows(hasStop: Bool) -> LiveManageRows {
        LiveManageSheet.actions(isSharing: isSharing, canShareLive: canShare, hasStop: hasStop)
    }

    @ObservationIgnored
    private var uid: String?
    /// Live tasks. `nonisolated(unsafe)` so the nonisolated deinit can cancel
    /// them — every mutation happens on the main actor, and by the time
    /// deinit runs no other reference exists (the EventsCoordinator pattern).
    @ObservationIgnored
    nonisolated(unsafe) private var sessionSubscription: Task<Void, Never>?
    @ObservationIgnored
    nonisolated(unsafe) private var publishTask: Task<Void, Never>?
    @ObservationIgnored
    nonisolated(unsafe) private var fixDrainTask: Task<Void, Never>?
    @ObservationIgnored
    nonisolated(unsafe) private var expiryWatchdog: Task<Void, Never>?
    /// One watchdog tick — sleeps ``LiveShareCadence/expiryTick`` in
    /// production; injected so tests can tick instantly.
    @ObservationIgnored
    private let expiryTickWait: @Sendable () async throws -> Void

    /// - Parameters:
    ///   - repository: nil when Firebase is not configured in this build —
    ///     the coordinator then stays inert (`wired == false`) and the
    ///     screen renders informational-only.
    ///   - provider: the device-positioning seam. Fixes are only consumed
    ///     while sharing; constructing the coordinator never touches GPS.
    ///   - canShare: the LIVE_LOCATION feature flag.
    ///   - now: test seam for the wall clock.
    ///   - expiryTickWait: test seam for the expiry watchdog's tick — the
    ///     production default sleeps ``LiveShareCadence/expiryTick``.
    init(
        repository: LiveLocationRepository?,
        provider: LocationProvider,
        canShare: Bool = true,
        now: @escaping @Sendable () -> Date = { Date() },
        expiryTickWait: @escaping @Sendable () async throws -> Void = {
            try await Task.sleep(nanoseconds: UInt64(LiveShareCadence.expiryTick * 1_000_000_000))
        }
    ) {
        self.repository = repository
        self.provider = provider
        self.canShare = canShare
        self.now = now
        self.expiryTickWait = expiryTickWait
        self.uid = repository?.currentUserId()
    }

    /// Production wiring: the Firebase repository (nil in config-less
    /// builds) plus the given provider — the factory the screen and the
    /// wiring PR call.
    static func live(provider: LocationProvider) -> LiveLocationCoordinator {
        LiveLocationCoordinator(
            repository: FirebaseLiveLocationRepository.createIfAvailable(),
            provider: provider
        )
    }

    deinit {
        sessionSubscription?.cancel()
        publishTask?.cancel()
        fixDrainTask?.cancel()
        expiryWatchdog?.cancel()
    }

    /// Begins observing the own session on first appearance. Idempotent: a
    /// second call (SwiftUI re-running `.task`) keeps the live subscription.
    /// No-op when unwired.
    func start() {
        guard sessionSubscription == nil, let repository, let uid else { return }
        let stream = repository.ownSessionUpdates(uid: uid)
        sessionSubscription = Task { [weak self] in
            for await session in stream {
                guard !Task.isCancelled, let self else { return }
                self.apply(session)
            }
        }
    }

    // MARK: - Commands

    /// Starts a session for the fixed default window (6h — see
    /// ``LiveLocation/defaultSessionDuration``; no duration is chosen).
    /// Publishing begins when the session echoes back as active.
    @discardableResult
    func startSharing() async -> LiveCommandResult {
        await execute { repository in
            try await repository.startSession(duration: LiveLocation.defaultSessionDuration)
        }
    }

    /// Stops the session (`user_stop`) and removes the marker. The fixes
    /// stream is torn down FIRST so the GPS stops immediately; a failure
    /// reconciles it back while the session is still active, so a marker is
    /// never left going stale by a failed stop.
    @discardableResult
    func stopSharing() async -> LiveCommandResult {
        await execute(tearDownPublishingFirst: true) { repository in
            try await repository.stopSession()
        }
    }

    /// Privacy stop — always offered, never gated (works while suspended
    /// too). Removes the latest marker immediately server-side; locally the
    /// fixes stream is torn down before the call so not one more sample is
    /// published.
    @discardableResult
    func hideMeNow() async -> LiveCommandResult {
        await execute(tearDownPublishingFirst: true) { repository in
            try await repository.hideMeNow()
        }
    }

    /// Clears a failure so the controls are usable again.
    func reset() {
        if actionStatus == .failed {
            actionStatus = .idle
        }
    }

    // MARK: - Internals

    private func apply(_ session: LiveSessionInfo?) {
        self.session = session
        reconcilePublishing()
    }

    /// Aligns the publish loop with the observed session: sharing and no
    /// loop → start one; not sharing → tear it down (which stops the GPS).
    private func reconcilePublishing() {
        if isSharing {
            beginPublishingIfNeeded()
        } else {
            endPublishing()
        }
    }

    private func beginPublishingIfNeeded() {
        guard publishTask == nil, let repository else { return }
        beginExpiryWatchdog()
        // Bounded relay between the provider stream and the publish loop.
        // `updatePosition` awaits the network, and the provider's stream
        // buffers without bound — so while a slow call is in flight, fixes
        // would otherwise pile up in memory for as long as the network
        // stays slow. Only the NEWEST pending fix is worth publishing (an
        // old queued position is exactly what the staleness contract
        // rejects), so the relay keeps one and drops the rest. The drain
        // task never awaits anything but the stream itself, so provider
        // teardown on stop/expiry stays prompt even mid-publish.
        let (relay, relayContinuation) = AsyncStream<LocationFix>.makeStream(
            bufferingPolicy: .bufferingNewest(1)
        )
        let providerStream = provider.fixes()
        fixDrainTask = Task {
            for await fix in providerStream {
                if Task.isCancelled { break }
                relayContinuation.yield(fix)
            }
            relayContinuation.finish()
        }
        let stream = relay
        let clock = now
        publishTask = Task { [weak self] in
            // Last SUBMITTED sample — recorded at dispatch, not on backend
            // confirmation, so a failing backend never retries at the full
            // fix cadence (Android's `shouldPublish` contract).
            var lastSubmittedAt: Date?
            var lastLatitude: Double?
            var lastLongitude: Double?
            for await fix in stream {
                guard !Task.isCancelled, let self else { return }
                // Expiry guard: the session may have run out between session
                // emissions; the next fix after expiry ends the loop (and
                // with it the stream/GPS) instead of publishing.
                guard self.isSharing else {
                    self.endPublishing()
                    return
                }
                let at = clock()
                guard
                    LiveShareCadence.shouldPublish(
                        lastSubmittedAt: lastSubmittedAt,
                        lastSubmittedLatitude: lastLatitude,
                        lastSubmittedLongitude: lastLongitude,
                        latitude: fix.latitude,
                        longitude: fix.longitude,
                        now: at
                    )
                else { continue }
                lastSubmittedAt = at
                lastLatitude = fix.latitude
                lastLongitude = fix.longitude
                // A failed publish is dropped (never logged — the payload is
                // an exact position); the session sweep and the next
                // qualifying fix self-correct.
                try? await repository.updatePosition(LiveCoordinate(fix: fix))
            }
        }
    }

    /// Re-checks the session against the clock on a fixed tick while the
    /// publish loop runs — the iOS analog of Android's `EXPIRY_TICK_MS`
    /// ticker in `LocationSharingService`. The per-fix guard in the publish
    /// loop cannot be the only expiry check: if CoreLocation stops
    /// delivering fixes (deep indoors, airplane mode), the stream would
    /// otherwise stay subscribed — and the GPS demand alive — past expiry.
    private func beginExpiryWatchdog() {
        guard expiryWatchdog == nil else { return }
        let tick = expiryTickWait
        expiryWatchdog = Task { [weak self] in
            while !Task.isCancelled {
                guard (try? await tick()) != nil else { return }
                guard let self, !Task.isCancelled else { return }
                guard self.isSharing else {
                    self.endPublishing()
                    return
                }
            }
        }
    }

    private func endPublishing() {
        // The drain task's cancellation is what terminates the provider
        // stream (and with it the GPS demand) — immediately, even while the
        // publish loop is awaiting a slow network call.
        fixDrainTask?.cancel()
        fixDrainTask = nil
        publishTask?.cancel()
        publishTask = nil
        expiryWatchdog?.cancel()
        expiryWatchdog = nil
    }

    /// One command at a time, mirroring Android's `execute`: `working` while
    /// in flight, `failed` on error, busy-rejected when overlapped.
    private func execute(
        tearDownPublishingFirst: Bool = false,
        _ action: (LiveLocationRepository) async throws -> Void
    ) async -> LiveCommandResult {
        guard actionStatus != .working else { return .busy }
        guard let repository else { return .failed }
        if tearDownPublishingFirst { endPublishing() }
        actionStatus = .working
        do {
            try await action(repository)
            actionStatus = .idle
            return .success
        } catch is CancellationError {
            // The enclosing task was cancelled (view went away) — not a
            // fault to surface; Android rethrows here, Swift's non-throwing
            // command shape reports plain failure with an idle status.
            actionStatus = .idle
            reconcilePublishing()
            return .failed
        } catch {
            // Details may reference the request payload (exact coordinates)
            // — never logged.
            actionStatus = .failed
            // A failed stop/hide leaves the session active: resume
            // publishing so the marker does not silently go stale.
            reconcilePublishing()
            return .failed
        }
    }
}
