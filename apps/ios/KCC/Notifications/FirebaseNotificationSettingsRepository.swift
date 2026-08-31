import FirebaseCore
import FirebaseFirestore
import Foundation

/// ``NotificationSettingsRepository`` backed by an owner Firestore
/// listener/update on `userPrivate/{uid}` — the iOS port of Android's
/// `FirebaseNotificationSettingsRepository`.
///
/// Preferences are a DIRECT rules-validated write (no callable): the
/// `notificationPreferences` map plus an `updatedAt` server timestamp. Reads
/// degrade to ``NotificationPreferences/allEnabled`` on an absent doc or a
/// transient error — a missing map means "nothing opted out", not an error.
///
/// Construction is guarded (``createIfAvailable()`` returns nil without
/// Firebase config), mirroring ``FirebaseEventsRepository``.
final class FirebaseNotificationSettingsRepository: NotificationSettingsRepository, @unchecked Sendable {
    private let firestore: Firestore

    private init(firestore: Firestore) {
        self.firestore = firestore
    }

    func preferences(uid: String) -> AsyncStream<NotificationSettingsSnapshot> {
        let document = firestore.collection(Self.userPrivateCollection).document(uid)
        return AsyncStream { continuation in
            let registration = document.addSnapshotListener { snapshot, _ in
                // A listener error can arrive WITH a usable cached snapshot; in
                // that case decode the snapshot rather than flip the UI back to
                // "all enabled", so a transient error never makes a user's
                // saved opt-outs appear cleared. Fall back to defaults (all
                // enabled — Android's `observePreferences` fallback) only when
                // there is no snapshot at all.
                // NOTE: Android's observePreferences resets to defaults on any
                // error; this is the more robust behavior and Android should
                // follow (cross-platform follow-up).
                guard let snapshot else {
                    continuation.yield(.loaded(.allEnabled))
                    return
                }
                let raw = snapshot.get(Self.preferencesField) as? [String: Any]
                continuation.yield(.loaded(NotificationPreferences.fromFirestore(raw)))
            }
            let box = ListenerBox(registration: registration)
            continuation.onTermination = { _ in
                box.registration.remove()
            }
        }
    }

    func savePreferences(uid: String, preferences: NotificationPreferences) async throws {
        let update: [String: Any] = [
            Self.preferencesField: preferences.toFirestoreMap(),
            Self.updatedAtField: FieldValue.serverTimestamp(),
        ]
        do {
            // `updateData` mirrors Android's `.update(update)`: a targeted
            // field write on the existing owner doc (rules-validated), not a
            // whole-document overwrite.
            try await firestore
                .collection(Self.userPrivateCollection)
                .document(uid)
                .updateData(update)
        } catch {
            // Bare status name only — the same PII-safe rule as the events RSVP
            // write (the SDK message embeds the document path).
            throw NotificationSettingsWriteError(
                code: FirebaseEventsRepository.firestoreStatusName(error)
            )
        }
    }

    // MARK: - Factory

    private static let userPrivateCollection = "userPrivate"
    private static let preferencesField = "notificationPreferences"
    private static let updatedAtField = "updatedAt"

    private static let cachedLock = NSLock()
    nonisolated(unsafe) private static var cached: FirebaseNotificationSettingsRepository?

    /// Returns the process-wide repository when Firebase is configured for
    /// this build, or nil when GoogleService-Info.plist is absent. Points the
    /// Firestore SDK at the emulator when `FIREBASE_FIRESTORE_EMULATOR_HOST`
    /// is set — the same seam ``FirebaseEventsRepository/createIfAvailable()``
    /// provides.
    static func createIfAvailable() -> NotificationSettingsRepository? {
        guard FirebaseApp.app() != nil else { return nil }
        cachedLock.lock()
        defer { cachedLock.unlock() }
        if let cached { return cached }
        let firestore = Firestore.firestore()
        // The host check makes a second application of the SAME emulator
        // settings a no-op instead of mutating settings after Firestore has
        // already started (`Firestore.firestore()` is a shared singleton, and
        // other repositories' `createIfAvailable()` factories apply the same
        // settings) — the same guard `FirebaseVehiclesRepository` /
        // `FirebaseUserProfileRepository` use.
        if let emulator = FirebaseEmulatorHost.parse(
            ProcessInfo.processInfo.environment["FIREBASE_FIRESTORE_EMULATOR_HOST"]
        ), firestore.settings.host != "\(emulator.host):\(emulator.port)" {
            firestore.useEmulator(withHost: emulator.host, port: emulator.port)
        }
        let repository = FirebaseNotificationSettingsRepository(firestore: firestore)
        cached = repository
        return repository
    }
}

/// `ListenerRegistration` is not Sendable; the `onTermination` closure only
/// removes the listener (thread-safe), so the wrapper is sound — the same
/// pattern ``FirebaseEventsRepository`` uses.
private struct ListenerBox: @unchecked Sendable {
    let registration: ListenerRegistration
}
