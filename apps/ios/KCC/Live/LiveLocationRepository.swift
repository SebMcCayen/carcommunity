import Foundation

/// Live-location session and authorized viewer operations — the iOS port of
/// Android's `live/LiveLocationRepository.kt`. Firebase-free protocol so the
/// coordinators and screens are unit-testable with fakes.
///
/// The write/read split mirrors Android exactly: every WRITE flows through
/// the `live.*` callables (functions/src/live/session.ts — grouped exports
/// `live-startSession` / `live-updatePosition` / `live-stopSession` /
/// `live-hideMeNow`). Sharing your OWN position is free — the callables
/// require an authenticated, non-suspended caller plus the liveLocation
/// feature flag, NOT an active membership (`hideMeNow` works even while
/// suspended; only VIEWING others is the paid surface); the RTDB nodes under
/// `liveLocation/{uid}` are backend-written and clients only ever READ them
/// (firebase/database.rules.json grants no client write there at all).
///
/// Session state is observed per-owner (``ownSessionUpdates(uid:)``). Map
/// awareness also reads explicit, backend-authorized member markers one uid at
/// a time and resolves their Storage image paths. Collection scans and nearby
/// discovery remain outside this protocol.
protocol LiveLocationRepository: AnyObject, Sendable {
    /// `live-startSession` — (re)starts the caller's session with a duration.
    ///
    /// `vehicleId` is the garage car to denormalize onto the session (the car
    /// picked in Android's "Start driving" popup); nil lets the server fall
    /// back to the caller's main car (then first car, then none). The iOS
    /// car picker arrives with the garage slice — callers pass nil today.
    func startSession(duration: LiveSessionDuration, vehicleId: String?) async throws

    /// `live-updatePosition` — publishes one sample (requires an active
    /// session; the backend enforces the contract's 60-second staleness
    /// threshold on `recordedAt`).
    func updatePosition(_ coordinate: LiveCoordinate) async throws

    /// `live-stopSession` — stops sharing and removes the marker immediately
    /// (reason `user_stop`).
    func stopSession() async throws

    /// `live-hideMeNow` — privacy stop; always available, even while
    /// suspended. Stops the session, removes the latest marker AND deletes
    /// the nearby-discovery doc at once.
    func hideMeNow() async throws

    /// Live view of the caller's own session node at
    /// `liveLocation/{uid}/session` (owner-only read); emits nil when none.
    /// Each call returns a fresh stream backed by its own RTDB listener;
    /// terminating the stream detaches the listener.
    func ownSessionUpdates(uid: String) -> AsyncStream<LiveSessionInfo?>

    /// Live view of one authorized sharer's latest marker. Callers subscribe
    /// only to backend-provided convoy member uids; collection scans are never
    /// attempted. A missing, malformed or denied value emits nil.
    func latestUpdates(uid: String) -> AsyncStream<LiveMarker?>

    /// Live marker stream plus retryable termination signals for per-uid
    /// convoy awareness subscriptions.
    func latestUpdateEvents(uid: String) -> AsyncStream<LiveMarkerUpdateEvent>

    /// Resolves a Storage path carried by a live marker for map identity.
    func imageDownloadURL(for imagePath: String) async -> URL?

    /// The signed-in user's uid, or nil with no session. Answered by the
    /// repository — which already owns the Firebase seam — so the live
    /// feature stays self-contained, exactly like ``EventsRepository``.
    func currentUserId() -> String?
}

extension LiveLocationRepository {
    /// Starts a session with the server choosing the car (no picker on iOS
    /// yet — see ``startSession(duration:vehicleId:)``).
    func startSession(duration: LiveSessionDuration) async throws {
        try await startSession(duration: duration, vehicleId: nil)
    }

    /// Keeps existing focused fakes source-compatible; viewer tests can
    /// override this with a scripted stream.
    func latestUpdates(uid: String) -> AsyncStream<LiveMarker?> {
        AsyncStream { $0.finish() }
    }

    func latestUpdateEvents(uid: String) -> AsyncStream<LiveMarkerUpdateEvent> {
        let stream = latestUpdates(uid: uid)
        return AsyncStream(bufferingPolicy: .bufferingNewest(1)) { continuation in
            let task = Task {
                for await marker in stream {
                    if Task.isCancelled { break }
                    continuation.yield(.value(marker))
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    func imageDownloadURL(for imagePath: String) async -> URL? { nil }
}

enum LiveMarkerUpdateEvent: Sendable {
    case value(LiveMarker?)
    case retry
}
