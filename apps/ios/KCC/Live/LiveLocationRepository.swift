import Foundation

/// Live-location session operations — the iOS port of Android's
/// `live/LiveLocationRepository.kt`, restricted to the OWN-session slice.
/// Firebase-free protocol so the coordinator and screen are unit-testable
/// with fakes.
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
/// Session state is observed per-owner (``ownSessionUpdates(uid:)``). The
/// viewer-side reads — `observeLatest` per-uid markers and the
/// `live-listNearby` discovery — are deliberately absent: they belong to the
/// map-layer slice that renders OTHER members, together with waves
/// (`live.sendWave`) and presence. This slice is the sharer's own session.
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
}
