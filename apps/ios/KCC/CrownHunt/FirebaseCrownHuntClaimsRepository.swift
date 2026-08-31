import FirebaseCore
import FirebaseFirestore
import Foundation

/// ``CrownHuntClaimsRepository`` backed by a bounded, rules-gated Firestore
/// query on the member's own claim attempts.
///
/// Reads `crownHuntClaims where userId == uid orderBy createdAt desc limit N`
/// (the owner-only read grant in firestore.rules — a member may read ONLY their
/// own claims). Shaped to the deployed composite index
/// `crownHuntClaims(userId ASC, createdAt ASC)` (firestore.indexes.json), which
/// serves the newest-first order via a reverse scan, so no new index is needed.
/// One-shot read per subscription; nothing here writes (every claim is written
/// by `crownHunt.submitClaim`). Construction is guarded
/// (``createIfAvailable()`` returns nil without Firebase).
final class FirebaseCrownHuntClaimsRepository: CrownHuntClaimsRepository, @unchecked Sendable {
    private let firestore: Firestore

    private init(firestore: Firestore) {
        self.firestore = firestore
    }

    func claims(uid: String) -> AsyncStream<CrownClaimsSnapshot> {
        let query = firestore
            .collection(Self.claimsCollection)
            .whereField(Self.userIdField, isEqualTo: uid)
            .order(by: Self.createdAtField, descending: true)
            .limit(to: CrownHuntClaims.queryLimit)
        return AsyncStream { continuation in
            let task = Task {
                do {
                    let snapshot = try await query.getDocuments()
                    let claims = snapshot.documents.compactMap(Self.claim(from:))
                    continuation.yield(.loaded(claims))
                } catch {
                    // Bare status name only — never the exception text, which
                    // embeds the failing query and the project id.
                    continuation.yield(.failed(code: CrownHuntFirestore.statusName(error)))
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    /// Defensive claim mapping — a doc without a usable result is dropped
    /// (schema drift stays visible rather than rendering an unknown outcome).
    /// `claimedAt` falls back to `createdAt` so a row always has a timestamp to
    /// sort/label by, and `pointsAwarded` is carried only for an `awarded` row.
    static func claim(from document: DocumentSnapshot) -> CrownHuntClaim? {
        guard document.exists,
            let result = CrownHuntClaimResult.fromWire(document.get(resultField) as? String)
        else { return nil }
        let claimedAt =
            (document.get(claimedAtField) as? Timestamp)?.dateValue()
            ?? (document.get(createdAtField) as? Timestamp)?.dateValue()
        return CrownHuntClaim(
            id: document.documentID,
            pointId: document.get(pointIdField) as? String ?? "",
            result: result,
            claimedAt: claimedAt,
            pointsAwarded: (document.get(pointsAwardedField) as? NSNumber)?.intValue
        )
    }

    // MARK: - Factory

    private static let claimsCollection = "crownHuntClaims"
    private static let userIdField = "userId"
    private static let createdAtField = "createdAt"
    private static let claimedAtField = "claimedAt"
    private static let resultField = "result"
    private static let pointIdField = "pointId"
    private static let pointsAwardedField = "pointsAwarded"

    private static let cachedLock = NSLock()
    nonisolated(unsafe) private static var cached: FirebaseCrownHuntClaimsRepository?

    /// A claims repository, or nil in a config-less/CI build (no Firebase).
    /// Honors the `FIREBASE_FIRESTORE_EMULATOR_HOST` seam.
    static func createIfAvailable() -> CrownHuntClaimsRepository? {
        guard FirebaseApp.app() != nil else { return nil }
        cachedLock.lock()
        defer { cachedLock.unlock() }
        if let cached { return cached }
        let repository = FirebaseCrownHuntClaimsRepository(firestore: CrownHuntFirebase.firestore())
        cached = repository
        return repository
    }
}
