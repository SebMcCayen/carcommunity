import FirebaseAuth
import FirebaseCore
import FirebaseFirestore
import FirebaseStorage
import Foundation

/// ``LeaderboardRepository`` backed by a single rules-gated Firestore listener
/// on the precomputed `leaderboards/{scope}` document — the iOS port of
/// Android's `FirebaseLeaderboardRepository.kt`.
///
/// The scope is resolved to a document id by
/// ``LeaderboardBoard/scopeDocId(_:seasonId:)``: the all-time board is the
/// fixed `alltime` id, the monthly board is the current Europe/Stockholm
/// `YYYY-MM` season id from ``LeaderboardSeasonClock`` — the exact month the
/// backend generator writes. Nothing here writes; the read rule
/// (firebase/firestore.rules `leaderboards/{scope}` → `allow read: if
/// isActiveMember()`) is the whole security surface, and the pure fold in
/// ``LeaderboardBoard`` turns the document map into the UI model, so this class
/// only extracts raw rows and forwards listener lifecycle.
///
/// A MISSING document (a month with no board yet, or the very first run) is a
/// valid EMPTY board, not an error: every category renders its friendly empty
/// state, exactly as Android does. Only a genuine listener error surfaces as
/// ``LeaderboardSnapshot/failed(code:)``, carrying the bare Firestore status
/// name (never the exception text, which can embed the path and the project
/// id).
///
/// Construction is guarded (``createIfAvailable()`` returns nil without
/// Firebase config), mirroring ``FirebaseEventsRepository`` and Android's
/// `createIfAvailable`.
final class FirebaseLeaderboardRepository: LeaderboardRepository, @unchecked Sendable {
    private let firestore: Firestore
    private let storage: Storage
    private let seasonId: @Sendable () -> String

    private init(
        firestore: Firestore,
        storage: Storage,
        seasonId: @escaping @Sendable () -> String
    ) {
        self.firestore = firestore
        self.storage = storage
        self.seasonId = seasonId
    }

    func observeBoard(scope: LeaderboardScope, viewerUid: String?) -> AsyncStream<LeaderboardSnapshot> {
        // Lazy: the season id (a `Date()` + format) is resolved ONLY for the
        // monthly scope — the all-time board's id is fixed.
        let docId = LeaderboardBoard.scopeDocId(scope, seasonId: seasonId())
        let document = firestore.collection(Self.collection).document(docId)
        return AsyncStream { continuation in
            let registration = document.addSnapshotListener { snapshot, error in
                if let error {
                    // Bare status name only — see LeaderboardSnapshot.failed
                    // and Android's firestoreCode().
                    continuation.yield(.failed(code: Self.firestoreStatusName(error)))
                    return
                }
                // A missing document is a valid EMPTY board (not an error):
                // LeaderboardBoard.board fills every category with an empty
                // list, so the screen shows its per-category empty state.
                let raw = Self.rawCategories(from: snapshot)
                continuation.yield(
                    .loaded(
                        LeaderboardBoard.board(
                            scope: scope,
                            rawByCategory: raw,
                            viewerUid: viewerUid
                        )
                    )
                )
            }
            let box = ListenerBox(registration: registration)
            continuation.onTermination = { _ in
                box.registration.remove()
            }
        }
    }

    func avatarDownloadURL(for avatarPath: String) async -> URL? {
        try? await storage.reference(withPath: avatarPath).downloadURL()
    }

    func currentUserId() -> String? {
        Auth.auth().currentUser?.uid
    }

    // MARK: - Mapping

    /// Extracts the per-category raw rows from a `leaderboards/{scope}`
    /// snapshot — Android's `rawCategories()`. Reads the document's
    /// `categories` map; each value is an ordered array of row maps
    /// `{ rank, uid, displayName, avatarPath, value }`. Only a row missing its
    /// `uid` is dropped HERE — the one field with no safe fallback (a row that
    /// cannot be keyed to a member); a blank `displayName` is passed through
    /// as-is and resolved by ``LeaderboardBoard`` (uid-stub fallback), so the
    /// two layers agree rather than the repository silently discarding what the
    /// pure fold would have shown. A missing/invalid `rank` is read as 0 and
    /// dropped downstream by ``LeaderboardBoard/board(scope:rawByCategory:viewerUid:)``
    /// (which requires a positive rank), keeping the "what makes a row
    /// renderable" rule in one, unit-tested place. Order is preserved verbatim;
    /// the server has already ranked each array.
    static func rawCategories(from snapshot: DocumentSnapshot?) -> [String: [RawLeaderboardRow]] {
        guard let snapshot, snapshot.exists else { return [:] }
        return rawCategories(fromCategoriesMap: snapshot.get(categoriesField) as? [String: Any])
    }

    /// The Firebase-free core of ``rawCategories(from:)`` — the `categories`
    /// map (already extracted from the snapshot) folded into raw rows. Split
    /// out so the tolerant decoding is unit-tested with plain dictionaries, no
    /// Firestore snapshot required (``LeaderboardModelTests``). A non-array
    /// category value is skipped; each row is decoded by ``rawRow(from:)``.
    static func rawCategories(fromCategoriesMap categories: [String: Any]?) -> [String: [RawLeaderboardRow]] {
        guard let categories else { return [:] }
        var result: [String: [RawLeaderboardRow]] = [:]
        for (key, value) in categories {
            guard let rows = value as? [Any] else { continue }
            result[key] = rows.compactMap { rawRow(from: $0) }
        }
        return result
    }

    /// One row map → ``RawLeaderboardRow``, or nil when the row is not a map or
    /// carries no usable `uid`. Every other field degrades defensively
    /// (missing `rank`/`value` → 0, missing `displayName` → "", empty
    /// `avatarPath` → nil).
    static func rawRow(from raw: Any) -> RawLeaderboardRow? {
        guard let row = raw as? [String: Any],
            let uid = (row["uid"] as? String), !uid.isEmpty
        else { return nil }
        let avatarPath = (row["avatarPath"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        return RawLeaderboardRow(
            rank: (row["rank"] as? NSNumber)?.intValue ?? 0,
            uid: uid,
            displayName: (row["displayName"] as? String) ?? "",
            avatarPath: avatarPath,
            value: (row["value"] as? NSNumber)?.doubleValue ?? 0
        )
    }

    /// The bare Firestore status name (`PERMISSION_DENIED`, `UNAVAILABLE`, …)
    /// for a listener error, or nil when the failure carries no Firestore code
    /// — the same PII-safe mapping as ``FirebaseEventsRepository``. A status
    /// name is the whole diagnosis and leaks nothing.
    static func firestoreStatusName(_ error: Error) -> String? {
        let nsError = error as NSError
        guard nsError.domain == FirestoreErrorDomain,
            let code = FirestoreErrorCode.Code(rawValue: nsError.code)
        else { return nil }
        switch code {
        case .OK: return "OK"
        case .cancelled: return "CANCELLED"
        case .unknown: return "UNKNOWN"
        case .invalidArgument: return "INVALID_ARGUMENT"
        case .deadlineExceeded: return "DEADLINE_EXCEEDED"
        case .notFound: return "NOT_FOUND"
        case .alreadyExists: return "ALREADY_EXISTS"
        case .permissionDenied: return "PERMISSION_DENIED"
        case .resourceExhausted: return "RESOURCE_EXHAUSTED"
        case .failedPrecondition: return "FAILED_PRECONDITION"
        case .aborted: return "ABORTED"
        case .outOfRange: return "OUT_OF_RANGE"
        case .unimplemented: return "UNIMPLEMENTED"
        case .internal: return "INTERNAL"
        case .unavailable: return "UNAVAILABLE"
        case .dataLoss: return "DATA_LOSS"
        case .unauthenticated: return "UNAUTHENTICATED"
        @unknown default: return nil
        }
    }

    // MARK: - Factory

    private static let collection = "leaderboards"
    private static let categoriesField = "categories"

    private static let cachedLock = NSLock()
    nonisolated(unsafe) private static var cached: FirebaseLeaderboardRepository?

    /// Returns the process-wide repository when Firebase is configured for this
    /// build, or nil when GoogleService-Info.plist is absent (CI, local
    /// validation builds — see apps/ios/README.md); the screen then shows its
    /// loading/unavailable affordance rather than crashing, exactly as the
    /// events and profile surfaces degrade.
    ///
    /// Emulator seams follow the shared `FIREBASE_*_EMULATOR_HOST` convention:
    /// `FIREBASE_FIRESTORE_EMULATOR_HOST` (8080) for the board listener and
    /// `FIREBASE_STORAGE_EMULATOR_HOST` (9199) for avatar URL resolution —
    /// ports per firebase.json. Firestore is a process-wide singleton shared
    /// with ``FirebaseEventsRepository``, whose factory applies the same
    /// emulator settings; the host check makes a second application a no-op
    /// instead of mutating settings twice.
    static func createIfAvailable() -> LeaderboardRepository? {
        guard FirebaseApp.app() != nil else { return nil }
        cachedLock.lock()
        defer { cachedLock.unlock() }
        if let cached { return cached }
        let firestore = Firestore.firestore()
        if let emulator = FirebaseEmulatorHost.parse(
            ProcessInfo.processInfo.environment["FIREBASE_FIRESTORE_EMULATOR_HOST"]
        ), firestore.settings.host != "\(emulator.host):\(emulator.port)" {
            firestore.useEmulator(withHost: emulator.host, port: emulator.port)
        }
        let storage = Storage.storage()
        if let emulator = FirebaseEmulatorHost.parse(
            ProcessInfo.processInfo.environment["FIREBASE_STORAGE_EMULATOR_HOST"]
        ) {
            storage.useEmulator(withHost: emulator.host, port: emulator.port)
        }
        let repository = FirebaseLeaderboardRepository(
            firestore: firestore,
            storage: storage,
            seasonId: { LeaderboardSeasonClock.seasonId() }
        )
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
