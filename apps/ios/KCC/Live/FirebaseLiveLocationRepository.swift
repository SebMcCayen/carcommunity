import FirebaseAuth
import FirebaseCore
import FirebaseDatabase
import FirebaseStorage
import Foundation

/// ``LiveLocationRepository`` backed by the `live.*` callables and a Realtime
/// Database listener on the caller's own session node — the iOS port of
/// Android's `live/FirebaseLiveLocationRepository.kt` (own-session slice).
///
/// The split is Android's exactly: WRITES go through the callables
/// (`live-startSession` / `live-updatePosition` / `live-stopSession` /
/// `live-hideMeNow`, all in europe-west1 via ``KccFunctionsClient``); the
/// RTDB `liveLocation/{uid}` nodes are backend-written, and this class only
/// READS the owner's session node (owner-only read —
/// firebase/database.rules.json).
///
/// Callable failures surface as ``KccFunctionsError`` (contract code only —
/// never the SDK message, which may reference the request payload; per the
/// privacy rules a payload here contains exact GPS coordinates, so nothing
/// from a failure is ever logged). Construction is guarded
/// (``createIfAvailable()`` returns nil without Firebase config), mirroring
/// the other repositories and Android's `createIfAvailable`.
final class FirebaseLiveLocationRepository: LiveLocationRepository, @unchecked Sendable {
    private let functions: KccFunctionsClient
    private let database: Database
    private let storage: Storage

    private init(functions: KccFunctionsClient, database: Database, storage: Storage) {
        self.functions = functions
        self.database = database
        self.storage = storage
    }

    // MARK: - Callables (writes)

    func startSession(duration: LiveSessionDuration, vehicleId: String?) async throws {
        var payload: [String: Any] = ["duration": duration.key]
        // Only send the car when one was chosen; omitting it lets the server
        // fall back to the main car (the callable schema treats a blank
        // string as invalid, so never send an empty one) — Android parity.
        if let vehicleId, !vehicleId.isEmpty {
            payload["vehicleId"] = vehicleId
        }
        _ = try await functions.call(Self.startSessionCallable, payload: payload)
    }

    func updatePosition(_ coordinate: LiveCoordinate) async throws {
        var coord: [String: Any] = [
            "latitude": coordinate.latitude,
            "longitude": coordinate.longitude,
            "recordedAt": coordinate.recordedAtIso,
        ]
        if let accuracy = coordinate.accuracyMeters { coord["accuracyMeters"] = accuracy }
        if let heading = coordinate.headingDegrees { coord["headingDegrees"] = heading }
        if let speed = coordinate.speedMetersPerSecond { coord["speedMetersPerSecond"] = speed }
        _ = try await functions.call(Self.updatePositionCallable, payload: ["coordinate": coord])
    }

    func stopSession() async throws {
        _ = try await functions.call(Self.stopSessionCallable, payload: ["reason": "user_stop"])
    }

    func hideMeNow() async throws {
        _ = try await functions.call(Self.hideMeNowCallable, payload: [:])
    }

    // MARK: - RTDB (own-session read)

    func ownSessionUpdates(uid: String) -> AsyncStream<LiveSessionInfo?> {
        let ref = database.reference(withPath: "liveLocation/\(uid)/session")
        return AsyncStream(bufferingPolicy: .bufferingNewest(1)) { continuation in
            let handle = ref.observe(
                .value,
                with: { snapshot in
                    let map = snapshot.value as? [String: Any]
                    continuation.yield(map.flatMap(LiveSessionInfo.fromMap))
                },
                withCancel: { _ in
                    // Read denied/interrupted: surface "no session" instead
                    // of hanging; a later successful read self-corrects
                    // (Android's onCancelled posture). The error is never
                    // logged — it can embed the database path with the uid.
                    continuation.yield(nil)
                }
            )
            let box = ObserverBox(reference: ref)
            box.setHandle(handle)
            continuation.onTermination = { _ in
                box.removeObserverOnce()
            }
        }
    }

    func latestUpdates(uid: String) -> AsyncStream<LiveMarker?> {
        let events = latestUpdateEvents(uid: uid)
        return AsyncStream(bufferingPolicy: .bufferingNewest(1)) { continuation in
            let task = Task {
                for await event in events {
                    if Task.isCancelled { break }
                    guard case let .value(marker) = event else { continue }
                    continuation.yield(marker)
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    func latestUpdateEvents(uid: String) -> AsyncStream<LiveMarkerUpdateEvent> {
        let ref = database.reference(withPath: "liveLocation/\(uid)/latest")
        return AsyncStream(bufferingPolicy: .bufferingNewest(1)) { continuation in
            var handle: DatabaseHandle?
            let box = ObserverBox(reference: ref)
            handle = ref.observe(
                .value,
                with: { snapshot in
                    let map = snapshot.value as? [String: Any]
                    continuation.yield(.value(map.flatMap { LiveMarker.fromMap(uid: uid, $0) }))
                },
                withCancel: { error in
                    box.removeObserverOnce()
                    if LiveRealtimeRetryPolicy.shouldRetry(error) {
                        continuation.yield(.retry)
                    } else {
                        continuation.yield(.value(nil))
                    }
                    continuation.finish()
                }
            )
            box.setHandle(handle)
            continuation.onTermination = { _ in
                box.removeObserverOnce()
            }
        }
    }

    func imageDownloadURL(for imagePath: String) async -> URL? {
        try? await storage.reference(withPath: imagePath).downloadURL()
    }

    func currentUserId() -> String? {
        Auth.auth().currentUser?.uid
    }

    // MARK: - Factory

    // Grouped-export callable names — suffixed so call sites read as
    // "invoke the named callable", not as recursion into the instance
    // methods they back.
    private static let startSessionCallable = "live-startSession"
    private static let updatePositionCallable = "live-updatePosition"
    private static let stopSessionCallable = "live-stopSession"
    private static let hideMeNowCallable = "live-hideMeNow"

    private static let cachedLock = NSLock()
    nonisolated(unsafe) private static var cached: FirebaseLiveLocationRepository?

    /// Returns the process-wide repository when Firebase is configured for
    /// this build, or nil when GoogleService-Info.plist is absent (CI, local
    /// validation builds — see apps/ios/README.md).
    ///
    /// Emulator seams follow the shared `FIREBASE_*_EMULATOR_HOST`
    /// convention: `FIREBASE_DATABASE_EMULATOR_HOST` (port 9000 per
    /// firebase.json) points the Realtime Database SDK at the emulator
    /// before first use; the callables ride ``KccFunctionsClient``'s own
    /// `FIREBASE_FUNCTIONS_EMULATOR_HOST` seam, while
    /// `FIREBASE_STORAGE_EMULATOR_HOST` resolves convoy car photos.
    static func createIfAvailable() -> LiveLocationRepository? {
        guard FirebaseApp.app() != nil else { return nil }
        cachedLock.lock()
        defer { cachedLock.unlock() }
        if let cached { return cached }
        guard let functions = KccFunctionsClient.createIfAvailable() else { return nil }
        let database: Database
        if let emulator = FirebaseEmulatorHost.parse(
            ProcessInfo.processInfo.environment["FIREBASE_DATABASE_EMULATOR_HOST"]
        ) {
            // Point at the emulator's namespace-less local instance; must
            // happen before the instance hands out references.
            database = Database.database()
            database.useEmulator(withHost: emulator.host, port: emulator.port)
        } else {
            database = Database.database()
        }
        let storage = Storage.storage()
        if let emulator = FirebaseEmulatorHost.parse(
            ProcessInfo.processInfo.environment["FIREBASE_STORAGE_EMULATOR_HOST"]
        ) {
            storage.useEmulator(withHost: emulator.host, port: emulator.port)
        }
        let repository = FirebaseLiveLocationRepository(
            functions: functions,
            database: database,
            storage: storage
        )
        cached = repository
        return repository
    }
}

/// `DatabaseReference`/handle pair carried into the stream's `onTermination`
/// closure, which must be Sendable — all it does is remove the observer,
/// which the Database SDK documents as thread-safe (same pattern as the
/// Firestore `ListenerBox`es).
private final class ObserverBox: @unchecked Sendable {
    let reference: DatabaseReference
    private let lock = NSLock()
    private var handle: DatabaseHandle?

    init(reference: DatabaseReference) {
        self.reference = reference
    }

    func setHandle(_ handle: DatabaseHandle?) {
        lock.lock()
        self.handle = handle
        lock.unlock()
    }

    func removeObserverOnce() {
        lock.lock()
        let handle = self.handle
        self.handle = nil
        lock.unlock()
        if let handle {
            reference.removeObserver(withHandle: handle)
        }
    }
}

private enum LiveRealtimeRetryPolicy {
    private static let operationFailedCode = -2
    private static let disconnectedCode = -4
    private static let networkErrorCode = -24
    private static let accessFragments = ["permission", "denied", "unauthor", "access"]

    static func shouldRetry(_ error: Error) -> Bool {
        let nsError = error as NSError
        let details = [
            nsError.localizedDescription,
            nsError.localizedFailureReason,
            nsError.userInfo[NSDebugDescriptionErrorKey] as? String
        ]
        .compactMap { $0?.lowercased() }
        if details.contains(where: { detail in
            accessFragments.contains { detail.contains($0) }
        }) {
            return false
        }
        return nsError.code == disconnectedCode
            || nsError.code == networkErrorCode
            || nsError.code == operationFailedCode
    }
}
