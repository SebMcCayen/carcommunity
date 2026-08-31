import FirebaseAuth
import FirebaseCore
import FirebaseFirestore
import Foundation

/// ``BadgesRepository`` backed by Cloud Firestore + the callable seam — the
/// iOS port of Android's `FirebaseBadgesRepository` (earned listener) and
/// `FirebaseBadgeProgressRepository` (progress callable).
///
/// EARNED badges are read with a snapshot listener on `users/{uid}/badges` —
/// PUBLIC read for any authenticated user, not owner-only
/// (`firebase/firestore.rules`), since an award is a trophy meant to be shown
/// off; this repository just happens to always be pointed at the signed-in
/// member's own uid for the own-profile wall. A listener failure surfaces as
/// ``BadgesSnapshot/failed(code:)`` carrying only the bare Firestore status
/// name, never a silently empty list. PROGRESS counters, by contrast, come
/// from the genuinely owner-only `badges-getMyProgress` callable via
/// ``KccFunctionsClient`` (europe-west1) — the backend-only
/// `badgeProgress/{uid}` document is denied to every client, owner included;
/// any callable failure degrades to nil counters, which the wall renders as
/// goals without bars.
///
/// Construction is guarded (``createIfAvailable()`` returns nil without
/// Firebase config), mirroring ``FirebaseEventsRepository`` /
/// ``FirebaseVehiclesRepository`` and Android's `createIfAvailable`.
final class FirebaseBadgesRepository: BadgesRepository, @unchecked Sendable {
    private let firestore: Firestore
    private let functions: KccFunctionsClient

    private init(firestore: Firestore, functions: KccFunctionsClient) {
        self.firestore = firestore
        self.functions = functions
    }

    func observeBadges(uid: String) -> AsyncStream<BadgesSnapshot> {
        let collection =
            firestore
            .collection(Self.usersCollection)
            .document(uid)
            .collection(Self.badgesCollection)
        return AsyncStream { continuation in
            let registration = collection.addSnapshotListener { snapshot, error in
                if let error {
                    // Bare status name only (never the exception text, which
                    // can embed the document path and the project id) — see
                    // BadgesSnapshot.failed and FirebaseEventsRepository.
                    continuation.yield(.failed(code: FirebaseEventsRepository.firestoreStatusName(error)))
                    return
                }
                let badges = (snapshot?.documents ?? []).map { document in
                    Badge.fromMap(id: document.documentID, map: Self.badgeFields(from: document))
                }
                continuation.yield(.loaded(Badges.sortedForList(badges)))
            }
            let box = ListenerBox(registration: registration)
            continuation.onTermination = { _ in
                box.registration.remove()
            }
        }
    }

    func fetchMyProgress() async -> BadgeCounters? {
        do {
            let data = try await functions.call(Self.getMyProgress, payload: [:])
            guard let map = data as? [String: Any] else { return nil }
            return BadgeProgressResponseParser.parse(map)
        } catch {
            // Any failure (KccFunctionsError, cancellation, an unexpected
            // payload) degrades to "no counters", which the wall renders as
            // goals without bars — the progress bar is a best-effort affordance.
            return nil
        }
    }

    func currentUserId() -> String? {
        Auth.auth().currentUser?.uid
    }

    // MARK: - Mapping

    /// The award document's fields as a plain map, converting the Firestore
    /// `Timestamp` for `awardedAt` into a `Date` so the pure ``Badge`` type
    /// stays Firebase-free (it reads `awardedAt` as a `Date`).
    private static func badgeFields(from document: QueryDocumentSnapshot) -> [String: Any] {
        var fields = document.data()
        if let timestamp = fields["awardedAt"] as? Timestamp {
            fields["awardedAt"] = timestamp.dateValue()
        } else {
            // A non-timestamp value would otherwise be handed to Badge.fromMap
            // as a stray type; drop it so awardedAt cleanly reads as nil.
            fields["awardedAt"] = nil
        }
        return fields
    }

    // MARK: - Factory

    private static let usersCollection = "users"
    private static let badgesCollection = "badges"
    private static let getMyProgress = "badges-getMyProgress"

    private static let cachedLock = NSLock()
    nonisolated(unsafe) private static var cached: FirebaseBadgesRepository?

    /// Returns the process-wide repository when Firebase is configured for
    /// this build, or nil when GoogleService-Info.plist is absent (CI, local
    /// validation builds — see apps/ios/README.md).
    ///
    /// The Firestore emulator seam (`FIREBASE_FIRESTORE_EMULATOR_HOST`) is
    /// applied by ``FirebaseEventsRepository`` when it constructs the shared
    /// `Firestore.firestore()`; the functions emulator seam
    /// (`FIREBASE_FUNCTIONS_EMULATOR_HOST`) is applied by
    /// ``KccFunctionsClient``. This repository reuses both, so no host parsing
    /// is duplicated here.
    static func createIfAvailable() -> BadgesRepository? {
        guard FirebaseApp.app() != nil, let functions = KccFunctionsClient.createIfAvailable() else {
            return nil
        }
        cachedLock.lock()
        defer { cachedLock.unlock() }
        if let cached { return cached }
        let firestore = Firestore.firestore()
        // Firestore is a process-wide singleton shared with the other
        // repositories, whose factories apply the same settings; the host
        // check makes the second application a no-op instead of mutating
        // settings twice (same guard as FirebaseVehiclesRepository).
        if let emulator = FirebaseEmulatorHost.parse(
            ProcessInfo.processInfo.environment["FIREBASE_FIRESTORE_EMULATOR_HOST"]
        ), firestore.settings.host != "\(emulator.host):\(emulator.port)" {
            firestore.useEmulator(withHost: emulator.host, port: emulator.port)
        }
        let repository = FirebaseBadgesRepository(firestore: firestore, functions: functions)
        cached = repository
        return repository
    }
}

/// `ListenerRegistration` is not Sendable, but the stream's `onTermination`
/// closure must be — all it does is remove the listener, which Firestore
/// documents as thread-safe, so the wrapper is sound (same pattern as
/// ``FirebaseEventsRepository``).
private struct ListenerBox: @unchecked Sendable {
    let registration: ListenerRegistration
}
